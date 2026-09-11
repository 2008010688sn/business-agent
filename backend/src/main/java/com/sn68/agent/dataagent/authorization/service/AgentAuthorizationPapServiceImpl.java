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
package com.sn68.agent.dataagent.authorization.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationBindingUpsertReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationDecisionSimulateDbReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationGrantCreateReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyCreateReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyDetailResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyModifyReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyPageQueryReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyVersionResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationTemplateResp;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationBinding;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationGrant;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicyVersion;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicyStatus;
import com.sn68.agent.dataagent.authorization.model.AuthorizationRule;
import com.sn68.agent.dataagent.authorization.model.PolicyValidator;
import com.sn68.agent.dataagent.authorization.model.SubjectMode;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationRequest;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationBindingMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationGrantMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationPolicyMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationPolicyVersionMapper;
import com.sn68.agent.dataagent.authorization.template.AuthorizationTemplate;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Agent 授权策略管理点（PAP）服务实现（PR-3b）。
 *
 * <p>版本生命周期：create（主档 DRAFT + 草稿 v1）→ modify（仅 DRAFT，覆盖草稿 JSON）→
 * publish（草稿 CAS 翻转 published，主档指针切换 PUBLISHED）→ 修改走 draftFromCurrent
 * （复制当前发布版本开新草稿）→ 再 publish。已发布版本的 JSON 与 hash 永不 UPDATE（SQL 守卫兜底）。</p>
 *
 * <p>契约边界：求值仅委托 PR-3a 冻结的 {@link AuthorizationPolicyEvaluator#evaluate}（二参），
 * 不重排求值顺序、不改 DecisionReasonCode 语义、policyHash 一律使用
 * {@link AuthorizationPolicy#computeHash()} 冻结算法。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAuthorizationPapServiceImpl implements AgentAuthorizationPapService {

	/**
	 * 策略 JSON 序列化专用实例（模块规范：Jackson 用法收敛到单例，不散落 new）。
	 */
	private static final ObjectMapper POLICY_JSON_MAPPER = new ObjectMapper();

	private final AgentAuthorizationPolicyMapper policyMapper;

	private final AgentAuthorizationPolicyVersionMapper versionMapper;

	private final AgentAuthorizationBindingMapper bindingMapper;

	private final AgentAuthorizationGrantMapper grantMapper;

	private final AuthorizationPolicyEvaluator policyEvaluator;

	@Override
	public List<AuthorizationTemplateResp> listTemplates() {
		List<AuthorizationTemplateResp> templates = new ArrayList<>();
		for (AuthorizationTemplate template : AuthorizationTemplate.values()) {
			templates.add(new AuthorizationTemplateResp(template.getCode(),
					serializePolicy(template.defaultPolicy())));
		}
		return templates;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AuthorizationPolicyDetailResp createPolicy(AuthorizationPolicyCreateReq request, String tenantId) {
		if (request == null) {
			throw CheckedException.badRequest("策略创建请求不能为空");
		}
		String code = request.getCode().trim();
		if (policyMapper.findByCodeAndTenant(code, tenantId) != null) {
			throw CheckedException.badRequest("策略编码已存在: " + code);
		}
		AuthorizationPolicy parsed = resolveCreatePolicy(request);
		String policyJson = StringUtils.hasText(request.getPolicyJson())
				? request.getPolicyJson()
				: serializePolicy(parsed);
		AgentAuthorizationPolicy policy = AgentAuthorizationPolicy.builder()
			.tenantId(tenantId)
			.code(code)
			.name(request.getName().trim())
			.templateCode(parsed.getTemplateCode())
			.status(AuthorizationPolicyStatus.DRAFT)
			.build();
		policyMapper.insert(policy);
		insertDraftVersion(policy.getId(), tenantId, 1, policyJson, parsed);
		log.info("创建授权策略, policyId={}, code={}, tenantId={}, hash={}", policy.getId(), code, tenantId,
				parsed.computeHash());
		return getPolicyDetail(policy.getId(), tenantId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AuthorizationPolicyDetailResp modifyPolicy(Long id, AuthorizationPolicyModifyReq request,
			String tenantId) {
		AgentAuthorizationPolicy policy = requirePolicy(id, tenantId);
		if (AuthorizationPolicyStatus.DRAFT != policy.getStatus()) {
			throw CheckedException.badRequest("仅草稿状态策略可修改，已发布策略请先从当前版本开新草稿");
		}
		AgentAuthorizationPolicyVersion latest = requireLatestVersion(policy.getId(), tenantId);
		if (Boolean.TRUE.equals(latest.getPublished())) {
			throw CheckedException.badRequest("最新版本已发布，请新建草稿版本后修改");
		}
		if (request != null && StringUtils.hasText(request.getName())) {
			policy.setName(request.getName().trim());
			policyMapper.updateById(policy);
		}
		if (request != null && StringUtils.hasText(request.getPolicyJson())) {
			AuthorizationPolicy parsed = PolicyValidator.validateAndParse(request.getPolicyJson());
			int updated = versionMapper.updateDraft(latest.getId(), tenantId, request.getPolicyJson(),
					parsed.computeHash());
			if (updated == 0) {
				throw CheckedException.badRequest("草稿版本已被并发发布或删除，请刷新后重试");
			}
			log.info("修改授权策略草稿, policyId={}, versionId={}, hash={}", policy.getId(), latest.getId(),
					parsed.computeHash());
		}
		return getPolicyDetail(policy.getId(), tenantId);
	}

	@Override
	public IPage<AgentAuthorizationPolicy> pagePolicies(AuthorizationPolicyPageQueryReq request, String tenantId) {
		return policyMapper.selectPolicyPage(
				(request == null ? new AuthorizationPolicyPageQueryReq() : request).buildPage(), request, tenantId);
	}

	@Override
	public AuthorizationPolicyDetailResp getPolicyDetail(Long id, String tenantId) {
		AgentAuthorizationPolicy policy = requirePolicy(id, tenantId);
		List<AgentAuthorizationPolicyVersion> versions = versionMapper.listByPolicyId(policy.getId(), tenantId);
		AuthorizationPolicyDetailResp detail = new AuthorizationPolicyDetailResp();
		detail.setId(policy.getId());
		detail.setCode(policy.getCode());
		detail.setName(policy.getName());
		detail.setTemplateCode(policy.getTemplateCode());
		detail.setStatus(policy.getStatus() == null ? null : policy.getStatus().getCode());
		detail.setCurrentVersionId(policy.getCurrentVersionId());
		versions.stream()
			.filter(version -> Objects.equals(version.getId(), policy.getCurrentVersionId()))
			.findFirst()
			.ifPresent(version -> {
				detail.setCurrentVersionNo(version.getVersionNo());
				detail.setCurrentPolicyJson(version.getPolicyJson());
				detail.setCurrentPolicyHash(version.getPolicyHash());
			});
		versions.stream()
			.filter(version -> !Boolean.TRUE.equals(version.getPublished()))
			.findFirst()
			.ifPresent(version -> {
				detail.setDraftVersionId(version.getId());
				detail.setDraftVersionNo(version.getVersionNo());
				detail.setDraftPolicyJson(version.getPolicyJson());
			});
		return detail;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deletePolicy(Long id, String tenantId) {
		AgentAuthorizationPolicy policy = requirePolicy(id, tenantId);
		List<AgentAuthorizationPolicyVersion> versions = versionMapper.listByPolicyId(policy.getId(), tenantId);
		for (AgentAuthorizationPolicyVersion version : versions) {
			if (bindingMapper.countByPolicyVersionId(version.getId()) > 0) {
				throw CheckedException.badRequest("策略版本 v" + version.getVersionNo() + " 仍被绑定引用，请先解绑再删除");
			}
		}
		versionMapper.deleteByPolicyId(policy.getId(), tenantId);
		policyMapper.deleteById(policy.getId());
		log.info("删除授权策略, policyId={}, code={}, tenantId={}", policy.getId(), policy.getCode(), tenantId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AuthorizationPolicyDetailResp publishPolicy(Long id, String tenantId) {
		AgentAuthorizationPolicy policy = requirePolicy(id, tenantId);
		if (AuthorizationPolicyStatus.RETIRED == policy.getStatus()) {
			throw CheckedException.badRequest("已停用策略不能直接发布，请先重新启用");
		}
		AgentAuthorizationPolicyVersion latest = requireLatestVersion(policy.getId(), tenantId);
		if (Boolean.TRUE.equals(latest.getPublished())) {
			throw CheckedException.badRequest("无待发布草稿版本，请先修改策略或从当前版本开新草稿");
		}
		// 发布前对库内 JSON 重新走严格校验（防绕过应用层写入的脏数据进入运行时）
		AuthorizationPolicy parsed = PolicyValidator.validateAndParse(latest.getPolicyJson());
		String expectedHash = parsed.computeHash();
		if (!expectedHash.equals(latest.getPolicyHash())) {
			log.warn("策略版本 hash 与内容不一致, versionId={}, stored={}, computed={}", latest.getId(),
					latest.getPolicyHash(), expectedHash);
			throw CheckedException.badRequest("策略版本内容与 hash 不一致，请重新保存草稿后再发布");
		}
		if (versionMapper.casPublish(latest.getId(), tenantId) == 0) {
			throw CheckedException.badRequest("版本已被并发发布，请刷新后查看");
		}
		policy.setCurrentVersionId(latest.getId());
		policy.setStatus(AuthorizationPolicyStatus.PUBLISHED);
		policyMapper.updateById(policy);
		log.info("发布授权策略版本, policyId={}, versionId={}, versionNo={}, hash={}", policy.getId(),
				latest.getId(), latest.getVersionNo(), expectedHash);
		return getPolicyDetail(policy.getId(), tenantId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AuthorizationPolicyDetailResp draftFromCurrent(Long id, String tenantId) {
		AgentAuthorizationPolicy policy = requirePolicy(id, tenantId);
		if (policy.getCurrentVersionId() == null) {
			throw CheckedException.badRequest("策略尚无已发布版本，请直接修改草稿");
		}
		List<AgentAuthorizationPolicyVersion> versions = versionMapper.listByPolicyId(policy.getId(), tenantId);
		AgentAuthorizationPolicyVersion current = versions.stream()
			.filter(version -> Objects.equals(version.getId(), policy.getCurrentVersionId()))
			.findFirst()
			.orElseThrow(() -> CheckedException.notFound("策略当前发布版本不存在"));
		boolean draftExists = versions.stream().anyMatch(version -> !Boolean.TRUE.equals(version.getPublished()));
		if (draftExists) {
			throw CheckedException.badRequest("已存在草稿版本，请先发布或修改既有草稿");
		}
		insertDraftVersion(policy.getId(), tenantId, current.getVersionNo() + 1, current.getPolicyJson(),
				PolicyValidator.validateAndParse(current.getPolicyJson()));
		policy.setStatus(AuthorizationPolicyStatus.DRAFT);
		policyMapper.updateById(policy);
		log.info("从当前发布版本开新草稿, policyId={}, sourceVersionNo={}", policy.getId(), current.getVersionNo());
		return getPolicyDetail(policy.getId(), tenantId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void retirePolicy(Long id, String tenantId) {
		AgentAuthorizationPolicy policy = requirePolicy(id, tenantId);
		if (AuthorizationPolicyStatus.RETIRED == policy.getStatus()) {
			return;
		}
		if (AuthorizationPolicyStatus.DRAFT == policy.getStatus()) {
			throw CheckedException.badRequest("草稿策略请直接删除，无需停用");
		}
		policy.setStatus(AuthorizationPolicyStatus.RETIRED);
		policyMapper.updateById(policy);
		log.info("停用授权策略, policyId={}, code={}, tenantId={}", policy.getId(), policy.getCode(), tenantId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void enablePolicy(Long id, String tenantId) {
		AgentAuthorizationPolicy policy = requirePolicy(id, tenantId);
		if (AuthorizationPolicyStatus.PUBLISHED == policy.getStatus()) {
			return;
		}
		if (AuthorizationPolicyStatus.RETIRED != policy.getStatus()) {
			throw CheckedException.badRequest("仅已停用策略可重新启用");
		}
		if (policy.getCurrentVersionId() == null) {
			throw CheckedException.badRequest("策略无已发布版本，不能启用");
		}
		policy.setStatus(AuthorizationPolicyStatus.PUBLISHED);
		policyMapper.updateById(policy);
		log.info("启用授权策略, policyId={}, code={}, tenantId={}", policy.getId(), policy.getCode(), tenantId);
	}

	@Override
	public List<AuthorizationPolicyVersionResp> listPolicyVersions(Long policyId, String tenantId) {
		requirePolicy(policyId, tenantId);
		return versionMapper.listByPolicyId(policyId, tenantId)
			.stream()
			.map(this::toVersionResp)
			.toList();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AgentAuthorizationBinding upsertBinding(AuthorizationBindingUpsertReq request, String tenantId) {
		if (request == null) {
			throw CheckedException.badRequest("策略绑定请求不能为空");
		}
		AgentAuthorizationPolicyVersion version = versionMapper.findByIdAndTenant(request.getPolicyVersionId(),
				tenantId);
		if (version == null || !Boolean.TRUE.equals(version.getPublished())) {
			throw CheckedException.badRequest("仅可绑定已发布策略版本: " + request.getPolicyVersionId());
		}
		// 绑定主体一致性（2026-08-19 评审修订，与 PDP 员工硬基线双锚配套）：数字员工只能绑定
		// subjectMode=EMPLOYEE 的策略版本，防止误绑 CALLER 模式策略削弱员工硬基线；
		// 已发布版本 JSON 在发布时已过严格校验，此处解析失败按管理数据完整性问题直接抛错暴露
		if (AuthorizationOwnerType.DIGITAL_EMPLOYEE == request.getOwnerType()
				&& PolicyValidator.validateAndParse(version.getPolicyJson()).getSubjectMode() != SubjectMode.EMPLOYEE) {
			throw CheckedException.badRequest(
					"数字员工绑定的策略版本主体模式不匹配，仅可绑定 subjectMode=EMPLOYEE 的策略, policyVersionId="
							+ request.getPolicyVersionId());
		}
		AgentAuthorizationBinding existing = bindingMapper.findBinding(request.getOwnerType(), request.getOwnerId(),
				request.getEnvironment(), tenantId);
		if (existing == null) {
			AgentAuthorizationBinding binding = AgentAuthorizationBinding.builder()
				.tenantId(tenantId)
				.ownerType(request.getOwnerType())
				.ownerId(request.getOwnerId())
				.environment(request.getEnvironment())
				.policyVersionId(request.getPolicyVersionId())
				.bindRevision(0L)
				.build();
			try {
				bindingMapper.insert(binding);
				log.info("创建策略绑定, ownerType={}, ownerId={}, environment={}, versionId={}",
						request.getOwnerType(), request.getOwnerId(), request.getEnvironment(),
						request.getPolicyVersionId());
				return binding;
			}
			catch (DuplicateKeyException ex) {
				throw CheckedException.badRequest("策略绑定已被并发创建，请刷新后重试");
			}
		}
		if (request.getExpectedBindRevision() == null) {
			throw CheckedException.badRequest("绑定已存在，更新需携带 expectedBindRevision（当前值: "
					+ existing.getBindRevision() + "）");
		}
		if (!Objects.equals(request.getExpectedBindRevision(), existing.getBindRevision())) {
			throw CheckedException.badRequest(
					"策略绑定已被并发修改，当前 bindRevision=" + existing.getBindRevision() + "，请刷新后重试");
		}
		if (Objects.equals(existing.getPolicyVersionId(), request.getPolicyVersionId())) {
			return existing;
		}
		if (bindingMapper.casUpdateBinding(existing.getId(), tenantId, request.getPolicyVersionId(),
				request.getExpectedBindRevision()) == 0) {
			AgentAuthorizationBinding latest = bindingMapper.findBinding(request.getOwnerType(),
					request.getOwnerId(), request.getEnvironment(), tenantId);
			long currentRevision = latest == null || latest.getBindRevision() == null ? -1L
					: latest.getBindRevision();
			throw CheckedException.badRequest(
					"策略绑定已被并发修改，当前 bindRevision=" + currentRevision + "，请刷新后重试");
		}
		log.info("CAS 更新策略绑定, ownerType={}, ownerId={}, environment={}, versionId={}, expectedRevision={}",
				request.getOwnerType(), request.getOwnerId(), request.getEnvironment(), request.getPolicyVersionId(),
				request.getExpectedBindRevision());
		return bindingMapper.findBinding(request.getOwnerType(), request.getOwnerId(), request.getEnvironment(),
				tenantId);
	}

	@Override
	public AgentAuthorizationBinding getBinding(AuthorizationOwnerType ownerType, Long ownerId,
			AuthorizationEnvironment environment, String tenantId) {
		return bindingMapper.findBinding(ownerType, ownerId, environment, tenantId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AgentAuthorizationGrant createGrant(AuthorizationGrantCreateReq request, String tenantId) {
		if (request == null) {
			throw CheckedException.badRequest("授权创建请求不能为空");
		}
		AgentAuthorizationGrant existing = grantMapper.findActive(request.getOwnerType(), request.getOwnerId(),
				request.getSubjectType(), request.getSubjectId(), request.getPermission(), tenantId);
		if (existing != null) {
			return existing;
		}
		AgentAuthorizationGrant grant = AgentAuthorizationGrant.builder()
			.tenantId(tenantId)
			.ownerType(request.getOwnerType())
			.ownerId(request.getOwnerId())
			.subjectType(request.getSubjectType())
			.subjectId(request.getSubjectId().trim())
			.subjectName(StringUtils.hasText(request.getSubjectName()) ? request.getSubjectName().trim() : null)
			.resourceType("OWNER")
			.permission(request.getPermission())
			.sourceType("MANUAL")
			.expireTime(resolveExpireTime(request.getExpireDays()))
			.status(AgentAuthorizationGrantMapper.GRANT_STATUS_ACTIVE)
			.build();
		try {
			grantMapper.insert(grant);
		}
		catch (DuplicateKeyException ex) {
			AgentAuthorizationGrant duplicate = grantMapper.findActive(request.getOwnerType(),
					request.getOwnerId(), request.getSubjectType(), request.getSubjectId(), request.getPermission(),
					tenantId);
			if (duplicate != null) {
				return duplicate;
			}
			throw ex;
		}
		log.info("创建授权记录, ownerType={}, ownerId={}, subjectType={}, subjectId={}, permission={}",
				request.getOwnerType(), request.getOwnerId(), request.getSubjectType(), request.getSubjectId(),
				request.getPermission());
		return grant;
	}

	@Override
	public List<AgentAuthorizationGrant> listGrants(AuthorizationOwnerType ownerType, Long ownerId, String tenantId) {
		return grantMapper.listByOwner(ownerType, ownerId, tenantId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteGrant(Long id, String tenantId) {
		AgentAuthorizationGrant grant = grantMapper.findByIdAndTenant(id, tenantId);
		if (grant == null) {
			throw CheckedException.notFound("授权记录不存在");
		}
		grantMapper.deleteById(grant.getId());
		log.info("删除授权记录, grantId={}, ownerType={}, ownerId={}", grant.getId(), grant.getOwnerType(),
				grant.getOwnerId());
	}

	@Override
	public AuthorizationDecisionResp simulateFromDb(AuthorizationDecisionSimulateDbReq request, String tenantId) {
		AuthorizationRequest pdpRequest = toPdpRequest(request);
		AuthorizationPolicy policy = resolveBoundPolicy(request, tenantId);
		// 无绑定/版本不可用 → policy 传 null，由冻结求值器按 MISSING_POLICY 求值
		AuthorizationDecision decision = policyEvaluator.evaluate(policy, pdpRequest);
		log.info("DB 决策模拟完成, ownerType={}, ownerId={}, environment={}, capabilityCode={}, action={}, "
				+ "allowed={}, reasonCode={}", request.getOwnerType(), request.getOwnerId(),
				request.getEnvironment(), request.getCapabilityCode(), request.getAction(), decision.isAllowed(),
				decision.getReasonCode());
		return toDecisionResp(decision);
	}

	/**
	 * 解析绑定指向的策略：绑定缺失或版本未发布/不存在均返回 null（MISSING_POLICY 路径）；
	 * 版本 JSON 损坏属于管理数据完整性问题，显式抛错暴露。
	 */
	private AuthorizationPolicy resolveBoundPolicy(AuthorizationDecisionSimulateDbReq request, String tenantId) {
		AgentAuthorizationBinding binding = bindingMapper.findBinding(request.getOwnerType(), request.getOwnerId(),
				request.getEnvironment(), tenantId);
		if (binding == null) {
			return null;
		}
		AgentAuthorizationPolicyVersion version = versionMapper.findByIdAndTenant(binding.getPolicyVersionId(),
				tenantId);
		if (version == null || !Boolean.TRUE.equals(version.getPublished())) {
			log.warn("策略绑定指向的版本不可用, ownerType={}, ownerId={}, environment={}, versionId={}",
					request.getOwnerType(), request.getOwnerId(), request.getEnvironment(),
					binding.getPolicyVersionId());
			return null;
		}
		return PolicyValidator.validateAndParse(version.getPolicyJson());
	}

	/**
	 * 组装 PDP 请求；iamAvailable 缺省按 true（与 PR-3a simulate 口径一致）。
	 */
	private AuthorizationRequest toPdpRequest(AuthorizationDecisionSimulateDbReq request) {
		return AuthorizationRequest.builder()
			.subjectKind(request.getSubjectKind())
			.capabilityCode(request.getCapabilityCode())
			.action(request.getAction())
			.capabilityVersion(request.getCapabilityVersion())
			.iamAvailable(request.getIamAvailable() == null || request.getIamAvailable())
			.build();
	}

	/**
	 * 决策转 REST 投影（字段集与 PR-3a simulate 响应完全一致）。
	 */
	private AuthorizationDecisionResp toDecisionResp(AuthorizationDecision decision) {
		return AuthorizationDecisionResp.builder()
			.allowed(decision.isAllowed())
			.reasonCode(decision.getReasonCode())
			.obligations(decision.getObligations())
			.maskFields(decision.getMaskFields())
			.policyHash(decision.getPolicyHash())
			.evaluatedAt(decision.getEvaluatedAt())
			.build();
	}

	/**
	 * 创建请求策略解析：policyJson 优先（严格校验）；否则按 templateCode 生成默认策略。
	 */
	private AuthorizationPolicy resolveCreatePolicy(AuthorizationPolicyCreateReq request) {
		if (StringUtils.hasText(request.getPolicyJson())) {
			return PolicyValidator.validateAndParse(request.getPolicyJson());
		}
		String templateCode = request.getTemplateCode() == null ? null : request.getTemplateCode().trim()
			.toUpperCase();
		AuthorizationTemplate template = AuthorizationTemplate.fromCode(templateCode);
		if (template == null) {
			throw CheckedException.badRequest("policyJson 与有效 templateCode 至少提供一个");
		}
		return template.defaultPolicy();
	}

	/**
	 * 插入草稿版本行（hash 使用冻结 computeHash 算法）。
	 */
	private void insertDraftVersion(Long policyId, String tenantId, int versionNo, String policyJson,
			AuthorizationPolicy parsed) {
		AgentAuthorizationPolicyVersion version = AgentAuthorizationPolicyVersion.builder()
			.tenantId(tenantId)
			.policyId(policyId)
			.versionNo(versionNo)
			.policyJson(policyJson)
			.policyHash(parsed.computeHash())
			.published(false)
			.build();
		versionMapper.insert(version);
	}

	/**
	 * 加载并校验策略主档（租户隔离）。
	 */
	private AgentAuthorizationPolicy requirePolicy(Long id, String tenantId) {
		AgentAuthorizationPolicy policy = policyMapper.findByIdAndTenant(id, tenantId);
		if (policy == null) {
			throw CheckedException.notFound("授权策略不存在");
		}
		return policy;
	}

	/**
	 * 加载策略最新版本行；无版本视为数据异常。
	 */
	private AgentAuthorizationPolicyVersion requireLatestVersion(Long policyId, String tenantId) {
		AgentAuthorizationPolicyVersion latest = versionMapper.findLatestByPolicyId(policyId, tenantId);
		if (latest == null) {
			throw CheckedException.notFound("策略版本不存在");
		}
		return latest;
	}

	/**
	 * 版本行转列表响应（不含 JSON 正文）。
	 */
	private AuthorizationPolicyVersionResp toVersionResp(AgentAuthorizationPolicyVersion version) {
		AuthorizationPolicyVersionResp resp = new AuthorizationPolicyVersionResp();
		resp.setId(version.getId());
		resp.setPolicyId(version.getPolicyId());
		resp.setVersionNo(version.getVersionNo());
		resp.setPolicyHash(version.getPolicyHash());
		resp.setPublished(version.getPublished());
		resp.setCreateTime(version.getCreateTime());
		return resp;
	}

	/**
	 * 有效天数转过期时间；空或非正数表示长期有效。
	 */
	private Instant resolveExpireTime(Integer expireDays) {
		if (expireDays == null || expireDays <= 0) {
			return null;
		}
		return Instant.now().plus(expireDays, ChronoUnit.DAYS);
	}

	/**
	 * 策略序列化为 JSON：字段顺序与 computeHash 规范化形态一致（空数组省略），
	 * 保证 validateAndParse(serializePolicy(p)).computeHash() == p.computeHash() 往返稳定。
	 */
	private String serializePolicy(AuthorizationPolicy policy) {
		try {
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("schemaVersion", policy.getSchemaVersion());
			if (policy.getTemplateCode() != null) {
				root.put("templateCode", policy.getTemplateCode());
			}
			if (policy.getSubjectMode() != null) {
				root.put("subjectMode", policy.getSubjectMode().getCode());
			}
			if (policy.getIamUnavailableBehavior() != null) {
				root.put("iamUnavailableBehavior", policy.getIamUnavailableBehavior().getCode());
			}
			root.put("allowModelOnly", policy.getAllowModelOnly());
			if (policy.getRules() != null && !policy.getRules().isEmpty()) {
				List<Map<String, Object>> ruleList = new ArrayList<>();
				for (AuthorizationRule rule : policy.getRules()) {
					Map<String, Object> ruleMap = new LinkedHashMap<>();
					ruleMap.put("name", rule.getName());
					ruleMap.put("effect", rule.getEffect().getCode());
					if (rule.getCapabilityCodes() != null && !rule.getCapabilityCodes().isEmpty()) {
						ruleMap.put("capabilityCodes", rule.getCapabilityCodes());
					}
					if (rule.getActions() != null && !rule.getActions().isEmpty()) {
						List<String> actions = new ArrayList<>();
						for (AuthorizationAction action : rule.getActions()) {
							actions.add(action.getCode());
						}
						ruleMap.put("actions", actions);
					}
					if (rule.getObligations() != null && !rule.getObligations().isEmpty()) {
						List<String> obligations = new ArrayList<>();
						for (AuthorizationObligation obligation : rule.getObligations()) {
							obligations.add(obligation.getCode());
						}
						ruleMap.put("obligations", obligations);
					}
					if (rule.getMaskFields() != null && !rule.getMaskFields().isEmpty()) {
						ruleMap.put("maskFields", rule.getMaskFields());
					}
					ruleList.add(ruleMap);
				}
				root.put("rules", ruleList);
			}
			return POLICY_JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("策略序列化失败: " + ex.getMessage());
		}
	}

}
