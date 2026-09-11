/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;

/**
 * Skill version lookup service.
 */
public interface SkillVersionService {

	DataAgentSkillVersion getRequired(Long versionId);

	DataAgentSkillVersion getPublished(DataAgentSkill skill, Long pinnedVersionId);

}
