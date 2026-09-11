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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskDeliveryService;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 运行终态 → 任务台账回写：监听 Outbox 派发器发布的 {@link RuntimeOutboxEvent}，
 * 把权威 Run 的终态同步到 {@code agent_task_run.run_status}，关联键为 {@code runtime_run_id}。
 *
 * <p>不新增扫描作业：Run 收敛为终态时（调度器 {@code maybeFinishRun}、镜像收尾、超时守护、
 * 空闲取消）已在 CAS 成功后旁路写入终态 Outbox 消息，接消费方即可，既及时又不引入第二套轮询。</p>
 *
 * <p><b>幂等（Outbox 为 at-least-once，同一事件可能被重复派发）：</b>唯一闸门是
 * {@code markTerminalByRuntimeRunId} 的 {@code run_status IN ('PENDING','RUNNING')} 谓词 ——
 * 重复消费的第二次更新命中 0 行，既不会重复推进，也不会覆盖 SKIPPED 等其他链路写入的终态。
 * 事前按 runtime_run_id 的查询只用于定位台账行与输出可追踪日志，不承担幂等职责。</p>
 *
 * <p><b>失败模式：</b>回写异常<b>不吞</b>，原样抛回派发器，让该 Outbox 记录进入退避重试、
 * 重试耗尽置 DEAD 可见（与 {@link DefaultTaskRunLauncher#onApprovalDecided} 同口径）。
 * 台账是任务中心与工作空间的展示来源，宁可让消息重投也不能静默停留在「在跑」。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskRunTerminalSyncListener {

	/**
	 * 运行终态事件 → 任务台账终态。任务侧没有独立超时态，超时按失败归集（与守护作业同步口径一致）。
	 */
	private static final Map<String, TaskRunStatus> TERMINAL_STATUS = Map.of(
			RuntimeEventType.RUN_SUCCEEDED.getValue(), TaskRunStatus.SUCCESS,
			RuntimeEventType.RUN_FAILED.getValue(), TaskRunStatus.FAILED,
			RuntimeEventType.RUN_TIMED_OUT.getValue(), TaskRunStatus.FAILED,
			RuntimeEventType.RUN_CANCELLED.getValue(), TaskRunStatus.CANCELLED);

	/** 非成功终态写入台账的中文原因，与错误码拼成 error_message；成功终态不写该列。 */
	private static final Map<String, String> TERMINAL_REASON = Map.of(
			RuntimeEventType.RUN_FAILED.getValue(), "运行时已收敛为失败终态",
			RuntimeEventType.RUN_TIMED_OUT.getValue(), "运行时已收敛为超时终态",
			RuntimeEventType.RUN_CANCELLED.getValue(), "运行时已收敛为取消终态");

	private final AgentTaskRunService taskRunService;

	private final AgentTaskRunMapper taskRunMapper;

	private final ObjectMapper objectMapper;

	private final AgentTaskDeliveryService deliveryService;

	/**
	 * 消费 Outbox 派发的运行终态事件。非终态事件与非任务链路发起的运行直接跳过，不报错也不误伤。
	 */
	@EventListener
	public void onRunTerminal(RuntimeOutboxEvent event) {
		if (event == null) {
			return;
		}
		if (!StringUtils.hasText(event.eventType())) {
			return;
		}
		String eventType = event.eventType().trim().toUpperCase();
		TaskRunStatus target = TERMINAL_STATUS.get(eventType);
		if (target == null) {
			return;
		}
		Long runtimeRunId = requireRuntimeRunId(event);
		AgentTaskRun taskRun = taskRunMapper.findByRuntimeRunId(runtimeRunId);
		if (taskRun == null) {
			// 对话、评测等非任务链路发起的运行没有台账行，属正常路径
			log.debug("运行终态事件无关联任务运行, 跳过台账回写。runtimeRunId={}, eventType={}, eventKey={}",
					runtimeRunId, eventType, event.eventKey());
			return;
		}
		syncTerminal(event, eventType, target, taskRun);
		// 投递独立于台账是否推进：Outbox 重投时 markTerminal 可能 0 行，投递必须仍幂等补写
		deliveryService.recordWebInbox(taskRun);
	}

	/** 推进台账终态并留痕。异常向上抛给派发器计入重试，绝不 catch 后假装成功。 */
	private void syncTerminal(RuntimeOutboxEvent event, String eventType, TaskRunStatus target,
			AgentTaskRun taskRun) {
		Long runtimeRunId = taskRun.getRuntimeRunId();
		boolean advanced;
		try {
			advanced = taskRunService.markTerminalByRuntimeRun(runtimeRunId, target,
					terminalMessage(event, eventType));
		}
		catch (RuntimeException ex) {
			log.error("任务台账终态回写失败, 事件将由 Outbox 退避重试。taskRunId={}, runtimeRunId={}, status={}, "
					+ "eventKey={}", taskRun.getId(), runtimeRunId, target.getValue(), event.eventKey(), ex);
			throw ex;
		}
		if (!advanced) {
			// 事件重投或台账已被其他链路收敛：状态谓词兜住了重复推进，属正常路径
			log.debug("任务台账终态回写未命中, 事件重投或台账已是终态。taskRunId={}, runtimeRunId={}, currentStatus={},"
					+ " eventKey={}", taskRun.getId(), runtimeRunId, taskRun.getRunStatus(), event.eventKey());
			return;
		}
		log.info("任务台账已按运行终态回写。taskRunId={}, runtimeRunId={}, status={}, eventKey={}", taskRun.getId(),
				runtimeRunId, target.getValue(), event.eventKey());
	}

	/**
	 * 终态事件必须带运行ID：缺失或非数字属脏消息，失败关闭抛出后进 Outbox 重试直至 DEAD，
	 * 让坏消息可见而不是被静默丢弃。
	 */
	private Long requireRuntimeRunId(RuntimeOutboxEvent event) {
		if (!StringUtils.hasText(event.runId())) {
			throw CheckedException.fail("运行终态事件缺少运行ID, 无法回写任务台账, eventKey=" + event.eventKey());
		}
		try {
			return Long.valueOf(event.runId().trim());
		}
		catch (NumberFormatException ex) {
			throw CheckedException.fail("运行终态事件的运行ID不是数字, 无法回写任务台账, runId=" + event.runId()
					+ ", eventKey=" + event.eventKey());
		}
	}

	/** 失败/超时/取消写入「[错误码] 中文原因」；成功终态返回 null，不往 error_message 里塞噪音。 */
	private String terminalMessage(RuntimeOutboxEvent event, String eventType) {
		String reason = TERMINAL_REASON.get(eventType);
		return reason == null ? null : "[" + errorCode(event, eventType) + "] " + reason;
	}

	/**
	 * 取负载里的错误码，缺失或负载损坏时退回事件类型。
	 *
	 * <p>终态语义完全由 eventType 决定，负载只是错误码这一项修饰：负载坏掉时降级记 warn 继续回写，
	 * 好过让台账因为一个脏 JSON 永远停在「在跑」。</p>
	 */
	private String errorCode(RuntimeOutboxEvent event, String fallback) {
		if (!StringUtils.hasText(event.payloadJson())) {
			return fallback;
		}
		try {
			JsonNode payload = objectMapper.readTree(event.payloadJson());
			String errorCode = payload.path("errorCode").asText(null);
			return StringUtils.hasText(errorCode) ? errorCode.trim() : fallback;
		}
		catch (Exception ex) {
			log.warn("运行终态事件负载解析失败, 错误码按事件类型回退。runId={}, eventKey={}", event.runId(),
					event.eventKey(), ex);
			return fallback;
		}
	}

}
