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
package com.sn68.agent.dataagent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.ModelConfigPageQueryReq;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.ModelConfigSummaryResp;
import com.sn68.agent.dataagent.dto.ModelConfigStatusModifyReq;
import com.sn68.agent.dataagent.dto.ModelCapabilityDescriptorResp;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigOpsService;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsResolver;
import com.sn68.agent.dataagent.vo.ModelCheckVO;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 维护大模型连接配置、启停状态和就绪检查。
 */
@AllArgsConstructor
@RestController
@RequestMapping("/model-config")
@Tag(name = "模型配置", description = "维护大模型连接配置、启停状态和就绪检查")
public class ModelConfigController {

	private final ModelConfigDataService modelConfigDataService;

	private final ModelConfigOpsService modelConfigOpsService;

	private final ModelRequestOptionsResolver modelRequestOptionsResolver;

	@Operation(summary = "查询模型能力描述", description = "查询模型端点与能力 Profile 的可持久化参数组合。")
	@GetMapping("/capabilities")
	public List<ModelCapabilityDescriptorResp> capabilities() {
		return modelRequestOptionsResolver.capabilityDescriptors();
	}

	@Operation(summary = "查询模型配置清单", description = "查询模型配置清单，用于模型配置相关管理和运行场景。")
	@PostMapping("/query")
	public List<ModelConfigDTO> list() {
		return modelConfigDataService.listConfigs();
	}

	@Operation(summary = "分页查询模型配置", description = "分页查询模型配置，用于模型配置相关管理和运行场景。")
	@PostMapping("/page")
	public IPage<ModelConfigDTO> page(@RequestBody(required = false) ModelConfigPageQueryReq request) {
		return modelConfigDataService.queryPage(request);
	}

	@Operation(summary = "查询模型配置汇总", description = "查询模型配置汇总，用于模型配置相关管理和运行场景。")
	@PostMapping("/summary")
	public ModelConfigSummaryResp summary() {
		return modelConfigDataService.summary();
	}

	@Operation(summary = "创建模型配置", description = "创建模型配置，用于模型配置相关管理和运行场景。")
	// 请求体携带明文 apiKey，关闭入参记录
	@AccessLog(module = "模型配置", description = "创建模型配置", request = false)
	@PostMapping("/create")
	public void add(@Valid @RequestBody ModelConfigDTO config) {
		modelConfigDataService.addConfig(config);
	}

	@Operation(summary = "修改模型配置", description = "修改模型配置，用于模型配置相关管理和运行场景。")
	// 请求体携带明文 apiKey，关闭入参记录
	@AccessLog(module = "模型配置", description = "修改模型配置", request = false)
	@PutMapping("/{id}/modify")
	public void update(@PathVariable Long id, @Valid @RequestBody ModelConfigDTO config) {
		config.setId(id);
		modelConfigOpsService.updateAndRefresh(config);
	}

	@Operation(summary = "删除模型配置", description = "删除模型配置，用于模型配置相关管理和运行场景。")
	@AccessLog(module = "模型配置", description = "删除模型配置")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		modelConfigDataService.deleteConfig(id);
	}

	@Operation(summary = "修改模型配置状态", description = "修改模型配置状态，用于模型配置相关管理和运行场景。")
	@AccessLog(module = "模型配置", description = "启用模型配置")
	@PutMapping("/{id}/status")
	public void activate(@PathVariable Long id, @RequestBody ModelConfigStatusModifyReq request) {
		if (request == null || !"active".equals(request.status())) {
			throw CheckedException.badRequest("模型配置状态不合法");
		}
		modelConfigOpsService.activateConfig(id);
	}

	@Operation(summary = "测试模型配置", description = "测试模型配置，用于模型配置相关管理和运行场景。")
	@PostMapping("/test")
	public void testConnection(@Valid @RequestBody ModelConfigDTO config) {
		modelConfigOpsService.testConnection(config);
	}

	@Operation(summary = "查询模型配置", description = "查询模型配置，用于模型配置相关管理和运行场景。")
	@GetMapping("/check-ready")
	public ModelCheckVO checkReady() {
		return modelConfigOpsService.checkReady();
	}

}
