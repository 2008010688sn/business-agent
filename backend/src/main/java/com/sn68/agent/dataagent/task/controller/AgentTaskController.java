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
package com.sn68.agent.dataagent.task.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.task.dto.AgentTaskApiSecretResp;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionModifyReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionSaveReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskDetailResp;
import com.sn68.agent.dataagent.task.dto.AgentTaskPageQueryReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskRunDetailResp;
import com.sn68.agent.dataagent.task.dto.AgentTaskRunPageQueryReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskTriggerModifyReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskTriggerSaveReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.service.AgentTaskDefinitionService;
import com.sn68.agent.dataagent.task.service.AgentTaskRunQueryService;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.dataagent.task.service.AgentTaskTriggerService;
import com.sn68.agent.dataagent.task.service.ApiTriggerCommand;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 任务定义、触发器与运行记录管理接口。
 */
@RestController
@RequestMapping("/agent-tasks")
@RequiredArgsConstructor
@Tag(name = "Agent任务", description = "维护数字员工任务定义、触发器与任务运行")
public class AgentTaskController {

	private final AgentTaskDefinitionService definitionService;

	private final AgentTaskTriggerService triggerService;

	private final AgentTaskRunService taskRunService;

	private final AgentTaskRunQueryService taskRunQueryService;

	@Operation(summary = "分页查询任务定义", description = "分页查询任务定义，用于Agent任务相关管理和运行场景。")
	@PostMapping("/page")
	public IPage<AgentTaskDefinition> page(@RequestBody(required = false) AgentTaskPageQueryReq request) {
		return definitionService.pageByTenant(request);
	}

	@Operation(summary = "创建任务定义", description = "创建任务定义并生成首个不可变版本。")
	@AccessLog(module = "Agent任务", description = "创建任务定义")
	@PostMapping("/create")
	public void create(@RequestBody @Validated AgentTaskDefinitionSaveReq request) {
		definitionService.create(request);
	}

	@Operation(summary = "修改任务定义", description = "修改任务定义，传入参数/提示词快照时生成新版本。")
	@AccessLog(module = "Agent任务", description = "修改任务定义")
	@PutMapping("/{id}/modify")
	public void modify(@PathVariable Long id, @RequestBody @Validated AgentTaskDefinitionModifyReq request) {
		definitionService.modify(id, request);
	}

	@Operation(summary = "删除任务定义", description = "删除任务定义并连带删除其触发器。")
	@AccessLog(module = "Agent任务", description = "删除任务定义")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		definitionService.delete(id);
	}

	@Operation(summary = "查询任务定义详情", description = "查询任务定义详情（含最新版本与触发器）。")
	@GetMapping("/{id}/detail")
	public AgentTaskDetailResp detail(@PathVariable Long id) {
		return definitionService.detail(id);
	}

	@Operation(summary = "查询任务触发器列表", description = "查询任务定义下的触发器列表。")
	@GetMapping("/{id}/triggers")
	public List<AgentTaskTrigger> listTriggers(@PathVariable Long id) {
		return triggerService.listByDefinition(id);
	}

	@Operation(summary = "创建任务触发器", description = "为任务定义创建触发器（CHAT/SCHEDULE/EVENT/API/IM）。")
	@AccessLog(module = "Agent任务", description = "创建任务触发器")
	@PostMapping("/{id}/triggers/create")
	public void createTrigger(@PathVariable Long id, @RequestBody @Validated AgentTaskTriggerSaveReq request) {
		triggerService.createTrigger(id, request);
	}

	@Operation(summary = "修改任务触发器", description = "修改任务触发器（触发类型不可变更）。")
	@AccessLog(module = "Agent任务", description = "修改任务触发器")
	@PutMapping("/{id}/triggers/{triggerId}/modify")
	public void modifyTrigger(@PathVariable Long id, @PathVariable Long triggerId,
			@RequestBody @Validated AgentTaskTriggerModifyReq request) {
		triggerService.modifyTrigger(id, triggerId, request);
	}

	@Operation(summary = "删除任务触发器", description = "删除任务触发器。")
	@AccessLog(module = "Agent任务", description = "删除任务触发器")
	@DeleteMapping("/{id}/triggers/{triggerId}")
	public void deleteTrigger(@PathVariable Long id, @PathVariable Long triggerId) {
		triggerService.deleteTrigger(id, triggerId);
	}

	@Operation(summary = "查询任务运行结果",
			description = "按任务运行ID聚合台账状态、最终回答、步骤与产物。权限码 task:query，不改 runtime-runs 权限域。")
	@GetMapping("/runs/{taskRunId}/detail")
	public AgentTaskRunDetailResp getRunDetail(@PathVariable Long taskRunId) {
		return taskRunQueryService.findDetail(taskRunId);
	}

	@Operation(summary = "取消任务运行", description = "取消占用中的任务运行并释放 FORBID 并发槽。已挂 Runtime 时同步请求运行时取消。")
	@AccessLog(module = "Agent任务", description = "取消任务运行")
	@PostMapping("/runs/{taskRunId}/cancel")
	public AgentTaskRun cancelRun(@PathVariable Long taskRunId) {
		return taskRunService.cancel(taskRunId, "用户取消占用");
	}

	@Operation(summary = "分页查询任务运行", description = "分页查询任务定义下的运行记录。")
	@PostMapping("/{id}/runs/page")
	public IPage<AgentTaskRun> pageRuns(@PathVariable Long id,
			@RequestBody(required = false) AgentTaskRunPageQueryReq request) {
		// 归属校验（跨租户按不存在处理），再按定义查询运行。
		definitionService.requireOwned(id);
		return taskRunService.pageByDefinition(id, request);
	}

	@Operation(summary = "API 触发任务运行", description = "外部系统按 API 触发器发起任务运行，Idempotency-Key 请求头必填（重放返回已有运行）；"
			+ "同时必须携带开放签名头：X-Timestamp（epoch 毫秒，±5 分钟窗口）、X-Nonce（一次性随机串）、"
			+ "X-Signature=HMAC-SHA256(secret, timestamp + \"\\n\" + nonce + \"\\n\" + 请求体原文) 小写 hex。")
	// request = false：签名头是方法入参，@AccessLog 默认会把入参整体序列化落库，签名值不得进日志。
	@AccessLog(module = "Agent任务", description = "API 触发任务运行", request = false)
	// 本端点本就依赖登录态解析租户（requireOwned -> authenticationContext.tenantId），
	// 故对接账号需被授予该权限码；HMAC 签名是叠加的第二道防线，不替代权限校验。
	@PostMapping("/{id}/triggers/{triggerId}/runs/create")
	public AgentTaskRun apiTrigger(@PathVariable Long id, @PathVariable Long triggerId,
			@RequestHeader(name = TaskConstants.HEADER_IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@RequestHeader(name = TaskConstants.HEADER_TIMESTAMP, required = false) String timestamp,
			@RequestHeader(name = TaskConstants.HEADER_NONCE, required = false) String nonce,
			@RequestHeader(name = TaskConstants.HEADER_SIGNATURE, required = false) String signature,
			@RequestBody(required = false) String rawBody) {
		// 请求体以原文接入：签名覆盖请求体原始字节，业务参数在验签通过后再反序列化。
		return triggerService.apiTrigger(id, triggerId,
				new ApiTriggerCommand(idempotencyKey, timestamp, nonce, signature, rawBody));
	}

	@Operation(summary = "控制台立即执行一次",
			description = "登录用户按权限立即拉起一次运行（SCHEDULE/EVENT/API）。不校验 HMAC，不推进定时器下次执行时间。")
	@AccessLog(module = "Agent任务", description = "控制台立即执行任务")
	@PostMapping("/{id}/triggers/{triggerId}/runs/manual")
	public AgentTaskRun manualTrigger(@PathVariable Long id, @PathVariable Long triggerId) {
		return triggerService.manualTrigger(id, triggerId);
	}

	@Operation(summary = "生成/轮换 API 触发签名密钥", description = "为 API 触发器生成新的 HMAC-SHA256 签名密钥，旧密钥即刻失效；明文密钥仅本次返回，请妥善保存。")
	// response = false：响应体是明文密钥，@AccessLog 默认会把返回值序列化落库，密钥不得进日志。
	@AccessLog(module = "Agent任务", description = "生成/轮换 API 触发签名密钥", response = false)
	@PostMapping("/{id}/triggers/{triggerId}/api-secret/rotate")
	public AgentTaskApiSecretResp rotateApiSecret(@PathVariable Long id, @PathVariable Long triggerId) {
		return triggerService.rotateApiSecret(id, triggerId);
	}

}
