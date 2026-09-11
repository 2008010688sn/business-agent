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

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.repository.typehandler.JsonbMapTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;
import java.util.Map;

/**
 * DataAgent实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_agent", autoResultMap = true)
@Schema(description = "DataAgent智能体")
public class DataAgent extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "智能体名称")
	private String name;

	@Schema(description = "智能体描述")
	private String description;

	@Schema(description = "头像地址")
	private String avatar;

	@TableField(exist = false)
	@Schema(description = "头像预览地址")
	private String avatarPreviewUrl;

	@Schema(description = "智能体模板类型")
	private String agentType;

	@Schema(description = "状态：draft-待发布，published-已发布，offline-已下线")
	private String status;

	@JsonIgnore
	@Schema(description = "外部访问API Key")
	private String apiKey;

	@Schema(description = "API Key是否启用")
	private Boolean apiKeyEnabled;

	@Schema(description = "自定义提示词配置")
	private String prompt;

	@Schema(description = "智能体分类")
	private String category;

	@Schema(description = "管理员ID")
	private Long adminId;

	@Schema(description = "标签，多个用逗号分隔")
	private String tags;

	@Schema(description = "总运行超时，单位秒")
	private Integer runtimeTimeoutSeconds;

	@Schema(description = "ReAct最大循环轮数，空值表示继承平台配置")
	private Integer reactMaxIterations;

	@Schema(description = "单次运行最大模型调用次数，空值表示继承平台配置")
	private Integer maxModelCalls;

	@Schema(description = "单次运行最大工具调用次数，空值表示继承平台配置")
	private Integer maxToolCalls;

	@Schema(description = "单次运行最大Prompt Token数，空值表示继承平台配置")
	private Long maxPromptTokens;

	@Schema(description = "对话模型配置ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long chatModelConfigId;

	@TableField(typeHandler = JsonbMapTypeHandler.class)
	@Schema(description = "时间解释策略")
	private Map<String, Object> temporalPolicy;

}
