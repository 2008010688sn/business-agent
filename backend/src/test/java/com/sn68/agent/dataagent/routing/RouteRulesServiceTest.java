/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteRulesServiceTest {

	private final RouteRulesService service = new RouteRulesService(new ObjectMapper(), new RouteTextNormalizer());

	@Test
	void normalizesAndDeduplicatesFinalRuleFields() {
		var rules = service.normalize(Map.of("phrases", List.of(" 用箱量 ", "用箱量", "客户排行！")));

		assertEquals(List.of("用箱量", "客户排行"), rules.phrases());
		assertEquals(List.of(), rules.exact());
	}

	@Test
	void rejectsUnknownFieldsAndPositiveHardExcludeConflicts() {
		assertThrows(CheckedException.class,
				() -> service.normalize(Map.of("prefixes", List.of("用箱量"))));
		assertThrows(CheckedException.class, () -> service.normalize(
				Map.of("phrases", List.of("用箱量"), "hardExcludes", List.of("用箱量"))));
	}

	@Test
	void negativeConflictRemainsAllowedAsSoftSignal() {
		var rules = service.normalize(
				Map.of("phrases", List.of("用箱量"), "negativeExamples", List.of("用箱量")));

		assertEquals(List.of("用箱量"), rules.negativeExamples());
	}

	@Test
	void normalizesStandardGlobPatternsAndOmitsDisabledDefaults() {
		var rules = service.normalize(Map.of("positivePatterns", List.of("*下单*", "foo*", "*bar", "foo*baz"),
				"hardExcludePatterns", List.of("*怎么*下单*"), "allowFlowAutoSelect", true));

		assertEquals(List.of("*下单*", "foo*", "*bar", "foo*baz"), rules.positivePatterns());
		assertEquals(List.of("*怎么*下单*"), rules.hardExcludePatterns());
		assertTrue(rules.allowFlowAutoSelect());
		assertEquals(rules, service.parse(service.toJson(rules)));
		assertTrue(service.toMap(new com.sn68.agent.dataagent.routing.model.RouteRules(List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of())).keySet().stream()
				.noneMatch(key -> key.equals("positivePatterns") || key.equals("allowFlowAutoSelect")));
	}

	@Test
	void rejectsInvalidPatternSyntaxAndBooleanType() {
		assertThrows(CheckedException.class,
				() -> service.normalize(Map.of("positivePatterns", List.of("*"))));
		assertThrows(CheckedException.class,
				() -> service.normalize(Map.of("positivePatterns", List.of("   "))));
		assertThrows(CheckedException.class,
				() -> service.normalize(Map.of("hardExcludePatterns", List.of("怎么**下单"))));
		assertThrows(CheckedException.class,
				() -> service.normalize(Map.of("positivePatterns", List.of("a*b*c*d*e"))));
		assertThrows(CheckedException.class,
				() -> service.normalize(Map.of("allowFlowAutoSelect", "true")));
		assertThrows(CheckedException.class,
				() -> service.normalize(java.util.Collections.singletonMap("allowFlowAutoSelect", null)));
		assertThrows(CheckedException.class,
				() -> service.normalize(java.util.Collections.singletonMap("phrases", null)));
	}

	@Test
	void keepsLegacyJsonDefaultsStable() {
		var rules = service.parse("{\"exact\":[\"create\"],\"phrases\":[],\"aliases\":[],"
				+ "\"positiveExamples\":[],\"negativeExamples\":[],\"hardExcludes\":[]}");

		assertTrue(rules.positivePatterns().isEmpty());
		assertFalse(service.toMap(rules).containsKey("hardExcludePatterns"));
		assertFalse(service.toMap(rules).containsKey("allowFlowAutoSelect"));
	}

	@Test
	void fullResponseIncludesDisabledExtensionsForLegacyRules() {
		var response = service.toResponseDTO(service.parse("{\"exact\":[\"create\"],\"phrases\":[],\"aliases\":[],"
				+ "\"positiveExamples\":[],\"negativeExamples\":[],\"hardExcludes\":[]}"));

		assertEquals(List.of(), response.positivePatterns());
		assertEquals(List.of(), response.hardExcludePatterns());
		assertFalse(response.allowFlowAutoSelect());
	}

	@Test
	void responseFormattingDoesNotChangeCompactPersistenceJson() {
		var rules = service.normalize(Map.of("phrases", List.of("创建需求"), "positivePatterns", List.of("*给*下单*"),
				"hardExcludePatterns", List.of("*不要*下单*"), "allowFlowAutoSelect", true));

		String persistedBeforeResponseConversion = service.toJson(rules);
		service.toResponseDTO(rules);

		assertEquals(persistedBeforeResponseConversion, service.toJson(rules));
		assertTrue(persistedBeforeResponseConversion.contains("positivePatterns"));
		assertTrue(persistedBeforeResponseConversion.contains("allowFlowAutoSelect"));
	}

}
