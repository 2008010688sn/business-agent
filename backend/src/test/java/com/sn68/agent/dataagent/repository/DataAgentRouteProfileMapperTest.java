/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;

class DataAgentRouteProfileMapperTest {

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				DataAgentRouteProfile.class);
	}

	@Test
	void artifactProfileScanUsesSemanticRecallInsteadOfAutomaticSelection() {
		DataAgentRouteProfileMapper mapper = mock(DataAgentRouteProfileMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findArtifactProfiles();

		ArgumentCaptor<LbqWrapper<DataAgentRouteProfile>> wrapperCaptor = ArgumentCaptor.captor();
		verify(mapper).selectList(wrapperCaptor.capture());
		String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
		assertTrue(sqlSegment.contains("semantic_recall_enabled"), sqlSegment);
		assertFalse(sqlSegment.contains("semantic_auto_select_enabled"), sqlSegment);
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(Boolean.TRUE));
	}

	@Test
	void revisionUpdatePersistsSemanticRecallIndependently() {
		DataAgentRouteProfileMapper mapper = mock(DataAgentRouteProfileMapper.class, Answers.CALLS_REAL_METHODS);
		DataAgentRouteProfile profile = DataAgentRouteProfile.builder()
			.id(1L)
			.semanticRecallEnabled(true)
			.semanticAutoSelectEnabled(false)
			.revision(3L)
			.deleted(false)
			.build();

		mapper.updateWithRevision(profile, 3L);

		ArgumentCaptor<LambdaUpdateWrapper<DataAgentRouteProfile>> wrapperCaptor = ArgumentCaptor.captor();
		verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
		String sqlSet = wrapperCaptor.getValue().getSqlSet();
		assertTrue(sqlSet.contains("semantic_recall_enabled"), sqlSet);
		assertTrue(sqlSet.contains("semantic_auto_select_enabled"), sqlSet);
		assertTrue(Boolean.TRUE.equals(wrapperCaptor.getValue()
			.getParamNameValuePairs()
			.get(parameterName(sqlSet, "semantic_recall_enabled"))));
		assertTrue(Boolean.FALSE.equals(wrapperCaptor.getValue()
			.getParamNameValuePairs()
			.get(parameterName(sqlSet, "semantic_auto_select_enabled"))));
	}

	@Test
	void findActiveScopesToCurrentTenant() {
		DataAgentRouteProfileMapper mapper = mock(DataAgentRouteProfileMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findActive("tenant-b");

		ArgumentCaptor<LbqWrapper<DataAgentRouteProfile>> wrapperCaptor = ArgumentCaptor.captor();
		verify(mapper).selectOne(wrapperCaptor.capture());
		String sql = wrapperCaptor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"), sql);
		assertTrue(sql.contains("status"), sql);
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue("tenant-b"));
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue("ACTIVE"));
	}

	@Test
	void retireActiveExceptScopesToCurrentTenant() {
		DataAgentRouteProfileMapper mapper = mock(DataAgentRouteProfileMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.retireActiveExcept("tenant-b", 9L);

		ArgumentCaptor<LambdaUpdateWrapper<DataAgentRouteProfile>> wrapperCaptor = ArgumentCaptor.captor();
		verify(mapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
		String sql = wrapperCaptor.getValue().getSqlSegment();
		assertTrue(sql.contains("tenant_id"), sql);
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue("tenant-b"));
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(9L));
	}

	@Test
	void routeModelProfileScanTargetsOnlyEnabledModelDisambiguationProfiles() {
		DataAgentRouteProfileMapper mapper = mock(DataAgentRouteProfileMapper.class, Answers.CALLS_REAL_METHODS);

		mapper.findRouteModelProfiles(11L);

		ArgumentCaptor<LbqWrapper<DataAgentRouteProfile>> wrapperCaptor = ArgumentCaptor.captor();
		verify(mapper).selectList(wrapperCaptor.capture());
		String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
		assertTrue(sqlSegment.contains("route_model_config_id"), sqlSegment);
		assertTrue(sqlSegment.contains("model_disambiguation_enabled"), sqlSegment);
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(11L));
		assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(Boolean.TRUE));
	}

	private String parameterName(String sqlSet, String column) {
		Matcher matcher = Pattern
			.compile(Pattern.quote(column) + "\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.([^}]+)}")
			.matcher(sqlSet);
		assertTrue(matcher.find(), sqlSet);
		return matcher.group(1);
	}

}
