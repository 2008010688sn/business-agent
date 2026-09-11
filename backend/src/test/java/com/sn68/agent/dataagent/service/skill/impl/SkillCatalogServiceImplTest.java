/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;
import com.sn68.agent.dataagent.dto.skill.SkillImportReq;
import com.sn68.agent.dataagent.dto.skill.SkillPreviewResp;
import com.sn68.agent.dataagent.dto.skill.SkillSaveReq;
import com.sn68.agent.dataagent.dto.skill.SkillToolEditorContextResp;
import com.sn68.agent.dataagent.dto.skill.SkillValidationResult;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.flow.FlowDefinitionValidator;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.RouteRulesService;
import com.sn68.agent.dataagent.routing.RouteTextNormalizer;
import com.sn68.agent.dataagent.service.knowledge.SkillKnowledgeCascadeService;
import com.sn68.agent.dataagent.service.knowledge.SkillKnowledgeCascadeService.DeletedSkillKnowledge;
import com.sn68.agent.dataagent.service.routing.RouteArtifactService;
import com.sn68.agent.dataagent.service.skill.SkillManagementTenantService;
import com.sn68.agent.dataagent.service.skill.SkillVersionResourceSnapshotService;
import com.sn68.agent.dataagent.skill.execution.DeterministicRuntimePolicy;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SkillCatalogServiceImplTest {

	@BeforeAll
	static void initTableInfo() {
		// publish() 走 Wraps.<DataAgentSkill>lbU()，lambda 列名解析依赖 MP 的 TableInfo 缓存。
		// 纯单测没有 Spring 容器做 mapper 扫描，需按 DataAgentRouteProfileMapperTest 的同款写法手动注册。
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				DataAgentSkill.class);
	}

	private final DataAgentSkillMapper skillMapper = mock(DataAgentSkillMapper.class);

	private final DataAgentSkillVersionMapper versionMapper = mock(DataAgentSkillVersionMapper.class);

	private final DataAgentSkillToolRefMapper toolRefMapper = mock(DataAgentSkillToolRefMapper.class);

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	private final DataAgentSkillBindingMapper bindingMapper = mock(DataAgentSkillBindingMapper.class);

	private final SkillManagementTenantService skillManagementTenantService =
			mock(SkillManagementTenantService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final FlowDefinitionValidator flowDefinitionValidator = mock(FlowDefinitionValidator.class);

	private final SkillVersionResourceSnapshotService resourceSnapshotService =
			mock(SkillVersionResourceSnapshotService.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final RouteRulesService routeRulesService =
			new RouteRulesService(objectMapper, new RouteTextNormalizer());

	private final RouteArtifactService routeArtifactService = mock(RouteArtifactService.class);

	private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

	private final SkillKnowledgeCascadeService skillKnowledgeCascadeService =
			mock(SkillKnowledgeCascadeService.class);

	private final SkillCatalogServiceImpl service = new SkillCatalogServiceImpl(skillMapper, versionMapper,
			toolRefMapper, resourceVersionMapper, bindingMapper, routeRulesService, skillManagementTenantService,
			resourceSnapshotService, flowDefinitionValidator, authenticationContext, objectMapper, routeArtifactService,
			transactionTemplate, new DataAgentProperties(), skillKnowledgeCascadeService);

	@BeforeEach
	void setUp() {
		when(skillManagementTenantService.effectiveTenantId()).thenReturn("tenant-1");
		when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		});
	}

	@Test
	void tenantAdministratorCannotManageOtherTenantSkill() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.TENANT_ADMIN);
		when(authenticationContext.funcPermissionList()).thenReturn(List.of("ai-agent:skill:modify"));
		DataAgentSkill otherTenantSkill = platformSkill();
		otherTenantSkill.setTenantId("tenant-2");
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(otherTenantSkill);

		assertThrows(CheckedException.class, () -> service.detail("platform-skill"));
	}

	@Test
	void draftDetailDoesNotExposePersistedResourceSnapshots() {
		DataAgentSkill skill = platformSkill();
		DataAgentSkillVersion draft = draft();
		draft.setDatasourceConfig("{\"datasourceId\":1001}");
		draft.setSemanticConfig("{\"semanticModelIds\":[101]}");
		stubDetail(skill, draft);

		SkillDetailResp detail = service.detail("platform-skill");

		assertEquals(Map.of(), detail.version().datasourceConfig());
		assertEquals(Map.of(), detail.version().semanticConfig());
	}

	@Test
	void publishedDetailExposesReadOnlyResourceSnapshots() {
		DataAgentSkill skill = platformSkill();
		skill.setStatus("PUBLISHED");
		skill.setLatestDraftVersionId(null);
		skill.setPublishedVersionId(10L);
		DataAgentSkillVersion published = draft();
		published.setStatus("PUBLISHED");
		published.setDatasourceConfig("{\"datasourceId\":1001}");
		published.setSemanticConfig("{\"semanticModelIds\":[101]}");
		stubDetail(skill, published);

		SkillDetailResp detail = service.detail("platform-skill");

		assertEquals(1001, detail.version().datasourceConfig().get("datasourceId"));
		assertEquals(List.of(101), detail.version().semanticConfig().get("semanticModelIds"));
	}

	@Test
	void detailExpandsLegacyRouteRulesToTheCompleteResponseContract() {
		DataAgentSkill skill = platformSkill();
		DataAgentSkillVersion draft = draft();
		draft.setRouteRules("{\"exact\":[\"create\"],\"phrases\":[],\"aliases\":[],"
				+ "\"positiveExamples\":[],\"negativeExamples\":[],\"hardExcludes\":[]}");
		stubDetail(skill, draft);

		SkillDetailResp detail = service.detail("platform-skill");

		assertEquals(List.of("create"), detail.version().routeRules().exact());
		assertEquals(List.of(), detail.version().routeRules().positivePatterns());
		assertEquals(List.of(), detail.version().routeRules().hardExcludePatterns());
		assertFalse(detail.version().routeRules().allowFlowAutoSelect());
	}

	@Test
	void toolEditorContextKeepsRetiredSelectedVersionVisible() {
		DataAgentSkill skill = platformSkill();
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of(DataAgentSkillToolRef.builder()
			.skillVersionId(10L).resourceVersionId(99L).resourceKey("demo.echo.query").build()));
		Map<String, Object> row = new HashMap<>();
		row.put("resource_version_id", 99L);
		row.put("resource_key", "demo.echo.query");
		row.put("resource_name", "查询工具");
		row.put("version_no", 3);
		row.put("resource_version_status", "RETIRED");
		row.put("resource_version_deleted", false);
		row.put("access_mode", "READ");
		row.put("exposure_mode", "MODEL");
		row.put("resource_enabled", true);
		row.put("resource_status", "enabled");
		row.put("resource_deleted", false);
		when(resourceVersionMapper.findSkillToolEditorOptions(eq("tenant-1"), eq(false), eq("REACT"), eq(Set.of(99L))))
			.thenReturn(List.of(row));

		SkillToolEditorContextResp context = service.toolEditorContext("platform-skill", null, "REACT");

		assertEquals(1, context.refs().size());
		assertEquals(1, context.options().size());
		assertFalse(context.options().get(0).selectable());
		assertEquals("工具版本已停用或已删除", context.options().get(0).unavailableReason());
	}

	@Test
	void createRejectsDifferentEmptyAndNonEmptyVariableSchemas() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("new-flow", null)).thenReturn(null);
		when(skillMapper.insert(any(DataAgentSkill.class))).thenAnswer(invocation -> {
			invocation.<DataAgentSkill>getArgument(0).setId(1L);
			return 1;
		});
		when(versionMapper.nextVersionNo(1L)).thenReturn(1);
		SkillSaveReq request = new SkillSaveReq("new-flow", "New Flow", null, null, "PLATFORM", "FLOW",
				0, "# Skill", Map.of(), Map.of(), Map.of(), Map.of("variablesSchema", Map.of()),
				Map.of("type", "object"), null);

		assertThrows(CheckedException.class, () -> service.create(request));
		verify(versionMapper, never()).insert(any(DataAgentSkillVersion.class));
	}

	@Test
	void createNormalizesLegacyPlatformScopeToTenantOne() {
		when(skillMapper.findVisibleByCode("legacy-scope", "tenant-1")).thenReturn(null);
		when(skillMapper.insert(any(DataAgentSkill.class))).thenAnswer(invocation -> {
			invocation.<DataAgentSkill>getArgument(0).setId(1L);
			return 1;
		});
		when(versionMapper.nextVersionNo(1L)).thenReturn(1);
		when(versionMapper.findBySkillId(1L)).thenReturn(List.of());
		SkillSaveReq request = new SkillSaveReq("legacy-scope", "Legacy scope", null, null, "PLATFORM",
				"KNOWLEDGE", 0, "# Skill", Map.of(), Map.of(), Map.of(), null, Map.of(), null, null, null,
				"QA", Map.of(), Map.of(), Map.of(), Map.of());

		service.create(request);

		ArgumentCaptor<DataAgentSkill> skillCaptor = ArgumentCaptor.forClass(DataAgentSkill.class);
		ArgumentCaptor<DataAgentSkillVersion> versionCaptor = ArgumentCaptor.forClass(DataAgentSkillVersion.class);
		verify(skillMapper).insert(skillCaptor.capture());
		verify(versionMapper).insert(versionCaptor.capture());
		assertEquals("TENANT", skillCaptor.getValue().getScope());
		assertEquals("tenant-1", skillCaptor.getValue().getTenantId());
		assertEquals("tenant-1", versionCaptor.getValue().getTenantId());
	}

	/**
	 * 锁定 data_agent_skill 与 data_agent_skill_version 之间循环外键要求的写入顺序。
	 *
	 * <p>复合外键 fk_data_agent_skill_latest_draft 是
	 * {@code (latest_draft_version_id, id) -> data_agent_skill_version(id, skill_id)} 且非 DEFERRABLE，
	 * 因此 create 必须满足三点，否则 PostgreSQL 立即报外键冲突：
	 * <ol>
	 * <li>skill 首次 insert 时 latest_draft_version_id 必须为 null，靠 MATCH SIMPLE 跳过校验；</li>
	 * <li>版本行必须先落库，且 skill_id 指向本 skill（外键第二列）；</li>
	 * <li>回填 latest_draft_version_id 的 update 必须排在版本 insert 之后。</li>
	 * </ol>
	 */
	@Test
	void createInsertsDraftVersionBeforeBackfillingLatestDraftId() {
		when(skillMapper.findVisibleByCode("order-guard", "tenant-1")).thenReturn(null);
		AtomicReference<Long> draftIdAtSkillInsert = new AtomicReference<>();
		when(skillMapper.insert(any(DataAgentSkill.class))).thenAnswer(invocation -> {
			DataAgentSkill inserted = invocation.getArgument(0);
			draftIdAtSkillInsert.set(inserted.getLatestDraftVersionId());
			inserted.setId(1L);
			return 1;
		});
		when(versionMapper.insert(any(DataAgentSkillVersion.class))).thenAnswer(invocation -> {
			invocation.<DataAgentSkillVersion>getArgument(0).setId(100L);
			return 1;
		});
		when(versionMapper.nextVersionNo(1L)).thenReturn(1);
		when(versionMapper.findBySkillId(1L)).thenReturn(List.of());
		SkillSaveReq request = new SkillSaveReq("order-guard", "Order guard", null, null, "TENANT",
				"KNOWLEDGE", 0, "# Skill", Map.of(), Map.of(), Map.of(), null, Map.of(), null, null, null,
				"QA", Map.of(), Map.of(), Map.of(), Map.of());

		service.create(request);

		InOrder inOrder = inOrder(skillMapper, versionMapper);
		inOrder.verify(skillMapper).insert(any(DataAgentSkill.class));
		inOrder.verify(versionMapper).insert(any(DataAgentSkillVersion.class));
		inOrder.verify(skillMapper).updateById(any(DataAgentSkill.class));

		assertNull(draftIdAtSkillInsert.get());
		ArgumentCaptor<DataAgentSkillVersion> versionCaptor = ArgumentCaptor.forClass(DataAgentSkillVersion.class);
		verify(versionMapper).insert(versionCaptor.capture());
		assertEquals(1L, versionCaptor.getValue().getSkillId());
		ArgumentCaptor<DataAgentSkill> updateCaptor = ArgumentCaptor.forClass(DataAgentSkill.class);
		verify(skillMapper).updateById(updateCaptor.capture());
		assertEquals(100L, updateCaptor.getValue().getLatestDraftVersionId());
	}

	@Test
	void omittedToolRefsKeepExistingReferences() {
		stubModifySkill();

		service.modify("platform-skill", modifyRequest(null));

		verify(toolRefMapper, never()).deleteBySkillVersionId(10L);
	}

	@Test
	void modifyingNeverPublishedSkillKeepsDraftStatus() {
		stubModifySkill();

		service.modify("platform-skill", modifyRequest(null));

		ArgumentCaptor<DataAgentSkill> captor = ArgumentCaptor.forClass(DataAgentSkill.class);
		verify(skillMapper).updateById(captor.capture());
		assertEquals("DRAFT", captor.getValue().getStatus());
	}

	@Test
	void emptyToolRefsExplicitlyClearReferences() {
		stubModifySkill();
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of());

		service.modify("platform-skill", modifyRequest(List.of()));

		verify(toolRefMapper).deleteBySkillVersionId(10L);
	}

	@Test
	void omittedFlowRuntimeAndPolicyKeepExistingValues() {
		DataAgentSkillVersion draft = stubModifySkill();
		draft.setFlowRuntimeConfig("{\"timeoutMs\":30000}");
		draft.setFlowPolicyConfig("{\"interruptPolicy\":\"ASK\"}");

		service.modify("platform-skill", modifyRequest(null));

		assertEquals("{\"timeoutMs\":30000}", draft.getFlowRuntimeConfig());
		assertEquals("{\"interruptPolicy\":\"ASK\"}", draft.getFlowPolicyConfig());
	}

	@Test
	void emptyFlowRuntimeAndPolicyExplicitlyClearValues() {
		DataAgentSkillVersion draft = stubModifySkill();
		draft.setFlowRuntimeConfig("{\"timeoutMs\":30000}");
		draft.setFlowPolicyConfig("{\"interruptPolicy\":\"ASK\"}");

		service.modify("platform-skill", modifyRequestWithFlow(Map.of(), Map.of()));

		assertEquals("{}", draft.getFlowRuntimeConfig());
		assertEquals("{}", draft.getFlowPolicyConfig());
	}

	@Test
	void omittedRuntimeConfigKeepsExistingValue() {
		DataAgentSkillVersion draft = stubModifySkill();
		draft.setRuntimeConfig("{\"maxRows\":120}");

		service.modify("platform-skill", modifyRequest(null));

		assertEquals("{\"maxRows\":120}", draft.getRuntimeConfig());
	}

	@Test
	void emptyRuntimeConfigExplicitlyClearsValue() {
		DataAgentSkillVersion draft = stubModifySkill();
		draft.setRuntimeConfig("{\"maxRows\":120}");

		service.modify("platform-skill", modifyRequestWithRuntimeConfig(Map.of()));

		assertEquals("{}", draft.getRuntimeConfig());
	}

	@Test
	void firstDraftFromPublishedSkillKeepsRuntimeConfigAndPublishedStatus() {
		DataAgentSkill skill = platformSkill();
		skill.setStatus("PUBLISHED");
		skill.setPublishedVersionId(20L);
		skill.setLatestDraftVersionId(null);
		DataAgentSkillVersion published = draft();
		published.setId(20L);
		published.setStatus("PUBLISHED");
		published.setRuntimeConfig("{\"maxRows\":120,\"deterministic\":{\"maxAttempts\":1}}");
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(versionMapper.findLatestDraft(1L)).thenReturn(null);
		when(versionMapper.selectById(20L)).thenReturn(published);
		when(versionMapper.nextVersionNo(1L)).thenReturn(2);
		when(versionMapper.insert(any(DataAgentSkillVersion.class))).thenAnswer(invocation -> {
			invocation.<DataAgentSkillVersion>getArgument(0).setId(30L);
			return 1;
		});
		when(toolRefMapper.findBySkillVersionId(20L)).thenReturn(List.of());

		service.modify("platform-skill", modifyRequest(null));

		ArgumentCaptor<DataAgentSkillVersion> captor = ArgumentCaptor.forClass(DataAgentSkillVersion.class);
		verify(versionMapper).insert(captor.capture());
		assertEquals("{\"maxRows\":120,\"deterministic\":{\"maxAttempts\":1}}",
				captor.getValue().getRuntimeConfig());
		assertEquals("PUBLISHED", skill.getStatus());
		assertEquals(20L, skill.getPublishedVersionId());
		assertEquals(30L, skill.getLatestDraftVersionId());
	}

	@Test
	void firstDraftFromPublishedSkillKeepsAnalysisConfigWhenRequestOmitsIt() {
		DataAgentSkill skill = platformSkill();
		skill.setStatus("PUBLISHED");
		skill.setPublishedVersionId(20L);
		skill.setLatestDraftVersionId(null);
		DataAgentSkillVersion published = draft();
		published.setId(20L);
		published.setStatus("PUBLISHED");
		published.setAnalysisConfig("{\"sources\":[{\"id\":\"f1\",\"type\":\"FILE_TABLE\",\"turnFile\":true}]}");
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(versionMapper.findLatestDraft(1L)).thenReturn(null);
		when(versionMapper.selectById(20L)).thenReturn(published);
		when(versionMapper.nextVersionNo(1L)).thenReturn(2);
		when(versionMapper.insert(any(DataAgentSkillVersion.class))).thenAnswer(invocation -> {
			invocation.<DataAgentSkillVersion>getArgument(0).setId(30L);
			return 1;
		});
		when(toolRefMapper.findBySkillVersionId(20L)).thenReturn(List.of());

		service.modify("platform-skill", modifyRequest(null));

		ArgumentCaptor<DataAgentSkillVersion> captor = ArgumentCaptor.forClass(DataAgentSkillVersion.class);
		verify(versionMapper).insert(captor.capture());
		assertEquals("{\"sources\":[{\"id\":\"f1\",\"type\":\"FILE_TABLE\",\"turnFile\":true}]}",
				captor.getValue().getAnalysisConfig());
	}

	@Test
	void previewExposesEffectiveDeterministicPolicy() {
		DataAgentSkill skill = platformSkill();
		skill.setExecutionMode("DETERMINISTIC");
		DataAgentSkillVersion draft = draft();
		draft.setExecutionMode("DETERMINISTIC");
		draft.setRuntimeConfig("""
				{"maxRows":200,"deterministic":{"totalTimeoutMs":12000,"plannerTimeoutMs":5000,
				"sqlTimeoutMs":4000,"maxOutputTokens":1024,"maxAttempts":1}}
				""");
		stubDeterministicDraft(skill, draft);

		SkillPreviewResp preview = service.preview("platform-skill", "draft");

		assertEquals(12000L, preview.effectiveDeterministicPolicy().get("totalTimeoutMs"));
		assertEquals(1024, preview.effectiveDeterministicPolicy().get("maxOutputTokens"));
		assertEquals(1000L, preview.effectiveDeterministicPolicy().get("finishBufferMs"));
		assertEquals(200, ((Map<?, ?>) preview.manifest().get("runtimeConfig")).get("maxRows"));
		assertEquals(List.of(), preview.routeRules().positivePatterns());
		assertFalse(preview.routeRules().allowFlowAutoSelect());
		assertEquals(List.of(), ((Map<?, ?>) preview.manifest().get("routeRules")).get("hardExcludePatterns"));
	}

	@Test
	void publishRejectsInvalidDeterministicPolicy() {
		DataAgentSkill skill = platformSkill();
		skill.setExecutionMode("DETERMINISTIC");
		DataAgentSkillVersion draft = draft();
		draft.setExecutionMode("DETERMINISTIC");
		draft.setRuntimeConfig("{\"deterministic\":{\"unexpected\":1}}");
		stubDeterministicDraft(skill, draft);

		SkillValidationResult validation = service.validate("platform-skill");

		assertFalse(validation.valid());
		assertTrue(validation.errors().stream().anyMatch(error -> error.contains("unknown field: unexpected")));
		assertThrows(CheckedException.class, () -> service.publish("platform-skill"));
		verify(routeArtifactService, never()).prepareSkillVersion(any(), any());
	}

	@Test
	void previewAndExportUseSelectedVersionMetadataInsteadOfEditableCatalogProjection() {
		DataAgentSkill skill = platformSkill();
		skill.setStatus("PUBLISHED");
		skill.setPublishedVersionId(10L);
		skill.setLatestDraftVersionId(null);
		skill.setSkillName("最新目录名称");
		skill.setDescription("最新目录说明");
		skill.setCategory("最新分类");
		skill.setDisplayOrder(99);
		DataAgentSkillVersion published = draft();
		published.setStatus("PUBLISHED");
		published.setSkillName("固定 V1 名称");
		published.setDescription("固定 V1 说明");
		published.setCategory("固定分类");
		published.setDisplayOrder(7);
		stubDetail(skill, published);
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of());

		SkillPreviewResp preview = service.preview("platform-skill", "published");
		Map<String, Object> bundle = service.exportBundle("platform-skill");
		Map<?, ?> manifest = (Map<?, ?>) bundle.get("manifest.yaml");

		assertEquals("固定 V1 名称", preview.skillName());
		assertEquals("固定 V1 名称", preview.manifest().get("name"));
		assertEquals("固定 V1 说明", preview.manifest().get("description"));
		assertEquals("固定 V1 名称", manifest.get("name"));
		assertEquals("固定 V1 说明", manifest.get("description"));
		assertEquals("固定分类", manifest.get("category"));
		assertEquals(7, manifest.get("displayOrder"));
	}

	@Test
	void publishDoesNotChangePinnedAgentBindings() {
		DataAgentSkill skill = platformSkill();
		DataAgentSkillVersion draft = draft();
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(skillMapper.selectById(1L)).thenReturn(skill);
		when(versionMapper.findLatestDraft(1L)).thenReturn(draft);
		when(versionMapper.selectById(10L)).thenReturn(draft);
		when(versionMapper.findBySkillId(1L)).thenReturn(List.of(draft));
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of());
		when(resourceSnapshotService.capture(any(), any(), any(), any())).thenReturn(
				new SkillVersionResourceSnapshotService.ResourceSnapshot(Map.of(), Map.of(), Map.of(), List.of()));

		SkillDetailResp result = service.publish("platform-skill");

		assertEquals(10L, result.publishedVersionId());
		assertNull(result.latestDraftVersionId());
		assertEquals(List.of(), result.version().routeRules().positivePatterns());
		assertFalse(result.version().routeRules().allowFlowAutoSelect());
		verify(skillMapper).update(isNull(), any());
		verify(skillMapper, never()).updateById(any(DataAgentSkill.class));
		org.mockito.Mockito.verifyNoInteractions(bindingMapper);
	}

	@Test
	void exportUsesPortableToolVersionNumber() {
		DataAgentSkill skill = platformSkill();
		DataAgentSkillVersion draft = draft();
		draft.setRuntimeConfig("{\"maxRows\":120,\"deterministic\":{\"maxAttempts\":1}}");
		draft.setRouteRules("{\"exact\":[],\"phrases\":[],\"aliases\":[],\"positiveExamples\":[],"
				+ "\"positivePatterns\":[\"give*order\"],\"negativeExamples\":[],\"hardExcludes\":[],"
				+ "\"hardExcludePatterns\":[\"how*order\"],\"allowFlowAutoSelect\":true}");
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(versionMapper.selectById(10L)).thenReturn(draft);
		when(versionMapper.findBySkillId(1L)).thenReturn(List.of(draft));
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of(DataAgentSkillToolRef.builder()
			.skillVersionId(10L).resourceVersionId(99L).resourceKey("demo.echo.query").status("enabled").build()));
		when(resourceVersionMapper.selectList(any())).thenReturn(List.of(toolVersion(99L, 3)));

		Map<String, Object> bundle = service.exportBundle("platform-skill");
		Map<?, ?> exportedRef = (Map<?, ?>) ((List<?>) bundle.get("tool-refs.json")).get(0);

		assertEquals("demo.echo.query", exportedRef.get("resourceKey"));
		assertEquals(3, exportedRef.get("resourceVersionNo"));
		assertFalse(exportedRef.containsKey("resourceVersionId"));
		assertEquals(Map.of(), ((Map<?, ?>) bundle.get("manifest.yaml")).get("flowRuntimeConfig"));
		assertEquals(Map.of(), ((Map<?, ?>) bundle.get("manifest.yaml")).get("flowPolicyConfig"));
		assertEquals(Map.of("maxRows", 120, "deterministic", Map.of("maxAttempts", 1)),
				((Map<?, ?>) bundle.get("manifest.yaml")).get("runtimeConfig"));
		assertEquals("QUERY", ((Map<?, ?>) bundle.get("manifest.yaml")).get("skillKind"));
		assertEquals(Map.of("datasource", Map.of("required", true)), bundle.get("resource-requirement.json"));
		assertEquals(Map.of("type", "object"), bundle.get("input-schema.json"));
		assertEquals(Map.of("type", "array"), bundle.get("output-schema.json"));
		Map<?, ?> routeRules = (Map<?, ?>) ((Map<?, ?>) bundle.get("manifest.yaml")).get("routeRules");
		assertEquals(List.of("give*order"), routeRules.get("positivePatterns"));
		assertEquals(List.of("how*order"), routeRules.get("hardExcludePatterns"));
		assertEquals(true, routeRules.get("allowFlowAutoSelect"));
		assertFalse(bundle.toString().contains("datasourceId"));
	}

	@Test
	void importResolvesTargetPublishedToolVersion() {
		AtomicReference<DataAgentSkill> insertedSkill = new AtomicReference<>();
		AtomicReference<DataAgentSkillVersion> insertedVersion = new AtomicReference<>();
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("imported-skill", null)).thenReturn(null);
		when(skillMapper.findVisibleByCode("imported-skill", "tenant-1"))
			.thenAnswer(invocation -> insertedSkill.get());
		when(skillMapper.insert(any(DataAgentSkill.class))).thenAnswer(invocation -> {
			DataAgentSkill skill = invocation.getArgument(0);
			skill.setId(1L);
			insertedSkill.set(skill);
			return 1;
		});
		when(versionMapper.nextVersionNo(1L)).thenReturn(1);
		when(versionMapper.insert(any(DataAgentSkillVersion.class))).thenAnswer(invocation -> {
			DataAgentSkillVersion version = invocation.getArgument(0);
			version.setId(10L);
			insertedVersion.set(version);
			return 1;
		});
		when(versionMapper.selectById(10L)).thenAnswer(invocation -> insertedVersion.get());
		when(versionMapper.findBySkillId(1L)).thenAnswer(invocation -> List.of(insertedVersion.get()));
		when(resourceVersionMapper.selectList(any())).thenReturn(List.of(toolVersion(20L, 3)));
		Map<String, Object> routeRules = Map.of("positivePatterns", List.of("give*order"),
				"hardExcludePatterns", List.of("how*order"), "allowFlowAutoSelect", true);
		Map<String, Object> manifest = Map.of("schemaVersion", "skill-package/v1", "skillCode", "imported-skill",
				"name", "Imported Skill", "scope", "PLATFORM", "skillKind", "ACTION", "executionMode", "FLOW",
				"routeRules", routeRules);
		Map<String, Object> bundle = Map.of("manifest.yaml", manifest, "SKILL.md", "# Imported",
				"tool-refs.json", List.of(Map.of("resourceKey", "demo.echo.query", "resourceVersionNo", 3)));

		SkillDetailResp imported = service.importBundle(new SkillImportReq(bundle));

		assertEquals("{}", insertedVersion.get().getFlowRuntimeConfig());
		assertEquals("{}", insertedVersion.get().getFlowPolicyConfig());
		assertEquals("{}", insertedVersion.get().getRuntimeConfig());
		var persistedRules = routeRulesService.parse(insertedVersion.get().getRouteRules());
		assertEquals(List.of("give*order"), persistedRules.positivePatterns());
		assertEquals(List.of("how*order"), persistedRules.hardExcludePatterns());
		assertTrue(persistedRules.allowFlowAutoSelect());
		assertEquals(List.of("give*order"), imported.version().routeRules().positivePatterns());
		assertEquals(List.of("how*order"), imported.version().routeRules().hardExcludePatterns());
		assertTrue(imported.version().routeRules().allowFlowAutoSelect());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<java.util.Collection<DataAgentSkillToolRef>> captor = ArgumentCaptor
			.forClass(java.util.Collection.class);
		org.mockito.Mockito.verify(toolRefMapper).insertBatch(captor.capture());
		assertEquals(1, captor.getValue().size());
		DataAgentSkillToolRef persistedRef = captor.getValue().iterator().next();
		assertEquals(10L, persistedRef.getSkillVersionId());
		assertEquals(20L, persistedRef.getResourceVersionId());
	}

	@Test
	void importRejectsNonObjectRouteRules() {
		Map<String, Object> manifest = Map.of("schemaVersion", "skill-package/v1", "skillCode", "imported-skill",
				"name", "Imported Skill", "scope", "TENANT", "skillKind", "QUERY", "executionMode", "REACT",
				"routeRules", List.of("invalid"));

		assertThrows(CheckedException.class,
				() -> service.importBundle(new SkillImportReq(Map.of("manifest.yaml", manifest))));
		verify(skillMapper, never()).insert(any(DataAgentSkill.class));
	}

	@Test
	void exportImportRoundTripPreservesRuntimeConfig() throws Exception {
		DataAgentSkill sourceSkill = platformSkill();
		sourceSkill.setExecutionMode("DETERMINISTIC");
		DataAgentSkillVersion sourceVersion = draft();
		sourceVersion.setExecutionMode("DETERMINISTIC");
		Map<String, Object> runtimeConfig = Map.of("maxRows", 120, "deterministic", Map.of("maxAttempts", 1));
		sourceVersion.setRuntimeConfig(objectMapper.writeValueAsString(runtimeConfig));
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(sourceSkill);
		when(versionMapper.selectById(10L)).thenReturn(sourceVersion);
		when(versionMapper.findBySkillId(1L)).thenReturn(List.of(sourceVersion));
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of());
		Map<String, Object> exportedBundle = service.exportBundle("platform-skill");
		Map<String, Object> importedManifest = new HashMap<>();
		((Map<?, ?>) exportedBundle.get("manifest.yaml"))
			.forEach((key, value) -> importedManifest.put(String.valueOf(key), value));
		importedManifest.put("skillCode", "imported-deterministic");
		Map<String, Object> importedBundle = new HashMap<>(exportedBundle);
		importedBundle.put("manifest.yaml", importedManifest);

		AtomicReference<DataAgentSkill> insertedSkill = new AtomicReference<>();
		AtomicReference<DataAgentSkillVersion> insertedVersion = new AtomicReference<>();
		when(skillMapper.findVisibleByCode("imported-deterministic", "tenant-1"))
			.thenAnswer(invocation -> insertedSkill.get());
		when(skillMapper.insert(any(DataAgentSkill.class))).thenAnswer(invocation -> {
			DataAgentSkill skill = invocation.getArgument(0);
			skill.setId(2L);
			insertedSkill.set(skill);
			return 1;
		});
		when(versionMapper.nextVersionNo(2L)).thenReturn(1);
		when(versionMapper.insert(any(DataAgentSkillVersion.class))).thenAnswer(invocation -> {
			DataAgentSkillVersion version = invocation.getArgument(0);
			version.setId(20L);
			insertedVersion.set(version);
			return 1;
		});
		when(versionMapper.selectById(20L)).thenAnswer(invocation -> insertedVersion.get());
		when(versionMapper.findBySkillId(2L)).thenAnswer(invocation -> List.of(insertedVersion.get()));

		SkillDetailResp imported = service.importBundle(new SkillImportReq(importedBundle));

		assertEquals(runtimeConfig, objectMapper.readValue(insertedVersion.get().getRuntimeConfig(), Map.class));
		assertEquals(runtimeConfig, imported.version().runtimeConfig());
	}

	@Test
	void importLegacyDeterministicBundleWithoutRuntimeConfigUsesPlatformDefaults() throws Exception {
		AtomicReference<DataAgentSkill> insertedSkill = new AtomicReference<>();
		AtomicReference<DataAgentSkillVersion> insertedVersion = new AtomicReference<>();
		when(skillMapper.findVisibleByCode("legacy-deterministic", "tenant-1"))
			.thenAnswer(invocation -> insertedSkill.get());
		when(skillMapper.insert(any(DataAgentSkill.class))).thenAnswer(invocation -> {
			DataAgentSkill skill = invocation.getArgument(0);
			skill.setId(1L);
			insertedSkill.set(skill);
			return 1;
		});
		when(versionMapper.nextVersionNo(1L)).thenReturn(1);
		when(versionMapper.insert(any(DataAgentSkillVersion.class))).thenAnswer(invocation -> {
			DataAgentSkillVersion version = invocation.getArgument(0);
			version.setId(10L);
			insertedVersion.set(version);
			return 1;
		});
		when(versionMapper.selectById(10L)).thenAnswer(invocation -> insertedVersion.get());
		when(versionMapper.findBySkillId(1L)).thenAnswer(invocation -> List.of(insertedVersion.get()));
		Map<String, Object> manifest = Map.of("schemaVersion", "skill-package/v1", "skillCode", "legacy-deterministic",
				"name", "Legacy Deterministic", "scope", "TENANT", "skillKind", "QUERY",
				"executionMode", "DETERMINISTIC");

		SkillDetailResp imported = service
			.importBundle(new SkillImportReq(Map.of("manifest.yaml", manifest, "SKILL.md", "# Legacy")));

		Map<String, Object> persistedRuntimeConfig = objectMapper
			.readValue(insertedVersion.get().getRuntimeConfig(), Map.class);
		assertEquals(Map.of(), persistedRuntimeConfig);
		assertEquals(Map.of(), imported.version().runtimeConfig());
		assertEquals(new DeterministicRuntimePolicy.Policy(20000L, 8000L, 6000L, 2048, 2, 1000L),
				DeterministicRuntimePolicy.resolve(new DataAgentProperties.Runtime(), persistedRuntimeConfig));
	}

	@Test
	void validationAllowsNonIdempotentWriteWithoutResultQuery() {
		stubFlowValidation(false);

		SkillValidationResult result = service.validate("platform-skill");

		assertEquals(true, result.valid());
	}

	@Test
	void validationRequiresResultQueryForIdempotentWrite() {
		stubFlowValidation(true);

		SkillValidationResult result = service.validate("platform-skill");

		assertFalse(result.valid());
		assertEquals(true, result.errors().stream().anyMatch(error -> error.contains("must define resultQuery")));
	}

	@Test
	void deleteUsesLogicalDeleteWhenSkillIsUnbound() {
		DataAgentSkill skill = stubDeletableSkill();
		when(bindingMapper.selectList(any())).thenReturn(List.of());
		when(skillMapper.deleteById(1L)).thenReturn(1);
		DeletedSkillKnowledge deleted = new DeletedSkillKnowledge(List.of(11L), List.of(21L));
		when(skillKnowledgeCascadeService.deleteKnowledgeRows(1L)).thenReturn(deleted);

		service.delete("platform-skill");

		verify(skillMapper).deleteById(skill.getId());
		verify(skillMapper, never()).updateById(any(DataAgentSkill.class));
		// 知识行必须与 Skill 主行同批落库，向量清理排在提交之后：反过来会在事务回滚时留下
		// 「Skill 还在、知识却检索不到」的静默降级
		InOrder inOrder = inOrder(skillKnowledgeCascadeService, skillMapper);
		inOrder.verify(skillKnowledgeCascadeService).deleteKnowledgeRows(1L);
		inOrder.verify(skillMapper).deleteById(1L);
		inOrder.verify(skillKnowledgeCascadeService).purgeKnowledgeVectors(1L, deleted);
	}

	/**
	 * 已发布版本会固定住自己 Skill 的知识行，逐条删除入口据此拒绝删除。整个 Skill 删除时这层保护已无对象
	 * （删除本就要求无 Agent 绑定，主行墓碑化后发布版本也解析不出来），若在这里也拒绝，被固定的知识既删不掉
	 * 又没有版本退役入口，Skill 将永远删不掉。
	 */
	@Test
	void deleteProceedsForPublishedSkillWhoseKnowledgeIsPinnedByAPublishedVersion() {
		DataAgentSkill skill = stubDeletableSkill();
		skill.setStatus("PUBLISHED");
		skill.setLatestDraftVersionId(null);
		skill.setPublishedVersionId(10L);
		when(bindingMapper.selectList(any())).thenReturn(List.of());
		when(skillMapper.deleteById(1L)).thenReturn(1);
		when(skillKnowledgeCascadeService.deleteKnowledgeRows(1L))
			.thenReturn(new DeletedSkillKnowledge(List.of(11L), List.of()));

		service.delete("platform-skill");

		verify(skillMapper).deleteById(1L);
		verify(skillKnowledgeCascadeService).purgeKnowledgeVectors(eq(1L), any());
	}

	@Test
	void deleteRejectsBoundSkill() {
		stubDeletableSkill();
		when(bindingMapper.selectList(any())).thenReturn(List.of(mock(DataAgentSkillBinding.class)));

		assertThrows(CheckedException.class, () -> service.delete("platform-skill"));

		verify(skillMapper, never()).deleteById(any());
		verify(skillMapper, never()).updateById(any(DataAgentSkill.class));
		org.mockito.Mockito.verifyNoInteractions(skillKnowledgeCascadeService);
	}

	@Test
	void deleteReportsNotFoundWhenLogicalDeleteAffectsNoRows() {
		stubDeletableSkill();
		when(bindingMapper.selectList(any())).thenReturn(List.of());
		when(skillMapper.deleteById(1L)).thenReturn(0);
		when(skillKnowledgeCascadeService.deleteKnowledgeRows(1L)).thenReturn(DeletedSkillKnowledge.none());

		assertThrows(CheckedException.class, () -> service.delete("platform-skill"));

		verify(skillMapper).deleteById(1L);
		// 主行没删掉时整个短事务回滚，向量绝不能先动
		verify(skillKnowledgeCascadeService, never()).purgeKnowledgeVectors(any(), any());
	}

	@Test
	void deleteSurfacesVectorCleanupFailure() {
		stubDeletableSkill();
		when(bindingMapper.selectList(any())).thenReturn(List.of());
		when(skillMapper.deleteById(1L)).thenReturn(1);
		when(skillKnowledgeCascadeService.deleteKnowledgeRows(1L))
			.thenReturn(new DeletedSkillKnowledge(List.of(11L), List.of()));
		org.mockito.Mockito.doThrow(CheckedException.fail("向量清理失败"))
			.when(skillKnowledgeCascadeService)
			.purgeKnowledgeVectors(eq(1L), any());

		assertThrows(CheckedException.class, () -> service.delete("platform-skill"));

		verify(skillMapper).deleteById(1L);
	}

	private DataAgentSkill platformSkill() {
		return DataAgentSkill.builder().id(1L).skillCode("platform-skill").skillName("Platform Skill")
			.tenantId("tenant-1").scope("TENANT").skillKind("QUERY").executionMode("REACT").status("DRAFT")
			.latestDraftVersionId(10L)
			.displayOrder(0).build();
	}

	private DataAgentSkill stubDeletableSkill() {
		DataAgentSkill skill = platformSkill();
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		return skill;
	}

	private void stubDetail(DataAgentSkill skill, DataAgentSkillVersion version) {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(versionMapper.selectById(version.getId())).thenReturn(version);
		when(versionMapper.findBySkillId(skill.getId())).thenReturn(List.of(version));
	}

	private DataAgentSkillVersion draft() {
		return DataAgentSkillVersion.builder().id(10L).skillId(1L).versionNo(1).status("DRAFT")
			.skillName("Platform Skill").displayOrder(0)
			.skillKind("QUERY").executionMode("REACT")
			.skillMarkdown("# Skill").routeRules("{}").knowledgeConfig("{}").reactConfig("{}")
			.variablesSchema("{}").resourceRequirement("{\"datasource\":{\"required\":true}}")
			.inputSchema("{\"type\":\"object\"}").outputSchema("{\"type\":\"array\"}").build();
	}

	private AgentExecutionResourceVersion toolVersion(Long id, Integer versionNo) {
		return AgentExecutionResourceVersion.builder().id(id).resourceKey("demo.echo.query").versionNo(versionNo)
			.status("PUBLISHED").accessMode("READ").exposureMode("MODEL").build();
	}

	private DataAgentSkillVersion stubModifySkill() {
		DataAgentSkill skill = platformSkill();
		DataAgentSkillVersion draft = draft();
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(versionMapper.findLatestDraft(1L)).thenReturn(draft);
		when(versionMapper.findBySkillId(1L)).thenReturn(List.of(draft));
		return draft;
	}

	private SkillSaveReq modifyRequest(List<com.sn68.agent.dataagent.dto.skill.SkillToolRefSaveDTO> toolRefs) {
		return new SkillSaveReq("platform-skill", "Platform Skill", null, null, "PLATFORM", "REACT", 0,
				"# Skill", Map.of(), Map.of(), Map.of(), null, Map.of(), toolRefs, null, null, "QUERY",
				Map.of(), Map.of(), Map.of(), null);
	}

	private SkillSaveReq modifyRequestWithRuntimeConfig(Map<String, Object> runtimeConfig) {
		return new SkillSaveReq("platform-skill", "Platform Skill", null, null, "PLATFORM", "REACT", 0,
				"# Skill", Map.of(), Map.of(), Map.of(), null, Map.of(), null, null, null, "QUERY",
				Map.of(), Map.of(), Map.of(), runtimeConfig);
	}

	private SkillSaveReq modifyRequestWithFlow(Map<String, Object> flowRuntimeConfig,
			Map<String, Object> flowPolicyConfig) {
		return new SkillSaveReq("platform-skill", "Platform Skill", null, null, "PLATFORM", "REACT", 0,
				"# Skill", Map.of(), Map.of(), Map.of(), null, Map.of(), null, flowRuntimeConfig, flowPolicyConfig,
				"QUERY", Map.of(), Map.of(), Map.of(), Map.of());
	}

	private void stubFlowValidation(boolean idempotencyRequired) {
		DataAgentSkill skill = DataAgentSkill.builder().id(1L).skillCode("platform-skill").skillName("Platform Skill")
			.tenantId("tenant-1").scope("TENANT").skillKind("ACTION").executionMode("FLOW").status("DRAFT")
			.latestDraftVersionId(10L)
			.displayOrder(0).build();
		DataAgentSkillVersion draft = DataAgentSkillVersion.builder().id(10L).skillId(1L).versionNo(1).status("DRAFT")
			.skillKind("ACTION").executionMode("FLOW")
			.routeRules("{}").flowDefinition("""
					{"schemaVersion":"skill-flow/v1","startNode":"confirm","nodes":[
					  {"id":"confirm","type":"confirm","next":"execute"},
					  {"id":"execute","type":"execute","next":"end","config":{"resourceVersionId":99}},
					  {"id":"end","type":"end"}
					]}
					""").build();
		DataAgentSkillToolRef ref = DataAgentSkillToolRef.builder().skillVersionId(10L).resourceVersionId(99L)
			.resourceKey("demo.echo.write").status("enabled").build();
		AgentExecutionResourceVersion writeTool = AgentExecutionResourceVersion.builder().id(99L)
			.resourceKey("demo.echo.write").versionNo(1).status("PUBLISHED").accessMode("WRITE")
			.exposureMode("FLOW_ONLY").idempotencyRequired(idempotencyRequired).build();
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userType()).thenReturn(UserType.PLATFORM_ADMIN);
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(versionMapper.findLatestDraft(1L)).thenReturn(draft);
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of(ref));
		when(resourceVersionMapper.selectList(any())).thenReturn(List.of(writeTool));
		when(flowDefinitionValidator.validate(any())).thenReturn(List.of());
		when(resourceSnapshotService.capture(any(), any(), any(), any())).thenReturn(
				new SkillVersionResourceSnapshotService.ResourceSnapshot(Map.of(), Map.of(), Map.of(), List.of()));
	}

	private void stubDeterministicDraft(DataAgentSkill skill, DataAgentSkillVersion draft) {
		when(skillMapper.findVisibleByCode("platform-skill", "tenant-1")).thenReturn(skill);
		when(versionMapper.findLatestDraft(1L)).thenReturn(draft);
		when(toolRefMapper.findBySkillVersionId(10L)).thenReturn(List.of());
		when(resourceSnapshotService.capture(any(), any(), any(), any())).thenReturn(
				new SkillVersionResourceSnapshotService.ResourceSnapshot(Map.of(), Map.of(), Map.of(), List.of()));
	}

}
