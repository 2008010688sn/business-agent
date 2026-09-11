/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.hook.dto;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运行时钩子Log分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "自动化日志分页查询")
public class RuntimeHookLogPageQueryRequest extends PageRequest {

	@Schema(description = "编码")
	private String hookCode;

	@Schema(description = "类型")
	private String eventType;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "技能字段")
	private String skillCode;

	@Schema(description = "能力编码")
	private Long skillVersionId;

	@Schema(description = "资源来源字段")
	private String resourceKey;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

}
