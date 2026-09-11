/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.authorization.pep.MemoryAuthorizationAdvisor;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.DomainKnowledgeSearchRequest;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.DomainKnowledgeSearchResult;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;

class DomainKnowledgeSearchServiceImplTest {

	private static final Long SKILL_ID = 7L;

	private static final Long LIVE_ID = 21L;

	private static final Long DELETED_ID = 22L;

	private final AgentVectorStoreService vectorStoreService = mock(AgentVectorStoreService.class);

	private final BusinessKnowledgeMapper businessKnowledgeMapper = mock(BusinessKnowledgeMapper.class);

	private final MemoryAuthorizationAdvisor memoryAuthorizationAdvisor = mock(MemoryAuthorizationAdvisor.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final DomainKnowledgeSearchServiceImpl service = new DomainKnowledgeSearchServiceImpl(vectorStoreService,
			businessKnowledgeMapper, mock(AnswerTraceExplainStore.class), mock(SkillKnowledgeMapper.class),
			memoryAuthorizationAdvisor, authenticationContext);

	/**
	 * F-6：逻辑删除已提交、向量还没清掉（或清理失败）时，检索不能再命中这条知识。存活行白名单在发起向量
	 * 检索之前就已下推，所以残留向量根本不会被取回来，也就不会挤占 topK。
	 */
	@Test
	void deletedKnowledgeIsExcludedFromTheVectorSearchItself() {
		// 逻辑删除后 MyBatis-Plus 的 @TableLogic 让 DELETED_ID 查不出来，只剩存活的一条
		when(businessKnowledgeMapper.selectByIds(org.mockito.ArgumentMatchers.anyCollection()))
			.thenReturn(List.of(embedded(LIVE_ID)));

		service.searchSkillBusinessKnowledge(SKILL_ID, List.of(LIVE_ID, DELETED_ID),
				new DomainKnowledgeSearchRequest("签收量", null, 5, 0.2D), agentRequest());

		assertEquals(Set.of(LIVE_ID), capturedWhitelist());
	}

	@Test
	void unembeddedKnowledgeIsExcludedFromTheVectorSearchItself() {
		BusinessKnowledge failed = embedded(DELETED_ID);
		failed.setEmbeddingStatus(EmbeddingStatus.FAILED);
		when(businessKnowledgeMapper.selectByIds(org.mockito.ArgumentMatchers.anyCollection()))
			.thenReturn(List.of(embedded(LIVE_ID), failed));

		service.searchSkillBusinessKnowledge(SKILL_ID, List.of(LIVE_ID, DELETED_ID),
				new DomainKnowledgeSearchRequest("签收量", null, 5, 0.2D), agentRequest());

		assertEquals(Set.of(LIVE_ID), capturedWhitelist());
	}

	@Test
	void searchIsSkippedEntirelyWhenNothingIsRecallable() {
		when(businessKnowledgeMapper.selectByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of());

		DomainKnowledgeSearchResult result = service.searchSkillBusinessKnowledge(SKILL_ID,
				List.of(LIVE_ID, DELETED_ID), new DomainKnowledgeSearchRequest("签收量", null, 5, 0.2D), agentRequest());

		assertTrue(result.hits().isEmpty());
		assertEquals(Set.of(), capturedWhitelist());
	}

	/**
	 * 命中内容落在 chunk 中后部时，从头截断会把它整段丢掉，而检索日志仍然是 matched。片段要围绕命中
	 * 位置取窗口，模型才看得到真正被召回的那段。
	 */
	@Test
	void snippetKeepsTheMatchedTextWhenItSitsPastTheHead() {
		String head = "前置说明".repeat(200);
		String matched = "生鲜箱的保温时长是 72 小时。";
		String tail = "后置说明".repeat(200);
		givenSingleBusinessDocument(head + matched + tail);

		DomainKnowledgeSearchResult result = service.searchSkillBusinessKnowledge(SKILL_ID,
				List.of(LIVE_ID, DELETED_ID), new DomainKnowledgeSearchRequest("保温时长", null, 5, 0.2D),
				agentRequest());

		String snippet = result.hits().get(0).snippet();
		assertTrue(snippet.contains(matched), "命中内容必须落在窗口里，实际片段：" + snippet);
		assertTrue(snippet.startsWith("..."), "窗口前有内容被截掉时要带省略标记");
		assertTrue(snippet.length() <= 1000, "单条片段不得超过下游 1000 字预算，实际 " + snippet.length());
	}

	/** 纯语义命中时查询词无法在片段里定位，回落到改动前的从头截断。 */
	@Test
	void snippetFallsBackToHeadTruncationWhenTheQueryCannotBeLocated() {
		String text = "甲乙丙丁".repeat(400);
		givenSingleBusinessDocument(text);

		DomainKnowledgeSearchResult result = service.searchSkillBusinessKnowledge(SKILL_ID,
				List.of(LIVE_ID, DELETED_ID), new DomainKnowledgeSearchRequest("戊己庚辛", null, 5, 0.2D),
				agentRequest());

		String snippet = result.hits().get(0).snippet();
		assertTrue(snippet.startsWith("甲乙丙丁"), "定位不到命中时应从头截断，实际片段：" + snippet);
		assertTrue(snippet.endsWith("..."), "尾部被截掉时要带省略标记");
	}

	private void givenSingleBusinessDocument(String text) {
		when(businessKnowledgeMapper.selectByIds(org.mockito.ArgumentMatchers.anyCollection()))
			.thenReturn(List.of(embedded(LIVE_ID)));
		when(vectorStoreService.getDocumentsForSkill(anyString(), anyString(), anyString(), anyInt(), anyDouble(),
				org.mockito.ArgumentMatchers.anyCollection()))
			.thenReturn(List.of(Document.builder()
				.text(text)
				.metadata(Map.of(DocumentMetadataConstant.DB_BUSINESS_TERM_ID, LIVE_ID))
				.build()));
	}

	/**
	 * PR-7 知识线接 PDP：ENFORCE 下策略拒绝（advisor 抛 CheckedException）不检索，
	 * 返回 no_match 空结果，不打断对话主链路（fail-closed）。
	 */
	@Test
	void enforcedPolicyDenialSuppressesVectorSearch() {
		when(memoryAuthorizationAdvisor.checkMemoryAccess(any(), any()))
			.thenThrow(CheckedException.fail("知识检索被授权策略拒绝（BUSINESS_DENIED）"));

		DomainKnowledgeSearchResult result = service.searchSkillBusinessKnowledge(SKILL_ID,
				List.of(LIVE_ID, DELETED_ID), new DomainKnowledgeSearchRequest("签收量", null, 5, 0.2D), agentRequest());

		assertTrue(result.hits().isEmpty());
		verify(vectorStoreService, never()).getDocumentsForSkill(anyString(), anyString(), anyString(), anyInt(),
				anyDouble(), org.mockito.ArgumentMatchers.anyCollection());
	}

	/**
	 * PR-7 知识线接 PDP：ENFORCE + MODEL_ONLY（CAPABILITY_ALLOWED——纯模型动作放行口径）
	 * 不检索私有知识（知识检索属能力消费非纯模型动作）。
	 */
	@Test
	void enforcedModelOnlyPolicySuppressesVectorSearch() {
		when(memoryAuthorizationAdvisor.checkMemoryAccess(any(), any()))
				.thenReturn(pepResult(true, DecisionReasonCode.CAPABILITY_ALLOWED,
						com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode.ENFORCE));

		DomainKnowledgeSearchResult result = service.searchSkillBusinessKnowledge(SKILL_ID,
				List.of(LIVE_ID, DELETED_ID), new DomainKnowledgeSearchRequest("签收量", null, 5, 0.2D), agentRequest());

		assertTrue(result.hits().isEmpty());
		verify(vectorStoreService, never()).getDocumentsForSkill(anyString(), anyString(), anyString(), anyInt(),
				anyDouble(), org.mockito.ArgumentMatchers.anyCollection());
	}

	/**
	 * PR-7 知识线接 PDP：SHADOW 只记录影子决策，检索行为不变（PR-4 灰度总约束）。
	 */
	@Test
	void shadowModeKeepsRetrievalUnchanged() {
		when(memoryAuthorizationAdvisor.checkMemoryAccess(any(), any()))
				.thenReturn(pepResult(true, DecisionReasonCode.POLICY_ALLOWED,
						com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode.SHADOW));
		givenSingleBusinessDocument("生鲜箱的保温时长是 72 小时。");

		DomainKnowledgeSearchResult result = service.searchSkillBusinessKnowledge(SKILL_ID,
				List.of(LIVE_ID, DELETED_ID), new DomainKnowledgeSearchRequest("保温时长", null, 5, 0.2D),
				agentRequest());

		verify(memoryAuthorizationAdvisor).checkMemoryAccess(any(), any());
		assertEquals(1, result.hits().size());
	}

	private PepDecisionResult pepResult(boolean allowed, DecisionReasonCode reasonCode,
			com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode mode) {
		return PepDecisionResult.builder()
			.decision(AuthorizationDecision.builder()
				.allowed(allowed)
				.reasonCode(reasonCode)
				.build())
			.effectiveMode(mode)
			.build();
	}

	@SuppressWarnings("unchecked")
	private Set<Long> capturedWhitelist() {
		ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
		verify(vectorStoreService).getDocumentsForSkill(eq(String.valueOf(SKILL_ID)), anyString(),
				eq(DocumentMetadataConstant.BUSINESS_TERM), anyInt(), anyDouble(), captor.capture());
		return Set.copyOf(captor.getValue());
	}

	private BusinessKnowledge embedded(Long id) {
		BusinessKnowledge knowledge = new BusinessKnowledge();
		knowledge.setId(id);
		knowledge.setSkillId(SKILL_ID);
		knowledge.setBusinessTerm("签收量");
		knowledge.setDescription("按订单产品明细汇总");
		knowledge.setIsRecall(true);
		knowledge.setDeleted(false);
		knowledge.setEmbeddingStatus(EmbeddingStatus.COMPLETED);
		return knowledge;
	}

	private AgentRequest agentRequest() {
		SkillVersionResources resources = new SkillVersionResources(SKILL_ID, 8L, null, List.of(),
				List.of(LIVE_ID, DELETED_ID), List.of(), Map.of());
		AgentRequest request = AgentRequest.builder().query("签收量").runtimeRequestId("run-1").build();
		request.setRoutedSkillId(SKILL_ID);
		request.setRoutedSkillVersionId(8L);
		request.setRoutedSkillResources(resources);
		return request;
	}

}
