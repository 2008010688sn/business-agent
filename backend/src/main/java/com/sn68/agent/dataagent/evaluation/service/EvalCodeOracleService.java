/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.enums.CodePoolExecutorEnum;
import com.sn68.agent.dataagent.evaluation.enums.EvalFailureReasonDict;
import com.sn68.agent.dataagent.properties.CodeExecutorProperties;
import com.sn68.agent.dataagent.service.code.CodePoolExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 评估代码 oracle：在 Docker 池里执行 Agent 产出的 Python，与期望 stdout/脚本结果比对。
 * LOCAL 或非 none 网络一律失败关闭，禁止为跑通放开沙箱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalCodeOracleService {

	private static final String NETWORK_NONE = "none";

	private final CodePoolExecutorService codePoolExecutorService;

	private final CodeExecutorProperties codeExecutorProperties;

	private final ObjectMapper objectMapper;

	public Verdict evaluate(String agentAnswer, String codeOracleJson) {
		if (!StringUtils.hasText(codeOracleJson)) {
			return Verdict.notApplicable();
		}
		JsonNode spec;
		try {
			spec = objectMapper.readTree(codeOracleJson);
		}
		catch (Exception ex) {
			return Verdict.mismatch("codeOracle JSON 无法解析");
		}
		if (spec == null || spec.isNull() || spec.isEmpty()) {
			return Verdict.notApplicable();
		}
		if (codePoolExecutorService == null) {
			return Verdict.unavailable("代码执行池不可用，代码类用例不能计为通过");
		}
		if (codeExecutorProperties == null
				|| codeExecutorProperties.getCodePoolExecutor() != CodePoolExecutorEnum.DOCKER) {
			return Verdict.unsafe("评估代码 oracle 只允许 docker 执行器，当前="
					+ (codeExecutorProperties == null ? "缺失" : codeExecutorProperties.getCodePoolExecutor()));
		}
		String networkMode = codeExecutorProperties.getNetworkMode();
		if (!NETWORK_NONE.equalsIgnoreCase(networkMode == null ? "" : networkMode.trim())) {
			return Verdict.unsafe("评估代码 oracle 要求 network-mode=none，当前=" + networkMode);
		}
		String expectedStdout = text(spec, "expectedStdout");
		String expectedScript = text(spec, "expectedScript");
		String actualCode = firstText(text(spec, "actualCode"), extractPython(agentAnswer));
		if (!StringUtils.hasText(actualCode)) {
			return Verdict.mismatch("Agent 输出中没有可执行的 Python 代码");
		}
		CodePoolExecutorService.TaskResponse actual = run(actualCode);
		if (actual == null || !actual.isSuccess()) {
			return Verdict.mismatch("实际代码在 Docker 中执行失败: " + (actual == null ? "无响应" : actual.exceptionMsg()));
		}
		String actualOut = normalize(actual.stdOut());
		if (StringUtils.hasText(expectedScript)) {
			CodePoolExecutorService.TaskResponse expected = run(expectedScript);
			if (expected == null || !expected.isSuccess()) {
				return Verdict.mismatch("期望脚本在 Docker 中执行失败: " + (expected == null ? "无响应" : expected.exceptionMsg()));
			}
			if (!actualOut.equals(normalize(expected.stdOut()))) {
				return Verdict.mismatch("实际 stdout 与期望脚本 stdout 不一致");
			}
			return Verdict.success();
		}
		if (!StringUtils.hasText(expectedStdout)) {
			return Verdict.mismatch("codeOracle 未提供 expectedStdout 或 expectedScript");
		}
		if (!actualOut.equals(normalize(expectedStdout))) {
			return Verdict.mismatch("实际 stdout 与 expectedStdout 不一致");
		}
		return Verdict.success();
	}

	private CodePoolExecutorService.TaskResponse run(String code) {
		try {
			return codePoolExecutorService.runTask(new CodePoolExecutorService.TaskRequest(code, "", null));
		}
		catch (RuntimeException ex) {
			log.warn("代码 oracle 执行异常", ex);
			return CodePoolExecutorService.TaskResponse.exception(ex.getMessage());
		}
	}

	static String extractPython(String answer) {
		if (!StringUtils.hasText(answer)) {
			return null;
		}
		String text = answer.trim();
		int pythonFence = indexOfIgnoreCase(text, "```python");
		if (pythonFence >= 0) {
			int start = text.indexOf('\n', pythonFence);
			int end = text.indexOf("```", pythonFence + 9);
			if (start > 0 && end > start) {
				return text.substring(start + 1, end).trim();
			}
		}
		int fence = text.indexOf("```");
		if (fence >= 0) {
			int start = text.indexOf('\n', fence);
			int end = text.indexOf("```", fence + 3);
			if (start > 0 && end > start) {
				return text.substring(start + 1, end).trim();
			}
		}
		return looksLikePython(text) ? text : null;
	}

	private static boolean looksLikePython(String text) {
		String first = text.lines().findFirst().orElse("").trim();
		return first.startsWith("print") || first.startsWith("import ") || first.startsWith("def ")
				|| first.startsWith("from ");
	}

	private static int indexOfIgnoreCase(String text, String token) {
		return text.toLowerCase().indexOf(token);
	}

	private static String normalize(String stdout) {
		if (stdout == null) {
			return "";
		}
		return stdout.replace("\r\n", "\n").replace('\r', '\n').trim();
	}

	private static String text(JsonNode spec, String field) {
		if (spec == null || !spec.hasNonNull(field)) {
			return null;
		}
		return spec.get(field).asText();
	}

	private static String firstText(String left, String right) {
		return StringUtils.hasText(left) ? left : right;
	}

	public record Verdict(Kind kind, EvalFailureReasonDict reason, String detail) {

		public static Verdict notApplicable() {
			return new Verdict(Kind.NOT_APPLICABLE, null, null);
		}

		public static Verdict success() {
			return new Verdict(Kind.PASSED, null, null);
		}

		public static Verdict mismatch(String detail) {
			return new Verdict(Kind.FAILED, EvalFailureReasonDict.CODE_ORACLE_MISMATCH, detail);
		}

		public static Verdict unsafe(String detail) {
			return new Verdict(Kind.FAILED, EvalFailureReasonDict.CODE_ORACLE_UNSAFE_EXECUTOR, detail);
		}

		public static Verdict unavailable(String detail) {
			return new Verdict(Kind.FAILED, EvalFailureReasonDict.CODE_ORACLE_UNAVAILABLE, detail);
		}

		public boolean applicable() {
			return kind != Kind.NOT_APPLICABLE;
		}

		public boolean passed() {
			return kind == Kind.PASSED;
		}

		public enum Kind {
			NOT_APPLICABLE, PASSED, FAILED
		}
	}

}
