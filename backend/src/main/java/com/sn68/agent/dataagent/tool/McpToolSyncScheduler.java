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
package com.sn68.agent.dataagent.tool;

import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
/**
 * MCP 工具目录定时同步任务：按配置开关启用，持分布式锁从各启用的 MCP Server 拉取工具清单并落库。
 */
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.ai.agent.tool-center.tool-sync", name = "enabled", havingValue = "true")
public class McpToolSyncScheduler {

	private static final String TOOL_SYNC_LOCK = "dataagent:tool-center:mcp-tool-sync";

	private final ToolDirectoryService toolDirectoryService;

	private final RedisLockHelper redisLockHelper;

	/**
	 * 多副本下同一时刻只让一个副本同步。{@code upsertTools} 是「先查后 insert/update」的非原子写法，
	 * {@code deleteToolsNotIn} 还会把对方刚写入、自己快照里没有的工具软删掉，因此整段同步必须互斥。
	 *
	 * <p>抢不到锁直接跳过本轮：这是定时任务，等待没有意义，下一个周期还会再来。锁只覆盖定时入口，
	 * 手动触发的同步接口不受影响（保持既有契约）。
	 */
	@Scheduled(fixedDelayString = "${spring.ai.agent.tool-center.tool-sync.fixed-delay:60000}")
	public void sync() {
		try {
			redisLockHelper.execute(TOOL_SYNC_LOCK, 0L, TimeUnit.SECONDS, () -> {
				toolDirectoryService.syncMcpTools();
				return null;
			});
		}
		catch (RedisLockException ex) {
			log.debug("Scheduled MCP tool sync skipped, another replica holds the lock");
		}
		catch (Exception ex) {
			log.warn("Scheduled MCP tool sync failed", ex);
		}
	}

}
