/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.visibility;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent用户Catalog分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户 Agent 目录分页查询")
public class AgentUserCatalogPageQueryReq extends PageRequest {

	@Schema(description = "搜索关键字")
	private String keyword;

	@Schema(description = "状态")
	private String status;

}
