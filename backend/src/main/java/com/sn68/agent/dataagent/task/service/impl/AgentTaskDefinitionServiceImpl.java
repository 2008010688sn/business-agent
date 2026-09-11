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
package com.sn68.agent.dataagent.task.service.impl;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionModifyReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskDefinitionSaveReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskDetailResp;
import com.sn68.agent.dataagent.task.dto.AgentTaskPageQueryReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.entity.AgentTaskVersion;
import com.sn68.agent.dataagent.task.enums.TaskAutonomyLevel;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskErrorDict;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskTriggerMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskVersionMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskDefinitionService;
import com.sn68.agent.dataagent.task.service.ApiTriggerSecurityService;
import com.sn68.agent.dataagent.task.service.EmployeeReleaseReferenceChecker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Agent任务定义服务（管理端，全部操作限定在当前登录租户内）。
 *
 * <p>策略：高风险写任务（highRiskWrite=true）的自治级别强制 ASSISTED，
 * 即使请求显式传 AUTONOMOUS 也会被覆盖，保证写动作必须经人工审批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskDefinitionServiceImpl extends SuperServiceImpl<AgentTaskDefinitionMapper, AgentTaskDefinition>
		implements AgentTaskDefinitionService {

	private final AgentTaskVersionMapper versionMapper;

	private final AgentTaskTriggerMapper triggerMapper;

	/** PR-1: 数字员工引用校验器（PR-5 前失败关闭） */
	private final EmployeeReleaseReferenceChecker employeeReferenceChecker;

	private final DigitalEmployeeMapper digitalEmployeeMapper;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	@Override
	public IPage<AgentTaskDefinition> pageByTenant(AgentTaskPageQueryReq request) {
		AgentTaskPageQueryReq query = request == null ? new AgentTaskPageQueryReq() : request;
		return baseMapper.selectPageByTenant(query.buildPage(), requireCurrentTenantId(), query);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void create(AgentTaskDefinitionSaveReq request) {
		if (request == null) {
			throw CheckedException.badRequest("任务定义不能为空");
		}
		String tenantId = requireCurrentTenantId();
		// PR-1: 校验数字员工与 Release 归属
		employeeReferenceChecker.validateReleaseOwner(request.getDigitalEmployeeId(), request.getEmployeeReleaseId());
		AgentTaskDefinition definition = AgentTaskDefinition.builder()
			.tenantId(tenantId)
			.digitalEmployeeId(request.getDigitalEmployeeId())
			.employeeReleaseId(request.getEmployeeReleaseId())
			.taskName(request.getTaskName().trim())
			.taskDescription(request.getTaskDescription())
			.taskType(StringUtils.hasText(request.getTaskType()) ? request.getTaskType().trim() : null)
			.defaultAutonomyLevel(resolveAutonomyLevel(request.getDefaultAutonomyLevel(), request.getHighRiskWrite()))
			.highRiskWrite(Boolean.TRUE.equals(request.getHighRiskWrite()))
			.servicePrincipal(resolveEmployeePrincipal(request.getDigitalEmployeeId(), tenantId))
			.status(TaskConstants.STATUS_ENABLED)
			.build();
		baseMapper.insert(definition);
		insertVersion(definition, 1, request.getParamsSnapshot(), request.getPromptSnapshot());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void modify(Long id, AgentTaskDefinitionModifyReq request) {
		if (request == null) {
			throw CheckedException.badRequest("任务定义修改内容不能为空");
		}
		AgentTaskDefinition definition = requireOwned(id);
		if (StringUtils.hasText(request.getTaskName())) {
			definition.setTaskName(request.getTaskName().trim());
		}
		if (StringUtils.hasText(request.getTaskDescription())) {
			definition.setTaskDescription(request.getTaskDescription());
		}
		if (StringUtils.hasText(request.getTaskType())) {
			definition.setTaskType(request.getTaskType().trim());
		}
		// PR-1: 仅在显式切换 Release 时校验归属（失败关闭，PR-5 提供真实实现）；digitalEmployeeId 创建后固定。
		if (request.getEmployeeReleaseId() != null) {
			employeeReferenceChecker.validateReleaseOwner(definition.getDigitalEmployeeId(),
					request.getEmployeeReleaseId());
			definition.setEmployeeReleaseId(request.getEmployeeReleaseId());
		}
		if (request.getHighRiskWrite() != null) {
			definition.setHighRiskWrite(request.getHighRiskWrite());
		}
		if (StringUtils.hasText(request.getDefaultAutonomyLevel()) || request.getHighRiskWrite() != null) {
			String requested = StringUtils.hasText(request.getDefaultAutonomyLevel())
					? request.getDefaultAutonomyLevel() : definition.getDefaultAutonomyLevel();
			definition.setDefaultAutonomyLevel(resolveAutonomyLevel(requested, definition.getHighRiskWrite()));
		}
		definition.setServicePrincipal(resolveEmployeePrincipal(definition.getDigitalEmployeeId(),
				definition.getTenantId()));
		if (StringUtils.hasText(request.getStatus())) {
			definition.setStatus(request.getStatus().trim());
		}
		baseMapper.updateById(definition);
		// 参数或提示词快照变化 → 生成新的不可变版本，历史运行仍指向旧版本。
		if (request.getParamsSnapshot() != null || request.getPromptSnapshot() != null) {
			AgentTaskVersion latest = versionMapper.findLatestByDefinition(definition.getTenantId(), definition.getId());
			int nextVersionNo = latest == null ? 1 : latest.getVersionNo() + 1;
			Map<String, Object> params = request.getParamsSnapshot() != null ? request.getParamsSnapshot()
					: latest == null ? null : latest.getParamsSnapshot();
			Map<String, Object> prompt = request.getPromptSnapshot() != null ? request.getPromptSnapshot()
					: latest == null ? null : latest.getPromptSnapshot();
			insertVersion(definition, nextVersionNo, params, prompt);
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		AgentTaskDefinition definition = requireOwned(id);
		baseMapper.deleteById(definition.getId());
		// 触发器随定义一并逻辑删除，避免调度器继续扫描到孤儿触发器；运行记录保留供审计追溯。
		List<AgentTaskTrigger> triggers = triggerMapper.findByDefinition(definition.getTenantId(), definition.getId());
		for (AgentTaskTrigger trigger : triggers) {
			triggerMapper.deleteById(trigger.getId());
		}
		log.info("任务定义已删除。definitionId={}, 连带删除触发器 {} 个", definition.getId(), triggers.size());
	}

	@Override
	public AgentTaskDetailResp detail(Long id) {
		AgentTaskDefinition definition = requireOwned(id);
		AgentTaskVersion latest = versionMapper.findLatestByDefinition(definition.getTenantId(), definition.getId());
		List<AgentTaskTrigger> triggers = triggerMapper.findByDefinition(definition.getTenantId(), definition.getId());
		ApiTriggerSecurityService.maskApiSecret(triggers);
		return new AgentTaskDetailResp(definition, latest, triggers);
	}

	@Override
	public AgentTaskDefinition requireOwned(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("任务定义 ID 不能为空");
		}
		AgentTaskDefinition definition = baseMapper.findByTenantAndId(requireCurrentTenantId(), id);
		if (definition == null) {
			throw CheckedException.notFound(TaskErrorDict.TASK_NOT_FOUND.getValue(),
					TaskErrorDict.TASK_NOT_FOUND.getLabel());
		}
		return definition;
	}

	/**
	 * 生成不可变版本并填充 PR-6 冻结清单：员工 Release、受限执行主体、授权策略版本、
	 * 授权模式、Token 预算、参数+提示词指纹。发布后不可改，参数调整生成新版本，
	 * 历史运行始终指向旧版本口径。
	 */
	private void insertVersion(AgentTaskDefinition definition, int versionNo, Map<String, Object> paramsSnapshot,
			Map<String, Object> promptSnapshot) {
		AgentTaskVersion version = AgentTaskVersion.builder()
			.tenantId(definition.getTenantId())
			.definitionId(definition.getId())
			.versionNo(versionNo)
			.paramsSnapshot(paramsSnapshot)
			.promptSnapshot(promptSnapshot)
			.employeeReleaseId(definition.getEmployeeReleaseId())
			.executionPrincipalId(definition.getServicePrincipal())
			.authorizationPolicyVersionId(
					readLongParam(paramsSnapshot, TaskConstants.PARAMS_AUTHORIZATION_POLICY_VERSION_ID))
			.authMode(readStringParam(paramsSnapshot, TaskConstants.PARAMS_AUTH_MODE))
			.budget(readMapParam(paramsSnapshot, TaskConstants.PARAMS_BUDGET))
			.specHash(computeSpecHash(paramsSnapshot, promptSnapshot))
			.build();
		versionMapper.insert(version);
	}

	/**
	 * 参数+提示词快照指纹（SHA-256 小写 hex 64 位）：顶层键排序后序列化，同内容同指纹；
	 * 指纹只做变更比对与审计口径，不承担安全签名。
	 */
	private String computeSpecHash(Map<String, Object> paramsSnapshot, Map<String, Object> promptSnapshot) {
		try {
			Map<String, Object> canonical = new TreeMap<>();
			canonical.put("params", paramsSnapshot == null ? Map.of() : paramsSnapshot);
			canonical.put("prompt", promptSnapshot == null ? Map.of() : promptSnapshot);
			return SecureUtil.sha256(objectMapper.writeValueAsString(canonical));
		}
		catch (Exception ex) {
			throw CheckedException.fail("任务版本指纹计算失败: " + ex.getMessage());
		}
	}

	private Long readLongParam(Map<String, Object> params, String key) {
		Object value = params == null ? null : params.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.valueOf(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			log.warn("任务版本参数 {} 不是数字, 忽略冻结。value={}", key, value);
			return null;
		}
	}

	private String readStringParam(Map<String, Object> params, String key) {
		Object value = params == null ? null : params.get(key);
		return value == null ? null : String.valueOf(value);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readMapParam(Map<String, Object> params, String key) {
		Object value = params == null ? null : params.get(key);
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
	}

	/**
	 * 高风险写任务强制 ASSISTED；未指定时默认 ASSISTED（宁可多审批，不放任自动写）。
	 */
	private String resolveAutonomyLevel(String requested, Boolean highRiskWrite) {
		if (Boolean.TRUE.equals(highRiskWrite)) {
			return TaskAutonomyLevel.ASSISTED.getValue();
		}
		TaskAutonomyLevel level = TaskAutonomyLevel.of(requested);
		return level == null ? TaskAutonomyLevel.ASSISTED.getValue() : level.getValue();
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次任务定义操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法操作任务定义");
		}
		return tenantId;
	}

	/**
	 * 任务执行主体取员工 IAM Principal，忽略客户端手填的 servicePrincipal。
	 */
	private String resolveEmployeePrincipal(Long employeeId, String tenantId) {
		DigitalEmployee employee = digitalEmployeeMapper.findByIdAndTenantId(employeeId, tenantId);
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		return StringUtils.hasText(employee.getIamPrincipalId()) ? employee.getIamPrincipalId().trim() : null;
	}

}
