/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评估运行分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "评估运行分页查询请求")
public class EvalRunQueryRequest extends PageRequest {

	@Schema(description = "评估集ID")
	private Long suiteId;

	@Schema(description = "评估对象ID")
	private Long subjectId;

	@Schema(description = "运行状态")
	private String status;

	@Schema(description = "开始时间")
	private Instant startTime;

	@Schema(description = "结束时间")
	private Instant endTime;

}
