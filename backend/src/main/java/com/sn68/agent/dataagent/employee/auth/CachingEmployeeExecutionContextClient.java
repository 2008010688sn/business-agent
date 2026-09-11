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
package com.sn68.agent.dataagent.employee.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 员工执行上下文客户端（standalone：本地 Principal 签发，不读 Sa-Token session）。
 */
@Slf4j
@Component
public class CachingEmployeeExecutionContextClient implements EmployeeExecutionContextClient {

	private static final String CACHE_KEY_PREFIX = "sp_auth_snapshot:v2:";

	private final LocalPrincipalStore principalStore;

	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

	private final DigitalEmployeeProperties properties;

	private final ObjectMapper objectMapper;

	@Autowired
	public CachingEmployeeExecutionContextClient(LocalPrincipalStore principalStore,
			ObjectProvider<StringRedisTemplate> redisTemplateProvider, DigitalEmployeeProperties properties,
			ObjectMapper objectMapper) {
		this.principalStore = principalStore;
		this.redisTemplateProvider = redisTemplateProvider;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public EmployeeAuthTokenContext issueContext(String tenantId, String principalId, String displayName) {
		requireText(tenantId, "tenantId");
		requireText(principalId, "principalId");
		String cacheKey = CACHE_KEY_PREFIX + tenantId + ":" + principalId;
		StringRedisTemplate redisTemplate = redisTemplate();
		EmployeeAuthTokenContext cached = readCache(redisTemplate, cacheKey);
		if (cached != null) {
			Long latest = latestRevision(tenantId, principalId);
			if (latest == null) {
				throw EmployeeAuthContextException.waitingAuth(
						"WAITING_AUTH: 授权快照未返回 auth_revision，拒绝复用缓存执行身份", null);
			}
			if (!latest.equals(cached.authRevision())) {
				log.info("员工执行身份 auth_revision 变更，踢出旧缓存并重签发. tenantId={}, principalId={}", tenantId,
						principalId);
				invalidate(tenantId, principalId);
			}
			else if (!isFresh(cached)) {
				invalidate(tenantId, principalId);
			}
			else if (principalStore.findByToken(cached.tokenValue()) == null) {
				invalidate(tenantId, principalId);
			}
			else {
				return cached;
			}
		}
		EmployeeAuthTokenContext fresh = issueFromStore(tenantId, principalId, displayName);
		writeCache(redisTemplate, cacheKey, fresh);
		return fresh;
	}

	@Override
	public Long latestRevision(String tenantId, String principalId) {
		requireText(tenantId, "tenantId");
		requireText(principalId, "principalId");
		LocalPrincipalStore.Record record = principalStore.require(principalId);
		if (record == null) {
			throw EmployeeAuthContextException.waitingAuth("WAITING_AUTH: 本地 Principal 不存在，员工执行身份暂不可用",
					null);
		}
		return record.authRevision();
	}

	@Override
	public void invalidate(String tenantId, String principalId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(principalId)) {
			return;
		}
		StringRedisTemplate redisTemplate = redisTemplate();
		if (redisTemplate == null) {
			return;
		}
		try {
			redisTemplate.delete(CACHE_KEY_PREFIX + tenantId + ":" + principalId);
		}
		catch (Exception ex) {
			log.warn("失效员工执行身份缓存失败（忽略，等待 TTL 自然过期）. tenantId={}, principalId={}", tenantId,
					principalId, ex);
		}
	}

	private EmployeeAuthTokenContext issueFromStore(String tenantId, String principalId, String displayName) {
		LocalPrincipalStore.IssuedToken issued = principalStore.issueToken(tenantId, principalId, displayName);
		return new EmployeeAuthTokenContext(issued.tokenValue(), issued.tokenType(), issued.expiresIn(),
				issued.authRevision(), System.currentTimeMillis());
	}

	private EmployeeAuthTokenContext readCache(StringRedisTemplate redisTemplate, String cacheKey) {
		if (redisTemplate == null) {
			return null;
		}
		try {
			String json = redisTemplate.opsForValue().get(cacheKey);
			if (!StringUtils.hasText(json)) {
				return null;
			}
			return objectMapper.readValue(json, EmployeeAuthTokenContext.class);
		}
		catch (Exception ex) {
			log.warn("读取员工执行身份缓存失败（视为未命中）. cacheKey={}", cacheKey, ex);
			return null;
		}
	}

	private void writeCache(StringRedisTemplate redisTemplate, String cacheKey, EmployeeAuthTokenContext context) {
		if (redisTemplate == null) {
			return;
		}
		Duration ttl = cacheTtl(context);
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			return;
		}
		try {
			String json = objectMapper.writeValueAsString(context);
			redisTemplate.opsForValue().set(cacheKey, json, ttl);
		}
		catch (Exception ex) {
			log.warn("写入员工执行身份缓存失败（忽略，下次直接重签发）. cacheKey={}", cacheKey, ex);
		}
	}

	boolean isFresh(EmployeeAuthTokenContext context) {
		return remainingSeconds(context) > 0;
	}

	long remainingSeconds(EmployeeAuthTokenContext context) {
		if (context == null || context.issuedAtEpochMillis() == null || context.expiresIn() == null
				|| context.expiresIn() <= 0) {
			return -1;
		}
		long skewMs = skewSeconds() * 1000L;
		long deadline = context.issuedAtEpochMillis() + context.expiresIn() * 1000L - skewMs;
		return (deadline - System.currentTimeMillis()) / 1000L;
	}

	Duration cacheTtl(EmployeeAuthTokenContext context) {
		long remaining = remainingSeconds(context);
		if (remaining <= 0) {
			return Duration.ZERO;
		}
		long maxSeconds = Math.max(1L, properties.getAuthCache().getTtlMinutes()) * 60L;
		return Duration.ofSeconds(Math.min(maxSeconds, remaining));
	}

	private long skewSeconds() {
		long skew = properties.getAuthCache().getSkewSeconds();
		return Math.max(0L, skew);
	}

	private StringRedisTemplate redisTemplate() {
		return redisTemplateProvider.getIfAvailable();
	}

	private static void requireText(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("EmployeeExecutionContextClient 参数不能为空: " + field);
		}
	}

}
