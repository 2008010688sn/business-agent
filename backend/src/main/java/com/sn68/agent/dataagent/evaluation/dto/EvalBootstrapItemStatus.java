/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评估初始化单项结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "评估初始化单项结果")
public class EvalBootstrapItemStatus {

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "业务ID")
	private Long id;

	@Schema(description = "处理状态：created-新建，reused-复用")
	private String status;

	public static EvalBootstrapItemStatus created(Long id) {
		return EvalBootstrapItemStatus.builder().id(id).status("created").build();
	}

	public static EvalBootstrapItemStatus reused(Long id) {
		return EvalBootstrapItemStatus.builder().id(id).status("reused").build();
	}

}
