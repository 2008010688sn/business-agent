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

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveConfigCryptoServiceTest {

	@Test
	void crypto_isEnabledByDefault() {
		assertTrue(new DataAgentProperties().getCrypto().isEnabled());
	}

	@Test
	void validate_failsStartupWhenCryptoEnabledButKeyMissing() {
		DataAgentProperties properties = new DataAgentProperties();
		SensitiveConfigCryptoService service = new SensitiveConfigCryptoService(properties);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, service::validate);

		// 启动失败信息必须直接给出要设的配置项，否则运维只能靠猜。
		assertTrue(ex.getMessage().contains(SensitiveConfigCryptoService.KEY_PROPERTY));
		assertTrue(ex.getMessage().contains(SensitiveConfigCryptoService.ENABLED_PROPERTY));
	}

	@Test
	void validate_skipsKeyCheckWhenCryptoExplicitlyDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(false);
		SensitiveConfigCryptoService service = new SensitiveConfigCryptoService(properties);

		assertDoesNotThrow(service::validate);
	}

	@Test
	void validate_reportsPropertyNameWhenCryptoKeyInvalid() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(true);
		properties.getCrypto().setKey("short-key");
		SensitiveConfigCryptoService service = new SensitiveConfigCryptoService(properties);

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, service::validate);

		assertTrue(ex.getMessage().contains("DATA_AGENT_CRYPTO_KEY"));
	}

	@Test
	void validate_acceptsValidPlainTextKeyWhenCryptoEnabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(true);
		properties.getCrypto().setKey("12345678901234567890123456789012");
		SensitiveConfigCryptoService service = new SensitiveConfigCryptoService(properties);

		assertDoesNotThrow(service::validate);
	}

}
