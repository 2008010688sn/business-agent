/*
 * Copyright (c) sn68. All Rights Reserved.
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
package com.sn68.agent.dataagent.service.aimodelconfig;

import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Seeds an OpenAI-compatible CHAT model from env when none is active.
 *
 * @author sn68
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.ai.agent.demo-model", name = "auto-seed", havingValue = "true")
public class DemoModelSeeder implements ApplicationRunner {

	static final String TENANT_ID = "default";

	private final ModelConfigMapper modelConfigMapper;

	private final Environment environment;

	@Override
	public void run(ApplicationArguments args) {
		seedChatIfMissing();
		seedEmbeddingIfRequested();
	}

	private void seedChatIfMissing() {
		ModelConfig active = modelConfigMapper.selectActiveByType(ModelType.CHAT.getCode(), TENANT_ID);
		if (active != null) {
			log.info("Demo model seeder skipped CHAT: an active config already exists. id={}", active.getId());
			return;
		}
		String apiKey = env("AGENT_OPENAI_API_KEY");
		if (!StringUtils.hasText(apiKey)) {
			log.info("Demo model seeder skipped CHAT: AGENT_OPENAI_API_KEY is empty");
			return;
		}
		String baseUrl = firstText(env("AGENT_OPENAI_BASE_URL"), "https://api.openai.com");
		String modelName = firstText(env("AGENT_OPENAI_CHAT_MODEL"), "gpt-4o-mini");
		ModelConfig entity = ModelConfig.builder()
			.tenantId(TENANT_ID)
			.provider("openai")
			.baseUrl(baseUrl)
			.apiKey(apiKey)
			.modelName(modelName)
			.endpointDialect(ModelEndpointDialect.OPENAI_COMPATIBLE.name())
			.isActive(true)
			.modelType(ModelType.CHAT)
			.deleted(false)
			.createTime(Instant.now())
			.build();
		modelConfigMapper.insert(entity);
		log.info("Demo model seeder inserted CHAT model_config. modelName={}", modelName);
	}

	private void seedEmbeddingIfRequested() {
		String embeddingModel = env("AGENT_OPENAI_EMBEDDING_MODEL");
		if (!StringUtils.hasText(embeddingModel)) {
			return;
		}
		ModelConfig active = modelConfigMapper.selectActiveByType(ModelType.EMBEDDING.getCode(), TENANT_ID);
		if (active != null) {
			return;
		}
		String apiKey = env("AGENT_OPENAI_API_KEY");
		if (!StringUtils.hasText(apiKey)) {
			return;
		}
		String baseUrl = firstText(env("AGENT_OPENAI_BASE_URL"), "https://api.openai.com");
		ModelConfig entity = ModelConfig.builder()
			.tenantId(TENANT_ID)
			.provider("openai")
			.baseUrl(baseUrl)
			.apiKey(apiKey)
			.modelName(embeddingModel)
			.endpointDialect(ModelEndpointDialect.OPENAI_COMPATIBLE.name())
			.isActive(true)
			.modelType(ModelType.EMBEDDING)
			.deleted(false)
			.createTime(Instant.now())
			.build();
		modelConfigMapper.insert(entity);
		log.info("Demo model seeder inserted EMBEDDING model_config. modelName={}", embeddingModel);
	}

	private String env(String key) {
		String value = environment.getProperty(key);
		if (StringUtils.hasText(value)) {
			return value.trim();
		}
		String fromSys = System.getenv(key);
		return StringUtils.hasText(fromSys) ? fromSys.trim() : null;
	}

	private static String firstText(String value, String fallback) {
		return StringUtils.hasText(value) ? value : fallback;
	}

}
