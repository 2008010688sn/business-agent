/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 评估对象保存请求。
 */
@Data
@Schema(description = "评估对象保存请求")
public class EvalSubjectRequest {

	@Schema(description = "评估对象名称")
	private String subjectName;

	@Schema(description = "评估对象类型")
	private String subjectType;

	@Schema(description = "评估对象业务ID")
	private String subjectId;

	@Schema(description = "适配器编码")
	private String adapterCode;

	@Schema(description = "评估对象状态")
	private String status;

	@Schema(description = "评估对象说明")
	private String description;

}
