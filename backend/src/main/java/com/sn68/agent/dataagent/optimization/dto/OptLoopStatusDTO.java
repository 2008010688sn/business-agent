/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * 自进化 LOOP 编排状态：Observe → Diagnose → Generate → OfflineEval(DRY_RUN) → HumanApprove
 * → Apply 适配器（DataAgent 快照 / 员工先 SANDBOX）→ Shadow（DataAgent 只读镜像）→ Canary（切流未实现）
 * → Monitor → Rollback。
 */
@Builder
@Schema(description = "自进化 LOOP 编排状态")
public record OptLoopStatusDTO(

		@Schema(description = "优化实验ID") Long experimentId,

		@Schema(description = "实验归属类型：DATA_AGENT 或 DIGITAL_EMPLOYEE") String ownerType,

		@Schema(description = "实验归属业务 ID：agentId 或 digitalEmployeeId") String ownerId,

		@Schema(description = "实验状态") String experimentStatus,

		@Schema(description = "LOOP 阶段列表（stage/status/detail）") List<Map<String, Object>> stages,

		@Schema(description = "下一步动作提示（人工操作指引，首期不自动推进生产发布）") String nextAction) {
}
