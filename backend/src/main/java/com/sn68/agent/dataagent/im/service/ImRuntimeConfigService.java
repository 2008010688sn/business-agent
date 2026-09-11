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

import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImProviderConfig;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImProviderConfigMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * IM 运行时配置解析服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImRuntimeConfigService {

	private final AgentImProviderConfigMapper providerConfigMapper;

	private final NotificationJsonSupport jsonSupport;

	/**
	 * 处理Im运行时配置。
	 */
	public ProviderRuntimeConfig providerRuntimeConfig(String provider) {
		AgentImProviderConfig config = providerConfigMapper.findEnabledByProvider(provider);
		if (config == null) {
			throw CheckedException.notFound(ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getValue(),
					ImErrorDict.PROVIDER_CONFIG_NOT_INITIALIZED.getLabel());
		}
		return toRuntimeConfig(config.getProvider(), jsonSupport.readEncryptedMap(config.getEncryptedConfig()));
	}

	/**
	 * 处理Im运行时配置。
	 */
	public Map<String, Object> connectorConfig(AgentImConnector connector) {
		return connector == null ? Map.of() : jsonSupport.readEncryptedMap(connector.getEncryptedConfig());
	}

	private ProviderRuntimeConfig toRuntimeConfig(String provider, Map<String, Object> config) {
		Map<String, Object> source = config == null ? Map.of() : config;
		return new ProviderRuntimeConfig(provider,
				textValue(source, "developerConsoleUrl", "https://open-dev.dingtalk.com"),
				intValue(source, "setupSessionTtlMinutes", 30),
				intValue(source, "credentialValidationTimeoutMs", 5000),
				intValue(source, "streamConnectTimeoutMs", 5000),
				intValue(source, "streamReconnectDelayMs", 5000),
				boolValue(source, "streamWorkerEnabled", true),
				intValue(source, "streamLeaseTtlSeconds", 90),
				intValue(source, "streamLeaseRenewSeconds", 30),
				boolValue(source, "userResolveEnabled", true),
				intValue(source, "userResolveTimeoutMs", 5000),
				textValue(source, "tokenUrl", "https://api.dingtalk.com/v1.0/oauth2/accessToken"),
				textValue(source, "userInfoUrlTemplate", "https://api.dingtalk.com/v1.0/contact/users/{userId}"),
				intValue(source, "platformSendTimeoutMs", 5000),
				intValue(source, "iamTimeoutMs", 5000),
				longValue(source, "delegatedTokenTtlSeconds", 900L),
				textValue(source, "delegatedClientId", "pc-web"),
				textValue(source, "delegatedDevice", "delegated-agent"),
				boolValue(source, "autoBindByContact", false),
				textValue(source, "defaultSingleTriggerPolicy", ImConstants.TRIGGER_ALWAYS),
				textValue(source, "defaultGroupTriggerPolicy", ImConstants.TRIGGER_MENTION),
				textValue(source, "unboundUserMessage", "未识别到您的系统账号，请先联系管理员完成 IM 用户绑定。"),
				textValue(source, "authSnapshotFailedMessage", "暂时无法确认您的登录身份，请稍后重试。"),
				textValue(source, "disabledConnectorMessage", "当前 IM 对话连接器未启用。"),
				textValue(source, "unsupportedMessageTypeMessage", "暂时只支持文本消息。"),
				boolValue(source, "thinkingMessageEnabled", true),
				textValue(source, "thinkingMessageText", "正在思考中，请耐心等候..."),
				textValue(source, "agentInvokeFailedMessage", "Agent 执行失败，请稍后再试。"),
				textValue(source, "agentInvokeTimeoutMessage",
						"本次分析超时，请缩小查询范围或补充筛选条件后重试；如果已触发后台任务，请稍后查看结果。"));
	}

	private String textValue(Map<String, Object> config, String key, String defaultValue) {
		Object value = config.get(key);
		if (!StringUtils.hasText(value == null ? null : String.valueOf(value))) {
			return defaultValue;
		}
		return String.valueOf(value).trim();
	}

	private int intValue(Map<String, Object> config, String key, int defaultValue) {
		Object value = config.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		if (value != null && StringUtils.hasText(String.valueOf(value))) {
			try {
				return Integer.parseInt(String.valueOf(value).trim());
			}
			catch (NumberFormatException ex) {
				log.warn("IM runtime config value is not an int, falling back to the default. key={}, value={}, "
						+ "default={}", key, value, defaultValue);
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private long longValue(Map<String, Object> config, String key, long defaultValue) {
		Object value = config.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value != null && StringUtils.hasText(String.valueOf(value))) {
			try {
				return Long.parseLong(String.valueOf(value).trim());
			}
			catch (NumberFormatException ex) {
				log.warn("IM runtime config value is not a long, falling back to the default. key={}, value={}, "
						+ "default={}", key, value, defaultValue);
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private boolean boolValue(Map<String, Object> config, String key, boolean defaultValue) {
		Object value = config.get(key);
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value != null && StringUtils.hasText(String.valueOf(value))) {
			return Boolean.parseBoolean(String.valueOf(value).trim());
		}
		return defaultValue;
	}

	/**
	 * 处理Im运行时配置。
	 */
	public record ProviderRuntimeConfig(String provider, String developerConsoleUrl, int setupSessionTtlMinutes,
			int credentialValidationTimeoutMs, int streamConnectTimeoutMs, int streamReconnectDelayMs,
			boolean streamWorkerEnabled, int streamLeaseTtlSeconds, int streamLeaseRenewSeconds,
			boolean userResolveEnabled, int userResolveTimeoutMs, String tokenUrl, String userInfoUrlTemplate,
			int platformSendTimeoutMs, int iamTimeoutMs, long delegatedTokenTtlSeconds, String delegatedClientId,
			String delegatedDevice, boolean autoBindByContact, String defaultSingleTriggerPolicy,
			String defaultGroupTriggerPolicy, String unboundUserMessage, String authSnapshotFailedMessage,
			String disabledConnectorMessage, String unsupportedMessageTypeMessage, boolean thinkingMessageEnabled,
			String thinkingMessageText, String agentInvokeFailedMessage, String agentInvokeTimeoutMessage) {
	}

}
