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
package com.sn68.agent.dataagent.im.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.im.adapter.ImAdapter;
import com.sn68.agent.dataagent.im.adapter.ImAdapterRegistry;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImConversationBinding;
import com.sn68.agent.dataagent.im.entity.AgentImMessage;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.im.repository.AgentImConversationBindingMapper;
import com.sn68.agent.dataagent.im.repository.AgentImMessageMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 运行时事件 → IM 回传：监听 Outbox 派发器发布的 {@link RuntimeOutboxEvent}，
 * 把审批请求/审批结果/运行完成/澄清请求转成 IM 出站文本卡片，推送到该工作空间
 * 绑定的 IM 会话（agent_im_conversation_binding，经既有适配器 sendReply 出站链路）。
 *
 * <p><b>失败模式（Outbox 派发为 at-least-once）：</b>
 * <ul>
 * <li>事件类型不支持 / 缺租户或工作空间 / 该工作空间没有任何绑定会话：debug 日志跳过，不报错；</li>
 * <li>绑定会话存在但连接器缺失/未启用/适配器缺失：按失败处理（warn 日志 + FAILED 出站消息），
 * 这些是配置事故，静默跳过会让通知整片消失且无人察觉；</li>
 * <li>推送失败：出站消息落 FAILED（带错误摘要）并记 error 日志，<b>异常不向派发器抛出</b>。
 * 同一事件上还挂着主链路监听方（如 DefaultTaskRunLauncher 的审批通过续发），Spring 事件是同步串行派发，
 * 本监听方抛出会中断后续监听方，使一个坏掉的 IM Webhook 拖住审批后的任务续发，
 * 且 outbox 重试耗尽置 DEAD 后任务再也不会续发。IM 回传是旁路通知，不能成为主链路的单点；
 * 失败留痕在 agent_im_message（FAILED + errorMessage）可查可补发。</li>
 * </ul>
 *
 * <p>幂等键为 {@code 租户:provider:连接器编码:RUNTIME:eventKey:会话}，事件重投时已成功送达的会话不会重复打扰。
 *
 * <p>出站依赖连接器配置中的 replyWebhook/webhook（适配器 sendReply 的既有语义，
 * 未配置时适配器静默跳过）；推送文案已由发布方脱敏（payloadJson 不含凭据与原始参数）。
 *
 * <p>授权模型: 内部事件无需授权
 * 本入口只消费进程内 Outbox 事件做旁路 IM 回传，不接受外部调用、不切换执行身份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImRuntimeEventNotifier {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	/** 审批请求（单聊附「同意/拒绝」可执行指令，群聊只给摘要，见 renderText）。 */
	static final String TYPE_APPROVAL_REQUESTED = "APPROVAL_REQUESTED";

	/** 审批结果：兼容契约名 APPROVAL_RESULT 与运行时事件枚举名 APPROVAL_DECIDED。 */
	static final Set<String> TYPES_APPROVAL_RESULT = Set.of("APPROVAL_RESULT", "APPROVAL_DECIDED");

	/**
	 * 运行终态：兼容契约名 RUN_COMPLETED 与运行时事件枚举名。
	 * RuntimeOutboxService 写入的终态事件共四种（RUN_SUCCEEDED / RUN_FAILED / RUN_CANCELLED / RUN_TIMED_OUT），
	 * 少收哪一种，IM 侧就会对该终态静默无通知。
	 */
	static final Set<String> TYPES_RUN_COMPLETED = Set.of("RUN_COMPLETED", "RUN_SUCCEEDED", "RUN_FAILED",
			"RUN_CANCELLED", "RUN_TIMED_OUT");

	/** 澄清请求。 */
	static final String TYPE_CLARIFY_REQUESTED = "CLARIFY_REQUESTED";

	private final AgentImConversationBindingMapper bindingMapper;

	private final AgentImConnectorMapper connectorMapper;

	private final AgentImMessageMapper messageMapper;

	private final ImAdapterRegistry adapterRegistry;

	private final NotificationJsonSupport jsonSupport;

	private final ObjectMapper objectMapper;

	/**
	 * 消费 Outbox 派发事件。本方法不向派发器抛异常（原因见类注释），失败以 FAILED 出站消息与 error 日志留痕。
	 */
	@EventListener
	public void onRuntimeOutboxEvent(RuntimeOutboxEvent event) {
		if (event == null || !supported(event.eventType())) {
			return;
		}
		if (event.tenantId() == null) {
			log.debug("运行时事件缺少租户, 跳过 IM 回传。eventType={}, eventKey={}", event.eventType(),
					event.eventKey());
			return;
		}
		// PR-1：workspace 维度拆除后按租户广播；待数字员工域（PR-5）接入 digitalEmployeeId 后收敛为精确路由。
		List<AgentImConversationBinding> bindings = bindingMapper
			.findEnabledByTenant(String.valueOf(event.tenantId()));
		if (bindings.isEmpty()) {
			log.debug("租户未绑定 IM 会话, 跳过运行时事件回传。tenantId={}, eventType={}, "
					+ "eventKey={}", event.tenantId(), event.eventType(), event.eventKey());
			return;
		}
		int failed = 0;
		for (AgentImConversationBinding binding : bindings) {
			// 文案按会话类型逐个渲染：群聊不得携带可执行审批指令（见 renderText）。
			if (!deliverToBinding(event, binding, renderText(event, allowsExecutableCommand(binding)))) {
				failed++;
			}
		}
		if (failed > 0) {
			// TODO(W6→通知治理): FAILED 出站消息目前只能人工补发，待通知栈去留结论明确后接入统一重投。
			log.error("运行时事件 IM 回传存在失败会话, 出站消息已置 FAILED 待补发。eventType={}, eventKey={}, failed={}/{}",
					event.eventType(), event.eventKey(), failed, bindings.size());
		}
	}

	/**
	 * 向单个绑定会话投递。返回 false 表示本次未送达（已落 FAILED 待补发）。
	 *
	 * <p>连接器缺失/未启用、provider 漂移、适配器缺失同样按失败处理：这些是配置事故而非"无需通知"，
	 * 原先只 debug 一行并计为投递成功，会让审批与运行终态通知在 IM 侧整片消失且无人察觉。
	 */
	private boolean deliverToBinding(RuntimeOutboxEvent event, AgentImConversationBinding binding, String text) {
		AgentImConnector connector = connectorMapper.findEnabledByTenantAndConnectorCode(binding.getTenantId(),
				binding.getConnectorCode());
		if (connector == null || !connector.getProvider().equalsIgnoreCase(binding.getProvider())) {
			log.warn("IM 会话绑定的连接器不存在、未启用或 provider 不一致, 运行时事件无法回传。tenantId={}, "
					+ "connectorCode={}, bindingProvider={}, bindingId={}, eventType={}, eventKey={}",
					binding.getTenantId(), binding.getConnectorCode(), binding.getProvider(), binding.getId(),
					event.eventType(), event.eventKey());
			return markUndelivered(event, binding, text, "IM 连接器不存在、未启用或 provider 与绑定不一致");
		}
		ImAdapter adapter;
		try {
			adapter = adapterRegistry.get(connector.getProvider());
		}
		catch (Exception ex) {
			log.warn("IM 平台适配器缺失, 运行时事件无法回传。tenantId={}, provider={}, connectorCode={}, "
					+ "eventType={}, eventKey={}", binding.getTenantId(), connector.getProvider(),
					binding.getConnectorCode(), event.eventType(), event.eventKey(), ex);
			return markUndelivered(event, binding, text, "IM 平台适配器缺失: " + connector.getProvider());
		}
		AgentImMessage outbound = claimOutbound(event, binding, text);
		if (outbound == null) {
			// 幂等命中：该事件已成功推送过此会话（at-least-once 重投场景）。
			return true;
		}
		try {
			Map<String, Object> config = jsonSupport.readEncryptedMap(connector.getEncryptedConfig());
			adapter.sendReply(config, syntheticMessage(binding), text);
			updateStatus(outbound, ImConstants.MESSAGE_STATUS_SUCCESS, null);
			return true;
		}
		catch (Exception ex) {
			log.warn("运行时事件 IM 推送失败。tenantId={}, connectorCode={}, conversationId={}, eventKey={}",
					binding.getTenantId(), binding.getConnectorCode(), binding.getExternalConversationId(),
					event.eventKey(), ex);
			updateStatus(outbound, ImConstants.MESSAGE_STATUS_FAILED, ex.getMessage());
			return false;
		}
	}

	/**
	 * 配置类不可达：同样落 FAILED 出站消息留痕，让降级在 agent_im_message 里可见可补发。
	 * 幂等键命中已成功记录时说明此前已送达，按成功返回，不重复留痕。
	 */
	private boolean markUndelivered(RuntimeOutboxEvent event, AgentImConversationBinding binding, String text,
			String reason) {
		AgentImMessage outbound = claimOutbound(event, binding, text);
		if (outbound == null) {
			return true;
		}
		updateStatus(outbound, ImConstants.MESSAGE_STATUS_FAILED, reason);
		return false;
	}

	/**
	 * 只有单聊投递可执行审批指令，其余会话类型一律只给摘要。
	 *
	 * <p>这里按白名单而不是「非 GROUP 即放行」：回调链路虽然把会话类型归一成 SINGLE/GROUP 两值，
	 * 但会话绑定管理接口（{@code ImConversationBindingService.apply}）只校验非空并转大写，
	 * 任意自定义取值（新平台的 TOPIC/CHANNEL、脏数据、拼写错误）都能落库；黑名单写法会把这些
	 * 未知的多人会话当成单聊，把审批指令格式推给不特定成员。未知类型按失败关闭处理。</p>
	 */
	private boolean allowsExecutableCommand(AgentImConversationBinding binding) {
		return ImConstants.CONVERSATION_SINGLE.equalsIgnoreCase(binding.getConversationType());
	}

	/**
	 * 出站消息落库即幂等占位：幂等键含事件键与会话，重投时已 SUCCESS 的直接跳过，
	 * FAILED/PENDING 的复用原记录重试发送。
	 */
	private AgentImMessage claimOutbound(RuntimeOutboxEvent event, AgentImConversationBinding binding, String text) {
		String idempotencyKey = binding.getTenantId() + ":" + binding.getProvider() + ":"
				+ binding.getConnectorCode() + ":" + ImConstants.OUTBOUND_KEY_RUNTIME_EVENT + ":" + event.eventKey()
				+ ":" + binding.getExternalConversationId();
		AgentImMessage existing = messageMapper.findByIdempotencyKey(idempotencyKey);
		if (existing != null) {
			return ImConstants.MESSAGE_STATUS_SUCCESS.equals(existing.getStatus()) ? null : existing;
		}
		AgentImMessage outbound = AgentImMessage.builder()
			.idempotencyKey(idempotencyKey)
			.tenantId(binding.getTenantId())
			.provider(binding.getProvider())
			.connectorCode(binding.getConnectorCode())
			.conversationType(binding.getConversationType())
			.externalConversationId(binding.getExternalConversationId())
			.direction(ImConstants.DIRECTION_OUTBOUND)
			.messageType("TEXT")
			.content(text)
			.responseContent(text)
			.agentId(binding.getAgentId())
			.runtimeRequestId(event.eventKey())
			.status(ImConstants.MESSAGE_STATUS_PENDING)
			.build();
		try {
			messageMapper.insert(outbound);
			return outbound;
		}
		catch (DuplicateKeyException ex) {
			AgentImMessage duplicated = messageMapper.findByIdempotencyKey(idempotencyKey);
			if (duplicated != null) {
				return ImConstants.MESSAGE_STATUS_SUCCESS.equals(duplicated.getStatus()) ? null : duplicated;
			}
			throw ex;
		}
	}

	private void updateStatus(AgentImMessage outbound, String status, String errorMessage) {
		outbound.setStatus(status);
		outbound.setErrorMessage(errorMessage == null ? null
				: errorMessage.substring(0, Math.min(errorMessage.length(), 500)));
		try {
			messageMapper.updateById(outbound);
		}
		catch (Exception ex) {
			log.error("运行时事件出站消息状态更新失败。idempotencyKey={}, status={}", outbound.getIdempotencyKey(),
					status, ex);
		}
	}

	/**
	 * 构造出站用的合成消息：仅携带会话定位字段，回复 Webhook 取连接器配置（sendReply 既有回退语义）。
	 */
	private ImCallbackMessage syntheticMessage(AgentImConversationBinding binding) {
		return new ImCallbackMessage(binding.getProvider(), binding.getConnectorCode(), null,
				binding.getConversationType(), binding.getExternalConversationId(), null, null, null, "TEXT", null,
				false, null, Map.of());
	}

	private boolean supported(String eventType) {
		if (!StringUtils.hasText(eventType)) {
			return false;
		}
		String normalized = eventType.trim().toUpperCase();
		return TYPE_APPROVAL_REQUESTED.equals(normalized) || TYPES_APPROVAL_RESULT.contains(normalized)
				|| TYPES_RUN_COMPLETED.contains(normalized) || TYPE_CLARIFY_REQUESTED.equals(normalized);
	}

	/**
	 * 事件 → 文本卡片。负载字段缺失时降级为通用文案，不因负载问题丢事件。
	 *
	 * @param allowExecutableCommand 是否允许在文案里给出可直接执行的审批指令格式。
	 * 群聊一律为 false：审批卡片会推给同租户下全部启用会话，群成员未必持有
	 * {@code ai-agent:approval:review}，把「回复『同意 &lt;ID&gt;』」写进群消息等于公开审批入口，
	 * 且审批ID 自增可枚举。群聊只给摘要并引导到控制台或私聊。
	 */
	private String renderText(RuntimeOutboxEvent event, boolean allowExecutableCommand) {
		Map<String, Object> payload = readPayload(event.payloadJson());
		String type = event.eventType().trim().toUpperCase();
		if (TYPE_APPROVAL_REQUESTED.equals(type)) {
			String approvalId = firstText(text(payload, "approvalId"), text(payload, "id"));
			StringBuilder builder = new StringBuilder("【审批请求】");
			if (approvalId != null) {
				builder.append('#').append(approvalId);
			}
			appendLine(builder, "能力", firstText(text(payload, "capabilityCode"), text(payload, "capability")));
			appendLine(builder, "风险等级", text(payload, "riskLevel"));
			appendLine(builder, "运行", event.runId());
			builder.append('\n');
			if (approvalId != null && allowExecutableCommand) {
				builder.append("回复「").append(ImConstants.APPROVAL_COMMAND_APPROVE).append(' ').append(approvalId)
					.append("」或「").append(ImConstants.APPROVAL_COMMAND_REJECT).append(' ').append(approvalId)
					.append(" 原因」处理该审批。");
			}
			else if (approvalId != null) {
				builder.append("请前往控制台处理该审批，或与我私聊完成审批（需具备审批权限）。");
			}
			else {
				builder.append("请前往控制台处理该审批。");
			}
			return builder.toString();
		}
		if (TYPES_APPROVAL_RESULT.contains(type)) {
			String approvalId = firstText(text(payload, "approvalId"), text(payload, "id"));
			StringBuilder builder = new StringBuilder("【审批结果】");
			if (approvalId != null) {
				builder.append('#').append(approvalId);
			}
			appendLine(builder, "结果", firstText(text(payload, "state"), text(payload, "decision")));
			appendLine(builder, "审批人", text(payload, "approver"));
			appendLine(builder, "意见", firstText(text(payload, "decisionComment"), text(payload, "comment")));
			return builder.toString();
		}
		if (TYPES_RUN_COMPLETED.contains(type)) {
			String state = firstText(text(payload, "state"), text(payload, "runState"), stateFromEventType(type));
			StringBuilder builder = new StringBuilder("【任务运行完成】");
			appendLine(builder, "运行", firstText(event.runId(), text(payload, "runId")));
			appendLine(builder, "结果", state);
			appendLine(builder, "摘要", firstText(text(payload, "summary"), text(payload, "message")));
			return builder.toString();
		}
		StringBuilder builder = new StringBuilder("【需要补充信息】");
		appendLine(builder, "运行", event.runId());
		String question = firstText(text(payload, "question"), text(payload, "prompt"), text(payload, "message"));
		builder.append('\n').append(question == null ? "智能体需要您补充信息后才能继续。" : question)
			.append("\n请直接回复补充内容。");
		return builder.toString();
	}

	/** 负载未带状态时按事件类型推导终态文案；契约名 RUN_COMPLETED 不含具体终态，返回 null 交由上层省略该行。 */
	private String stateFromEventType(String type) {
		return switch (type) {
			case "RUN_SUCCEEDED" -> "SUCCEEDED";
			case "RUN_FAILED" -> "FAILED";
			case "RUN_CANCELLED" -> "CANCELLED";
			case "RUN_TIMED_OUT" -> "TIMED_OUT";
			default -> null;
		};
	}

	private Map<String, Object> readPayload(String payloadJson) {
		if (!StringUtils.hasText(payloadJson)) {
			return Map.of();
		}
		try {
			Map<String, Object> payload = objectMapper.readValue(payloadJson, MAP_TYPE);
			return payload == null ? Map.of() : payload;
		}
		catch (Exception ex) {
			log.warn("运行时事件负载解析失败, 按空负载渲染文案。length={}", payloadJson.length(), ex);
			return Map.of();
		}
	}

	private void appendLine(StringBuilder builder, String label, String value) {
		if (StringUtils.hasText(value)) {
			builder.append('\n').append(label).append('：').append(value.trim());
		}
	}

	private String text(Map<String, Object> payload, String key) {
		Object value = payload.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

}
