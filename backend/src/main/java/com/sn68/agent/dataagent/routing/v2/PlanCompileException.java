/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

/**
 * PlanCompiler 编译失败异常,风格对齐 V1 RouteStageException:reasonCode + message。
 * 编译失败即拒绝执行,不产生任何降级计划。
 */
public class PlanCompileException extends RuntimeException {

	public static final String PROPOSAL_JSON_INVALID = "PROPOSAL_JSON_INVALID";

	public static final String PROPOSAL_SCHEMA_INVALID = "PROPOSAL_SCHEMA_INVALID";

	public static final String PROPOSAL_FORBIDDEN_CONTENT = "PROPOSAL_FORBIDDEN_CONTENT";

	public static final String PROPOSAL_MODE_INVALID = "PROPOSAL_MODE_INVALID";

	public static final String STEP_KEY_DUPLICATE = "STEP_KEY_DUPLICATE";

	public static final String STEP_LIMIT_EXCEEDED = "STEP_LIMIT_EXCEEDED";

	public static final String CAPABILITY_NOT_IN_CANDIDATE_SET = "CAPABILITY_NOT_IN_CANDIDATE_SET";

	public static final String NESTED_ORCHESTRATION_DEPTH_EXCEEDED = "NESTED_ORCHESTRATION_DEPTH_EXCEEDED";

	public static final String PORT_NOT_FOUND = "PORT_NOT_FOUND";

	public static final String PORT_TYPE_MISMATCH = "PORT_TYPE_MISMATCH";

	public static final String PORT_CARDINALITY_VIOLATION = "PORT_CARDINALITY_VIOLATION";

	public static final String NATURAL_LANGUAGE_BINDING_FORBIDDEN = "NATURAL_LANGUAGE_BINDING_FORBIDDEN";

	public static final String SENSITIVE_PORT_DENIED = "SENSITIVE_PORT_DENIED";

	public static final String UNBOUND_REQUIRED_INPUT = "UNBOUND_REQUIRED_INPUT";

	public static final String MISSING_STEP_REFERENCE = "MISSING_STEP_REFERENCE";

	public static final String SELF_REFERENCE = "SELF_REFERENCE";

	public static final String PLAN_CYCLE_DETECTED = "PLAN_CYCLE_DETECTED";

	public static final String FANOUT_LIMIT_EXCEEDED = "FANOUT_LIMIT_EXCEEDED";

	public static final String PLAN_CANONICALIZE_FAILED = "PLAN_CANONICALIZE_FAILED";

	private final String reasonCode;

	public PlanCompileException(String reasonCode, String message) {
		super(message);
		this.reasonCode = reasonCode;
	}

	public PlanCompileException(String reasonCode, String message, Throwable cause) {
		super(message, cause);
		this.reasonCode = reasonCode;
	}

	public String reasonCode() {
		return reasonCode;
	}

}
