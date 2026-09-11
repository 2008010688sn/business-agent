/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.employee.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationGrantCreateReq;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantPermission;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantSubjectType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.service.AgentAuthorizationPapService;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeCreateReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeModifyReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeOptionResp;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeePageQueryReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeCapabilityBindReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseCreateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.enums.DeploymentStatusDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeCapabilityMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.employee.service.DigitalEmployeeService;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.employee.service.EmployeeJobTemplateService;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.employee.service.PrincipalProvisioningService;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 数字员工档案服务实现。
 * 框架租户拦截器为白名单制，本服务所有查询显式携带 tenant_id 条件作为租户防线；
 * 启用前置校验 rollout 开关 + Principal READY（未开启/未就绪一律 CheckedException 拒绝）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalEmployeeServiceImpl implements DigitalEmployeeService {

	private static final int OPTIONS_MAX_SIZE = 100;

	private static final String DEFAULT_AUTONOMY_LEVEL = "ASSISTED";

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeReleaseMapper releaseMapper;

	private final DigitalEmployeeDeploymentMapper deploymentMapper;

	private final DigitalEmployeeCapabilityMapper capabilityMapper;

	private final DigitalEmployeeModelConfigMapper modelConfigMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final PrincipalProvisioningService provisioningService;

	private final EmployeeReleaseLifecycleService releaseLifecycleService;

	private final EmployeeDeploymentService deploymentService;

	private final DigitalEmployeeProperties properties;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	private final EmployeeJobTemplateService jobTemplateService;

	private final AgentAuthorizationPapService papService;

	private final TransactionTemplate transactionTemplate;

	@Override
	public IPage<DigitalEmployeeResp> queryPage(DigitalEmployeePageQueryReq request) {
		DigitalEmployeePageQueryReq query = request == null ? new DigitalEmployeePageQueryReq() : request;
		String tenantId = currentTenantId();
		IPage<DigitalEmployee> page = employeeMapper.selectEmployeePage(query.buildPage(), query, tenantId);
		List<Long> employeeIds = page.getRecords().stream().map(DigitalEmployee::getId).filter(java.util.Objects::nonNull)
			.toList();
		Map<Long, Long> modelCounts = modelConfigMapper.countByEmployeeIds(employeeIds, tenantId);
		Map<Long, Long> capabilityCounts = capabilityMapper.countByEmployeeIds(employeeIds, tenantId);
		return page.convert(employee -> {
			DigitalEmployeeResp response = toResp(employee);
			response.setModelCount(modelCounts.getOrDefault(employee.getId(), 0L));
			response.setCapabilityCount(capabilityCounts.getOrDefault(employee.getId(), 0L));
			return response;
		});
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public DigitalEmployeeResp create(DigitalEmployeeCreateReq request) {
		if (request == null || !StringUtils.hasText(request.getEmployeeName())) {
			throw CheckedException.badRequest("员工名称不能为空");
		}
		jobTemplateService.applyDefaults(request);
		String tenantId = currentTenantId();
		String createUserId = currentUserIdOrNull();
		requireApproverNotSelf(request.getApproverUserId(), createUserId);
		String employeeCode = StringUtils.hasText(request.getEmployeeCode()) ? request.getEmployeeCode().trim()
				: generateEmployeeCode();
		if (employeeMapper.findByCodeAndTenantId(employeeCode, tenantId) != null) {
			throw CheckedException.badRequest("员工编码已存在: " + employeeCode);
		}
		DigitalEmployee employee = DigitalEmployee.builder()
			.tenantId(tenantId)
			.employeeCode(employeeCode)
			.employeeName(request.getEmployeeName().trim())
			.avatarFileId(trimToNull(request.getAvatarFileId()))
			.jobTitle(trimToNull(request.getJobTitle()))
			.description(trimToNull(request.getDescription()))
			.systemInstruction(trimToNull(request.getSystemInstruction()))
			.greeting(trimToNull(request.getGreeting()))
			.status(EmployeeStatusDict.DRAFT.getValue())
			.managerUserId(trimToNull(request.getManagerUserId()))
			.approverUserId(trimToNull(request.getApproverUserId()))
			.principalStatus(PrincipalProvisionStatusDict.PENDING.getValue())
			.modelConfigId(request.getModelConfigId())
			.routeProfileId(request.getRouteProfileId())
			.sourceAgentId(request.getSourceAgentId())
			.autonomyLevel(StringUtils.hasText(request.getAutonomyLevel()) ? request.getAutonomyLevel().trim()
					: DEFAULT_AUTONOMY_LEVEL)
			.executionPolicy(writeJsonOrDefault(request.getExecutionPolicy(), "{}"))
			.draftRevision(0)
			.stateVersion(0L)
			.build();
		employeeMapper.insert(employee);
		grantCreatorUse(employee, createUserId, tenantId);
		log.info("数字员工已创建（rollout={}，未调用 IAM，principal_status=PENDING）. employeeId={}, code={}, tenantId={}",
				properties.getRollout().isEnabled(), employee.getId(), employeeCode, tenantId);
		return toResp(employee);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void modify(Long id, DigitalEmployeeModifyReq request) {
		if (request == null) {
			throw CheckedException.badRequest("修改内容不能为空");
		}
		DigitalEmployee employee = requireEmployee(id);
		if (EmployeeStatusDict.ARCHIVED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("已封存的员工不可修改");
		}
		requireApproverNotSelf(request.getApproverUserId(), currentUserIdOrNull());
		int nextDraftRevision = (employee.getDraftRevision() == null ? 0 : employee.getDraftRevision()) + 1;
		int updated = employeeMapper.update(null, draftUpdateWrapper(employee, request, nextDraftRevision));
		if (updated == 0) {
			throw CheckedException.badRequest("员工资料已被并发修改，请刷新后重试");
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void archive(Long id, Long expectStateVersion) {
		DigitalEmployee employee = requireEmployee(id);
		requireStateVersion(employee, expectStateVersion);
		if (EmployeeStatusDict.ENABLED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("启用中的员工不可封存，请先停用");
		}
		int updated = employeeMapper.casUpdateStatus(id, employee.getTenantId(), employee.getStatus(),
				EmployeeStatusDict.ARCHIVED.getValue(), expectStateVersion);
		if (updated == 0) {
			throw CheckedException.badRequest("员工状态已变更（并发冲突），请刷新后重试");
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		DigitalEmployee employee = requireEmployee(id);
		if (!EmployeeStatusDict.DRAFT.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("仅草稿状态的员工可删除，请先封存归档");
		}
		long releaseCount = releaseMapper.countByEmployeeIdAndStatus(id, null, employee.getTenantId());
		if (releaseCount > 0) {
			throw CheckedException.badRequest("员工已存在发布版本记录，不可删除（保留审计链路），请改用封存");
		}
		employeeMapper.deleteById(id);
	}

	@Override
	public DigitalEmployeeResp getDetail(Long id) {
		DigitalEmployee employee = requireEmployee(id);
		String tenantId = currentTenantId();
		DigitalEmployeeResp response = toResp(employee);
		response.setModelCount(modelConfigMapper.countByEmployeeIds(List.of(employee.getId()), tenantId)
			.getOrDefault(employee.getId(), 0L));
		response.setCapabilityCount(capabilityMapper.countByEmployeeIds(List.of(employee.getId()), tenantId)
			.getOrDefault(employee.getId(), 0L));
		return response;
	}

	@Override
	public List<DigitalEmployeeOptionResp> listOptions(String keyword, String status) {
		DigitalEmployeePageQueryReq query = new DigitalEmployeePageQueryReq();
		query.setCurrent(1);
		query.setSize(OPTIONS_MAX_SIZE);
		query.setKeyword(keyword);
		query.setStatus(status);
		return employeeMapper.selectEmployeePage(query.buildPage(), query, currentTenantId()).getRecords().stream()
			.map(DigitalEmployeeOptionResp::from)
			.toList();
	}

	@Override
	public DigitalEmployeeResp findByPrincipalId(String principalId) {
		if (!StringUtils.hasText(principalId)) {
			throw CheckedException.badRequest("principalId 不能为空");
		}
		DigitalEmployee employee = employeeMapper.findByIamPrincipalIdAndTenantId(principalId.trim(), currentTenantId());
		if (employee == null) {
			throw CheckedException.notFound("未找到对应数字员工: " + principalId);
		}
		return toResp(employee);
	}

	@Override
	public void enable(Long id, Long expectStateVersion) {
		// provision（Feign）无事务；仅 casUpdateStatus 走短事务，避免 IAM 已成功但本地回滚导致 PENDING 永久卡住
		if (expectStateVersion == null) {
			throw CheckedException.badRequest("stateVersion 不能为空（CAS 防并发覆盖）");
		}
		if (!properties.getRollout().isEnabled()) {
			throw CheckedException.badRequest("数字员工灰度未开启（rollout=false），员工暂不可启用");
		}
		DigitalEmployee employee = requireEmployee(id);
		requireStateVersion(employee, expectStateVersion);
		if (!EmployeeStatusDict.DRAFT.getValue().equals(employee.getStatus())
				&& !EmployeeStatusDict.DISABLED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("当前状态不可启用: " + employee.getStatus());
		}
		requirePublishedProduction(employee);
		if (!PrincipalProvisionStatusDict.READY.getValue().equals(employee.getPrincipalStatus())) {
			PrincipalProvisioningService.ProvisionOutcome outcome = provisioningService.provision(employee);
			if (!outcome.ready()) {
				throw CheckedException.badRequest("Principal 未就绪，无法启用: " + outcome.message());
			}
			employee = requireEmployee(id);
		}
		casEnable(id, employee.getTenantId(), employee.getStatus(), expectStateVersion);
		log.info("数字员工已启用. employeeId={}, principalId={}", id, employee.getIamPrincipalId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void disable(Long id, Long expectStateVersion) {
		if (expectStateVersion == null) {
			throw CheckedException.badRequest("stateVersion 不能为空（CAS 防并发覆盖）");
		}
		DigitalEmployee employee = requireEmployee(id);
		requireStateVersion(employee, expectStateVersion);
		if (!EmployeeStatusDict.ENABLED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("仅启用中的员工可以停用，当前状态: " + employee.getStatus());
		}
		int updated = employeeMapper.casUpdateStatus(id, employee.getTenantId(), employee.getStatus(),
				EmployeeStatusDict.DISABLED.getValue(), expectStateVersion);
		if (updated == 0) {
			throw CheckedException.badRequest("员工状态已变更（并发冲突），请刷新后重试");
		}
	}

	@Override
	public void provisionPrincipal(Long id) {
		DigitalEmployee employee = requireEmployee(id);
		PrincipalProvisioningService.ProvisionOutcome outcome = provisioningService.provision(employee);
		if (outcome.ready()) {
			return;
		}
		throw CheckedException.badRequest(outcome.message());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void publishCurrentConfig(Long id) {
		DigitalEmployee employee = requireEmployee(id);
		if (EmployeeStatusDict.ARCHIVED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("已封存的员工不可发布");
		}
		Long releaseId = releaseLifecycleService.createDraft(id, new EmployeeReleaseCreateReq());
		releaseLifecycleService.seal(releaseId);
		releaseLifecycleService.publish(releaseId);
		DigitalEmployeeDeployment current = deploymentService.findCurrent(id,
				DeploymentEnvironmentDict.PRODUCTION.getValue());
		int expectVersion = current == null || current.getDeploymentVersion() == null ? 0
				: current.getDeploymentVersion();
		EmployeeDeploymentActivateReq activate = new EmployeeDeploymentActivateReq();
		activate.setReleaseId(releaseId);
		activate.setEnvironment(DeploymentEnvironmentDict.PRODUCTION.getValue());
		activate.setExpectVersion(expectVersion);
		deploymentService.activate(id, activate);
		log.info("数字员工已发布当前配置并激活生产. employeeId={}, releaseId={}", id, releaseId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Long bindCapability(Long employeeId, EmployeeCapabilityBindReq request) {
		if (request == null || request.getSkillVersionId() == null) {
			throw CheckedException.badRequest("skillVersionId 不能为空");
		}
		String tenantId = currentTenantId();
		requireEmployee(employeeId);
		requirePublishedSkillVersion(request.getSkillVersionId(), tenantId);
		DigitalEmployeeCapability existing = capabilityMapper.findByEmployeeAndSkillVersion(employeeId,
				request.getSkillVersionId(), tenantId);
		if (existing != null) {
			capabilityMapper.reEnable(existing.getId(), tenantId);
			return existing.getId();
		}
		DigitalEmployeeCapability capability = DigitalEmployeeCapability.builder()
			.tenantId(tenantId)
			.employeeId(employeeId)
			.skillVersionId(request.getSkillVersionId())
			.enabled(request.getEnabled() == null || request.getEnabled())
			.build();
		capabilityMapper.insert(capability);
		return capability.getId();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void unbindCapability(Long employeeId, Long capabilityId) {
		String tenantId = currentTenantId();
		requireEmployee(employeeId);
		DigitalEmployeeCapability capability = capabilityMapper.findByIdAndEmployeeId(capabilityId, employeeId, tenantId);
		if (capability == null) {
			throw CheckedException.notFound("能力绑定不存在或不属于该员工: " + capabilityId);
		}
		capabilityMapper.deleteById(capabilityId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void updateCapabilityEnabled(Long employeeId, Long capabilityId, Boolean enabled) {
		if (enabled == null) {
			throw CheckedException.badRequest("enabled 不能为空");
		}
		DigitalEmployee employee = requireEmployee(employeeId);
		if (EmployeeStatusDict.ARCHIVED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("已封存的员工不可修改能力状态");
		}
		String tenantId = employee.getTenantId();
		DigitalEmployeeCapability capability = capabilityMapper.findByIdAndEmployeeId(capabilityId, employeeId,
				tenantId);
		if (capability == null) {
			throw CheckedException.notFound("能力绑定不存在或不属于该员工: " + capabilityId);
		}
		int updated = capabilityMapper.updateEnabledById(capabilityId, employeeId, tenantId, enabled);
		if (updated == 0) {
			throw CheckedException.badRequest("能力状态已变更，请刷新后重试: " + capabilityId);
		}
	}

	@Override
	public java.util.List<DigitalEmployeeCapability> listCapabilities(Long employeeId) {
		String tenantId = currentTenantId();
		requireEmployee(employeeId);
		return capabilityMapper.findByEmployeeId(employeeId, tenantId);
	}

	private void requirePublishedSkillVersion(Long skillVersionId, String tenantId) {
		DataAgentSkillVersion version = skillVersionMapper.selectById(skillVersionId);
		if (version == null || Boolean.TRUE.equals(version.getDeleted())) {
			throw CheckedException.notFound("技能版本不存在: " + skillVersionId);
		}
		if (!"PUBLISHED".equals(version.getStatus())) {
			throw CheckedException.badRequest("只能绑定已发布的技能版本, skillVersionId=" + skillVersionId);
		}
		if (StringUtils.hasText(version.getTenantId()) && !tenantId.equals(version.getTenantId())) {
			throw CheckedException.badRequest("技能版本不属于当前租户, skillVersionId=" + skillVersionId);
		}
	}

	private void grantCreatorUse(DigitalEmployee employee, String createUserId, String tenantId) {
		if (!StringUtils.hasText(createUserId) || employee == null || employee.getId() == null) {
			return;
		}
		AuthorizationGrantCreateReq grantReq = new AuthorizationGrantCreateReq();
		grantReq.setOwnerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE);
		grantReq.setOwnerId(employee.getId());
		grantReq.setSubjectType(AuthorizationGrantSubjectType.USER);
		grantReq.setSubjectId(createUserId.trim());
		grantReq.setPermission(AuthorizationGrantPermission.USE);
		try {
			papService.createGrant(grantReq, tenantId);
		}
		catch (CheckedException ex) {
			log.error("创建人 USE Grant 写入失败. employeeId={}, userId={}", employee.getId(), createUserId, ex);
			throw ex;
		}
		catch (Exception ex) {
			log.error("创建人 USE Grant 写入失败. employeeId={}, userId={}", employee.getId(), createUserId, ex);
			throw CheckedException.badRequest("创建人 USE 授权失败");
		}
	}

	private LambdaUpdateWrapper<DigitalEmployee> draftUpdateWrapper(DigitalEmployee employee,
			DigitalEmployeeModifyReq request, int nextDraftRevision) {
		return Wraps.<DigitalEmployee>lbU()
			.eq(DigitalEmployee::getId, employee.getId())
			.eq(DigitalEmployee::getTenantId, employee.getTenantId())
			.eq(DigitalEmployee::getDeleted, false)
			.set(StringUtils.hasText(request.getEmployeeName()), DigitalEmployee::getEmployeeName,
					StringUtils.hasText(request.getEmployeeName()) ? request.getEmployeeName().trim() : null)
			.set(request.getAvatarFileId() != null, DigitalEmployee::getAvatarFileId,
					trimToNull(request.getAvatarFileId()))
			.set(request.getJobTitle() != null, DigitalEmployee::getJobTitle, trimToNull(request.getJobTitle()))
			.set(request.getDescription() != null, DigitalEmployee::getDescription, trimToNull(request.getDescription()))
			.set(request.getSystemInstruction() != null, DigitalEmployee::getSystemInstruction,
					trimToNull(request.getSystemInstruction()))
			.set(request.getGreeting() != null, DigitalEmployee::getGreeting, trimToNull(request.getGreeting()))
			.set(request.getManagerUserId() != null, DigitalEmployee::getManagerUserId,
					trimToNull(request.getManagerUserId()))
			.set(request.getApproverUserId() != null, DigitalEmployee::getApproverUserId,
					trimToNull(request.getApproverUserId()))
			.set(request.getModelConfigId() != null, DigitalEmployee::getModelConfigId, request.getModelConfigId())
			.set(request.getRouteProfileId() != null, DigitalEmployee::getRouteProfileId, request.getRouteProfileId())
			.set(StringUtils.hasText(request.getAutonomyLevel()), DigitalEmployee::getAutonomyLevel,
					StringUtils.hasText(request.getAutonomyLevel()) ? request.getAutonomyLevel().trim() : null)
			.set(request.getExecutionPolicy() != null, DigitalEmployee::getExecutionPolicy,
					request.getExecutionPolicy() == null ? null
							: writeJsonOrDefault(request.getExecutionPolicy(), "{}"))
			.set(DigitalEmployee::getDraftRevision, nextDraftRevision);
	}

	private void casEnable(Long id, String tenantId, String expectStatus, Long expectStateVersion) {
		transactionTemplate.executeWithoutResult(status -> {
			int updated = employeeMapper.casUpdateStatus(id, tenantId, expectStatus,
					EmployeeStatusDict.ENABLED.getValue(), expectStateVersion);
			if (updated == 0) {
				throw CheckedException.badRequest("员工状态已变更（并发冲突），请刷新后重试");
			}
		});
	}

	private void requirePublishedProduction(DigitalEmployee employee) {
		DigitalEmployeeDeployment production = deploymentMapper.findByEmployeeAndEnvironment(employee.getId(),
				DeploymentEnvironmentDict.PRODUCTION.getValue(), employee.getTenantId());
		if (production == null || !DeploymentStatusDict.ACTIVE.getValue().equals(production.getStatus())
				|| production.getActiveReleaseId() == null) {
			throw CheckedException.badRequest("请先发布当前配置");
		}
		DigitalEmployeeRelease release = releaseMapper.findByIdAndTenantId(production.getActiveReleaseId(),
				employee.getTenantId());
		if (release == null || !EmployeeReleaseStatusDict.PUBLISHED.getValue().equals(release.getStatus())) {
			throw CheckedException.badRequest("请先发布当前配置");
		}
	}

	private void requireApproverNotSelf(String approverUserId, String createUserId) {
		if (StringUtils.hasText(approverUserId) && StringUtils.hasText(createUserId)
				&& approverUserId.trim().equals(createUserId.trim())) {
			throw CheckedException.badRequest("审批人不能是创建人自己");
		}
	}

	private void requireStateVersion(DigitalEmployee employee, Long expectStateVersion) {
		if (expectStateVersion == null || !expectStateVersion.equals(employee.getStateVersion())) {
			throw CheckedException.badRequest("员工状态版本已变更，请刷新后重试. expectVersion=" + expectStateVersion
					+ ", currentVersion=" + employee.getStateVersion());
		}
	}

	private DigitalEmployee requireEmployee(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(id, currentTenantId());
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + id);
		}
		return employee;
	}

	private DigitalEmployeeResp toResp(DigitalEmployee employee) {
		DigitalEmployeeResp response = DigitalEmployeeResp.from(employee, properties.getRollout().isEnabled());
		if (response != null) {
			response.setJobTemplateCode(readJobTemplateCode(employee == null ? null : employee.getExecutionPolicy()));
		}
		return response;
	}

	private String readJobTemplateCode(String executionPolicyJson) {
		if (!StringUtils.hasText(executionPolicyJson)) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(executionPolicyJson);
			JsonNode code = node.get(EmployeeJobTemplateService.JOB_TEMPLATE_CODE_KEY);
			return code != null && code.isTextual() && StringUtils.hasText(code.asText()) ? code.asText() : null;
		}
		catch (Exception ex) {
			log.warn("解析员工岗位模板编码失败", ex);
			return null;
		}
	}

	private String generateEmployeeCode() {
		return "DE-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
	}

	private String writeJsonOrDefault(java.util.Map<String, Object> value, String defaultJson) {
		if (value == null) {
			return defaultJson;
		}
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			log.error("序列化员工运营配置JSON失败", ex);
			throw CheckedException.fail("员工运营配置序列化失败，请检查提交内容");
		}
	}

	private String currentUserIdOrNull() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止访问数字员工管理");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止访问数字员工管理");
		}
		return tenantId.trim();
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
