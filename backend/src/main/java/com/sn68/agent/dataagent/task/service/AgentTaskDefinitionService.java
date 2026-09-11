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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionModifyReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionSaveReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskDetailResp;
import com.sn68.agent.dataagent.task.dto.AgentTaskPageQueryReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperService;

/**
 * Agent任务定义服务契约。
 */
public interface AgentTaskDefinitionService extends SuperService<AgentTaskDefinition> {

	/**
	 * 分页查询当前租户任务定义。
	 */
	IPage<AgentTaskDefinition> pageByTenant(AgentTaskPageQueryReq request);

	/**
	 * 创建任务定义并生成首个不可变版本。
	 */
	void create(AgentTaskDefinitionSaveReq request);

	/**
	 * 修改任务定义；传入参数/提示词快照时生成新的不可变版本。
	 */
	void modify(Long id, AgentTaskDefinitionModifyReq request);

	/**
	 * 删除任务定义（连带逻辑删除其触发器；运行记录保留可追溯）。
	 */
	void delete(Long id);

	/**
	 * 查询任务定义详情（定义 + 最新版本 + 触发器）。
	 */
	AgentTaskDetailResp detail(Long id);

	/**
	 * 校验并返回当前租户下的任务定义，不存在或跨租户一律按不存在处理。
	 */
	AgentTaskDefinition requireOwned(Long id);

}
