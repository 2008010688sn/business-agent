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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;

/**
 * 数据源实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("datasource")
@Schema(description = "DataAgent数据源")
public class Datasource extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "数据源名称")
	private String name;

	@Schema(description = "数据源类型")
	private String type;

	@Schema(description = "主机地址")
	private String host;

	@Schema(description = "端口")
	private Integer port;

	@Schema(description = "数据库名称")
	private String databaseName;

	/**
	 * 数据库账号属敏感字段，只接受入参不对外回显。对外契约 {@code DatasourceResp.username} 走掩码，
	 * 但那只覆盖了以 Resp 为返回类型的端点；直接返回实体（{@code SkillDatasourceController.candidates}）
	 * 与嵌套返回（{@code SkillDatasource.datasource}）会绕过掩码回显真实账号，故与 password、connectionUrl
	 * 一样在实体层堵死全部序列化路径。
	 */
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@Schema(description = "用户名")
	private String username;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@Schema(description = "密码")
	private String password;

	@TableField(exist = false)
	@Schema(description = "密码是否已配置")
	private Boolean passwordConfigured;

	/**
	 * 连接串含库名与账号信息，属敏感字段，只接受入参不对外回显（配置状态见 {@code connectionUrlConfigured}）。
	 * 用注解而非在转换方法里置空，是为了覆盖直接返回实体与嵌套返回（如 SkillDatasource.datasource）的全部序列化路径。
	 */
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@Schema(description = "连接URL")
	private String connectionUrl;

	@TableField(exist = false)
	@Schema(description = "连接URL是否已配置")
	private Boolean connectionUrlConfigured;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "连接测试状态")
	private String testStatus;

	@Schema(description = "描述")
	private String description;

	@Schema(description = "创建人ID")
	private Long creatorId;

}
