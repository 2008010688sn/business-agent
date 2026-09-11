/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.dto.tool.ToolReferenceResp;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/**
 * Skill to pinned tool-version reference mapper.
 */
@Repository
public interface DataAgentSkillToolRefMapper extends SuperMapper<DataAgentSkillToolRef> {

	/**
	 * 按工具资源标识查询全部工具版本引用，按 Skill 版本 ID 升序；逻辑删除由 {@code @TableLogic} 自动过滤。
	 */
	default List<DataAgentSkillToolRef> findByResourceKey(String resourceKey) {
		return selectList(Wraps.<DataAgentSkillToolRef>lbQ()
			.eq(DataAgentSkillToolRef::getResourceKey, resourceKey)
			.orderByAsc(DataAgentSkillToolRef::getSkillVersionId));
	}

	/**
	 * 查询工具资源被「已发布 Skill 当前发布版本」引用的可见清单。
	 *
	 * <p>复杂 SQL（三表 JOIN + 动态租户条件），XML 维护：{@code mapper/dataagent/DataAgentSkillToolRefMapper.xml}。
	 * tenantId 非空时仅返回 scope=TENANT 且租户匹配的引用，tenantId 为空时恒返回空集；已删除与禁用引用均排除。
	 */
	List<ToolReferenceResp> findVisiblePublishedReferences(@Param("resourceKey") String resourceKey,
			@Param("tenantId") String tenantId);

	/**
	 * 统计工具资源被「已发布 Skill 当前发布版本」引用且未禁用的条数（不区分租户，用于删除前防护校验）。
	 *
	 * <p>复杂 SQL（三表 JOIN），XML 维护：{@code mapper/dataagent/DataAgentSkillToolRefMapper.xml}。
	 */
	long countPublishedReferences(@Param("resourceKey") String resourceKey);

	/**
	 * 按 Skill 版本 ID 查询其固定的工具版本引用，按展示顺序升序；逻辑删除自动过滤。
	 */
	default List<DataAgentSkillToolRef> findBySkillVersionId(Long skillVersionId) {
		return selectList(Wraps.<DataAgentSkillToolRef>lbQ()
			.eq(DataAgentSkillToolRef::getSkillVersionId, skillVersionId)
			.orderByAsc(DataAgentSkillToolRef::getDisplayOrder));
	}

	/**
	 * 按 Skill 版本 ID 逻辑删除其全部工具版本引用；skillVersionId 为空时直接返回 0，避免全表误删。
	 */
	default int deleteBySkillVersionId(Long skillVersionId) {
		return skillVersionId == null ? 0 : delete(Wraps.<DataAgentSkillToolRef>lbQ()
			.eq(DataAgentSkillToolRef::getSkillVersionId, skillVersionId));
	}

}
