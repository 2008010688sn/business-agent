/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;

/**
 * Publish a validated immutable Skill version.
 */
public interface SkillPublishService {

	SkillDetailResp publish(String skillCode);

}
