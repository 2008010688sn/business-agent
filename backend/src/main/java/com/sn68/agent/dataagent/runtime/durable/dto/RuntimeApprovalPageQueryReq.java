/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 持久运行时审批分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "持久运行时审批分页查询请求")
public class RuntimeApprovalPageQueryReq extends PageRequest {

	@Schema(description = "审批状态：PENDING、APPROVED、REJECTED、EXPIRED、CANCELLED、CONSUMED")
	private String state;

	@Schema(description = "所属运行ID")
	private Long runId;

	@Schema(description = "关联步骤键")
	private String stepKey;

	@Schema(description = "风险等级：LOW、MEDIUM、HIGH")
	private String riskLevel;

	@Schema(description = "审批幂等键（能力编码 + 参数指纹）")
	private String approvalKey;

}
