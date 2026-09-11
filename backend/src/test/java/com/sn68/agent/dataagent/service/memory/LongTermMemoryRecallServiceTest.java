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

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryConfigResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryRecallResultDTO;
import com.sn68.agent.dataagent.entity.AgentMemory;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.repository.AgentMemoryMapper;
import com.sn68.agent.dataagent.repository.AgentMemoryConfigMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryRecallServiceTest {

	private final AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);

	private final AgentMemoryConfigMapper configMapper = mock(AgentMemoryConfigMapper.class);

	private final LongTermMemoryPromptAssembler assembler = new LongTermMemoryPromptAssembler();

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final AgentVectorStoreService vectorStoreService = mock(AgentVectorStoreService.class);

	private final LongTermMemoryRecallService service = new LongTermMemoryRecallService(memoryMapper, configMapper,
			assembler, new DataAgentProperties(), authenticationContext, vectorStoreService);

	@Test
	void recall_skipsWhenGlobalSwitchDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getLongTermMemory().setEnabled(false);
		LongTermMemoryRecallService disabledService = new LongTermMemoryRecallService(memoryMapper, configMapper,
				assembler, properties, authenticationContext, vectorStoreService);

		AgentMemoryRecallResultDTO result = disabledService.recall("1", "u1", "账单按什么口径？", List.of());

		assertEquals(0, result.injectedCount());
		assertEquals("", result.promptBlock());
	}

	@Test
	void recall_filtersCurrentAgentUserEnabledTypesAndThresholds() {
		when(configMapper.findByAgentIdAndUserId(1L, "u1")).thenReturn(null);
		when(authenticationContext.tenantId()).thenReturn("1");
		when(vectorStoreService.getDocumentsForAgent(eq("1"), anyString(), eq(DocumentMetadataConstant.AGENT_MEMORY),
				anyInt(), anyDouble())).thenReturn(List.of());
		when(memoryMapper.findRecallCandidates(1L, "u1", "1")).thenReturn(List.of(
				memory(1L, "1", "u1", AgentMemoryType.PREFERENCE, AgentMemoryStatus.ACTIVE, 0.8, 0.9,
						"用户偏好按客户维度汇总账单。"),
				memory(2L, "1", "u1", AgentMemoryType.SEMANTIC, AgentMemoryStatus.DISABLED, 0.9, 0.9,
						"禁用记忆不召回。"),
				memory(3L, "1", "u1", AgentMemoryType.PROCEDURAL, AgentMemoryStatus.ACTIVE, 0.1, 0.9,
						"重要度过低不召回。"),
				memory(4L, "1", "u2", AgentMemoryType.PREFERENCE, AgentMemoryStatus.ACTIVE, 0.9, 0.9,
						"其他用户不召回。")));

		AgentMemoryConfigResp config = AgentMemoryConfigResp.defaults(1L, "u1", new DataAgentProperties());
		config.setRecallEnabled(true);
		config.setRecallTypes(List.of(AgentMemoryType.PREFERENCE, AgentMemoryType.SEMANTIC));
		config.setMinImportance(0.5);
		config.setSimilarityThreshold(0.2);

		AgentMemoryRecallResultDTO result = service.recall("1", "u1", "账单客户维度汇总", List.of(), config);

		assertEquals(1, result.injectedCount());
		assertTrue(result.promptBlock().contains("用户偏好按客户维度汇总账单"));
		assertEquals(1, result.hits().size());
		assertEquals(1L, result.hits().get(0).id());
	}

	/**
	 * PR-7 无人值守守卫：数字员工链路不读真人召回配置、不查真人候选，
	 * 候选严格走 findRecallCandidatesForDigitalEmployee（WORKSPACE 共享记忆）。
	 */
	@Test
	void recallForDigitalEmployeeNeverTouchesUserMemories() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(vectorStoreService.getDocumentsForAgent(eq("1"), anyString(), eq(DocumentMetadataConstant.AGENT_MEMORY),
				anyInt(), anyDouble())).thenReturn(List.of());
		when(memoryMapper.findRecallCandidatesForDigitalEmployee(1L, 55L, "1")).thenReturn(List.of());

		AgentMemoryRecallResultDTO result = service.recallForDigitalEmployee("1", "55", "本月生鲜箱结算口径", List.of());

		verify(memoryMapper).findRecallCandidatesForDigitalEmployee(1L, 55L, "1");
		verify(memoryMapper, never()).findRecallCandidates(anyLong(), anyString(), anyString());
		verify(configMapper, never()).findByAgentIdAndUserId(anyLong(), anyString());
		assertEquals(0, result.injectedCount());
	}

	/** PR-7：数字员工链路召回 WORKSPACE 共享记忆，过期（expiresAt）候选被过滤。 */
	@Test
	void recallForDigitalEmployeeRecallsSharedMemoryAndFiltersExpired() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(vectorStoreService.getDocumentsForAgent(eq("1"), anyString(), eq(DocumentMetadataConstant.AGENT_MEMORY),
				anyInt(), anyDouble())).thenReturn(List.of());
		when(memoryMapper.findRecallCandidatesForDigitalEmployee(1L, 55L, "1"))
			.thenReturn(List.of(
					sharedMemory(11L, "数字员工按客户维度出日报。", null),
					sharedMemory(12L, "已过期的共享记忆不召回。", Instant.parse("2020-01-01T00:00:00Z"))));

		AgentMemoryRecallResultDTO result = service.recallForDigitalEmployee("1", "55", "数字员工按客户维度出日报。", List.of());

		assertEquals(1, result.injectedCount());
		assertTrue(result.promptBlock().contains("数字员工按客户维度出日报"));
		assertEquals(1, result.hits().size());
		assertEquals(11L, result.hits().get(0).id());
	}

	@Test
	void recall_usesVectorHitsHydratedByTenantAndSkipsLexicalScan() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(vectorStoreService.getDocumentsForAgent(eq("1"), eq("默认提货网点"),
				eq(DocumentMetadataConstant.AGENT_MEMORY), anyInt(), anyDouble()))
			.thenReturn(List.of(memoryDocument("11")));
		AgentMemory hydrated = memory(11L, "1", "u1", AgentMemoryType.PREFERENCE, AgentMemoryStatus.ACTIVE, 0.8, 0.9,
				"用户偏好默认提货网点太阳食品。");
		hydrated.setSubjectType(MemoryScope.EMPLOYEE_USER);
		when(memoryMapper.findByIdsAndTenant(any(), eq("1"))).thenReturn(List.of(hydrated));
		AgentMemoryConfigResp config = AgentMemoryConfigResp.defaults(1L, "u1", new DataAgentProperties());
		config.setRecallEnabled(true);

		AgentMemoryRecallResultDTO result = service.recall("1", "u1", "默认提货网点", List.of(), config);

		assertEquals(1, result.injectedCount());
		assertEquals(11L, result.hits().get(0).id());
		verify(memoryMapper, never()).findRecallCandidates(anyLong(), anyString(), anyString());
	}

	@Test
	void recall_usesExplicitTenantWhenAuthContextMissing() {
		when(authenticationContext.tenantId()).thenThrow(new IllegalStateException("no ttl"));
		when(vectorStoreService.getDocumentsForAgent(eq("1"), anyString(), eq(DocumentMetadataConstant.AGENT_MEMORY),
				anyInt(), anyDouble())).thenReturn(List.of());
		when(memoryMapper.findRecallCandidates(1L, "u1", "tenant-x")).thenReturn(List.of(memory(1L, "1", "u1",
				AgentMemoryType.PREFERENCE, AgentMemoryStatus.ACTIVE, 0.8, 0.9, "用户偏好按客户维度汇总账单。")));
		AgentMemoryConfigResp config = AgentMemoryConfigResp.defaults(1L, "u1", new DataAgentProperties());
		config.setRecallEnabled(true);
		config.setSimilarityThreshold(0.2);

		AgentMemoryRecallResultDTO result = service.recall("1", "u1", "账单客户维度汇总", List.of(), config, "tenant-x");

		assertEquals(1, result.injectedCount());
		verify(memoryMapper).findRecallCandidates(1L, "u1", "tenant-x");
		verify(memoryMapper).markUsed(List.of(1L), "tenant-x");
	}

	@Test
	void recall_doesNotInjectVectorIdMissingFromTenantTable() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(vectorStoreService.getDocumentsForAgent(eq("1"), anyString(), eq(DocumentMetadataConstant.AGENT_MEMORY),
				anyInt(), anyDouble())).thenReturn(List.of(memoryDocument("99")));
		when(memoryMapper.findByIdsAndTenant(any(), eq("1"))).thenReturn(List.of());
		when(memoryMapper.findRecallCandidates(1L, "u1", "1")).thenReturn(List.of());
		AgentMemoryConfigResp config = AgentMemoryConfigResp.defaults(1L, "u1", new DataAgentProperties());
		config.setRecallEnabled(true);

		AgentMemoryRecallResultDTO result = service.recall("1", "u1", "默认提货网点", List.of(), config);

		assertEquals(0, result.injectedCount());
		verify(memoryMapper).findByIdsAndTenant(any(), eq("1"));
	}

	private static Document memoryDocument(String memoryId) {
		return new Document("vector-hit", Map.of(DocumentMetadataConstant.DB_AGENT_MEMORY_ID, memoryId));
	}

	private AgentMemory memory(Long id, String agentId, String userId, AgentMemoryType type, AgentMemoryStatus status,
			double importance, double confidence, String summary) {
		return AgentMemory.builder()
			.id(id)
			.agentId(Long.valueOf(agentId))
			.userId(userId)
			.memoryType(type)
			.status(status)
			.importance(importance)
			.confidence(confidence)
			.summary(summary)
			.content(summary)
			.createTime(Instant.now())
			.deleted(false)
			.build();
	}

	private AgentMemory sharedMemory(Long id, String summary, Instant expiresAt) {
		return AgentMemory.builder()
			.id(id)
			.agentId(1L)
			.digitalEmployeeId(55L)
			.subjectType(MemoryScope.WORKSPACE)
			.subjectId("55")
			.memoryType(AgentMemoryType.SEMANTIC)
			.status(AgentMemoryStatus.ACTIVE)
			.importance(0.8)
			.confidence(0.9)
			.summary(summary)
			.content(summary)
			.expiresAt(expiresAt)
			.createTime(Instant.now())
			.deleted(false)
			.build();
	}

}
