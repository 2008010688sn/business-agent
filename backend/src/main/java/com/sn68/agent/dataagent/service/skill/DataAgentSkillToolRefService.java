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
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.dataagent.dto.skill.AgentSkillToolRefDTO;
import java.util.List;

/**
 * DataAgent技能工具Ref服务契约。
 */
public interface DataAgentSkillToolRefService {

	/**
	 * 查询DataAgent技能工具Ref。
	 */
	List<AgentSkillToolRefDTO> listRefs(String skillId);

	/**
	 * 保存DataAgent技能工具Ref。
	 */
	List<AgentSkillToolRefDTO> saveRefs(String skillId, List<AgentSkillToolRefDTO> refs);

}
