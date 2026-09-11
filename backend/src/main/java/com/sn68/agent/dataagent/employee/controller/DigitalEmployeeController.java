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
package com.sn68.agent.dataagent.employee.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.dto.agent.RuntimeChatModelDTO;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeCreateReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeModifyReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeOptionResp;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeePageQueryReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeResp;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeStatusUpdateReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeCapabilityBindReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeCapabilityEnabledReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeModelConfigItemResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeDigestResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeJobTemplateResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunDetailResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunFeedbackReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunFeedbackResp;
import com.sn68.agent.dataagent.employee.dto.UpdateEmployeeModelConfigReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.service.DigitalEmployeeService;
import com.sn68.agent.dataagent.employee.service.EmployeeConversationService;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.employee.service.EmployeeModelConfigService;
import com.sn68.agent.dataagent.employee.service.EmployeePrincipalService;
import com.sn68.agent.dataagent.employee.service.DigitalEmployeeDigestService;
import com.sn68.agent.dataagent.employee.service.EmployeeJobTemplateService;
import com.sn68.agent.dataagent.employee.service.EmployeeRunFeedbackService;
import com.sn68.agent.dataagent.employee.service.EmployeeRunQueryService;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetReportResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.market.service.MarketInstallService;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalAuthSnapshotResp;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRoleResp;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRolesReplaceReq;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalStatusReq;
import com.sn68.agent.framework.commons.annotation.IgnoreGlobalResponse;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 数字员工管理接口：档案 CRUD、启用/停用/封存（state_version CAS）、Principal 开通重试、
 * 能力绑定、环境部署（deployment_version CAS）、运行履历与对话 Facade。
 *
 * <p>权限三档：只读 {@code ai-agent:digital-employee:query}；
 * 对话 {@code ai-agent:digital-employee:use}；管理（含部署）{@code ai-agent:digital-employee:manage}。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/digital-employees")
@Tag(name = "数字员工", description = "数字员工身份档案、发布部署与对话管理")
public class DigitalEmployeeController {

	private final DigitalEmployeeService employeeService;

	private final EmployeeDeploymentService deploymentService;

	private final EmployeeConversationService conversationService;

	private final EmployeePrincipalService principalService;

	private final EmployeeModelConfigService employeeModelConfigService;

	private final MarketInstallService marketInstallService;

	private final EmployeeRunQueryService employeeRunQueryService;

	private final EmployeeRunFeedbackService employeeRunFeedbackService;

	private final DigitalEmployeeDigestService employeeDigestService;

	private final EmployeeJobTemplateService jobTemplateService;

	@Operation(summary = "分页查询数字员工", description = "按状态/名称关键字分页查询当前租户的数字员工。")
	@PostMapping("/page")
	public IPage<DigitalEmployeeResp> queryPage(@RequestBody(required = false) DigitalEmployeePageQueryReq request) {
		return employeeService.queryPage(request);
	}

	@Operation(summary = "数字员工选择器", description = "返回当前租户员工摘要（最多 100 条），供权限中心/任务筛选；避免手填雪花 ID。")
	@GetMapping("/options")
	public List<DigitalEmployeeOptionResp> listOptions(@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String status) {
		return employeeService.listOptions(keyword, status);
	}

	@Operation(summary = "岗位模板清单", description = "创建向导预填；当前仅 OPS_ANALYST。不绑定未发布技能、不在草稿期创建任务。")
	@GetMapping("/templates")
	public List<EmployeeJobTemplateResp> listTemplates() {
		return jobTemplateService.list();
	}

	@Operation(summary = "按 Principal 反查员工", description = "按 iamPrincipalId（sp_ 前缀）查询当前租户员工档案。")
	@GetMapping("/by-principal/{principalId}")
	public DigitalEmployeeResp findByPrincipalId(@PathVariable String principalId) {
		return employeeService.findByPrincipalId(principalId);
	}

	@Operation(summary = "创建数字员工", description = "创建 DRAFT 员工；rollout 未开启时不调用 IAM，principal_status 留 PENDING。返回档案（id 为字符串）。")
	@AccessLog(module = "数字员工", description = "创建数字员工")
	@PostMapping("/create")
	public DigitalEmployeeResp create(@Valid @RequestBody DigitalEmployeeCreateReq request) {
		return employeeService.create(request);
	}

	@Operation(summary = "查询数字员工详情", description = "查询员工档案（含 Principal 状态、state_version 与 rolloutEnabled）。")
	@GetMapping("/{id}/detail")
	public DigitalEmployeeResp getDetail(@PathVariable Long id) {
		return employeeService.getDetail(id);
	}

	@Operation(summary = "修改数字员工", description = "修改草稿/停用/启用态员工（null 字段不更新，draft_revision +1）；已封存不可改，启用态改的是草稿，须再发布才进生产。")
	@AccessLog(module = "数字员工", description = "修改数字员工")
	@PutMapping("/{id}/modify")
	public void modify(@PathVariable Long id, @Valid @RequestBody DigitalEmployeeModifyReq request) {
		employeeService.modify(id, request);
	}

	@Operation(summary = "删除数字员工", description = "仅 DRAFT 且无发布版本记录时可删除（软删，保留审计）。")
	@AccessLog(module = "数字员工", description = "删除数字员工")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		employeeService.delete(id);
	}

	@Operation(summary = "封存数字员工", description = "DISABLED → ARCHIVED（state_version CAS）；启用中须先停用。")
	@AccessLog(module = "数字员工", description = "封存数字员工")
	@PostMapping("/{id}/archive")
	public void archive(@PathVariable Long id, @Valid @RequestBody DigitalEmployeeStatusUpdateReq request) {
		employeeService.archive(id, request.getStateVersion());
	}

	@Operation(summary = "启用数字员工", description = "须已有生产 PUBLISHED 快照；rollout 开启 + Principal READY 前置校验，state_version CAS。")
	@AccessLog(module = "数字员工", description = "启用数字员工")
	@PostMapping("/{id}/enable")
	public void enable(@PathVariable Long id, @Valid @RequestBody DigitalEmployeeStatusUpdateReq request) {
		employeeService.enable(id, request.getStateVersion());
	}

	@Operation(summary = "停用数字员工", description = "ENABLED → DISABLED（state_version CAS）。")
	@AccessLog(module = "数字员工", description = "停用数字员工")
	@PostMapping("/{id}/disable")
	public void disable(@PathVariable Long id, @Valid @RequestBody DigitalEmployeeStatusUpdateReq request) {
		employeeService.disable(id, request.getStateVersion());
	}

	@Operation(summary = "手动触发 Principal 开通", description = "rollout 开启后管理员重试入口；未就绪或灰度关闭时返回业务错误。")
	@AccessLog(module = "数字员工", description = "触发 Principal 开通")
	@PostMapping("/{id}/principal/provision")
	public void provisionPrincipal(@PathVariable Long id) {
		employeeService.provisionPrincipal(id);
	}

	@Operation(summary = "发布当前配置", description = "编排 Seal + Publish + 激活 PRODUCTION；未发布不能启用。")
	@AccessLog(module = "数字员工", description = "发布当前配置")
	@PostMapping("/{id}/publish-current-config")
	public void publishCurrentConfig(@PathVariable Long id) {
		employeeService.publishCurrentConfig(id);
	}

	@Operation(summary = "查询员工 Principal 角色", description = "回显当前绑定角色（含停用/不可分配项）；无 Principal 时返回空列表。浏览器不直连 IAM。")
	@GetMapping("/{id}/principal/roles")
	public List<ServicePrincipalRoleResp> listPrincipalRoles(@PathVariable Long id) {
		return principalService.listRoles(id);
	}

	@Operation(summary = "替换员工 Principal 角色", description = "全量替换；空数组=MODEL_ONLY。权限码 ai-agent:digital-employee:manage，由本服务转发 IAM internal。")
	@AccessLog(module = "数字员工", description = "替换 Principal 角色")
	@PutMapping("/{id}/principal/roles")
	public void replacePrincipalRoles(@PathVariable Long id, @RequestBody(required = false) ServicePrincipalRolesReplaceReq request) {
		principalService.replaceRoles(id, request);
	}

	@Operation(summary = "变更员工 Principal 状态", description = "ENABLED/DISABLED。权限码 ai-agent:digital-employee:manage，由本服务转发 IAM internal。")
	@AccessLog(module = "数字员工", description = "变更 Principal 状态")
	@PutMapping("/{id}/principal/status")
	public void updatePrincipalStatus(@PathVariable Long id, @Valid @RequestBody ServicePrincipalStatusReq request) {
		principalService.updateStatus(id, request);
	}

	@Operation(summary = "预览员工 Principal 有效权限", description = "返回套餐过滤后的功能权限码与 authRevision；禁止前端调用 IAM /internal。")
	@GetMapping("/{id}/principal/auth-preview")
	public ServicePrincipalAuthSnapshotResp previewPrincipalAuth(@PathVariable Long id) {
		return principalService.previewAuth(id);
	}

	@Operation(summary = "绑定能力", description = "为员工绑定已发布 Skill 版本（幂等：重复绑定恢复启用）。")
	@AccessLog(module = "数字员工", description = "绑定能力")
	@PostMapping("/{id}/capabilities/bind")
	public Long bindCapability(@PathVariable Long id, @Valid @RequestBody EmployeeCapabilityBindReq request) {
		return employeeService.bindCapability(id, request);
	}

	@Operation(summary = "解绑能力", description = "解绑员工能力（停用绑定记录）。")
	@AccessLog(module = "数字员工", description = "解绑能力")
	@DeleteMapping("/{id}/capabilities/{capabilityId}")
	public void unbindCapability(@PathVariable Long id, @PathVariable Long capabilityId) {
		employeeService.unbindCapability(id, capabilityId);
	}

	@Operation(summary = "启停员工能力", description = "启用或停用员工能力绑定；修改草稿，已发布快照不受影响。")
	@AccessLog(module = "数字员工", description = "启停员工能力")
	@PutMapping("/{id}/capabilities/{capabilityId}/enabled")
	public void updateCapabilityEnabled(@PathVariable Long id, @PathVariable Long capabilityId,
			@Valid @RequestBody EmployeeCapabilityEnabledReq request) {
		employeeService.updateCapabilityEnabled(id, capabilityId, request.enabled());
	}

	@Operation(summary = "查询员工能力清单", description = "查询员工全部能力绑定（含停用）。")
	@GetMapping("/{id}/capabilities")
	public List<DigitalEmployeeCapability> listCapabilities(@PathVariable Long id) {
		return employeeService.listCapabilities(id);
	}

	@Operation(summary = "查询员工可用模型", description = "查询员工维度可用/默认对话模型，不复用 agent_model_config。")
	@GetMapping("/{id}/model-configs")
	public List<EmployeeModelConfigItemResp> listModelConfigs(@PathVariable Long id) {
		return employeeModelConfigService.listModelConfigs(id);
	}

	@Operation(summary = "保存员工可用模型", description = "全量替换员工可用模型并写回默认模型；ENABLED 改草稿，须再发布才进生产。")
	@AccessLog(module = "数字员工", description = "保存员工可用模型")
	@PutMapping("/{id}/model-configs")
	public List<EmployeeModelConfigItemResp> updateModelConfigs(@PathVariable Long id,
			@RequestBody(required = false) UpdateEmployeeModelConfigReq request) {
		return employeeModelConfigService.updateModelConfigs(id, request);
	}

	@Operation(summary = "查询员工运行时对话模型", description = "对话页下拉：启用且用户可选的 CHAT 模型。")
	@GetMapping("/{id}/runtime-chat-models")
	public List<RuntimeChatModelDTO> listRuntimeChatModels(@PathVariable Long id) {
		return employeeModelConfigService.listRuntimeChatModels(id);
	}

	@Operation(summary = "激活部署", description = "将指定 PUBLISHED Release 激活到目标环境（deployment_version CAS 防并发重复部署）。")
	@AccessLog(module = "数字员工", description = "激活部署")
	@PostMapping("/{id}/deployments/activate")
	public void activateDeployment(@PathVariable Long id, @Valid @RequestBody EmployeeDeploymentActivateReq request) {
		deploymentService.activate(id, request);
	}

	@Operation(summary = "回滚部署", description = "回滚到 previousReleaseId 指向版本（deployment_version CAS；无历史版本时拒绝）。")
	@AccessLog(module = "数字员工", description = "回滚部署")
	@PostMapping("/{id}/deployments/rollback")
	public void rollbackDeployment(@PathVariable Long id, @RequestParam String environment,
			@RequestParam Integer expectVersion) {
		deploymentService.rollback(id, environment, expectVersion);
	}

	@Operation(summary = "查询当前部署", description = "查询员工在指定环境的当前部署行（未初始化时返回 null）。")
	@GetMapping("/{id}/deployments/current")
	public DigitalEmployeeDeployment findCurrentDeployment(@PathVariable Long id,
			@RequestParam(defaultValue = "PRODUCTION") String environment) {
		return deploymentService.findCurrent(id, environment);
	}

	@Operation(summary = "查询员工全部部署", description = "查询员工全部环境的部署行（详情页展示）。")
	@GetMapping("/{id}/deployments")
	public List<DigitalEmployeeDeployment> listDeployments(@PathVariable Long id) {
		return deploymentService.findByEmployee(id);
	}

	@Operation(summary = "分页查询员工运行履历",
			description = "按路径员工ID强制过滤；请求体 digitalEmployeeId/ownerId/ownerType/agentId 不生效。权限码 query。")
	@PostMapping("/{id}/runs/page")
	public IPage<RuntimeRunResp> pageRuns(@PathVariable Long id,
			@RequestBody(required = false) RuntimeRunPageQueryReq request) {
		return employeeRunQueryService.pageRuns(id, request);
	}

	@Operation(summary = "查询员工运行详情", description = "返回最终回答、步骤与产物摘要；跨员工按不存在处理。权限码 query。")
	@GetMapping("/{id}/runs/{runtimeRunId}/detail")
	public EmployeeRunDetailResp getRunDetail(@PathVariable Long id, @PathVariable Long runtimeRunId) {
		return employeeRunQueryService.getRunDetail(id, runtimeRunId);
	}

	@Operation(summary = "提交运行反馈", description = "UP/DOWN，同一用户覆盖写。权限码 use。不自动改 prompt。")
	@AccessLog(module = "数字员工", description = "提交运行反馈")
	@PostMapping("/{id}/runs/{runtimeRunId}/feedback")
	public EmployeeRunFeedbackResp saveRunFeedback(@PathVariable Long id, @PathVariable Long runtimeRunId,
			@Valid @RequestBody EmployeeRunFeedbackReq request) {
		return employeeRunFeedbackService.save(id, runtimeRunId, request);
	}

	@Operation(summary = "查询最近一次每日汇总", description = "无汇总时返回 null。权限码 query。")
	@GetMapping("/{id}/digests/latest")
	public EmployeeDigestResp latestDigest(@PathVariable Long id) {
		return employeeDigestService.findLatest(id);
	}

	@Operation(summary = "查询本员工运行成本",
			description = "汇总该员工预算流水（工具次数、耗时等）。路径 id 定归属，不计费。默认近 7 天，最长 93 天。权限码 query。")
	@GetMapping("/{id}/budget-report")
	public RuntimeBudgetReportResp budgetReport(@PathVariable Long id,
			@RequestParam(name = "fromTime", required = false) Instant fromTime,
			@RequestParam(name = "toTime", required = false) Instant toTime) {
		return employeeRunQueryService.budgetReport(id, fromTime, toTime);
	}

	@Operation(summary = "数字员工对话", description = "同步单轮对话；rollout 未开启走默认 Guard 放行纯模型对话（MODEL_ONLY），"
			+ "rollout 开启且 Principal READY 时以员工身份委托执行（PRINCIPAL）。")
	@AccessLog(module = "数字员工", description = "数字员工对话")
	@PostMapping("/{id}/conversations")
	public EmployeeConversationResp converse(@PathVariable Long id,
			@Valid @RequestBody EmployeeConversationReq request) {
		return conversationService.converse(id, request);
	}

	@Operation(summary = "数字员工流式对话", description = "SSE 复用现网 AgentResponse 事件（message/complete/error/runtime_progress）；"
			+ "身份与会话由 Facade 钉死，禁止改走 /stream/search。权限码 use。")
	@IgnoreGlobalResponse(description = "数字员工流式对话")
	@PostMapping(value = "/{id}/conversations/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<AgentResponse>> converseStream(@PathVariable Long id,
			@Valid @RequestBody EmployeeConversationReq request) {
		return conversationService.converseStream(id, request);
	}

	@Operation(summary = "安装市场技能", description = "安装技能市场的已发布技能到数字员工（校验 Listing 状态为 APPROVED 且 versionNo 指向 PUBLISHED 的 SkillVersion）。")
	@AccessLog(module = "数字员工", description = "安装市场技能")
	@PostMapping("/{id}/install-market-skill/{listingId}/{versionNo}")
	public Long installMarketSkill(@PathVariable Long id, @PathVariable Long listingId, @PathVariable Integer versionNo) {
		return marketInstallService.install(listingId, versionNo, id).installationId();
	}

}
