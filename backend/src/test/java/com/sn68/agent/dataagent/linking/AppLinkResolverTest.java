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
package com.sn68.agent.dataagent.linking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.dto.GroundedFacts;
import com.sn68.agent.dataagent.agentscope.dto.GroundedKey;
import com.sn68.agent.dataagent.agentscope.dto.PageContext;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.security.UntrustedContentBoundary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AppLinkResolverTest {

	private static final String OWN_URL = "http://10.0.0.1:31770/dual-effect/receivable/account/detail?id=2087735796997206016";

	private static final String OWN_ID = "2087735796997206016";

	private DataAgentProperties properties;

	private AgentRuntimeProgressService progressService;

	private AnswerTraceExplainStore explainStore;

	private AppLinkResolver resolver;

	@BeforeEach
	void setUp() {
		properties = new DataAgentProperties();
		properties.getWebEvidence().setAppOrigins(List.of("http://10.0.0.1:31770"));
		progressService = Mockito.mock(AgentRuntimeProgressService.class);
		explainStore = Mockito.mock(AnswerTraceExplainStore.class);
		resolver = new AppLinkResolver(properties, progressService, explainStore);
	}

	@Test
	void extractsOwnOriginQueryIdAndIgnoresExternalArticleId() {
		AgentRequest request = request("分析需求和运单 " + OWN_URL + " 以及 https://news.example.com/story?id=999&page=2");

		resolver.resolve(request);

		assertEquals("分析需求和运单 " + OWN_URL + " 以及 https://news.example.com/story?id=999&page=2", request.getQuery());
		GroundedFacts facts = request.getGroundedFacts();
		assertNotNull(facts);
		assertTrue(facts.isOwnOrigin());
		assertEquals(OWN_ID, trustedValue(facts, "id"));
		assertTrue(facts.getKeys().stream().anyMatch(
				key -> "999".equals(key.getValue()) && GroundedKey.TRUST_EXTERNAL.equals(key.getTrust())));
		assertTrue(request.getEffectiveRoutingQuery().contains("[link-keys"));
		String marker = request.getEffectiveRoutingQuery()
			.substring(request.getEffectiveRoutingQuery().indexOf("[link-keys"));
		assertTrue(marker.contains(OWN_ID));
		assertTrue(marker.contains("trust=" + GroundedKey.TRUST_OWN_ORIGIN));
		assertFalse(marker.contains("999"));
		assertFalse(marker.contains(UntrustedContentBoundary.SENTINEL));
		verifyProgress();
		verify(explainStore).recordLinkResolve(eq(request), any(GroundedFacts.class));
	}

	@Test
	void extractsHashRouteAndPathId() {
		AgentRequest hashQuery = request(
				"http://10.0.0.1:31770/#/dual-effect/receivable/account/detail/" + OWN_ID);
		resolver.resolve(hashQuery);
		assertEquals(OWN_ID, trustedValue(hashQuery.getGroundedFacts(), "id"));

		AgentRequest hashQueryParam = request(
				"http://10.0.0.1:31770/#/dual-effect/receivable/account/detail?id=" + OWN_ID);
		resolver.resolve(hashQueryParam);
		assertEquals(OWN_ID, trustedValue(hashQueryParam.getGroundedFacts(), "id"));

		AgentRequest pathId = request(
				"http://10.0.0.1:31770/dual-effect/receivable/account/detail/" + OWN_ID);
		resolver.resolve(pathId);
		assertEquals(OWN_ID, trustedValue(pathId.getGroundedFacts(), "id"));
	}

	@Test
	void matchesOriginWithDefaultPortTrailingSlashAndUserinfo() {
		properties.getWebEvidence().setAppOrigins(List.of("https://i.example.com/"));
		AgentRequest request = request("see https://user:secret@i.example.com:443/orders/detail?id=" + OWN_ID);

		resolver.resolve(request);

		assertEquals(OWN_ID, trustedValue(request.getGroundedFacts(), "id"));
		assertTrue(request.getGroundedFacts().isOwnOrigin());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
		verify(progressService).emit(eq(request), eq("LINK_RESOLVE"), eq(AgentRuntimeProgressService.STATUS_SUCCESS),
				any(), isNull(), eq("链接解析"), details.capture());
		assertFalse(details.getValue().toString().contains("secret"));
		assertFalse(details.getValue().toString().contains("https://"));
		assertFalse(details.getValue().toString().contains(OWN_ID));
	}

	@Test
	void rfc1918IsOwnSiteWithoutAppOrigins() {
		properties.getWebEvidence().setAppOrigins(List.of());
		AgentRequest request = request("分析 " + OWN_URL);

		resolver.resolve(request);

		assertEquals(OWN_ID, trustedValue(request.getGroundedFacts(), "id"));
		assertTrue(request.getGroundedFacts().isOwnOrigin());
	}

	@Test
	void pageContextKeysAreTrustedAndReplaceOldMarker() {
		AgentRequest request = request("看一下这张单");
		request.setEffectiveRoutingQuery("看一下这张单\n[link-keys old=1 trust=own_origin]");
		request.setPageContext(new PageContext(Map.of("costCode", "CC-9", "id", OWN_ID), "receivable"));

		resolver.resolve(request);

		assertEquals(GroundedKey.TRUST_PAGE_CONTEXT, trustOf(request.getGroundedFacts(), "costCode"));
		assertFalse(request.getEffectiveRoutingQuery().contains("old=1"));
		assertTrue(request.getEffectiveRoutingQuery().contains("costCode=CC-9"));
		assertEquals("看一下这张单", request.getQuery());
		verify(progressService, never()).emit(any(), any(), any(), any(), any(), any(), any());
		verify(progressService, never()).emitHint(any(), any(), anyLong(), any());
	}

	@Test
	void originalQuerySnapshotSuppliesUrlWhenCurrentQueryHasNone() {
		AgentRequest request = request("它包含哪些需求");
		request.setOriginalQuerySnapshot("分析 " + OWN_URL);

		resolver.resolve(request);

		assertEquals(OWN_ID, trustedValue(request.getGroundedFacts(), "id"));
		assertTrue(request.getGroundedFacts().isOwnOrigin());
		verify(progressService).emitHint(any(), eq("已从链接抽出键"), anyLong(), any());
	}

	@Test
	void markdownAndChineseQuotesAreExtracted() {
		AgentRequest request = request("请看「" + OWN_URL + "」以及 [详情](" + OWN_URL + ")");

		resolver.resolve(request);

		assertEquals(OWN_ID, trustedValue(request.getGroundedFacts(), "id"));
	}

	private void verifyProgress() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
		verify(progressService).emit(any(), eq("LINK_RESOLVE"), eq(AgentRuntimeProgressService.STATUS_SUCCESS), any(),
				isNull(), eq("链接解析"), details.capture());
		assertEquals(List.of("id"), details.getValue().get("keyNames"));
		assertFalse(details.getValue().toString().contains(OWN_URL));
		verify(progressService).emitHint(any(), eq("已从链接抽出键"), anyLong(), any());
	}

	private static AgentRequest request(String query) {
		return AgentRequest.builder().agentId("10").threadId("thread-1").runtimeRequestId("runtime-1").query(query)
			.build();
	}

	private static String trustedValue(GroundedFacts facts, String name) {
		return facts.trustedKeys().stream().filter(key -> name.equals(key.getName())).map(GroundedKey::getValue)
			.findFirst().orElse(null);
	}

	private static String trustOf(GroundedFacts facts, String name) {
		return facts.getKeys().stream().filter(key -> name.equals(key.getName())).map(GroundedKey::getTrust).findFirst()
			.orElse(null);
	}

}
