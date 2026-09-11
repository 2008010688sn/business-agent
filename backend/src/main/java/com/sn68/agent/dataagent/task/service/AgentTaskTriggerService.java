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

import com.sn68.agent.dataagent.task.dto.AgentTaskApiSecretResp;
import com.sn68.agent.dataagent.task.dto.AgentTaskTriggerModifyReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskTriggerSaveReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperService;
import java.util.List;

/**
 * Agent任务触发器服务契约。
 */
public interface AgentTaskTriggerService extends SuperService<AgentTaskTrigger> {

	/**
	 * 查询任务定义下的触发器列表。
	 */
	List<AgentTaskTrigger> listByDefinition(Long definitionId);

	/**
	 * 为任务定义创建触发器（SCHEDULE 校验 cron/时区并计算首次执行时刻；EVENT 校验事件主题）。
	 */
	void createTrigger(Long definitionId, AgentTaskTriggerSaveReq request);

	/**
	 * 修改触发器（触发类型不可变更）。
	 */
	void modifyTrigger(Long definitionId, Long triggerId, AgentTaskTriggerModifyReq request);

	/**
	 * 删除触发器。
	 */
	void deleteTrigger(Long definitionId, Long triggerId);

	/**
	 * API 触发一次任务运行：Idempotency-Key 必填（重放返回已有运行），
	 * 并按开放签名协议校验 X-Signature/X-Timestamp/X-Nonce（见 {@link ApiTriggerSecurityService}）。
	 */
	AgentTaskRun apiTrigger(Long definitionId, Long triggerId, ApiTriggerCommand command);

	/**
	 * 控制台立即执行一次：登录态 + task:trigger 权限，不走 HMAC。
	 * 支持 SCHEDULE/EVENT/API；不推进 SCHEDULE 的 nextFireTime。
	 */
	AgentTaskRun manualTrigger(Long definitionId, Long triggerId);

	/**
	 * 生成/轮换 API 触发签名密钥：明文仅本次返回，落库密文，旧密钥即刻失效。
	 */
	AgentTaskApiSecretResp rotateApiSecret(Long definitionId, Long triggerId);

}
