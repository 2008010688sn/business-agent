/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.dto.tool;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 引用了指定锁定工具版本的已发布 Skill 版本信息。
 */
@Data
@Schema(description = "工具版本被Skill引用记录")
public class ToolReferenceResp {

	@Schema(description = "Skill编码")
	private String skillCode;

	@Schema(description = "Skill名称")
	private String skillName;

	@Schema(description = "执行模式")
	private String executionMode;

	@Schema(description = "Skill可见范围")
	private String skillScope;

	@Schema(description = "Skill版本ID")
	private Long skillVersionId;

	@Schema(description = "Skill版本号")
	private Integer skillVersionNo;

	@Schema(description = "被引用的工具版本ID")
	private Long resourceVersionId;

	@Schema(description = "工具版本号")
	private Integer toolVersionNo;

	@Schema(description = "工具用途说明")
	private String usage;

	@Schema(description = "引用状态")
	private String status;

}
