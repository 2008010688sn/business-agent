/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tool;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 执行资源分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "执行资源分页查询")
public class ToolPageQueryReq extends PageRequest {

	@Schema(description = "资源类型来源字段")
	private String resourceType;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "搜索关键字")
	private String keyword;

}
