/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 目录分页查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillPageQueryReq extends PageRequest {

	@Schema(description = "关键字（匹配编码/名称/描述）")
	private String keyword;

	@Schema(description = "Skill状态")
	private String status;

	@Schema(description = "执行模式")
	private String executionMode;

}
