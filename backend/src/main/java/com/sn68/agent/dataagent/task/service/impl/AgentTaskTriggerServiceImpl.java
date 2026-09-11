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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.task.dto.AgentTaskApiSecretResp;
import com.sn68.agent.dataagent.task.dto.AgentTaskApiTriggerReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskTriggerModifyReq;
import com.sn68.agent.dataagent.task.dto.AgentTaskTriggerSaveReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.entity.AgentTaskVersion;
import com.sn68.agent.dataagent.task.enums.TaskConcurrencyPolicy;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskErrorDict;
import com.sn68.agent.dataagent.task.enums.TaskMisfirePolicy;
import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import com.sn68.agent.dataagent.task.repository.AgentTaskTriggerMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskVersionMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskDefinitionService;
import com.sn68.agent.dataagent.task.service.AgentTaskTriggerService;
import com.sn68.agent.dataagent.task.service.ApiTriggerCommand;
import com.sn68.agent.dataagent.task.service.ApiTriggerSecurityService;
import com.sn68.agent.dataagent.task.schedule.TaskRuntimeKick;
import com.sn68.agent.dataagent.task.service.TaskLaunchRequest;
import com.sn68.agent.dataagent.task.service.TaskRunLauncher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Agent任务触发器服务（管理端，归属校验依托任务定义服务的租户校验）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskTriggerServiceImpl extends SuperServiceImpl<AgentTaskTriggerMapper, AgentTaskTrigger>
		implements AgentTaskTriggerService {

	private final AgentTaskDefinitionService definitionService;

	private final AgentTaskVersionMapper versionMapper;

	private final TaskRunLauncher taskRunLauncher;

	private final ApiTriggerSecurityService apiTriggerSecurityService;

	private final ObjectMapper objectMapper;

	/** PR-6：启用校验读数字员工 Principal 就绪态（只读消费 PR-5 契约）。 */
	private final DigitalEmployeeMapper digitalEmployeeMapper;

	private final ObjectProvider<TaskRuntimeKick> runtimeKick;

	@Override
	public List<AgentTaskTrigger> listByDefinition(Long definitionId) {
		AgentTaskDefinition definition = definitionService.requireOwned(definitionId);
		List<AgentTaskTrigger> triggers = baseMapper.findByDefinition(definition.getTenantId(), definition.getId());
		ApiTriggerSecurityService.maskApiSecret(triggers);
		return triggers;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void createTrigger(Long definitionId, AgentTaskTriggerSaveReq request) {
		if (request == null) {
			throw CheckedException.badRequest("触发器配置不能为空");
		}
		AgentTaskDefinition definition = definitionService.requireOwned(definitionId);
		TaskTriggerType triggerType = TaskTriggerType.of(request.getTriggerType());
		if (triggerType == null) {
			throw CheckedException.badRequest(TaskErrorDict.TRIGGER_TYPE_INVALID.getValue(),
					TaskErrorDict.TRIGGER_TYPE_INVALID.getLabel());
		}
		Long taskVersionId = resolveTaskVersionId(definition, request.getTaskVersionId());
		String timezone = resolveTimezone(request.getTimezone());
		String status = StringUtils.hasText(request.getStatus()) ? request.getStatus().trim()
				: TaskConstants.STATUS_ENABLED;
		// PR-6：启用 SCHEDULE 前置校验执行主体就绪，未就绪在配置时点拒绝（早失败）
		requireLaunchablePrincipal(definition, triggerType, status);
		AgentTaskTrigger trigger = AgentTaskTrigger.builder()
			.tenantId(definition.getTenantId())
			.definitionId(definition.getId())
			.taskVersionId(taskVersionId)
			.triggerType(triggerType.getValue())
			.triggerConfig(apiTriggerSecurityService.sanitizeConfig(request.getTriggerConfig(), null))
			.timezone(timezone)
			.misfirePolicy(TaskMisfirePolicy.ofOrDefault(request.getMisfirePolicy()).getValue())
			.concurrencyPolicy(TaskConcurrencyPolicy.ofOrDefault(request.getConcurrencyPolicy()).getValue())
			.status(status)
			.build();
		validateTypedConfig(triggerType, trigger);
		if (triggerType == TaskTriggerType.SCHEDULE) {
			trigger.setNextFireTime(computeNextFireTime(trigger, Instant.now()));
		}
		baseMapper.insert(trigger);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void modifyTrigger(Long definitionId, Long triggerId, AgentTaskTriggerModifyReq request) {
		if (request == null) {
			throw CheckedException.badRequest("触发器修改内容不能为空");
		}
		AgentTaskDefinition definition = definitionService.requireOwned(definitionId);
		AgentTaskTrigger trigger = requireOwnedTrigger(definition, triggerId);
		TaskTriggerType triggerType = TaskTriggerType.of(trigger.getTriggerType());
		if (request.getTaskVersionId() != null) {
			trigger.setTaskVersionId(resolveTaskVersionId(definition, request.getTaskVersionId()));
		}
		if (request.getTriggerConfig() != null) {
			trigger.setTriggerConfig(
					apiTriggerSecurityService.sanitizeConfig(request.getTriggerConfig(), trigger.getTriggerConfig()));
		}
		if (StringUtils.hasText(request.getTimezone())) {
			trigger.setTimezone(resolveTimezone(request.getTimezone()));
		}
		if (StringUtils.hasText(request.getMisfirePolicy())) {
			trigger.setMisfirePolicy(TaskMisfirePolicy.ofOrDefault(request.getMisfirePolicy()).getValue());
		}
		if (StringUtils.hasText(request.getConcurrencyPolicy())) {
			trigger.setConcurrencyPolicy(TaskConcurrencyPolicy.ofOrDefault(request.getConcurrencyPolicy()).getValue());
		}
		if (StringUtils.hasText(request.getStatus())) {
			trigger.setStatus(request.getStatus().trim());
		}
		validateTypedConfig(triggerType, trigger);
		// PR-6：启用 SCHEDULE 前置校验执行主体就绪（含经 modify 从 disabled 切回 enabled）
		requireLaunchablePrincipal(definition, triggerType, trigger.getStatus());
		if (triggerType == TaskTriggerType.SCHEDULE) {
			trigger.setNextFireTime(computeNextFireTime(trigger, Instant.now()));
		}
		baseMapper.updateById(trigger);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteTrigger(Long definitionId, Long triggerId) {
		AgentTaskDefinition definition = definitionService.requireOwned(definitionId);
		AgentTaskTrigger trigger = requireOwnedTrigger(definition, triggerId);
		baseMapper.deleteById(trigger.getId());
	}

	@Override
	public AgentTaskRun apiTrigger(Long definitionId, Long triggerId, ApiTriggerCommand command) {
		// API 任务缺少 Idempotency-Key 一律拒绝：无幂等键的重放会重复执行任务。
		if (command == null || !StringUtils.hasText(command.idempotencyKey())) {
			throw CheckedException.badRequest(TaskErrorDict.IDEMPOTENCY_KEY_REQUIRED.getValue(),
					TaskErrorDict.IDEMPOTENCY_KEY_REQUIRED.getLabel());
		}
		AgentTaskDefinition definition = definitionService.requireOwned(definitionId);
		AgentTaskTrigger trigger = requireOwnedTrigger(definition, triggerId);
		if (TaskTriggerType.of(trigger.getTriggerType()) != TaskTriggerType.API) {
			throw CheckedException.badRequest(TaskErrorDict.TRIGGER_TYPE_INVALID.getValue(),
					"该触发器不是 API 触发类型");
		}
		// 开放 API 防重放三件套：HMAC-SHA256 验签 + 时间窗 + nonce 去重（失败关闭）；
		// Idempotency-Key 唯一约束继续兜底防重复执行（重放返回已有运行）。
		apiTriggerSecurityService.verify(trigger, command.timestamp(), command.nonce(), command.signature(),
				command.rawBody());
		return taskRunLauncher.launch(new TaskLaunchRequest(trigger, TaskTriggerType.API,
				command.idempotencyKey().trim(), Instant.now(), parseParams(command.rawBody())));
	}

	@Override
	public AgentTaskRun manualTrigger(Long definitionId, Long triggerId) {
		AgentTaskDefinition definition = definitionService.requireOwned(definitionId);
		AgentTaskTrigger trigger = requireOwnedTrigger(definition, triggerId);
		TaskTriggerType triggerType = TaskTriggerType.of(trigger.getTriggerType());
		if (triggerType == null || triggerType == TaskTriggerType.CHAT || triggerType == TaskTriggerType.IM) {
			throw CheckedException.badRequest(TaskErrorDict.MANUAL_TRIGGER_UNSUPPORTED.getValue(),
					TaskErrorDict.MANUAL_TRIGGER_UNSUPPORTED.getLabel());
		}
		Instant now = Instant.now();
		// SCHEDULE 用当前时刻做幂等键，避免占用 cron 计划时刻导致到点被当成重放跳过。
		String externalEventId = triggerType == TaskTriggerType.SCHEDULE ? null : "manual-" + UUID.randomUUID();
		AgentTaskRun run = taskRunLauncher.launch(new TaskLaunchRequest(trigger, triggerType, externalEventId, now,
				Map.of()));
		kickDaemonScanQuietly();
		log.info("控制台立即执行已受理。definitionId={}, triggerId={}, triggerType={}, taskRunId={}", definition.getId(),
				trigger.getId(), triggerType.getValue(), run.getId());
		return run;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AgentTaskApiSecretResp rotateApiSecret(Long definitionId, Long triggerId) {
		AgentTaskDefinition definition = definitionService.requireOwned(definitionId);
		AgentTaskTrigger trigger = requireOwnedTrigger(definition, triggerId);
		if (TaskTriggerType.of(trigger.getTriggerType()) != TaskTriggerType.API) {
			throw CheckedException.badRequest(TaskErrorDict.TRIGGER_TYPE_INVALID.getValue(),
					"仅 API 触发器支持签名密钥生成/轮换");
		}
		String secret = apiTriggerSecurityService.rotateSecret(trigger);
		baseMapper.updateById(trigger);
		log.info("API 触发签名密钥已生成/轮换。definitionId={}, triggerId={}", definition.getId(), trigger.getId());
		return new AgentTaskApiSecretResp(secret, TaskConstants.SIGNATURE_ALGORITHM);
	}

	private void kickDaemonScanQuietly() {
		TaskRuntimeKick kick = runtimeKick.getIfAvailable();
		if (kick == null) {
			return;
		}
		try {
			kick.kickDaemonScan();
		}
		catch (Exception ex) {
			log.warn("通知 Snail 拉起待启动运行失败, 运行保持 PENDING 等待扫描作业。", ex);
		}
	}

	/**
	 * 验签通过后再反序列化业务参数：请求体原文参与签名，解析失败按参数非法拒绝。
	 */
	private Map<String, Object> parseParams(String rawBody) {
		if (!StringUtils.hasText(rawBody)) {
			return null;
		}
		try {
			AgentTaskApiTriggerReq request = objectMapper.readValue(rawBody, AgentTaskApiTriggerReq.class);
			return request == null ? null : request.getParams();
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("API 触发请求体不是合法 JSON");
		}
	}

	/**
	 * PR-6：启用 SCHEDULE 触发器前校验受限执行主体就绪（员工 ENABLED + Principal READY）。
	 * 统一口径校验而不区分是否含业务 Skill：无人值守触发一律依赖员工 Principal，
	 * 未就绪必然在拉起时失败，前置拒绝把失败暴露在配置时点而不是每个调度周期落 FAILED。
	 */
	private void requireLaunchablePrincipal(AgentTaskDefinition definition, TaskTriggerType triggerType,
			String status) {
		if (triggerType != TaskTriggerType.SCHEDULE || !TaskConstants.STATUS_ENABLED.equalsIgnoreCase(status)) {
			return;
		}
		DigitalEmployee employee = definition.getDigitalEmployeeId() == null ? null
				: digitalEmployeeMapper.findByIdAndTenantId(definition.getDigitalEmployeeId(),
						definition.getTenantId());
		if (employee == null || !EmployeeStatusDict.ENABLED.getValue().equals(employee.getStatus())
				|| !StringUtils.hasText(employee.getIamPrincipalId())
				|| !PrincipalProvisionStatusDict.READY.getValue().equals(employee.getPrincipalStatus())) {
			throw CheckedException.badRequest(TaskErrorDict.PRINCIPAL_NOT_READY.getValue(),
					TaskErrorDict.PRINCIPAL_NOT_READY.getLabel());
		}
	}

	private AgentTaskTrigger requireOwnedTrigger(AgentTaskDefinition definition, Long triggerId) {
		AgentTaskTrigger trigger = baseMapper.findByTenantAndId(definition.getTenantId(), triggerId);
		if (trigger == null || !definition.getId().equals(trigger.getDefinitionId())) {
			throw CheckedException.notFound(TaskErrorDict.TRIGGER_NOT_FOUND.getValue(),
					TaskErrorDict.TRIGGER_NOT_FOUND.getLabel());
		}
		return trigger;
	}

	private Long resolveTaskVersionId(AgentTaskDefinition definition, Long requestedVersionId) {
		if (requestedVersionId != null) {
			AgentTaskVersion version = versionMapper.findByTenantAndId(definition.getTenantId(), requestedVersionId);
			if (version == null || !definition.getId().equals(version.getDefinitionId())) {
				throw CheckedException.notFound(TaskErrorDict.TASK_VERSION_NOT_FOUND.getValue(),
						TaskErrorDict.TASK_VERSION_NOT_FOUND.getLabel());
			}
			return version.getId();
		}
		AgentTaskVersion latest = versionMapper.findLatestByDefinition(definition.getTenantId(), definition.getId());
		if (latest == null) {
			throw CheckedException.notFound(TaskErrorDict.TASK_VERSION_NOT_FOUND.getValue(),
					TaskErrorDict.TASK_VERSION_NOT_FOUND.getLabel());
		}
		return latest.getId();
	}

	private String resolveTimezone(String timezone) {
		String candidate = StringUtils.hasText(timezone) ? timezone.trim() : TaskConstants.DEFAULT_TIMEZONE;
		try {
			ZoneId.of(candidate);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest(TaskErrorDict.TIMEZONE_INVALID.getValue(),
					TaskErrorDict.TIMEZONE_INVALID.getLabel() + ": " + candidate);
		}
		return candidate;
	}

	private void validateTypedConfig(TaskTriggerType triggerType, AgentTaskTrigger trigger) {
		if (triggerType == TaskTriggerType.SCHEDULE) {
			String cron = configText(trigger.getTriggerConfig(), TaskConstants.CONFIG_CRON);
			if (!StringUtils.hasText(cron) || !CronExpression.isValidExpression(cron)) {
				throw CheckedException.badRequest(TaskErrorDict.CRON_INVALID.getValue(),
						TaskErrorDict.CRON_INVALID.getLabel() + ": " + cron);
			}
		}
		if (triggerType == TaskTriggerType.EVENT
				&& !StringUtils.hasText(configText(trigger.getTriggerConfig(), TaskConstants.CONFIG_EVENT_TOPIC))) {
			throw CheckedException.badRequest(TaskErrorDict.EVENT_TOPIC_REQUIRED.getValue(),
					TaskErrorDict.EVENT_TOPIC_REQUIRED.getLabel());
		}
	}

	/**
	 * 按触发器时区计算下一次计划执行时刻。DST 跳变由 ZonedDateTime 语义兜底：
	 * 不存在的本地时间（春季快进）自动顺延，重复的本地时间（秋季回拨）只取第一次。
	 */
	private Instant computeNextFireTime(AgentTaskTrigger trigger, Instant fromInclusive) {
		String cron = configText(trigger.getTriggerConfig(), TaskConstants.CONFIG_CRON);
		CronExpression expression = CronExpression.parse(cron);
		ZoneId zone = ZoneId.of(StringUtils.hasText(trigger.getTimezone()) ? trigger.getTimezone()
				: TaskConstants.DEFAULT_TIMEZONE);
		ZonedDateTime next = expression.next(fromInclusive.atZone(zone));
		return next == null ? null : next.toInstant();
	}

	private String configText(Map<String, Object> config, String key) {
		Object value = config == null ? null : config.get(key);
		return value == null ? null : String.valueOf(value).trim();
	}

}
