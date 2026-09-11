/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.visibility;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sn68.agent.dataagent.authorization.legacy.LegacyVisibilityPolicyProvider;
import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.dto.visibility.AgentUserCatalogResp;
import com.sn68.agent.dataagent.dto.visibility.AgentUserCatalogPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityAgentOptionResp;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityAgentOptionPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationAuditReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationCreateReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityGrantCreateReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityGrantPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityPolicyReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityApplication;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityGrant;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityApplicationMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityGrantMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityPolicyMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * DataAgent可见性服务组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAgentVisibilityServiceImpl implements DataAgentVisibilityService {

	private final DataAgentService dataAgentService;

	private final DataAgentMapper dataAgentMapper;

	private final DataAgentVisibilityPolicyMapper policyMapper;

	private final DataAgentVisibilityGrantMapper grantMapper;

	private final DataAgentVisibilityApplicationMapper applicationMapper;

	private final AuthenticationContext authenticationContext;

	private final List<AgentVisibilityApprovalAdapter> approvalAdapters;

	/** PR-4 写路径冻结开关载体（freezeLegacyVisibilityWrites）：旧可见性写入口统一拒写断言，读判定不受影响。 */
	private final LegacyVisibilityPolicyProvider legacyVisibilityPolicyProvider;

	/**
	 * 查询当前登录用户工作台可见的已发布 Agent 列表（按可见性策略过滤）。
	 */
	@Override
	public List<DataAgent> listUserWorkbenchAgents() {
		requireLogin();
		List<DataAgent> publishedAgents = dataAgentService.findByStatus(AgentStatusConstant.PUBLISHED);
		return publishedAgents.stream()
			.filter(agent -> agent != null && canVisibleInUserWorkbench(agent))
			.toList();
	}

	/**
	 * 分页查询当前用户的 Agent 目录：含可见 Agent 与可申请 Agent 的访问状态标记。
	 */
	@Override
	public IPage<AgentUserCatalogResp> queryUserCatalogPage(AgentUserCatalogPageQueryReq request) {
		requireLogin();
		AgentUserCatalogPageQueryReq query = request == null ? new AgentUserCatalogPageQueryReq() : request;
		List<AgentUserCatalogResp> records = dataAgentService.findByStatus(AgentStatusConstant.PUBLISHED)
			.stream()
			.filter(agent -> matchesKeyword(agent, query.getKeyword()))
			.map(this::toCatalogDTO)
			.filter(item -> item != null && canShowCatalogItem(item))
			.filter(item -> !StringUtils.hasText(query.getStatus())
					|| query.getStatus().trim().equalsIgnoreCase(item.getVisibilityStatus()))
			.sorted(Comparator.comparing(item -> Optional.ofNullable(item.getAgent())
				.map(DataAgent::getCreateTime)
				.orElse(Instant.EPOCH), Comparator.reverseOrder()))
			.toList();
		return pageInMemory(query, records);
	}

	/**
	 * 提交可见性申请：申请制 Agent 才可申请，事务内落申请记录并按需触发审批流。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentVisibilityApplication createApplication(Long agentId,
			AgentVisibilityApplicationCreateReq request) {
		requireLogin();
		legacyVisibilityPolicyProvider.assertWritesNotFrozen();
		DataAgent agent = requirePublishedAgent(agentId);
		if (canVisibleInUserWorkbench(agent)) {
			throw CheckedException.badRequest("当前用户已可见该 Agent");
		}
		DataAgentVisibilityPolicy policy = getPolicy(agentId);
		if (!AgentVisibilityConstant.POLICY_STATUS_ENABLED.equals(policy.getStatus())) {
			throw CheckedException.badRequest("当前 Agent 可见性策略未启用");
		}
		if (AgentVisibilityConstant.APPLY_MODE_DISABLED.equals(policy.getApplyMode())) {
			throw CheckedException.badRequest("当前 Agent 不支持申请");
		}
		DataAgentVisibilityApplication pending = applicationMapper.findPending(agentId, authenticationContext.userId());
		if (pending != null) {
			throw CheckedException.badRequest("当前 Agent 已存在审批中的申请");
		}
		Instant now = Instant.now();
		DataAgentVisibilityApplication application = DataAgentVisibilityApplication.builder()
			.agentId(agent.getId())
			.agentName(agent.getName())
			.applicantUserId(authenticationContext.userId())
			.applicantNickName(authenticationContext.nickName())
			.reason(trim(request == null ? null : request.getReason()))
			.grantDays(resolveGrantDays(request == null ? null : request.getGrantDays(), policy))
			.status(AgentVisibilityConstant.APPLICATION_STATUS_PENDING)
			.approvalMode(policy.getApprovalMode())
			.workflowFlowCode(resolveWorkflowFlowCode(policy.getWorkflowFlowCode()))
			.submitTime(now)
			.build();
		applicationMapper.insert(application);
		if (AgentVisibilityConstant.APPLY_MODE_AUTO_APPROVE.equals(policy.getApplyMode())) {
			approveApplicationDirectly(application, "自动通过", application.getGrantDays());
			return applicationMapper.selectById(application.getId());
		}
		adapter(policy.getApprovalMode()).submit(application, policy);
		return applicationMapper.selectById(application.getId());
	}

	/**
	 * 分页查询当前用户提交的可见性申请记录。
	 */
	@Override
	public IPage<DataAgentVisibilityApplication> queryMyApplicationsPage(
			AgentVisibilityApplicationPageQueryReq request) {
		requireLogin();
		AgentVisibilityApplicationPageQueryReq query = request == null
				? new AgentVisibilityApplicationPageQueryReq() : request;
		query.setApplicantUserId(authenticationContext.userId());
		return applicationMapper.selectApplicationPage(query.buildPage(), query);
	}

	@Override
	public DataAgentVisibilityPolicy getPolicy(Long agentId) {
		DataAgentVisibilityPolicy existing = policyMapper.findByAgentId(agentId);
		if (existing != null) {
			normalizePolicy(existing);
			return existing;
		}
		return defaultPolicy(agentId);
	}

	/**
	 * 修改指定 Agent 的可见性策略（公开/申请制/私有），事务内同步策略生效。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentVisibilityPolicy modifyPolicy(Long agentId, AgentVisibilityPolicyReq request) {
		legacyVisibilityPolicyProvider.assertWritesNotFrozen();
		dataAgentService.requireAgent(agentId);
		if (request == null) {
			throw CheckedException.badRequest("可见性策略配置不能为空");
		}
		DataAgentVisibilityPolicy policy = policyMapper.findByAgentId(agentId);
		if (policy == null) {
			policy = defaultPolicy(agentId);
			applyPolicyRequest(policy, request);
			policyMapper.insert(policy);
			return policyMapper.selectById(policy.getId());
		}
		applyPolicyRequest(policy, request);
		policyMapper.updateById(policy);
		return policyMapper.selectById(policy.getId());
	}

	/**
	 * 分页查询指定 Agent 已授予的可见性授权记录。
	 */
	@Override
	public IPage<DataAgentVisibilityGrant> queryGrantsPage(Long agentId, AgentVisibilityGrantPageQueryReq request) {
		dataAgentService.requireAgent(agentId);
		AgentVisibilityGrantPageQueryReq query = request == null ? new AgentVisibilityGrantPageQueryReq()
				: request;
		return grantMapper.selectGrantPage(agentId, query.buildPage(), query);
	}

	/**
	 * 管理员直接授予可见性：事务内写入授权记录（已存在则幂等复用）。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentVisibilityGrant createGrant(Long agentId, AgentVisibilityGrantCreateReq request) {
		legacyVisibilityPolicyProvider.assertWritesNotFrozen();
		DataAgent agent = dataAgentService.requireAgent(agentId);
		if (request == null) {
			throw CheckedException.badRequest("授权配置不能为空");
		}
		String subjectType = normalizeRequired(request.getSubjectType(), AgentVisibilityConstant.SUBJECT_TYPES, "授权主体类型不合法");
		String subjectId = requiredText(request.getSubjectId(), "授权主体ID不能为空");
		DataAgentVisibilityGrant existing = grantMapper.findActive(agentId, subjectType, subjectId);
		if (existing != null) {
			return existing;
		}
		retireExpiredActiveGrant(agentId, subjectType, subjectId);
		DataAgentVisibilityGrant grant = DataAgentVisibilityGrant.builder()
			.agentId(agent.getId())
			.agentName(agent.getName())
			.subjectType(subjectType)
			.subjectId(subjectId)
			.subjectName(resolveSubjectName(subjectType, subjectId, request.getSubjectName()))
			.sourceType(AgentVisibilityConstant.GRANT_SOURCE_MANUAL)
			.expireTime(request.getExpireTime())
			.status(AgentVisibilityConstant.GRANT_STATUS_ACTIVE)
			.build();
		try {
			grantMapper.insert(grant);
		}
		catch (DuplicateKeyException ex) {
			DataAgentVisibilityGrant duplicate = grantMapper.findActive(agentId, subjectType, subjectId);
			if (duplicate != null) {
				return duplicate;
			}
			throw ex;
		}
		return grantMapper.selectById(grant.getId());
	}

	/**
	 * 撤销可见性授权：事务内删除授权记录。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteGrant(Long id) {
		legacyVisibilityPolicyProvider.assertWritesNotFrozen();
		DataAgentVisibilityGrant grant = grantMapper.selectById(id);
		if (grant == null || Boolean.TRUE.equals(grant.getDeleted())) {
			throw CheckedException.notFound("Agent 可见性授权不存在");
		}
		grant.setStatus(AgentVisibilityConstant.GRANT_STATUS_REVOKED);
		grant.setDeleted(true);
		grantMapper.updateById(grant);
	}

	/**
	 * 分页查询待审批/已审批的可见性申请（管理侧视角）。
	 */
	@Override
	public IPage<DataAgentVisibilityApplication> queryApplicationsPage(
			AgentVisibilityApplicationPageQueryReq request) {
		AgentVisibilityApplicationPageQueryReq query = request == null
				? new AgentVisibilityApplicationPageQueryReq() : request;
		return applicationMapper.selectApplicationPage(query.buildPage(), query);
	}

	/**
	 * 分页查询可发起可见性申请的 Agent 选项列表。
	 */
	@Override
	public IPage<AgentVisibilityAgentOptionResp> queryApplicationAgentOptionsPage(
			AgentVisibilityAgentOptionPageQueryReq request) {
		AgentVisibilityAgentOptionPageQueryReq query = request == null
				? new AgentVisibilityAgentOptionPageQueryReq() : request;
		IPage<DataAgent> agentPage = dataAgentMapper.selectVisibilityAgentOptionPage(query.buildPage(), query,
				safeTenantId());
		Page<AgentVisibilityAgentOptionResp> page = new Page<>(agentPage.getCurrent(), agentPage.getSize(),
				agentPage.getTotal());
		page.setRecords(agentPage.getRecords().stream().map(this::toAgentOptionDTO).toList());
		return page;
	}

	/**
	 * 通过可见性申请：走本地审批时事务内落授权并更新申请状态；工作流接管时拒绝本地操作。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentVisibilityApplication approveApplication(Long id, AgentVisibilityApplicationAuditReq request) {
		legacyVisibilityPolicyProvider.assertWritesNotFrozen();
		DataAgentVisibilityApplication application = requirePendingApplication(id);
		rejectWorkflowLocalAudit(application);
		adapter(application.getApprovalMode()).approve(application, request);
		approveApplicationDirectly(application, request == null ? null : request.getApprovalComment(),
				request == null ? null : request.getGrantDays());
		return applicationMapper.selectById(id);
	}

	/**
	 * 驳回可见性申请：仅允许本地审批链路，事务内更新申请状态与驳回原因。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentVisibilityApplication rejectApplication(Long id, AgentVisibilityApplicationAuditReq request) {
		legacyVisibilityPolicyProvider.assertWritesNotFrozen();
		DataAgentVisibilityApplication application = requirePendingApplication(id);
		rejectWorkflowLocalAudit(application);
		adapter(application.getApprovalMode()).reject(application, request);
		Instant now = Instant.now();
		application.setStatus(AgentVisibilityConstant.APPLICATION_STATUS_REJECTED);
		application.setApproverUserId(safeCurrentUserId());
		application.setApproverNickName(safeCurrentNickName());
		application.setApprovalComment(trim(request == null ? null : request.getApprovalComment()));
		application.setFinishTime(now);
		applicationMapper.updateById(application);
		return applicationMapper.selectById(id);
	}

	/**
	 * 按已通过的申请落授权记录（工作流回调链路复用）。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public DataAgentVisibilityGrant createGrantByApprovedApplication(DataAgentVisibilityApplication application) {
		legacyVisibilityPolicyProvider.assertWritesNotFrozen();
		return grantApprovedApplication(application);
	}

	/**
	 * 供本类内部调用的建授权实现，避免走自调用绕过 {@code @Transactional} 代理。
	 * @param application the approved application
	 * @return the active grant
	 */
	private DataAgentVisibilityGrant grantApprovedApplication(DataAgentVisibilityApplication application) {
		if (application == null || application.getId() == null) {
			throw CheckedException.badRequest("Agent 可见性申请单不存在");
		}
		DataAgentVisibilityGrant existing = grantMapper.findActive(application.getAgentId(),
				AgentVisibilityConstant.SUBJECT_TYPE_USER, application.getApplicantUserId());
		if (existing != null) {
			return existing;
		}
		retireExpiredActiveGrant(application.getAgentId(), AgentVisibilityConstant.SUBJECT_TYPE_USER,
				application.getApplicantUserId());
		DataAgentVisibilityGrant grant = DataAgentVisibilityGrant.builder()
			.agentId(application.getAgentId())
			.agentName(application.getAgentName())
			.subjectType(AgentVisibilityConstant.SUBJECT_TYPE_USER)
			.subjectId(application.getApplicantUserId())
			.subjectName(application.getApplicantNickName())
			.sourceType(AgentVisibilityConstant.GRANT_SOURCE_APPLICATION)
			.applicationId(application.getId())
			.expireTime(resolveExpireTime(application.getGrantDays()))
			.status(AgentVisibilityConstant.GRANT_STATUS_ACTIVE)
			.build();
		try {
			grantMapper.insert(grant);
		}
		catch (DuplicateKeyException ex) {
			DataAgentVisibilityGrant duplicate = grantMapper.findActive(application.getAgentId(),
					AgentVisibilityConstant.SUBJECT_TYPE_USER, application.getApplicantUserId());
			if (duplicate != null) {
				return duplicate;
			}
			throw ex;
		}
		return grantMapper.selectById(grant.getId());
	}

	/**
	 * 判断指定 Agent 对当前用户工作台是否可见（状态 + 可见性策略）。
	 */
	@Override
	public boolean canVisibleInUserWorkbench(Long agentId) {
		DataAgent agent = dataAgentService.findById(agentId);
		return canVisibleInUserWorkbench(agent);
	}

	private boolean canVisibleInUserWorkbench(DataAgent agent) {
		if (agent == null || !AgentStatusConstant.PUBLISHED.equals(agent.getStatus())) {
			return false;
		}
		if (isCurrentAdmin()) {
			return true;
		}
		DataAgentVisibilityPolicy policy = getPolicy(agent.getId());
		if (!AgentVisibilityConstant.POLICY_STATUS_ENABLED.equals(policy.getStatus())) {
			return false;
		}
		if (hasMatchedActiveGrant(agent.getId())) {
			return true;
		}
		return switch (policy.getConversationScope()) {
			case AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT -> true;
			case AgentVisibilityConstant.CONVERSATION_SCOPE_TEAM -> hasMatchedTeamGrant(agent.getId());
			case AgentVisibilityConstant.CONVERSATION_SCOPE_PERMISSION -> hasMatchedPermissionGrant(agent.getId());
			default -> false;
		};
	}

	private AgentUserCatalogResp toCatalogDTO(DataAgent agent) {
		DataAgentVisibilityPolicy policy = getPolicy(agent.getId());
		DataAgentVisibilityApplication pending = applicationMapper.findPending(agent.getId(), safeCurrentUserId());
		boolean visible = canVisibleInUserWorkbench(agent);
		boolean catalogVisible = canListInCatalog(agent, policy, visible);
		if (!visible && !catalogVisible && pending == null) {
			return null;
		}
		String status;
		boolean canApply;
		String disabledReason = null;
		if (visible) {
			status = AgentVisibilityConstant.CATALOG_STATUS_VISIBLE;
			canApply = false;
		}
		else if (pending != null) {
			status = AgentVisibilityConstant.CATALOG_STATUS_PENDING;
			canApply = false;
		}
		else if (AgentVisibilityConstant.APPLY_MODE_DISABLED.equals(policy.getApplyMode())) {
			status = AgentVisibilityConstant.CATALOG_STATUS_NOT_APPLYABLE;
			canApply = false;
			disabledReason = "当前 Agent 不支持申请";
		}
		else {
			status = AgentVisibilityConstant.CATALOG_STATUS_APPLYABLE;
			canApply = true;
		}
		return AgentUserCatalogResp.builder()
			.agent(agent)
			.visibilityStatus(status)
			.applyMode(policy.getApplyMode())
			.approvalMode(policy.getApprovalMode())
			.canApply(canApply)
			.disabledReason(disabledReason)
			.pendingApplicationId(pending == null ? null : pending.getId())
			.build();
	}

	private boolean canListInCatalog(DataAgent agent, DataAgentVisibilityPolicy policy, boolean visible) {
		if (visible) {
			return true;
		}
		if (policy == null || !AgentVisibilityConstant.POLICY_STATUS_ENABLED.equals(policy.getStatus())) {
			return false;
		}
		return switch (policy.getCatalogScope()) {
			case AgentVisibilityConstant.CATALOG_SCOPE_TENANT -> true;
			case AgentVisibilityConstant.CATALOG_SCOPE_TEAM -> hasMatchedTeamGrant(agent.getId());
			case AgentVisibilityConstant.CATALOG_SCOPE_PERMISSION -> hasMatchedPermissionGrant(agent.getId());
			default -> false;
		};
	}

	private boolean canShowCatalogItem(AgentUserCatalogResp item) {
		return item != null && item.getAgent() != null && item.getVisibilityStatus() != null;
	}

	private boolean hasMatchedActiveGrant(Long agentId) {
		if (!StringUtils.hasText(safeCurrentUserId())) {
			return false;
		}
		List<DataAgentVisibilityGrant> grants = grantMapper.listActiveByAgentId(agentId);
		return grants.stream().anyMatch(this::matchesCurrentUser);
	}

	private boolean hasMatchedTeamGrant(Long agentId) {
		List<String> teamIds = safeTeamIds();
		if (teamIds.isEmpty()) {
			return false;
		}
		return grantMapper.listActiveByAgentId(agentId)
			.stream()
			.anyMatch(grant -> AgentVisibilityConstant.SUBJECT_TYPE_TEAM.equals(grant.getSubjectType())
					&& teamIds.contains(grant.getSubjectId()));
	}

	private boolean hasMatchedPermissionGrant(Long agentId) {
		List<String> permissions = safeFuncPermissions();
		if (permissions.isEmpty()) {
			return false;
		}
		return grantMapper.listActiveByAgentId(agentId)
			.stream()
			.anyMatch(grant -> AgentVisibilityConstant.SUBJECT_TYPE_PERMISSION.equals(grant.getSubjectType())
					&& permissions.contains(grant.getSubjectId()));
	}

	private boolean matchesCurrentUser(DataAgentVisibilityGrant grant) {
		if (grant == null || !AgentVisibilityConstant.GRANT_STATUS_ACTIVE.equals(grant.getStatus())) {
			return false;
		}
		return switch (grant.getSubjectType()) {
			case AgentVisibilityConstant.SUBJECT_TYPE_USER -> Objects.equals(grant.getSubjectId(), safeCurrentUserId());
			case AgentVisibilityConstant.SUBJECT_TYPE_TENANT -> Objects.equals(grant.getSubjectId(), safeTenantId());
			case AgentVisibilityConstant.SUBJECT_TYPE_TEAM -> safeTeamIds().contains(grant.getSubjectId());
			case AgentVisibilityConstant.SUBJECT_TYPE_PERMISSION -> safeFuncPermissions().contains(grant.getSubjectId());
			default -> false;
		};
	}

	private AgentVisibilityAgentOptionResp toAgentOptionDTO(DataAgent agent) {
		return AgentVisibilityAgentOptionResp.builder()
			.id(agent.getId())
			.name(agent.getName())
			.status(agent.getStatus())
			.build();
	}

	private DataAgentVisibilityApplication requirePendingApplication(Long id) {
		DataAgentVisibilityApplication application = applicationMapper.selectByIdForUpdate(id);
		if (application == null || Boolean.TRUE.equals(application.getDeleted())) {
			throw CheckedException.notFound("Agent 可见性申请不存在");
		}
		if (!AgentVisibilityConstant.APPLICATION_STATUS_PENDING.equals(application.getStatus())) {
			throw CheckedException.badRequest("当前申请已处理，请刷新后查看最新状态");
		}
		return application;
	}

	private void rejectWorkflowLocalAudit(DataAgentVisibilityApplication application) {
		if (application != null
				&& AgentVisibilityConstant.APPROVAL_MODE_WORKFLOW.equals(application.getApprovalMode())) {
			throw CheckedException.badRequest("流程审批申请请前往流程中心处理");
		}
	}

	private void approveApplicationDirectly(DataAgentVisibilityApplication application, String comment, Integer grantDays) {
		Instant now = Instant.now();
		Integer resolvedGrantDays = grantDays == null ? application.getGrantDays() : grantDays;
		application.setGrantDays(resolvedGrantDays);
		application.setApproverUserId(firstText(application.getApproverUserId(), safeCurrentUserId()));
		application.setApproverNickName(firstText(application.getApproverNickName(), safeCurrentNickName()));
		application.setApprovalComment(trim(comment));
		application.setFinishTime(now);
		// 先建授权再置 APPROVED：授权失败时申请单仍停留在 PENDING，
		// MQ 重投能穿过 handleWorkflowCallback 的「非 PENDING 直接 return」守卫完成补偿。
		grantApprovedApplication(application);
		application.setStatus(AgentVisibilityConstant.APPLICATION_STATUS_APPROVED);
		applicationMapper.updateById(application);
	}

	private void retireExpiredActiveGrant(Long agentId, String subjectType, String subjectId) {
		DataAgentVisibilityGrant grant = grantMapper.findActiveStatus(agentId, subjectType, subjectId);
		if (grant == null || grant.getExpireTime() == null || grant.getExpireTime().isAfter(Instant.now())) {
			return;
		}
		grant.setStatus(AgentVisibilityConstant.GRANT_STATUS_REVOKED);
		grant.setDeleted(true);
		grantMapper.updateById(grant);
	}

	private DataAgent requirePublishedAgent(Long agentId) {
		DataAgent agent = dataAgentService.requireAgent(agentId);
		if (!AgentStatusConstant.PUBLISHED.equals(agent.getStatus())) {
			throw CheckedException.badRequest("仅已发布 Agent 支持用户可见性申请");
		}
		return agent;
	}

	private DataAgentVisibilityPolicy defaultPolicy(Long agentId) {
		return DataAgentVisibilityPolicy.builder()
			.agentId(agentId)
			.conversationScope(AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT)
			.catalogScope(AgentVisibilityConstant.CATALOG_SCOPE_TENANT)
			.applyMode(AgentVisibilityConstant.APPLY_MODE_DISABLED)
			.approvalMode(AgentVisibilityConstant.APPROVAL_MODE_LOCAL)
			.workflowFlowCode(AgentVisibilityConstant.DEFAULT_WORKFLOW_FLOW_CODE)
			.riskLevel("NORMAL")
			.defaultGrantDays(null)
			.status(AgentVisibilityConstant.POLICY_STATUS_ENABLED)
			.build();
	}

	private void applyPolicyRequest(DataAgentVisibilityPolicy policy, AgentVisibilityPolicyReq request) {
		policy.setConversationScope(normalizeRequired(request.getConversationScope(),
				AgentVisibilityConstant.CONVERSATION_SCOPES, "对话可见范围不合法"));
		policy.setCatalogScope(normalizeRequired(request.getCatalogScope(), AgentVisibilityConstant.CATALOG_SCOPES,
				"目录可见范围不合法"));
		policy.setApplyMode(normalizeRequired(request.getApplyMode(), AgentVisibilityConstant.APPLY_MODES,
				"申请模式不合法"));
		policy.setApprovalMode(normalizeRequired(request.getApprovalMode(), AgentVisibilityConstant.APPROVAL_MODES,
				"审批模式不合法"));
		policy.setWorkflowFlowCode(resolveWorkflowFlowCode(request.getWorkflowFlowCode()));
		policy.setRiskLevel(firstText(request.getRiskLevel(), "NORMAL"));
		policy.setDefaultGrantDays(request.getDefaultGrantDays());
		policy.setApproverConfigJson(trim(request.getApproverConfigJson()));
		policy.setStatus(normalizeStatus(request.getStatus()));
	}

	private void normalizePolicy(DataAgentVisibilityPolicy policy) {
		if (!StringUtils.hasText(policy.getConversationScope())) {
			policy.setConversationScope(AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT);
		}
		if (!StringUtils.hasText(policy.getCatalogScope())) {
			policy.setCatalogScope(AgentVisibilityConstant.CATALOG_SCOPE_TENANT);
		}
		if (!StringUtils.hasText(policy.getApplyMode())) {
			policy.setApplyMode(AgentVisibilityConstant.APPLY_MODE_DISABLED);
		}
		if (!StringUtils.hasText(policy.getApprovalMode())) {
			policy.setApprovalMode(AgentVisibilityConstant.APPROVAL_MODE_LOCAL);
		}
		if (!StringUtils.hasText(policy.getWorkflowFlowCode())) {
			policy.setWorkflowFlowCode(AgentVisibilityConstant.DEFAULT_WORKFLOW_FLOW_CODE);
		}
		if (!StringUtils.hasText(policy.getStatus())) {
			policy.setStatus(AgentVisibilityConstant.POLICY_STATUS_ENABLED);
		}
	}

	private AgentVisibilityApprovalAdapter adapter(String mode) {
		String normalizedMode = normalizeRequired(mode, AgentVisibilityConstant.APPROVAL_MODES, "审批模式不合法");
		return approvalAdapters.stream()
			.filter(item -> normalizedMode.equals(item.mode()))
			.findFirst()
			.orElseThrow(() -> CheckedException.badRequest("审批模式未启用: " + normalizedMode));
	}

	private boolean matchesKeyword(DataAgent agent, String keyword) {
		if (agent == null || !StringUtils.hasText(keyword)) {
			return true;
		}
		String normalized = keyword.trim().toLowerCase();
		return contains(agent.getName(), normalized) || contains(agent.getDescription(), normalized)
				|| contains(agent.getTags(), normalized);
	}

	private boolean contains(String value, String keyword) {
		return StringUtils.hasText(value) && value.toLowerCase().contains(keyword);
	}

	private IPage<AgentUserCatalogResp> pageInMemory(AgentUserCatalogPageQueryReq query,
			List<AgentUserCatalogResp> records) {
		int current = Math.max(1, query.getCurrent());
		int size = Math.max(1, query.getSize());
		int from = Math.min((current - 1) * size, records.size());
		int to = Math.min(from + size, records.size());
		Page<AgentUserCatalogResp> page = new Page<>(current, size);
		page.setTotal(records.size());
		page.setRecords(records.subList(from, to));
		return page;
	}

	private Integer resolveGrantDays(Integer requestedGrantDays, DataAgentVisibilityPolicy policy) {
		if (requestedGrantDays != null) {
			return requestedGrantDays;
		}
		return policy == null ? null : policy.getDefaultGrantDays();
	}

	private Instant resolveExpireTime(Integer grantDays) {
		if (grantDays == null || grantDays <= 0) {
			return null;
		}
		return Instant.now().plus(grantDays, ChronoUnit.DAYS);
	}

	private String resolveSubjectName(String subjectType, String subjectId, String requestedSubjectName) {
		String normalizedName = trim(requestedSubjectName);
		if (StringUtils.hasText(normalizedName)) {
			return normalizedName;
		}
		if (AgentVisibilityConstant.SUBJECT_TYPE_USER.equals(subjectType)) {
			return resolveUserNickName(subjectId);
		}
		if (AgentVisibilityConstant.SUBJECT_TYPE_TENANT.equals(subjectType)) {
			return Objects.equals(subjectId, safeTenantId()) ? authenticationContext.tenantName() : subjectId;
		}
		return subjectId;
	}

	private String resolveUserNickName(String userId) {
		return userId;
	}

	private String normalizeRequired(String value, List<String> allowedValues, String message) {
		String normalized = requiredText(value, message).toUpperCase();
		if (!allowedValues.contains(normalized)) {
			throw CheckedException.badRequest(message);
		}
		return normalized;
	}

	private String normalizeStatus(String value) {
		String normalized = firstText(value, AgentVisibilityConstant.POLICY_STATUS_ENABLED).toUpperCase();
		if (!List.of(AgentVisibilityConstant.POLICY_STATUS_ENABLED, AgentVisibilityConstant.POLICY_STATUS_DISABLED)
			.contains(normalized)) {
			throw CheckedException.badRequest("策略状态不合法");
		}
		return normalized;
	}

	private String resolveWorkflowFlowCode(String workflowFlowCode) {
		return firstText(workflowFlowCode, AgentVisibilityConstant.DEFAULT_WORKFLOW_FLOW_CODE);
	}

	private String requiredText(String value, String message) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(message);
		}
		return value.trim();
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private void requireLogin() {
		if (authenticationContext == null || authenticationContext.anonymous()
				|| !StringUtils.hasText(authenticationContext.userId())) {
			throw CheckedException.forbidden();
		}
	}

	private boolean isCurrentAdmin() {
		try {
			return authenticationContext != null && !authenticationContext.anonymous()
					&& authenticationContext.getContext() != null
					&& UserType.isAdmin(authenticationContext.userType());
		}
		catch (Exception ex) {
			log.warn("解析当前登录用户类型失败, 可见性判定按非管理员处理", ex);
			return false;
		}
	}

	private String safeCurrentUserId() {
		try {
			return authenticationContext == null ? null : authenticationContext.userId();
		}
		catch (Exception ex) {
			log.warn("解析当前登录用户失败, 用户维度可见性将判定为不匹配", ex);
			return null;
		}
	}

	private String safeCurrentNickName() {
		try {
			return authenticationContext == null ? null : authenticationContext.nickName();
		}
		catch (Exception ex) {
			log.warn("解析当前登录用户昵称失败, 审批人昵称将留空", ex);
			return null;
		}
	}

	private String safeTenantId() {
		try {
			return authenticationContext == null ? null : authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户失败, 租户维度可见性将判定为不匹配", ex);
			return null;
		}
	}

	private List<String> safeTeamIds() {
		try {
			return authenticationContext == null || authenticationContext.teamIds() == null ? List.of()
					: authenticationContext.teamIds();
		}
		catch (Exception ex) {
			log.warn("解析当前用户团队列表失败, 团队维度可见性将判定为不匹配", ex);
			return List.of();
		}
	}

	private List<String> safeFuncPermissions() {
		try {
			return authenticationContext == null || authenticationContext.funcPermissionList() == null ? List.of()
					: authenticationContext.funcPermissionList();
		}
		catch (Exception ex) {
			log.warn("解析当前用户功能权限失败, 权限维度可见性将判定为不匹配", ex);
			return List.of();
		}
	}

	private Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.parseLong(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
