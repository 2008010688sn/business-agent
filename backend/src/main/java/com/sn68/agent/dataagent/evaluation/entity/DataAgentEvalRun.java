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
 * DataAgent 评估运行。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_eval_run")
@Schema(description = "DataAgent 评估运行")
public class DataAgentEvalRun extends SuperEntity<Long> {

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
	@Schema(description = "评估集ID")
	private Long suiteId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估对象ID")
	private Long subjectId;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "评估策略ID")
	private Long policyId;

	@Schema(description = "评估策略版本号")
	private Integer policyVersionNo;

	@Schema(description = "评估策略快照哈希")
	private String policyHash;

	@Schema(description = "评估策略快照JSON")
	private String policySnapshotJson;

	@Schema(description = "用户ID快照")
	private String userIdSnapshot;

	@Schema(description = "客户端ID快照")
	private String clientIdSnapshot;

	@Schema(description = "团队ID快照JSON")
	private String teamIdsJson;

	@Schema(description = "数据权限快照JSON")
	private String dataPermissionSnapshotJson;

	@Schema(description = "模型配置快照JSON")
	private String modelConfigSnapshotJson;

	@Schema(description = "Agent 配置快照JSON")
	private String agentConfigSnapshotJson;

	@Schema(description = "执行意图：LIVE-在线执行，DRY_RUN-离线干跑（禁写、禁外部副作用）")
	private String executionIntent;

	@Schema(description = "写副作用违规数（DRY_RUN 拦截的写能力与外部副作用调用）")
	private Integer writeViolationCount;

	@Schema(description = "权限或租户隔离违规数（DRY_RUN 发现的越权/跨租户拒绝）")
	private Integer isolationViolationCount;

	@Schema(description = "运行状态")
	private String status;

	@Schema(description = "用例总数")
	private Integer totalCount;

	@Schema(description = "成功用例数")
	private Integer successCount;

	@Schema(description = "失败用例数")
	private Integer failedCount;

	@Schema(description = "硬失败用例数")
	private Integer hardFailCount;

	@Schema(description = "平均分")
	private BigDecimal averageScore;

	@Schema(description = "开始时间")
	private Instant startedAt;

	@Schema(description = "结束时间")
	private Instant finishedAt;

	@Schema(description = "错误消息")
	private String errorMessage;

}
