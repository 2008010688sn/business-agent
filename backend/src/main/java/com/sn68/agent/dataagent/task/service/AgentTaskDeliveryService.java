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
package com.sn68.agent.dataagent.task.service;

import com.sn68.agent.dataagent.task.dto.AgentTaskDeliveryResp;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import java.util.List;

/**
 * 任务结果投递。本期 WEB 收件箱：终态写入一条 SUCCESS 记录，目标为员工详情页。
 */
public interface AgentTaskDeliveryService {

	/**
	 * 幂等写入 WEB 渠道投递。同一 taskRun 已有 SUCCESS 则跳过。
	 */
	void recordWebInbox(AgentTaskRun taskRun);

	List<AgentTaskDeliveryResp> listByTaskRun(String tenantId, Long taskRunId);

}
