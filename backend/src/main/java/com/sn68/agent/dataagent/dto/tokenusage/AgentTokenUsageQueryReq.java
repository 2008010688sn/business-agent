/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tokenusage;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AgentToken用量查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "DataAgent Token 用量查询")
public class AgentTokenUsageQueryReq extends PageRequest {

	@Schema(description = "租户ID（仅平台管理员可用于定向查询，其余用户由服务端按登录租户强制覆盖）")
	private String tenantId;

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "模型配置ID")
	private Long modelConfigId;

	@Schema(description = "模型提供商")
	private String provider;

	@Schema(description = "模型名称")
	private String modelName;

	@Schema(description = "用量来源")
	private String usageSource;

	@Schema(description = "计量模式")
	private String meteringMode;

	@Schema(description = "记录状态")
	private String status;

	@Schema(description = "请求来源")
	private String requestSource;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "根运行请求ID")
	private String rootRuntimeRequestId;

	@Schema(description = "开始时间")
	private Instant startTime;

	@Schema(description = "结束时间")
	private Instant endTime;

	@Schema(description = "分组维度（user/agent/model/day）")
	private String groupBy;

}
