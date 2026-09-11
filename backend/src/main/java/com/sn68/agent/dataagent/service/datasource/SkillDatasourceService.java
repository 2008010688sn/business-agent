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
package com.sn68.agent.dataagent.service.datasource;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import java.util.List;
import java.util.Map;

/**
 * Skill 数据源服务契约。
 */
public interface SkillDatasourceService {

	Boolean initializeSchemaForSkillWithDatasource(Long skillId, Long datasourceId, List<String> tables);

	/**
	 * 系统路径（启动初始化）按 Skill 行上的 tenant_id 初始化表结构，不依赖登录上下文。
	 */
	Boolean initializeSchemaForPublishedSkill(DataAgentSkill skill, Long datasourceId, List<String> tables);

	void initializeSchemaForCurrentSkillDatasource(Long skillId);

	List<SkillDatasource> listSkillDatasources(Long skillId);

	List<Datasource> listDatasourceCandidates(Long skillId);

	boolean testSkillDatasourceConnection(Long skillId, Long datasourceId);

	List<String> getAvailableSkillDatasourceTables(Long skillId, Long datasourceId) throws Exception;

	List<String> getAvailableSkillDatasourceColumns(Long skillId, Long datasourceId, String tableName) throws Exception;

	List<LogicalRelation> getSkillLogicalRelations(Long skillId, Long datasourceId);

	List<LogicalRelation> saveSkillLogicalRelations(Long skillId, Long datasourceId,
			List<LogicalRelation> logicalRelations);

	default SkillDatasource getCurrentSkillDatasource(Long skillId) {
		return listSkillDatasources(skillId).stream()
			.filter(a -> Boolean.TRUE.equals(a.getIsActive()))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("Skill " + skillId + " has no active datasource"));
	}

	SkillDatasource addDatasourceToSkill(Long skillId, Long datasourceId);

	void removeDatasourceFromSkill(Long skillId, Long datasourceId);

	SkillDatasource toggleDatasourceForSkill(Long skillId, Long datasourceId, Boolean isActive);

	SkillDatasource updateSkillDatasourceTables(Long skillId, Long datasourceId, List<String> tables);

	SkillDatasource updateSkillDatasourceColumns(Long skillId, Long datasourceId,
			Map<String, List<String>> columnsByTable) throws Exception;

	/**
	 * Resolves the fields that a Skill version may expose. An omitted table entry means
	 * all currently available fields for that table.
	 */
	Map<String, List<String>> getEffectiveSkillDatasourceColumns(Long skillId, Long datasourceId) throws Exception;

	List<String> getVisibleSkillTableColumns(Long skillId, Long datasourceId, String tableName) throws Exception;

}
