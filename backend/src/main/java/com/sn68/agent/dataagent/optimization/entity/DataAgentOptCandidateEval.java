/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent 自优化候选评估。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_opt_candidate_eval")
@Schema(description = "Agent 自优化候选评估")
public class DataAgentOptCandidateEval extends SuperEntity<Long> {

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

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "优化实验ID")
	private Long experimentId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "候选ID")
	private Long candidateId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "基线运行ID")
	private Long baselineRunId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "沙箱运行ID")
	private Long sandboxRunId;

	@Schema(description = "分数差值")
	private BigDecimal scoreDelta;

	@Schema(description = "通过率差值")
	private BigDecimal passRateDelta;

	@Schema(description = "耗时变化百分比")
	private BigDecimal durationDeltaPct;

	@Schema(description = "Token 变化百分比")
	private BigDecimal tokenDeltaPct;

	@Schema(description = "候选运行写副作用违规数（DRY_RUN 拦截，硬门禁须为 0）")
	private Integer writeViolationCount;

	@Schema(description = "候选运行权限或租户隔离违规数（硬门禁须为 0）")
	private Integer isolationViolationCount;

	@Schema(description = "分片评估结果JSON（按租户/岗位/业务类型标签分片对比）")
	private String shardResultJson;

	@Schema(description = "是否通过门禁")
	private Boolean passed;

	@Schema(description = "对比结果JSON")
	private String compareJson;

	@Schema(description = "评估状态")
	private String status;

}
