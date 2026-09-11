/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityCandidateSet;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityDescriptor;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityRiskLevel;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityVersionRef;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlanIdempotencyPolicy;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlanStep;
import com.sn68.agent.dataagent.routing.v2.model.CompiledStepBinding;
import com.sn68.agent.dataagent.routing.v2.model.PlanCompileContext;
import com.sn68.agent.dataagent.routing.v2.model.PortCardinality;
import com.sn68.agent.dataagent.routing.v2.model.PortSensitivity;
import com.sn68.agent.dataagent.routing.v2.model.PortSpec;
import com.sn68.agent.dataagent.routing.v2.model.RouteBindingSourceKind;
import com.sn68.agent.dataagent.routing.v2.model.RoutePortValueType;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanCompiler 编译流程单元测试:覆盖方案第十八章验收第 4/5/6 条与 W4 任务要求场景。
 */
class PlanCompilerTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final PlanCompiler compiler = new PlanCompiler(new RouteProposalV2Parser(OBJECT_MAPPER),
			new ProposalForbiddenFieldGuard(), OBJECT_MAPPER);

	// ---------- 成功场景 ----------

	@Test
	void compilesOutOfOrderLegalDag() {
		CompiledPlan plan = compiler.compile(orchestrationProposalJson(), candidates(), context());

		assertEquals(List.of("s1", "s2", "s3"), plan.steps().stream().map(CompiledPlanStep::stepKey).toList());
		assertEquals(List.of(0, 1, 2), plan.steps().stream().map(CompiledPlanStep::executionOrder).toList());
		assertEquals(List.of("s1"), plan.steps().get(1).dependsOn());
		assertEquals(List.of("s2"), plan.steps().get(2).dependsOn());
		assertEquals(RouteProposalMode.ORCHESTRATION, plan.mode());
		assertEquals("tenant-1", plan.tenantId());
		assertEquals(300L, plan.releaseId());
		assertEquals(2_000L, plan.steps().get(0).stepBudgetTokens());
		assertEquals(16_000L, plan.totalBudgetTokens());
		assertEquals(CompiledPlanIdempotencyPolicy.PLAN_HASH_STEP_KEY, plan.idempotencyPolicy());
		assertEquals(64, plan.planHash().length());
	}

	@Test
	void compiledBindingsAreTypedFromServerPorts() {
		CompiledPlan plan = compiler.compile(orchestrationProposalJson(), candidates(), context());

		CompiledPlanStep orderStep = plan.steps().get(1);
		assertEquals(2, orderStep.bindings().size());
		CompiledStepBinding customer = orderStep.bindings().get(0);
		assertEquals("customer", customer.targetPortHandle());
		assertEquals(RouteBindingSourceKind.STEP_OUTPUT, customer.sourceKind());
		assertEquals("s1", customer.sourceStepKey());
		assertEquals(RoutePortValueType.ID, customer.valueType());
		CompiledStepBinding quantity = orderStep.bindings().get(1);
		assertEquals("quantity", quantity.targetPortHandle());
		assertEquals(RouteBindingSourceKind.USER_INPUT, quantity.sourceKind());
		assertNull(quantity.sourceStepKey());
		assertEquals(RoutePortValueType.STATISTIC, quantity.valueType());
	}

	@Test
	void riskLevelTakenFromCandidateSetNotProposal() {
		CompiledPlan plan = compiler.compile(orchestrationProposalJson(), candidates(), context());

		assertEquals(CapabilityRiskLevel.WRITE, plan.riskLevel());
		assertTrue(plan.approvalRequired());
	}

	@Test
	void allowsSameCapabilityUnderMultipleStepKeys() {
		CompiledPlan plan = compiler.compile(lookupStepsJson(2, "[[\"s1\",\"s2\"]]"), candidates(), context());

		assertEquals(2, plan.steps().size());
		assertEquals("cap-lookup", plan.steps().get(0).capabilityHandle());
		assertEquals("cap-lookup", plan.steps().get(1).capabilityHandle());
		assertEquals(CapabilityRiskLevel.READ_ONLY, plan.riskLevel());
		assertFalse(plan.approvalRequired());
	}

	@Test
	void compilesChatProposalWithoutSteps() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"steps\":[],"
				+ "\"controlEdges\":[],\"clarify\":null}";

		CompiledPlan plan = compiler.compile(json, candidates(), context());

		assertTrue(plan.steps().isEmpty());
		assertEquals(CapabilityRiskLevel.READ_ONLY, plan.riskLevel());
		assertFalse(plan.approvalRequired());
	}

	@Test
	void compilesClarifyResumeProposal() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CLARIFY_RESUME\",\"steps\":[],"
				+ "\"controlEdges\":[],\"clarify\":{\"question\":\"请补充下单数量\"}}";

		CompiledPlan plan = compiler.compile(json, candidates(), context());

		assertEquals("请补充下单数量", plan.clarifyQuestion());
		assertTrue(plan.canonicalJson().contains("请补充下单数量"));
	}

	@Test
	void directModeMayCallOrchestrationCapabilityWithinDepthLimit() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"DIRECT\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-nested\",\"task\":\"执行子编排\"}]}";

		CompiledPlan plan = compiler.compile(json, candidates(), context());

		assertEquals(RouteProposalMode.DIRECT, plan.mode());
		assertEquals(1, plan.steps().size());
	}

	// ---------- planHash 与 canonical JSON ----------

	@Test
	void planHashIsStableForSemanticallyEqualProposals() {
		CompiledPlan first = compiler.compile(orchestrationProposalJson(), candidates(), context());
		CompiledPlan second = compiler.compile(reorderedOrchestrationProposalJson(), candidates(), context());

		assertEquals(first.canonicalJson(), second.canonicalJson());
		assertEquals(first.planHash(), second.planHash());
	}

	@Test
	void planHashChangesWhenSemanticsChange() {
		CompiledPlan base = compiler.compile(orchestrationProposalJson(), candidates(), context());
		CompiledPlan changed = compiler.compile(
				orchestrationProposalJson().replace("查询客户信息", "查询客户联系方式"), candidates(), context());

		assertNotEquals(base.planHash(), changed.planHash());
	}

	@Test
	void canonicalJsonIsCompactAndStable() {
		CompiledPlan plan = compiler.compile(orchestrationProposalJson(), candidates(), context());

		assertTrue(plan.canonicalJson().startsWith("{\"schemaVersion\":\"compiled-plan-v2\""));
		assertFalse(plan.canonicalJson().contains("\n"));
		assertFalse(plan.canonicalJson().contains(": "));
		assertTrue(plan.canonicalJson().contains("\"tenantId\":\"tenant-1\""));
	}

	// ---------- 拒绝场景 ----------

	@Test
	void rejectsCyclicDependencies() {
		assertCompileFails(lookupStepsJson(2, "[[\"s1\",\"s2\"],[\"s2\",\"s1\"]]"),
				PlanCompileException.PLAN_CYCLE_DETECTED);
	}

	@Test
	void rejectsSelfReference() {
		assertCompileFails(lookupStepsJson(1, "[[\"s1\",\"s1\"]]"), PlanCompileException.SELF_REFERENCE);
	}

	@Test
	void rejectsMissingStepReference() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-order\",\"task\":\"创建订单草稿\",\"bindings\":["
				+ "{\"targetPortHandle\":\"customer\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s9\","
				+ "\"sourcePortHandle\":\"customerRef\"}]}]}";
		assertCompileFails(json, PlanCompileException.MISSING_STEP_REFERENCE);
	}

	@Test
	void rejectsUnboundRequiredInput() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-order\",\"task\":\"创建订单草稿\",\"bindings\":["
				+ "{\"targetPortHandle\":\"quantity\",\"sourceKind\":\"USER_INPUT\"}]}]}";
		assertCompileFails(json, PlanCompileException.UNBOUND_REQUIRED_INPUT);
	}

	@Test
	void rejectsPortTypeMismatch() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\"},"
				+ "{\"stepKey\":\"s2\",\"capabilityHandle\":\"cap-report\",\"task\":\"汇总订单结果\",\"bindings\":["
				+ "{\"targetPortHandle\":\"orders\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s1\","
				+ "\"sourcePortHandle\":\"customerRef\"}]}]}";
		assertCompileFails(json, PlanCompileException.PORT_TYPE_MISMATCH);
	}

	@Test
	void rejectsSensitivePortAboveClearance() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-secret\",\"task\":\"读取密级引用\"},"
				+ "{\"stepKey\":\"s2\",\"capabilityHandle\":\"cap-order\",\"task\":\"创建订单草稿\",\"bindings\":["
				+ "{\"targetPortHandle\":\"customer\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s1\","
				+ "\"sourcePortHandle\":\"secretRef\"},"
				+ "{\"targetPortHandle\":\"quantity\",\"sourceKind\":\"USER_INPUT\"}]}]}";
		assertCompileFails(json, PlanCompileException.SENSITIVE_PORT_DENIED);
	}

	@Test
	void rejectsNaturalLanguageOutputBinding() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"e1\",\"capabilityHandle\":\"cap-echo\",\"task\":\"生成说明\"},"
				+ "{\"stepKey\":\"e2\",\"capabilityHandle\":\"cap-echo\",\"task\":\"复述说明\",\"bindings\":["
				+ "{\"targetPortHandle\":\"prompt\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"e1\","
				+ "\"sourcePortHandle\":\"answer\"}]}]}";
		assertCompileFails(json, PlanCompileException.NATURAL_LANGUAGE_BINDING_FORBIDDEN);
	}

	@Test
	void rejectsUnknownPort() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\",\"bindings\":["
				+ "{\"targetPortHandle\":\"nosuch\",\"sourceKind\":\"USER_INPUT\"}]}]}";
		assertCompileFails(json, PlanCompileException.PORT_NOT_FOUND);
	}

	@Test
	void rejectsSingleCardinalityBoundTwice() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\"},"
				+ "{\"stepKey\":\"s1b\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询备选客户\"},"
				+ "{\"stepKey\":\"s2\",\"capabilityHandle\":\"cap-order\",\"task\":\"创建订单草稿\",\"bindings\":["
				+ "{\"targetPortHandle\":\"customer\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s1\","
				+ "\"sourcePortHandle\":\"customerRef\"},"
				+ "{\"targetPortHandle\":\"customer\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s1b\","
				+ "\"sourcePortHandle\":\"customerRef\"}]}]}";
		assertCompileFails(json, PlanCompileException.PORT_CARDINALITY_VIOLATION);
	}

	@Test
	void rejectsDuplicateStepKeys() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\"},"
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-report\",\"task\":\"汇总订单结果\"}]}";
		assertCompileFails(json, PlanCompileException.STEP_KEY_DUPLICATE);
	}

	@Test
	void rejectsCapabilityOutsideCandidateSet() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-none\",\"task\":\"查询客户信息\"}]}";
		assertCompileFails(json, PlanCompileException.CAPABILITY_NOT_IN_CANDIDATE_SET);
	}

	@Test
	void rejectsStepCountAboveLimit() {
		assertCompileFails(lookupStepsJson(9, "[]"), PlanCompileException.STEP_LIMIT_EXCEEDED);
	}

	@Test
	void rejectsFanoutAboveLimit() {
		String edges = "[[\"s1\",\"s2\"],[\"s1\",\"s3\"],[\"s1\",\"s4\"],[\"s1\",\"s5\"],[\"s1\",\"s6\"]]";
		assertCompileFails(lookupStepsJson(6, edges), PlanCompileException.FANOUT_LIMIT_EXCEEDED);
	}

	@Test
	void rejectsNestedOrchestrationBeyondDepthLimit() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-nested\",\"task\":\"执行子编排\"}]}";
		assertCompileFails(json, PlanCompileException.NESTED_ORCHESTRATION_DEPTH_EXCEEDED);
	}

	@Test
	void rejectsChatModeWithSteps() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\"}]}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_MODE_INVALID);
	}

	@Test
	void rejectsClarifyOutsideClarifyResumeMode() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\"}],"
				+ "\"clarify\":{\"question\":\"要查哪个客户\"}}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_MODE_INVALID);
	}

	// ---------- 禁止字段防线 ----------

	@Test
	void rejectsForbiddenFieldName() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"sql\":\"select 1\"}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_FORBIDDEN_CONTENT);
	}

	@Test
	void rejectsIdSuffixFieldName() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\",\"bindings\":["
				+ "{\"targetPortHandle\":\"keyword\",\"sourceKind\":\"USER_INPUT\",\"customerId\":\"123\"}]}]}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_FORBIDDEN_CONTENT);
	}

	@Test
	void rejectsSqlStatementInTask() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\","
				+ "\"task\":\"select name from t_customer where status=1\"}]}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_FORBIDDEN_CONTENT);
	}

	@Test
	void rejectsRealUrlInTask() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\","
				+ "\"task\":\"查询后回调 https://evil.example.com/hook\"}]}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_FORBIDDEN_CONTENT);
	}

	// ---------- 严格解析 ----------

	@Test
	void rejectsUnknownBenignFieldAsSchemaInvalid() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\","
				+ "\"note\":\"备注\"}]}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsWrongSchemaVersion() {
		String json = "{\"schemaVersion\":\"route-proposal-v1\",\"mode\":\"CHAT\"}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsTrailingJsonContent() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\"} {}";
		assertCompileFails(json, PlanCompileException.PROPOSAL_JSON_INVALID);
	}

	// ---------- 夹具 ----------

	private PlanCompileException assertCompileFails(String proposalJson, String reasonCode) {
		PlanCompileException ex = assertThrows(PlanCompileException.class,
				() -> compiler.compile(proposalJson, candidates(), context()));
		assertEquals(reasonCode, ex.reasonCode());
		return ex;
	}

	/** 乱序合法 DAG:steps 按 s3、s2、s1 声明,依赖 s1→s2→s3,V1 会拒绝而 V2 必须编译成功。 */
	private static String orchestrationProposalJson() {
		return "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s3\",\"capabilityHandle\":\"cap-report\",\"task\":\"汇总订单结果\",\"bindings\":["
				+ "{\"targetPortHandle\":\"orders\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s2\","
				+ "\"sourcePortHandle\":\"orderDraft\"}]},"
				+ "{\"stepKey\":\"s2\",\"capabilityHandle\":\"cap-order\",\"task\":\"创建订单草稿\",\"bindings\":["
				+ "{\"targetPortHandle\":\"customer\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s1\","
				+ "\"sourcePortHandle\":\"customerRef\"},"
				+ "{\"targetPortHandle\":\"quantity\",\"sourceKind\":\"USER_INPUT\"}]},"
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\",\"bindings\":[]}],"
				+ "\"controlEdges\":[[\"s1\",\"s2\"]],\"clarify\":null}";
	}

	/** 与 orchestrationProposalJson 语义相同,但对象字段顺序、步骤顺序、绑定顺序全部打乱。 */
	private static String reorderedOrchestrationProposalJson() {
		return "{\"mode\":\"ORCHESTRATION\",\"controlEdges\":[[\"s1\",\"s2\"]],\"clarify\":null,\"steps\":["
				+ "{\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\",\"stepKey\":\"s1\"},"
				+ "{\"bindings\":[{\"sourceKind\":\"USER_INPUT\",\"targetPortHandle\":\"quantity\"},"
				+ "{\"sourcePortHandle\":\"customerRef\",\"targetPortHandle\":\"customer\","
				+ "\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s1\"}],"
				+ "\"task\":\"创建订单草稿\",\"stepKey\":\"s2\",\"capabilityHandle\":\"cap-order\"},"
				+ "{\"task\":\"汇总订单结果\",\"stepKey\":\"s3\",\"capabilityHandle\":\"cap-report\","
				+ "\"bindings\":[{\"targetPortHandle\":\"orders\",\"sourceKind\":\"STEP_OUTPUT\","
				+ "\"sourceStepKey\":\"s2\",\"sourcePortHandle\":\"orderDraft\"}]}],"
				+ "\"schemaVersion\":\"route-proposal-v2\"}";
	}

	/** 生成 count 个 cap-lookup 步骤(s1..sN)与给定控制边的编排提案。 */
	private static String lookupStepsJson(int count, String edgesJson) {
		StringBuilder steps = new StringBuilder();
		for (int i = 1; i <= count; i++) {
			if (i > 1) {
				steps.append(',');
			}
			steps.append("{\"stepKey\":\"s").append(i)
				.append("\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\"}");
		}
		return "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":[" + steps
				+ "],\"controlEdges\":" + edgesJson + "}";
	}

	private static CapabilityCandidateSet candidates() {
		return CapabilityCandidateSet.of(List.of(
				capability("cap-lookup",
						List.of(port("keyword", RoutePortValueType.FILTER, true, PortSensitivity.PUBLIC)),
						List.of(port("customerRef", RoutePortValueType.ID, false, PortSensitivity.INTERNAL)),
						CapabilityRiskLevel.READ_ONLY, false),
				capability("cap-order",
						List.of(port("customer", RoutePortValueType.ID, false, PortSensitivity.INTERNAL),
								port("quantity", RoutePortValueType.STATISTIC, true, PortSensitivity.PUBLIC)),
						List.of(port("orderDraft", RoutePortValueType.STRUCT, false, PortSensitivity.INTERNAL)),
						CapabilityRiskLevel.WRITE, false),
				capability("cap-report",
						List.of(new PortSpec("orders", RoutePortValueType.STRUCT, true, PortCardinality.MULTI,
								PortSensitivity.INTERNAL)),
						List.of(port("summary", RoutePortValueType.STATISTIC, false, PortSensitivity.PUBLIC)),
						CapabilityRiskLevel.READ_ONLY, false),
				capability("cap-secret", List.of(),
						List.of(port("secretRef", RoutePortValueType.ID, false, PortSensitivity.CONFIDENTIAL)),
						CapabilityRiskLevel.READ_ONLY, false),
				capability("cap-echo",
						List.of(port("prompt", RoutePortValueType.TEXT, true, PortSensitivity.PUBLIC)),
						List.of(port("answer", RoutePortValueType.TEXT, false, PortSensitivity.PUBLIC)),
						CapabilityRiskLevel.READ_ONLY, false),
				capability("cap-nested", List.of(), List.of(), CapabilityRiskLevel.READ_ONLY, true)));
	}

	private static CapabilityDescriptor capability(String handle, List<PortSpec> inputs, List<PortSpec> outputs,
			CapabilityRiskLevel risk, boolean orchestration) {
		return new CapabilityDescriptor(handle, inputs, outputs, risk, new CapabilityVersionRef("SKILL", 1L, 11L, null),
				orchestration);
	}

	private static PortSpec port(String handle, RoutePortValueType type, boolean nullable,
			PortSensitivity sensitivity) {
		return new PortSpec(handle, type, nullable, PortCardinality.SINGLE, sensitivity);
	}

	private static PlanCompileContext context() {
		return new PlanCompileContext("tenant-1", 100L, 200L, 300L, "{\"policy\":\"default\"}",
				Instant.parse("2026-08-12T12:00:00Z"), 2_000L, 16_000L, PortSensitivity.INTERNAL);
	}

}
