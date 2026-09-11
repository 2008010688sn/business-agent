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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.dto.GroundedFacts;
import com.sn68.agent.dataagent.agentscope.dto.GroundedKey;
import com.sn68.agent.dataagent.agentscope.dto.PageContext;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 在 consumePending 之后、query-clarify 之前抽出链接键。不改 {@code query}；失败 fail-open。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppLinkResolver {

	private final DataAgentProperties dataAgentProperties;

	private final AgentRuntimeProgressService runtimeProgressService;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	public void resolve(AgentRequest request) {
		if (request == null) {
			return;
		}
		long startedAt = System.nanoTime();
		try {
			doResolve(request, startedAt);
		}
		catch (RuntimeException ex) {
			failOpen(request, "PARSE_FAILED", startedAt, true, ex);
		}
	}

	private void doResolve(AgentRequest request, long startedAt) {
		List<String> urls = LinkKeyExtractor.extractUrls(request.getQuery(), request.getOriginalQuerySnapshot());
		List<GroundedKey> keys = new ArrayList<>();
		boolean ownOrigin = false;
		for (String url : urls) {
			URI uri = AppOriginMatcher.parse(url);
			if (uri == null) {
				log.warn(
						"Link resolve skipped malformed URL. sessionId={}, runtimeRequestId={}, url={}",
						request.getThreadId(), request.getRuntimeRequestId(), AppOriginMatcher.truncateForLog(url));
				continue;
			}
			boolean own = AppOriginMatcher.isOwnSite(uri, appOrigins());
			ownOrigin = ownOrigin || own;
			String trust = own ? GroundedKey.TRUST_OWN_ORIGIN : GroundedKey.TRUST_EXTERNAL;
			keys.addAll(LinkKeyExtractor.extractKeys(uri, trust));
			if (log.isDebugEnabled()) {
				log.debug("Link resolve URL. sessionId={}, runtimeRequestId={}, ownOrigin={}, url={}",
						request.getThreadId(), request.getRuntimeRequestId(), own,
						AppOriginMatcher.truncateForLog(url));
			}
		}
		collectPageContextKeys(request, keys);
		if (urls.isEmpty() && keys.isEmpty()) {
			return;
		}
		GroundedFacts facts = GroundedFacts.builder().keys(dedupe(keys)).ownOrigin(ownOrigin).build();
		request.setGroundedFacts(facts);
		applyRoutingHint(request, facts);
		long durationMs = elapsedMs(startedAt);
		log.info("Link resolve done. sessionId={}, runtimeRequestId={}, keyNames={}, keyCount={}, trust={}, ownOrigin={}, durationMs={}",
				request.getThreadId(), request.getRuntimeRequestId(), keyNames(facts), facts.getKeys().size(),
				trusts(facts), facts.isOwnOrigin(), durationMs);
		answerTraceExplainStore.recordLinkResolve(request, facts);
		if (!urls.isEmpty()) {
			emitProgress(request, facts, durationMs);
		}
	}

	private void collectPageContextKeys(AgentRequest request, List<GroundedKey> keys) {
		PageContext pageContext = request.getPageContext();
		if (pageContext == null || CollectionUtils.isEmpty(pageContext.getKeys())) {
			return;
		}
		for (Map.Entry<String, String> entry : pageContext.getKeys().entrySet()) {
			GroundedKey key = LinkKeyExtractor.pageContextKey(entry.getKey(), entry.getValue());
			if (key != null) {
				keys.add(key);
			}
		}
	}

	private void applyRoutingHint(AgentRequest request, GroundedFacts facts) {
		List<GroundedKey> trusted = capTrusted(facts.trustedKeys());
		boolean hadEffective = StringUtils.hasText(request.getEffectiveRoutingQuery());
		String base = LinkKeysMarker.strip(
				hadEffective ? request.getEffectiveRoutingQuery() : request.getQuery());
		if (trusted.isEmpty()) {
			if (hadEffective) {
				request.setEffectiveRoutingQuery(StringUtils.hasText(base) ? base : null);
			}
			return;
		}
		// routing query 只放短键，避免哨兵污染词法/向量；不可信包裹只在 LinkContextHook 里做。
		request.setEffectiveRoutingQuery(LinkKeysMarker.replace(base, LinkKeysMarker.wrapMarker(formatKeys(trusted))));
	}

	private void emitProgress(AgentRequest request, GroundedFacts facts, long durationMs) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("keyNames", keyNames(facts));
		details.put("keyCount", facts.getKeys() == null ? 0 : facts.getKeys().size());
		details.put("trust", trusts(facts));
		details.put("ownOrigin", facts.isOwnOrigin());
		runtimeProgressService.emit(request, "LINK_RESOLVE", AgentRuntimeProgressService.STATUS_SUCCESS, durationMs,
				null, "链接解析", details);
		int keyCount = facts.getKeys() == null ? 0 : facts.getKeys().size();
		if (keyCount > 0) {
			runtimeProgressService.emitHint(request, "已从链接抽出键", durationMs, details);
		}
	}

	private void failOpen(AgentRequest request, String reason, long startedAt, boolean foundUrl, RuntimeException ex) {
		long durationMs = elapsedMs(startedAt);
		log.warn("Link resolve failed open. sessionId={}, runtimeRequestId={}, reason={}, durationMs={}",
				request.getThreadId(), request.getRuntimeRequestId(), reason, durationMs, ex);
		GroundedFacts facts = GroundedFacts.failOpen(reason);
		request.setGroundedFacts(facts);
		if (StringUtils.hasText(request.getEffectiveRoutingQuery())) {
			request.setEffectiveRoutingQuery(LinkKeysMarker.strip(request.getEffectiveRoutingQuery()));
		}
		answerTraceExplainStore.recordLinkResolve(request, facts);
		if (foundUrl) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("keyCount", 0);
			details.put("ownOrigin", false);
			details.put("failOpenReason", reason);
			runtimeProgressService.emit(request, "LINK_RESOLVE", AgentRuntimeProgressService.STATUS_SUCCESS, durationMs,
					null, "链接解析", details);
		}
	}

	private List<String> appOrigins() {
		if (dataAgentProperties == null || dataAgentProperties.getWebEvidence() == null
				|| dataAgentProperties.getWebEvidence().getAppOrigins() == null) {
			return List.of();
		}
		return dataAgentProperties.getWebEvidence().getAppOrigins();
	}

	private static List<GroundedKey> dedupe(List<GroundedKey> keys) {
		if (CollectionUtils.isEmpty(keys)) {
			return List.of();
		}
		Map<String, GroundedKey> byIdentity = new LinkedHashMap<>();
		for (GroundedKey key : keys) {
			if (key == null || !StringUtils.hasText(key.getName()) || !StringUtils.hasText(key.getValue())) {
				continue;
			}
			String identity = key.getName() + "=" + key.getValue();
			GroundedKey existing = byIdentity.get(identity);
			if (existing == null || trustRank(key.getTrust()) > trustRank(existing.getTrust())) {
				byIdentity.put(identity, key);
			}
		}
		return new ArrayList<>(byIdentity.values());
	}

	private static List<GroundedKey> capTrusted(List<GroundedKey> trusted) {
		if (trusted.size() <= LinkKeyExtractor.MAX_KEYS_PER_TURN) {
			return trusted;
		}
		return List.copyOf(trusted.subList(0, LinkKeyExtractor.MAX_KEYS_PER_TURN));
	}

	private static int trustRank(String trust) {
		if (GroundedKey.TRUST_OWN_ORIGIN.equals(trust)) {
			return 3;
		}
		if (GroundedKey.TRUST_PAGE_CONTEXT.equals(trust)) {
			return 2;
		}
		return 1;
	}

	private static String formatKeys(List<GroundedKey> keys) {
		StringBuilder builder = new StringBuilder();
		for (GroundedKey key : keys) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(key.getName()).append('=').append(key.getValue()).append(" trust=").append(key.getTrust());
		}
		return builder.toString();
	}

	private static List<String> keyNames(GroundedFacts facts) {
		if (facts == null || CollectionUtils.isEmpty(facts.getKeys())) {
			return List.of();
		}
		Set<String> names = new LinkedHashSet<>();
		for (GroundedKey key : facts.getKeys()) {
			if (key != null && StringUtils.hasText(key.getName())) {
				names.add(key.getName());
			}
		}
		return List.copyOf(names);
	}

	private static List<String> trusts(GroundedFacts facts) {
		if (facts == null || CollectionUtils.isEmpty(facts.getKeys())) {
			return List.of();
		}
		Set<String> values = new LinkedHashSet<>();
		for (GroundedKey key : facts.getKeys()) {
			if (key != null && StringUtils.hasText(key.getTrust())) {
				values.add(key.getTrust());
			}
		}
		return List.copyOf(values);
	}

	private static long elapsedMs(long startedAt) {
		return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
	}

}
