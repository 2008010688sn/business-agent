/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeRetryPolicy;
import com.sn68.agent.dataagent.agentscope.runtime.ModelHttpException;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateRequest;
import com.sn68.agent.dataagent.optimization.enums.AgentOptimizationErrorDict;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 自进化 LLM 候选生成器：只产出白名单类型的最小 JSON 补丁，失败关闭且不落库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptCandidateGenerator {

	private static final String DEFAULT_TARGET_TYPE = "PROMPT";

	/** 与 PromptBackedReActAgent ExecutionConfig.maxAttempts(2) 对齐。 */
	private static final int MODEL_CALL_MAX_ATTEMPTS = 2;

	private final DataAgentProperties dataAgentProperties;

	private final ModelConfigDataService modelConfigDataService;

	private final DynamicModelFactory dynamicModelFactory;

	private final ObjectMapper objectMapper;

	public boolean autoGenerateEnabled() {
		DataAgentProperties.Optimization optimization = dataAgentProperties.getOptimization();
		return optimization != null && optimization.isAutoGenerateEnabled();
	}

	public OptCandidateRequest generate(String targetType, String targetId, Long modelConfigId, String traces) {
		String normalizedType = StringUtils.hasText(targetType) ? targetType.trim().toUpperCase(Locale.ROOT)
				: DEFAULT_TARGET_TYPE;
		ModelConfigDTO modelConfig = resolveModel(modelConfigId);
		String raw;
		try {
			ChatModel chatModel = dynamicModelFactory.createChatModel(modelConfig);
			ChatResponse response = callWithRetry(
					chatModel, new Prompt(List.of(new UserMessage(buildPrompt(normalizedType, traces)))));
			raw = response == null || response.getResult() == null || response.getResult().getOutput() == null ? null
					: response.getResult().getOutput().getText();
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.warn("LLM 生成自进化候选失败, 不写入候选", ex);
			throw badRequest(AgentOptimizationErrorDict.GENERATE_FAILED);
		}
		return parse(raw, normalizedType, targetId, traces);
	}

	static String sanitize(String text, int maxChars) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		String redacted = text.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[email]")
			.replaceAll("\\d{11,}", "[id]");
		int limit = Math.max(200, maxChars);
		return redacted.length() <= limit ? redacted : redacted.substring(0, limit);
	}

	int maxTraceChars() {
		DataAgentProperties.Optimization optimization = dataAgentProperties.getOptimization();
		return optimization == null ? 2000 : Math.max(200, optimization.getMaxTraceChars());
	}

	private ChatResponse callWithRetry(ChatModel chatModel, Prompt prompt) {
		for (int attempt = 1;; attempt++) {
			try {
				return chatModel.call(prompt);
			}
			catch (CheckedException ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				Throwable mapped = ModelHttpException.wrapIfHttp(ex);
				if (attempt >= MODEL_CALL_MAX_ATTEMPTS || !AgentRuntimeRetryPolicy.isRetryable(mapped)) {
					throw ex;
				}
			}
		}
	}

	private ModelConfigDTO resolveModel(Long modelConfigId) {
		try {
			ModelConfigDTO config = modelConfigId != null
					? modelConfigDataService.getRuntimeConfigById(modelConfigId, ModelType.CHAT)
					: modelConfigDataService.getActiveRuntimeConfigByType(ModelType.CHAT);
			if (config == null) {
				throw badRequest(AgentOptimizationErrorDict.GENERATE_FAILED);
			}
			return config;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			log.warn("解析自进化生成模型失败", ex);
			throw badRequest(AgentOptimizationErrorDict.GENERATE_FAILED);
		}
	}

	private String buildPrompt(String targetType, String traces) {
		return """
				你是箱箱云自进化候选生成器。只输出一个 JSON 对象，不要 markdown 围栏。
				只允许改 harness：PROMPT / CLARIFY_QUESTION / ROUTE_EXAMPLE / SKILL_DESCRIPTION / MEMORY_RECALL_POLICY / MODEL_ROUTE / FLOW_EXTRACTION_SAMPLE。
				禁止权限、凭据、工具代码、财务/库存/资产规则、审批策略。
				本次目标类型：%s
				JSON 字段：targetType, candidateName, changeReason, expectedImpact, patchJson。
				PROMPT 的 patchJson 必须是 {"snapshotOverlay":{"promptSnapshot":{"prompt":"..."}}}。
				失败轨迹（已脱敏截断）：
				%s
				""".formatted(targetType, traces == null ? "" : traces);
	}

	private OptCandidateRequest parse(String raw, String fallbackType, String targetId, String traces) {
		JsonNode node = readJsonObject(raw);
		if (node == null) {
			throw badRequest(AgentOptimizationErrorDict.GENERATE_FAILED);
		}
		String type = text(node, "targetType", fallbackType);
		String patchJson = patchJson(node);
		if (!StringUtils.hasText(patchJson) || !StringUtils.hasText(type)) {
			throw badRequest(AgentOptimizationErrorDict.GENERATE_FAILED);
		}
		String beforeHash = sha256(fallbackType + ":" + (traces == null ? "" : traces));
		String afterHash = sha256(patchJson);
		OptCandidateRequest request = new OptCandidateRequest();
		request.setCandidateName(text(node, "candidateName", "LLM 候选-" + type));
		request.setTargetType(type);
		request.setTargetId(StringUtils.hasText(targetId) ? targetId : text(node, "targetId", type));
		request.setChangeReason(text(node, "changeReason", "根据失败聚类生成"));
		request.setExpectedImpact(text(node, "expectedImpact", null));
		request.setPatchJson(patchJson);
		request.setBeforeHash(beforeHash);
		request.setAfterHash(afterHash);
		request.setGeneratedBy("llm");
		request.setRiskLevel("medium");
		return request;
	}

	private JsonNode readJsonObject(String raw) {
		if (!StringUtils.hasText(raw)) {
			return null;
		}
		String text = raw.trim();
		int fence = text.indexOf("```");
		if (fence >= 0) {
			int start = text.indexOf('\n', fence);
			int end = text.indexOf("```", fence + 3);
			if (start > 0 && end > start) {
				text = text.substring(start + 1, end).trim();
			}
		}
		try {
			JsonNode node = objectMapper.readTree(text);
			return node != null && node.isObject() ? node : null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String patchJson(JsonNode node) {
		JsonNode patch = node.get("patchJson");
		if (patch == null || patch.isNull()) {
			return null;
		}
		return patch.isTextual() ? patch.asText() : patch.toString();
	}

	private static String text(JsonNode node, String field, String fallback) {
		if (node == null || !node.hasNonNull(field)) {
			return fallback;
		}
		String value = node.get(field).asText();
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (Exception ex) {
			throw CheckedException.fail("无法计算候选哈希");
		}
	}

	private static CheckedException badRequest(AgentOptimizationErrorDict dict) {
		return CheckedException.badRequest(dict.getValue(), dict.getLabel());
	}

}
