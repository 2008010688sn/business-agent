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
package com.sn68.agent.dataagent.im.enums;

import java.util.List;

/**
 * IM 对话连接器常量。
 */
public final class ImConstants {

	public static final String STATUS_ENABLED = "enabled";

	public static final String STATUS_DISABLED = "disabled";

	public static final String PROVIDER_DINGTALK = "DINGTALK";

	public static final String PROVIDER_WECOM = "WECOM";

	public static final List<String> DINGTALK_REPLY_WEBHOOK_ALLOWED_HOSTS = List.of("oapi.dingtalk.com",
			"api.dingtalk.com");

	public static final List<String> WECOM_REPLY_WEBHOOK_ALLOWED_HOSTS = List.of("qyapi.weixin.qq.com");

	public static final String CONNECT_MODE_STREAM = "STREAM";

	public static final String CONNECT_MODE_HTTP_CALLBACK = "HTTP_CALLBACK";

	public static final String SETUP_STATUS_PENDING = "PENDING";

	public static final String SETUP_STATUS_VALIDATED = "VALIDATED";

	public static final String SETUP_STATUS_COMPLETED = "COMPLETED";

	public static final String SETUP_STATUS_EXPIRED = "EXPIRED";

	public static final String CONVERSATION_SINGLE = "SINGLE";

	public static final String CONVERSATION_GROUP = "GROUP";

	public static final String TRIGGER_ALWAYS = "ALWAYS";

	public static final String TRIGGER_MENTION = "MENTION";

	public static final String TRIGGER_WAKE_WORD = "WAKE_WORD";

	public static final String TRIGGER_MENTION_OR_WAKE_WORD = "MENTION_OR_WAKE_WORD";

	public static final String SESSION_SCOPE_PER_USER = "PER_USER";

	public static final String SESSION_SCOPE_PER_CONVERSATION = "PER_CONVERSATION";

	public static final String SESSION_SCOPE_PER_AGENT = "PER_AGENT";

	public static final String DIRECTION_INBOUND = "INBOUND";

	public static final String DIRECTION_OUTBOUND = "OUTBOUND";

	public static final String MESSAGE_STATUS_RECEIVED = "RECEIVED";

	public static final String MESSAGE_STATUS_SKIPPED = "SKIPPED";

	public static final String MESSAGE_STATUS_AUTH_FAILED = "AUTH_FAILED";

	public static final String MESSAGE_STATUS_PROCESSING = "PROCESSING";

	public static final String MESSAGE_STATUS_PENDING = "PENDING";

	public static final String MESSAGE_STATUS_SUCCESS = "SUCCESS";

	public static final String MESSAGE_STATUS_FAILED = "FAILED";

	public static final String BIND_SOURCE_MANUAL = "MANUAL";

	public static final String BIND_SOURCE_CONTACT_AUTO = "CONTACT_AUTO";

	public static final String BIND_SOURCE_QR_PAIR = "QR_PAIR";

	public static final String BIND_SESSION_PENDING = "PENDING";

	public static final String BIND_SESSION_CONSUMED = "CONSUMED";

	public static final String BIND_SESSION_EXPIRED = "EXPIRED";

	public static final String BIND_CODE_PREFIX = "BIND-";

	public static final int BIND_CODE_LENGTH = 8;

	public static final int BIND_SESSION_TTL_MINUTES = 10;

	public static final int BIND_FAIL_MAX_ATTEMPTS = 8;

	public static final int BIND_FAIL_WINDOW_MINUTES = 10;

	public static final String REQUEST_SOURCE_IM = "IM";

	/**
	 * IM 审批指令动词（一期简易协议）：文本消息去除 @提及后须匹配
	 * 「同意 &lt;审批ID&gt; [备注]」/「拒绝 &lt;审批ID&gt; [备注]」，审批ID 为纯数字。
	 */
	public static final String APPROVAL_COMMAND_APPROVE = "同意";

	public static final String APPROVAL_COMMAND_REJECT = "拒绝";

	/**
	 * 处理运行时审批所需的功能权限码，与 AgentApprovalController 同码。
	 * IM 指令链路不经过 Spring MVC，注解不参与，必须在服务内显式判定同一权限码。
	 */
	public static final String PERMISSION_APPROVAL_REVIEW = "ai-agent:approval:review";

	/** 运行时事件回传出站消息的幂等键段（区别于会话回复的 OUT 段）。 */
	public static final String OUTBOUND_KEY_RUNTIME_EVENT = "RUNTIME";

	/**
	 * 返回平台官方回复域名默认值。未知平台不允许回退到任意域名。
	 */
	public static List<String> defaultReplyWebhookAllowedHosts(String provider) {
		if (PROVIDER_DINGTALK.equalsIgnoreCase(provider)) {
			return DINGTALK_REPLY_WEBHOOK_ALLOWED_HOSTS;
		}
		if (PROVIDER_WECOM.equalsIgnoreCase(provider)) {
			return WECOM_REPLY_WEBHOOK_ALLOWED_HOSTS;
		}
		return List.of();
	}

	private ImConstants() {
	}

}
