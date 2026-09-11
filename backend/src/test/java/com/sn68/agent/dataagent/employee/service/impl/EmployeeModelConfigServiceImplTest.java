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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeModelConfig;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.employee.dto.EmployeeModelConfigItemResp;
import com.sn68.agent.dataagent.employee.dto.UpdateEmployeeModelConfigReq;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 数字员工模型配置契约测试：全量替换只接受 CHAT 模型（不要求平台 is_active），并保持默认模型语义。
 */
class EmployeeModelConfigServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private final DigitalEmployeeMapper employeeMapper = org.mockito.Mockito.mock(DigitalEmployeeMapper.class);

	private final DigitalEmployeeModelConfigMapper employeeModelConfigMapper =
			org.mockito.Mockito.mock(DigitalEmployeeModelConfigMapper.class);

	private final ModelConfigMapper modelConfigMapper = org.mockito.Mockito.mock(ModelConfigMapper.class);

	private final ModelConfigDataService modelConfigDataService = org.mockito.Mockito.mock(ModelConfigDataService.class);

	private final AuthenticationContext authenticationContext = org.mockito.Mockito.mock(AuthenticationContext.class);

	private EmployeeModelConfigServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new EmployeeModelConfigServiceImpl(employeeMapper, employeeModelConfigMapper, modelConfigMapper,
				modelConfigDataService, authenticationContext);
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.build());
	}

	@Test
	void updateReplacesModelsAndPreservesDefaultSelection() {
		when(modelConfigMapper.findById(11L)).thenReturn(model(11L, ModelType.CHAT, true));
		when(modelConfigMapper.findById(12L)).thenReturn(model(12L, ModelType.CHAT, true));
		when(employeeModelConfigMapper.findByEmployeeId(EMPLOYEE_ID, TENANT_ID)).thenReturn(List.of(
				DigitalEmployeeModelConfig.builder().id(101L).employeeId(EMPLOYEE_ID).modelConfigId(11L)
					.isDefault(true).enabled(true).userSelectable(true).build()));

		UpdateEmployeeModelConfigReq request = new UpdateEmployeeModelConfigReq(11L, List.of(
				new UpdateEmployeeModelConfigReq.ModelItem(11L, true, true),
				new UpdateEmployeeModelConfigReq.ModelItem(12L, false, true)));

		List<EmployeeModelConfigItemResp> result = service.updateModelConfigs(EMPLOYEE_ID, request);

		verify(employeeModelConfigMapper).softDeleteByEmployeeId(EMPLOYEE_ID, TENANT_ID);
		ArgumentCaptor<DigitalEmployeeModelConfig> captor = ArgumentCaptor.forClass(DigitalEmployeeModelConfig.class);
		verify(employeeModelConfigMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
		assertEquals(List.of(11L, 12L), captor.getAllValues().stream()
			.map(DigitalEmployeeModelConfig::getModelConfigId).toList());
		assertEquals(Boolean.TRUE, captor.getAllValues().get(0).getIsDefault());
		assertEquals(Boolean.FALSE, captor.getAllValues().get(1).getIsDefault());
		assertEquals(1, result.size());
		assertEquals(11L, result.get(0).getModelConfigId());
	}

	@Test
	void rejectsDefaultModelOutsideAvailableList() {
		UpdateEmployeeModelConfigReq request = new UpdateEmployeeModelConfigReq(99L,
				List.of(new UpdateEmployeeModelConfigReq.ModelItem(11L, true, true)));

		assertThrows(CheckedException.class, () -> service.updateModelConfigs(EMPLOYEE_ID, request));

		verify(modelConfigMapper, never()).findById(any());
		verify(employeeModelConfigMapper, never()).softDeleteByEmployeeId(EMPLOYEE_ID, TENANT_ID);
	}

	@Test
	void rejectsNonChatModel() {
		when(modelConfigMapper.findById(11L)).thenReturn(model(11L, ModelType.EMBEDDING, true));

		UpdateEmployeeModelConfigReq request = new UpdateEmployeeModelConfigReq(null,
				List.of(new UpdateEmployeeModelConfigReq.ModelItem(11L, true, true)));

		assertThrows(CheckedException.class, () -> service.updateModelConfigs(EMPLOYEE_ID, request));

		verify(employeeModelConfigMapper, never()).softDeleteByEmployeeId(EMPLOYEE_ID, TENANT_ID);
	}

	@Test
	void acceptsChatModelThatIsNotPlatformDefault() {
		when(modelConfigMapper.findById(11L)).thenReturn(model(11L, ModelType.CHAT, false));
		when(employeeModelConfigMapper.findByEmployeeId(EMPLOYEE_ID, TENANT_ID)).thenReturn(List.of(
				DigitalEmployeeModelConfig.builder().id(101L).employeeId(EMPLOYEE_ID).modelConfigId(11L)
					.isDefault(false).enabled(true).userSelectable(true).build()));

		UpdateEmployeeModelConfigReq request = new UpdateEmployeeModelConfigReq(null,
				List.of(new UpdateEmployeeModelConfigReq.ModelItem(11L, true, true)));

		service.updateModelConfigs(EMPLOYEE_ID, request);

		verify(employeeModelConfigMapper).softDeleteByEmployeeId(EMPLOYEE_ID, TENANT_ID);
		verify(employeeModelConfigMapper).insert(any(DigitalEmployeeModelConfig.class));
	}

	private ModelConfig model(Long id, ModelType type, boolean active) {
		return ModelConfig.builder().id(id).modelType(type).isActive(active).modelName("model-" + id)
			.provider("provider").build();
	}

}
