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
package com.sn68.agent.dataagent.im.adapter;

import com.sn68.agent.dataagent.im.ChannelInteractionCapability;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import java.util.Map;
import java.util.Set;

/**
 * IM 平台适配器。
 */
public interface ImAdapter {

	/**
	 * 声明该平台支持的交互能力，默认为纯文本指令。
	 */
	default Set<ChannelInteractionCapability> interactionCapabilities() {
		return Set.of(ChannelInteractionCapability.TEXT_COMMANDS);
	}

	/**
	 * 返回平台标识（大写），与连接器 provider 字段对应。
	 */
	String provider();

	/**
	 * 使用连接器密钥对回调请求验签，未通过必须抛异常；验签通过前不得信任报文中的任何业务字段。
	 *
	 * <p><b>必须是纯校验、无副作用。</b>同一请求会拿去逐个试签跨租户的同码候选连接器
	 * （见 {@code ImCallbackService.handleCallback}），带副作用会让第二个候选的校验被第一个污染，
	 * 从而掩盖「同码同密钥」这类配置事故。防重放占用另见 {@link #claimReplayGuard}。
	 */
	void verify(String connectorCode, Map<String, Object> config, Map<String, String> headers,
			Map<String, String> queryParams, String rawBody);

	/**
	 * 占用本次回调的防重放凭据，重复提交必须抛异常。
	 *
	 * <p>与 {@link #verify} 分开，是因为它有副作用（写去重存储），只能在候选连接器唯一确定
	 * （即租户已可信）之后调用一次，键需带租户与连接器编码，避免跨租户互相顶掉。
	 */
	default void claimReplayGuard(String tenantId, String connectorCode, Map<String, String> headers,
			Map<String, String> queryParams, String rawBody) {
	}

	/**
	 * 将平台原始回调报文解析为统一的回调消息结构。
	 */
	ImCallbackMessage parse(String provider, String connectorCode, String rawBody);

	/**
	 * 组装平台要求的同步应答报文（例如钉钉要求的 success 响应体）。
	 */
	Map<String, Object> responsePayload(String text);

	/**
	 * 通过平台服务端接口向会话回复文本消息。
	 */
	void sendReply(Map<String, Object> config, ImCallbackMessage message, String text);

}
