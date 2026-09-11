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
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.sn68.agent.dataagent.constant.OrchestrationStatus;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeMirrorService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.runtime.durable.support.InMemoryDurableRuntime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 镜像服务 run 终态收敛的 Outbox 旁路写入测试：终态迁移 CAS 成功才写、重复收尾不写第二条、
 * 非终态（等待澄清）不写、负载不带最终答案与错误信息原文。
 */
class RuntimeMirrorServiceImplTest {

	private final InMemoryDurableRuntime durable = new InMemoryDurableRuntime();

	private final RuntimeMirrorService mirror = durable.mirror();

	@Test
	void terminalMirrorConvergenceWritesOutboxOnceWithoutBusinessText() {
		Long mirrorRunId = durable.seedMirroredRun(100L, "7");
		AgentOrchestrationRun legacy = legacyRun(100L);
		legacy.setFinalAnswer("最终答案原文不应进入 outbox 负载");

		mirror.mirrorRunFinished(legacy, OrchestrationStatus.SUCCESS);

		assertEquals(RuntimeRunState.SUCCEEDED.getValue(), durable.runById(mirrorRunId).getState());
		List<RuntimeOutboxService.OutboxAppend> appends = durable.outboxAppends();
		assertEquals(1, appends.size());
		RuntimeOutboxService.OutboxAppend append = appends.get(0);
		assertEquals("RUN_SUCCEEDED", append.eventType());
		assertEquals("run-terminal:" + mirrorRunId + ":RUN_SUCCEEDED", append.eventKey());
		assertEquals(mirrorRunId, append.runId());
		assertEquals(7L, append.tenantId());
		assertEquals(Map.of("state", RuntimeRunState.SUCCEEDED.getValue()), append.payload());
	}

	@Test
	void repeatedTerminalMirrorCasFailsAndDoesNotWriteOutboxAgain() {
		durable.seedMirroredRun(100L, "7");
		AgentOrchestrationRun legacy = legacyRun(100L);

		mirror.mirrorRunFinished(legacy, OrchestrationStatus.SUCCESS);
		// 重复收尾（编排链路重放/并发收尾输家）：run 已终态，CAS 迁移失败，不得再写 outbox
		mirror.mirrorRunFinished(legacy, OrchestrationStatus.SUCCESS);
		mirror.mirrorRunFinished(legacy, OrchestrationStatus.FAILED);

		assertEquals(1, durable.outboxAppends().size());
	}

	@Test
	void waitingClarificationIsNotTerminalAndDoesNotWriteOutbox() {
		Long mirrorRunId = durable.seedMirroredRun(100L, "7");

		mirror.mirrorRunFinished(legacyRun(100L), OrchestrationStatus.WAITING_CLARIFICATION);

		assertEquals(RuntimeRunState.WAITING_INPUT.getValue(), durable.runById(mirrorRunId).getState());
		assertTrue(durable.outboxAppends().isEmpty());
	}

	@Test
	void failedMirrorWritesErrorCodeWithoutRawErrorMessage() {
		Long mirrorRunId = durable.seedMirroredRun(100L, "7");
		AgentOrchestrationRun legacy = legacyRun(100L);
		legacy.setErrorMessage("含内部细节的错误信息原文");

		mirror.mirrorRunFinished(legacy, OrchestrationStatus.FAILED);

		assertEquals(RuntimeRunState.FAILED.getValue(), durable.runById(mirrorRunId).getState());
		List<RuntimeOutboxService.OutboxAppend> appends = durable.outboxAppends();
		assertEquals(1, appends.size());
		assertEquals("RUN_FAILED", appends.get(0).eventType());
		assertEquals("run-terminal:" + mirrorRunId + ":RUN_FAILED", appends.get(0).eventKey());
		assertEquals(Map.of("state", RuntimeRunState.FAILED.getValue(), "errorCode", "LEGACY_RUN_FAILED"),
				appends.get(0).payload());
	}

	private AgentOrchestrationRun legacyRun(Long id) {
		AgentOrchestrationRun run = new AgentOrchestrationRun();
		run.setId(id);
		return run;
	}

}
