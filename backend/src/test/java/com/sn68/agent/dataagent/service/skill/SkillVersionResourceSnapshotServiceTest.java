/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SemanticModelMapper;
import com.sn68.agent.dataagent.service.datasource.SkillDatasourceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillVersionResourceSnapshotServiceTest {

	private final SkillDatasourceService skillDatasourceService = org.mockito.Mockito.mock(SkillDatasourceService.class);

	private final SemanticModelMapper semanticModelMapper = org.mockito.Mockito.mock(SemanticModelMapper.class);

	private final BusinessKnowledgeMapper businessKnowledgeMapper = org.mockito.Mockito.mock(BusinessKnowledgeMapper.class);

	private final SkillKnowledgeMapper skillKnowledgeMapper = org.mockito.Mockito.mock(SkillKnowledgeMapper.class);

	private final SkillVersionResourceSnapshotService service = new SkillVersionResourceSnapshotService(
			skillDatasourceService, semanticModelMapper, businessKnowledgeMapper, skillKnowledgeMapper);

	@Test
	void qaSnapshotContainsOnlyCompletedSkillKnowledge() {
		DataAgentSkill skill = DataAgentSkill.builder().id(1L).tenantId("1").scope("TENANT")
			.skillKind("QA").executionMode("KNOWLEDGE").build();
		when(skillKnowledgeMapper.selectBySkillId(1L)).thenReturn(List.of(SkillKnowledge.builder()
			.id(7L)
			.skillId(1L)
			.title("订单状态")
			.content("订单状态定义")
			.isRecall(true)
			.embeddingStatus(EmbeddingStatus.COMPLETED)
			.deleted(false)
			.build()));

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of());

		assertTrue(snapshot.valid());
		assertEquals(Map.of(), snapshot.datasourceConfig());
		assertEquals(Map.of(), snapshot.semanticConfig());
		assertEquals(List.of(7L), snapshot.knowledgeConfig().get("skillKnowledgeIds"));
		assertEquals(List.of(), snapshot.knowledgeConfig().get("businessKnowledgeIds"));
		org.mockito.Mockito.verifyNoInteractions(skillDatasourceService, semanticModelMapper, businessKnowledgeMapper);
	}

	@Test
	void incompleteQaKnowledgeWarnsWithoutBlockingPublication() {
		DataAgentSkill skill = DataAgentSkill.builder().id(6L).tenantId("1").scope("TENANT")
			.skillKind("QA").executionMode("KNOWLEDGE").build();
		when(skillKnowledgeMapper.selectBySkillId(6L)).thenReturn(List.of(SkillKnowledge.builder()
			.id(9L).skillId(6L).title("待解析文档").isRecall(true).embeddingStatus(EmbeddingStatus.PROCESSING)
			.deleted(false).build()));

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of());

		assertTrue(snapshot.valid());
		assertEquals(List.of(), snapshot.knowledgeConfig().get("skillKnowledgeIds"));
		assertEquals(1, snapshot.warnings().size());
		assertTrue(snapshot.warnings().get(0).contains("待解析文档"));
	}

	@Test
	void flowSnapshotCanResolveOptionalBusinessKnowledge() {
		DataAgentSkill skill = DataAgentSkill.builder().id(3L).tenantId("1").scope("TENANT")
			.skillKind("ORCHESTRATION").executionMode("FLOW").build();

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of());

		assertTrue(snapshot.valid());
		assertEquals(Map.of(), snapshot.datasourceConfig());
		assertEquals(Map.of(), snapshot.semanticConfig());
		assertEquals(List.of(), snapshot.knowledgeConfig().get("businessKnowledgeIds"));
		org.mockito.Mockito.verify(businessKnowledgeMapper).selectBySkillId(3L);
		org.mockito.Mockito.verifyNoInteractions(skillDatasourceService, semanticModelMapper);
	}

	@Test
	void flowSnapshotRejectsBusinessKnowledgeTopKAboveRuntimeLimit() {
		DataAgentSkill skill = DataAgentSkill.builder().id(5L).tenantId("1").scope("TENANT")
			.skillKind("ACTION").executionMode("FLOW").build();
		when(businessKnowledgeMapper.selectBySkillId(5L)).thenReturn(List.of());

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of("topK", 6),
				Map.of());

		assertTrue(!snapshot.valid());
		assertTrue(snapshot.errors().contains("Business knowledge topK must be a number between 1 and 5"));
		assertTrue(!snapshot.knowledgeConfig().containsKey("topK"));
	}

	@Test
	void selectedKnowledgeWithIncompleteEmbeddingBlocksSnapshot() {
		DataAgentSkill skill = DataAgentSkill.builder().id(4L).tenantId("1").scope("TENANT")
			.skillKind("ACTION").executionMode("FLOW").build();
		when(businessKnowledgeMapper.selectBySkillId(4L)).thenReturn(List.of(BusinessKnowledge.builder()
			.id(8L).skillId(4L).businessTerm("签收量").isRecall(true).embeddingStatus(EmbeddingStatus.PROCESSING)
			.deleted(false).build()));

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of());

		assertTrue(!snapshot.valid());
		assertTrue(snapshot.errors().get(0).contains("签收量"));
		assertTrue(snapshot.errors().get(0).contains("PROCESSING"));
	}

	@Test
	void querySnapshotExpandsAnUnrestrictedTableIntoExplicitVersionColumns() throws Exception {
		DataAgentSkill skill = DataAgentSkill.builder().id(2L).tenantId("1").scope("TENANT")
			.skillKind("QUERY").executionMode("REACT").build();
		SkillDatasource datasource = SkillDatasource.builder().id(8L).skillId(2L).datasourceId(3L).isActive(true)
			.datasource(Datasource.builder().id(3L).tenantId("1").build()).selectTables(List.of("dis_demand"))
			.selectColumns(Map.of()).build();
		when(skillDatasourceService.listSkillDatasources(2L)).thenReturn(List.of(datasource));
		when(skillDatasourceService.getEffectiveSkillDatasourceColumns(2L, 3L))
			.thenReturn(Map.of("dis_demand", List.of("id", "demand_no")));
		when(semanticModelMapper.selectEnabledBySkillId(2L)).thenReturn(List.of());
		when(businessKnowledgeMapper.selectBySkillId(2L)).thenReturn(List.of());

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of());

		assertTrue(snapshot.valid());
		assertEquals(List.of(Map.of("table", "dis_demand", "columns", List.of("id", "demand_no"))),
			snapshot.datasourceConfig().get("tables"));
		assertEquals(Map.of(), snapshot.analysisConfig());
	}

	@Test
	void queryWithoutAnalysisConfigStillRequiresOneDatasource() {
		DataAgentSkill skill = DataAgentSkill.builder().id(11L).tenantId("1").scope("TENANT")
			.skillKind("QUERY").executionMode("REACT").build();
		when(skillDatasourceService.listSkillDatasources(11L)).thenReturn(List.of());
		when(businessKnowledgeMapper.selectBySkillId(11L)).thenReturn(List.of());

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of());

		assertFalse(snapshot.valid());
		assertTrue(snapshot.errors().contains("QUERY Skill requires one enabled datasource"));
	}

	@Test
	void queryWithoutAnalysisConfigRejectsTwoEnabledDatasources() {
		DataAgentSkill skill = DataAgentSkill.builder().id(12L).tenantId("1").scope("TENANT")
			.skillKind("QUERY").executionMode("REACT").build();
		when(skillDatasourceService.listSkillDatasources(12L))
			.thenReturn(List.of(activeDatasource(12L, 3L, "dis_demand"), activeDatasource(12L, 4L, "stock")));
		when(businessKnowledgeMapper.selectBySkillId(12L)).thenReturn(List.of());

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of());

		assertFalse(snapshot.valid());
		assertTrue(snapshot.errors().contains("Skill has more than one enabled datasource"));
		assertFalse(snapshot.errors().contains("QUERY Skill requires one enabled datasource"));
	}

	@Test
	void analysisConfigWithTwoJdbcDoesNotFailMoreThanOneDatasource() throws Exception {
		DataAgentSkill skill = DataAgentSkill.builder().id(13L).tenantId("1").scope("TENANT")
			.skillKind("QUERY").executionMode("REACT").build();
		when(skillDatasourceService.listSkillDatasources(13L))
			.thenReturn(List.of(activeDatasource(13L, 3L, "dis_demand"), activeDatasource(13L, 4L, "stock")));
		when(skillDatasourceService.getEffectiveSkillDatasourceColumns(13L, 3L))
			.thenReturn(Map.of("dis_demand", List.of("id", "demand_no")));
		when(skillDatasourceService.getEffectiveSkillDatasourceColumns(13L, 4L))
			.thenReturn(Map.of("stock", List.of("id", "qty")));
		when(semanticModelMapper.selectEnabledBySkillId(13L)).thenReturn(List.of());
		when(businessKnowledgeMapper.selectBySkillId(13L)).thenReturn(List.of());

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of(),
				twoJdbcAnalysisConfig());

		assertTrue(snapshot.valid(), () -> snapshot.errors().toString());
		assertFalse(snapshot.errors().contains("Skill has more than one enabled datasource"));
		assertEquals(2, ((List<?>) snapshot.datasourceConfig().get("datasources")).size());
		assertEquals(2, ((List<?>) snapshot.analysisConfig().get("sources")).size());
	}

	@Test
	void fileOnlyAnalysisConfigCanSnapshotWithoutJdbc() {
		DataAgentSkill skill = DataAgentSkill.builder().id(14L).tenantId("1").scope("TENANT")
			.skillKind("QUERY").executionMode("REACT").build();
		when(skillDatasourceService.listSkillDatasources(14L)).thenReturn(List.of());
		when(businessKnowledgeMapper.selectBySkillId(14L)).thenReturn(List.of());
		when(skillKnowledgeMapper.selectBySkillId(14L)).thenReturn(List.of());

		SkillVersionResourceSnapshotService.ResourceSnapshot snapshot = service.capture(skill, Map.of(), Map.of(),
				fileOnlyAnalysisConfig());

		assertTrue(snapshot.valid(), () -> snapshot.errors().toString());
		assertEquals(Map.of(), snapshot.datasourceConfig());
		List<?> sources = (List<?>) snapshot.analysisConfig().get("sources");
		Map<?, ?> fileSource = (Map<?, ?>) sources.get(0);
		assertEquals("FILE_TABLE", fileSource.get("type"));
		assertEquals(Boolean.TRUE, fileSource.get("turnFile"));
	}

	private SkillDatasource activeDatasource(Long skillId, Long datasourceId, String table) {
		return SkillDatasource.builder().id(datasourceId + 10).skillId(skillId).datasourceId(datasourceId).isActive(true)
			.datasource(Datasource.builder().id(datasourceId).tenantId("1").build()).selectTables(List.of(table))
			.selectColumns(Map.of()).build();
	}

	private Map<String, Object> twoJdbcAnalysisConfig() {
		return Map.of("sources", List.of(
				Map.of("id", "t1", "type", "TABLE", "datasourceId", 3L, "table", "dis_demand", "columns",
						List.of("id", "demand_no")),
				Map.of("id", "t2", "type", "TABLE", "datasourceId", 4L, "table", "stock", "columns", List.of("id", "qty"))),
				"associations", List.of(Map.of("left", Map.of("source", "t1", "field", "id"), "right",
						Map.of("source", "t2", "field", "id"), "match", "EXACT")));
	}

	private Map<String, Object> fileOnlyAnalysisConfig() {
		return Map.of("sources", List.of(Map.of("id", "f1", "type", "FILE_TABLE", "turnFile", true)));
	}

}
