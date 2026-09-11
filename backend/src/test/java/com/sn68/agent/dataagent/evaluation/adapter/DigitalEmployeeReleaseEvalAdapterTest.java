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
package com.sn68.agent.dataagent.evaluation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PR-7 数字员工发布版本评测适配器（REPLAY）测试：回放源运行取 finalAnswer，
 * 溯源/租户/回答缺失一律失败关闭。
 */
class DigitalEmployeeReleaseEvalAdapterTest {

	private final RuntimeRunService runtimeRunService = mock(RuntimeRunService.class);

	private final DigitalEmployeeEvalInvoker evalInvoker = mock(DigitalEmployeeEvalInvoker.class);

	private final EmployeeReleaseLifecycleService releaseLifecycleService = mock(EmployeeReleaseLifecycleService.class);

	private final DigitalEmployeeReleaseEvalAdapter adapter = new DigitalEmployeeReleaseEvalAdapter(runtimeRunService,
			new ObjectMapper(), evalInvoker, releaseLifecycleService);

	@Test
	void supportsDigitalEmployeeReleaseSubjectOnly() {
		assertTrue(adapter.supports("DIGITAL_EMPLOYEE_RELEASE"));
		assertTrue(adapter.supports("digital_employee_release"));
		assertFalse(adapter.supports("DATA_AGENT"));
		assertEquals("DIGITAL_EMPLOYEE_RELEASE", adapter.adapterCode());
	}

	@Test
	void replaysFinalAnswerFromTracedRuntimeRun() {
		when(runtimeRunService.detail("1", 88L)).thenReturn(detail("本月结算完成，共 12 笔"));

		EvalInvocationResult result = adapter.invoke(prepared("{\"source\":\"RUNTIME_RUN\",\"runtimeRunId\":88}", "1"));

		assertTrue(result.success());
		assertEquals("本月结算完成，共 12 笔", result.answer());
		assertEquals("req-88", result.runtimeRequestId());
	}

	@Test
	void failsClosedWhenTraceSnapshotIsMissing() {
		EvalInvocationResult result = adapter.invoke(prepared(null, "1"));

		assertFalse(result.success());
		assertTrue(result.errorMessage().contains("无法回放"));
	}

	@Test
	void failsClosedWhenTenantSnapshotIsNotNumeric() {
		EvalInvocationResult result = adapter.invoke(prepared("{\"runtimeRunId\":88}", "tenant-a"));

		assertFalse(result.success());
		assertTrue(result.errorMessage().contains("禁止回放"));
	}

	@Test
	void failsClosedWhenSourceRunHasNoFinalAnswer() {
		when(runtimeRunService.detail("1", 88L)).thenReturn(detail(null));

		EvalInvocationResult result = adapter.invoke(prepared("{\"runtimeRunId\":88}", "1"));

		assertFalse(result.success());
		assertTrue(result.errorMessage().contains("最终回答"));
	}

	@Test
	void failsClosedWhenSourceRunDoesNotExist() {
		when(runtimeRunService.detail("1", 88L)).thenReturn(null);

		EvalInvocationResult result = adapter.invoke(prepared("{\"runtimeRunId\":88}", "1"));

		assertFalse(result.success());
		assertTrue(result.errorMessage().contains("不存在"));
		verifyNoInteractions(evalInvoker);
	}

	@Test
	void invokeModeUsesSandboxInvokerAndDoesNotReplay() {
		DigitalEmployeeRelease release = new DigitalEmployeeRelease();
		release.setId(9L);
		release.setEmployeeId(77L);
		when(releaseLifecycleService.getDetail(9L)).thenReturn(release);
		when(evalInvoker.invoke(any(), eq(77L), any())).thenReturn(new EvalInvocationResult(true, "SANDBOX回答",
				1L, "1", "eval-1", 10L, null, null, null, 0, 0, null));

		EvalInvocationResult result = adapter.invoke(preparedInvoke());

		assertTrue(result.success());
		assertEquals("SANDBOX回答", result.answer());
		verify(evalInvoker).invoke(any(), eq(77L), any());
		verifyNoInteractions(runtimeRunService);
	}

	private EvalInvocationPrepared preparedInvoke() {
		EvalInvocationPrepared base = prepared(null, "1");
		base.run().setAgentConfigSnapshotJson("{\"evalMode\":\"INVOKE\"}");
		return base;
	}

	private EvalInvocationPrepared prepared(String traceSnapshotJson, String tenantId) {
		DataAgentEvalSubject subject = new DataAgentEvalSubject();
		subject.setSubjectType(DigitalEmployeeReleaseEvalAdapter.SUBJECT_TYPE);
		subject.setSubjectId("9");
		subject.setAdapterCode(DigitalEmployeeReleaseEvalAdapter.ADAPTER_CODE);
		DataAgentEvalCase evalCase = new DataAgentEvalCase();
		evalCase.setId(5L);
		evalCase.setCaseName("回放用例");
		evalCase.setUserInput("生成本月结算日报");
		evalCase.setTraceSnapshotJson(traceSnapshotJson);
		DataAgentEvalRun run = DataAgentEvalRun.builder()
			.tenantId(tenantId)
			.build();
		return new EvalInvocationPrepared(subject, evalCase, run, java.time.Duration.ofSeconds(30));
	}

	private RuntimeRunDetailResp detail(String finalAnswer) {
		RuntimeRunResp run = new RuntimeRunResp(88L, null, null, null, null, null, null, "thread-88", "req-88",
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
		return new RuntimeRunDetailResp(run, finalAnswer, null, null, List.of(), null);
	}

}
