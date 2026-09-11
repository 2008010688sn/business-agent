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
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

/**
 * web_fetch 固定限额。超时/体积/QPS 不会按环境改，不进配置。
 */
final class WebFetchLimits {

	static final int TIMEOUT_MS = 5000;

	static final long MAX_BYTES = 1048576L;

	static final int QPS = 2;

	private WebFetchLimits() {
	}

}
