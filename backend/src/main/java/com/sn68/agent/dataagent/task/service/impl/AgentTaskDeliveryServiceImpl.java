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

import com.sn68.agent.dataagent.task.dto.AgentTaskDeliveryResp;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskDelivery;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskDeliveryStatus;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskDeliveryMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskDeliveryService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * WEB 收件箱投递：工作台能看到即视为投递成功，不走 notification 栈。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskDeliveryServiceImpl implements AgentTaskDeliveryService {

	private static final String CHANNEL_WEB = "WEB";

	private static final String WEB_TARGET_PREFIX = "/ai-agent/digital-employees/detail?id=";

	private final AgentTaskDeliveryMapper deliveryMapper;

	private final AgentTaskDefinitionMapper definitionMapper;

	@Override
	public void recordWebInbox(AgentTaskRun taskRun) {
		if (taskRun == null || taskRun.getId() == null) {
			throw CheckedException.fail("任务运行缺失，无法写入投递记录");
		}
		String tenantId = taskRun.getTenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.fail("任务运行缺少租户，无法写入投递记录, taskRunId=" + taskRun.getId());
		}
		if (hasSuccessfulWebDelivery(tenantId, taskRun.getId())) {
			return;
		}
		Instant now = Instant.now();
		AgentTaskDelivery row = AgentTaskDelivery.builder()
			.tenantId(tenantId.trim())
			.taskRunId(taskRun.getId())
			.channel(CHANNEL_WEB)
			.target(webTarget(taskRun))
			.deliveryStatus(TaskDeliveryStatus.SUCCESS.getValue())
			.retryCount(0)
			.lastDeliveryTime(now)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		if (deliveryMapper.insert(row) != 1) {
			throw CheckedException.fail("WEB 投递记录写入失败, taskRunId=" + taskRun.getId());
		}
		log.info("任务 WEB 投递已记录。taskRunId={}, target={}", taskRun.getId(), row.getTarget());
	}

	@Override
	public List<AgentTaskDeliveryResp> listByTaskRun(String tenantId, Long taskRunId) {
		if (!StringUtils.hasText(tenantId) || taskRunId == null) {
			return List.of();
		}
		List<AgentTaskDelivery> rows = deliveryMapper.findByTaskRun(tenantId.trim(), taskRunId);
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		return rows.stream()
			.map(row -> new AgentTaskDeliveryResp(row.getId(), row.getChannel(), row.getTarget(),
					row.getDeliveryStatus(), row.getLastDeliveryTime(), row.getLastError()))
			.toList();
	}

	private boolean hasSuccessfulWebDelivery(String tenantId, Long taskRunId) {
		AgentTaskDelivery existing = deliveryMapper.selectOne(Wraps.<AgentTaskDelivery>lbQ()
			.eq(AgentTaskDelivery::getTenantId, tenantId.trim())
			.eq(AgentTaskDelivery::getTaskRunId, taskRunId)
			.eq(AgentTaskDelivery::getChannel, CHANNEL_WEB)
			.eq(AgentTaskDelivery::getDeliveryStatus, TaskDeliveryStatus.SUCCESS.getValue())
			.last(" limit 1"));
		return existing != null;
	}

	private String webTarget(AgentTaskRun taskRun) {
		Long employeeId = null;
		if (taskRun.getDefinitionId() != null && StringUtils.hasText(taskRun.getTenantId())) {
			AgentTaskDefinition definition = definitionMapper.findByTenantAndId(taskRun.getTenantId().trim(),
					taskRun.getDefinitionId());
			if (definition != null) {
				employeeId = definition.getDigitalEmployeeId();
			}
		}
		if (employeeId == null) {
			return WEB_TARGET_PREFIX;
		}
		String target = WEB_TARGET_PREFIX + employeeId;
		if (taskRun.getRuntimeRunId() != null) {
			target = target + "&runtimeRunId=" + taskRun.getRuntimeRunId();
		}
		return target;
	}

}
