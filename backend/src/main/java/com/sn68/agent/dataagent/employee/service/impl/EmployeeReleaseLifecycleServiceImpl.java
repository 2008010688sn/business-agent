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
package com.sn68.agent.dataagent.employee.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseCreateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeCapabilityMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotAssembler;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 数字员工发布生命周期实现（Seal/Publish/Retire 均为 CAS 条件更新，0 行即并发冲突）。
 * 框架租户拦截器为白名单制，本服务所有查询显式携带 tenant_id 条件作为租户防线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeReleaseLifecycleServiceImpl implements EmployeeReleaseLifecycleService {

	private static final String SOURCE_TYPE_DRAFT = "DRAFT";

	private static final String SOURCE_TYPE_BASE_RELEASE = "BASE_RELEASE";

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeReleaseMapper releaseMapper;

	private final DigitalEmployeeCapabilityMapper capabilityMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final DigitalEmployeeDeploymentMapper deploymentMapper;

	private final AgentTaskDefinitionMapper taskDefinitionMapper;

	private final EmployeeReleaseSnapshotAssembler snapshotAssembler;

	private final AuthenticationContext authenticationContext;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Long createDraft(Long employeeId, EmployeeReleaseCreateReq request) {
		if (employeeId == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		String tenantId = currentTenantId();
		DigitalEmployee employee = requireEmployee(employeeId, tenantId);
		Integer maxReleaseNo = releaseMapper.findMaxReleaseNo(employeeId, tenantId);
		int releaseNo = maxReleaseNo == null ? 1 : maxReleaseNo + 1;

		String snapshot;
		Long baseReleaseId = request == null ? null : request.getBaseReleaseId();
		String sourceType = SOURCE_TYPE_DRAFT;
		if (baseReleaseId != null) {
			DigitalEmployeeRelease base = requireRelease(baseReleaseId, tenantId);
			if (!employeeId.equals(base.getEmployeeId())) {
				throw CheckedException.badRequest("基础发布版本不属于该数字员工: " + baseReleaseId);
			}
			if (!EmployeeReleaseStatusDict.SEALED.getValue().equals(base.getStatus())
					&& !EmployeeReleaseStatusDict.PUBLISHED.getValue().equals(base.getStatus())) {
				throw CheckedException.badRequest("基础发布版本必须已封版或已发布: " + baseReleaseId);
			}
			snapshot = base.getSnapshot();
			sourceType = SOURCE_TYPE_BASE_RELEASE;
		}
		else {
			List<DigitalEmployeeCapability> capabilities = capabilityMapper.findEnabledByEmployeeId(employeeId,
					tenantId);
			requirePublishedSkillVersions(capabilities, tenantId);
			snapshot = snapshotAssembler.assemble(employee, capabilities);
		}

		DigitalEmployeeRelease release = DigitalEmployeeRelease.builder()
			.tenantId(tenantId)
			.employeeId(employeeId)
			.releaseNo(releaseNo)
			.schemaVersion(EmployeeReleaseSnapshotAssembler.SNAPSHOT_SCHEMA_VERSION)
			.snapshot(snapshot)
			// .specHash(null) — DRAFT 状态允许为空，Seal 时填充
			.baseReleaseId(baseReleaseId)
			.sourceType(sourceType)
			.sourceAgentId(employee.getSourceAgentId())
			.status(EmployeeReleaseStatusDict.DRAFT.getValue())
			.build();
		releaseMapper.insert(release);
		log.info("数字员工发布草稿已创建. employeeId={}, releaseId={}, releaseNo={}, baseReleaseId={}", employeeId,
				release.getId(), releaseNo, baseReleaseId);
		return release.getId();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void seal(Long releaseId) {
		String tenantId = currentTenantId();
		DigitalEmployeeRelease release = requireRelease(releaseId, tenantId);
		if (!EmployeeReleaseStatusDict.DRAFT.getValue().equals(release.getStatus())) {
			throw CheckedException.badRequest("仅草稿状态的发布可以封版，当前状态: " + release.getStatus());
		}
		DigitalEmployee employee = requireEmployee(release.getEmployeeId(), tenantId);
		// 冻结语义：以 Seal 时刻的草稿 + 启用中能力清单重新装配（此后草稿变更不再影响本 Release）。
		List<DigitalEmployeeCapability> capabilities = capabilityMapper.findEnabledByEmployeeId(release.getEmployeeId(),
				tenantId);
		requirePublishedSkillVersions(capabilities, tenantId);
		String snapshot = snapshotAssembler.assemble(employee, capabilities);
		String specHash = snapshotAssembler.computeSpecHash(snapshot);
		int updated = releaseMapper.casSeal(releaseId, tenantId, snapshot, specHash, currentUserIdOrNull());
		if (updated == 0) {
			throw CheckedException.badRequest("发布版本状态已变更（可能已被并发封版），请刷新后重试: " + releaseId);
		}
		log.info("数字员工发布已封版. releaseId={}, employeeId={}, specHash={}", releaseId, release.getEmployeeId(),
				specHash);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void publish(Long releaseId) {
		String tenantId = currentTenantId();
		DigitalEmployeeRelease release = requireRelease(releaseId, tenantId);
		if (!EmployeeReleaseStatusDict.SEALED.getValue().equals(release.getStatus())) {
			throw CheckedException.badRequest("仅已封版的发布可以发布，当前状态: " + release.getStatus());
		}
		int updated = releaseMapper.casPublish(releaseId, tenantId, currentUserIdOrNull());
		if (updated == 0) {
			throw CheckedException.badRequest("发布版本状态已变更（可能已被并发发布），请刷新后重试: " + releaseId);
		}
		log.info("数字员工发布已发布. releaseId={}, employeeId={}", releaseId, release.getEmployeeId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void retire(Long releaseId) {
		String tenantId = currentTenantId();
		DigitalEmployeeRelease release = requireRelease(releaseId, tenantId);
		if (!EmployeeReleaseStatusDict.PUBLISHED.getValue().equals(release.getStatus())) {
			throw CheckedException.badRequest("仅已发布的版本可以退役，当前状态: " + release.getStatus());
		}
		requireNoReference(releaseId);
		int updated = releaseMapper.casRetire(releaseId, tenantId);
		if (updated == 0) {
			throw CheckedException.badRequest("发布版本状态已变更（可能已被并发退役），请刷新后重试: " + releaseId);
		}
		log.info("数字员工发布已退役. releaseId={}, employeeId={}", releaseId, release.getEmployeeId());
	}

	@Override
	public IPage<DigitalEmployeeRelease> queryPage(Long employeeId, String status, PageRequest request) {
		PageRequest query = request == null ? new PageRequest() : request;
		return releaseMapper.selectReleasePage(query.buildPage(), employeeId, trim(status), currentTenantId());
	}

	@Override
	public DigitalEmployeeRelease getDetail(Long releaseId) {
		return requireRelease(releaseId, currentTenantId());
	}

	@Override
	public void requireReleaseOwnedByEmployee(Long employeeId, Long releaseId) {
		DigitalEmployeeRelease release = loadOwnedRelease(employeeId, releaseId);
		if (!EmployeeReleaseStatusDict.SEALED.getValue().equals(release.getStatus())
				&& !EmployeeReleaseStatusDict.PUBLISHED.getValue().equals(release.getStatus())) {
			throw CheckedException.badRequest("发布版本状态不可用（须已封版或已发布），当前状态: " + release.getStatus());
		}
	}

	@Override
	public void requirePublishedReleaseOwnedByEmployee(Long employeeId, Long releaseId) {
		DigitalEmployeeRelease release = loadOwnedRelease(employeeId, releaseId);
		if (!EmployeeReleaseStatusDict.PUBLISHED.getValue().equals(release.getStatus())) {
			throw CheckedException.badRequest("任务只能绑定已发布的员工版本，当前状态: " + release.getStatus());
		}
	}

	private DigitalEmployeeRelease loadOwnedRelease(Long employeeId, Long releaseId) {
		if (employeeId == null || releaseId == null) {
			throw CheckedException.badRequest("数字员工ID与发布版本ID均不能为空");
		}
		String tenantId = currentTenantId();
		requireEmployee(employeeId, tenantId);
		DigitalEmployeeRelease release = requireRelease(releaseId, tenantId);
		if (!employeeId.equals(release.getEmployeeId())) {
			throw CheckedException.badRequest("发布版本不属于该数字员工. releaseId=" + releaseId + ", employeeId=" + employeeId);
		}
		return release;
	}

	private void requirePublishedSkillVersions(List<DigitalEmployeeCapability> capabilities, String tenantId) {
		if (capabilities == null || capabilities.isEmpty()) {
			return;
		}
		for (DigitalEmployeeCapability capability : capabilities) {
			if (capability == null || capability.getSkillVersionId() == null) {
				throw CheckedException.badRequest("封版失败：能力绑定缺少 skillVersionId");
			}
			DataAgentSkillVersion version = skillVersionMapper.selectById(capability.getSkillVersionId());
			if (version == null || Boolean.TRUE.equals(version.getDeleted())
					|| Boolean.TRUE.equals(version.getRevoked()) || !"PUBLISHED".equals(version.getStatus())) {
				throw CheckedException.badRequest("封版失败：能力绑定的技能版本不存在或未发布, skillVersionId="
						+ capability.getSkillVersionId());
			}
			if (!StringUtils.hasText(version.getTenantId()) || !tenantId.equals(version.getTenantId().trim())) {
				throw CheckedException.badRequest("封版失败：技能版本不属于当前租户, skillVersionId="
						+ capability.getSkillVersionId());
			}
		}
	}

	private void requireNoReference(Long releaseId) {
		Long taskRefCount = taskDefinitionMapper.countByEmployeeReleaseId(releaseId);
		if (taskRefCount != null && taskRefCount > 0) {
			throw CheckedException.badRequest("仍有任务定义引用该发布版本，无法退役，请先切换任务绑定");
		}
		long deploymentRefCount = deploymentMapper.countByActiveReleaseId(releaseId);
		if (deploymentRefCount > 0) {
			throw CheckedException.badRequest("该发布版本仍被员工部署激活引用，无法退役，请先切换部署到其他版本");
		}
	}

	private DigitalEmployeeRelease requireRelease(Long releaseId, String tenantId) {
		if (releaseId == null) {
			throw CheckedException.badRequest("发布版本ID不能为空");
		}
		DigitalEmployeeRelease release = releaseMapper.findByIdAndTenantId(releaseId, tenantId);
		if (release == null) {
			throw CheckedException.notFound("发布版本不存在: " + releaseId);
		}
		return release;
	}

	private DigitalEmployee requireEmployee(Long employeeId, String tenantId) {
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, tenantId);
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		return employee;
	}

	private String currentUserIdOrNull() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			log.warn("解析当前登录用户失败, 操作人记录为空", ex);
			return null;
		}
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前登录租户失败", ex);
			throw CheckedException.forbidden("无法解析当前登录租户，禁止访问数字员工发布管理");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止访问数字员工发布管理");
		}
		return tenantId.trim();
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
