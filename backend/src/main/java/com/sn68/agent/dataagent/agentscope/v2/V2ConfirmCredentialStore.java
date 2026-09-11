/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package com.sn68.agent.dataagent.agentscope.v2;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sn68.agent.dataagent.ui.ToolConfirmUiAssembler;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * v2 写工具 ASK 确认凭证：Redis 共享存储，TTL 与卡片一致（5 分钟），一次性消费。
 *
 * <p>多副本不进本机内存。审批表仍由 {@code AgentApprovalService} 留痕；本仓库负责下一回合
 * {@code onActing} 按 toolName + 参数指纹跳过 ASK，并重建 {@link ConfirmResult}。
 */
@Slf4j
public class V2ConfirmCredentialStore {

	static final String STATE_PENDING = "PENDING";

	static final String STATE_APPROVED = "APPROVED";

	static final String STATE_DENIED = "DENIED";

	static final String STATE_CONSUMED = "CONSUMED";

	private final AgentScopeV2Properties properties;

	private final ObjectProvider<StringRedisTemplate> redisProvider;

	private final AtomicBoolean redisAbsentLogged = new AtomicBoolean();

	public V2ConfirmCredentialStore(AgentScopeV2Properties properties,
			ObjectProvider<StringRedisTemplate> redisProvider) {
		this.properties = properties;
		this.redisProvider = redisProvider;
	}

	public void savePending(V2RuntimeSnapshot snapshot, String replyId, ToolUseBlock call) {
		if (call == null || !StringUtils.hasText(call.getName())) {
			return;
		}
		String tenantId = snapshot == null ? null : snapshot.tenantId();
		String userId = snapshot == null ? null : snapshot.userId();
		Map<String, Object> input = call.getInput() == null ? Map.of() : call.getInput();
		write(tenantId, userId, ToolConfirmUiAssembler.fingerprint(input), call.getId(), call.getName(), input, replyId,
				STATE_PENDING, ToolConfirmUiAssembler.expiresAt(Instant.now()));
	}

	public void markDecision(String tenantId, String userId, String toolCallId, String toolName, String fingerprint,
			boolean approved) {
		if (!StringUtils.hasText(fingerprint)) {
			return;
		}
		Credential existing = read(tenantId, userId, fingerprint.trim());
		Map<String, Object> input = existing == null || existing.input() == null ? Map.of() : existing.input();
		String id = StringUtils.hasText(toolCallId) ? toolCallId.trim()
				: existing == null ? null : existing.toolCallId();
		String name = StringUtils.hasText(toolName) ? toolName.trim()
				: existing == null ? null : existing.toolName();
		String replyId = existing == null ? null : existing.replyId();
		Instant expiresAt = existing == null || existing.expiresAtEpochMs() <= 0 ? ToolConfirmUiAssembler.expiresAt(Instant.now())
				: Instant.ofEpochMilli(existing.expiresAtEpochMs());
		write(tenantId, userId, fingerprint.trim(), id, name, input, replyId,
				approved ? STATE_APPROVED : STATE_DENIED, expiresAt);
	}

	public boolean isApproved(V2RuntimeSnapshot snapshot, ToolUseBlock call) {
		Credential credential = loadLive(snapshot, call);
		return credential != null && STATE_APPROVED.equals(credential.state());
	}

	public Optional<ConfirmResult> restore(V2RuntimeSnapshot snapshot, ToolUseBlock call) {
		Credential credential = loadLive(snapshot, call);
		if (credential == null || !STATE_APPROVED.equals(credential.state())) {
			return Optional.empty();
		}
		return Optional.of(new ConfirmResult(true, toToolCall(credential, call)));
	}

	public void consume(V2RuntimeSnapshot snapshot, ToolUseBlock call) {
		Credential credential = loadLive(snapshot, call);
		if (credential == null || !STATE_APPROVED.equals(credential.state())) {
			return;
		}
		String tenantId = snapshot == null ? null : snapshot.tenantId();
		String userId = snapshot == null ? null : snapshot.userId();
		write(tenantId, userId, credential.fingerprint(), credential.toolCallId(), credential.toolName(),
				credential.input(), credential.replyId(), STATE_CONSUMED,
				Instant.ofEpochMilli(credential.expiresAtEpochMs()));
	}

	private Credential loadLive(V2RuntimeSnapshot snapshot, ToolUseBlock call) {
		if (snapshot == null || call == null || !StringUtils.hasText(call.getName())) {
			return null;
		}
		Map<String, Object> input = call.getInput() == null ? Map.of() : call.getInput();
		String fingerprint = ToolConfirmUiAssembler.fingerprint(input);
		Credential credential = read(snapshot.tenantId(), snapshot.userId(), fingerprint);
		if (credential == null) {
			return null;
		}
		if (credential.expiresAtEpochMs() > 0 && credential.expiresAtEpochMs() <= Instant.now().toEpochMilli()) {
			return null;
		}
		if (StringUtils.hasText(credential.toolName()) && !call.getName().equals(credential.toolName())) {
			return null;
		}
		return credential;
	}

	private void write(String tenantId, String userId, String fingerprint, String toolCallId, String toolName,
			Map<String, Object> input, String replyId, String state, Instant expiresAt) {
		StringRedisTemplate redis = redis();
		if (redis == null || !StringUtils.hasText(tenantId) || !StringUtils.hasText(userId)
				|| !StringUtils.hasText(fingerprint)) {
			return;
		}
		Instant expiry = expiresAt == null ? ToolConfirmUiAssembler.expiresAt(Instant.now()) : expiresAt;
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("toolCallId", toolCallId);
		payload.put("toolName", toolName);
		payload.put("input", input == null ? Map.of() : input);
		payload.put("fingerprint", fingerprint);
		payload.put("replyId", replyId);
		payload.put("state", state);
		payload.put("expiresAtEpochMs", expiry.toEpochMilli());
		try {
			Duration ttl = ttlUntil(expiry);
			redis.opsForValue().set(redisKey(tenantId, userId, fingerprint), JSONUtil.toJsonStr(payload), ttl);
		}
		catch (RuntimeException ex) {
			log.error("v2 confirm credential Redis 写入失败. tenant={} user={} fingerprint={}", tenantId, userId,
					fingerprint, ex);
		}
	}

	private Credential read(String tenantId, String userId, String fingerprint) {
		StringRedisTemplate redis = redis();
		if (redis == null || !StringUtils.hasText(tenantId) || !StringUtils.hasText(userId)
				|| !StringUtils.hasText(fingerprint)) {
			return null;
		}
		try {
			String json = redis.opsForValue().get(redisKey(tenantId, userId, fingerprint));
			if (!StringUtils.hasText(json)) {
				return null;
			}
			JSONObject object = JSONUtil.parseObj(json);
			Map<String, Object> input = object.get("input") instanceof Map<?, ?> map ? copyMap(map) : Map.of();
			return new Credential(object.getStr("toolCallId"), object.getStr("toolName"), input,
					object.getStr("fingerprint"), object.getStr("replyId"), object.getStr("state"),
					object.getLong("expiresAtEpochMs", 0L));
		}
		catch (RuntimeException ex) {
			log.error("v2 confirm credential Redis 读取失败. tenant={} user={} fingerprint={}", tenantId, userId,
					fingerprint, ex);
			return null;
		}
	}

	private StringRedisTemplate redis() {
		if (redisProvider == null) {
			return null;
		}
		StringRedisTemplate redis = redisProvider.getIfAvailable();
		if (redis == null && redisAbsentLogged.compareAndSet(false, true)) {
			log.warn("AgentScope v2 confirm credential store: StringRedisTemplate absent, ASK 凭证不可跨副本恢复");
		}
		return redis;
	}

	private String redisKey(String tenantId, String userId, String fingerprint) {
		String prefix = properties == null ? "as2:" : properties.resolvedRedisKeyPrefix();
		return prefix + "confirm:t:" + V2AgentStateStore.enc(tenantId) + ":u:" + V2AgentStateStore.enc(userId) + ":fp:"
				+ V2AgentStateStore.enc(fingerprint);
	}

	private static Duration ttlUntil(Instant expiresAt) {
		Duration ttl = Duration.between(Instant.now(), expiresAt);
		if (ttl.isZero() || ttl.isNegative()) {
			return Duration.ofSeconds(1);
		}
		return ttl;
	}

	private static ToolUseBlock toToolCall(Credential credential, ToolUseBlock fallback) {
		String id = StringUtils.hasText(credential.toolCallId()) ? credential.toolCallId()
				: fallback == null ? null : fallback.getId();
		String name = StringUtils.hasText(credential.toolName()) ? credential.toolName()
				: fallback == null ? null : fallback.getName();
		Map<String, Object> input = credential.input() == null || credential.input().isEmpty()
				? fallback == null || fallback.getInput() == null ? Map.of() : fallback.getInput()
				: credential.input();
		return ToolUseBlock.builder().id(id).name(name).input(input).build();
	}

	private static Map<String, Object> copyMap(Map<?, ?> map) {
		LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			if (key != null) {
				copy.put(String.valueOf(key), value);
			}
		});
		return copy;
	}

	record Credential(String toolCallId, String toolName, Map<String, Object> input, String fingerprint, String replyId,
			String state, long expiresAtEpochMs) {
	}

}
