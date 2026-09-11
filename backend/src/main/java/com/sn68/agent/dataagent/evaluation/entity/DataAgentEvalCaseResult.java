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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * DataAgent 评估用例结果。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_eval_case_result")
@Schema(description = "DataAgent 评估用例结果")
public class DataAgentEvalCaseResult extends SuperEntity<Long> {

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
	@Schema(description = "评估运行ID")
	private Long runId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估集ID")
	private Long suiteId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估用例ID")
	private Long caseId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估对象ID")
	private Long subjectId;

	@Schema(description = "执行尝试序号")
	private Integer attemptNo;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估会话ID")
	private Long sessionId;

	@Schema(description = "运行线程ID")
	private String threadId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "结果状态")
	private String status;

	@Schema(description = "用户输入快照")
	private String userInput;

	@Schema(description = "Agent 输出快照")
	private String agentOutput;

	@Schema(description = "期望输出快照")
	private String expectedOutput;

	@Schema(description = "总分")
	private BigDecimal score;

	@Schema(description = "质量分")
	private BigDecimal qualityScore;

	@Schema(description = "安全分")
	private BigDecimal safetyScore;

	@Schema(description = "效率分")
	private BigDecimal efficiencyScore;

	@Schema(description = "稳定性分")
	private BigDecimal stabilityScore;

	@Schema(description = "是否硬失败")
	private Boolean hardFail;

	@Schema(description = "写副作用违规数（DRY_RUN 拦截）")
	private Integer writeViolationCount;

	@Schema(description = "权限或租户隔离违规数（DRY_RUN 发现）")
	private Integer isolationViolationCount;

	@Schema(description = "DRY_RUN 违规明细JSON（不含参数值等敏感 payload）")
	private String violationDetailJson;

	@Schema(description = "评分明细JSON")
	private String scoreDetailJson;

	@Schema(description = "失败原因JSON")
	private String failureReasonsJson;

	@Schema(description = "效率指标JSON")
	private String efficiencyMetricsJson;

	@Schema(description = "Trace 快照JSON")
	private String traceSnapshotJson;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant finishedAt;

	@Schema(description = "耗时毫秒")
	private Long durationMs;

	@Schema(description = "错误消息")
	private String errorMessage;

}
