/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.dataagent.dto.skill.SkillFlowTestResult;
import com.sn68.agent.dataagent.dto.skill.SkillRouteTestReq;
import com.sn68.agent.dataagent.dto.skill.SkillRouteTestResult;

/**
 * Side-effect-free Skill draft tests.
 */
public interface SkillTestService {

	SkillRouteTestResult testRoute(String skillCode, SkillRouteTestReq request);

	SkillFlowTestResult testFlow(String skillCode);

}
