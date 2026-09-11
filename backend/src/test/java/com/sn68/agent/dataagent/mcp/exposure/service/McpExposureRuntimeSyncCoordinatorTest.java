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
package com.sn68.agent.dataagent.mcp.exposure.service;

import com.alibaba.fastjson2.JSON;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureRuntimeSyncResult;
import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureRuntimeSyncEvent.Reason;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpExposureRuntimeSyncCoordinatorTest {

	private final McpExposureRuntimeRegistry runtimeRegistry = mock(McpExposureRuntimeRegistry.class);

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	private final McpExposureRuntimeSyncCoordinator coordinator =
			new McpExposureRuntimeSyncCoordinator(runtimeRegistry, redisTemplate);

	@AfterEach
	void clearTransactionState() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	@Test
	void manualSyncCompletesLocallyAndReturnsNotifiedPeerCount() {
		when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(3L);

		McpExposureRuntimeSyncResult result = coordinator.synchronizeManually();

		assertTrue(result.localSynced());
		assertEquals(2L, result.notifiedPeerCount());
		assertFalse(result.eventId().isBlank());
		assertTrue(result.syncedAt().isAfter(Instant.EPOCH));
		verify(runtimeRegistry).reconcile();
		ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
		verify(redisTemplate).convertAndSend(eq(McpExposureRuntimeSyncCoordinator.SYNC_CHANNEL),
				messageCaptor.capture());
		assertTrue(messageCaptor.getValue().contains("\"reason\":\"MANUAL\""));
	}

	@Test
	void configurationSyncWaitsForCommit() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();
		when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

		coordinator.requestAfterCommit(Reason.CREATE);

		verifyNoInteractions(runtimeRegistry, redisTemplate);
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCommit();
		}
		verify(runtimeRegistry).reconcile();
		verify(redisTemplate).convertAndSend(anyString(), anyString());
	}

	@Test
	void configurationSyncDoesNotRunAfterRollback() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		TransactionSynchronizationManager.initSynchronization();

		coordinator.requestAfterCommit(Reason.CREATE);
		for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
			synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
		}

		verifyNoInteractions(runtimeRegistry, redisTemplate);
	}

	@Test
	void automaticSyncAttemptsPublishWhenLocalReconcileFails() {
		doThrow(new IllegalStateException("local failed")).when(runtimeRegistry).reconcile();
		when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);

		assertDoesNotThrow(() -> coordinator.requestAfterCommit(Reason.UPDATE));

		verify(redisTemplate).convertAndSend(anyString(), anyString());
	}

	@Test
	void automaticSyncDoesNotPropagatePublishFailure() {
		when(redisTemplate.convertAndSend(anyString(), anyString()))
			.thenThrow(new IllegalStateException("redis failed"));

		assertDoesNotThrow(() -> coordinator.requestAfterCommit(Reason.DELETE));

		verify(runtimeRegistry).reconcile();
	}

	@Test
	void manualSyncPropagatesFailureAndDoesNotPublishAfterLocalFailure() {
		doThrow(new IllegalStateException("local failed")).when(runtimeRegistry).reconcile();

		assertThrows(IllegalStateException.class, coordinator::synchronizeManually);

		verifyNoInteractions(redisTemplate);
	}

	@Test
	void manualSyncPropagatesPublishFailureAfterLocalSuccess() {
		when(redisTemplate.convertAndSend(anyString(), anyString()))
			.thenThrow(new IllegalStateException("redis failed"));

		assertThrows(IllegalStateException.class, coordinator::synchronizeManually);

		verify(runtimeRegistry).reconcile();
	}

	@Test
	void listenerIgnoresOwnEventAndReconcilesRemoteEvent() {
		when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
		ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
		coordinator.synchronizeManually();
		verify(redisTemplate).convertAndSend(anyString(), messageCaptor.capture());
		McpExposureRuntimeSyncEvent ownEvent =
				JSON.parseObject(messageCaptor.getValue(), McpExposureRuntimeSyncEvent.class);
		clearInvocations(runtimeRegistry);

		coordinator.handleMessage(ownEvent);
		verify(runtimeRegistry, never()).reconcile();

		McpExposureRuntimeSyncEvent remoteEvent = new McpExposureRuntimeSyncEvent("remote-event", "remote-instance",
				Reason.UPDATE, Instant.now());
		coordinator.handleMessage(remoteEvent);
		verify(runtimeRegistry).reconcile();
	}

}
