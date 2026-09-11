/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;
import com.sn68.agent.dataagent.dto.skill.SkillImportReq;
import java.util.Map;

/**
 * Skill file-bundle boundary. Files are never a runtime source of truth.
 */
public interface SkillImportExportService {

	SkillDetailResp importBundle(SkillImportReq request);

	Map<String, Object> exportBundle(String skillCode);

}
