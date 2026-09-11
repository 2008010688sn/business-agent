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
package com.sn68.agent.dataagent.service.memory;

import com.sn68.agent.dataagent.dto.memory.AgentMemoryCandidateSaveReq;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryConfigResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryExportItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemsQueryReq;
import com.sn68.agent.dataagent.dto.memory.UpdateAgentMemoryConfigReq;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import java.util.List;

/**
 * Agent记忆服务契约。
 */
public interface AgentMemoryService {

	/**
	 * 查询Agent记忆。
	 */
	AgentMemoryConfigResp getConfig(Long agentId, String userId);

	/**
	 * 保存Agent记忆。
	 */
	AgentMemoryConfigResp saveConfig(Long agentId, String userId, UpdateAgentMemoryConfigReq request);

	/**
	 * 按 Agent、用户查询个人侧记忆列表，支持记忆类型、状态与治理维度（范围/敏感级/同意状态）筛选。
	 */
	List<AgentMemoryItemResp> listMemories(AgentMemoryItemsQueryReq request, String userId);

	/**
	 * 保存Agent记忆。
	 */
	void updateStatus(Long agentId, String userId, Long memoryId, AgentMemoryStatus status);

	/**
	 * 删除Agent记忆。
	 */
	void deleteMemory(Long agentId, String userId, Long memoryId);

	/**
	 * 删除Agent记忆。
	 */
	void clearMyMemories(Long agentId, String userId);

	/**
	 * 长期记忆候选治理写入口（方案第十二章）：校验来源根 Run 成功、敏感记忆同意状态、
	 * 内容红线（不保存隐藏思维链与原始敏感业务 payload），PROCEDURAL 记忆默认待人工审核，
	 * 同 factKey 写入按 revision 递增修订。返回落库记忆ID。
	 */
	Long saveLongTermCandidate(AgentMemoryCandidateSaveReq request);

	/**
	 * 程序性（PROCEDURAL）记忆人工审核通过：仅 PENDING_REVIEW 状态可执行，通过后转为 ACTIVE 才参与召回。
	 */
	void approveProceduralMemory(Long agentId, Long memoryId);

	/**
	 * 按单一记忆范围 + 主体查询记忆清单，范围之间严格隔离，禁止跨 scope 混查。
	 */
	List<AgentMemoryItemResp> listMemoriesByScope(Long agentId, MemoryScope scope, String subjectId);

	/**
	 * 按主体全量导出记忆（含治理字段）。用户侧范围（EMPLOYEE_USER / EPISODIC / PROCEDURAL）
	 * 只能导出当前登录用户，禁止跨主体。
	 */
	List<AgentMemoryExportItemResp> exportMemoriesBySubject(Long agentId, MemoryScope scope, String subjectId);

}
