/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.RouteModelTransport.Response;
import com.sn68.agent.dataagent.routing.RouteModelTransportException.FailureKind;
import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteModelResult;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Negotiates a model output protocol and applies one strict local parser to both
 * capability checks and runtime route selection.
 */
@Slf4j
@Service
public class RouteModelAdapter {

	private static final int MAX_RESULT_CANDIDATES = 5;

	private static final int MAX_DIAGNOSTIC_VALUE_LENGTH = 80;

	private static final int MAX_STEP_ID_LENGTH = 64;

	private static final int MAX_QUERY_FRAGMENT_LENGTH = 1000;

	private static final int MAX_EXPECTED_OUTPUT_LENGTH = 500;

	private static final int MAX_CLARIFICATION_QUESTION_LENGTH = 1000;

	private static final Set<String> REQUIRED_RESULT_FIELDS = Set.of("decision", "confidence");

	private static final Set<String> RESULT_FIELDS = Set.of("decision", "candidates", "steps", "confidence",
			"clarificationQuestion");

	private static final Set<String> STEP_FIELDS = Set.of("stepId", "handle", "queryFragment", "expectedOutput",
			"dependsOn");

	private final RouteModelTransport transport;

	private final ObjectMapper objectMapper;

	private final DataAgentProperties.Routing routing;

	public RouteModelAdapter(RouteModelTransport transport, ObjectMapper objectMapper, DataAgentProperties properties) {
		this.transport = transport;
		this.objectMapper = objectMapper;
		this.routing = properties.getRuntime().getRouting();
	}

	public RouteModelProbeResult probe(ModelConfigDTO config) {
		if (config == null) {
			log.warn("Route model capability probe completed, provider=unknown, model=unknown, "
					+ "capabilityState=UNAVAILABLE, failureCode=ROUTE_MODEL_CONFIGURATION_INVALID");
			return unavailable("ROUTE_MODEL_CONFIGURATION_INVALID");
		}
		String provider = diagnosticValue(config.getProvider());
		String model = diagnosticValue(config.getModelName());
		List<RouteModelOutputProtocol> protocols = protocols(config);
		log.info("Route model capability probe started, provider={}, model={}, protocolOrder={}", provider, model,
				protocols);
		long deadline = System.nanoTime() + routing.getModelProbeTimeout().toNanos();
		boolean protocolRejected = false;
		boolean invalidOutput = false;
		for (RouteModelOutputProtocol protocol : protocols) {
			long started = System.nanoTime();
			try {
				Duration firstRemaining = remainingProbeTimeout(deadline);
				long firstLatency = probeCall(config, protocol, probeSelectCase(), firstRemaining);
				Duration secondRemaining = remainingProbeTimeout(deadline);
				long secondLatency = probeCall(config, protocol, probeMultiSelectCase(), secondRemaining);
				RouteModelProbeResult result = new RouteModelProbeResult(RouteCapabilityState.SUPPORTED, protocol,
						Math.max(firstLatency, secondLatency), null);
				log.info("Route model capability probe completed, provider={}, model={}, protocol={}, elapsedMs={}, "
						+ "capabilityState=SUPPORTED", provider, model, protocol, elapsedMs(started));
				return result;
			}
			catch (RouteModelTransportException ex) {
				if (ex.failureKind() == FailureKind.PROTOCOL_REJECTED) {
					protocolRejected = true;
					log.warn("Route model capability protocol failed, provider={}, model={}, protocol={}, "
							+ "failureKind={}, httpStatus={}, elapsedMs={}, action=NEXT_PROTOCOL", provider, model,
							protocol, ex.failureKind(), ex.httpStatus(), elapsedMs(started));
					continue;
				}
				String failureCode = failureCode(ex);
				log.warn("Route model capability probe completed, provider={}, model={}, protocol={}, failureKind={}, "
						+ "httpStatus={}, elapsedMs={}, capabilityState=UNAVAILABLE, failureCode={}", provider, model,
						protocol, ex.failureKind(), ex.httpStatus(), elapsedMs(started), failureCode);
				return unavailable(failureCode);
			}
			catch (RouteStageException ex) {
				if ("ROUTE_MODEL_INVALID_OUTPUT".equals(ex.reasonCode())) {
					invalidOutput = true;
					log.warn("Route model capability protocol failed, provider={}, model={}, protocol={}, "
							+ "failureKind=INVALID_OUTPUT, httpStatus=null, elapsedMs={}, action=NEXT_PROTOCOL",
							provider, model, protocol, elapsedMs(started));
					continue;
				}
				log.warn("Route model capability probe completed, provider={}, model={}, protocol={}, failureKind={}, "
						+ "httpStatus=null, elapsedMs={}, capabilityState=UNAVAILABLE, failureCode={}", provider,
						model, protocol, ex.reasonCode(), elapsedMs(started), ex.reasonCode());
				return unavailable(ex.reasonCode());
			}
			catch (RuntimeException ex) {
				log.warn("Route model capability probe completed, provider={}, model={}, protocol={}, "
						+ "failureKind=FAILED, httpStatus=null, exceptionType={}, elapsedMs={}, "
						+ "capabilityState=UNAVAILABLE, failureCode=ROUTE_MODEL_PROBE_FAILED", provider, model,
						protocol, ex.getClass().getSimpleName(), elapsedMs(started));
				return unavailable("ROUTE_MODEL_PROBE_FAILED");
			}
		}
		if (invalidOutput) {
			log.warn("Route model capability probe completed, provider={}, model={}, protocolOrder={}, "
					+ "capabilityState=UNAVAILABLE, failureCode=ROUTE_MODEL_INVALID_OUTPUT", provider, model, protocols);
			return unavailable("ROUTE_MODEL_INVALID_OUTPUT");
		}
		if (protocolRejected) {
			log.warn("Route model capability probe completed, provider={}, model={}, protocolOrder={}, "
					+ "capabilityState=UNSUPPORTED, failureCode=ROUTE_MODEL_PROTOCOL_UNSUPPORTED", provider, model,
					protocols);
			return new RouteModelProbeResult(RouteCapabilityState.UNSUPPORTED, RouteModelOutputProtocol.NONE, 0L,
					"ROUTE_MODEL_PROTOCOL_UNSUPPORTED");
		}
		return unavailable("ROUTE_MODEL_PROBE_FAILED");
	}

	public RouteModelResult disambiguate(ModelConfigDTO config, RouteModelOutputProtocol protocol, RouteContext context,
			List<ScoredCandidate> candidates, Duration timeout) {
		return disambiguate(config, protocol, context, candidates, timeout, null);
	}

	RouteModelResult disambiguate(ModelConfigDTO config, RouteModelOutputProtocol protocol, RouteContext context,
			List<ScoredCandidate> candidates, Duration timeout, RouteModelCallTelemetry.Call telemetry) {
		if (config == null || protocol == null || protocol == RouteModelOutputProtocol.NONE) {
			throw new RouteStageException("MODEL_UNAVAILABLE", "Route model capability is not available");
		}
		Map<String, RouteTargetRef> handles = handles(candidates);
		long started = System.nanoTime();
		try {
			Response response = telemetry == null
				? transport.call(config, protocol, timeout, timeout, systemPrompt(protocol), userPrompt(context, candidates, handles))
				: transport.call(config, protocol, timeout, timeout, systemPrompt(protocol),
						userPrompt(context, candidates, handles), telemetry);
			RouteModelResult result = parse(response, protocol, handles);
			log.debug("Route model disambiguation completed, provider={}, model={}, protocol={}, elapsedMs={}, "
					+ "status=SUCCESS", diagnosticValue(config.getProvider()), diagnosticValue(config.getModelName()),
					protocol, elapsedMs(started));
			return result;
		}
		catch (RouteModelTransportException ex) {
			log.warn("Route model disambiguation failed, provider={}, model={}, protocol={}, failureKind={}, "
					+ "httpStatus={}, elapsedMs={}", diagnosticValue(config.getProvider()),
					diagnosticValue(config.getModelName()), protocol, ex.failureKind(), ex.httpStatus(), elapsedMs(started));
			throw new RouteStageException(transportFailureCode(ex), "Route model transport is unavailable", ex);
		}
		catch (RouteStageException ex) {
			log.warn("Route model disambiguation failed, provider={}, model={}, protocol={}, failureKind={}, "
					+ "httpStatus=null, elapsedMs={}", diagnosticValue(config.getProvider()),
					diagnosticValue(config.getModelName()), protocol, ex.reasonCode(), elapsedMs(started));
			throw ex;
		}
	}

	void recordLocalTimeout(RouteModelCallTelemetry.Call telemetry, String reason) {
		transport.recordLocalTimeout(telemetry, reason);
	}

	private long probeCall(ModelConfigDTO config, RouteModelOutputProtocol protocol, ProbeCase probeCase,
			Duration timeout) {
		long started = System.nanoTime();
		Response response = callProbe(config, protocol, probeCase, timeout);
		RouteModelResult result = parse(response, protocol, probeCase.handles());
		if (result.decision() != probeCase.decision() || result.targets().size() != probeCase.expectedTargets()) {
			throw new RouteStageException("ROUTE_MODEL_INVALID_OUTPUT", "Route model probe returned an invalid plan");
		}
		return Duration.ofNanos(System.nanoTime() - started).toMillis();
	}

	private Response callProbe(ModelConfigDTO config, RouteModelOutputProtocol protocol, ProbeCase probeCase,
			Duration timeout) {
		Duration connectTimeout = timeout.compareTo(routing.getModelProbeConnectTimeout()) < 0 ? timeout
				: routing.getModelProbeConnectTimeout();
		return transport.call(config, protocol, timeout, connectTimeout, systemPrompt(protocol), probeCase.prompt());
	}

	private List<RouteModelOutputProtocol> protocols(ModelConfigDTO config) {
		List<RouteModelOutputProtocol> result = new ArrayList<>();
		if (routing.isFunctionCallingEnabled()) {
			result.add(RouteModelOutputProtocol.FUNCTION_CALL);
		}
		if (isNativeDialect(config)) {
			result.add(RouteModelOutputProtocol.JSON_OBJECT);
			result.add(RouteModelOutputProtocol.PROMPT_JSON);
		}
		else {
			result.add(RouteModelOutputProtocol.STRICT_SCHEMA);
			result.add(RouteModelOutputProtocol.JSON_OBJECT);
			result.add(RouteModelOutputProtocol.PROMPT_JSON);
		}
		return List.copyOf(result);
	}

	private boolean isNativeDialect(ModelConfigDTO config) {
		if (config == null || !StringUtils.hasText(config.getEndpointDialect())) {
			return false;
		}
		try {
			return ModelEndpointDialect.valueOf(config.getEndpointDialect().trim().toUpperCase())
				!= ModelEndpointDialect.OPENAI_COMPATIBLE
				&& ModelEndpointDialect.valueOf(config.getEndpointDialect().trim().toUpperCase())
				!= ModelEndpointDialect.CUSTOM;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private RouteModelProbeResult unavailable(String code) {
		return new RouteModelProbeResult(RouteCapabilityState.UNAVAILABLE, RouteModelOutputProtocol.NONE, 0L, code);
	}

	private String failureCode(RouteModelTransportException ex) {
		if (StringUtils.hasText(ex.failureCode())) {
			return ex.failureCode();
		}
		return switch (ex.failureKind()) {
			case UNAVAILABLE -> "ROUTE_MODEL_UNAVAILABLE";
			case PROTOCOL_REJECTED -> "ROUTE_MODEL_PROTOCOL_REJECTED";
			case FAILED -> "ROUTE_MODEL_PROBE_FAILED";
		};
	}

	private String transportFailureCode(RouteModelTransportException ex) {
		return failureCode(ex);
	}

	private Duration remainingProbeTimeout(long deadline) {
		long remainingNanos = deadline - System.nanoTime();
		if (remainingNanos <= 0L) {
			throw new RouteStageException("ROUTE_MODEL_TIMEOUT", "Route model capability probe timed out");
		}
		long remainingMillis = Math.max(1L, Duration.ofNanos(remainingNanos).toMillis());
		return Duration.ofMillis(remainingMillis);
	}

	private String systemPrompt(RouteModelOutputProtocol protocol) {
		String base = "Select only from the supplied candidate handles. Do not create parameters, SQL, tasks, "
				+ "explanations, business tool calls, or new candidates. "
				+ "Use SELECT only when one candidate can cover every user subtask. "
				+ "Use MULTI_SELECT when the user has multiple subtasks, including ordered or dependent steps, "
				+ "conjunctions such as 以及/并且/同时/分别, or a later clause depends on earlier entities "
				+ "(这些/上述/前述/其中/先再). "
				+ "Dependent steps must set dependsOn to the preceding stepId. Independent subtasks use empty dependsOn. "
				+ "Each step queryFragment must cover only that candidate's subtask, never the full original question. "
				+ "Return exactly one JSON object with only decision, confidence, and the decision-specific optional fields "
				+ "candidates, steps, and clarificationQuestion. For SELECT, return exactly one opaque handle in candidates "
				+ "and omit steps. For MULTI_SELECT, omit candidates and return 2 to 5 ordered steps; every step must contain "
				+ "exactly stepId, handle, queryFragment, expectedOutput, and dependsOn. Preserve unique stepId values and only "
				+ "reference preceding stepId values from dependsOn. For CLARIFY, clarificationQuestion may be returned. "
				+ "For NO_MATCH, omit candidates and steps. ";
		if (protocol == RouteModelOutputProtocol.FUNCTION_CALL) {
			return base + "Submit exactly one function call named submit_route_plan. Put the route plan JSON in its arguments.";
		}
		return protocol == RouteModelOutputProtocol.PROMPT_JSON ? base + "Return JSON only; no markdown."
				: base + "Return only the required JSON result.";
	}

	private String userPrompt(RouteContext context, List<ScoredCandidate> candidates,
			Map<String, RouteTargetRef> handles) {
		Map<RouteTargetRef, String> handleByTarget = new LinkedHashMap<>();
		handles.forEach((handle, target) -> handleByTarget.put(target, handle));
		StringBuilder prompt = new StringBuilder("<user_query>\n").append(boundedText(context == null ? null : context.query(), 4000))
				.append("\n</user_query>\n");
		if (context != null && StringUtils.hasText(context.previousQuery())) {
			prompt.append("<previous_query>\n").append(boundedText(context.previousQuery(), 1000))
					.append("\n</previous_query>\n");
		}
		prompt.append("<candidates>\n");
		for (ScoredCandidate scored : candidates) {
			var candidate = scored.candidate();
			prompt.append("<candidate handle=\"").append(handleByTarget.get(candidate.target())).append("\">\n")
				.append("name: ").append(boundedText(candidate.name(), 200)).append('\n')
				.append("description: ").append(boundedText(candidate.description(), 500)).append('\n')
				.append("risk: ").append(candidate.risk()).append('\n')
				.append("lexicalScore: ").append(scored.lexicalScore()).append('\n')
				.append("matchedSignals: ").append(boundedText(String.join(",", scored.matchedSignals()), 500)).append('\n')
				.append("</candidate>\n");
		}
		prompt.append("</candidates>\n");
		return prompt.toString();
	}

	private RouteModelResult parse(Response response, RouteModelOutputProtocol protocol,
			Map<String, RouteTargetRef> handles) {
		try {
			if (response == null || "length".equalsIgnoreCase(response.finishReason())) {
				throw invalid();
			}
			JsonNode root = readResult(response.content());
			if (root == null || !root.isObject() || !hasRequiredResultShape(root)) {
				throw invalid();
			}
			JsonNode decisionNode = root.path("decision");
			JsonNode candidateNode = root.path("candidates");
			JsonNode stepsNode = root.path("steps");
			JsonNode confidenceNode = root.path("confidence");
			JsonNode clarificationNode = root.path("clarificationQuestion");
			if (!decisionNode.isTextual() || !confidenceNode.isNumber()) {
				throw invalid();
			}
			RouteDecisionType decision = RouteDecisionType.valueOf(decisionNode.textValue());
			validateDecisionShape(decision, candidateNode, stepsNode, clarificationNode);
			double confidence = confidenceNode.doubleValue();
			if (!Double.isFinite(confidence) || confidence < 0D || confidence > 1D) {
				throw invalid();
			}
			List<RouteTargetRef> targets = new ArrayList<>();
			List<RoutePlanStep> planSteps = new ArrayList<>();
			if (stepsNode.isArray()) {
				parseSteps(stepsNode, handles, targets, planSteps);
			}
			else if (candidateNode.isArray()) {
				parseCandidates(candidateNode, handles, targets);
			}
			validateCardinality(decision, targets);
			RoutePlan plan = planSteps.isEmpty() ? RoutePlan.empty() : new RoutePlan(planSteps);
			String clarificationQuestion = clarificationNode.isMissingNode() ? null
					: requiredText(clarificationNode, MAX_CLARIFICATION_QUESTION_LENGTH);
			return new RouteModelResult(decision, targets, confidence, plan, clarificationQuestion);
		}
		catch (RouteStageException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new RouteStageException("ROUTE_MODEL_INVALID_OUTPUT", "Route model returned invalid structured output", ex);
		}
	}

	private void validateDecisionShape(RouteDecisionType decision, JsonNode candidates, JsonNode steps,
			JsonNode clarificationQuestion) {
		boolean hasCandidates = !candidates.isMissingNode();
		boolean hasSteps = !steps.isMissingNode();
		if (hasCandidates && (!candidates.isArray() || candidates.size() > MAX_RESULT_CANDIDATES)) {
			throw invalid();
		}
		if (hasSteps && (!steps.isArray() || steps.size() < 2 || steps.size() > MAX_RESULT_CANDIDATES)) {
			throw invalid();
		}
		if (!clarificationQuestion.isMissingNode() && decision != RouteDecisionType.CLARIFY) {
			throw invalid();
		}
		boolean valid = switch (decision) {
			case SELECT -> hasCandidates && !hasSteps;
			case MULTI_SELECT -> !hasCandidates && hasSteps;
			case CLARIFY, NO_MATCH -> !hasSteps;
			default -> false;
		};
		if (!valid) {
			throw invalid();
		}
	}

	private void parseCandidates(JsonNode candidates, Map<String, RouteTargetRef> handles,
			List<RouteTargetRef> targets) {
		Set<String> uniqueHandles = new LinkedHashSet<>();
		for (JsonNode candidate : candidates) {
			String handle = requiredText(candidate, 2);
			if (!uniqueHandles.add(handle) || !handles.containsKey(handle)) {
				throw invalid();
			}
			targets.add(handles.get(handle));
		}
	}

	private void parseSteps(JsonNode steps, Map<String, RouteTargetRef> handles, List<RouteTargetRef> targets,
			List<RoutePlanStep> planSteps) {
		Set<String> stepIds = new LinkedHashSet<>();
		Set<String> uniqueHandles = new LinkedHashSet<>();
		for (JsonNode step : steps) {
			if (step == null || !step.isObject() || !hasExactFields(step, STEP_FIELDS)) {
				throw invalid();
			}
			String stepId = requiredText(step.path("stepId"), MAX_STEP_ID_LENGTH);
			String handle = requiredText(step.path("handle"), 2);
			if (!stepIds.add(stepId) || !uniqueHandles.add(handle) || !handles.containsKey(handle)) {
				throw invalid();
			}
			List<String> dependsOn = parseDependencies(step.path("dependsOn"), stepIds, stepId);
			RouteTargetRef target = handles.get(handle);
			targets.add(target);
			planSteps.add(new RoutePlanStep(stepId, target,
					requiredText(step.path("queryFragment"), MAX_QUERY_FRAGMENT_LENGTH), dependsOn,
					requiredText(step.path("expectedOutput"), MAX_EXPECTED_OUTPUT_LENGTH)));
		}
	}

	private List<String> parseDependencies(JsonNode dependencies, Set<String> stepIds, String currentStepId) {
		if (!dependencies.isArray() || dependencies.size() > MAX_RESULT_CANDIDATES) {
			throw invalid();
		}
		List<String> result = new ArrayList<>();
		Set<String> uniqueDependencies = new LinkedHashSet<>();
		for (JsonNode dependencyNode : dependencies) {
			String dependency = requiredText(dependencyNode, MAX_STEP_ID_LENGTH);
			if (!uniqueDependencies.add(dependency) || dependency.equals(currentStepId) || !stepIds.contains(dependency)) {
				throw invalid();
			}
			result.add(dependency);
		}
		return result;
	}

	private String requiredText(JsonNode value, int maxLength) {
		if (value == null || !value.isTextual() || !StringUtils.hasText(value.textValue())
				|| value.textValue().length() > maxLength) {
			throw invalid();
		}
		return value.textValue();
	}

	private JsonNode readResult(String content) throws IOException {
		return objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(content);
	}

	private boolean hasRequiredResultShape(JsonNode root) {
		Set<String> fields = new LinkedHashSet<>();
		root.fieldNames().forEachRemaining(fields::add);
		return fields.containsAll(REQUIRED_RESULT_FIELDS) && RESULT_FIELDS.containsAll(fields);
	}

	private boolean hasExactFields(JsonNode node, Set<String> expectedFields) {
		Set<String> fields = new LinkedHashSet<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields.equals(expectedFields);
	}

	private Map<String, RouteTargetRef> handles(List<ScoredCandidate> candidates) {
		Map<String, RouteTargetRef> result = new LinkedHashMap<>();
		for (int index = 0; index < candidates.size(); index++) {
			result.put("c" + (index + 1), candidates.get(index).candidate().target());
		}
		return result;
	}

	private RouteTargetRef probeTarget() {
		return new RouteTargetRef(com.sn68.agent.dataagent.routing.model.RouteTargetType.SKILL, 1L, 1L, 1L);
	}

	private ProbeCase probeSelectCase() {
		return new ProbeCase("query: select c1\ncandidates:\nc1 | name=probe | description=probe | risk=READ_ONLY\n",
				Map.of("c1", probeTarget()), RouteDecisionType.SELECT, 1);
	}

	private ProbeCase probeMultiSelectCase() {
		RouteTargetRef first = probeTarget();
		RouteTargetRef second = new RouteTargetRef(com.sn68.agent.dataagent.routing.model.RouteTargetType.SKILL, 1L, 2L, 2L);
		return new ProbeCase("query: first select c1, then use its result to select c2\n"
				+ "candidates:\nc1 | name=probe-first | description=first step | risk=READ_ONLY\n"
				+ "c2 | name=probe-second | description=dependent second step | risk=READ_ONLY\n",
				Map.of("c1", first, "c2", second), RouteDecisionType.MULTI_SELECT, 2);
	}

	private record ProbeCase(String prompt, Map<String, RouteTargetRef> handles, RouteDecisionType decision,
			int expectedTargets) {
	}

	private void validateCardinality(RouteDecisionType decision, List<RouteTargetRef> targets) {
		boolean valid = switch (decision) {
			case SELECT -> targets.size() == 1;
			case MULTI_SELECT -> targets.size() >= 2;
			case CLARIFY -> targets.size() <= 2;
			case NO_MATCH -> targets.isEmpty();
			default -> false;
		};
		if (!valid) {
			throw invalid();
		}
	}

	private RouteStageException invalid() {
		return new RouteStageException("ROUTE_MODEL_INVALID_OUTPUT", "Route model returned invalid structured output");
	}

	private long elapsedMs(long started) {
		return Duration.ofNanos(System.nanoTime() - started).toMillis();
	}

	private String diagnosticValue(String value) {
		String sanitized = safe(value).strip();
		if (!StringUtils.hasText(sanitized)) {
			return "unknown";
		}
		return sanitized.length() <= MAX_DIAGNOSTIC_VALUE_LENGTH ? sanitized
				: sanitized.substring(0, MAX_DIAGNOSTIC_VALUE_LENGTH);
	}

	private String safe(String value) {
		return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
	}

	private String boundedText(String value, int maxLength) {
		String text = safe(value).strip();
		return text.length() <= maxLength ? text : text.substring(0, maxLength);
	}

}
