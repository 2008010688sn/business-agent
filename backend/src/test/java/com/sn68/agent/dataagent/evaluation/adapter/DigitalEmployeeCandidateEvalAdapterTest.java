/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigitalEmployeeCandidateEvalAdapterTest {

	private final DigitalEmployeeEvalInvoker evalInvoker = mock(DigitalEmployeeEvalInvoker.class);

	private final DigitalEmployeeCandidateEvalAdapter adapter = new DigitalEmployeeCandidateEvalAdapter(evalInvoker,
			new ObjectMapper());

	@Test
	void supportsCandidateSubjectOnly() {
		assertTrue(adapter.supports("DIGITAL_EMPLOYEE_CANDIDATE"));
		assertFalse(adapter.supports("DIGITAL_EMPLOYEE_RELEASE"));
		assertEquals("DIGITAL_EMPLOYEE_CANDIDATE", adapter.adapterCode());
	}

	@Test
	void invokesEmployeeSandboxWithOverlay() {
		DataAgentEvalRun run = DataAgentEvalRun.builder()
			.tenantId("1")
			.agentConfigSnapshotJson("{\"evalMode\":\"INVOKE\",\"candidateOverlay\":\"{\\\"systemInstruction\\\":\\\"新提示\\\"}\"}")
			.build();
		EvalInvocationPrepared prepared = prepared(run, "77");
		when(evalInvoker.invoke(eq(prepared), eq(77L), eq("新提示")))
			.thenReturn(new EvalInvocationResult(true, "ok", null, null, null, null, null, null, null, 0, 0, null));

		EvalInvocationResult result = adapter.invoke(prepared);

		assertTrue(result.success());
		verify(evalInvoker).invoke(eq(prepared), eq(77L), eq("新提示"));
	}

	@Test
	void failsClosedWhenEmployeeIdInvalid() {
		EvalInvocationResult result = adapter.invoke(prepared(DataAgentEvalRun.builder().build(), "abc"));

		assertFalse(result.success());
		assertTrue(result.errorMessage().contains("数字员工 ID"));
	}

	private EvalInvocationPrepared prepared(DataAgentEvalRun run, String subjectId) {
		DataAgentEvalSubject subject = new DataAgentEvalSubject();
		subject.setSubjectType(DigitalEmployeeCandidateEvalAdapter.SUBJECT_TYPE);
		subject.setSubjectId(subjectId);
		DataAgentEvalCase evalCase = new DataAgentEvalCase();
		evalCase.setUserInput("问");
		return new EvalInvocationPrepared(subject, evalCase, run, Duration.ofSeconds(30));
	}

}
