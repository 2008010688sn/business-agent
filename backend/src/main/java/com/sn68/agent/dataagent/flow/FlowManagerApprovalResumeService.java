/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.repository.DataAgentFlowInstanceMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeApprovalState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.service.chat.ChatMessageService;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 消费能力网关管理端审批结果：按 FLOW 实例冻住的上下文续跑，写入身份为填单人。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowManagerApprovalResumeService {

	static final String APPROVAL_SOURCE_GATEWAY = "CAPABILITY_GATEWAY";

	private final FlowEngine flowEngine;

	private final FlowInstanceService instanceService;

	private final DataAgentFlowInstanceMapper instanceMapper;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final FlowManagerApprovalIdentityRestorer identityRestorer;

	private final ChatMessageService chatMessageService;

	private final ObjectMapper objectMapper;

	@EventListener
	public void onApprovalDecided(RuntimeOutboxEvent event) {
		if (event == null || !RuntimeEventType.APPROVAL_DECIDED.getValue().equals(event.eventType())) {
			return;
		}
		JsonNode payload = readPayload(event);
		if (!APPROVAL_SOURCE_GATEWAY.equals(payload.path("source").asText(null))) {
			return;
		}
		String sourceRefId = payload.path("sourceRefId").asText(null);
		long approvalId = payload.path("approvalId").asLong(0);
		if (!StringUtils.hasText(sourceRefId) || approvalId <= 0) {
			log.warn("能力网关审批结果缺少实例或审批ID, eventKey={}", event.eventKey());
			return;
		}
		DataAgentFlowInstance instance = instanceMapper.selectById(Long.valueOf(sourceRefId.trim()));
		if (instance == null || Boolean.TRUE.equals(instance.getDeleted())) {
			log.info("审批续跑跳过：FLOW 实例不存在. sourceRefId={}", sourceRefId);
			return;
		}
		if (event.tenantId() != null && StringUtils.hasText(instance.getTenantId())
				&& !String.valueOf(event.tenantId()).equals(instance.getTenantId().trim())) {
			log.warn("审批续跑拒绝：租户不匹配. approvalId={}, flowInstanceId={}", approvalId, instance.getId());
			return;
		}
		Map<String, Object> waiting = readMap(instance.getWaitingPayload());
		if (!FlowInstanceStatus.WAITING.name().equals(instance.getStatus())
				|| !"APPROVAL".equalsIgnoreCase(text(waiting.get("action")))) {
			log.info("审批续跑跳过：实例不在管理端审批等待. flowInstanceId={}, status={}", instance.getId(),
					instance.getStatus());
			return;
		}
		if (!Long.valueOf(approvalId).equals(longValue(waiting.get("approvalId")))) {
			log.warn("审批续跑拒绝：等待中的审批ID不匹配. approvalId={}, flowInstanceId={}", approvalId,
					instance.getId());
			return;
		}
		String state = payload.path("state").asText("");
		if (RuntimeApprovalState.REJECTED.getValue().equals(state)) {
			failRejected(instance, payload);
			return;
		}
		if (!RuntimeApprovalState.APPROVED.getValue().equals(state)) {
			return;
		}
		resumeApproved(instance, approvalId);
	}

	private void resumeApproved(DataAgentFlowInstance instance, long approvalId) {
		DataAgentSkill skill = skillMapper.selectById(instance.getSkillId());
		DataAgentSkillVersion version = skillVersionMapper.selectById(instance.getSkillVersionId());
		if (skill == null || version == null || Boolean.TRUE.equals(skill.getDeleted())
				|| Boolean.TRUE.equals(version.getDeleted())) {
			log.warn("审批续跑失败：Skill 版本不可用. flowInstanceId={}", instance.getId());
			return;
		}
		Map<String, Object> snapshot = callerSnapshot(instance);
		AgentRequest request = resumeRequest(instance, snapshot, approvalId);
		try {
			FlowExecutionResult result = identityRestorer.runAsCaller(instance, snapshot,
					() -> flowEngine.execute(request, skill, version, null));
			persistResult(instance, result);
		}
		catch (CheckedException ex) {
			log.warn("审批续跑未能执行写入，实例保持等待. flowInstanceId={}, message={}", instance.getId(),
					ex.getMessage());
			persistWaitingHint(instance, "审批已通过，但还原填单人身份失败，请稍后重试或联系管理员。"
					+ ex.getMessage());
		}
	}

	private void failRejected(DataAgentFlowInstance instance, JsonNode payload) {
		String comment = firstText(payload.path("decisionComment").asText(null), payload.path("comment").asText(null),
				"管理端已驳回");
		try {
			instanceService.advance(instance, FlowInstanceStatus.FAILED, instance.getCurrentNodeId(),
					readMap(instance.getContextData()), Map.of(), instance.getIdempotencyKey(),
					"approval-reject:" + instance.getId(), "APPROVAL_REJECTED", comment, java.time.Instant.now());
		}
		catch (RuntimeException ex) {
			log.warn("驳回后推进 FLOW 失败. flowInstanceId={}", instance.getId(), ex);
		}
		persistWaitingHint(instance, "管理端已驳回本次提交：" + comment);
	}

	private AgentRequest resumeRequest(DataAgentFlowInstance instance, Map<String, Object> snapshot, long approvalId) {
		Long ownerId = longValue(snapshot.get("ownerId"));
		Long releaseId = longValue(snapshot.get("releaseId"));
		return AgentRequest.builder()
			.agentId(String.valueOf(instance.getAgentId()))
			.threadId(instance.getThreadId())
			.runtimeRequestId("approval-resume:" + approvalId)
			.flowInstanceId(String.valueOf(instance.getId()))
			.flowAction(new FlowAction("approval-resume", "APPROVAL_RESUME", true, Map.of("approvalId", approvalId)))
			.userIdSnapshot(firstText(text(snapshot.get("userId")), instance.getUserId()))
			.userNickNameSnapshot(text(snapshot.get("userNickName")))
			.tenantIdSnapshot(firstText(text(snapshot.get("tenantId")), instance.getTenantId()))
			.tenantCodeSnapshot(text(snapshot.get("tenantCode")))
			.clientIdSnapshot(text(snapshot.get("clientId")))
			.ownerType(text(snapshot.get("ownerType")))
			.ownerId(ownerId)
			.releaseId(releaseId)
			.build();
	}

	private Map<String, Object> callerSnapshot(DataAgentFlowInstance instance) {
		Map<String, Object> context = readMap(instance.getContextData());
		Object snapshot = context.get("runtime") instanceof Map<?, ?> runtime ? ((Map<?, ?>) runtime).get("callerSnapshot")
				: null;
		return snapshot instanceof Map<?, ?> map ? copyMap(map) : Map.of();
	}

	private void persistResult(DataAgentFlowInstance instance, FlowExecutionResult result) {
		if (result == null) {
			return;
		}
		String text = StringUtils.hasText(result.text()) ? result.text() : "流程已继续。";
		saveAssistantMessage(instance, text, result.message());
	}

	private void persistWaitingHint(DataAgentFlowInstance instance, String text) {
		saveAssistantMessage(instance, text, null);
	}

	private void saveAssistantMessage(DataAgentFlowInstance instance, String text, AgentUiMessage uiMessage) {
		if (instance.getSessionId() == null) {
			return;
		}
		try {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("uiSchemaVersion", "agent-ui/v2");
			metadata.put("uiSource", "flow-approval-resume");
			metadata.put("messageType", AgentSessionConstant.MESSAGE_TYPE_SKILL_FLOW);
			if (uiMessage != null) {
				metadata.put("agentUi", uiMessage);
			}
			chatMessageService.saveMessage(DataChatMessage.builder()
				.sessionId(instance.getSessionId())
				.role("assistant")
				.content(text)
				.messageType(AgentSessionConstant.MESSAGE_TYPE_SKILL_FLOW)
				.metadata(objectMapper.writeValueAsString(metadata))
				.build(), instance.getAgentId());
		}
		catch (Exception ex) {
			log.error("审批续跑结果落会话失败. flowInstanceId={}", instance.getId(), ex);
		}
	}

	private JsonNode readPayload(RuntimeOutboxEvent event) {
		try {
			return objectMapper.readTree(StringUtils.hasText(event.payloadJson()) ? event.payloadJson() : "{}");
		}
		catch (Exception ex) {
			throw CheckedException.fail("能力网关审批结果事件负载解析失败, eventKey=" + event.eventKey());
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readMap(String json) {
		if (!StringUtils.hasText(json)) {
			return new LinkedHashMap<>();
		}
		try {
			Object value = objectMapper.readValue(json, Map.class);
			return value instanceof Map<?, ?> map ? copyMap(map) : new LinkedHashMap<>();
		}
		catch (Exception ex) {
			return new LinkedHashMap<>();
		}
	}

	private Map<String, Object> copyMap(Map<?, ?> source) {
		Map<String, Object> result = new LinkedHashMap<>();
		source.forEach((key, value) -> result.put(String.valueOf(key), value));
		return result;
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private static String firstText(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private static Long longValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null || !StringUtils.hasText(String.valueOf(value))) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
