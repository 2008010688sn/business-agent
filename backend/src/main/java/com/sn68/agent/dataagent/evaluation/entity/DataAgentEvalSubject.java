/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * DataAgent 评估对象。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_eval_subject")
@Schema(description = "DataAgent 评估对象")
public class DataAgentEvalSubject extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "租户编码")
	private String tenantCode;

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
