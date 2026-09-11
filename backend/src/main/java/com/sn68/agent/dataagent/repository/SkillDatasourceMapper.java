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
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Skill数据源Mapper服务契约。
 */
@Repository
public interface SkillDatasourceMapper extends SuperMapper<SkillDatasource> {

	/**
	 * 查询 Skill 关联的数据源并携带数据源明细（LEFT JOIN datasource）。
	 *
	 * <p>复杂 SQL（关联查询 + 嵌套 resultMap），XML 维护：{@code mapper/dataagent/SkillDatasourceMapper.xml}。
	 */
	List<SkillDatasource> selectBySkillIdWithDatasource(@Param("skillId") Long skillId,
			@Param("tenantId") String tenantId);

	/** Query associated data sources by Skill ID. */
	default List<SkillDatasource> selectBySkillId(Long skillId) {
		return selectList(new LambdaQueryWrapper<SkillDatasource>().eq(SkillDatasource::getSkillId, skillId)
			.orderByDesc(SkillDatasource::getCreateTime));
	}

	/** Query active datasource ID by skill ID */
	default Long selectActiveDatasourceIdBySkillId(Long skillId) {
		SkillDatasource relation = selectOne(new LambdaQueryWrapper<SkillDatasource>()
			.select(SkillDatasource::getDatasourceId)
			.eq(SkillDatasource::getSkillId, skillId)
			.eq(SkillDatasource::getIsActive, true)
			.last(" limit 1"));
		return relation == null ? null : relation.getDatasourceId();
	}

	/** Query association by skill ID and data source ID */
	default SkillDatasource selectBySkillIdAndDatasourceId(Long skillId, Long datasourceId) {
		return selectOne(new LambdaQueryWrapper<SkillDatasource>().eq(SkillDatasource::getSkillId, skillId)
			.eq(SkillDatasource::getDatasourceId, datasourceId)
			.last(" limit 1"));
	}

	/** Query associations by datasource ID */
	default List<SkillDatasource> selectByDatasourceId(Long datasourceId) {
		return selectList(new LambdaQueryWrapper<SkillDatasource>().eq(SkillDatasource::getDatasourceId, datasourceId)
			.orderByDesc(SkillDatasource::getCreateTime));
	}

	/** Disable all data sources for a skill */
	default int disableAllBySkillId(Long skillId) {
		return update(null, Wraps.<SkillDatasource>lbU().eq(SkillDatasource::getSkillId, skillId)
			.set(SkillDatasource::getIsActive, false)
			.set(SkillDatasource::getLastModifyTime, Instant.now()));
	}

	/**
	 * 按主键更新启用状态并补写租户（用于空 tenant_id 的历史绑定行）。
	 */
	default int patchActiveAndTenant(Long id, Boolean isActive, String tenantId) {
		return update(null, Wraps.<SkillDatasource>lbU().eq(SkillDatasource::getId, id)
			.set(SkillDatasource::getIsActive, isActive)
			.set(tenantId != null && !tenantId.isBlank(), SkillDatasource::getTenantId, tenantId)
			.set(SkillDatasource::getLastModifyTime, Instant.now()));
	}

	/**
	 * Count the number of enabled data sources for a skill (excluding the specified data
	 * source)
	 */
	default int countActiveBySkillIdExcluding(Long skillId, Long excludeDatasourceId) {
		return Math.toIntExact(selectCount(new LambdaQueryWrapper<SkillDatasource>()
			.eq(SkillDatasource::getSkillId, skillId)
			.eq(SkillDatasource::getIsActive, true)
			.ne(SkillDatasource::getDatasourceId, excludeDatasourceId)));
	}

	/**
	 * 统计 Skill 当前启用（isActive=true）的数据源关联条数。
	 */
	default int countActiveBySkillId(Long skillId) {
		return Math.toIntExact(selectCount(new LambdaQueryWrapper<SkillDatasource>()
			.eq(SkillDatasource::getSkillId, skillId)
			.eq(SkillDatasource::getIsActive, true)));
	}

	/**
	 * 按数据源 ID 逻辑删除全部 Skill 关联（数据源删除时的级联清理）。
	 */
	default int deleteAllByDatasourceId(Long datasourceId) {
		return delete(new LambdaQueryWrapper<SkillDatasource>().eq(SkillDatasource::getDatasourceId, datasourceId));
	}

	/**
	 * 新建 Skill 与数据源的关联（默认启用）。
	 */
	default int createNewRelationEnabled(Long skillId, Long datasourceId) {
		return createNewRelationEnabled(skillId, datasourceId, null);
	}

	/**
	 * 新建 Skill 与数据源的关联（默认启用），可写入租户以免拦截器被忽略时漏租户。
	 */
	default int createNewRelationEnabled(Long skillId, Long datasourceId, String tenantId) {
		SkillDatasource relation = new SkillDatasource(skillId, datasourceId);
		relation.setTenantId(tenantId);
		return insert(relation);
	}

	/**
	 * 更新 Skill 与数据源关联的启用状态。
	 */
	default int updateRelation(Long skillId, Long datasourceId, Boolean isActive) {
		return update(null, Wraps.<SkillDatasource>lbU().eq(SkillDatasource::getSkillId, skillId)
			.eq(SkillDatasource::getDatasourceId, datasourceId)
			.set(SkillDatasource::getIsActive, isActive)
			.set(SkillDatasource::getLastModifyTime, Instant.now()));
	}

	/**
	 * 启用 Skill 与数据源的既有关联。
	 */
	default int enableRelation(Long skillId, Long datasourceId) {
		return updateRelation(skillId, datasourceId, true);
	}

	/**
	 * 逻辑删除 Skill 与数据源的关联。
	 */
	default int removeRelation(Long skillId, Long datasourceId) {
		return delete(new LambdaQueryWrapper<SkillDatasource>().eq(SkillDatasource::getSkillId, skillId)
			.eq(SkillDatasource::getDatasourceId, datasourceId));
	}

}
