/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.FlowStructuredModel;
import com.sn68.agent.dataagent.service.tokenusage.AgentStructuredModelCallResult;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageContext;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * FLOW-only sparse patch extractor. Output protocol is negotiated at runtime
 * per chat model ({@code FUNCTION_CALL → STRICT_JSON_SCHEMA → JSON_OBJECT}) and
 * is not configured on the FLOW node. Field schema and literal mappings stay
 * on the extract node; sampling stays model-owned.
 */
@Service
@Slf4j
public class ModelFlowFieldExtractor implements FlowFieldExtractor {

	private final DynamicModelFactory dynamicModelFactory;

	private final AgentTokenUsageService tokenUsageService;

	private final ObjectMapper objectMapper;

	private final DataAgentProperties dataAgentProperties;

	private final AgentTemporalService agentTemporalService;

	private final FlowStructuredCapabilityService capabilityService;

	private final FlowSchemaCompiler schemaCompiler;

	private final FlowStructuredPatchDecoder patchDecoder;

	private final FlowExtractionBudgetPlanner budgetPlanner;

	@Autowired
	public ModelFlowFieldExtractor(DynamicModelFactory dynamicModelFactory, AgentTokenUsageService tokenUsageService,
			ObjectMapper objectMapper, DataAgentProperties dataAgentProperties, AgentTemporalService agentTemporalService,
			FlowStructuredCapabilityService capabilityService, FlowSchemaCompiler schemaCompiler,
			FlowStructuredPatchDecoder patchDecoder, FlowExtractionBudgetPlanner budgetPlanner) {
		this.dynamicModelFactory = dynamicModelFactory;
		this.tokenUsageService = tokenUsageService;
		this.objectMapper = objectMapper;
		this.dataAgentProperties = dataAgentProperties;
		this.agentTemporalService = agentTemporalService;
		this.capabilityService = capabilityService;
		this.schemaCompiler = schemaCompiler;
		this.patchDecoder = patchDecoder;
		this.budgetPlanner = budgetPlanner;
	}

	@Override
	public Map<String, Object> extract(AgentRequest request, ModelConfigDTO modelConfig, Map<String, Object> schema,
			String instruction) {
		return extract(request, modelConfig, schema, instruction, Map.of(), Map.of());
	}

	@Override
	public Map<String, Object> extract(AgentRequest request, ModelConfigDTO modelConfig, Map<String, Object> schema,
			String instruction, Map<String, Object> runtimeConfig) {
		return extract(request, modelConfig, schema, instruction, runtimeConfig, Map.of());
	}

	@Override
	public Map<String, Object> extract(AgentRequest request, ModelConfigDTO modelConfig, Map<String, Object> schema,
			String instruction, Map<String, Object> runtimeConfig, Map<String, Object> currentValues) {
		if (request == null || modelConfig == null || !StringUtils.hasText(request.getQuery())) {
			return Map.of();
		}
		requireDependencies();
		FlowSchemaCompiler.CompiledSchema compiled = compile(schema);
		if (schemaProperties(compiled.patchSchema()).isEmpty()) {
			return Map.of();
		}
		List<FlowStructuredOutputProtocol> protocols = capabilityService.candidateProtocols(modelConfig);
		if (protocols.isEmpty()) {
			throw new FlowExtractionException(FlowExtractionException.PROTOCOL_INCOMPATIBLE,
					"Selected chat model has no compatible FLOW structured output protocol", null);
		}
		for (int index = 0; index < protocols.size(); index++) {
			FlowStructuredOutputProtocol protocol = protocols.get(index);
			boolean lastProtocol = index == protocols.size() - 1;
			String prompt = buildPrompt(request, compiled.patchSchema(), currentValues, instruction, protocol);
			try {
				DataAgentProperties.Flow flow = flowProperties();
				FlowExtractionBudgetPlanner.Budget budget = budgetPlanner.plan(request, modelConfig, prompt, flow);
				log.info("FLOW structured extraction attempting. runtimeRequestId={}, modelName={}, protocol={}, attempt={}/{}, configuredExtractMaxTokens={}, plannedMaxOutputTokens={}",
						request.getRuntimeRequestId(), modelConfig.getModelName(), protocol, index + 1, protocols.size(),
						flow.getExtractMaxTokens(), budget.maxOutputTokens());
				Map<String, Object> patch = callAndDecode(request, modelConfig, compiled, protocol, budget, prompt);
				capabilityService.recordSuccess(modelConfig, protocol);
				return patch;
			}
			catch (FlowExtractionException ex) {
				if (lastProtocol || !canFallbackToNextProtocol(ex)) {
					throw ex;
				}
				log.warn("FLOW structured extraction protocol failed, trying next. runtimeRequestId={}, modelName={}, "
								+ "protocol={}, errorCode={}, nextProtocol={}",
						request.getRuntimeRequestId(), modelConfig.getModelName(), protocol, ex.errorCode(),
						protocols.get(index + 1), ex);
				if (marksProtocolUnsupported(ex, protocol)) {
					capabilityService.recordProtocolRejected(modelConfig, protocol);
				}
			}
		}
		throw new FlowExtractionException(FlowExtractionException.PROTOCOL_INCOMPATIBLE,
				"Selected chat model has no compatible FLOW structured output protocol", null);
	}

	private boolean canFallbackToNextProtocol(FlowExtractionException error) {
		if (error == null) {
			return false;
		}
		return FlowExtractionException.PROTOCOL_INCOMPATIBLE.equals(error.errorCode())
				|| FlowExtractionException.INVALID_RESPONSE.equals(error.errorCode())
				|| FlowExtractionException.CONFIG_ERROR.equals(error.errorCode())
				|| FlowExtractionException.TIMEOUT.equals(error.errorCode());
	}

	private boolean marksProtocolUnsupported(FlowExtractionException error,
			FlowStructuredOutputProtocol protocol) {
		if (error == null) {
			return false;
		}
		if (FlowExtractionException.PROTOCOL_INCOMPATIBLE.equals(error.errorCode())) {
			return true;
		}
		// Function Calling 没交出 tool call 视为该协议不可用；JSON 多写字段是内容问题，不记 UNSUPPORTED。
		return protocol == FlowStructuredOutputProtocol.FUNCTION_CALL
				&& FlowExtractionException.INVALID_RESPONSE.equals(error.errorCode());
	}

	private Map<String, Object> callAndDecode(AgentRequest request, ModelConfigDTO modelConfig,
			FlowSchemaCompiler.CompiledSchema compiled, FlowStructuredOutputProtocol protocol,
			FlowExtractionBudgetPlanner.Budget budget, String prompt) {
		try {
			FlowStructuredModel model = dynamicModelFactory.createFlowStructuredModel(modelConfig, budget.timeout(),
					budget.maxOutputTokens(), compiled.jsonSchema(), protocol);
			AgentTokenUsageContext usageContext = tokenUsageService.buildContext(request, modelConfig, "FLOW_EXTRACT");
			if (usageContext == null) {
				throw new FlowExtractionException(FlowExtractionException.CONFIG_ERROR,
						"FLOW extraction token usage context is unavailable", null);
			}
			AgentStructuredModelCallResult modelCall = tokenUsageService.callAndRecordStructured(model.model(), prompt,
					usageContext.toBuilder().maxTokens(budget.maxOutputTokens()).build());
			if (modelCall == null) {
				throw new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE,
						"FLOW structured model returned no response metadata", null);
			}
			if (isLengthFinish(modelCall.finishReason())) {
				throw new FlowExtractionException(FlowExtractionException.TRUNCATED,
						"FLOW structured response was truncated", null);
			}
			Map<String, Object> patch = patchDecoder.decode(protocol, modelCall, compiled.patchSchema());
			log.info("FLOW structured extraction completed. runtimeRequestId={}, modelName={}, protocol={}, "
					+ "maxOutputTokens={}, promptTokens={}, completionTokens={}, finishReason={}, durationMs={}",
					request.getRuntimeRequestId(), modelConfig.getModelName(), protocol, budget.maxOutputTokens(),
					modelCall.promptTokens(), modelCall.completionTokens(), safeFinishReason(modelCall.finishReason()),
					modelCall.durationMs());
			return patch;
		}
		catch (FlowExtractionException ex) {
			throw ex;
		}
		catch (IllegalArgumentException ex) {
			FlowExtractionException failure = FlowExtractionException.configuration(ex);
			log.warn("FLOW structured extraction failed. runtimeRequestId={}, modelName={}, protocol={}, errorCode={}",
					request.getRuntimeRequestId(), modelConfig.getModelName(), protocol, failure.errorCode(), ex);
			throw failure;
		}
		catch (RuntimeException ex) {
			FlowExtractionException failure = FlowExtractionException.modelCall(ex);
			log.warn("FLOW structured extraction failed. runtimeRequestId={}, modelName={}, protocol={}, errorCode={}",
					request.getRuntimeRequestId(), modelConfig.getModelName(), protocol, failure.errorCode(), ex);
			throw failure;
		}
	}

	private FlowSchemaCompiler.CompiledSchema compile(Map<String, Object> schema) {
		try {
			return schemaCompiler.compile(schema == null ? Map.of() : schema);
		}
		catch (RuntimeException ex) {
			throw FlowExtractionException.configuration(ex);
		}
	}

	private String buildPrompt(AgentRequest request, Map<String, Object> patchSchema,
			Map<String, Object> currentValues, String instruction, FlowStructuredOutputProtocol protocol) {
		try {
			String temporalContext = agentTemporalService == null ? "none"
					: agentTemporalService.promptBlock(request.getTemporalContext(), request.getTemporalInterval());
			String responseInstruction = protocol == FlowStructuredOutputProtocol.FUNCTION_CALL
					? "Submit exactly one submit_flow_patch function call whose arguments are {\"set\":{...}}. "
							+ "Do not provide explanatory text."
					: "Return exactly one JSON object of the form {\"set\":{...}} and nothing else. "
							+ "Top-level fields other than set are rejected. "
							+ "Do not use Markdown fences or explanatory text.";
			return """
					Temporal context: %s
					Node extraction instruction: %s
					Writable field schema: %s
					Current collected values: %s
					Extract only values explicitly present or explicitly changed in the user message. Do not guess, resolve master data, or invent identifiers.
					If the schema includes turnAction, set it to fill, list, proceed, cancel, ask, or chitchat for this user message. fill=providing or correcting form values. list=user wants available options; then set listLabel to a resource name described in the schema. proceed=continue without new values. cancel=stop only when the user clearly wants to abort this task. ask=the user asked a question about this task or capability and did not supply form values. chitchat=off-topic small talk; set turnReply to a short reply that pulls the user back to the current form. Ambiguous 算了 is proceed or chitchat, not cancel. For ask or chitchat, set turnReply using schema field titles for what is still missing and do not emit form values. Only fill may emit form field values.
					All fields in the patch are optional. Do not emit undeclared fields or internal identifiers.
					%s
					User message: %s
					""".formatted(firstText(temporalContext, "none"), firstText(instruction, "none"),
					objectMapper.writeValueAsString(patchSchema),
					objectMapper.writeValueAsString(filterCurrentValues(currentValues, patchSchema)), responseInstruction,
					request.getQuery());
		}
		catch (Exception ex) {
			throw FlowExtractionException.configuration(ex);
		}
	}

	private Map<String, Object> filterCurrentValues(Map<String, Object> currentValues, Map<String, Object> schema) {
		if (currentValues == null || currentValues.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<String, Object> property : schemaProperties(schema).entrySet()) {
			if (!currentValues.containsKey(property.getKey()) || !(property.getValue() instanceof Map<?, ?> childSchema)) {
				continue;
			}
			result.put(property.getKey(), filterValue(currentValues.get(property.getKey()), map(childSchema)));
		}
		return result;
	}

	private Object filterValue(Object value, Map<String, Object> schema) {
		if (value instanceof Map<?, ?> nested && "object".equals(schema.get("type"))) {
			return filterCurrentValues(map(nested), schema);
		}
		if (value instanceof Iterable<?> values && schema.get("items") instanceof Map<?, ?> itemSchema) {
			List<Object> result = new ArrayList<>();
			for (Object item : values) {
				result.add(filterValue(item, map(itemSchema)));
			}
			return result;
		}
		return value;
	}

	private Map<String, Object> schemaProperties(Map<String, Object> schema) {
		return schema != null && schema.get("properties") instanceof Map<?, ?> properties ? map(properties) : Map.of();
	}

	private DataAgentProperties.Flow flowProperties() {
		return dataAgentProperties == null || dataAgentProperties.getFlow() == null ? new DataAgentProperties.Flow()
				: dataAgentProperties.getFlow();
	}

	private boolean isLengthFinish(String finishReason) {
		return StringUtils.hasText(finishReason) && "length".equalsIgnoreCase(finishReason.trim());
	}

	private String safeFinishReason(String finishReason) {
		return StringUtils.hasText(finishReason) ? finishReason.trim() : "unknown";
	}

	private String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value : fallback;
	}

	private void requireDependencies() {
		if (dynamicModelFactory == null || tokenUsageService == null || objectMapper == null || capabilityService == null
				|| schemaCompiler == null || patchDecoder == null || budgetPlanner == null) {
			throw new FlowExtractionException(FlowExtractionException.CONFIG_ERROR,
					"FLOW structured extraction dependencies are unavailable", null);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Map<?, ?> value) {
		return (Map<String, Object>) value;
	}

}
