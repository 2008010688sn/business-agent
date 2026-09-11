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
package com.sn68.agent.dataagent.task.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbMapTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 任务定义的不可变版本。创建后内容不允许更新（应用层约束），
 * 参数与提示词调整一律生成新版本，保证历史 task_run 可回溯到当时的快照。
 *
 * <p>PR-6 冻结清单：employeeReleaseId、specHash、executionPrincipalId、
 * authorizationPolicyVersionId、authMode、budget 随版本生成时固化，发布后不可改；
 * 调整这些维度必须生成新版本，不得原地质改。</p>
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_task_version", autoResultMap = true)
@Schema(description = "Agent任务版本实体")
public class AgentTaskVersion extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "任务定义ID")
	private Long definitionId;

	@Schema(description = "版本号（同一定义内单调递增）")
	private Integer versionNo;

	@TableField(typeHandler = JsonbMapTypeHandler.class)
	@Schema(description = "任务参数快照（JSONB）")
	private Map<String, Object> paramsSnapshot;

	@TableField(typeHandler = JsonbMapTypeHandler.class)
	@Schema(description = "提示词快照（JSONB，含系统提示词与用户提示词模板）")
	private Map<String, Object> promptSnapshot;

	@Schema(description = "冻结：版本生成时绑定的数字员工发布版本ID")
	private Long employeeReleaseId;

	@Schema(description = "冻结：版本生成时的受限执行主体标识（service principal）")
	private String executionPrincipalId;

	@Schema(description = "冻结：版本生成时绑定的授权策略版本ID（可空）")
	private Long authorizationPolicyVersionId;

	@Schema(description = "冻结：授权模式（取参数快照 authMode 键，可空）")
	private String authMode;

	@TableField(typeHandler = JsonbMapTypeHandler.class)
	@Schema(description = "冻结：Token 预算配置（取参数快照 budget 键，可空）")
	private Map<String, Object> budget;

	@Schema(description = "冻结：参数+提示词快照指纹（SHA-256 hex 64 位）")
	private String specHash;

}
