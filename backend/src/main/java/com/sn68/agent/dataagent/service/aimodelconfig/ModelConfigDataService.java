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
package com.sn68.agent.dataagent.service.aimodelconfig;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.ModelConfigPageQueryReq;
import com.sn68.agent.dataagent.dto.ModelConfigSummaryResp;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.entity.ModelConfig;

import java.util.List;

/**
 * 模型配置Data服务契约。
 */
public interface ModelConfigDataService {

	/**
	 * 查询模型配置Data。
	 */
	ModelConfig findById(Long id);

	/**
	 * 处理模型配置Data。
	 */
	void switchActiveStatus(Long id, ModelType type);

	/**
	 * 查询模型配置Data。
	 */
	List<ModelConfigDTO> listConfigs();

	/**
	 * 查询模型配置Data。
	 */
	ModelConfigDTO getConfigById(Long id, ModelType modelType);

	/**
	 * 查询模型配置Data。
	 */
	ModelConfigDTO getRuntimeConfigById(Long id, ModelType modelType);

	/**
	 * 创建模型配置Data。
	 */
	void addConfig(ModelConfigDTO dto);

	/**
	 * 保存模型配置Data。
	 */
	ModelConfig updateConfigInDb(ModelConfigDTO dto);

	/**
	 * 删除模型配置Data。
	 */
	void deleteConfig(Long id);

	/**
	 * 查询模型配置Data。
	 */
	ModelConfigDTO getActiveConfigByType(ModelType modelType);

	/**
	 * 查询当前登录租户启用中的运行时模型；无登录上下文时返回 null，不回落到固定租户。
	 */
	ModelConfigDTO getActiveRuntimeConfigByType(ModelType modelType);

	/**
	 * 查询指定租户启用中的运行时模型。tenantId 为空返回 null。
	 */
	ModelConfigDTO getActiveRuntimeConfigByType(ModelType modelType, String tenantId);

	/**
	 * 按主键加载运行时模型并校验归属租户。tenantId 为空或与行上租户不一致时视为不存在。
	 */
	ModelConfigDTO getRuntimeConfigById(Long id, ModelType modelType, String tenantId);

	/**
	 * 按主键加载脱敏模型并校验归属租户。tenantId 为空或与行上租户不一致时视为不存在。
	 */
	ModelConfigDTO getConfigById(Long id, ModelType modelType, String tenantId);

	/**
	 * 处理模型配置Data。
	 */
	ModelConfigDTO prepareRuntimeConfig(ModelConfigDTO dto);

	/**
	 * 查询模型配置Data。
	 */
	IPage<ModelConfigDTO> queryPage(ModelConfigPageQueryReq request);

	/**
	 * 处理模型配置Data。
	 */
	ModelConfigSummaryResp summary();

}
