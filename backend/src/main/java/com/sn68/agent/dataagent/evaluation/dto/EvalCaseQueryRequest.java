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
 * 评估用例分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "评估用例分页查询请求")
public class EvalCaseQueryRequest extends PageRequest {

	@Schema(description = "评估集ID")
	private Long suiteId;

	@Schema(description = "用例名称")
	private String caseName;

	@Schema(description = "用例状态")
	private String status;

	@Schema(description = "关键字")
	private String keyword;

}
