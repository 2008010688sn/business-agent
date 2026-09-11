/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

/**
 * Execution-resource version mapper.
 */
@Repository
public interface AgentExecutionResourceVersionMapper extends SuperMapper<AgentExecutionResourceVersion> {

	/**
	 * 查询 Skill 工具编辑器可选的工具版本选项（含资源名、访问/暴露模式等展示列）。
	 *
	 * <p>复杂 SQL（LEFT JOIN + 多段动态条件），XML 维护：
	 * {@code mapper/dataagent/AgentExecutionResourceVersionMapper.xml}。
	 * 仅本租户（tenantId 为空则 XML 失败关闭）；mode=REACT 仅
	 * READ+MODEL，mode=FLOW 排除 WRITE+MODEL；selectedIds 中的已选版本无条件补入以保证编辑回显。
	 */
	List<Map<String, Object>> findSkillToolEditorOptions(@Param("tenantId") String tenantId,
			@Param("platformScope") Boolean platformScope, @Param("mode") String mode,
			@Param("selectedIds") Collection<Long> selectedIds);

	/**
	 * 按主键查询已发布（PUBLISHED）的工具版本，未发布或已逻辑删除返回 null。
	 */
	default AgentExecutionResourceVersion findPublished(Long id) {
		return selectOne(Wraps.<AgentExecutionResourceVersion>lbQ()
			.eq(AgentExecutionResourceVersion::getId, id)
			.eq(AgentExecutionResourceVersion::getStatus, "PUBLISHED"));
	}

	/**
	 * 按资源标识查询版本号最大的已发布工具版本；逻辑删除自动过滤。
	 */
	default AgentExecutionResourceVersion findLatestPublished(String resourceKey) {
		return selectOne(Wraps.<AgentExecutionResourceVersion>lbQ()
			.eq(AgentExecutionResourceVersion::getResourceKey, resourceKey)
			.eq(AgentExecutionResourceVersion::getStatus, "PUBLISHED")
			.orderByDesc(AgentExecutionResourceVersion::getVersionNo)
			.last("LIMIT 1"));
	}

	/**
	 * 按资源标识 + 版本号精确查询已发布工具版本；逻辑删除自动过滤。
	 */
	default AgentExecutionResourceVersion findPublished(String resourceKey, Integer versionNo) {
		return selectOne(Wraps.<AgentExecutionResourceVersion>lbQ()
			.eq(AgentExecutionResourceVersion::getResourceKey, resourceKey)
			.eq(AgentExecutionResourceVersion::getVersionNo, versionNo)
			.eq(AgentExecutionResourceVersion::getStatus, "PUBLISHED"));
	}

}
