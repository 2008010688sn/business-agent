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
package com.sn68.agent.dataagent.authorization.pep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationBinding;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicyVersion;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.observability.AuthorizationMetrics;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationBindingMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationPolicyVersionMapper;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PEP 运行时策略串联器聚焦单测（PR-3c 交付物 1：PDP 串联 + 缓存失效 + revision 比对）。
 *
 * <p>求值器用 PR-3a 冻结真实实例，Mapper 全部 Mockito mock：断言串联语义与冻结契约一致。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
class RuntimePolicyEvaluatorTest {

	private static final String TENANT = "1";

	private static final String POLICY_JSON = """
			{
			  "schemaVersion": 1,
			  "templateCode": "CALLER_READ_ONLY",
			  "subjectMode": "CALLER",
			  "iamUnavailableBehavior": "DENY",
			  "allowModelOnly": false,
			  "rules": [
			    {"name": "allow-execute", "effect": "ALLOW", "capabilityCodes": ["*"], "actions": ["EXECUTE"]}
			  ]
			}
			""";

	private AgentAuthorizationBindingMapper bindingMapper;

	private AgentAuthorizationPolicyVersionMapper versionMapper;

	private EmployeeReleaseSnapshotResolver employeeSnapshotResolver;

	private RuntimePolicyEvaluator evaluator;

	@BeforeEach
	void setUp() {
		bindingMapper = mock(AgentAuthorizationBindingMapper.class);
		versionMapper = mock(AgentAuthorizationPolicyVersionMapper.class);
		employeeSnapshotResolver = mock(EmployeeReleaseSnapshotResolver.class);
		evaluator = new RuntimePolicyEvaluator(bindingMapper, versionMapper, new AuthorizationPolicyEvaluator(),
				new PepAuthorizationProperties(), employeeSnapshotResolver, AuthorizationMetrics.noop());
	}

	@Test
	void missingBindingEvaluatesAsMissingPolicy() {
		when(bindingMapper.findBinding(AuthorizationOwnerType.DATA_AGENT, 10L,
				AuthorizationEnvironment.PRODUCTION, TENANT)).thenReturn(null);

		PepDecisionResult result = evaluator.evaluate(context(null, null));

		assertFalse(result.allowed());
		assertEquals(DecisionReasonCode.MISSING_POLICY, result.reasonCode());
		assertNull(result.getPolicyVersionId());
		assertNull(result.getBindRevision());
		assertNotNull(result.getDecisionId());
		assertEquals(SubjectKind.CALLER, result.getSubjectKind());
	}

	@Test
	void boundPublishedPolicyEvaluatesAllowAndPropagatesPointers() {
		stubBinding(900L, 3L);
		stubVersion(900L, true, POLICY_JSON);

		PepDecisionResult result = evaluator.evaluate(context(null, null));

		assertTrue(result.allowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, result.reasonCode());
		assertEquals(900L, result.getPolicyVersionId());
		assertEquals(3L, result.getBindRevision());
		assertNotNull(result.getDecision().getPolicyHash());
	}

	@Test
	void sameBindRevisionReusesCacheAcrossEvaluations() {
		stubBinding(900L, 3L);
		stubVersion(900L, true, POLICY_JSON);

		evaluator.evaluate(context(null, null));
		evaluator.evaluate(context(null, null));

		verify(versionMapper, times(1)).findByIdAndTenant(900L, TENANT);
	}

	@Test
	void bindRevisionChangeInvalidatesCacheAndReloads() {
		stubBinding(900L, 3L);
		stubVersion(900L, true, POLICY_JSON);
		evaluator.evaluate(context(null, null));

		// CAS 切换策略版本后 bind_revision 变化：下次求值必须重新解析版本
		stubBinding(901L, 4L);
		stubVersion(901L, true, POLICY_JSON);
		PepDecisionResult second = evaluator.evaluate(context(null, null));

		verify(versionMapper).findByIdAndTenant(900L, TENANT);
		verify(versionMapper).findByIdAndTenant(901L, TENANT);
		assertEquals(901L, second.getPolicyVersionId());
		assertEquals(4L, second.getBindRevision());
	}

	@Test
	void unpublishedVersionTreatedAsMissingPolicy() {
		stubBinding(900L, 3L);
		stubVersion(900L, false, POLICY_JSON);

		PepDecisionResult result = evaluator.evaluate(context(null, null));

		assertFalse(result.allowed());
		assertEquals(DecisionReasonCode.MISSING_POLICY, result.reasonCode());
	}

	@Test
	void corruptPolicyJsonFailsFast() {
		stubBinding(900L, 3L);
		stubVersion(900L, true, "{ not-valid-json");

		// 策略 JSON 损坏属配置错误：快速失败（CheckedException 静态工厂口径），不静默降级
		CheckedException ex = assertThrows(CheckedException.class,
				() -> evaluator.evaluate(context(null, null)));
		assertTrue(ex.getMessage().contains("策略 JSON 格式错误"));
	}

	@Test
	void authRevisionMismatchMarkedWithoutNewReasonCode() {
		stubBinding(900L, 3L);
		stubVersion(900L, true, POLICY_JSON);

		PepDecisionResult mismatch = evaluator.evaluate(context(5L, 6L));
		assertFalse(mismatch.getRevisionMatched());
		// revision 不一致只标记不拒绝：PDP 原因码契约冻结，不新增 WAITING 类原因码
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, mismatch.reasonCode());

		PepDecisionResult partial = evaluator.evaluate(context(5L, null));
		assertNull(partial.getRevisionMatched());
	}

	@Test
	void dataAgentOwnerSkipsSnapshotResolutionAndKeepsLegacyBehavior() {
		stubBinding(900L, 3L);
		stubVersion(900L, true, POLICY_JSON);

		PepDecisionResult result = evaluator.evaluate(context(null, null, "900-frozen"));

		// PR-3d：非数字员工 owner 不触发快照解析，策略绑定版本传 null → PDP 跳过比对，现网语义不变
		verifyNoInteractions(employeeSnapshotResolver);
		assertTrue(result.allowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, result.reasonCode());
	}

	@Test
	void employeeSnapshotVersionMatchEvaluatesAllow() {
		stubEmployeeBinding();
		stubVersion(900L, true, POLICY_JSON);
		// 快照收录 skillVersionId=900，与请求版本一致 → 比对一致放行
		stubSnapshot(List.of(new EmployeeReleaseSnapshot.CapabilityRef(900L, 50L)), "spec-hash-1");

		PepDecisionResult result = evaluator.evaluate(employeeContext("900"));

		assertTrue(result.allowed());
		assertEquals(DecisionReasonCode.POLICY_ALLOWED, result.reasonCode());
	}

	@Test
	void employeeLiveVersionOutsideSnapshotDeniesByVersionMismatch() {
		stubEmployeeBinding();
		stubVersion(900L, true, POLICY_JSON);
		// 请求 live 版本 901（未被快照收录）→ 策略绑定版本回落为快照锚点 → 不一致即 CAPABILITY_VERSION_MISMATCH
		stubSnapshot(List.of(new EmployeeReleaseSnapshot.CapabilityRef(900L, 50L)), "spec-hash-1");

		PepDecisionResult result = evaluator.evaluate(employeeContext("901"));

		assertFalse(result.allowed());
		assertEquals(DecisionReasonCode.CAPABILITY_VERSION_MISMATCH, result.reasonCode());
	}

	@Test
	void employeeSnapshotResolutionFailureFailsFast() {
		stubEmployeeBinding();
		stubVersion(900L, true, POLICY_JSON);
		// 快照缺失/RETIRED/摘要篡改由解析器失败关闭；SHADOW/ENFORCE 的差异由接缝侧（PepInvocationInspector）裁决
		when(employeeSnapshotResolver.resolveActive(TENANT, 77L, AuthorizationEnvironment.PRODUCTION.getCode()))
				.thenThrow(CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：员工在环境 PRODUCTION 无激活部署"));

		CheckedException ex = assertThrows(CheckedException.class, () -> evaluator.evaluate(employeeContext("900")));
		assertTrue(ex.getMessage().contains("失败关闭"));
	}

	private void stubBinding(Long versionId, Long bindRevision) {
		AgentAuthorizationBinding binding = AgentAuthorizationBinding.builder()
			.policyVersionId(versionId)
			.bindRevision(bindRevision)
			.build();
		when(bindingMapper.findBinding(AuthorizationOwnerType.DATA_AGENT, 10L,
				AuthorizationEnvironment.PRODUCTION, TENANT)).thenReturn(binding);
	}

	private void stubVersion(Long versionId, boolean published, String policyJson) {
		AgentAuthorizationPolicyVersion version = AgentAuthorizationPolicyVersion.builder()
			.id(versionId)
			.published(published)
			.policyJson(policyJson)
			.build();
		when(versionMapper.findByIdAndTenant(versionId, TENANT)).thenReturn(version);
	}

	private void stubEmployeeBinding() {
		AgentAuthorizationBinding binding = AgentAuthorizationBinding.builder()
			.policyVersionId(900L)
			.bindRevision(3L)
			.build();
		when(bindingMapper.findBinding(AuthorizationOwnerType.DIGITAL_EMPLOYEE, 77L,
				AuthorizationEnvironment.PRODUCTION, TENANT)).thenReturn(binding);
	}

	private void stubSnapshot(List<EmployeeReleaseSnapshot.CapabilityRef> capabilities, String specHash) {
		EmployeeReleaseSnapshot snapshot = new EmployeeReleaseSnapshot(500L, 77L, 1, "1", specHash, "E-001",
				"员工A", null, null, null, null, null, null, null, java.util.Map.of(), capabilities, List.of(), null);
		when(employeeSnapshotResolver.resolveActive(TENANT, 77L, AuthorizationEnvironment.PRODUCTION.getCode()))
				.thenReturn(snapshot);
	}

	private PepDecisionContext employeeContext(String capabilityVersion) {
		return PepDecisionContext.builder()
			.tenantId(TENANT)
			.ownerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE)
			.ownerId(77L)
			.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
			.subjectId("77")
			.capabilityCode("tool-1")
			.capabilityVersion(capabilityVersion)
			.action(AuthorizationAction.EXECUTE)
			.build();
	}

	private PepDecisionContext context(Long expectedRevision, Long currentRevision) {
		return context(expectedRevision, currentRevision, null);
	}

	private PepDecisionContext context(Long expectedRevision, Long currentRevision, String capabilityVersion) {
		return PepDecisionContext.builder()
			.tenantId(TENANT)
			.ownerType(AuthorizationOwnerType.DATA_AGENT)
			.ownerId(10L)
			.subjectKind(SubjectKind.CALLER)
			.subjectId("user-1")
			.capabilityCode("tool-1")
			.capabilityVersion(capabilityVersion)
			.action(AuthorizationAction.EXECUTE)
			.expectedAuthRevision(expectedRevision)
			.currentAuthRevision(currentRevision)
			.build();
	}

}
