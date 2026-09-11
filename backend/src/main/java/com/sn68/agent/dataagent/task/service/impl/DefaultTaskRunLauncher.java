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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.TaskAuthorizationGuard;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthContextException;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeApprovalState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.RunStateChange;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskAutonomyLevel;
import com.sn68.agent.dataagent.task.enums.TaskConcurrencyPolicy;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskErrorDict;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.dataagent.task.service.TaskLaunchRequest;
import com.sn68.agent.dataagent.task.service.TaskRunIdempotency;
import com.sn68.agent.dataagent.task.service.TaskRunLauncher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 任务运行发起器默认实现。
 *
 * <p>当前职责：校验任务/触发器状态 → FORBID 并发槽占用（方案A：SKIP LOCKED 预检 +
 * idx_forbid_slot 部分唯一索引仲裁，冲突落 SKIPPED 原因码 CONCURRENT_SLOT_LOCKED）→
 * 受限执行身份解析（数字员工 ENABLED + Principal READY + IAM execution-context 签发验证，
 * 禁止创建者长期 Token）→ TaskAuthorizationGuard START_UNATTENDED 守卫（SHADOW 记录 /
 * ENFORCE 拒绝落 FAILED）→ 幂等创建统一运行时 RuntimeRun 并回填 runtimeRunId →
 * ASSISTED 任务创建审批并把运行挂起为待审批，审批通过事件回流后经 {@link #launchApproved} 续发。</p>
 *
 * <p>PR-6 失败语义：执行身份/授权失败不创建 RuntimeRun，任务侧直接落 FAILED 并带原因码
 * （[WAITING_AUTH] 可重试、[AUTHORIZATION_DENIED] 确定性拒绝）；失败行返回触发方而非抛出，
 * 保证调度链路正常推进下一计划时刻（重试由新触发承担，幂等键防重复）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultTaskRunLauncher implements TaskRunLauncher {

	/** 任务触发的统一运行时运行模式：自动任务按智能体循环执行。 */
	private static final String RUNTIME_RUN_MODE = "AGENT_LOOP";

	/** ASSISTED 任务发起审批时使用的能力编码（伪能力，标识任务发起动作本身）。 */
	private static final String TASK_LAUNCH_CAPABILITY = "agent-task:launch";

	/** 审批来源标识：任务运行发起。 */
	private static final String APPROVAL_SOURCE_TASK_RUN = "TASK_RUN";

	/** 参数指纹长度，与能力网关 fingerprint 口径一致（SHA-256 前 16 位）。 */
	private static final int PARAMS_HASH_LENGTH = 16;

	private static final int WAITING_APPROVAL_CAS_RETRY = 3;

	private final AgentTaskRunService taskRunService;

	private final AgentTaskRunMapper taskRunMapper;

	private final AgentTaskDefinitionMapper definitionMapper;

	private final RuntimeRunService runtimeRunService;

	private final RuntimeStateService runtimeStateService;

	private final AgentApprovalService approvalService;

	private final ObjectMapper objectMapper;

	private final TaskAuthorizationGuard taskAuthorizationGuard;

	private final EmployeeExecutionContextClient employeeExecutionContextClient;

	private final DigitalEmployeeMapper digitalEmployeeMapper;

	@Override
	public AgentTaskRun launch(TaskLaunchRequest request) {
		AgentTaskTrigger trigger = requireLaunchableTrigger(request);
		AgentTaskDefinition definition = definitionMapper.findByTenantAndId(trigger.getTenantId(),
				trigger.getDefinitionId());
		if (definition == null) {
			throw CheckedException.notFound(TaskErrorDict.TASK_NOT_FOUND.getValue(),
					TaskErrorDict.TASK_NOT_FOUND.getLabel());
		}
		if (!TaskConstants.STATUS_ENABLED.equalsIgnoreCase(definition.getStatus())) {
			throw CheckedException.badRequest(TaskErrorDict.TASK_DISABLED.getValue(),
					TaskErrorDict.TASK_DISABLED.getLabel());
		}
		AgentTaskRunService.SlotClaim claim = taskRunService.claimSlot(buildTaskRun(request, trigger, definition));
		if (claim.outcome() != AgentTaskRunService.SlotOutcome.CLAIMED) {
			// 幂等命中：重复事件/API 重放/定时重扫。PENDING 且未关联的行借重放补齐身份与运行挂接，
			// 双侧幂等保证不重复建 Run；SLOT_CONFLICT 返回已落的 SKIPPED 台账行。
			if (claim.outcome() == AgentTaskRunService.SlotOutcome.IDEMPOTENT_HIT) {
				resumeUnattachedRun(definition, claim.run());
			}
			else {
				log.info("任务触发因 FORBID 并发槽被占跳过。definitionId={}, triggerId={}, taskRunId={}, reason={}",
						definition.getId(), trigger.getId(), claim.run().getId(), claim.run().getErrorMessage());
			}
			return claim.run();
		}
		AgentTaskRun claimed = claim.run();
		log.info("任务运行已受理。definitionId={}, triggerId={}, triggerType={}, taskRunId={}, "
				+ "idempotencyKey={}, concurrencyPolicy={}", definition.getId(), trigger.getId(),
				request.triggerType(), claimed.getId(), claimed.getIdempotencyKey(), claimed.getConcurrencyPolicy());
		ExecutionIdentity identity = resolveExecutionIdentity(definition, claimed);
		if (identity == null) {
			// 已落 FAILED（[WAITING_AUTH]/[AUTHORIZATION_DENIED]），不创建 RuntimeRun，正常返回推进调度
			return claimed;
		}
		attachRuntimeRun(definition, claimed, identity.principalId());
		guardAssistedLaunch(definition, claimed);
		if (request.triggerType() != TaskTriggerType.CHAT) {
			log.debug("自动任务已关联统一运行时, 等待运行时执行器拉起。taskRunId={}, runtimeRunId={}", claimed.getId(),
					claimed.getRuntimeRunId());
		}
		return claimed;
	}

	private AgentTaskTrigger requireLaunchableTrigger(TaskLaunchRequest request) {
		if (request == null || request.trigger() == null || request.triggerType() == null) {
			throw CheckedException.badRequest("任务触发请求不完整");
		}
		AgentTaskTrigger trigger = request.trigger();
		if (!TaskConstants.STATUS_ENABLED.equalsIgnoreCase(trigger.getStatus())) {
			throw CheckedException.badRequest(TaskErrorDict.TRIGGER_DISABLED.getValue(),
					TaskErrorDict.TRIGGER_DISABLED.getLabel());
		}
		return trigger;
	}

	/** 构造待落库的任务运行行：并发策略快照取自触发器落库时刻值，参与 FORBID 槽索引谓词。 */
	private AgentTaskRun buildTaskRun(TaskLaunchRequest request, AgentTaskTrigger trigger,
			AgentTaskDefinition definition) {
		return AgentTaskRun.builder()
			.tenantId(trigger.getTenantId())
			.definitionId(definition.getId())
			.taskVersionId(trigger.getTaskVersionId())
			.triggerId(trigger.getId())
			.triggerType(request.triggerType().getValue())
			.idempotencyKey(TaskRunIdempotency.buildKey(request.triggerType(), trigger.getId(),
					request.externalEventId(), request.scheduledTime()))
			.externalEventId(request.externalEventId())
			.scheduledTime(request.scheduledTime())
			.runStatus(TaskRunStatus.PENDING.getValue())
			.servicePrincipal(definition.getServicePrincipal())
			.concurrencyPolicy(TaskConcurrencyPolicy.ofOrDefault(trigger.getConcurrencyPolicy()).getValue())
			.build();
	}

	/** 幂等命中自愈：PENDING 且未关联 RuntimeRun 的行补走身份解析与运行挂接（上次受理后创建失败）。 */
	private void resumeUnattachedRun(AgentTaskDefinition definition, AgentTaskRun existing) {
		if (existing.getRuntimeRunId() != null
				|| !TaskRunStatus.PENDING.getValue().equals(existing.getRunStatus())) {
			return;
		}
		ExecutionIdentity identity = resolveExecutionIdentity(definition, existing);
		if (identity != null) {
			attachRuntimeRun(definition, existing, identity.principalId());
			guardAssistedLaunch(definition, existing);
		}
	}

	/**
	 * PR-6 受限执行身份解析（替代创建者长期 Token）：数字员工启用且 Principal 就绪 →
	 * IAM execution-context 签发验证（成功即证明执行凭据可用）→ START_UNATTENDED 守卫。
	 *
	 * <p>token 只用于验证签发可行并取回 authRevision，不落库不外传（RuntimeRun 无凭据字段，
	 * 执行期由运行时按 principal 自行换取）。任何失败均落 FAILED + 原因码并返回 null，
	 * 调用方不得继续创建 RuntimeRun。</p>
	 */
	private ExecutionIdentity resolveExecutionIdentity(AgentTaskDefinition definition, AgentTaskRun taskRun) {
		Long employeeId = definition.getDigitalEmployeeId();
		DigitalEmployee employee = employeeId == null ? null
				: digitalEmployeeMapper.findByIdAndTenantId(employeeId, taskRun.getTenantId());
		if (employee == null || !EmployeeStatusDict.ENABLED.getValue().equals(employee.getStatus())) {
			return failLaunch(taskRun, TaskConstants.REASON_AUTHORIZATION_DENIED,
					"数字员工不存在或未启用, 无法拉起自动任务。employeeId=" + employeeId);
		}
		if (!StringUtils.hasText(employee.getIamPrincipalId())
				|| !PrincipalProvisionStatusDict.READY.getValue().equals(employee.getPrincipalStatus())) {
			return failLaunch(taskRun, TaskConstants.REASON_AUTHORIZATION_DENIED,
					"受限执行主体未就绪(Principal 非 READY), 无法拉起自动任务。employeeId=" + employeeId);
		}
		EmployeeAuthTokenContext token;
		try {
			token = employeeExecutionContextClient.issueContext(taskRun.getTenantId(),
					employee.getIamPrincipalId(), employee.getEmployeeName());
		}
		catch (EmployeeAuthContextException ex) {
			log.warn("任务拉起执行身份签发失败(WAITING_AUTH)。taskRunId={}, employeeId={}, reason={}",
					taskRun.getId(), employeeId, ex.getMessage());
			return failLaunch(taskRun, TaskConstants.REASON_WAITING_AUTH,
					"IAM 执行身份暂不可用, 等待授权后重试: " + ex.getMessage());
		}
		if (!authorizeTaskStart(definition, taskRun, employee, token)) {
			return null;
		}
		return new ExecutionIdentity(employee.getIamPrincipalId(), token.authRevision());
	}

	/**
	 * START_UNATTENDED 守卫接线（PR-6）：owner 恒 DIGITAL_EMPLOYEE，主体为员工 Principal；
	 * subjectSnapshot 传 null（无人值守触发无真人会话，跳过 owner 维度 USE 预检，
	 * 影子比对按 ORIGINAL_ONLY 记录）。ENFORCE 拒绝（BUSINESS_DENIED）落 FAILED +
	 * AUTHORIZATION_DENIED 后返回；SHADOW 模式只记录不拦截。
	 */
	private boolean authorizeTaskStart(AgentTaskDefinition definition, AgentTaskRun taskRun,
			DigitalEmployee employee, EmployeeAuthTokenContext token) {
		PepDecisionContext context = PepDecisionContext.builder()
			.tenantId(taskRun.getTenantId())
			.runId(taskRun.getRuntimeRunId())
			.ownerType(AuthorizationOwnerType.DIGITAL_EMPLOYEE)
			.ownerId(definition.getDigitalEmployeeId())
			.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
			.subjectId(employee.getIamPrincipalId())
			.capabilityCode(TASK_LAUNCH_CAPABILITY)
			.action(AuthorizationAction.START_UNATTENDED)
			.expectedAuthRevision(employee.getPrincipalRevision())
			.currentAuthRevision(token.authRevision())
			.build();
		try {
			taskAuthorizationGuard.authorizeTaskStart(context, null);
			return true;
		}
		catch (CheckedException ex) {
			log.warn("任务拉起被授权策略拒绝(ENFORCE)。taskRunId={}, employeeId={}, reason={}", taskRun.getId(),
					definition.getDigitalEmployeeId(), ex.getMessage());
			failLaunch(taskRun, TaskConstants.REASON_AUTHORIZATION_DENIED, "授权策略拒绝任务拉起: " + ex.getMessage());
			return false;
		}
	}

	/** 拉起失败落终态（FAILED + 原因码前缀），随终态释放 FORBID 并发槽；返回 null 表示不得创建 RuntimeRun。 */
	private ExecutionIdentity failLaunch(AgentTaskRun taskRun, String reasonCode, String detail) {
		boolean advanced = taskRunService.markTerminalById(taskRun.getId(), TaskRunStatus.FAILED,
				"[" + reasonCode + "] " + detail);
		log.warn("任务拉起失败已落终态。taskRunId={}, reasonCode={}, advanced={}, detail={}", taskRun.getId(),
				reasonCode, advanced, detail);
		taskRun.setRunStatus(TaskRunStatus.FAILED.getValue());
		taskRun.setErrorMessage("[" + reasonCode + "] " + detail);
		return null;
	}

	@Override
	public AgentTaskRun launchApproved(Long taskRunId) {
		if (taskRunId == null) {
			throw CheckedException.badRequest("taskRunId 不能为空");
		}
		AgentTaskRun taskRun = taskRunMapper.selectById(taskRunId);
		if (taskRun == null) {
			throw CheckedException.notFound("任务运行不存在: " + taskRunId);
		}
		if (taskRun.getRuntimeRunId() == null) {
			throw CheckedException.badRequest("任务运行未关联统一运行时, 无法续发, taskRunId=" + taskRunId);
		}
		String tenantId = parseTenantId(taskRun.getTenantId());
		RuntimeRunState state = runtimeRunService.findRunState(tenantId, taskRun.getRuntimeRunId());
		if (state != RuntimeRunState.WAITING_APPROVAL) {
			// 幂等：outbox at-least-once 重投或人工重复触发时运行已恢复/已推进，跳过不重复续发。
			log.info("任务续发跳过, 运行不处于待审批状态。taskRunId={}, runtimeRunId={}, state={}", taskRunId,
					taskRun.getRuntimeRunId(), state);
			return taskRun;
		}
		runtimeRunService.resume(tenantId, taskRun.getServicePrincipal(), taskRun.getRuntimeRunId());
		log.info("ASSISTED 任务审批通过, 运行已恢复待执行。taskRunId={}, runtimeRunId={}", taskRunId,
				taskRun.getRuntimeRunId());
		return taskRun;
	}

	/**
	 * 订阅 Outbox 派发的审批结果事件：TASK_RUN 来源且决定为 APPROVED 时续发任务。
	 * 派发语义 at-least-once：续发按运行状态幂等；异常向上抛给派发器计入重试，重试耗尽置 DEAD 可见。
	 *
	 * <p>授权模型: 服务主体
	 * 内部 Outbox 事件入口；续发沿用任务运行已落库的 service principal，不再使用调用方会话。
	 */
	@EventListener
	public void onApprovalDecided(RuntimeOutboxEvent event) {
		if (event == null || !RuntimeEventType.APPROVAL_DECIDED.getValue().equals(event.eventType())) {
			return;
		}
		JsonNode payload = readEventPayload(event);
		if (!APPROVAL_SOURCE_TASK_RUN.equals(payload.path("source").asText(null))
				|| !RuntimeApprovalState.APPROVED.getValue().equals(payload.path("state").asText(null))) {
			return;
		}
		String sourceRefId = payload.path("sourceRefId").asText(null);
		if (!StringUtils.hasText(sourceRefId)) {
			throw CheckedException.fail("任务审批结果事件缺少任务运行ID, eventKey=" + event.eventKey());
		}
		AgentTaskRun taskRun = launchApproved(Long.valueOf(sourceRefId.trim()));
		// 任务审批按 sourceRefId 绑定唯一运行，续发成功后标记一次性消费（CONSUMED），防止批复被复用。
		long approvalId = payload.path("approvalId").asLong(0);
		if (approvalId > 0 && !approvalService.consume(approvalId)) {
			log.debug("任务审批消费标记跳过（已消费或已过期）。approvalId={}, taskRunId={}", approvalId,
					taskRun.getId());
		}
	}

	/**
	 * ASSISTED 任务挂接审批：先幂等创建审批（与任务运行的幂等键指纹绑定），再把 PENDING 的
	 * RuntimeRun 挂起为 WAITING_APPROVAL。运行已非 PENDING（已挂起 / 已被审批恢复 / 已推进）时
	 * 不重复挂接，避免重放把已恢复的运行再次挂起或复制审批请求。
	 */
	private void guardAssistedLaunch(AgentTaskDefinition definition, AgentTaskRun taskRun) {
		if (!assisted(definition) || taskRun.getRuntimeRunId() == null) {
			return;
		}
		String tenantId = parseTenantId(taskRun.getTenantId());
		RuntimeRunState state = runtimeRunService.findRunState(tenantId, taskRun.getRuntimeRunId());
		if (state != RuntimeRunState.PENDING) {
			log.debug("ASSISTED 任务运行已非初始状态, 不重复挂接审批。taskRunId={}, runtimeRunId={}, state={}",
					taskRun.getId(), taskRun.getRuntimeRunId(), state);
			return;
		}
		String paramsHash = SecureUtil.sha256(taskRun.getIdempotencyKey()).substring(0, PARAMS_HASH_LENGTH);
		approvalService.createPending(new AgentApprovalService.ApprovalCreateRequest(tenantId,
				"DIGITAL_EMPLOYEE", definition.getDigitalEmployeeId(), taskRun.getRuntimeRunId(), null,
				TASK_LAUNCH_CAPABILITY, paramsHash, null, definition.getServicePrincipal(), null,
				APPROVAL_SOURCE_TASK_RUN, String.valueOf(taskRun.getId())));
		boolean waiting = runtimeStateService.transitionRunWithRetry(taskRun.getRuntimeRunId(),
				RuntimeRunState.WAITING_APPROVAL, RunStateChange.none(), WAITING_APPROVAL_CAS_RETRY);
		if (!waiting) {
			// 失败关闭：挂起失败必须让本次触发失败可见；task_run 与审批均已幂等落库，重放会重新尝试挂接。
			throw CheckedException.fail("ASSISTED 任务挂接审批失败：运行状态未能置为待审批, taskRunId="
					+ taskRun.getId() + ", runtimeRunId=" + taskRun.getRuntimeRunId());
		}
		log.info("ASSISTED 任务已挂起等待审批。taskRunId={}, runtimeRunId={}, definitionId={}", taskRun.getId(),
				taskRun.getRuntimeRunId(), definition.getId());
	}

	/** 高风险写任务强制 ASSISTED；自治级别缺失/未识别按 ASSISTED（宁可多审批，不放任自动写）。 */
	private boolean assisted(AgentTaskDefinition definition) {
		if (Boolean.TRUE.equals(definition.getHighRiskWrite())) {
			return true;
		}
		return TaskAutonomyLevel.of(definition.getDefaultAutonomyLevel()) != TaskAutonomyLevel.AUTONOMOUS;
	}

	/** 事件负载解析失败按失败关闭抛出：进入派发重试直至 DEAD，坏消息可见而不是被静默丢弃。 */
	private JsonNode readEventPayload(RuntimeOutboxEvent event) {
		try {
			return objectMapper.readTree(StringUtils.hasText(event.payloadJson()) ? event.payloadJson() : "{}");
		}
		catch (Exception ex) {
			throw CheckedException.fail("任务审批结果事件负载解析失败, eventKey=" + event.eventKey());
		}
	}

	/**
	 * 幂等创建统一运行时 RuntimeRun 并回填 agent_task_run.runtime_run_id。
	 * clientRequestId 复用任务幂等键：重复触发命中 (tenantId, ownerType, ownerId, clientRequestId) 唯一键
	 * 返回既有 Run，不重复创建；任务幂等键取值可枚举，幂等域必须限本租户内，否则可被跨租户抢占。
	 *
	 * <p>PR-6：执行主体用身份解析得到的员工 Principal（IAM 侧实况，定义快照可能滞后或为空），
	 * 凭据本体不落库——RuntimeRun 无凭据字段，执行期由运行时按 Principal 自行换取。</p>
	 *
	 * <p>任务定义固定绑定 digitalEmployeeId + employeeReleaseId，运行记录一并落 digitalEmployeeId，
	 * 运行台账才能按数字员工精确归集（同一智能体的多个 Release 绑定是不同数字员工，
	 * 只按 agentId 会混在一起）。</p>
	 */
	private void attachRuntimeRun(AgentTaskDefinition definition, AgentTaskRun taskRun, String principalId) {
		RuntimeRunResp runtimeRun = runtimeRunService.create(parseTenantId(taskRun.getTenantId()), principalId,
				new RuntimeRunCreateReq(taskRun.getIdempotencyKey(), "DIGITAL_EMPLOYEE",
						definition.getDigitalEmployeeId(), definition.getDigitalEmployeeId(),
						definition.getEmployeeReleaseId(), null, null,
						taskQuery(definition), RUNTIME_RUN_MODE, null, null));
		AgentTaskRun update = new AgentTaskRun();
		update.setId(taskRun.getId());
		update.setRuntimeRunId(runtimeRun.id());
		taskRunMapper.updateById(update);
		taskRun.setRuntimeRunId(runtimeRun.id());
		log.info("任务运行已关联统一运行时。taskRunId={}, runtimeRunId={}, runtimeState={}", taskRun.getId(),
				runtimeRun.id(), runtimeRun.state());
	}

	private String taskQuery(AgentTaskDefinition definition) {
		return StringUtils.hasText(definition.getTaskDescription()) ? definition.getTaskDescription()
				: definition.getTaskName();
	}

	private String parseTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			throw CheckedException.badRequest("任务运行缺少租户信息, 无法创建统一运行时 Run");
		}
		return tenantId.trim();
	}

	/** 受限执行身份解析结果：principalId 供 RuntimeRun 关联，authRevision 供守卫比对。 */
	private record ExecutionIdentity(String principalId, Long authRevision) {
	}

}
