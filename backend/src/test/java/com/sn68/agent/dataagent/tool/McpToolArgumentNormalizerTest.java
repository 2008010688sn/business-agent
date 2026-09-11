package com.sn68.agent.dataagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.temporal.AgentTemporalContext;
import com.sn68.agent.dataagent.temporal.TemporalAmbiguityStrategy;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.DayOfWeek;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class McpToolArgumentNormalizerTest {

	private final McpToolArgumentNormalizer normalizer = new McpToolArgumentNormalizer(new ObjectMapper(),
			new AgentTemporalService());

	@Test
	@SuppressWarnings("unchecked")
	void wrapsFlatArgumentsWhenSchemaRequiresRequestObject() {
		Map<String, Object> normalized = normalizer.normalize(resource(wrapperSchema()),
				Map.of("resourceKey", "demo.echo.latest", "companyId", "CN022203",
						"oneProjectId", "ed4645", "runtimeRequestId", "runtime-1", "_agentId", 9L,
						"_skillVersionId", 11L, "_resourceVersionId", 21L, "idempotencyKey", "idem-1"));

		Map<String, Object> request = (Map<String, Object>) normalized.get("request");
		assertEquals(1, normalized.size());
		assertEquals("CN022203", request.get("companyId"));
		assertEquals("ed4645", request.get("oneProjectId"));
		assertFalse(request.containsKey("resourceKey"));
		assertFalse(request.containsKey("runtimeRequestId"));
		assertFalse(request.containsKey("_agentId"));
		assertFalse(request.containsKey("_skillVersionId"));
		assertFalse(request.containsKey("_resourceVersionId"));
		assertEquals("idem-1", request.get("idempotencyKey"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void mapsRuntimeArgumentsToConfiguredWrappedPath() {
		Map<String, Object> normalized = normalizer.normalize(resource("""
				{"mcpArgumentWrapper":"request","runtimeParamMappings":{"idempotencyKey":"request.idempotencyKey"}}
				"""), Map.of("companyId", "CN022203", "idempotencyKey", "idem-1"));

		Map<String, Object> request = (Map<String, Object>) normalized.get("request");
		assertEquals("CN022203", request.get("companyId"));
		assertEquals("idem-1", request.get("idempotencyKey"));
	}

	@Test
	void doesNotWrapAlreadyWrappedArguments() {
		Map<String, Object> request = Map.of("companyId", "CN022203");
		Map<String, Object> normalized = normalizer.normalize(resource(wrapperSchema()),
				Map.of("request", request, "_agentId", 9L));

		assertEquals(request, normalized.get("request"));
		assertEquals(1, normalized.size());
	}

	@Test
	void removesRuntimeArgumentsFromFlatSchema() {
		Map<String, Object> normalized = normalizer.normalize(resource("{}"),
				Map.of("companyId", "CN022203", "_agentId", 9L, "_skillVersionId", 11L,
						"_agentRequestTenantIdSnapshot", "tenant-1"));

		assertEquals(Map.of("companyId", "CN022203"), normalized);
	}

	@Test
	void keepsFlatArgumentsWhenSchemaIsFlat() {
		Map<String, Object> arguments = Map.of("companyId", "CN022203", "limit", 10);

		Map<String, Object> normalized = normalizer.normalize(resource("""
				{"inputSchema":{"type":"object","properties":{"companyId":{"type":"string"},"limit":{"type":"integer"}}}}
				"""), arguments);

		assertEquals(arguments, normalized);
		assertFalse(normalized.containsKey("request"));
	}

	@Test
	void keepsFlatArgumentsWhenSchemaHasWrapperAndOtherProperties() {
		Map<String, Object> arguments = Map.of("companyId", "CN022203", "traceId", "trace-1");

		Map<String, Object> normalized = normalizer.normalize(resource("""
				{"inputSchema":{"type":"object","required":["request"],"properties":{"request":{"type":"object"},"traceId":{"type":"string"}}}}
				"""), arguments);

		assertEquals(arguments, normalized);
		assertFalse(normalized.containsKey("request"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void wrapperConfigTakesPrecedenceOverFlatSchema() {
		Map<String, Object> normalized = normalizer.normalize(resource("""
				{"mcpArgumentWrapper":"request","inputSchema":{"type":"object","properties":{"companyId":{"type":"string"}}}}
				"""), Map.of("companyId", "CN022203"));

		Map<String, Object> request = (Map<String, Object>) normalized.get("request");
		assertEquals("CN022203", request.get("companyId"));
		assertEquals(1, normalized.size());
	}

	@Test
	void keepsArgumentsWhenSchemaIsMissingOrInvalid() {
		Map<String, Object> arguments = Map.of("companyId", "CN022203");

		assertEquals(arguments, normalizer.normalize(resource("{}"), arguments));
		assertEquals(arguments, normalizer.normalize(resource("{\"inputSchema\":\"not-json\"}"), arguments));
	}

	@Test
	@SuppressWarnings("unchecked")
	void normalizesArrivalTimeInWrappedRequest() {
		Map<String, Object> normalized = normalizer.normalize(resource(wrapperSchema()),
				Map.of("request", Map.of("arrivalTime", 1783987200L)));

		Map<String, Object> request = (Map<String, Object>) normalized.get("request");
		assertEquals("2026-07-14T00:00:00Z", request.get("arrivalTime"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void normalizesArrivalTimeVariantsRecursively() {
		Map<String, Object> flat = normalizer.normalize(resource("{}"), Map.of("arrivalTime", 1783987200L));
		assertEquals("2026-07-14T00:00:00Z", flat.get("arrivalTime"));

		assertArrivalTime(1783987200L, "2026-07-14T00:00:00Z");
		assertArrivalTime(1783987200000L, "2026-07-14T00:00:00Z");
		assertArrivalTime("1783987200", "2026-07-14T00:00:00Z");
		assertArrivalTime("2026-07-14", "2026-07-13T16:00:00Z");
		assertArrivalTime("2026-07-14 08:30", "2026-07-14T00:30:00Z");
		assertArrivalTime("2026-07-14 08:30:15", "2026-07-14T00:30:15Z");
		assertArrivalTime("2026-07-14T00:00:00Z", "2026-07-14T00:00:00Z");
		assertArrivalTime(Instant.parse("2026-07-14T00:00:00Z"), "2026-07-14T00:00:00Z");
	}

	@Test
	void normalizesConfiguredTemporalFieldsWithoutChangingWrapperRules() {
		Map<String, Object> arguments = Map.of("planTime", "2026-07-14 08:30", "keyword", "ACME");

		Map<String, Object> normalized = normalizer.normalize(resource("""
				{"mcpTemporalFields":["planTime"],"inputSchema":{"type":"object","properties":{"planTime":{"type":"string"},"keyword":{"type":"string"}}}}
				"""), arguments);

		assertEquals("2026-07-14T00:30:00Z", normalized.get("planTime"));
		assertEquals("ACME", normalized.get("keyword"));
		assertFalse(normalized.containsKey("request"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void resolvesRelativeTimeFromHiddenTurnSnapshotAndRejectsAmbiguity() {
		AgentTemporalContext context = new AgentTemporalContext(Instant.parse("2026-07-19T06:00:00Z"),
				ZoneId.of("Asia/Shanghai"), Locale.forLanguageTag("zh-CN"), LocalDate.parse("2026-07-19"),
				DayOfWeek.MONDAY, TemporalAmbiguityStrategy.ASK);
		AgentRequest request = AgentRequest.builder().temporalContext(context).build();
		Map<String, Object> arguments = AgentRequestSnapshotSupport.enrichArguments(
				Map.of("request", Map.of("arrivalTime", "今天")), request);

		Map<String, Object> normalized = normalizer.normalize(resource("""
				{"mcpArgumentWrapper":"request","mcpTemporalPolicies":{"arrivalTime":{"kind":"DATE_OR_DATE_TIME","targetType":"INSTANT"}}}
				"""), arguments);

		assertEquals("2026-07-18T16:00:00Z",
				((Map<String, Object>) normalized.get("request")).get("arrivalTime"));
		Map<String, Object> ambiguous = AgentRequestSnapshotSupport.enrichArguments(
				Map.of("request", Map.of("arrivalTime", "周五")), request);
		assertThrows(CheckedException.class, () -> normalizer.normalize(resource("""
				{"mcpArgumentWrapper":"request","mcpTemporalFields":["arrivalTime"]}
				"""), ambiguous));
	}

	@Test
	void requestTemporalContextOverridesCallerSuppliedHiddenSnapshot() {
		AgentTemporalContext context = new AgentTemporalContext(Instant.parse("2026-07-19T06:00:00Z"),
				ZoneId.of("Asia/Shanghai"), Locale.forLanguageTag("zh-CN"), LocalDate.parse("2026-07-19"),
				DayOfWeek.MONDAY, TemporalAmbiguityStrategy.ASK);
		AgentRequest request = AgentRequest.builder().temporalContext(context).build();
		Map<String, Object> enriched = AgentRequestSnapshotSupport.enrichArguments(Map.of(
				AgentRequestSnapshotSupport.TEMPORAL_REFERENCE_INSTANT, "2000-01-01T00:00:00Z",
				AgentRequestSnapshotSupport.TEMPORAL_ZONE_ID, "UTC"), request);

		assertEquals("2026-07-19T06:00:00Z",
				enriched.get(AgentRequestSnapshotSupport.TEMPORAL_REFERENCE_INSTANT));
		assertEquals("Asia/Shanghai", enriched.get(AgentRequestSnapshotSupport.TEMPORAL_ZONE_ID));
	}

	@SuppressWarnings("unchecked")
	private void assertArrivalTime(Object value, String expected) {
		Map<String, Object> normalized = normalizer.normalize(resource("{}"),
				Map.of("parameters", Map.of("request", Map.of("arrivalTime", value))));
		Map<String, Object> parameters = (Map<String, Object>) normalized.get("parameters");
		Map<String, Object> request = (Map<String, Object>) parameters.get("request");
		assertEquals(expected, request.get("arrivalTime"));
	}

	private AgentExecutionResource resource(String extConfig) {
		AgentExecutionResource resource = new AgentExecutionResource();
		resource.setResourceKey("demo.echo.latest");
		resource.setToolName("echoLatest");
		resource.setExtConfig(extConfig);
		return resource;
	}

	private String wrapperSchema() {
		return """
				{"inputSchema":{"type":"object","required":["request"],"properties":{"request":{"type":"object","properties":{"companyId":{"type":"string"},"oneProjectId":{"type":"string"}}}}}}
				""";
	}

}
