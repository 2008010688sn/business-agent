/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 可见性申请分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "DataAgent 可见性申请分页查询")
public class AgentVisibilityApplicationPageQueryReq extends PageRequest {

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "Agent名称")
	private String agentName;

	@Schema(description = "申请人用户ID")
	private String applicantUserId;

	@Schema(description = "申请人昵称")
	private String applicantNickName;

	@Schema(description = "申请状态")
	private String status;

	@Schema(description = "审批模式")
	private String approvalMode;

	@Schema(description = "业务编码")
	private String businessCode;

	@Schema(description = "提交时间起")
	private Instant submitTimeStart;

	@Schema(description = "提交时间止")
	private Instant submitTimeEnd;

}
