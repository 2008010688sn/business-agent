/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评估结果分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "评估结果分页查询请求")
public class EvalResultQueryRequest extends PageRequest {

	@Schema(description = "评估运行ID")
	private Long runId;

	@Schema(description = "评估集ID")
	private Long suiteId;

	@Schema(description = "评估用例ID")
	private Long caseId;

	@Schema(description = "评估对象ID")
	private Long subjectId;

	@Schema(description = "结果状态")
	private String status;

	@Schema(description = "是否硬失败")
	private Boolean hardFail;

	@Schema(description = "关键字")
	private String keyword;

}
