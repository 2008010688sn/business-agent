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
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

import com.sn68.agent.framework.redis.plus.RedisLimitHelper;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RateType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 租户 QPS。优先 RedisLimitHelper；没有 Redis 时按 tenantId 进程内 1 秒窗口限流（多副本不共享）。
 */
@Slf4j
@Component
public class WebFetchRateLimiter {

	static final String REASON_QPS = "QPS";

	private static final String RATE_KEY_PREFIX = "ai:web-fetch:qps:";

	private final ObjectProvider<RedisLimitHelper> redisLimitHelper;

	private final ConcurrentHashMap<String, Window> localWindows = new ConcurrentHashMap<>();

	public WebFetchRateLimiter(ObjectProvider<RedisLimitHelper> redisLimitHelper) {
		this.redisLimitHelper = redisLimitHelper;
	}

	public boolean tryAcquire(String tenantId) {
		int qps = WebFetchLimits.QPS;
		String key = RATE_KEY_PREFIX + (StringUtils.hasText(tenantId) ? tenantId.trim() : "anonymous");
		RedisLimitHelper helper = redisLimitHelper == null ? null : redisLimitHelper.getIfAvailable();
		if (helper != null) {
			try {
				return helper.tryAcquire(key, qps, 1L, TimeUnit.SECONDS, RateType.OVERALL, 0L);
			}
			catch (RuntimeException ex) {
				log.warn("web_fetch redis QPS fallback to process-local. tenantId={}", key);
			}
		}
		return tryAcquireLocal(key, qps);
	}

	private boolean tryAcquireLocal(String key, int qps) {
		long now = System.currentTimeMillis();
		Window window = localWindows.computeIfAbsent(key, ignored -> new Window());
		synchronized (window) {
			if (now - window.startedAtMs >= 1000L) {
				window.startedAtMs = now;
				window.count = 0;
			}
			if (window.count >= qps) {
				return false;
			}
			window.count++;
			return true;
		}
	}

	private static final class Window {

		private long startedAtMs = System.currentTimeMillis();

		private int count;

	}

}
