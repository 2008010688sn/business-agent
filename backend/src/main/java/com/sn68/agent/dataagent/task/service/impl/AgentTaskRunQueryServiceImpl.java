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
package com.sn68.agent.dataagent.task.service.impl;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeStepResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.support.RuntimeWorkProductAssembler;
import com.sn68.agent.dataagent.task.dto.AgentTaskRunDetailResp;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskDefinitionService;
import com.sn68.agent.dataagent.task.service.AgentTaskDeliveryService;
import com.sn68.agent.dataagent.task.service.AgentTaskRunQueryService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 按 taskRunId 聚合台账与 RuntimeRun 结论。定义归属走 requireOwned。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskRunQueryServiceImpl implements AgentTaskRunQueryService {

	private final AuthenticationContext authenticationContext;

	private final AgentTaskRunMapper taskRunMapper;

	private final AgentTaskDefinitionService definitionService;

	private final RuntimeRunService runtimeRunService;

	private final RuntimeWorkProductAssembler workProductAssembler;

	private final AgentTaskDeliveryService deliveryService;

	@Override
	public AgentTaskRunDetailResp findDetail(Long taskRunId) {
		if (taskRunId == null) {
			throw CheckedException.badRequest("任务运行ID不能为空");
		}
		String tenantId = requireCurrentTenantId();
		AgentTaskRun taskRun = taskRunMapper.findByTenantAndId(tenantId, taskRunId);
		if (taskRun == null) {
			throw CheckedException.notFound("任务运行不存在: " + taskRunId);
		}
		definitionService.requireOwned(taskRun.getDefinitionId());
		String finalAnswer = null;
		List<RuntimeStepResp> steps = List.of();
		List<RuntimeArtifactResp> artifacts = List.of();
		if (taskRun.getRuntimeRunId() != null) {
			try {
				RuntimeRunDetailResp runtimeDetail = runtimeRunService.detail(tenantId,
						taskRun.getRuntimeRunId());
				if (runtimeDetail != null) {
					steps = runtimeDetail.steps() == null ? List.of() : runtimeDetail.steps();
					artifacts = workProductAssembler.listArtifacts(taskRun.getRuntimeRunId());
					finalAnswer = workProductAssembler.resolveFinalAnswer(runtimeDetail.finalAnswer(), artifacts);
				}
			}
			catch (CheckedException ex) {
				log.warn("任务运行关联的 RuntimeRun 不可访问, 仍返回台账。taskRunId={}, runtimeRunId={}",
						taskRunId, taskRun.getRuntimeRunId(), ex);
			}
		}
		return new AgentTaskRunDetailResp(taskRun.getId(), taskRun.getDefinitionId(), taskRun.getRuntimeRunId(),
				taskRun.getTriggerType(), taskRun.getRunStatus(), taskRun.getErrorMessage(),
				taskRun.getScheduledTime(), taskRun.getStartedTime(), taskRun.getFinishedTime(),
				taskRun.getServicePrincipal(), finalAnswer, steps, artifacts,
				deliveryService.listByTaskRun(tenantId, taskRun.getId()));
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次任务运行详情查询", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法查询任务运行");
		}
		return tenantId.trim();
	}

}
