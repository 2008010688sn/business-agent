/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowDefinitionValidatorTest {

	private final FlowDefinitionValidator validator = new FlowDefinitionValidator(new ObjectMapper());

	@Test
	void acceptsDeterministicFlowWithConfirmBeforeExecute() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "confirm",
				"nodes", List.of(Map.of("id", "confirm", "type", "confirm", "next", "execute"),
						Map.of("id", "execute", "type", "execute", "next", "end", "config",
								Map.of("resourceVersionId", 9, "resultQuery", Map.of("resourceVersionId", 10,
										"completedCondition", Map.of("op", "eq", "path", "/result/found", "value", true)))),
						Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void acceptsNonIdempotentWriteWithoutUnknownResultQuery() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "confirm",
				"nodes", List.of(Map.of("id", "confirm", "type", "confirm", "next", "execute"),
						Map.of("id", "execute", "type", "execute", "next", "end", "config",
								Map.of("resourceVersionId", 9)), Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void validatesTemporalSchemaAndExecuteSuccessCondition() {
		Map<String, Object> valid = Map.of("schemaVersion", "skill-flow/v1", "startNode", "confirm",
				"variablesSchema", Map.of("type", "object", "properties", Map.of("arrivalTime",
						Map.of("type", "string", "x-temporal", Map.of("kind", "DATE_OR_DATE_TIME",
								"targetType", "INSTANT", "dateLanding", "START_OF_DAY")))),
				"nodes", List.of(Map.of("id", "confirm", "type", "confirm", "next", "execute"),
						Map.of("id", "execute", "type", "execute", "next", "end", "config", Map.of(
								"resourceVersionId", 9, "successCondition", Map.of("op", "all", "conditions",
										List.of(Map.of("op", "eq", "path", "/result/success", "value", true),
												Map.of("op", "notEmpty", "path", "/result/demandNo"))))),
						Map.of("id", "end", "type", "end")));
		Map<String, Object> invalid = Map.of("schemaVersion", "skill-flow/v1", "startNode", "end",
				"variablesSchema", Map.of("type", "object", "properties", Map.of("arrivalTime",
						Map.of("type", "string", "x-temporal", Map.of("kind", "GUESS")))),
				"nodes", List.of(Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(valid).isEmpty());
		assertTrue(validator.validate(invalid).stream().anyMatch(error -> error.contains("x-temporal")));
	}

	@Test
	void rejectsUnsupportedTemporalTargetAndDateLanding() {
		Map<String, Object> invalid = Map.of("schemaVersion", "skill-flow/v1", "startNode", "end",
				"variablesSchema", Map.of("type", "object", "properties", Map.of("arrivalTime",
						Map.of("type", "string", "x-temporal", Map.of("kind", "DATE_OR_DATE_TIME",
								"targetType", "UTC_TEXT", "dateLanding", "END_OF_DAY")))),
				"nodes", List.of(Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(invalid);
		assertTrue(errors.stream().anyMatch(error -> error.contains("targetType")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("dateLanding")));
	}

	@Test
	void rejectsIncompleteUnknownResultQuery() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "confirm",
				"nodes", List.of(Map.of("id", "confirm", "type", "confirm", "next", "execute"),
						Map.of("id", "execute", "type", "execute", "next", "end", "config",
								Map.of("resourceVersionId", 9, "resultQuery", Map.of("pendingText", "pending"))),
						Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);
		assertTrue(errors.stream().anyMatch(error -> error.contains("resultQuery resourceVersionId")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("completedCondition")));
	}

	@Test
	void rejectsScriptsAndUnconfirmedWrite() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "execute",
				"nodes", List.of(Map.of("id", "execute", "type", "execute", "next", "end", "config",
						Map.of("script", "doSomething()")), Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);
		assertFalse(errors.isEmpty());
		assertTrue(errors.stream().anyMatch(error -> error.contains("scripts")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("confirm")));
	}

	@Test
	void rejectsMissingTargetsInNodeConfig() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "select",
				"nodes", List.of(
						Map.of("id", "select", "type", "select", "next", "validate", "config",
								Map.of("emptyNext", "missing-empty")),
						Map.of("id", "validate", "type", "validate", "next", "execute", "config",
								Map.of("invalidNext", "missing-invalid")),
						Map.of("id", "execute", "type", "execute", "next", "end", "config",
								Map.of("resourceVersionId", 9, "resultQuery", Map.of("resourceVersionId", 10,
										"completedCondition", Map.of("op", "eq", "path", "/done", "value", true),
										"next", "missing-result"))),
						Map.of("id", "confirm", "type", "confirm", "next", "execute"),
						Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);
		assertTrue(errors.stream().anyMatch(error -> error.contains("missing-empty")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("missing-invalid")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("missing-result")));
	}

	@Test
	void rejectsBranchThatReachesExecuteBeforeConfirm() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "switch",
				"nodes", List.of(Map.of("id", "switch", "type", "switch", "next", "confirm", "branches",
						List.of(Map.of("condition", Map.of("op", "eq", "path", "/input/fast", "value", true),
								"next", "execute"))),
					Map.of("id", "confirm", "type", "confirm", "next", "execute"),
					Map.of("id", "execute", "type", "execute", "next", "end", "config",
							Map.of("resourceVersionId", 9)), Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("reachable before a confirm")));
	}

	@Test
	void acceptsCycleThatOnlyReachesExecuteAfterConfirm() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect",
				"nodes", List.of(Map.of("id", "collect", "type", "collect", "next", "confirm"),
						Map.of("id", "confirm", "type", "confirm", "next", "loop"),
						Map.of("id", "loop", "type", "switch", "next", "execute", "branches",
								List.of(Map.of("condition", Map.of("op", "eq", "path", "/loop", "value", true),
										"next", "loop"))),
						Map.of("id", "execute", "type", "execute", "next", "end", "config",
								Map.of("resourceVersionId", 9)), Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void rejectsResolverDependencyCycleAndConflictingParallelOutputs() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "a", "nodes",
				List.of(Map.of("id", "a", "type", "resolve", "next", "end", "config", Map.of("dependsOn",
						List.of("b"), "parallelResolvers", List.of(Map.of("id", "one", "resourceVersionId", 1,
								"parallelSafe", true, "outputPath", "/resolved/shared"), Map.of("id", "two",
								"resourceVersionId", 2, "parallelSafe", true, "outputPath", "/resolved/shared")))),
					Map.of("id", "b", "type", "resolve", "next", "end", "config", Map.of("dependsOn",
							List.of("a"), "resourceVersionId", 3)),
					Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);
		assertTrue(errors.stream().anyMatch(error -> error.contains("dependency graph contains a cycle")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("output paths conflict")));
	}

	@Test
	void defaultsUiModeToChatAndAcceptsSafeExplicitModes() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "review", "config", Map.of()),
						Map.of("id", "review", "type", "review", "next", "form", "config", Map.of("uiMode",
								"SUMMARY", "displayFields", List.of(Map.of("label", "客户", "path", "/input/companyName")))),
						Map.of("id", "form", "type", "collect", "next", "end", "config", Map.of("uiMode", "FORM",
								"uiSchema", Map.of("type", "object", "properties",
										Map.of("companyName", Map.of("type", "string"))))),
						Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void rejectsUnsafeOrIncompleteUiModes() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "bad-mode", "nodes",
				List.of(Map.of("id", "bad-mode", "type", "collect", "next", "bad-form", "config",
						Map.of("uiMode", "TABLE")), Map.of("id", "bad-form", "type", "collect", "next", "bad-summary",
								"config", Map.of("uiMode", "FORM", "uiSchema", Map.of("type", "object", "properties",
										Map.of("companyId", Map.of("type", "string"))))),
						Map.of("id", "bad-summary", "type", "review", "next", "end", "config",
								Map.of("uiMode", "SUMMARY")), Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);
		assertTrue(errors.stream().anyMatch(error -> error.contains("Unsupported uiMode")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("must not expose business ID")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("must define displayFields")));
	}

	@Test
	void rejectsBusinessIdsInSummaryFieldsAndCollections() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "summary", "nodes",
				List.of(Map.of("id", "summary", "type", "review", "next", "end", "config", Map.of("uiMode",
						"SUMMARY", "displayFields", List.of(Map.of("label", "客户", "path", "/input/companyId")),
						"displayCollections", List.of(Map.of("path", "/input/productList", "itemFields",
								List.of(Map.of("label", "商品", "path", "/productId")))))),
						Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("must not expose business ID paths")));
	}

	@Test
	void acceptsValidLiteralMappings() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "end", "config", Map.of(
						"schema", Map.of("type", "object", "properties", Map.of("type", Map.of("type", "string"))),
						"literalMappings", Map.of("type", Map.of("3", List.of("送箱", "SEND_BOX"))))),
						Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void rejectsInvalidOrConflictingLiteralMappings() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "end", "config", Map.of(
						"schema", Map.of("type", "object", "properties", Map.of("type", Map.of("type", "string"))),
						"literalMappings", Map.of("missing", Map.of("3", List.of("未知")), "type",
								Map.of("0", List.of("送箱"), "3", List.of("送箱"), "4", "退箱")))),
						Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("missing is not defined in schema")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("alias conflicts")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("aliases must be a non-empty array")));
	}

	@Test
	void acceptsExplicitEmptyActionsForV2ChatCollection() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v2", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "end", "config",
						Map.of("uiActions", List.of())), Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void rejectsV2WaitingNodesWithoutExplicitActions() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v2", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "resolve", "config", Map.of()),
						Map.of("id", "resolve", "type", "resolve", "next", "end", "config",
								Map.of("forEach", "/input/items")), Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("waiting node collect")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("waiting node resolve")));
	}

	@Test
	void rejectsIncompleteCancelPolicyAndMissingRefreshTarget() {
		Map<String, Object> cancelAction = Map.of("actionId", "cancel", "type", "CANCEL", "label", "取消");
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v2", "startNode", "review", "nodes",
				List.of(Map.of("id", "review", "type", "review", "next", "end", "config",
						Map.of("uiActions", List.of(cancelAction), "refreshNext", "missing",
								"refreshRoutes", List.of("invalid-route"), "issuesPaths", List.of("invalid-pointer"), "cancelPolicy",
								Map.of("question", "确认取消？", "confirmLabel", "确认", "keepLabel", "继续",
										"successText", "已取消"))), Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("cancelPolicy.continueText")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("missing")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("refreshRoutes entries must be objects")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("issuesPaths must contain JSON Pointers")));
	}

	@Test
	void acceptsScopedExtractionConfiguration() {
		Map<String, Object> schema = Map.of("type", "object", "properties",
				Map.of("companyId", Map.of("type", "string"), "companyName", Map.of("type", "string")));
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "extract",
				"nodes", List.of(Map.of("id", "extract", "type", "extract", "next", "end", "config", Map.of(
						"outputPath", "/input", "requiredAnyPaths", List.of("/input/companyId", "/input/companyName"),
						"schema", schema, "extraction", Map.of("modelPolicy", "IF_UNRESOLVED", "schemaMode",
								"CONFIGURED_PATHS", "paths", List.of("/input/companyId", "/input/companyName"),
								"failurePolicy", "WAIT_RETRY"))), Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void rejectsInvalidExtractionPoliciesAndPathsTogether() {
		Map<String, Object> schema = Map.of("type", "object", "properties",
				Map.of("companyName", Map.of("type", "string")));
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "bad-policy",
				"nodes", List.of(
						Map.of("id", "bad-policy", "type", "extract", "next", "bad-path", "config", Map.of(
								"schema", schema, "extraction", Map.of("modelPolicy", "SOMETIMES", "schemaMode", "FULL",
										"failurePolicy", "WAIT_RETRY"))),
						Map.of("id", "bad-path", "type", "extract", "next", "bad-continue", "config", Map.of(
								"schema", schema, "extraction", Map.of("modelPolicy", "IF_UNRESOLVED", "schemaMode",
										"CONFIGURED_PATHS", "paths", List.of("/input/missing"),
										"failurePolicy", "WAIT_RETRY"))),
						Map.of("id", "bad-continue", "type", "collect", "next", "empty-paths", "config", Map.of(
								"schema", schema, "requiredPaths", List.of("/input/companyName"), "extraction",
								Map.of("modelPolicy", "IF_UNRESOLVED", "schemaMode", "UNRESOLVED_REQUIRED",
										"failurePolicy", "CONTINUE"))),
						Map.of("id", "empty-paths", "type", "extract", "next", "end", "config", Map.of(
								"schema", schema, "extraction", Map.of("modelPolicy", "ALWAYS", "schemaMode",
										"CONFIGURED_PATHS", "paths", List.of(), "failurePolicy", "WAIT_RETRY"))),
						Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("modelPolicy SOMETIMES")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("IF_UNRESOLVED requires")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("/input/missing is not defined")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("Unsupported extraction failurePolicy CONTINUE")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("requires non-empty paths")));
	}

	@Test
	void acceptsTextSearchOnSelectAndCollectionResolver() {
		Map<String, Object> textSearch = Map.of("enabled", true, "resolverNode", "resolve-customer",
				"argumentName", "keyword", "matchPaths", List.of("/companyName", "/company~0Code"),
				"notFoundText", "未找到客户");
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "select-customer",
				"nodes", List.of(
						Map.of("id", "select-customer", "type", "select", "next", "resolve-customer", "config",
								Map.of("textSearch", textSearch)),
						Map.of("id", "resolve-customer", "type", "resolve", "next", "end", "config", Map.of(
								"resourceVersionId", 1, "forEach", "/input/customers", "textSearch", textSearch)),
						Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void rejectsTextSearchOnOtherNodesAndInvalidPointers() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect",
				"nodes", List.of(
						Map.of("id", "collect", "type", "collect", "next", "select", "config", Map.of(
								"textSearch", Map.of("enabled", true, "resolverNode", "missing", "argumentName", "keyword",
										"matchPaths", List.of("/company~2Code"), "notFoundText", "未找到客户"))),
						Map.of("id", "select", "type", "select", "next", "end", "config", Map.of()),
						Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("only allowed on select")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("resolverNode must reference")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("valid JSON Pointer")));
	}

	@Test
	void validatesDynamicCollectionPresentationAgainstRequiredPaths() {
		Map<String, Object> validPresentation = Map.of("mode", "MISSING_ONLY", "fieldPrompts",
				Map.of("/input/type", "请补充需求类型", "/input/businessType", "请补充业务类型"));
		Map<String, Object> valid = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "end", "config", Map.of(
						"requiredPaths", List.of("/input/type", "/input/businessType"),
						"collectionPresentation", validPresentation)), Map.of("id", "end", "type", "end")));
		Map<String, Object> invalid = Map.of("schemaVersion", "skill-flow/v1", "startNode", "review", "nodes",
				List.of(Map.of("id", "review", "type", "review", "next", "end", "config", Map.of(
						"requiredPaths", List.of("/input/type", "/input/businessType"),
						"collectionPresentation", Map.of("mode", "ALL", "fieldPrompts",
								Map.of("/input/type", "", "/input/other", "其他")))),
						Map.of("id", "end", "type", "end")));
		Map<String, Object> incomplete = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "end", "config", Map.of(
						"requiredPaths", List.of("/input/type", "/input/businessType"),
						"collectionPresentation", Map.of("mode", "MISSING_ONLY", "fieldPrompts",
								Map.of("/input/type", "请补充需求类型", "/input/other", "其他")))),
						Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(valid).isEmpty());
		List<String> errors = validator.validate(invalid);
		assertTrue(errors.stream().anyMatch(error -> error.contains("only allowed on collect")));
		List<String> incompleteErrors = validator.validate(incomplete);
		assertTrue(incompleteErrors.stream().anyMatch(error -> error.contains("is not a required path")));
		assertTrue(incompleteErrors.stream().anyMatch(error -> error.contains("must define prompt")));
	}

	@Test
	void rejectsTechnicalCollectionPromptButAllowsBusinessTerminology() {
		Map<String, Object> technical = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "end", "config", Map.of(
						"requiredPaths", List.of("/input/companyName"), "collectionPresentation",
						Map.of("mode", "MISSING_ONLY", "fieldPrompts",
								Map.of("/input/companyName", "请提供 companyId")))),
						Map.of("id", "end", "type", "end")));
		Map<String, Object> business = Map.of("schemaVersion", "skill-flow/v1", "startNode", "collect", "nodes",
				List.of(Map.of("id", "collect", "type", "collect", "next", "end", "config", Map.of(
						"requiredPaths", List.of("/input/companyName"), "collectionPresentation",
						Map.of("mode", "MISSING_ONLY", "fieldPrompts",
								Map.of("/input/companyName", "请提供客户名称或客户编码")))),
						Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(technical).stream()
				.anyMatch(error -> error.contains("must use a business term")));
		assertTrue(validator.validate(business).isEmpty());
	}

	@Test
	void acceptsWellConfiguredSelectFallback() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "select-project",
				"nodes", List.of(
						Map.of("id", "select-project", "type", "select", "next", "end", "config", Map.of(
								"fallbackNode", "collect-customer", "fallbackClears",
								List.of("/resolved/project", "/resolved/customer"))),
						Map.of("id", "collect-customer", "type", "collect", "next", "end", "config", Map.of()),
						Map.of("id", "end", "type", "end")));

		assertTrue(validator.validate(definition).isEmpty());
	}

	@Test
	void rejectsInvalidSelectFallbackConfiguration() {
		Map<String, Object> definition = Map.of("schemaVersion", "skill-flow/v1", "startNode", "bad-missing",
				"nodes", List.of(
						Map.of("id", "bad-missing", "type", "select", "next", "bad-self", "config", Map.of(
								"fallbackNode", "missing-node", "fallbackClears", List.of("/resolved/project"))),
						Map.of("id", "bad-self", "type", "select", "next", "bad-execute", "config", Map.of(
								"fallbackNode", "bad-self", "fallbackClears", List.of("/resolved/project"))),
						Map.of("id", "bad-execute", "type", "select", "next", "bad-clears", "config", Map.of(
								"fallbackNode", "execute-node", "fallbackClears", List.of("/resolved/project"))),
						Map.of("id", "bad-clears", "type", "select", "next", "end", "config", Map.of(
								"fallbackNode", "collect-customer")),
						Map.of("id", "execute-node", "type", "execute", "next", "end", "config",
								Map.of("resourceVersionId", 9)),
						Map.of("id", "collect-customer", "type", "collect", "next", "end", "config", Map.of()),
						Map.of("id", "end", "type", "end")));

		List<String> errors = validator.validate(definition);

		assertTrue(errors.stream().anyMatch(error -> error.contains("references missing node missing-node")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("must not reference itself")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("must not reference an execute node")));
		assertTrue(errors.stream().anyMatch(error -> error.contains("fallbackClears must be a non-empty array")));
	}

}
