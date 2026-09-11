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
package com.sn68.agent.dataagent.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "spring.ai.agent.tool-center")
public class ToolCenterProperties {

	private boolean enabled = true;

	private McpClient mcpClient = new McpClient();

	private ToolSync toolSync = new ToolSync();

	private Confirm confirm = new Confirm();

	private Permission permission = new Permission();

	private Mcp mcp = new Mcp();

	@Getter
	@Setter
	public static class McpClient {

		private boolean enabled = false;

	}

	@Getter
	@Setter
	public static class ToolSync {

		private boolean enabled = false;

	}

	@Getter
	@Setter
	public static class Confirm {

		private boolean writeRequired = true;

	}

	@Getter
	@Setter
	public static class Permission {

		private String denyMessage = "Current user has no permission to use this tool.";

	}

	@Getter
	@Setter
	public static class Mcp {

		private Map<String, McpServer> servers = new LinkedHashMap<>();

	}

	@Getter
	@Setter
	public static class McpServer {

		private boolean enabled = false;

		private String serviceName;

		private String endpointPath = "/mcp";

		private String transportType = "streamable-http";

		private String baseUrl;

	}

}
