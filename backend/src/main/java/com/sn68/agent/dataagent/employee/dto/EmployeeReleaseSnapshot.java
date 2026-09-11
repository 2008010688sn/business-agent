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
package com.sn68.agent.dataagent.employee.dto;

import cn.hutool.core.collection.CollUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数字员工 Release 快照解析产物（PR-3d 运行时事实源消费视图）。
 *
 * <p>对应 {@code digital_employee_release.snapshot}（Seal 时冻结的完整运行规范 JSON）的强类型只读视图。
 * 运行时（PEP 版本固定、能力网关 Release 固定、PR-6 任务拉起校验）一律消费本视图，
 * 不回读 digital_employee / digital_employee_capability 草稿表——Seal 后草稿变更不影响本视图。</p>
 *
 * <p>能力版本口径（授权六层求交第 6 层「Release/TaskVersion 只收窄」）：
 * <ul>
 * <li>{@link #capabilityVersions()}：快照冻结的 SkillVersionID 字符串集合（skill 级精确版本）；</li>
 * <li>{@link #versionAnchor()}：spec_hash（快照内容指纹，快照级版本锚点）；</li>
 * <li>{@link #matchCapabilityVersion(String)}：请求版本被快照收录时返回请求版本（比对一致放行），
 * 否则返回快照锚点（触发 CAPABILITY_VERSION_MISMATCH，由 PDP 按 ENFORCE 模式裁决）。</li>
 * </ul></p>
 */
@Schema(description = "数字员工 Release 快照解析产物（运行时只读视图）")
public record EmployeeReleaseSnapshot(
		@Schema(description = "发布版本ID（digital_employee_release.id）") Long releaseId,
		@Schema(description = "数字员工ID") Long employeeId,
		@Schema(description = "发布序号") Integer releaseNo,
		@Schema(description = "快照 schema 版本") String schemaVersion,
		@Schema(description = "快照 SHA-256（Seal 时冻结）") String specHash,
		@Schema(description = "员工编码") String employeeCode,
		@Schema(description = "员工姓名") String employeeName,
		@Schema(description = "岗位") String jobTitle,
		@Schema(description = "系统提示词") String systemInstruction,
		@Schema(description = "开场白") String greeting,
		@Schema(description = "模型配置ID") Long modelConfigId,
		@Schema(description = "路由档案ID") Long routeProfileId,
		@Schema(description = "能力来源 DataAgentID") Long sourceAgentId,
		@Schema(description = "自主等级") String autonomyLevel,
		@Schema(description = "执行策略（JSON 对象；历史快照可能为原始字符串，按原样保留不降级）") Object executionPolicy,
		@Schema(description = "冻结的能力清单") List<CapabilityRef> capabilities,
		@Schema(description = "可用对话模型配置ID列表（Seal 时冻结；禁止回源 agent_model_config）") List<Long> availableModelConfigIds,
		@Schema(description = "封版冻结时间") Instant frozenAt) {

	public EmployeeReleaseSnapshot {
		availableModelConfigIds = availableModelConfigIds == null ? List.of() : List.copyOf(availableModelConfigIds);
	}

	/**
	 * 快照冻结的能力条目（skillVersionId 为运行时唯一能力版本事实源）。
	 */
	@Schema(description = "快照冻结的能力条目")
	public record CapabilityRef(
			@Schema(description = "Skill 版本ID") Long skillVersionId,
			@Schema(description = "封版时的能力绑定ID") Long boundCapabilityId,
			@Schema(description = "技能执行模式（Seal 时从 SkillVersion 冻结）") String executionMode) {

		public CapabilityRef(Long skillVersionId, Long boundCapabilityId) {
			this(skillVersionId, boundCapabilityId, null);
		}
	}

	/**
	 * 快照冻结的 SkillVersionID 字符串集合（skill 级精确版本比对输入）。
	 */
	public Set<String> capabilityVersions() {
		Set<String> versions = new LinkedHashSet<>();
		if (CollUtil.isNotEmpty(capabilities)) {
			for (CapabilityRef capability : capabilities) {
				if (capability != null && capability.skillVersionId() != null) {
					versions.add(String.valueOf(capability.skillVersionId()));
				}
			}
		}
		return versions;
	}

	/**
	 * 快照级版本锚点：spec_hash（内容指纹）。同一锚点即同一份冻结能力清单与模型参数。
	 */
	public String versionAnchor() {
		return specHash;
	}

	/**
	 * 解析策略绑定的能力版本（PR-3d 消费 PDP 三参 evaluate 的第三参输入）。
	 *
	 * <ul>
	 * <li>请求版本为空：返回 null（PDP 侧"任一为空跳过比对"，安全不误拒）；</li>
	 * <li>请求版本被快照收录：返回请求版本本身（调用按快照固定版本执行，比对一致放行）；</li>
	 * <li>请求版本未被快照收录（live 版本或其它 Release 的版本）：返回快照锚点，
	 * 与请求版本不等值即触发 CAPABILITY_VERSION_MISMATCH（ENFORCE 拒绝、SHADOW 进影子日志）。</li>
	 * </ul>
	 *
	 * @param requestedVersion 请求侧声明的能力版本（可空）
	 * @return 策略绑定的能力版本；null 表示跳过版本比对
	 */
	public String matchCapabilityVersion(String requestedVersion) {
		if (requestedVersion == null || requestedVersion.isBlank()) {
			return null;
		}
		String normalized = requestedVersion.trim();
		if (capabilityVersions().contains(normalized)) {
			return normalized;
		}
		return versionAnchor();
	}

}
