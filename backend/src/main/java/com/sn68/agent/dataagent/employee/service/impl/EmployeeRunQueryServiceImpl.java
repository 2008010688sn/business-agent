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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunDetailResp;
import com.sn68.agent.dataagent.employee.service.EmployeeRunFeedbackService;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeRunQueryService;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetReportResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetReportService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.support.RuntimeWorkProductAssembler;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 员工履历：路径 id 定归属，请求体里的 digitalEmployeeId/ownerId/ownerType/agentId 不生效。
 */
@Service
@RequiredArgsConstructor
public class EmployeeRunQueryServiceImpl implements EmployeeRunQueryService {

	private static final String OWNER_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private final DigitalEmployeeMapper employeeMapper;

	private final AuthenticationContext authenticationContext;

	private final RuntimeRunService runtimeRunService;

	private final RuntimeWorkProductAssembler workProductAssembler;

	private final EmployeeRunFeedbackService feedbackService;

	private final RuntimeBudgetReportService budgetReportService;

	@Override
	public IPage<RuntimeRunResp> pageRuns(Long employeeId, RuntimeRunPageQueryReq request) {
		requireEmployee(employeeId);
		RuntimeRunPageQueryReq query = new RuntimeRunPageQueryReq();
		if (request != null) {
			query.setCurrent(request.getCurrent());
			query.setSize(request.getSize());
			query.setState(request.getState());
			query.setRunMode(request.getRunMode());
			query.setKeyword(request.getKeyword());
			query.setThreadId(request.getThreadId());
		}
		query.setDigitalEmployeeId(employeeId);
		return runtimeRunService.page(currentTenantId(), query);
	}

	@Override
	public EmployeeRunDetailResp getRunDetail(Long employeeId, Long runtimeRunId) {
		requireEmployee(employeeId);
		if (runtimeRunId == null) {
			throw CheckedException.badRequest("运行ID不能为空");
		}
		RuntimeRunDetailResp detail = runtimeRunService.detail(currentTenantId(), runtimeRunId);
		RuntimeRunResp run = detail == null ? null : detail.run();
		if (run == null || !belongsToEmployee(run, employeeId)) {
			throw CheckedException.notFound("运行不存在或无权访问: " + runtimeRunId);
		}
		List<RuntimeArtifactResp> artifacts = workProductAssembler.listArtifacts(run.id());
		String finalAnswer = workProductAssembler.resolveFinalAnswer(detail.finalAnswer(), artifacts);
		return new EmployeeRunDetailResp(run, finalAnswer, detail.steps(), detail.latestSeq(), artifacts,
				feedbackService.findMine(employeeId, runtimeRunId));
	}

	@Override
	public RuntimeBudgetReportResp budgetReport(Long employeeId, Instant fromTime, Instant toTime) {
		requireEmployee(employeeId);
		return budgetReportService.report(currentTenantId(), fromTime, toTime, employeeId);
	}

	private boolean belongsToEmployee(RuntimeRunResp run, Long employeeId) {
		if (employeeId.equals(run.digitalEmployeeId())) {
			return true;
		}
		return OWNER_DIGITAL_EMPLOYEE.equals(run.ownerType()) && employeeId.equals(run.ownerId());
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

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止访问数字员工运行");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止访问数字员工运行");
		}
		return tenantId.trim();
	}

}
