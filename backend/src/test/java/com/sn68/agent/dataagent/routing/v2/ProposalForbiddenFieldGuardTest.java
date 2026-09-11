/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProposalForbiddenFieldGuard 禁止字段防线测试:字段名黑名单、ID 后缀形态、可疑值内容与自定义规则。
 */
class ProposalForbiddenFieldGuardTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ProposalForbiddenFieldGuard guard = new ProposalForbiddenFieldGuard();

	@Test
	void rejectsForbiddenFieldNames() {
		assertForbidden("{\"sql\":\"x\"}");
		assertForbidden("{\"password\":\"x\"}");
		assertForbidden("{\"idempotencyKey\":\"x\"}");
		assertForbidden("{\"tenant_scope\":\"x\"}");
		assertForbidden("{\"riskLevel\":\"HIGH\"}");
		assertForbidden("{\"approvalResult\":\"PASS\"}");
	}

	@Test
	void rejectsIdSuffixFieldNames() {
		assertForbidden("{\"customerId\":\"1\"}");
		assertForbidden("{\"orderIds\":[1,2]}");
		assertForbidden("{\"steps\":[{\"warehouse_id\":\"3\"}]}");
	}

	@Test
	void rejectsNestedForbiddenField() {
		assertForbidden("{\"steps\":[{\"bindings\":[{\"secret\":\"x\"}]}]}");
	}

	@Test
	void rejectsSqlStatementValue() {
		assertForbidden("{\"task\":\"select name from t_customer where status=1\"}");
		assertForbidden("{\"task\":\"DROP TABLE agent_runtime_plan\"}");
	}

	@Test
	void rejectsJsonPathValue() {
		assertForbidden("{\"task\":\"取 $.orders[0].amount 作为输入\"}");
	}

	@Test
	void rejectsRealUrlValue() {
		assertForbidden("{\"task\":\"回调 https://evil.example.com/hook\"}");
		assertForbidden("{\"task\":\"连接 jdbc:postgresql://10.0.0.1:5432/db\"}");
	}

	@Test
	void rejectsCredentialLikeValue() {
		assertForbidden("{\"task\":\"使用 password: hunter2 登录\"}");
		assertForbidden("{\"task\":\"Authorization Bearer abcdef123456\"}");
	}

	@Test
	void rejectsDatabaseIdAssignmentAndUuidValue() {
		assertForbidden("{\"task\":\"查询 id=12345 的客户\"}");
		assertForbidden("{\"task\":\"550e8400-e29b-41d4-a716-446655440000\"}");
	}

	@Test
	void rejectsTenantScopeAndApprovalConclusionValue() {
		assertForbidden("{\"task\":\"按 tenant_id 过滤\"}");
		assertForbidden("{\"task\":\"该单审批通过\"}");
	}

	@Test
	void doesNotEchoSuspiciousValueInMessage() {
		PlanCompileException ex = assertForbidden("{\"task\":\"Authorization Bearer supersecrettoken\"}");

		assertFalse(ex.getMessage().contains("supersecrettoken"));
		assertTrue(ex.getMessage().contains("$.task"));
	}

	@Test
	void allowsCleanProposal() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-lookup\",\"task\":\"查询客户信息\",\"bindings\":["
				+ "{\"targetPortHandle\":\"keyword\",\"sourceKind\":\"USER_INPUT\"}]}],"
				+ "\"controlEdges\":[],\"clarify\":null}";

		assertDoesNotThrow(() -> guard.verify(tree(json)));
	}

	@Test
	void supportsCustomConfiguration() {
		ProposalForbiddenFieldGuard custom = new ProposalForbiddenFieldGuard(Set.of(), List.of(),
				List.of(new ProposalForbiddenFieldGuard.GuardRule("INTERNAL_CODE",
						Pattern.compile("(?i)codename-x"))));

		PlanCompileException ex = assertThrows(PlanCompileException.class,
				() -> custom.verify(tree("{\"task\":\"启动 codename-x 行动\"}")));
		assertEquals(PlanCompileException.PROPOSAL_FORBIDDEN_CONTENT, ex.reasonCode());
		assertTrue(ex.getMessage().contains("INTERNAL_CODE"));
		// 自定义配置整体替换默认规则,默认黑名单不再生效
		assertDoesNotThrow(() -> custom.verify(tree("{\"sql\":\"select 1 from t\"}")));
	}

	private PlanCompileException assertForbidden(String json) {
		PlanCompileException ex = assertThrows(PlanCompileException.class, () -> guard.verify(tree(json)));
		assertEquals(PlanCompileException.PROPOSAL_FORBIDDEN_CONTENT, ex.reasonCode());
		return ex;
	}

	private static JsonNode tree(String json) {
		try {
			return MAPPER.readTree(json);
		}
		catch (JsonProcessingException ex) {
			throw new IllegalStateException("Test fixture JSON is invalid", ex);
		}
	}

}
