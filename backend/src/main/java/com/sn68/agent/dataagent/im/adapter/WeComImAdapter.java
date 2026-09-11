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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 企微 IM 对话适配器。
 *
 * <p>本适配器走的是通用 JSON 回调（HMAC 签名 + 明文 JSON 报文），并未实现企微官方的
 * {@code msg_signature} + AES 密文 XML 协议，因此可以并且应当启用基类的 BODY 模式
 * （连接器配置 {@code callbackSignatureMode=BODY}），让签名覆盖请求体。
 */
@Component
public class WeComImAdapter extends AbstractJsonImAdapter {

	public WeComImAdapter(ObjectMapper objectMapper, WebClient.Builder webClientBuilder,
			ImRuntimeConfigService runtimeConfigService, ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
		super(objectMapper, webClientBuilder, runtimeConfigService, redisTemplateProvider);
	}

	@Override
	public String provider() {
		return ImConstants.PROVIDER_WECOM;
	}

}
