/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * DataAgent 记忆范围（落库到 {@code data_agent_memory.subject_type}，即记忆主体类型）。
 *
 * <p>与 {@link AgentMemoryType} 的关系：{@code AgentMemoryType} 表达记忆的<b>内容类型</b>
 * （偏好/事实/结论/工作方式），被抽取分类、记忆配置的 write/recall types 与向量元数据消费；
 * 本枚举表达记忆的<b>归属边界</b>（挂在哪个主体上、按哪个主体隔离）。二者是独立维度，
 * 若把 SESSION/WORKSPACE 等边界值混入 {@code AgentMemoryType}，记忆配置与抽取分类的
 * 既有消费点语义会被破坏，因此按方案第十二章另建本枚举而不是扩展 {@code AgentMemoryType}。
 *
 * <p>subjectId 约定：SESSION→会话ID；EMPLOYEE_USER/EPISODIC/PROCEDURAL→用户ID；
 * WORKSPACE→数字员工ID（PR-7 起语义重定义为数字员工共享记忆，冗余 digital_employee_id 列便于索引，
 * workspace 域已随 PR-1 拆除、workspace_id 列已随 PR-7 DDL 清理，不新增枚举值）。
 *
 * <p>隔离规则：用户侧查询（EMPLOYEE_USER/EPISODIC/PROCEDURAL）与 WORKSPACE 共享记忆严格隔离，
 * 查询入口必须显式指定单一 scope，禁止跨 scope 混查；数字员工（对话与无人值守任务，ownerType=DIGITAL_EMPLOYEE）只召回
 * WORKSPACE 共享记忆，不召回任何真人记忆。
 */
public enum MemoryScope implements DictEnum<String> {

	/** 历史遗留短记忆枚举值，仅兼容存量读/导出；治理写入口拒绝新写入，运行时短记忆走 as2:。 */
	SESSION("SESSION", "会话记忆"),

	EMPLOYEE_USER("EMPLOYEE_USER", "员工用户记忆"),

	WORKSPACE("WORKSPACE", "数字员工共享记忆"),

	EPISODIC("EPISODIC", "情景记忆"),

	PROCEDURAL("PROCEDURAL", "程序性记忆");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	MemoryScope(String value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

	/**
	 * 是否归属用户个人侧（与 WORKSPACE/SESSION 记忆隔离的召回边界）。
	 */
	public boolean userOwned() {
		return this == EMPLOYEE_USER || this == EPISODIC || this == PROCEDURAL;
	}

}
