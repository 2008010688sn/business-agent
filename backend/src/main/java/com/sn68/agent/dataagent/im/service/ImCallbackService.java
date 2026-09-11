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
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorClassifier;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorCode;
import com.sn68.agent.dataagent.channel.entity.AgentChannelSessionMapping;
import com.sn68.agent.dataagent.channel.service.ChannelSessionMappingService;
import com.sn68.agent.dataagent.im.ChannelInteractionCapability;
import com.sn68.agent.dataagent.im.adapter.ImAdapter;
import com.sn68.agent.dataagent.im.adapter.ImAdapterRegistry;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.dto.ImCallbackResponse;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImConversationBinding;
import com.sn68.agent.dataagent.im.entity.AgentImMessage;
import com.sn68.agent.dataagent.im.entity.AgentImUserIdentity;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.im.repository.AgentImConversationBindingMapper;
import com.sn68.agent.dataagent.im.repository.AgentImMessageMapper;
import com.sn68.agent.dataagent.im.repository.AgentImUserIdentityMapper;
import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextReq;
import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextResp;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.dataagent.service.agent.AgentInvocationService;
import com.sn68.agent.dataagent.service.agent.AgentInvocationResult;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.tool.ToolInvocationException;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * IM 回调处理服务。
 */
@Slf4j
@Service
public class ImCallbackService {

	private static final TypeReference<List<String>> WAKE_WORD_LIST = new TypeReference<>() {
	};

	private final AgentImConnectorMapper connectorMapper;

	private final AgentImConversationBindingMapper bindingMapper;

	private final AgentImUserIdentityMapper identityMapper;

	private final AgentImMessageMapper messageMapper;

	private final ImAdapterRegistry adapterRegistry;

	private final NotificationJsonSupport jsonSupport;

	private final ObjectMapper objectMapper;

	private final ChannelSessionMappingService channelSessionMappingService;

	private final DataChatSessionService chatSessionService;

	private final AgentInvocationService agentInvocationService;

	private final FlowTextRenderer flowTextRenderer;

	private final DingTalkCredentialService dingTalkCredentialService;

	private final DelegatedAuthContextService delegatedAuthContextService;

	private final ImApprovalCommandService approvalCommandService;

	private final ImUserBindSessionService bindSessionService;

	private final ImRuntimeConfigService runtimeConfigService;

	private final Executor imCallbackExecutor;

	@Autowired
	public ImCallbackService(AgentImConnectorMapper connectorMapper, AgentImConversationBindingMapper bindingMapper,
			AgentImUserIdentityMapper identityMapper, AgentImMessageMapper messageMapper, ImAdapterRegistry adapterRegistry,
			NotificationJsonSupport jsonSupport, ObjectMapper objectMapper,
			ChannelSessionMappingService channelSessionMappingService, DataChatSessionService chatSessionService,
			AgentInvocationService agentInvocationService, FlowTextRenderer flowTextRenderer,
			DingTalkCredentialService dingTalkCredentialService, DelegatedAuthContextService delegatedAuthContextService,
			ImApprovalCommandService approvalCommandService, ImUserBindSessionService bindSessionService,
			ImRuntimeConfigService runtimeConfigService,
			@Qualifier("imCallbackExecutor") Executor imCallbackExecutor) {
		this.connectorMapper = connectorMapper;
		this.bindingMapper = bindingMapper;
		this.identityMapper = identityMapper;
		this.messageMapper = messageMapper;
		this.adapterRegistry = adapterRegistry;
		this.jsonSupport = jsonSupport;
		this.objectMapper = objectMapper;
		this.channelSessionMappingService = channelSessionMappingService;
		this.chatSessionService = chatSessionService;
		this.agentInvocationService = agentInvocationService;
		this.flowTextRenderer = flowTextRenderer;
		this.dingTalkCredentialService = dingTalkCredentialService;
		this.delegatedAuthContextService = delegatedAuthContextService;
		this.approvalCommandService = approvalCommandService;
		this.bindSessionService = bindSessionService;
		this.runtimeConfigService = runtimeConfigService;
		this.imCallbackExecutor = imCallbackExecutor;
	}

	/**
	 * 兼容现有单元测试及旧构造方；生产 Bean 使用 Spring 生成的完整构造器。
	 * 该构造方不启用审批指令拦截（approvalCommandService 为 null 时消息一律走 Agent 链路）。
	 */
	public ImCallbackService(AgentImConnectorMapper connectorMapper, AgentImConversationBindingMapper bindingMapper,
			AgentImUserIdentityMapper identityMapper, AgentImMessageMapper messageMapper, ImAdapterRegistry adapterRegistry,
			NotificationJsonSupport jsonSupport, ObjectMapper objectMapper,
			ChannelSessionMappingService channelSessionMappingService, DataChatSessionService chatSessionService,
			AgentInvocationService agentInvocationService, DingTalkCredentialService dingTalkCredentialService,
			DelegatedAuthContextService delegatedAuthContextService, ImRuntimeConfigService runtimeConfigService) {
		this(connectorMapper, bindingMapper, identityMapper, messageMapper, adapterRegistry, jsonSupport, objectMapper,
				channelSessionMappingService, chatSessionService, agentInvocationService, new FlowTextRenderer(),
				dingTalkCredentialService, delegatedAuthContextService, null, null, runtimeConfigService, Runnable::run);
	}

	/**
	 * 测试构造方：启用绑定短码拦截。
	 */
	public ImCallbackService(AgentImConnectorMapper connectorMapper, AgentImConversationBindingMapper bindingMapper,
			AgentImUserIdentityMapper identityMapper, AgentImMessageMapper messageMapper, ImAdapterRegistry adapterRegistry,
			NotificationJsonSupport jsonSupport, ObjectMapper objectMapper,
			ChannelSessionMappingService channelSessionMappingService, DataChatSessionService chatSessionService,
			AgentInvocationService agentInvocationService, DingTalkCredentialService dingTalkCredentialService,
			DelegatedAuthContextService delegatedAuthContextService, ImRuntimeConfigService runtimeConfigService,
			ImUserBindSessionService bindSessionService) {
		this(connectorMapper, bindingMapper, identityMapper, messageMapper, adapterRegistry, jsonSupport, objectMapper,
				channelSessionMappingService, chatSessionService, agentInvocationService, new FlowTextRenderer(),
				dingTalkCredentialService, delegatedAuthContextService, null, bindSessionService, runtimeConfigService,
				Runnable::run);
	}

	/**
	 * 处理ImCallback。
	 *
	 * <p>租户定位规则：回调路径只携带 (provider, connectorCode)，同一编码可能存在于多个租户，
	 * 因此先取出全部候选连接器，逐个用各自密钥验签；验签通过的候选即持有正确密钥的连接器，
	 * 其 tenantId 才是本次回调的可信租户。任何租户与业务解析都必须发生在验签成功之后。
	 *
	 * <p><b>多候选一律拒绝：</b>验签通过的候选超过一个，说明不同租户配了同码同密钥，属配置事故；
	 * 静默择一会把消息投进错误租户，因此记 error 日志并整体拒绝，由人工消歧。
	 */
	public ImCallbackResponse handleCallback(String provider, String connectorCode, Map<String, String> headers,
			Map<String, String> queryParams, String rawBody) {
		List<AgentImConnector> candidates = connectorMapper.findCallbackCandidates(provider, connectorCode);
		if (candidates.isEmpty()) {
			throw CheckedException.notFound(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(), ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		runtimeConfigService.providerRuntimeConfig(candidates.get(0).getProvider());
		List<VerifiedCandidate> verified = new ArrayList<>();
		for (AgentImConnector candidate : candidates) {
			ImAdapter candidateAdapter = adapterRegistry.get(candidate.getProvider());
			Map<String, Object> candidateConfig = jsonSupport.readEncryptedMap(candidate.getEncryptedConfig());
			try {
				candidateAdapter.verify(candidate.getConnectorCode(), candidateConfig, headers, queryParams, rawBody);
			}
			catch (Exception ex) {
				log.debug("IM 回调候选连接器验签未通过, 继续尝试下一个候选。provider={}, connectorCode={}, tenantId={}",
						candidate.getProvider(), candidate.getConnectorCode(), candidate.getTenantId());
				continue;
			}
			verified.add(new VerifiedCandidate(candidate, candidateConfig, candidateAdapter));
		}
		if (verified.isEmpty()) {
			throw CheckedException.badRequest(ImErrorDict.CALLBACK_SIGNATURE_INVALID.getValue(),
					ImErrorDict.CALLBACK_SIGNATURE_INVALID.getLabel());
		}
		if (verified.size() > 1) {
			log.error("IM 回调同码连接器在多个租户同时验签通过, 无法确定归属租户, 已拒绝。provider={}, connectorCode={}, "
					+ "matchedTenantIds={}", provider, connectorCode,
					verified.stream().map(item -> item.connector().getTenantId()).toList());
			throw CheckedException.badRequest(ImErrorDict.CONNECTOR_CODE_AMBIGUOUS.getValue(),
					ImErrorDict.CONNECTOR_CODE_AMBIGUOUS.getLabel());
		}
		AgentImConnector connector = verified.get(0).connector();
		Map<String, Object> config = verified.get(0).config();
		ImAdapter adapter = verified.get(0).adapter();
		// 租户已唯一确定，此时才占用防重放凭据（verify 本身必须无副作用，否则会污染多候选判定）。
		adapter.claimReplayGuard(connector.getTenantId(), connector.getConnectorCode(), headers, queryParams, rawBody);
		// 验签已通过，此后 connector.tenantId 是本次回调的唯一可信租户来源。
		ImCallbackMessage message = adapter.parse(connector.getProvider(), connector.getConnectorCode(), rawBody);
		if (shouldSendAsync(config, message)) {
			dispatchCallback(connector, adapter, config, message, rawBody);
			return new ImCallbackResponse(true, "ACCEPTED", null, null, null,
					Map.of("success", true, "accepted", true));
		}
		return processInbound(connector, adapter, config, message, rawBody, true);
	}

	/**
	 * 处理ImCallback。
	 */
	public ImCallbackResponse handleStreamMessage(AgentImConnector connector, ImCallbackMessage message) {
		if (connector == null) {
			throw CheckedException.notFound(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(), ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		if (message == null || !connector.getProvider().equalsIgnoreCase(message.provider())
				|| !connector.getConnectorCode().equals(message.connectorCode())) {
			throw CheckedException.badRequest(ImErrorDict.PROVIDER_MISMATCH.getValue(),
					ImErrorDict.PROVIDER_MISMATCH.getLabel());
		}
		ImAdapter adapter = adapterRegistry.get(connector.getProvider());
		Map<String, Object> config = jsonSupport.readEncryptedMap(connector.getEncryptedConfig());
		runtimeConfigService.providerRuntimeConfig(connector.getProvider());
		return processInbound(connector, adapter, config, message, jsonSupport.writeJson(message.rawPayload()), false);
	}

	/**
	 * IM 入站消息统一处理入口。
	 *
	 * <p><b>权限守卫（不可放宽）：</b>IM 入站内容是不可信的外部输入，本方法内：
	 * <ul>
	 * <li>只能通过 {@code DelegatedAuthContextService.issue} 换取与绑定用户等权的受限委托上下文，
	 * 禁止提升权限、切换成其他用户/系统凭据、或使用创建者长期 Token；</li>
	 * <li>委托上下文的租户必须与验签通过的连接器租户一致（见 requireTenantConsistent），防止跨租户提权；</li>
	 * <li>不得绕过运行时的审批/澄清流程——审批、澄清、人工接管经 Runtime Event 回传 IM
	 * （见 {@link ImRuntimeEventNotifier}）；入站「同意/拒绝 &lt;审批ID&gt;」指令仅在绑定身份
	 * 校验通过后以绑定用户本人身份执行（见 {@link ImApprovalCommandService}），同样不得提权。</li>
	 * </ul>
	 */
	private ImCallbackResponse processInbound(AgentImConnector connector, ImAdapter adapter, Map<String, Object> config,
			ImCallbackMessage message, String rawBody, boolean replyOnDuplicate) {
		if (!StringUtils.hasText(connector.getTenantId())) {
			// 租户缺失说明连接器数据不完整，直接失败关闭，禁止落到"全局"语义继续处理。
			log.error("IM 连接器缺少租户信息, 拒绝处理回调。provider={}, connectorCode={}", connector.getProvider(),
					connector.getConnectorCode());
			throw CheckedException.badRequest(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(),
					ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig = runtimeConfigService
			.providerRuntimeConfig(connector.getProvider());
		if (!ImConstants.STATUS_ENABLED.equalsIgnoreCase(connector.getStatus())) {
			return reply(adapter, config, message, runtimeConfig.disabledConnectorMessage(), null, null, false);
		}
		String idempotencyKey = inboundIdempotencyKey(connector, message, rawBody);
		AgentImMessage existing = messageMapper.findByIdempotencyKey(idempotencyKey);
		if (existing != null) {
			return replyOnDuplicate ? duplicateReply(adapter, message, existing) : duplicateResponse(existing);
		}
		InboundClaim claim = claimInbound(connector, message, idempotencyKey);
		if (!claim.claimed()) {
			return replyOnDuplicate ? duplicateReply(adapter, message, claim.message())
					: duplicateResponse(claim.message());
		}
		AgentImMessage inbound = claim.message();
		if (!"TEXT".equalsIgnoreCase(message.messageType())) {
			return markAndReply(adapter, config, message, inbound, ImConstants.MESSAGE_STATUS_SKIPPED,
					runtimeConfig.unsupportedMessageTypeMessage(), ImErrorDict.MESSAGE_NOT_SUPPORTED, false);
		}
		ImCallbackResponse bindResponse = tryHandleBindCommand(connector, adapter, config, message, inbound);
		if (bindResponse != null) {
			return bindResponse;
		}
		ConversationTarget target = resolveTarget(connector, message, runtimeConfig);
		if (target.agentId() == null) {
			return markAndReply(adapter, config, message, inbound, ImConstants.MESSAGE_STATUS_AUTH_FAILED,
					"当前 IM 会话未绑定 Agent。", ImErrorDict.AGENT_NOT_BOUND, true);
		}
		if (!shouldTrigger(target, message, runtimeConfig)) {
			inbound.setAgentId(target.agentId());
			inbound.setStatus(ImConstants.MESSAGE_STATUS_SKIPPED);
			messageMapper.updateById(inbound);
			return new ImCallbackResponse(true, "消息未命中触发策略", null, null, null, Map.of("success", true));
		}
		String runtimeRequestId = "im-" + UUID.randomUUID();
		AuthResolveResult resolved;
		try {
			resolved = resolveDelegatedAuthContext(connector, message, config, runtimeRequestId, runtimeConfig);
		}
		catch (Exception ex) {
			log.warn("IM delegated auth resolve failed. provider={}, connectorCode={}, externalUserId={}",
					message.provider(), message.connectorCode(), message.externalUserId(), ex);
			resolved = AuthResolveResult.snapshotFailed();
		}
		if (!resolved.succeeded()) {
			boolean unbound = resolved.failure() == AuthFailureKind.UNBOUND;
			return markAndReply(adapter, config, message, inbound, ImConstants.MESSAGE_STATUS_AUTH_FAILED,
					unbound ? runtimeConfig.unboundUserMessage() : runtimeConfig.authSnapshotFailedMessage(),
					unbound ? ImErrorDict.USER_NOT_BOUND : ImErrorDict.AUTH_SNAPSHOT_EMPTY, true);
		}
		DelegatedAuthContext authContext = resolved.auth();
		ImCallbackResponse approvalResponse = tryHandleApprovalCommand(connector, adapter, config, message, inbound,
				target.agentId(), authContext, runtimeRequestId);
		if (approvalResponse != null) {
			return approvalResponse;
		}
		AgentChannelSessionMapping mapping;
		String answer;
		try {
			inbound.setStatus(ImConstants.MESSAGE_STATUS_PROCESSING);
			inbound.setAgentId(target.agentId());
			inbound.setUserId(authContext.context().getUserId());
			messageMapper.updateById(inbound);
			mapping = getOrCreateSessionMapping(connector, message, target,
					authContext.context());
			sendThinkingMessageIfNeeded(adapter, config, message, runtimeConfig);
			AgentInvocationResult invocation = delegatedAuthContextService.executeWith(authContext.context(),
				() -> invokeAgent(message, target.agentId(), mapping.getSessionId(), runtimeRequestId,
						authContext.context(), adapter.interactionCapabilities()));
			answer = flowTextRenderer.render(invocation);
			chatSessionService.updateSessionTime(mapping.getSessionId());
		}
		catch (Exception ex) {
			return handleInvokeFailure(ex, adapter, config, message, inbound, runtimeConfig, runtimeRequestId);
		}

		updateSuccess(inbound, answer, runtimeRequestId, mapping.getSessionId(), authContext.context().getUserId());
		return deliverReply(connector, adapter, config, message, target.agentId(), mapping.getSessionId(),
				runtimeRequestId, authContext.context().getUserId(), answer);
	}

	/**
	 * 扫码绑定短码拦截：必须在会话 Agent / 身份解析之前处理，未绑定用户才能完成自助绑定。
	 * 返回 null 表示不是绑定指令。
	 */
	private ImCallbackResponse tryHandleBindCommand(AgentImConnector connector, ImAdapter adapter,
			Map<String, Object> config, ImCallbackMessage message, AgentImMessage inbound) {
		if (bindSessionService == null) {
			return null;
		}
		ImUserBindSessionService.ConsumeResult result = bindSessionService.consume(connector, message);
		if (result == null || !result.bindCommand()) {
			return null;
		}
		if (result.success()) {
			inbound.setStatus(ImConstants.MESSAGE_STATUS_SUCCESS);
			inbound.setUserId(result.userId());
			inbound.setResponseContent(result.reply());
			inbound.setErrorCode(null);
			inbound.setErrorMessage(null);
			messageMapper.updateById(inbound);
			return reply(adapter, config, message, result.reply(), null, inbound.getSessionId(), true);
		}
		ImErrorDict error = result.error() == null ? ImErrorDict.BIND_CODE_INVALID : result.error();
		return markAndReply(adapter, config, message, inbound, ImConstants.MESSAGE_STATUS_FAILED, result.reply(), error,
				true);
	}

	/**
	 * IM 审批指令拦截：在绑定身份校验通过后、Agent 调用前识别「同意/拒绝 &lt;审批ID&gt;」
	 * 指令（一期简易协议，见 {@link ImApprovalCommandService}）。返回 null 表示非审批指令，
	 * 继续走 Agent 对话链路。业务失败（权限不足/审批不存在/已过期等）回复失败原因，不再触发 Agent。
	 */
	private ImCallbackResponse tryHandleApprovalCommand(AgentImConnector connector, ImAdapter adapter,
			Map<String, Object> config, ImCallbackMessage message, AgentImMessage inbound, Long agentId,
			DelegatedAuthContext authContext, String runtimeRequestId) {
		if (approvalCommandService == null) {
			return null;
		}
		ImApprovalCommandService.ApprovalCommand command = approvalCommandService.parse(message.text());
		if (command == null) {
			return null;
		}
		inbound.setStatus(ImConstants.MESSAGE_STATUS_PROCESSING);
		inbound.setAgentId(agentId);
		inbound.setUserId(authContext.context().getUserId());
		messageMapper.updateById(inbound);
		String replyText;
		try {
			replyText = delegatedAuthContextService.executeWith(authContext.context(),
					() -> approvalCommandService.execute(connector, authContext.context(), command));
			updateSuccess(inbound, replyText, runtimeRequestId, inbound.getSessionId(),
					authContext.context().getUserId());
		}
		catch (Exception ex) {
			CheckedException checked = findCheckedException(ex);
			String reason = firstText(checked == null ? null : checked.getMessage(),
					ImErrorDict.APPROVAL_COMMAND_FAILED.getLabel());
			log.warn("IM 审批指令执行失败。provider={}, connectorCode={}, approvalId={}", message.provider(),
					message.connectorCode(), command.approvalId(), ex);
			inbound.setStatus(ImConstants.MESSAGE_STATUS_FAILED);
			inbound.setErrorCode(String.valueOf(ImErrorDict.APPROVAL_COMMAND_FAILED.getValue()));
			inbound.setErrorMessage(reason);
			inbound.setUserId(authContext.context().getUserId());
			messageMapper.updateById(inbound);
			replyText = "审批指令处理失败：" + reason;
		}
		return reply(adapter, config, message, replyText, runtimeRequestId, inbound.getSessionId(), true);
	}

	/**
	 * 幂等命中时的非回声应答：直接确认成功并标记 duplicate，不再触发 Agent。
	 */
	private ImCallbackResponse duplicateResponse(AgentImMessage duplicated) {
		return new ImCallbackResponse(true, "OK", null, duplicated.getRuntimeRequestId(), duplicated.getSessionId(),
				Map.of("success", true, "duplicate", true));
	}

	/**
	 * Agent 调用失败的收口处理：区分工具拒绝/超时/一般失败，落库失败状态并回复用户话术。
	 */
	private ImCallbackResponse handleInvokeFailure(Exception ex, ImAdapter adapter, Map<String, Object> config,
			ImCallbackMessage message, AgentImMessage inbound,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig, String runtimeRequestId) {
		ToolInvocationException toolFailure = findToolInvocationFailure(ex);
		if (toolFailure == null) {
			log.warn("IM callback invoke failed. provider={}, connectorCode={}, externalMessageId={}",
					message.provider(), message.connectorCode(), message.externalMessageId(), ex);
		}
		else {
			log.warn("IM tool invocation rejected. provider={}, connectorCode={}, runtimeRequestId={}, errorCode={}",
					message.provider(), message.connectorCode(), runtimeRequestId, toolFailure.getCode());
		}
		boolean timeout = AgentRuntimeErrorClassifier.classify(ex).code() == AgentRuntimeErrorCode.TIMEOUT;
		ImErrorDict error = timeout ? ImErrorDict.AGENT_INVOKE_TIMEOUT : ImErrorDict.AGENT_INVOKE_FAILED;
		String errorReply = toolFailure != null ? toolFailure.getMessage()
				: timeout ? runtimeConfig.agentInvokeTimeoutMessage() : runtimeConfig.agentInvokeFailedMessage();
		inbound.setStatus(ImConstants.MESSAGE_STATUS_FAILED);
		inbound.setErrorCode(toolFailure == null ? String.valueOf(error.getValue()) : toolFailure.getCode().name());
		inbound.setErrorMessage(firstText(toolFailure == null ? ex.getMessage() : toolFailure.getMessage(),
				error.getLabel()));
		messageMapper.updateById(inbound);
		return reply(adapter, config, message, errorReply, null, inbound.getSessionId(), true);
	}

	/**
	 * Agent 成功后的出站投递：记录出站消息并回复；回复失败时仅标记出站失败并返回 REPLY_FAILED，不回滚入站成功状态。
	 */
	private ImCallbackResponse deliverReply(AgentImConnector connector, ImAdapter adapter, Map<String, Object> config,
			ImCallbackMessage message, Long agentId, Long sessionId, String runtimeRequestId, String userId,
			String answer) {
		AgentImMessage outbound = null;
		try {
			outbound = createOutbound(connector, message, agentId, sessionId, runtimeRequestId, userId, answer);
			ImCallbackResponse response = reply(adapter, config, message, answer, runtimeRequestId, sessionId, true);
			updateOutbound(outbound, ImConstants.MESSAGE_STATUS_SUCCESS, null, null);
			return response;
		}
		catch (Exception ex) {
			String errorCode = replyErrorCode(ex);
			updateOutbound(outbound, ImConstants.MESSAGE_STATUS_FAILED, errorCode, replyErrorMessage(ex));
			log.warn("IM reply delivery failed after Agent success. provider={}, connectorCode={}, "
					+ "externalMessageId={}, errorCode={}", message.provider(), message.connectorCode(),
					message.externalMessageId(), errorCode);
			return new ImCallbackResponse(false, "REPLY_FAILED", answer, runtimeRequestId, sessionId,
					Map.of("success", false, "errorCode", errorCode));
		}
	}

	private ImCallbackResponse duplicateReply(ImAdapter adapter, ImCallbackMessage message, AgentImMessage existing) {
		Map<String, Object> payload = StringUtils.hasText(existing.getResponseContent())
				? adapter.responsePayload(existing.getResponseContent()) : Map.of("success", true, "duplicate", true);
		return new ImCallbackResponse(true, "OK", existing.getResponseContent(), existing.getRuntimeRequestId(),
				existing.getSessionId(), payload);
	}

	private void dispatchCallback(AgentImConnector connector, ImAdapter adapter, Map<String, Object> config,
			ImCallbackMessage message, String rawBody) {
		try {
			imCallbackExecutor.execute(() -> {
				try {
					processInbound(connector, adapter, config, message, rawBody, false);
				}
				catch (Exception ex) {
					log.warn("IM 异步回调处理失败。provider={}, connectorCode={}, externalMessageId={}",
							message.provider(), message.connectorCode(), message.externalMessageId(), ex);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			throw CheckedException.badRequest(ImErrorDict.STREAM_RUNTIME_UNAVAILABLE.getValue(),
					ImErrorDict.STREAM_RUNTIME_UNAVAILABLE.getLabel());
		}
	}

	private InboundClaim claimInbound(AgentImConnector connector, ImCallbackMessage message, String idempotencyKey) {
		AgentImMessage inbound = AgentImMessage.builder()
			.idempotencyKey(idempotencyKey)
			.tenantId(connector.getTenantId())
			.externalMessageId(message.externalMessageId())
			.provider(message.provider())
			.connectorCode(message.connectorCode())
			.conversationType(message.conversationType())
			.externalConversationId(message.externalConversationId())
			.externalUserId(message.externalUserId())
			.direction(ImConstants.DIRECTION_INBOUND)
			.messageType(message.messageType())
			.content(message.text())
			.status(ImConstants.MESSAGE_STATUS_RECEIVED)
			.rawPayload(jsonSupport.writeJson(message.rawPayload()))
			.build();
		try {
			messageMapper.insert(inbound);
			return new InboundClaim(inbound, true);
		}
		catch (DuplicateKeyException ex) {
			AgentImMessage duplicated = messageMapper.findByIdempotencyKey(idempotencyKey);
			if (duplicated != null) {
				return new InboundClaim(duplicated, false);
			}
			throw ex;
		}
	}

	private ConversationTarget resolveTarget(AgentImConnector connector, ImCallbackMessage message,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		AgentImConversationBinding binding = bindingMapper.findEnabled(connector.getTenantId(), message.provider(),
				message.connectorCode(), message.conversationType(), message.externalConversationId());
		Long agentId = binding == null ? connector.getDefaultAgentId() : binding.getAgentId();
		String triggerPolicy = binding == null ? defaultTriggerPolicy(message.conversationType(), runtimeConfig)
				: binding.getTriggerPolicy();
		List<String> wakeWords = binding == null ? List.of() : readWakeWords(binding.getWakeWords());
		if (ImConstants.CONVERSATION_SINGLE.equals(message.conversationType())
				&& !Boolean.TRUE.equals(connector.getDirectEnabled())) {
			agentId = null;
		}
		if (ImConstants.CONVERSATION_GROUP.equals(message.conversationType())
				&& !Boolean.TRUE.equals(connector.getGroupEnabled()) && binding == null) {
			agentId = null;
		}
		return new ConversationTarget(agentId, firstText(triggerPolicy, defaultTriggerPolicy(message.conversationType(),
				runtimeConfig)),
				wakeWords, binding == null ? ImConstants.SESSION_SCOPE_PER_USER : binding.getSessionScope());
	}

	private boolean shouldTrigger(ConversationTarget target, ImCallbackMessage message,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		if (ImConstants.CONVERSATION_SINGLE.equals(message.conversationType())) {
			return true;
		}
		String policy = firstText(target.triggerPolicy(), runtimeConfig.defaultGroupTriggerPolicy(),
				ImConstants.TRIGGER_MENTION);
		boolean mentioned = message.mentionedBot();
		boolean wakeWordMatched = target.wakeWords().stream()
			.filter(StringUtils::hasText)
			.anyMatch(word -> StringUtils.hasText(message.text()) && message.text().contains(word));
		if (ImConstants.TRIGGER_ALWAYS.equalsIgnoreCase(policy)) {
			return true;
		}
		if (ImConstants.TRIGGER_WAKE_WORD.equalsIgnoreCase(policy)) {
			return wakeWordMatched;
		}
		if (ImConstants.TRIGGER_MENTION_OR_WAKE_WORD.equalsIgnoreCase(policy)) {
			return mentioned || wakeWordMatched;
		}
		return mentioned;
	}

	private AuthResolveResult resolveDelegatedAuthContext(AgentImConnector connector, ImCallbackMessage message,
			Map<String, Object> config, String runtimeRequestId,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		if (!StringUtils.hasText(message.externalUserId())) {
			log.warn("IM inbound missing external user id. tenantId={}, provider={}, connectorCode={}",
					connector.getTenantId(), message.provider(), message.connectorCode());
			return AuthResolveResult.unbound();
		}
		AgentImUserIdentity identity = identityMapper.findEnabledByExternalUser(connector.getTenantId(),
				message.provider(), message.connectorCode(), message.externalUserId());
		if (identity != null && StringUtils.hasText(identity.getUserId())) {
			DelegatedAuthContextReq req = delegatedReq(connector, message, runtimeRequestId, runtimeConfig);
			req.setUserId(identity.getUserId());
			return issuedContext(connector, message, delegatedAuthContextService.issue(req), identity);
		}
		String contact = firstText(message.contact(), resolveDingTalkContact(connector, message, config));
		if (!runtimeConfig.autoBindByContact() || !StringUtils.hasText(contact)) {
			log.warn("IM user identity not bound. tenantId={}, provider={}, connectorCode={}, externalUserId={}",
					connector.getTenantId(), message.provider(), message.connectorCode(), message.externalUserId());
			return AuthResolveResult.unbound();
		}
		DelegatedAuthContextReq req = delegatedReq(connector, message, runtimeRequestId, runtimeConfig);
		req.setContact(contact);
		DelegatedAuthContextService.IssueResult issued = delegatedAuthContextService.issue(req);
		AuthResolveResult resolved = issuedContext(connector, message, issued, null);
		if (!resolved.succeeded()) {
			return resolved;
		}
		AgentImUserIdentity autoIdentity = autoBind(connector, message, resolved.auth().context(), contact);
		return AuthResolveResult.ok(new DelegatedAuthContext(resolved.auth().context(), autoIdentity));
	}

	private AuthResolveResult issuedContext(AgentImConnector connector, ImCallbackMessage message,
			DelegatedAuthContextService.IssueResult issued, AgentImUserIdentity identity) {
		if (issued == null || !issued.succeeded()) {
			log.warn(
					"IM delegated auth failed. status={}, reason={}, tenantId={}, provider={}, connectorCode={}, externalUserId={}",
					issued == null ? null : issued.status(), issued == null ? null : issued.reason(),
					connector.getTenantId(), message.provider(), message.connectorCode(), message.externalUserId());
			return AuthResolveResult.snapshotFailed();
		}
		DelegatedAuthContextResp context = requireTenantConsistent(connector, issued.context());
		if (context == null) {
			return AuthResolveResult.snapshotFailed();
		}
		return AuthResolveResult.ok(new DelegatedAuthContext(context, identity));
	}

	/**
	 * 权限守卫断言点：IAM 颁发的委托上下文租户必须与验签通过的连接器租户一致。
	 * 不一致说明外部账号映射到了其他租户的用户（联系方式撞号、脏数据或恶意构造），
	 * 若放行等于让 IM 输入完成跨租户提权，因此一律按鉴权快照失败处理并留告警日志。
	 */
	private DelegatedAuthContextResp requireTenantConsistent(AgentImConnector connector, DelegatedAuthContextResp context) {
		if (context == null) {
			return null;
		}
		if (!connector.getTenantId().equals(context.getTenantId())) {
			log.warn("IM 委托上下文租户与连接器租户不一致, 已拒绝。connectorTenantId={}, contextTenantId={}, "
					+ "provider={}, connectorCode={}", connector.getTenantId(), context.getTenantId(),
					connector.getProvider(), connector.getConnectorCode());
			return null;
		}
		return context;
	}

	private AgentImUserIdentity autoBind(AgentImConnector connector, ImCallbackMessage message,
			DelegatedAuthContextResp context, String contact) {
		AgentImUserIdentity identity = identityMapper.findByExternalUser(connector.getTenantId(), message.provider(),
				message.connectorCode(), message.externalUserId());
		if (identity == null) {
			identity = new AgentImUserIdentity();
			identity.setTenantId(connector.getTenantId());
			identity.setProvider(message.provider());
			identity.setConnectorCode(connector.getConnectorCode());
			identity.setExternalUserId(message.externalUserId());
		}
		identity.setUnionId(firstText(message.unionId(), identity.getUnionId()));
		identity.setContact(firstText(contact, message.contact(), identity.getContact(), context.getMobile(), context.getEmail()));
		identity.setUserId(context.getUserId());
		identity.setUsername(context.getUsername());
		identity.setNickName(context.getNickName());
		identity.setBindStatus(ImConstants.STATUS_ENABLED);
		identity.setBindSource(ImConstants.BIND_SOURCE_CONTACT_AUTO);
		if (identity.getId() == null) {
			identityMapper.insert(identity);
		}
		else {
			identityMapper.updateById(identity);
		}
		return identity;
	}

	private DelegatedAuthContextReq delegatedReq(AgentImConnector connector, ImCallbackMessage message,
			String runtimeRequestId, ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		return DelegatedAuthContextReq.builder()
			.clientId(firstText(runtimeConfig.delegatedClientId(), "pc-web"))
			.device(firstText(runtimeConfig.delegatedDevice(), "delegated-agent"))
			.source(ImConstants.REQUEST_SOURCE_IM)
			.provider(message.provider())
			.connectorCode(connector.getConnectorCode())
			.externalUserId(message.externalUserId())
			.audience("data-agent")
			.runtimeRequestId(runtimeRequestId)
			.timeoutSeconds(Math.max(60L, runtimeConfig.delegatedTokenTtlSeconds()))
			.build();
	}

	private String resolveDingTalkContact(AgentImConnector connector, ImCallbackMessage message, Map<String, Object> config) {
		if (connector == null || !ImConstants.PROVIDER_DINGTALK.equalsIgnoreCase(connector.getProvider())) {
			return null;
		}
		Object enabled = config == null ? null : config.get("userResolveEnabled");
		if (Boolean.FALSE.equals(enabled) || "false".equalsIgnoreCase(String.valueOf(enabled))) {
			return null;
		}
		return dingTalkCredentialService.resolveUserContact(config == null ? Map.of() : config, message.externalUserId());
	}

	private AgentChannelSessionMapping getOrCreateSessionMapping(AgentImConnector connector, ImCallbackMessage message,
			ConversationTarget target, DelegatedAuthContextResp context) {
		Long userId = parseLong(context.getUserId());
		return channelSessionMappingService.getOrCreate(new ChannelSessionMappingService.ResolveRequest(
				firstText(message.provider(), connector.getProvider()), connector.getConnectorCode(),
				message.conversationType(), message.externalConversationId(), message.externalUserId(),
				message.unionId(), target.agentId(), userId, sessionTitle(message), target.sessionScope()));
	}

	private AgentInvocationResult invokeAgent(ImCallbackMessage message, Long agentId, Long sessionId, String runtimeRequestId,
			DelegatedAuthContextResp context, Set<ChannelInteractionCapability> capabilities) {
		AgentRequest request = AgentRequest.builder()
			.agentId(String.valueOf(agentId))
			.threadId(String.valueOf(sessionId))
			.runtimeRequestId(runtimeRequestId)
			.rootRuntimeRequestId(runtimeRequestId)
			.query(message.text())
			.requestSource(ImConstants.REQUEST_SOURCE_IM)
			.provider(message.provider())
			.connectorCode(message.connectorCode())
			.conversationType(message.conversationType())
			.externalUserId(message.externalUserId())
			.externalConversationId(message.externalConversationId())
			.userIdSnapshot(context.getUserId())
			.userNickNameSnapshot(context.getNickName())
			.dataPermissionSnapshot(context.getDataPermission())
			.tenantIdSnapshot(context.getTenantId())
			.tenantCodeSnapshot(context.getTenantCode())
			.clientIdSnapshot(context.getClientId())
			.teamIdsSnapshot(context.getTeamIds())
			.interactionCapabilities(capabilities == null ? Set.of(ChannelInteractionCapability.TEXT_COMMANDS) : capabilities)
			.build();
		AgentInvocationResult result = agentInvocationService.invokeDetailed(request);
		if (result == null) {
			throw new IllegalStateException("Agent 详细调用未返回结果");
		}
		return result;
	}

	private void updateSuccess(AgentImMessage inbound, String answer, String runtimeRequestId, Long sessionId,
			String userId) {
		inbound.setStatus(ImConstants.MESSAGE_STATUS_SUCCESS);
		inbound.setRuntimeRequestId(runtimeRequestId);
		inbound.setSessionId(sessionId);
		inbound.setUserId(userId);
		inbound.setResponseContent(answer);
		inbound.setErrorCode(null);
		inbound.setErrorMessage(null);
		messageMapper.updateById(inbound);
	}

	private AgentImMessage createOutbound(AgentImConnector connector, ImCallbackMessage message, Long agentId,
			Long sessionId, String runtimeRequestId, String userId, String answer) {
		AgentImMessage outbound = AgentImMessage.builder()
			.idempotencyKey(outboundIdempotencyKey(connector, message, runtimeRequestId))
			.tenantId(connector.getTenantId())
			.externalMessageId(message.externalMessageId())
			.provider(message.provider())
			.connectorCode(message.connectorCode())
			.conversationType(message.conversationType())
			.externalConversationId(message.externalConversationId())
			.externalUserId(message.externalUserId())
			.direction(ImConstants.DIRECTION_OUTBOUND)
			.messageType("TEXT")
			.content(answer)
			.responseContent(answer)
			.agentId(agentId)
			.sessionId(sessionId)
			.runtimeRequestId(runtimeRequestId)
			.userId(userId)
			.status(ImConstants.MESSAGE_STATUS_PENDING)
			.build();
		messageMapper.insert(outbound);
		return outbound;
	}

	private void updateOutbound(AgentImMessage outbound, String status, String errorCode, String errorMessage) {
		if (outbound == null) {
			return;
		}
		outbound.setStatus(status);
		outbound.setErrorCode(errorCode);
		outbound.setErrorMessage(errorMessage);
		try {
			messageMapper.updateById(outbound);
		}
		catch (Exception ex) {
			log.error("IM outbound message status update failed. provider={}, connectorCode={}, "
					+ "externalMessageId={}, status={}", outbound.getProvider(), outbound.getConnectorCode(),
					outbound.getExternalMessageId(), status, ex);
		}
	}

	private String replyErrorCode(Throwable error) {
		CheckedException checked = findCheckedException(error);
		return checked == null ? String.valueOf(ImErrorDict.AGENT_INVOKE_FAILED.getValue())
				: String.valueOf(checked.getCode());
	}

	private String replyErrorMessage(Throwable error) {
		CheckedException checked = findCheckedException(error);
		return checked == null ? firstText(error == null ? null : error.getMessage(),
					ImErrorDict.AGENT_INVOKE_FAILED.getLabel()) : checked.getMessage();
	}

	private CheckedException findCheckedException(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof CheckedException checked) {
				return checked;
			}
			current = current.getCause();
		}
		return null;
	}

	private ImCallbackResponse markAndReply(ImAdapter adapter, Map<String, Object> config, ImCallbackMessage message,
			AgentImMessage inbound, String status, String reply, ImErrorDict error, boolean exposeReply) {
		inbound.setStatus(status);
		inbound.setResponseContent(reply);
		inbound.setErrorCode(String.valueOf(error.getValue()));
		inbound.setErrorMessage(error.getLabel());
		messageMapper.updateById(inbound);
		return reply(adapter, config, message, reply, null, inbound.getSessionId(), exposeReply);
	}

	private ImCallbackResponse reply(ImAdapter adapter, Map<String, Object> config, ImCallbackMessage message, String reply,
			String runtimeRequestId, Long sessionId, boolean exposeReply) {
		Map<String, Object> payload = exposeReply && StringUtils.hasText(reply) && !shouldSendAsync(config, message)
				? adapter.responsePayload(reply) : Map.of("success", true);
		if (exposeReply && StringUtils.hasText(reply) && shouldSendAsync(config, message)) {
			adapter.sendReply(config, message, reply);
		}
		return new ImCallbackResponse(true, "OK", exposeReply ? reply : null, runtimeRequestId, sessionId, payload);
	}

	private void sendThinkingMessageIfNeeded(ImAdapter adapter, Map<String, Object> config, ImCallbackMessage message,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		String thinkingMessage = thinkingMessageText(config, runtimeConfig);
		if (!isThinkingMessageEnabled(config, runtimeConfig) || !StringUtils.hasText(thinkingMessage)
				|| !shouldSendAsync(config, message)) {
			return;
		}
		try {
			adapter.sendReply(config, message, thinkingMessage);
		}
		catch (Exception ex) {
			log.warn("Send IM thinking message failed. provider={}, connectorCode={}, externalMessageId={}",
					message == null ? null : message.provider(), message == null ? null : message.connectorCode(),
					message == null ? null : message.externalMessageId(), ex);
		}
	}

	private boolean isThinkingMessageEnabled(Map<String, Object> config,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		Object enabled = config == null ? null : config.get("thinkingMessageEnabled");
		if (enabled != null) {
			return Boolean.TRUE.equals(enabled) || "true".equalsIgnoreCase(String.valueOf(enabled));
		}
		return runtimeConfig.thinkingMessageEnabled();
	}

	private String thinkingMessageText(Map<String, Object> config,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		Object configuredText = config == null ? null : config.get("thinkingMessageText");
		return firstText(configuredText == null ? null : String.valueOf(configuredText),
				runtimeConfig.thinkingMessageText());
	}

	private boolean shouldSendAsync(Map<String, Object> config, ImCallbackMessage message) {
		Object enabled = config == null ? null : config.get("webhookReplyEnabled");
		return Boolean.TRUE.equals(enabled) || "true".equalsIgnoreCase(String.valueOf(enabled))
				|| StringUtils.hasText(message == null ? null : message.replyWebhook());
	}

	private List<String> readWakeWords(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(value, WAKE_WORD_LIST);
		}
		catch (Exception ex) {
			log.warn("Failed to read IM wake words, falling back to no wake word filter", ex);
			return List.of();
		}
	}

	/**
	 * 入站幂等键。以 provider messageId 为核心，前缀加入租户：connectorCode 只在租户内唯一，
	 * 不带租户前缀时不同租户的重码连接器会互相吞掉对方的消息。
	 */
	private String inboundIdempotencyKey(AgentImConnector connector, ImCallbackMessage message, String rawBody) {
		String externalMessageId = firstText(message.externalMessageId(), sha256(rawBody));
		return connector.getTenantId() + ":" + message.provider() + ":" + message.connectorCode() + ":"
				+ externalMessageId;
	}

	private String outboundIdempotencyKey(AgentImConnector connector, ImCallbackMessage message, String runtimeRequestId) {
		return connector.getTenantId() + ":" + message.provider() + ":" + message.connectorCode() + ":OUT:"
				+ runtimeRequestId;
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		}
		catch (NoSuchAlgorithmException ex) {
			// 该哈希是入站幂等键的唯一来源，兜底随机值会让同一条回调每次算出不同的键，
			// 等于彻底关闭去重并重复处理消息。SHA-256 是 Java SE 必备算法，这里只能失败关闭。
			log.error("SHA-256 unavailable, IM inbound idempotency key cannot be computed", ex);
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private String defaultTriggerPolicy(String conversationType, ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		if (ImConstants.CONVERSATION_GROUP.equals(conversationType)) {
			return firstText(runtimeConfig.defaultGroupTriggerPolicy(), ImConstants.TRIGGER_MENTION);
		}
		return firstText(runtimeConfig.defaultSingleTriggerPolicy(), ImConstants.TRIGGER_ALWAYS);
	}

	private String sessionTitle(ImCallbackMessage message) {
		String prefix = ImConstants.CONVERSATION_GROUP.equals(message.conversationType()) ? "IM 群聊" : "IM 单聊";
		return prefix + " - " + firstText(message.externalConversationId(), message.externalUserId(), message.provider());
	}

	private Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private ToolInvocationException findToolInvocationFailure(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 8; depth++) {
			if (current instanceof ToolInvocationException toolFailure) {
				return toolFailure;
			}
			current = current.getCause();
		}
		return null;
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

	private record ConversationTarget(Long agentId, String triggerPolicy, List<String> wakeWords, String sessionScope) {
	}

	/** 验签通过的候选连接器及其解密配置与适配器，用于多候选消歧。 */
	private record VerifiedCandidate(AgentImConnector connector, Map<String, Object> config, ImAdapter adapter) {
	}

	private record DelegatedAuthContext(DelegatedAuthContextResp context, AgentImUserIdentity identity) {
	}

	private enum AuthFailureKind {
		UNBOUND, AUTH_SNAPSHOT
	}

	private record AuthResolveResult(DelegatedAuthContext auth, AuthFailureKind failure) {

		static AuthResolveResult ok(DelegatedAuthContext auth) {
			return new AuthResolveResult(auth, null);
		}

		static AuthResolveResult unbound() {
			return new AuthResolveResult(null, AuthFailureKind.UNBOUND);
		}

		static AuthResolveResult snapshotFailed() {
			return new AuthResolveResult(null, AuthFailureKind.AUTH_SNAPSHOT);
		}

		boolean succeeded() {
			return auth != null && auth.context() != null && failure == null;
		}
	}

	private record InboundClaim(AgentImMessage message, boolean claimed) {
	}

}
