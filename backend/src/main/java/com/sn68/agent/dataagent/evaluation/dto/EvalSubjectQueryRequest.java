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
 * 评估对象分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "评估对象分页查询请求")
public class EvalSubjectQueryRequest extends PageRequest {

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

}
