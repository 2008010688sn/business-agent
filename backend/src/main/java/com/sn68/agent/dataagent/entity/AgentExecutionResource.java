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
 * Agent能力资源实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_execution_resource")
@Schema(description = "Agent能力资源实体")
public class AgentExecutionResource extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "资源类型来源字段")
	private String resourceType;

	@Schema(description = "资源来源字段")
	private String resourceKey;

	@Schema(description = "资源名称来源字段")
	private String resourceName;

	@Schema(description = "编码")
	private String serverCode;

	@Schema(description = "名称")
	private String serviceName;

	@Schema(description = "地址")
	private String baseUrl;

	@Schema(description = "工具名称")
	private String toolName;

	@Schema(description = "地址")
	private String endpointUrl;

	@Schema(description = "httpMethod字段")
	private String httpMethod;

	@Schema(description = "模板字段")
	private String headerTemplate;

	@Schema(description = "类型")
	private String authType;

	@Schema(description = "credentialRef字段")
	private String credentialRef;

	@Schema(description = "paramMapping字段")
	private String paramMapping;

	@Schema(description = "请求模板字段")
	private String requestTemplate;

	@Schema(description = "响应字段")
	private String responseMapping;

	@Schema(description = "是否启用")
	private Boolean enabled;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "displayOrder字段")
	private Integer displayOrder;

	@Schema(description = "配置")
	private String extConfig;

}
