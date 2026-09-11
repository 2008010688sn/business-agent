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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 输出义务执行器聚焦单测（PR-3c 交付物 5：MASK_FIELDS/FILTER_FIELDS 接现有脱敏口径）。
 *
 * @author James (PR-3c PEP 内核扩展)
 */
class OutputObligationApplierTest {

	private final OutputObligationApplier applier = new OutputObligationApplier();

	@Test
	void maskObligationMasksFieldsRecursively() {
		AuthorizationDecision decision = decision(true, List.of(AuthorizationObligation.MASK_FIELDS),
				List.of("phone"));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", "张三");
		payload.put("phone", "13800000000");
		Map<String, Object> nested = new LinkedHashMap<>();
		nested.put("phone", "13900000000");
		nested.put("city", "上海");
		payload.put("contact", nested);
		payload.put("rows", List.of(Map.of("phone", "13700000000", "ok", "1")));

		Map<String, Object> result = applier.applyObligations(decision, Set.of(), payload);

		assertEquals("张三", result.get("name"));
		assertEquals("****", result.get("phone"));
		@SuppressWarnings("unchecked")
		Map<String, Object> maskedContact = (Map<String, Object>) result.get("contact");
		assertEquals("****", maskedContact.get("phone"));
		assertEquals("上海", maskedContact.get("city"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
		assertEquals("****", rows.get(0).get("phone"));
		assertEquals("1", rows.get(0).get("ok"));
		// 原 payload 不被修改（副本处理）
		assertEquals("13800000000", payload.get("phone"));
	}

	@Test
	void filterObligationRemovesFields() {
		AuthorizationDecision decision = decision(true, List.of(AuthorizationObligation.FILTER_FIELDS),
				List.of("secret"));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("secret", "s3cr3t");
		payload.put("publicField", "v");

		Map<String, Object> result = applier.applyObligations(decision, Set.of(), payload);

		assertFalse(result.containsKey("secret"));
		assertEquals("v", result.get("publicField"));
	}

	@Test
	void emptyMaskFieldsFallsBackToContractDefaults() {
		AuthorizationDecision decision = decision(true, List.of(AuthorizationObligation.MASK_FIELDS),
				List.of());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("idCard", "310101199001011234");
		payload.put("name", "李四");

		Map<String, Object> result = applier.applyObligations(decision, Set.of("idCard"), payload);

		assertEquals("****", result.get("idCard"));
		assertEquals("李四", result.get("name"));
	}

	@Test
	void noObligationReturnsPayloadAsIs() {
		AuthorizationDecision decision = decision(true, List.of(), List.of());
		Map<String, Object> payload = Map.of("phone", "13800000000");

		Map<String, Object> result = applier.applyObligations(decision, Set.of("phone"), payload);

		assertEquals("13800000000", result.get("phone"));
		assertTrue(applier.shadowPreview(decision, Set.of("phone")).isEmpty());
	}

	@Test
	void nullDecisionOrPayloadReturnsOriginal() {
		Map<String, Object> payload = Map.of("a", "1");
		assertNull(applier.applyObligations(null, Set.of(), null));
		assertEquals(payload, applier.applyObligations(null, Set.of(), payload));
		AuthorizationDecision decision = decision(true, List.of(AuthorizationObligation.MASK_FIELDS),
				List.of("a"));
		// payload 为 null 时无输出可处理，原样返回 null（与 decision null 同口径）
		assertNull(applier.applyObligations(decision, Set.of(), null));
	}

	@Test
	void shadowPreviewComputesFieldsWithoutTouchingPayload() {
		AuthorizationDecision decision = decision(true, List.of(AuthorizationObligation.MASK_FIELDS),
				List.of());
		Set<String> preview = applier.shadowPreview(decision, Set.of("idCard", "bankNo"));
		assertEquals(Set.of("idCard", "bankNo"), preview);
	}

	private AuthorizationDecision decision(boolean allowed, List<AuthorizationObligation> obligations,
			List<String> maskFields) {
		return AuthorizationDecision.builder()
			.allowed(allowed)
			.reasonCode(null)
			.obligations(obligations)
			.maskFields(maskFields)
			.policyHash("hash-1")
			.evaluatedAt(Instant.now())
			.build();
	}

}
