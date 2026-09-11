/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool;

import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;

/**
 * Resource tools that are available only to a routed, published Skill version.
 */
public interface SkillResourceToolProvider {

	Map<String, ToolCallback> getSkillToolCallbacks(SkillVersionResources resources);

}
