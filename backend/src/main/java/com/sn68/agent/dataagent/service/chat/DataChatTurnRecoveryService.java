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
package com.sn68.agent.dataagent.service.chat;

import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataChatTurnMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Recovers chat turns left running after their owning runtime has disappeared.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataChatTurnRecoveryService {

	static final String ORPHANED_ERROR = "RUNTIME_ORPHANED: runtime ended without a terminal state";

	private final DataChatTurnMapper turnMapper;

	private final AgentRuntimeRegistry runtimeRegistry;

	private final DataAgentProperties properties;

	@Scheduled(fixedDelayString = "${spring.ai.agent.runtime.stale-turn-scan-interval:60s}")
	public void recoverStaleRunningTurns() {
		recoverStaleRunningTurns(Instant.now());
	}

	int recoverStaleRunningTurns(Instant now) {
		DataAgentProperties.Runtime runtime = properties.getRuntime();
		if (runtime == null || !runtime.isStaleTurnRecoveryEnabled()) {
			return 0;
		}
		Duration totalTimeout = runtime.getTotalTimeout();
		Duration grace = runtime.getStaleTurnGrace();
		if (!isPositive(totalTimeout) || grace == null || grace.isNegative()) {
			log.error("Skip stale chat turn recovery because timeout configuration is invalid");
			return 0;
		}
		Instant startedBefore = now.minus(totalTimeout.plus(grace));
		List<DataChatTurn> candidates = turnMapper.findStaleRunning(startedBefore,
				runtime.getStaleTurnRecoveryBatchSize());
		int recovered = 0;
		for (DataChatTurn turn : candidates) {
			if (turn == null || turn.getId() == null || runtimeRegistry.isActive(turn.getThreadId(),
					turn.getRuntimeRequestId())) {
				continue;
			}
			try {
				long durationMs = elapsedMs(turn.getStartedAt(), now);
				int updated = turnMapper.markStaleFailedIfRunning(turn.getId(), startedBefore, ORPHANED_ERROR, now,
						durationMs);
				if (updated > 0) {
					recovered++;
					log.warn("Recovered orphaned chat turn. sessionId={}, runtimeRequestId={}, startedAt={}",
							turn.getSessionId(), turn.getRuntimeRequestId(), turn.getStartedAt());
				}
			}
			catch (RuntimeException error) {
				log.error("Failed to recover orphaned chat turn. sessionId={}, runtimeRequestId={}", turn.getSessionId(),
						turn.getRuntimeRequestId(), error);
			}
		}
		return recovered;
	}

	private boolean isPositive(Duration duration) {
		return duration != null && !duration.isNegative() && !duration.isZero();
	}

	private long elapsedMs(Instant startedAt, Instant finishedAt) {
		if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
			return 0L;
		}
		return Duration.between(startedAt, finishedAt).toMillis();
	}

}
