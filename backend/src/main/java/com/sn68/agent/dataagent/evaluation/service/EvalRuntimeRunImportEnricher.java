/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.service;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRunFeedback;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunFeedbackMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 从 RuntimeRun 导入评估用例时补岗位分片与人工反馈，不编造空岗位。
 */
@Component
@RequiredArgsConstructor
public class EvalRuntimeRunImportEnricher {

	static final String SHARD_TAG_POSITION_PREFIX = "position:";

	static final String TAG_FEEDBACK_UP = "feedback:UP";

	static final String TAG_FEEDBACK_DOWN = "feedback:DOWN";

	private static final int MAX_POSITION_CHARS = 64;

	private final DigitalEmployeeMapper employeeMapper;

	private final AgentRuntimeRunFeedbackMapper feedbackMapper;

	public List<String> enrichTags(List<String> tags, String tenantId, RuntimeRunResp run) {
		Set<String> merged = new LinkedHashSet<>();
		if (tags != null) {
			tags.stream().filter(StringUtils::hasText).map(String::trim).forEach(merged::add);
		}
		DigitalEmployee employee = findEmployee(tenantId, run);
		if (employee != null && StringUtils.hasText(employee.getJobTitle())) {
			String position = sanitizePosition(employee.getJobTitle());
			if (StringUtils.hasText(position)) {
				merged.add(SHARD_TAG_POSITION_PREFIX + position);
			}
		}
		if (run != null && run.id() != null && tenantId != null) {
			List<AgentRuntimeRunFeedback> feedbacks = feedbackMapper.listByTenantAndRun(tenantId,
					run.id());
			boolean up = false;
			boolean down = false;
			for (AgentRuntimeRunFeedback row : feedbacks) {
				if (row == null || !StringUtils.hasText(row.getRating())) {
					continue;
				}
				if ("UP".equalsIgnoreCase(row.getRating().trim())) {
					up = true;
				}
				else if ("DOWN".equalsIgnoreCase(row.getRating().trim())) {
					down = true;
				}
			}
			if (up) {
				merged.add(TAG_FEEDBACK_UP);
			}
			if (down) {
				merged.add(TAG_FEEDBACK_DOWN);
			}
		}
		return List.copyOf(merged);
	}

	/**
	 * expected 已有则不改。否则取最近一条 DOWN 评语作为「应当回答」。
	 */
	public String enrichExpectedOutput(String expectedOutput, String tenantId, Long runId) {
		if (StringUtils.hasText(expectedOutput) || tenantId == null || runId == null) {
			return expectedOutput;
		}
		List<AgentRuntimeRunFeedback> feedbacks = feedbackMapper.listByTenantAndRun(tenantId, runId);
		for (AgentRuntimeRunFeedback row : feedbacks) {
			if (row != null && "DOWN".equalsIgnoreCase(row.getRating()) && StringUtils.hasText(row.getComment())) {
				return row.getComment().trim();
			}
		}
		return expectedOutput;
	}

	private DigitalEmployee findEmployee(String tenantId, RuntimeRunResp run) {
		if (!StringUtils.hasText(tenantId) || run == null || run.digitalEmployeeId() == null) {
			return null;
		}
		return employeeMapper.findByIdAndTenantId(run.digitalEmployeeId(), tenantId);
	}

	private String sanitizePosition(String jobTitle) {
		String trimmed = jobTitle.trim().replace('\n', ' ').replace('\r', ' ');
		if (trimmed.length() > MAX_POSITION_CHARS) {
			return trimmed.substring(0, MAX_POSITION_CHARS);
		}
		return trimmed;
	}

}
