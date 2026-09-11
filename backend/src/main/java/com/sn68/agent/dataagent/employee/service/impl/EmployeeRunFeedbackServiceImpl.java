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
package com.sn68.agent.dataagent.employee.service.impl;

import com.sn68.agent.dataagent.employee.dto.EmployeeRunFeedbackReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunFeedbackResp;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeRunFeedbackService;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRunFeedback;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunFeedbackMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 同一用户对同一 Run 覆盖写。不自动改 prompt。
 */
@Service
@RequiredArgsConstructor
public class EmployeeRunFeedbackServiceImpl implements EmployeeRunFeedbackService {

	private static final String OWNER_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private static final String RATING_UP = "UP";

	private static final String RATING_DOWN = "DOWN";

	private final DigitalEmployeeMapper employeeMapper;

	private final RuntimeRunService runtimeRunService;

	private final AgentRuntimeRunFeedbackMapper feedbackMapper;

	private final AuthenticationContext authenticationContext;

	@Override
	public EmployeeRunFeedbackResp save(Long employeeId, Long runtimeRunId, EmployeeRunFeedbackReq request) {
		requireEmployee(employeeId);
		requireOwnedRun(employeeId, runtimeRunId);
		String rating = normalizeRating(request == null ? null : request.getRating());
		String comment = request == null ? null : trimToNull(request.getComment());
		String tenantId = currentTenantId();
		String userId = currentUserId();
		Instant now = Instant.now();
		AgentRuntimeRunFeedback existing = feedbackMapper.findByTenantRunUser(tenantId, runtimeRunId, userId);
		if (existing == null) {
			AgentRuntimeRunFeedback row = AgentRuntimeRunFeedback.builder()
				.tenantId(tenantId)
				.runId(runtimeRunId)
				.userId(userId)
				.rating(rating)
				.comment(comment)
				.createTime(now)
				.lastModifyTime(now)
				.deleted(false)
				.build();
			if (feedbackMapper.insert(row) != 1) {
				throw CheckedException.fail("运行反馈写入失败, runId=" + runtimeRunId);
			}
			return new EmployeeRunFeedbackResp(row.getRating(), row.getComment(), row.getCreateTime());
		}
		existing.setRating(rating);
		existing.setComment(comment);
		existing.setLastModifyTime(now);
		feedbackMapper.updateById(existing);
		return new EmployeeRunFeedbackResp(existing.getRating(), existing.getComment(), existing.getCreateTime());
	}

	@Override
	public EmployeeRunFeedbackResp findMine(Long employeeId, Long runtimeRunId) {
		if (employeeId == null || runtimeRunId == null) {
			return null;
		}
		AgentRuntimeRunFeedback row = feedbackMapper.findByTenantRunUser(currentTenantId(), runtimeRunId,
				currentUserId());
		if (row == null) {
			return null;
		}
		return new EmployeeRunFeedbackResp(row.getRating(), row.getComment(), row.getCreateTime());
	}

	private String normalizeRating(String rating) {
		if (!StringUtils.hasText(rating)) {
			throw CheckedException.badRequest("反馈类型不能为空");
		}
		String normalized = rating.trim().toUpperCase();
		if (!RATING_UP.equals(normalized) && !RATING_DOWN.equals(normalized)) {
			throw CheckedException.badRequest("反馈只能是 UP 或 DOWN");
		}
		return normalized;
	}

	private void requireOwnedRun(Long employeeId, Long runtimeRunId) {
		if (runtimeRunId == null) {
			throw CheckedException.badRequest("运行ID不能为空");
		}
		RuntimeRunDetailResp detail = runtimeRunService.detail(currentTenantId(), runtimeRunId);
		RuntimeRunResp run = detail == null ? null : detail.run();
		if (run == null) {
			throw CheckedException.notFound("运行不存在或无权访问: " + runtimeRunId);
		}
		boolean owned = employeeId.equals(run.digitalEmployeeId())
				|| (OWNER_DIGITAL_EMPLOYEE.equals(run.ownerType()) && employeeId.equals(run.ownerId()));
		if (!owned) {
			throw CheckedException.notFound("运行不存在或无权访问: " + runtimeRunId);
		}
	}

	private DigitalEmployee requireEmployee(Long employeeId) {
		if (employeeId == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, currentTenantId());
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		return employee;
	}

	private String currentUserId() {
		String userId;
		try {
			userId = authenticationContext.userId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录用户，禁止提交运行反馈");
		}
		if (!StringUtils.hasText(userId)) {
			throw CheckedException.forbidden("当前登录用户为空，禁止提交运行反馈");
		}
		return userId.trim();
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止提交运行反馈");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止提交运行反馈");
		}
		return tenantId.trim();
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
