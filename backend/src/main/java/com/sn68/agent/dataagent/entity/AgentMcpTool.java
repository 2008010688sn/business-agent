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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * AgentMCP工具实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_mcp_tool")
@Schema(description = "MCP Tool catalog")
public class AgentMcpTool extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "编码")
	private String serverCode;

	@Schema(description = "工具名称")
	private String toolName;

	@Schema(description = "工具字段")
	private String toolTitle;

	@Schema(description = "描述")
	private String description;

	@Schema(description = "inputSchema字段")
	private String inputSchema;

	@Schema(description = "outputSchema字段")
	private String outputSchema;

	@Schema(description = "策略字段")
	private String permissionPolicy;

	@Schema(description = "级别字段")
	private String riskLevel;

	@Schema(description = "readonly字段")
	private Boolean readonly;

	@Schema(description = "confirmRequired字段")
	private Boolean confirmRequired;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "元数据")
	private String metadata;

}
