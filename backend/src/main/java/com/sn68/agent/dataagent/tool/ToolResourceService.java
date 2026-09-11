/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.tool.ToolPageQueryReq;
import com.sn68.agent.dataagent.dto.tool.ToolResourceDTO;
import com.sn68.agent.dataagent.dto.tool.ToolDetailResp;
import com.sn68.agent.dataagent.dto.tool.ToolReferenceResp;
import com.sn68.agent.dataagent.dto.tool.ToolTestReq;
import com.sn68.agent.dataagent.dto.tool.ToolVersionPublishReq;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.entity.AgentMcpTool;
import java.util.List;
import java.util.Map;

/**
 * Tool center service over the existing execution-resource directory.
 */
public interface ToolResourceService {

	IPage<ToolResourceDTO> page(ToolPageQueryReq request);

	ToolDetailResp detail(String resourceKey);

	ToolResourceDTO save(ToolResourceDTO request);

	AgentExecutionResourceVersion publish(String resourceKey, ToolVersionPublishReq request);

	Map<String, Object> test(String resourceKey, ToolTestReq request);

	void delete(String resourceKey);

	List<ToolReferenceResp> listReferences(String resourceKey);

	List<AgentMcpServer> listMcpServers();

	List<AgentMcpTool> listMcpTools(String serverCode);

	ToolResourceDTO addMcpTool(String serverCode, String toolName);

	void syncMcpTools();

}
