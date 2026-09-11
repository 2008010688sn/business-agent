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
package com.sn68.agent.dataagent.mcp.exposure.service;

import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureToolBuilder.ExposureTool;
import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureToolBuilder.ExposureToolSnapshot;
import io.modelcontextprotocol.server.McpSyncServer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 只协调 agent_mcp_exposure 管理的运行时 MCP Tool。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpExposureRuntimeRegistry {

	private final McpSyncServer mcpSyncServer;

	private final McpExposureToolBuilder toolBuilder;

	private final Set<String> managedToolNames = new LinkedHashSet<>();

	private final Set<String> staticToolNames = new LinkedHashSet<>();

	private final Map<String, ExposureToolSnapshot> managedSnapshots = new LinkedHashMap<>();

	private boolean staticToolNamesInitialized;

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		reconcileSafely("应用启动");
	}

	/**
	 * 判断名称是否属于启动期静态 MCP Tool。
	 */
	public synchronized boolean isStaticToolName(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		initializeStaticToolNames();
		return staticToolNames.contains(toolName.trim());
	}

	synchronized void reconcile() {
		initializeStaticToolNames();
		Map<String, ExposureTool> desiredTools = toolBuilder.buildEnabledTools();

		for (String managedName : new ArrayList<>(managedToolNames)) {
			if (!desiredTools.containsKey(managedName)) {
				removeManagedTool(managedName);
			}
		}

		for (ExposureTool desired : desiredTools.values()) {
			String toolName = desired.snapshot().toolName();
			if (staticToolNames.contains(toolName)) {
				log.error("拒绝覆盖静态 MCP Tool，exposureCode={}, toolName={}",
						desired.snapshot().exposureCode(), toolName);
				continue;
			}
			ExposureToolSnapshot current = managedSnapshots.get(toolName);
			if (desired.snapshot().equals(current)) {
				continue;
			}
			if (current != null && !removeManagedTool(toolName)) {
				continue;
			}
			addManagedTool(desired);
		}
	}

	private void initializeStaticToolNames() {
		if (staticToolNamesInitialized) {
			return;
		}
		mcpSyncServer.listTools().stream()
			.map(tool -> tool.name())
			.filter(StringUtils::hasText)
			.filter(name -> !managedToolNames.contains(name))
			.forEach(staticToolNames::add);
		staticToolNamesInitialized = true;
	}

	private boolean removeManagedTool(String toolName) {
		try {
			mcpSyncServer.removeTool(toolName);
			managedToolNames.remove(toolName);
			managedSnapshots.remove(toolName);
			log.info("已移除运行时 MCP 暴露 Tool，toolName={}", toolName);
			return true;
		}
		catch (Exception ex) {
			log.error("移除运行时 MCP 暴露 Tool 失败，toolName={}", toolName, ex);
			return false;
		}
	}

	private void addManagedTool(ExposureTool tool) {
		String toolName = tool.snapshot().toolName();
		try {
			mcpSyncServer.addTool(McpToolUtils.toSyncToolSpecification(tool.callback()));
			managedToolNames.add(toolName);
			managedSnapshots.put(toolName, tool.snapshot());
			log.info("已注册运行时 MCP 暴露 Tool，exposureCode={}, toolName={}",
					tool.snapshot().exposureCode(), toolName);
		}
		catch (Exception ex) {
			log.error("注册运行时 MCP 暴露 Tool 失败，exposureCode={}, toolName={}",
					tool.snapshot().exposureCode(), toolName, ex);
		}
	}

	private void reconcileSafely(String reason) {
		try {
			reconcile();
		}
		catch (Exception ex) {
			log.error("MCP 暴露运行时协调失败，reason={}", reason, ex);
		}
	}

}
