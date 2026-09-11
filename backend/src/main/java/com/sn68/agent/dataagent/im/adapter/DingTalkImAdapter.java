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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.service.ImRuntimeConfigService;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 钉钉 IM 对话适配器。
 *
 * <p>钉钉机器人 outgoing 回调的签名算法由平台规定（timestamp 与密钥参与、请求体不参与、无 nonce），
 * 因此验签沿用基类的 PLATFORM 兼容模式与配套补偿控制，见 {@link AbstractJsonImAdapter}。
 */
@Component
public class DingTalkImAdapter extends AbstractJsonImAdapter {

	// 类中另有测试专用构造器，必须显式指定注入构造器，否则 Spring 会回退到不存在的无参构造导致启动失败
	@Autowired
	public DingTalkImAdapter(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
			ImRuntimeConfigService runtimeConfigService, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
		super(objectMapper, webClientBuilder, runtimeConfigService, redisTemplateProvider);
	}

	DingTalkImAdapter(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
			ImRuntimeConfigService runtimeConfigService, ObjectProvider<StringRedisTemplate> redisTemplateProvider,
			Clock clock) {
		super(objectMapper, webClientBuilder, runtimeConfigService, redisTemplateProvider, clock);
	}

	@Override
	public String provider() {
		return ImConstants.PROVIDER_DINGTALK;
	}

	/**
	 * 钉钉身份键只用 {@code senderStaffId}（企业 userid）。{@code senderId} 是
	 * {@code $:LWCP_v1:$…} 密文，不能当绑定表主键，也不剥 {@code $:} 前缀。
	 */
	@Override
	protected String resolveExternalUserId(Map<String, Object> payload) {
		if (payload == null || payload.isEmpty()) {
			return null;
		}
		return firstText(string(payload.get("externalUserId")), string(payload.get("senderStaffId")));
	}

}
