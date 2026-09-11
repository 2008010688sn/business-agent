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
package com.sn68.agent.dataagent.service.security;

import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DatasourceMapper;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 敏感配置配置Migration组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveConfigMigrationService implements ApplicationRunner {

	private final DataAgentProperties properties;

	private final SensitiveConfigCryptoService cryptoService;

	private final DatasourceMapper datasourceMapper;

	private final ModelConfigMapper modelConfigMapper;

	/**
	 * 执行敏感配置配置Migration。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void run(ApplicationArguments args) {
		if (!properties.getCrypto().getMigration().isEnabled()) {
			return;
		}
		migrateExistingSecrets();
	}

	/**
	 * 处理敏感配置配置Migration。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void migrateExistingSecrets() {
		if (!cryptoService.isEnabled()) {
			throw new IllegalStateException("Sensitive config migration requires crypto.enabled=true");
		}
		int datasourceCount = migrateDatasourcePasswords();
		int modelConfigCount = migrateModelConfigSecrets();
		log.info("Sensitive config migration completed. datasourceUpdated={}, modelConfigUpdated={}", datasourceCount,
				modelConfigCount);
	}

	private int migrateDatasourcePasswords() {
		List<Datasource> changed = new ArrayList<>();
		for (Datasource datasource : datasourceMapper.selectAll()) {
			if (datasource == null || !shouldEncrypt(datasource.getPassword())) {
				continue;
			}
			datasource.setPassword(cryptoService.encryptIfNecessary(datasource.getPassword()));
			changed.add(datasource);
		}
		if (!changed.isEmpty()) {
			datasourceMapper.updateBatch(changed);
		}
		return changed.size();
	}

	private int migrateModelConfigSecrets() {
		List<ModelConfig> changed = new ArrayList<>();
		for (ModelConfig modelConfig : modelConfigMapper.findAll()) {
			if (modelConfig == null) {
				continue;
			}
			boolean dirty = false;
			if (shouldEncrypt(modelConfig.getApiKey())) {
				modelConfig.setApiKey(cryptoService.encryptIfNecessary(modelConfig.getApiKey()));
				dirty = true;
			}
			if (shouldEncrypt(modelConfig.getProxyPassword())) {
				modelConfig.setProxyPassword(cryptoService.encryptIfNecessary(modelConfig.getProxyPassword()));
				dirty = true;
			}
			if (dirty) {
				changed.add(modelConfig);
			}
		}
		if (!changed.isEmpty()) {
			modelConfigMapper.updateBatch(changed);
		}
		return changed.size();
	}

	private boolean shouldEncrypt(String value) {
		return StringUtils.hasText(value) && !cryptoService.isCipherText(value) && !cryptoService.isPlaceholder(value);
	}

}
