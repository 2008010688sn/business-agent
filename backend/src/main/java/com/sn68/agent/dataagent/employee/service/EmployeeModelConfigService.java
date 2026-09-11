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
package com.sn68.agent.dataagent.employee.service;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.agent.RuntimeChatModelDTO;
import com.sn68.agent.dataagent.employee.dto.EmployeeModelConfigItemResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.dto.UpdateEmployeeModelConfigReq;
import java.util.List;

/**
 * 数字员工可用模型配置（独立表，禁止写入 {@code agent_model_config.agent_id}）。
 */
public interface EmployeeModelConfigService {

	List<EmployeeModelConfigItemResp> listModelConfigs(Long employeeId);

	List<EmployeeModelConfigItemResp> updateModelConfigs(Long employeeId, UpdateEmployeeModelConfigReq request);

	List<RuntimeChatModelDTO> listRuntimeChatModels(Long employeeId);

	List<Long> listEnabledModelConfigIds(Long employeeId, String tenantId);

	/**
	 * 解析员工对话选用的 CHAT 模型：选中须在可用列表；未选则用默认；都空回落租户默认。
	 * 不查询 {@code agent_model_config}。
	 */
	ModelConfigDTO resolveChatModelConfig(Long employeeId, String tenantId, Long selectedChatModelConfigId,
			EmployeeReleaseSnapshot snapshot);

}
