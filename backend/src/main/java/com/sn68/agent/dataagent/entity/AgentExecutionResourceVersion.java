/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Immutable execution-resource version.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_execution_resource_version", autoResultMap = true)
public class AgentExecutionResourceVersion extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 租户ID（空表示平台级共享版本）。 */
	private String tenantId;

	/** 所属执行资源ID。 */
	private Long resourceId;

	/** 工具资源标识（冗余自资源，便于按 key 查版本）。 */
	private String resourceKey;

	/** 版本号（同一资源内递增）。 */
	private Integer versionNo;

	/** 版本状态（DRAFT/PUBLISHED等）。 */
	private String status;

	/** 访问模式（READ/WRITE）。 */
	private String accessMode;

	/** 暴露模式（MODEL=暴露给模型调用）。 */
	private String exposureMode;

	/** 调用所需权限编码。 */
	private String permissionCode;

	/** 调用前是否需要用户确认。 */
	private Boolean confirmRequired;

	/** 是否要求幂等键。 */
	private Boolean idempotencyRequired;

	/** 调用超时（毫秒）。 */
	private Integer timeoutMs;

	/** 入参 JSON Schema。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String inputSchema;

	/** 出参 JSON Schema。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String outputSchema;

	/** 运行时参数映射（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String runtimeParamMappings;

	/** 响应字段映射（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String responseMappings;

	/** 敏感字段清单（JSON，输出时脱敏）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String sensitiveFields;

	/** 发布时资源全量快照（JSON，保证版本不可变）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String snapshot;

	/** 发布时间。 */
	private Instant publishedAt;

}
