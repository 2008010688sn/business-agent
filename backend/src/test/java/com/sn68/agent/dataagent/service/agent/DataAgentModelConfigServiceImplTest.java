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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.AgentModelConfig;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.repository.AgentModelConfigMapper;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataAgentModelConfigServiceImplTest {

	private final AgentModelConfigMapper agentModelConfigMapper = mock(AgentModelConfigMapper.class);

	private final ModelConfigMapper modelConfigMapper = mock(ModelConfigMapper.class);

	private final ModelConfigDataService modelConfigDataService = mock(ModelConfigDataService.class);

	private final DataAgentService agentService = mock(DataAgentService.class);

	private final AgentModelConfigServiceImpl service = new AgentModelConfigServiceImpl(agentModelConfigMapper,
			modelConfigMapper, modelConfigDataService, agentService);

	@Test
	void resolveChatModelConfig_rejectsSelectedModelOutsideAgentSelectableRange() {
		DataAgent dataAgent = DataAgent.builder().id(10L).build();
		when(modelConfigMapper.findById(99L)).thenReturn(chatModel(99L));
		when(agentModelConfigMapper.findEnabledByAgentId(10L))
			.thenReturn(List.of(AgentModelConfig.builder().agentId(10L).modelConfigId(88L).enabled(true).build()));
		when(agentModelConfigMapper.findByAgentIdAndModelConfigId(10L, 99L))
			.thenReturn(AgentModelConfig.builder()
				.agentId(10L)
				.modelConfigId(99L)
				.enabled(true)
				.userSelectable(false)
				.build());

		assertThrows(CheckedException.class, () -> service.resolveChatModelConfig(dataAgent, 99L));
	}

	@Test
	void resolveChatModelConfig_usesSavedAgentDefaultModelConfig() {
		DataAgent dataAgent = DataAgent.builder().id(10L).build();
		when(agentModelConfigMapper.findDefaultByAgentId(10L))
			.thenReturn(AgentModelConfig.builder()
				.agentId(10L)
				.modelConfigId(88L)
				.enabled(true)
				.isDefault(true)
				.build());
		when(modelConfigDataService.getRuntimeConfigById(88L, ModelType.CHAT))
			.thenReturn(com.sn68.agent.dataagent.dto.ModelConfigDTO.builder().id(88L).build());

		assertEquals(88L, service.resolveChatModelConfig(dataAgent, null).getId());
	}

	private ModelConfig chatModel(Long id) {
		return ModelConfig.builder().id(id).modelType(ModelType.CHAT).isActive(true).build();
	}

}
