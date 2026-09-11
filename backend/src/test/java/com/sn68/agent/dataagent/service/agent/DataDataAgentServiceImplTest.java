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

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.service.file.FilePreviewResp;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataDataAgentServiceImplTest {

	private static final String TENANT_ID = "1001";

	private final DataAgentMapper dataAgentMapper = mock(DataAgentMapper.class);

	private final AgentVectorStoreService agentVectorStoreService = mock(AgentVectorStoreService.class);

	private final LocalFileService localFileService = mock(LocalFileService.class);

	private final DataAgentProperties properties = runtimeProperties();

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final DataAgentServiceImpl service = new DataAgentServiceImpl(dataAgentMapper, agentVectorStoreService,
			localFileService, properties, new AgentTemporalService(), authenticationContext);

	@BeforeEach
	void bindTenantContext() {
		// save() 走 assignTenant()，无租户上下文即 400。这里的用例断言的是超时/时区策略，
		// 租户只是必要前置，不是被测行为。
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
	}

	@Test
	void save_defaultsRuntimeTimeoutSecondsFromRuntimePropertiesWhenEmpty() {
		service.save(DataAgent.builder().agentType(AgentTypeConstant.DATA_ANALYSIS).build());

		ArgumentCaptor<DataAgent> captor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentMapper).insert(captor.capture());
		assertEquals(150, captor.getValue().getRuntimeTimeoutSeconds());
	}

	@Test
	void save_defaultsRuntimeTimeoutSecondsFromRuntimePropertiesWhenInvalid() {
		service.save(DataAgent.builder().agentType(AgentTypeConstant.DATA_ANALYSIS).runtimeTimeoutSeconds(0).build());

		ArgumentCaptor<DataAgent> captor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentMapper).insert(captor.capture());
		assertEquals(150, captor.getValue().getRuntimeTimeoutSeconds());
	}

	private static DataAgentProperties runtimeProperties() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getRuntime().setTotalTimeout(java.time.Duration.ofSeconds(150));
		return properties;
	}

	@Test
	void save_keepsConfiguredRuntimeTimeoutSeconds() {
		when(dataAgentMapper.findById(1L)).thenReturn(DataAgent.builder().id(1L).tenantId(TENANT_ID).build());

		service.save(DataAgent.builder()
			.id(1L)
			.agentType(AgentTypeConstant.DATA_ANALYSIS)
			.runtimeTimeoutSeconds(180)
			.build());

		ArgumentCaptor<DataAgent> captor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentMapper).updateById(captor.capture());
		assertEquals(180, captor.getValue().getRuntimeTimeoutSeconds());
	}

	@Test
	void save_keepsExistingRuntimeTimeoutSecondsWhenUpdateDoesNotProvideOne() {
		when(dataAgentMapper.findById(1L))
			.thenReturn(DataAgent.builder().id(1L).tenantId(TENANT_ID).runtimeTimeoutSeconds(240).build());

		service.save(DataAgent.builder().id(1L).agentType(AgentTypeConstant.DATA_ANALYSIS).build());

		ArgumentCaptor<DataAgent> captor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentMapper).updateById(captor.capture());
		assertEquals(240, captor.getValue().getRuntimeTimeoutSeconds());
	}

	@Test
	void findById_returnsRawAgentWithoutResolvingAvatarPreview() {
		DataAgent agent = DataAgent.builder().id(1L).tenantId(TENANT_ID)
			.avatar("https://bucket.oss-cn.example.com/a.png").build();
		when(dataAgentMapper.findById(1L)).thenReturn(agent);

		DataAgent result = service.findById(1L);

		assertEquals(agent, result);
		assertNull(result.getAvatarPreviewUrl());
		verify(localFileService, never()).findByPaths(org.mockito.ArgumentMatchers.anyCollection());
		verify(localFileService, never()).findDisplayPreviews(org.mockito.ArgumentMatchers.anyCollection());
	}

	@Test
	void findByIdWithPreview_resolvesAvatarPreviewFromSuitePath() {
		DataAgent agent = DataAgent.builder().id(1L).tenantId(TENANT_ID)
			.avatar("https://bucket.oss-cn.example.com/a.png").build();
		FilePreviewResp preview = FilePreviewResp.builder()
			.path("https://bucket.oss-cn.example.com/a.png")
			.previewUrl("https://preview.example.com/a.png")
			.build();
		when(dataAgentMapper.findById(1L)).thenReturn(agent);
		when(localFileService.findDisplayPreviews(List.of("https://bucket.oss-cn.example.com/a.png")))
			.thenReturn(Map.of("https://bucket.oss-cn.example.com/a.png", preview));

		DataAgent result = service.findByIdWithPreview(1L);

		assertEquals("https://preview.example.com/a.png", result.getAvatarPreviewUrl());
	}

	@Test
	void findByStatusRaw_doesNotResolveAvatarPreviewDuringStartup() {
		when(dataAgentMapper.findByStatus("published"))
			.thenReturn(List.of(DataAgent.builder().id(1L).avatar("https://bucket.oss-cn.example.com/a.png").build()));

		List<DataAgent> result = service.findByStatusRaw("published");

		assertEquals(1, result.size());
		assertNull(result.get(0).getAvatarPreviewUrl());
		verify(localFileService, never()).findByPaths(org.mockito.ArgumentMatchers.anyCollection());
		verify(localFileService, never()).findDisplayPreviews(org.mockito.ArgumentMatchers.anyCollection());
	}

	@Test
	void list_enrichesAvatarPreviewFromLocalFileService() {
		String avatar = "https://bucket.oss-cn.example.com/a.png";
		FilePreviewResp preview = FilePreviewResp.builder()
			.path(avatar)
			.previewUrl("https://preview.example.com/a.png")
			.build();
		when(dataAgentMapper.findByConditions(null, null, TENANT_ID))
			.thenReturn(List.of(DataAgent.builder().id(1L).tenantId(TENANT_ID).avatar(avatar).build()));
		when(localFileService.findDisplayPreviews(List.of(avatar))).thenReturn(Map.of(avatar, preview));

		List<DataAgent> result = service.list(null, null);

		assertEquals(1, result.size());
		assertEquals("https://preview.example.com/a.png", result.get(0).getAvatarPreviewUrl());
	}

	@Test
	void save_keepsExistingTemporalPolicyWhenUpdateOmitsIt() {
		Map<String, Object> existingPolicy = Map.of("zoneId", "Europe/Paris", "locale", "fr-FR",
				"weekStartsOn", "MONDAY", "ambiguityStrategy", "ASK");
		when(dataAgentMapper.findById(1L)).thenReturn(DataAgent.builder().id(1L).tenantId(TENANT_ID)
			.runtimeTimeoutSeconds(240).temporalPolicy(existingPolicy).build());

		service.save(DataAgent.builder().id(1L).agentType(AgentTypeConstant.DATA_ANALYSIS).build());

		ArgumentCaptor<DataAgent> captor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentMapper).updateById(captor.capture());
		assertEquals("Europe/Paris", captor.getValue().getTemporalPolicy().get("zoneId"));
	}

	@Test
	void save_emptyTemporalPolicyRestoresDefaultsAndRejectsInvalidZone() {
		service.save(DataAgent.builder().agentType(AgentTypeConstant.DATA_ANALYSIS)
			.temporalPolicy(Map.of()).build());
		ArgumentCaptor<DataAgent> captor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentMapper).insert(captor.capture());
		assertEquals("Asia/Shanghai", captor.getValue().getTemporalPolicy().get("zoneId"));

		assertThrows(CheckedException.class, () -> service.save(DataAgent.builder()
			.agentType(AgentTypeConstant.DATA_ANALYSIS)
			.temporalPolicy(Map.of("zoneId", "invalid-zone"))
			.build()));
	}

	@Test
	void save_validatesAvatarAsSuiteReference() {
		String avatar = "https://bucket.oss-cn.example.com/a.png";
		service.save(DataAgent.builder().agentType(AgentTypeConstant.DATA_ANALYSIS).avatar(avatar).build());

		verify(localFileService).validateSuiteReference(avatar);
	}

	@Test
	void save_clearsInvalidAvatarReference() {
		String avatar = "https://preview.example.com/a.png?Expires=1";
		org.mockito.Mockito.doThrow(CheckedException.badRequest("Suite file path cannot contain temporary query parameters"))
			.when(localFileService)
			.validateSuiteReference(avatar);

		service.save(DataAgent.builder().agentType(AgentTypeConstant.DATA_ANALYSIS).avatar(avatar).build());

		ArgumentCaptor<DataAgent> captor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentMapper).insert(captor.capture());
		assertNull(captor.getValue().getAvatar());
	}

}
