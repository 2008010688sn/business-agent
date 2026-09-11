/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.converter.SimpleVectorStoreFilterExpressionConverter;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

class DynamicFilterServiceTest {

	@Test
	void buildSkillDynamicFilterUsesSkillNamespaceForBusinessKnowledge() {
		BusinessKnowledgeMapper mapper = mock(BusinessKnowledgeMapper.class);
		when(mapper.selectRecalledKnowledgeIds(2L)).thenReturn(List.of(20L));
		DynamicFilterService service = new DynamicFilterService(mapper);

		Filter.Expression filter = service.buildSkillDynamicFilter("2", DocumentMetadataConstant.BUSINESS_TERM);

		assertNotNull(filter);
		assertFalse(containsKey(filter, Constant.AGENT_ID));
		assertNotNull(findKey(filter, Constant.SKILL_ID));
	}

	@Test
	void buildFilterExpressionStringKeepsSkillKnowledgeSnowflakeIdsAsStrings() {
		String filter = DynamicFilterService.buildFilterExpressionString(
				Map.of(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID, "2065294018721169409"));

		assertEquals("skillKnowledgeId == '2065294018721169409'", filter);
	}

	/**
	 * F-6：库先删、向量后删（甚至删失败）时，残留向量必须仍然召不回来。这里用默认向量库
	 * （SimpleVectorStore）真正走一遍它的 SpEL 过滤，而不是只断言表达式长什么样。
	 */
	@Test
	void deletedKnowledgeIsNotRecallableEvenWhenItsVectorSurvives() {
		DynamicFilterService service = new DynamicFilterService(mock(BusinessKnowledgeMapper.class));

		Filter.Expression filter = service.buildSkillDynamicFilter("2", DocumentMetadataConstant.BUSINESS_TERM,
				List.of(20L));

		assertNotNull(filter);
		assertTrue(matches(filter, Map.of(Constant.SKILL_ID, "2", DocumentMetadataConstant.VECTOR_TYPE,
				DocumentMetadataConstant.BUSINESS_TERM, DocumentMetadataConstant.DB_BUSINESS_TERM_ID, "20")));
		assertFalse(matches(filter, Map.of(Constant.SKILL_ID, "2", DocumentMetadataConstant.VECTOR_TYPE,
				DocumentMetadataConstant.BUSINESS_TERM, DocumentMetadataConstant.DB_BUSINESS_TERM_ID, "21")));
	}

	@Test
	void liveIdWhitelistNeverTouchesTheDatabase() {
		BusinessKnowledgeMapper mapper = mock(BusinessKnowledgeMapper.class);
		DynamicFilterService service = new DynamicFilterService(mapper);

		service.buildSkillDynamicFilter("2", DocumentMetadataConstant.BUSINESS_TERM, List.of(20L));

		verify(mapper, never()).selectRecalledKnowledgeIds(2L);
	}

	@Test
	void emptyLiveIdWhitelistShortCircuitsTheSearch() {
		DynamicFilterService service = new DynamicFilterService(mock(BusinessKnowledgeMapper.class));

		assertNull(service.buildSkillDynamicFilter("2", DocumentMetadataConstant.SKILL_KNOWLEDGE, List.of()));
	}

	@Test
	void skillKnowledgeWhitelistUsesItsOwnResourceIdKey() {
		DynamicFilterService service = new DynamicFilterService(mock(BusinessKnowledgeMapper.class));

		Filter.Expression filter = service.buildSkillDynamicFilter("2", DocumentMetadataConstant.SKILL_KNOWLEDGE,
				List.of(2065294018721169409L));

		assertTrue(matches(filter, Map.of(Constant.SKILL_ID, "2", DocumentMetadataConstant.VECTOR_TYPE,
				DocumentMetadataConstant.SKILL_KNOWLEDGE, DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID,
				"2065294018721169409")));
		assertFalse(matches(filter, Map.of(Constant.SKILL_ID, "2", DocumentMetadataConstant.VECTOR_TYPE,
				DocumentMetadataConstant.SKILL_KNOWLEDGE, DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID,
				"2065294018721169410")));
	}

	@Test
	void whitelistOnAVectorTypeWithoutResourceIdFailsLoudlyInsteadOfFailingOpen() {
		DynamicFilterService service = new DynamicFilterService(mock(BusinessKnowledgeMapper.class));

		assertThrows(IllegalArgumentException.class,
				() -> service.buildSkillDynamicFilter("2", DocumentMetadataConstant.TABLE, List.of(20L)));
	}

	private boolean matches(Filter.Expression filter, Map<String, Object> documentMetadata) {
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("metadata", documentMetadata);
		return Boolean.TRUE.equals(new SpelExpressionParser()
			.parseExpression(new SimpleVectorStoreFilterExpressionConverter().convertExpression(filter))
			.getValue(context, Boolean.class));
	}

	private boolean containsKey(Filter.Operand operand, String key) {
		if (operand instanceof Filter.Key filterKey) {
			return key.equals(filterKey.key());
		}
		if (operand instanceof Filter.Expression expression) {
			return containsKey(expression.left(), key) || containsKey(expression.right(), key);
		}
		return false;
	}

	private Filter.Key findKey(Filter.Operand operand, String key) {
		if (operand instanceof Filter.Key filterKey && key.equals(filterKey.key())) {
			return filterKey;
		}
		if (operand instanceof Filter.Expression expression) {
			Filter.Key left = findKey(expression.left(), key);
			return left != null ? left : findKey(expression.right(), key);
		}
		return null;
	}

}
