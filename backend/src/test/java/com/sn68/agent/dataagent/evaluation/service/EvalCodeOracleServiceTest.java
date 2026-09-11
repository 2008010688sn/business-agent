/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.enums.CodePoolExecutorEnum;
import com.sn68.agent.dataagent.evaluation.enums.EvalFailureReasonDict;
import com.sn68.agent.dataagent.properties.CodeExecutorProperties;
import com.sn68.agent.dataagent.service.code.CodePoolExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalCodeOracleServiceTest {

	private final CodePoolExecutorService codePool = mock(CodePoolExecutorService.class);

	private final CodeExecutorProperties properties = new CodeExecutorProperties();

	private EvalCodeOracleService oracle;

	@BeforeEach
	void setUp() {
		properties.setCodePoolExecutor(CodePoolExecutorEnum.DOCKER);
		properties.setNetworkMode("none");
		oracle = new EvalCodeOracleService(codePool, properties, new ObjectMapper());
	}

	@Test
	void blankSpecIsNotApplicable() {
		assertFalse(oracle.evaluate("print(1)", null).applicable());
		assertFalse(oracle.evaluate("print(1)", "{}").applicable());
	}

	@Test
	void localExecutorFailsClosed() {
		properties.setCodePoolExecutor(CodePoolExecutorEnum.LOCAL);
		EvalCodeOracleService.Verdict verdict = oracle.evaluate("```python\nprint(1)\n```",
				"{\"expectedStdout\":\"1\"}");

		assertTrue(verdict.applicable());
		assertFalse(verdict.passed());
		assertEquals(EvalFailureReasonDict.CODE_ORACLE_UNSAFE_EXECUTOR, verdict.reason());
	}

	@Test
	void nonNoneNetworkFailsClosed() {
		properties.setNetworkMode("bridge");
		EvalCodeOracleService.Verdict verdict = oracle.evaluate("```python\nprint(1)\n```",
				"{\"expectedStdout\":\"1\"}");

		assertFalse(verdict.passed());
		assertEquals(EvalFailureReasonDict.CODE_ORACLE_UNSAFE_EXECUTOR, verdict.reason());
		assertTrue(verdict.detail().contains("none"));
	}

	@Test
	void missingPoolFailsClosed() {
		EvalCodeOracleService missing = new EvalCodeOracleService(null, properties, new ObjectMapper());
		EvalCodeOracleService.Verdict verdict = missing.evaluate("```python\nprint(1)\n```",
				"{\"expectedStdout\":\"1\"}");

		assertEquals(EvalFailureReasonDict.CODE_ORACLE_UNAVAILABLE, verdict.reason());
	}

	@Test
	void stdoutMismatchIsBlocked() {
		when(codePool.runTask(any())).thenReturn(CodePoolExecutorService.TaskResponse.success("41\n"));
		EvalCodeOracleService.Verdict verdict = oracle.evaluate("```python\nprint(40+2)\n```",
				"{\"expectedStdout\":\"42\"}");

		assertFalse(verdict.passed());
		assertEquals(EvalFailureReasonDict.CODE_ORACLE_MISMATCH, verdict.reason());
	}

	@Test
	void stdoutMatchPasses() {
		when(codePool.runTask(any())).thenReturn(CodePoolExecutorService.TaskResponse.success("42\n"));
		EvalCodeOracleService.Verdict verdict = oracle.evaluate("```python\nprint(40+2)\n```",
				"{\"expectedStdout\":\"42\"}");

		assertTrue(verdict.passed());
		assertNull(verdict.reason());
	}

	@Test
	void expectedScriptComparedInDocker() {
		when(codePool.runTask(any())).thenReturn(CodePoolExecutorService.TaskResponse.success("2"));
		EvalCodeOracleService.Verdict verdict = oracle.evaluate("```python\nprint(1+1)\n```",
				"{\"expectedScript\":\"print(1+1)\"}");

		assertTrue(verdict.passed());
	}

	@Test
	void dockerExecutionFailureIsMismatch() {
		when(codePool.runTask(any())).thenReturn(CodePoolExecutorService.TaskResponse.failure("", "Traceback"));
		EvalCodeOracleService.Verdict verdict = oracle.evaluate("```python\nprint(1)\n```",
				"{\"expectedStdout\":\"1\"}");

		assertFalse(verdict.passed());
		assertEquals(EvalFailureReasonDict.CODE_ORACLE_MISMATCH, verdict.reason());
	}

	@Test
	void extractPythonFromFence() {
		assertEquals("print(1)", EvalCodeOracleService.extractPython("答：\n```python\nprint(1)\n```"));
	}

}
