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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationBindingUpsertReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationDecisionSimulateDbReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyCreateReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyDetailResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyModifyReq;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationBinding;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicyVersion;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicyStatus;
import com.sn68.agent.dataagent.authorization.model.PolicyValidator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationRequest;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationBindingMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationGrantMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationPolicyMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationPolicyVersionMapper;
import com.sn68.agent.dataagent.authorization.template.AuthorizationTemplate;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PAP 服务聚焦单测（PR-3b）：版本生命周期、发布不可变、绑定 CAS、simulate-db 契约一致性。
 *
 * <p>不启 Spring：Mapper 全部 Mockito mock（insert 以 doAnswer 回填主键），求值器用真实实例，
 * 保证 simulate-db 断言与 PR-3a 冻结求值器直连结果逐字段一致。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
class AgentAuthorizationPapServiceImplTest {

	private static final String TENANT = "tenant-1";

	private static final String POLICY_JSON = """
			{
			  "schemaVersion": 1,
			  "templateCode": "CALLER_READ_ONLY",
			  "subjectMode": "CALLER",
			  "iamUnavailableBehavior": "DENY",
			  "allowModelOnly": false,
			  "rules": [
			    {"name": "allow-read", "effect": "ALLOW", "capabilityCodes": ["*"], "actions": ["READ", "USE", "DISCOVER"]}
			  ]
			}
			""";

	/** EMPLOYEE 主体模式策略 JSON（B2 绑定一致性校验的正例数据）。 */
	private static final String EMPLOYEE_POLICY_JSON = """
			{
			  "schemaVersion": 1,
			  "templateCode": "DIGITAL_WORKER",
			  "subjectMode": "EMPLOYEE",
			  "iamUnavailableBehavior": "DENY",
			  "allowModelOnly": false,
			  "rules": [
			    {"name": "allow-read", "effect": "ALLOW", "capabilityCodes": ["*"], "actions": ["READ", "USE", "DISCOVER"]}
			  ]
			}
			""";

	private AgentAuthorizationPolicyMapper policyMapper;

	private AgentAuthorizationPolicyVersionMapper versionMapper;

	private AgentAuthorizationBindingMapper bindingMapper;

	private AgentAuthorizationGrantMapper grantMapper;

	private AgentAuthorizationPapServiceImpl papService;

	@BeforeEach
	void setUp() {
		policyMapper = mock(AgentAuthorizationPolicyMapper.class);
		versionMapper = mock(AgentAuthorizationPolicyVersionMapper.class);
		bindingMapper = mock(AgentAuthorizationBindingMapper.class);
		grantMapper = mock(AgentAuthorizationGrantMapper.class);
		papService = new AgentAuthorizationPapServiceImpl(policyMapper, versionMapper, bindingMapper, grantMapper,
				new AuthorizationPolicyEvaluator());
	}

	@Test
	void createPolicyByTemplateInsertsDraftVersionWithFrozenHash() {
		when(policyMapper.findByCodeAndTenant("code-1", TENANT)).thenReturn(null);
		AtomicReference<AgentAuthorizationPolicy> policyRef = new AtomicReference<>();
		doAnswer(invocation -> {
			AgentAuthorizationPolicy entity = invocation.getArgument(0);
			entity.setId(100L);
			policyRef.set(entity);
			return 1;
		}).when(policyMapper).insert(any(AgentAuthorizationPolicy.class));
		AtomicReference<AgentAuthorizationPolicyVersion> versionRef = new AtomicReference<>();
		doAnswer(invocation -> {
			AgentAuthorizationPolicyVersion entity = invocation.getArgument(0);
			entity.setId(900L);
			versionRef.set(entity);
			return 1;
		}).when(versionMapper).insert(any(AgentAuthorizationPolicyVersion.class));
		when(policyMapper.findByIdAndTenant(100L, TENANT)).thenAnswer(invocation -> policyRef.get());
		when(versionMapper.listByPolicyId(100L, TENANT)).thenAnswer(invocation -> List.of(versionRef.get()));

		AuthorizationPolicyCreateReq request = new AuthorizationPolicyCreateReq();
		request.setCode("code-1");
		request.setName("测试策略");
		request.setTemplateCode("caller_read_only");
		AuthorizationPolicyDetailResp detail = papService.createPolicy(request, TENANT);

		ArgumentCaptor<AgentAuthorizationPolicy> policyCaptor = ArgumentCaptor
			.forClass(AgentAuthorizationPolicy.class);
		verify(policyMapper).insert(policyCaptor.capture());
		assertEquals(AuthorizationPolicyStatus.DRAFT, policyCaptor.getValue().getStatus());
		assertEquals("CALLER_READ_ONLY", policyCaptor.getValue().getTemplateCode());
		ArgumentCaptor<AgentAuthorizationPolicyVersion> versionCaptor = ArgumentCaptor
			.forClass(AgentAuthorizationPolicyVersion.class);
		verify(versionMapper).insert(versionCaptor.capture());
		AgentAuthorizationPolicyVersion version = versionCaptor.getValue();
		assertEquals(1, version.getVersionNo());
		assertEquals(Boolean.FALSE, version.getPublished());
		// hash 必须来自冻结的 computeHash 算法（模板默认策略）
		assertEquals(AuthorizationTemplate.CALLER_READ_ONLY.defaultPolicy().computeHash(), version.getPolicyHash());
		assertNotNull(detail.getId());
	}

	@Test
	void createPolicyDuplicateCodeRejected() {
		when(policyMapper.findByCodeAndTenant("code-1", TENANT))
			.thenReturn(AgentAuthorizationPolicy.builder().id(1L).build());
		AuthorizationPolicyCreateReq request = new AuthorizationPolicyCreateReq();
		request.setCode("code-1");
		request.setName("重复");
		CheckedException ex = assertThrows(CheckedException.class,
				() -> papService.createPolicy(request, TENANT));
		assertTrue(ex.getMessage().contains("已存在"));
	}

	@Test
	void modifyPolicyPublishedPolicyRejected() {
		AgentAuthorizationPolicy published = AgentAuthorizationPolicy.builder()
			.id(100L)
			.tenantId(TENANT)
			.status(AuthorizationPolicyStatus.PUBLISHED)
			.build();
		when(policyMapper.findByIdAndTenant(100L, TENANT)).thenReturn(published);
		AuthorizationPolicyModifyReq request = new AuthorizationPolicyModifyReq();
		request.setName("新名字");
		CheckedException ex = assertThrows(CheckedException.class,
				() -> papService.modifyPolicy(100L, request, TENANT));
		assertTrue(ex.getMessage().contains("草稿"));
		verify(versionMapper, never()).updateDraft(anyLong(), anyString(), anyString(), anyString());
	}

	@Test
	void modifyPolicyOverwritesDraftWithStrictValidation() {
		AgentAuthorizationPolicy draft = AgentAuthorizationPolicy.builder()
			.id(100L)
			.tenantId(TENANT)
			.status(AuthorizationPolicyStatus.DRAFT)
			.build();
		when(policyMapper.findByIdAndTenant(100L, TENANT)).thenReturn(draft);
		AgentAuthorizationPolicyVersion draftVersion = AgentAuthorizationPolicyVersion.builder()
			.id(900L)
			.policyId(100L)
			.versionNo(1)
			.published(false)
			.build();
		when(versionMapper.findLatestByPolicyId(100L, TENANT)).thenReturn(draftVersion);
		when(versionMapper.updateDraft(eq(900L), eq(TENANT), eq(POLICY_JSON),
				eq(PolicyValidator.validateAndParse(POLICY_JSON).computeHash()))).thenReturn(1);
		when(versionMapper.listByPolicyId(100L, TENANT)).thenReturn(List.of(draftVersion));

		AuthorizationPolicyModifyReq request = new AuthorizationPolicyModifyReq();
		request.setPolicyJson(POLICY_JSON);
		papService.modifyPolicy(100L, request, TENANT);

		verify(versionMapper).updateDraft(eq(900L), eq(TENANT), eq(POLICY_JSON),
				eq(PolicyValidator.validateAndParse(POLICY_JSON).computeHash()));
	}

	@Test
	void publishPolicyPublishesDraftAndSwitchesPointer() {
		AgentAuthorizationPolicy policy = AgentAuthorizationPolicy.builder()
			.id(100L)
			.tenantId(TENANT)
			.status(AuthorizationPolicyStatus.DRAFT)
			.build();
		when(policyMapper.findByIdAndTenant(100L, TENANT)).thenReturn(policy);
		AgentAuthorizationPolicyVersion draft = AgentAuthorizationPolicyVersion.builder()
			.id(900L)
			.policyId(100L)
			.versionNo(1)
			.policyJson(POLICY_JSON)
			.policyHash(PolicyValidator.validateAndParse(POLICY_JSON).computeHash())
			.published(false)
			.build();
		when(versionMapper.findLatestByPolicyId(100L, TENANT)).thenReturn(draft);
		when(versionMapper.casPublish(900L, TENANT)).thenReturn(1);
		when(versionMapper.listByPolicyId(100L, TENANT)).thenReturn(List.of(draft));

		papService.publishPolicy(100L, TENANT);

		ArgumentCaptor<AgentAuthorizationPolicy> captor = ArgumentCaptor.forClass(AgentAuthorizationPolicy.class);
		verify(policyMapper).updateById(captor.capture());
		assertEquals(AuthorizationPolicyStatus.PUBLISHED, captor.getValue().getStatus());
		assertEquals(900L, captor.getValue().getCurrentVersionId());
	}

	@Test
	void publishPolicyHashMismatchRejected() {
		AgentAuthorizationPolicy policy = AgentAuthorizationPolicy.builder()
			.id(100L)
			.tenantId(TENANT)
			.status(AuthorizationPolicyStatus.DRAFT)
			.build();
		when(policyMapper.findByIdAndTenant(100L, TENANT)).thenReturn(policy);
		AgentAuthorizationPolicyVersion draft = AgentAuthorizationPolicyVersion.builder()
			.id(900L)
			.policyId(100L)
			.versionNo(1)
			.policyJson(POLICY_JSON)
			.policyHash("deadbeef")
			.published(false)
			.build();
		when(versionMapper.findLatestByPolicyId(100L, TENANT)).thenReturn(draft);

		CheckedException ex = assertThrows(CheckedException.class, () -> papService.publishPolicy(100L, TENANT));
		assertTrue(ex.getMessage().contains("hash"));
		verify(versionMapper, never()).casPublish(anyLong(), anyString());
	}

	@Test
	void publishPolicyWithoutDraftRejected() {
		AgentAuthorizationPolicy policy = AgentAuthorizationPolicy.builder()
			.id(100L)
			.tenantId(TENANT)
			.status(AuthorizationPolicyStatus.PUBLISHED)
			.currentVersionId(900L)
			.build();
		when(policyMapper.findByIdAndTenant(100L, TENANT)).thenReturn(policy);
		when(versionMapper.findLatestByPolicyId(100L, TENANT))
			.thenReturn(AgentAuthorizationPolicyVersion.builder().id(900L).published(true).build());

		CheckedException ex = assertThrows(CheckedException.class, () -> papService.publishPolicy(100L, TENANT));
		assertTrue(ex.getMessage().contains("草稿"));
	}

	@Test
	void upsertBindingFirstInsertStartsAtRevisionZero() {
		when(versionMapper.findByIdAndTenant(900L, TENANT)).thenReturn(AgentAuthorizationPolicyVersion.builder()
			.id(900L)
			.tenantId(TENANT)
			.policyJson(EMPLOYEE_POLICY_JSON)
			.published(true)
			.build());
		when(bindingMapper.findBinding(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 7L, AuthorizationEnvironment.PRODUCTION,
				TENANT)).thenReturn(null);
		AtomicReference<AgentAuthorizationBinding> bindingRef = new AtomicReference<>();
		doAnswer(invocation -> {
			AgentAuthorizationBinding entity = invocation.getArgument(0);
			entity.setId(500L);
			bindingRef.set(entity);
			return 1;
		}).when(bindingMapper).insert(any(AgentAuthorizationBinding.class));

		AgentAuthorizationBinding result = papService.upsertBinding(buildBindingReq(900L, null), TENANT);

		assertEquals(0L, result.getBindRevision());
		assertEquals(900L, result.getPolicyVersionId());
	}

	@Test
	void upsertBindingStaleRevisionRejectedWithCurrentRevision() {
		when(versionMapper.findByIdAndTenant(901L, TENANT)).thenReturn(AgentAuthorizationPolicyVersion.builder()
			.id(901L)
			.tenantId(TENANT)
			.policyJson(EMPLOYEE_POLICY_JSON)
			.published(true)
			.build());
		AgentAuthorizationBinding existing = AgentAuthorizationBinding.builder()
			.id(500L)
			.tenantId(TENANT)
			.ownerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE)
			.ownerId(7L)
			.environment(AuthorizationEnvironment.PRODUCTION)
			.policyVersionId(900L)
			.bindRevision(3L)
			.build();
		when(bindingMapper.findBinding(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 7L,
				AuthorizationEnvironment.PRODUCTION, TENANT)).thenReturn(existing);

		// 乐观锁版本过期（期望 2，实际 3）
		CheckedException stale = assertThrows(CheckedException.class,
				() -> papService.upsertBinding(buildBindingReq(901L, 2L), TENANT));
		assertTrue(stale.getMessage().contains("bindRevision=3"));

		// CAS 竞争失败（期望 3 但数据库侧已被并发推进）
		when(bindingMapper.casUpdateBinding(500L, TENANT, 901L, 3L)).thenReturn(0);
		CheckedException race = assertThrows(CheckedException.class,
				() -> papService.upsertBinding(buildBindingReq(901L, 3L), TENANT));
		assertTrue(race.getMessage().contains("bindRevision=3"));
	}

	// B2 绑定一致性校验（2026-08-19 评审修订）：数字员工误绑 CALLER 模式策略版本必须被拒绝
	@Test
	void upsertBindingDigitalEmployeeWithCallerModePolicyRejected() {
		when(versionMapper.findByIdAndTenant(900L, TENANT)).thenReturn(AgentAuthorizationPolicyVersion.builder()
			.id(900L)
			.tenantId(TENANT)
			.policyJson(POLICY_JSON)
			.published(true)
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> papService.upsertBinding(buildBindingReq(900L, null), TENANT));

		assertTrue(ex.getMessage().contains("主体模式"), ex.getMessage());
		assertTrue(ex.getMessage().contains("EMPLOYEE"), ex.getMessage());
		verify(bindingMapper, never()).insert(any(AgentAuthorizationBinding.class));
	}

	@Test
	void simulateFromDbMatchesFrozenEvaluatorContract() {
		AgentAuthorizationBinding binding = AgentAuthorizationBinding.builder()
			.id(500L)
			.tenantId(TENANT)
			.ownerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE)
			.ownerId(7L)
			.environment(AuthorizationEnvironment.PRODUCTION)
			.policyVersionId(900L)
			.bindRevision(0L)
			.build();
		when(bindingMapper.findBinding(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 7L,
				AuthorizationEnvironment.PRODUCTION, TENANT)).thenReturn(binding);
		when(versionMapper.findByIdAndTenant(900L, TENANT)).thenReturn(AgentAuthorizationPolicyVersion.builder()
			.id(900L)
			.tenantId(TENANT)
			.policyJson(POLICY_JSON)
			.policyHash(PolicyValidator.validateAndParse(POLICY_JSON).computeHash())
			.published(true)
			.build());

		// 命中 ALLOW 规则：READ 放行，与求值器直连逐字段一致
		AuthorizationDecisionResp allowed = papService.simulateFromDb(buildSimulateReq(AuthorizationAction.READ),
				TENANT);
		assertTrue(allowed.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, allowed.getReasonCode());
		assertEquals(PolicyValidator.validateAndParse(POLICY_JSON).computeHash(), allowed.getPolicyHash());

		// 未命中规则：WRITE 默认拒
		AuthorizationDecisionResp denied = papService.simulateFromDb(buildSimulateReq(AuthorizationAction.WRITE),
				TENANT);
		assertFalse(denied.isAllowed());
		assertEquals(DecisionReasonCode.POLICY_DENIED, denied.getReasonCode());

		// 与 PR-3a 冻结求值器直连结果一致性（同请求同策略）
		AuthorizationPolicy parsed = PolicyValidator.validateAndParse(POLICY_JSON);
		AuthorizationRequest directRequest = AuthorizationRequest.builder()
			.subjectKind(SubjectKind.CALLER)
			.capabilityCode("capability:test")
			.action(AuthorizationAction.READ)
			.build();
		var direct = new AuthorizationPolicyEvaluator().evaluate(parsed, directRequest);
		assertEquals(direct.isAllowed(), allowed.isAllowed());
		assertEquals(direct.getReasonCode(), allowed.getReasonCode());
		assertEquals(direct.getPolicyHash(), allowed.getPolicyHash());
	}

	@Test
	void simulateFromDbWithoutBindingReturnsMissingPolicy() {
		when(bindingMapper.findBinding(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 7L,
				AuthorizationEnvironment.PRODUCTION, TENANT)).thenReturn(null);

		AuthorizationDecisionResp resp = papService.simulateFromDb(buildSimulateReq(AuthorizationAction.READ), TENANT);

		assertFalse(resp.isAllowed());
		assertEquals(DecisionReasonCode.MISSING_POLICY, resp.getReasonCode());
		assertNull(resp.getPolicyHash());
	}

	private AuthorizationBindingUpsertReq buildBindingReq(Long policyVersionId, Long expectedRevision) {
		AuthorizationBindingUpsertReq request = new AuthorizationBindingUpsertReq();
		request.setOwnerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE);
		request.setOwnerId(7L);
		request.setEnvironment(AuthorizationEnvironment.PRODUCTION);
		request.setPolicyVersionId(policyVersionId);
		request.setExpectedBindRevision(expectedRevision);
		return request;
	}

	private AuthorizationDecisionSimulateDbReq buildSimulateReq(AuthorizationAction action) {
		AuthorizationDecisionSimulateDbReq request = new AuthorizationDecisionSimulateDbReq();
		request.setOwnerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE);
		request.setOwnerId(7L);
		request.setEnvironment(AuthorizationEnvironment.PRODUCTION);
		request.setSubjectKind(SubjectKind.CALLER);
		request.setCapabilityCode("capability:test");
		request.setAction(action);
		return request;
	}

}
