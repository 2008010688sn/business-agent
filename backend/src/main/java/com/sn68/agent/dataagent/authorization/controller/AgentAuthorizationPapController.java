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
package com.sn68.agent.dataagent.authorization.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationBindingUpsertReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationDecisionSimulateDbReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationGrantCreateReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyCreateReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyDetailResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyModifyReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyPageQueryReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyVersionResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationTemplateResp;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationBinding;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationGrant;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.service.AgentAuthorizationPapService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 授权中心 PAP 管理端点（PR-3b）。
 *
 * <p>与 PR-3a 决策模拟端点（{@link AgentAuthorizationController}）共用 /agent-authorizations 前缀；
 * 覆盖策略模板/主档 CRUD/版本发布/绑定/授权/DB 决策模拟。租户上下文由认证信息显式注入服务层。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Slf4j
@RestController
@RequestMapping("/agent-authorizations")
@RequiredArgsConstructor
@Tag(name = "Agent 授权中心 PAP", description = "授权策略管理点：策略 CRUD、版本发布、绑定与授权管理、DB 决策模拟（PR-3b）")
public class AgentAuthorizationPapController {

	private final AgentAuthorizationPapService papService;

	private final AuthenticationContext authenticationContext;

	@Operation(summary = "授权模板列表 - [DONE] - [Jay]", description = "列出全部预设授权模板及其默认策略 JSON，可直接作为创建策略的输入。")
	@AccessLog(module = "Agent 授权中心", description = "查询授权模板列表")
	@GetMapping("/templates")
	public List<AuthorizationTemplateResp> listTemplates() {
		return papService.listTemplates();
	}

	@Operation(summary = "创建授权策略 - [DONE] - [Jay]", description = "创建策略主档（DRAFT）与首个草稿版本；策略内容优先取 policyJson（严格校验），缺省按模板生成。")
	@AccessLog(module = "Agent 授权中心", description = "创建授权策略")
	@PostMapping("/policies/create")
	public AuthorizationPolicyDetailResp createPolicy(@Valid @RequestBody AuthorizationPolicyCreateReq request) {
		return papService.createPolicy(request, authenticationContext.tenantId());
	}

	@Operation(summary = "修改授权策略 - [DONE] - [Jay]", description = "仅草稿状态可修改；名称与草稿 JSON 均为可选更新，JSON 经严格校验后覆盖草稿版本。")
	@AccessLog(module = "Agent 授权中心", description = "修改授权策略")
	@PutMapping("/policies/{id}/modify")
	public AuthorizationPolicyDetailResp modifyPolicy(@PathVariable("id") Long id,
			@Valid @RequestBody AuthorizationPolicyModifyReq request) {
		return papService.modifyPolicy(id, request, authenticationContext.tenantId());
	}

	@Operation(summary = "分页查询授权策略 - [DONE] - [Jay]", description = "按编码/名称/状态/模板过滤，分页返回当前租户的策略主档。")
	@AccessLog(module = "Agent 授权中心", description = "分页查询授权策略")
	@PostMapping("/policies/page")
	public IPage<AgentAuthorizationPolicy> pagePolicies(@RequestBody(required = false) AuthorizationPolicyPageQueryReq request) {
		return papService.pagePolicies(request, authenticationContext.tenantId());
	}

	@Operation(summary = "授权策略详情 - [DONE] - [Jay]", description = "主档基础信息 + 当前发布版本内容 + 最新草稿内容。")
	@AccessLog(module = "Agent 授权中心", description = "查询授权策略详情")
	@GetMapping("/policies/{id}/detail")
	public AuthorizationPolicyDetailResp getPolicyDetail(@PathVariable("id") Long id) {
		return papService.getPolicyDetail(id, authenticationContext.tenantId());
	}

	@Operation(summary = "删除授权策略 - [DONE] - [Jay]", description = "逻辑删除策略及其版本行；存在被绑定引用的版本时拒绝删除。")
	@AccessLog(module = "Agent 授权中心", description = "删除授权策略")
	@DeleteMapping("/policies/{id}")
	public void deletePolicy(@PathVariable("id") Long id) {
		papService.deletePolicy(id, authenticationContext.tenantId());
	}

	@Operation(summary = "发布授权策略版本 - [DONE] - [Jay]", description = "校验最新草稿 JSON 与 hash 后发布（CAS 翻转），主档指针切换为 PUBLISHED；已发布版本不可变。")
	@AccessLog(module = "Agent 授权中心", description = "发布授权策略版本")
	@PostMapping("/policies/{id}/versions/publish")
	public AuthorizationPolicyDetailResp publishPolicy(@PathVariable("id") Long id) {
		return papService.publishPolicy(id, authenticationContext.tenantId());
	}

	@Operation(summary = "从当前版本开新草稿 - [DONE] - [Jay]", description = "复制当前发布版本内容为新草稿（主档回到 DRAFT），用于已发布策略的变更流转。")
	@AccessLog(module = "Agent 授权中心", description = "开新草稿版本")
	@PostMapping("/policies/{id}/versions/draft")
	public AuthorizationPolicyDetailResp draftFromCurrent(@PathVariable("id") Long id) {
		return papService.draftFromCurrent(id, authenticationContext.tenantId());
	}

	@Operation(summary = "授权策略版本列表 - [DONE] - [Jay]", description = "按版本号倒序返回策略全部版本（含草稿），不含 JSON 正文。")
	@AccessLog(module = "Agent 授权中心", description = "查询策略版本列表")
	@GetMapping("/policies/{id}/versions")
	public List<AuthorizationPolicyVersionResp> listPolicyVersions(@PathVariable("id") Long id) {
		return papService.listPolicyVersions(id, authenticationContext.tenantId());
	}

	@Operation(summary = "停用授权策略 - [DONE] - [Jay]", description = "PUBLISHED → RETIRED；存量绑定不受影响（运行时以绑定指向的版本为准）。")
	@AccessLog(module = "Agent 授权中心", description = "停用授权策略")
	@PutMapping("/policies/{id}/retire")
	public void retirePolicy(@PathVariable("id") Long id) {
		papService.retirePolicy(id, authenticationContext.tenantId());
	}

	@Operation(summary = "启用授权策略 - [DONE] - [Jay]", description = "RETIRED → PUBLISHED，要求存在已发布版本。")
	@AccessLog(module = "Agent 授权中心", description = "启用授权策略")
	@PutMapping("/policies/{id}/enable")
	public void enablePolicy(@PathVariable("id") Long id) {
		papService.enablePolicy(id, authenticationContext.tenantId());
	}

	@Operation(summary = "策略绑定 upsert - [DONE] - [Jay]", description = "无绑定则创建（bindRevision=0）；更新已有绑定需携带 expectedBindRevision，并发冲突返回当前值。")
	@AccessLog(module = "Agent 授权中心", description = "保存策略绑定")
	@PutMapping("/bindings")
	public AgentAuthorizationBinding upsertBinding(@Valid @RequestBody AuthorizationBindingUpsertReq request) {
		return papService.upsertBinding(request, authenticationContext.tenantId());
	}

	@Operation(summary = "查询策略绑定 - [DONE] - [Jay]", description = "按 owner + 环境查询唯一绑定；无绑定为空。")
	@AccessLog(module = "Agent 授权中心", description = "查询策略绑定")
	@GetMapping("/bindings")
	public AgentAuthorizationBinding getBinding(@RequestParam("ownerType") AuthorizationOwnerType ownerType,
			@RequestParam("ownerId") Long ownerId,
			@RequestParam("environment") AuthorizationEnvironment environment) {
		return papService.getBinding(ownerType, ownerId, environment, authenticationContext.tenantId());
	}

	@Operation(summary = "创建授权记录 - [DONE] - [Jay]", description = "为主体授予 DISCOVER/USE 权限；同主体同权限幂等复用既有生效记录。")
	@AccessLog(module = "Agent 授权中心", description = "创建授权记录")
	@PostMapping("/grants/create")
	public AgentAuthorizationGrant createGrant(@Valid @RequestBody AuthorizationGrantCreateReq request) {
		return papService.createGrant(request, authenticationContext.tenantId());
	}

	@Operation(summary = "授权记录列表 - [DONE] - [Jay]", description = "查询 owner 的授权记录（含已撤销），按创建时间倒序。")
	@AccessLog(module = "Agent 授权中心", description = "查询授权记录列表")
	@GetMapping("/grants")
	public List<AgentAuthorizationGrant> listGrants(@RequestParam("ownerType") AuthorizationOwnerType ownerType,
			@RequestParam("ownerId") Long ownerId) {
		return papService.listGrants(ownerType, ownerId, authenticationContext.tenantId());
	}

	@Operation(summary = "删除授权记录 - [DONE] - [Jay]", description = "逻辑删除授权记录。")
	@AccessLog(module = "Agent 授权中心", description = "删除授权记录")
	@DeleteMapping("/grants/{id}")
	public void deleteGrant(@PathVariable("id") Long id) {
		papService.deleteGrant(id, authenticationContext.tenantId());
	}

	@Operation(summary = "授权决策 DB 模拟 - [DONE] - [Jay]", description = "按 owner + 环境读取绑定的已发布策略版本，复用 PR-3a 冻结求值器求值；无绑定按 MISSING_POLICY 返回。")
	@AccessLog(module = "Agent 授权中心", description = "授权决策 DB 模拟")
	@PostMapping("/decisions/simulate-db")
	public AuthorizationDecisionResp simulateFromDb(@Valid @RequestBody AuthorizationDecisionSimulateDbReq request) {
		return papService.simulateFromDb(request, authenticationContext.tenantId());
	}

}
