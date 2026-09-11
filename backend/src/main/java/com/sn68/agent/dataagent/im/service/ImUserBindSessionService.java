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
package com.sn68.agent.dataagent.im.service;

import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.dto.ImUserBindSessionCreateRequest;
import com.sn68.agent.dataagent.im.dto.ImUserBindSessionDTO;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImUserBindSession;
import com.sn68.agent.dataagent.im.entity.AgentImUserIdentity;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImUserBindSessionMapper;
import com.sn68.agent.dataagent.im.repository.AgentImUserIdentityMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IM 用户扫码/短码绑定。生成短码时快照当前登录用户；消费发生在 IM 入站线程，不再读取登录态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImUserBindSessionService {

	private static final Pattern LEADING_MENTION = Pattern.compile("^(?:@\\S+\\s+)+");

	private static final Pattern BIND_CODE_PATTERN = Pattern
		.compile("(?i)^" + ImConstants.BIND_CODE_PREFIX + "[2-9A-HJ-NP-Z]{" + ImConstants.BIND_CODE_LENGTH + "}$");

	private static final char[] BIND_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final long BIND_FAIL_WINDOW_MILLIS = TimeUnit.MINUTES
		.toMillis(ImConstants.BIND_FAIL_WINDOW_MINUTES);

	private final ConcurrentHashMap<String, FailBucket> failBuckets = new ConcurrentHashMap<>();

	private final AgentImUserBindSessionMapper bindSessionMapper;

	private final AgentImUserIdentityMapper identityMapper;

	private final AuthenticationContext authenticationContext;

	public record ConsumeResult(boolean bindCommand, boolean success, String reply, String userId, ImErrorDict error) {

		public static ConsumeResult ignored() {
			return new ConsumeResult(false, false, null, null, null);
		}

		public static ConsumeResult ok(String userId, String reply) {
			return new ConsumeResult(true, true, reply, userId, null);
		}

		public static ConsumeResult fail(ImErrorDict error, String reply) {
			return new ConsumeResult(true, false, reply, null, error);
		}
	}

	@Transactional(rollbackFor = Exception.class)
	public ImUserBindSessionDTO create(ImUserBindSessionCreateRequest request) {
		String tenantId = requireCurrentTenantId();
		String userId = requireCurrentUserId();
		String connectorCode = trimToNull(request == null ? null : request.connectorCode());
		expireOwnPending(tenantId, userId, connectorCode);
		AgentImUserBindSession session = new AgentImUserBindSession();
		session.setTenantId(tenantId);
		session.setConnectorCode(connectorCode);
		session.setUserId(userId);
		session.setUsername(firstText(authenticationContext.nickName(), userId));
		session.setNickName(firstText(authenticationContext.nickName(), userId));
		session.setStatus(ImConstants.BIND_SESSION_PENDING);
		session.setExpireTime(LocalDateTime.now().plusMinutes(ImConstants.BIND_SESSION_TTL_MINUTES));
		session.setBindCode(insertWithUniqueCode(session));
		return toDTO(session);
	}

	public ImUserBindSessionDTO status(Long id) {
		if (id == null) {
			throw CheckedException.badRequest(ImErrorDict.BIND_SESSION_NOT_FOUND.getValue(),
					ImErrorDict.BIND_SESSION_NOT_FOUND.getLabel());
		}
		AgentImUserBindSession session = bindSessionMapper.findByIdInTenant(requireCurrentTenantId(), id);
		if (session == null || !requireCurrentUserId().equals(session.getUserId())) {
			throw CheckedException.notFound(ImErrorDict.BIND_SESSION_NOT_FOUND.getValue(),
					ImErrorDict.BIND_SESSION_NOT_FOUND.getLabel());
		}
		return toDTO(refreshExpired(session));
	}

	/**
	 * IM 入站消费短码。返回 ignored 表示不是绑定指令，调用方继续原链路。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ConsumeResult consume(AgentImConnector connector, ImCallbackMessage message) {
		String code = parseBindCode(message == null ? null : message.text());
		if (code == null) {
			return ConsumeResult.ignored();
		}
		if (connector == null || !StringUtils.hasText(connector.getTenantId())
				|| message == null || !StringUtils.hasText(message.externalUserId())) {
			return ConsumeResult.fail(ImErrorDict.BIND_CODE_INVALID, ImErrorDict.BIND_CODE_INVALID.getLabel());
		}
		String failKey = failKey(connector, message.externalUserId());
		if (isRateLimited(failKey)) {
			return ConsumeResult.fail(ImErrorDict.BIND_CODE_RATE_LIMITED,
					ImErrorDict.BIND_CODE_RATE_LIMITED.getLabel());
		}
		AgentImUserBindSession session = bindSessionMapper.findPendingByCode(connector.getTenantId(), code);
		if (session == null) {
			return failBind(failKey, ImErrorDict.BIND_CODE_INVALID, "绑定码无效或已过期，请在管理端重新生成。");
		}
		session = refreshExpired(session);
		if (!ImConstants.BIND_SESSION_PENDING.equals(session.getStatus())) {
			return failBind(failKey, ImErrorDict.BIND_CODE_INVALID, "绑定码无效或已过期，请在管理端重新生成。");
		}
		if (StringUtils.hasText(session.getConnectorCode())
				&& !session.getConnectorCode().equals(connector.getConnectorCode())) {
			return failBind(failKey, ImErrorDict.BIND_CODE_INVALID, "绑定码无效或已过期，请在管理端重新生成。");
		}
		String externalUserId = message.externalUserId().trim();
		AgentImUserIdentity existing = identityMapper.findByExternalUser(connector.getTenantId(),
				connector.getProvider(), connector.getConnectorCode(), externalUserId);
		if (existing != null && StringUtils.hasText(existing.getUserId())
				&& ImConstants.STATUS_ENABLED.equalsIgnoreCase(existing.getBindStatus())
				&& !session.getUserId().equals(existing.getUserId())) {
			return failBind(failKey, ImErrorDict.BIND_IDENTITY_OCCUPIED, "该钉钉用户已绑定其他系统账号，请联系管理员处理。");
		}
		if (bindSessionMapper.markConsumed(session.getId(), externalUserId, LocalDateTime.now()) != 1) {
			return failBind(failKey, ImErrorDict.BIND_CODE_INVALID, "绑定码无效或已过期，请在管理端重新生成。");
		}
		clearFailures(failKey);
		upsertIdentity(connector, message, session, existing, externalUserId);
		String display = firstText(session.getNickName(), session.getUsername(), session.getUserId());
		return ConsumeResult.ok(session.getUserId(), "已绑定到账号 " + display + "，可以开始对话。");
	}

	String parseBindCode(String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		String normalized = LEADING_MENTION.matcher(text.trim()).replaceFirst("").trim().toUpperCase();
		if (!BIND_CODE_PATTERN.matcher(normalized).matches()) {
			return null;
		}
		return normalized;
	}

	private void upsertIdentity(AgentImConnector connector, ImCallbackMessage message, AgentImUserBindSession session,
			AgentImUserIdentity existing, String externalUserId) {
		AgentImUserIdentity identity = existing;
		if (identity == null) {
			identity = new AgentImUserIdentity();
			identity.setTenantId(connector.getTenantId());
			identity.setProvider(connector.getProvider());
			identity.setConnectorCode(connector.getConnectorCode());
			identity.setExternalUserId(externalUserId);
		}
		identity.setUnionId(firstText(message.unionId(), identity.getUnionId()));
		identity.setUserId(session.getUserId());
		identity.setUsername(session.getUsername());
		identity.setNickName(session.getNickName());
		identity.setBindStatus(ImConstants.STATUS_ENABLED);
		identity.setBindSource(ImConstants.BIND_SOURCE_QR_PAIR);
		if (identity.getId() == null) {
			identityMapper.insert(identity);
		}
		else {
			identityMapper.updateById(identity);
		}
	}

	private void expireOwnPending(String tenantId, String userId, String connectorCode) {
		List<AgentImUserBindSession> pending = bindSessionMapper.findPendingByUser(tenantId, userId, connectorCode);
		for (AgentImUserBindSession session : pending) {
			bindSessionMapper.expirePending(session.getId());
		}
	}

	private String insertWithUniqueCode(AgentImUserBindSession session) {
		for (int attempt = 0; attempt < 8; attempt++) {
			String code = ImConstants.BIND_CODE_PREFIX + randomCode();
			session.setBindCode(code);
			try {
				bindSessionMapper.insert(session);
				return code;
			}
			catch (DataIntegrityViolationException ex) {
				log.debug("IM 绑定短码冲突，重试生成。attempt={}", attempt);
			}
		}
		throw CheckedException.fail("生成绑定码失败，请稍后重试");
	}

	private String randomCode() {
		char[] chars = new char[ImConstants.BIND_CODE_LENGTH];
		for (int i = 0; i < chars.length; i++) {
			chars[i] = BIND_CODE_ALPHABET[RANDOM.nextInt(BIND_CODE_ALPHABET.length)];
		}
		return new String(chars);
	}

	private AgentImUserBindSession refreshExpired(AgentImUserBindSession session) {
		if (session == null) {
			return null;
		}
		if (ImConstants.BIND_SESSION_PENDING.equals(session.getStatus()) && session.getExpireTime() != null
				&& !session.getExpireTime().isAfter(LocalDateTime.now())) {
			bindSessionMapper.expirePending(session.getId());
			session.setStatus(ImConstants.BIND_SESSION_EXPIRED);
		}
		return session;
	}

	private ImUserBindSessionDTO toDTO(AgentImUserBindSession session) {
		return new ImUserBindSessionDTO(session.getId(), session.getBindCode(), session.getBindCode(),
				session.getConnectorCode(), session.getUserId(), session.getNickName(), session.getStatus(),
				session.getExpireTime(), session.getExternalUserId());
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次绑定会话操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法创建 IM 绑定会话");
		}
		return tenantId;
	}

	private String requireCurrentUserId() {
		String userId;
		try {
			userId = authenticationContext.userId();
		}
		catch (Exception ex) {
			log.warn("解析当前用户上下文失败, 将按缺失用户拒绝本次绑定会话操作", ex);
			userId = null;
		}
		if (!StringUtils.hasText(userId)) {
			throw CheckedException.badRequest("登录用户缺失, 无法创建 IM 绑定会话");
		}
		return userId;
	}

	private ConsumeResult failBind(String failKey, ImErrorDict error, String reply) {
		recordFailure(failKey);
		return ConsumeResult.fail(error, reply);
	}

	private String failKey(AgentImConnector connector, String externalUserId) {
		return connector.getTenantId() + "|" + firstText(connector.getConnectorCode(), "") + "|"
				+ externalUserId.trim();
	}

	private boolean isRateLimited(String failKey) {
		FailBucket bucket = failBuckets.get(failKey);
		if (bucket == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		if (now - bucket.windowStart >= BIND_FAIL_WINDOW_MILLIS) {
			failBuckets.remove(failKey, bucket);
			return false;
		}
		return bucket.count.get() >= ImConstants.BIND_FAIL_MAX_ATTEMPTS;
	}

	private void recordFailure(String failKey) {
		long now = System.currentTimeMillis();
		FailBucket bucket = failBuckets.compute(failKey, (key, existing) -> {
			if (existing == null || now - existing.windowStart >= BIND_FAIL_WINDOW_MILLIS) {
				return new FailBucket(now);
			}
			return existing;
		});
		bucket.count.incrementAndGet();
	}

	private void clearFailures(String failKey) {
		failBuckets.remove(failKey);
	}

	private static final class FailBucket {
		private final AtomicInteger count = new AtomicInteger();
		private final long windowStart;

		private FailBucket(long windowStart) {
			this.windowStart = windowStart;
		}
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

}
