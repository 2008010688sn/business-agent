/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 自优化错误码。
 */
@Getter
@AllArgsConstructor
public enum AgentOptimizationErrorDict implements DictEnum<Integer> {

	REQUEST_INVALID(491001, "自优化请求参数无效"),

	EXPERIMENT_NOT_FOUND(491002, "优化实验不存在"),

	CANDIDATE_NOT_FOUND(491003, "优化候选不存在"),

	RELEASE_NOT_FOUND(491004, "优化发布记录不存在"),

	BASELINE_RUN_NOT_FOUND(491005, "基线评估运行不存在"),

	SANDBOX_RUN_NOT_FOUND(491006, "候选沙箱评估运行不存在"),

	TARGET_TYPE_NOT_ALLOWED(491007, "优化目标类型不允许"),

	GATE_NOT_PASSED(491008, "候选未通过发布门禁"),

	HASH_MISMATCH(491009, "目标配置哈希不一致，请重新评估"),

	RELEASE_NOT_ROLLBACKABLE(491010, "该发布记录不可回滚"),

	TARGET_TYPE_FORBIDDEN(491011, "优化目标类型属于禁止自动修改项（权限/凭据/工具代码/Flow 写入逻辑/财务库存资产规则/数据库结构/补偿逻辑/审批策略）"),

	PATCH_CONTENT_FORBIDDEN(491012, "候选补丁内容命中禁止自动修改项"),

	SANDBOX_RUN_NOT_DRY_RUN(491013, "候选沙箱评估运行未按 DRY_RUN 执行，离线评估必须强制 DRY_RUN"),

	SUBJECT_NOT_DATA_AGENT(491014, "评估对象不是 DataAgent，无法创建生产发布草稿"),

	SANDBOX_RUN_NOT_FINISHED(491015, "候选沙箱评估运行尚未结束，请等待评估完成后再执行门禁判定"),

	SANDBOX_RUN_REPLAY_NOT_ALLOWED(491016, "REPLAY 评估运行不能作为进化门禁沙箱，必须对候选真跑 DRY_RUN"),

	SNAPSHOT_MISSING(491017, "缺少可进化字段快照，无法安全发布或回滚"),

	OWNER_TYPE_NOT_ALLOWED(491018, "实验归属类型只允许 DATA_AGENT 或 DIGITAL_EMPLOYEE"),

	EMPLOYEE_APPLY_NOT_IMPLEMENTED(491019, "数字员工进化发布尚未启用，必须先激活 SANDBOX 再激活 PRODUCTION"),

	EMPLOYEE_STAGING_REQUIRED(491020, "数字员工须先激活 SANDBOX，才能提升 PRODUCTION"),

	AUTO_GENERATE_DISABLED(491021, "未开启自动生成候选，请人工创建或打开 optimization.auto-generate-enabled"),

	GENERATE_FAILED(491022, "自动生成候选失败，未写入任何候选"),

	GENERATE_SATURATED(491023, "基线评估已无失败用例，拒绝继续生成候选以免烧预算");

	@EnumValue
	@JsonValue
	private final Integer value;

	private final String label;

}
