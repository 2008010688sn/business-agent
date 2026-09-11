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
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseCreateReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleasePageQueryReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数字员工发布版本接口：DRAFT → SEALED → PUBLISHED → RETIRED 生命周期管理。
 *
 * <p>Seal 冻结能力清单进 snapshot 并计算 spec_hash（CAS）；
 * Publish/Retire 均为 CAS 状态迁移，Retire 前校验任务与部署引用。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/digital-employee-releases")
@Tag(name = "数字员工发布", description = "数字员工发布版本生命周期（Seal 冻结快照 / Publish / Retire）")
public class DigitalEmployeeReleaseController {

	private final EmployeeReleaseLifecycleService releaseLifecycleService;

	@Operation(summary = "分页查询发布版本", description = "按员工与状态分页查询当前租户的数字员工发布版本。")
	@PostMapping("/page")
	public IPage<DigitalEmployeeRelease> queryPage(@RequestBody(required = false) EmployeeReleasePageQueryReq request) {
		EmployeeReleasePageQueryReq query = request == null ? new EmployeeReleasePageQueryReq() : request;
		return releaseLifecycleService.queryPage(query.getEmployeeId(), query.getStatus(), query);
	}

	@Operation(summary = "创建发布草稿", description = "从员工当前草稿（或指定 base Release）创建 DRAFT 发布版本。")
	@AccessLog(module = "数字员工发布", description = "创建发布草稿")
	@PostMapping("/{employeeId}/create")
	public Long createDraft(@PathVariable Long employeeId,
			@RequestBody(required = false) EmployeeReleaseCreateReq request) {
		return releaseLifecycleService.createDraft(employeeId, request);
	}

	@Operation(summary = "查询发布版本详情", description = "查询发布版本（含冻结快照与 spec_hash）。")
	@GetMapping("/{releaseId}/detail")
	public DigitalEmployeeRelease getDetail(@PathVariable Long releaseId) {
		return releaseLifecycleService.getDetail(releaseId);
	}

	@Operation(summary = "封版", description = "以 Seal 时刻员工草稿 + 启用中能力清单重新装配快照并冻结（DRAFT → SEALED，CAS）。")
	@AccessLog(module = "数字员工发布", description = "封版发布")
	@PostMapping("/{releaseId}/seal")
	public void seal(@PathVariable Long releaseId) {
		releaseLifecycleService.seal(releaseId);
	}

	@Operation(summary = "发布", description = "SEALED → PUBLISHED（CAS）；仅已发布版本可部署。")
	@AccessLog(module = "数字员工发布", description = "发布版本")
	@PostMapping("/{releaseId}/publish")
	public void publish(@PathVariable Long releaseId) {
		releaseLifecycleService.publish(releaseId);
	}

	@Operation(summary = "退役", description = "PUBLISHED → RETIRED（CAS）；仍被任务定义或员工部署引用时拒绝。")
	@AccessLog(module = "数字员工发布", description = "退役发布版本")
	@PostMapping("/{releaseId}/retire")
	public void retire(@PathVariable Long releaseId) {
		releaseLifecycleService.retire(releaseId);
	}

}
