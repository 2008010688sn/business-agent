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
 * 持久运行时 Run 分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "持久运行时 Run 分页查询请求")
public class RuntimeRunPageQueryReq extends PageRequest {

	@Schema(description = "运行状态")
	private String state;

	@Schema(description = "运行模式")
	private String runMode;

	@Schema(description = "运行主体类型：DIGITAL_EMPLOYEE / CALLER / PLATFORM")
	private String ownerType;
	
	@Schema(description = "运行主体 ID，空表示不按主体过滤")
	private Long ownerId;
	
	/** PR-1: 精确按数字员工过滤。 */
	@Schema(description = "数字员工 ID，空表示不按数字员工过滤")
	private Long digitalEmployeeId;

	@Schema(description = "DataAgent ID")
	private Long agentId;

	@Schema(description = "会话ID")
	private String threadId;

	@Schema(description = "问题关键字（模糊匹配）")
	private String keyword;

}
