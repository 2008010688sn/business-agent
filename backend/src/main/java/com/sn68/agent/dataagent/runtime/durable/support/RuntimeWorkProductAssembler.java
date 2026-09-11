/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeArtifact;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 运行产物装配：给员工履历 / 任务结果门面复用，不改 runtime-runs 权限域。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeWorkProductAssembler {

	static final String SINGLE_TURN_ANSWER_SCHEMA = "single-turn-answer/v1";

	static final String SENSITIVITY_SENSITIVE = "SENSITIVE";

	private static final Set<String> NON_ANSWER_SCHEMAS = Set.of(
			RuntimeStructuredWorkProductComposer.QUERY_RESULT_SCHEMA,
			RuntimeStructuredWorkProductComposer.CHART_CANDIDATE_SCHEMA,
			RuntimeStructuredWorkProductComposer.ANALYSIS_REPORT_SCHEMA);

	private final AgentRuntimeArtifactMapper artifactMapper;

	private final ObjectMapper objectMapper;

	public List<RuntimeArtifactResp> listArtifacts(Long runId) {
		if (runId == null) {
			return List.of();
		}
		List<AgentRuntimeArtifact> rows = artifactMapper.listByRunId(runId);
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<RuntimeArtifactResp> artifacts = new ArrayList<>(rows.size());
		for (AgentRuntimeArtifact row : rows) {
			if (row == null) {
				continue;
			}
			boolean sensitive = SENSITIVITY_SENSITIVE.equalsIgnoreCase(row.getSensitivity());
			artifacts.add(new RuntimeArtifactResp(row.getId(), row.getStepKey(), row.getSchemaVersion(),
					sensitive ? null : row.getData(), row.getSensitivity()));
		}
		return List.copyOf(artifacts);
	}

	/**
	 * 优先用权威 finalAnswer；空则从最近一条非敏感 single-turn-answer 取 answer 字段。
	 */
	public String resolveFinalAnswer(String finalAnswer, List<RuntimeArtifactResp> artifacts) {
		if (StringUtils.hasText(finalAnswer)) {
			return finalAnswer;
		}
		if (artifacts == null || artifacts.isEmpty()) {
			return null;
		}
		for (int index = artifacts.size() - 1; index >= 0; index--) {
			String answer = answerFromArtifact(artifacts.get(index));
			if (StringUtils.hasText(answer)) {
				return answer;
			}
		}
		return null;
	}

	private String answerFromArtifact(RuntimeArtifactResp artifact) {
		if (artifact == null || !StringUtils.hasText(artifact.data())) {
			return null;
		}
		if (NON_ANSWER_SCHEMAS.contains(artifact.schemaVersion())) {
			return null;
		}
		if (!SINGLE_TURN_ANSWER_SCHEMA.equals(artifact.schemaVersion())) {
			return artifact.data();
		}
		try {
			JsonNode root = objectMapper.readTree(artifact.data());
			JsonNode answer = root.path("answer");
			return answer.isTextual() ? answer.asText() : artifact.data();
		}
		catch (Exception ex) {
			log.warn("解析单轮产物 JSON 失败, artifactId={}", artifact.id(), ex);
			return artifact.data();
		}
	}

}
