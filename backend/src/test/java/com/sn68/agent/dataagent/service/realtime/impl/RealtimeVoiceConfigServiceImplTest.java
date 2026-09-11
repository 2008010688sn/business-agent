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
package com.sn68.agent.dataagent.service.realtime.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.realtime.RealtimeVoiceConfigDTO;
import com.sn68.agent.dataagent.entity.RealtimeVoiceConfig;
import com.sn68.agent.dataagent.repository.ModelAsrConfigMapper;
import com.sn68.agent.dataagent.repository.ModelRealtimeVoiceConfigMapper;
import com.sn68.agent.dataagent.repository.ModelTtsConfigMapper;
import com.sn68.agent.dataagent.repository.RealtimeVoiceConfigMapper;
import com.sn68.agent.dataagent.repository.TtsVoiceProfileMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.dataagent.service.tts.impl.TtsJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.Test;

class RealtimeVoiceConfigServiceImplTest {

	private final RealtimeVoiceConfigMapper realtimeVoiceConfigMapper = mock(RealtimeVoiceConfigMapper.class);

	private final PlatformScopePermissionService platformScopePermissionService = mock(
			PlatformScopePermissionService.class);

	private final RealtimeVoiceConfigServiceImpl service = new RealtimeVoiceConfigServiceImpl(
			mock(ModelAsrConfigMapper.class), mock(ModelRealtimeVoiceConfigMapper.class), realtimeVoiceConfigMapper,
			mock(ModelTtsConfigMapper.class), mock(TtsVoiceProfileMapper.class), mock(ModelConfigDataService.class),
			new TtsJsonSupport(new ObjectMapper()), platformScopePermissionService);

	@Test
	void tenantDefaultIsSavedForCurrentTenant() {
		when(platformScopePermissionService.requireCurrentTenantId(anyString())).thenReturn("tenant-1");

		service.saveConfig(RealtimeVoiceConfigDTO.builder().isDefault(true).build());

		verify(realtimeVoiceConfigMapper).insert(any(RealtimeVoiceConfig.class));
		verify(realtimeVoiceConfigMapper).clearDefault(eq("tenant-1"), isNull(), any());
		verify(platformScopePermissionService, never()).requirePlatformAdmin(anyString());
	}

	@Test
	void agentScopedDefaultStaysOpenToTenantUsers() {
		when(platformScopePermissionService.requireCurrentTenantId(anyString())).thenReturn("tenant-1");

		service.saveConfig(RealtimeVoiceConfigDTO.builder().agentId(100L).isDefault(true).build());

		verify(realtimeVoiceConfigMapper).insert(any(RealtimeVoiceConfig.class));
		verify(realtimeVoiceConfigMapper).clearDefault(eq("tenant-1"), eq(100L), any());
	}

}
