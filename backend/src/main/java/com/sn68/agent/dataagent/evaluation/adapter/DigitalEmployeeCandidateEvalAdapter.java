/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字员工候选评估：钉 SANDBOX 基线 Release，叠加候选 overlay，不写生产 Deployment。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigitalEmployeeCandidateEvalAdapter implements AgentEvalAdapter {

	public static final String ADAPTER_CODE = "DIGITAL_EMPLOYEE_CANDIDATE";

	public static final String SUBJECT_TYPE = "DIGITAL_EMPLOYEE_CANDIDATE";

	private final DigitalEmployeeEvalInvoker evalInvoker;

	private final ObjectMapper objectMapper;

	@Override
	public String adapterCode() {
		return ADAPTER_CODE;
	}

	@Override
	public boolean supports(String subjectType) {
		return SUBJECT_TYPE.equalsIgnoreCase(subjectType);
	}

	@Override
	public EvalInvocationResult invoke(EvalInvocationPrepared prepared) {
		Long employeeId = parseEmployeeId(prepared == null || prepared.subject() == null ? null
				: prepared.subject().getSubjectId());
		if (employeeId == null) {
			return new EvalInvocationResult(false, null, null, null, null, null, null, null, null, 0, 0,
					"候选评估失败: 评估对象未绑定合法的数字员工 ID");
		}
		return evalInvoker.invoke(prepared, employeeId,
				EvalRunHarness.overlayInstruction(prepared.run(), objectMapper));
	}

	private Long parseEmployeeId(String subjectId) {
		if (!StringUtils.hasText(subjectId)) {
			return null;
		}
		try {
			return Long.valueOf(subjectId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
