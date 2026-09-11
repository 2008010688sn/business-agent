/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.Getter;

/**
 * 技能打开了管理端审批且尚无可用批复。FLOW 应转为 WAITING，不得当成终态失败。
 */
@Getter
public class CapabilityApprovalRequiredException extends CheckedException {

	private final Long approvalId;

	private final String capabilityCode;

	private final String paramsHash;

	public CapabilityApprovalRequiredException(Long approvalId, String capabilityCode, String paramsHash) {
		super("已提交管理端审批（approvalId=" + approvalId + "），通过后将自动继续，capabilityCode=" + capabilityCode);
		this.approvalId = approvalId;
		this.capabilityCode = capabilityCode;
		this.paramsHash = paramsHash;
	}

}
