/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Skill version mapper.
 */
@Repository
public interface DataAgentSkillVersionMapper extends SuperMapper<DataAgentSkillVersion> {

	/**
	 * 批量查询多个 Skill 的已发布（PUBLISHED）版本，按版本号倒序；skillIds 为空时返回空集，避免全表扫描。
	 */
	default List<DataAgentSkillVersion> findPublishedBySkillIds(Collection<Long> skillIds) {
		if (skillIds == null || skillIds.isEmpty()) {
			return List.of();
		}
		return selectList(Wraps.<DataAgentSkillVersion>lbQ()
			.in(DataAgentSkillVersion::getSkillId, skillIds)
			.eq(DataAgentSkillVersion::getStatus, "PUBLISHED")
			.orderByDesc(DataAgentSkillVersion::getVersionNo));
	}

	/**
	 * 查询 Skill 的全部版本（不区分状态），按版本号倒序；逻辑删除自动过滤。
	 */
	default List<DataAgentSkillVersion> findBySkillId(Long skillId) {
		return selectList(Wraps.<DataAgentSkillVersion>lbQ()
			.eq(DataAgentSkillVersion::getSkillId, skillId)
			.orderByDesc(DataAgentSkillVersion::getVersionNo));
	}

	/**
	 * 查询 Skill 版本号最大的草稿（DRAFT）版本，不存在返回 null。
	 */
	default DataAgentSkillVersion findLatestDraft(Long skillId) {
		return selectOne(Wraps.<DataAgentSkillVersion>lbQ()
			.eq(DataAgentSkillVersion::getSkillId, skillId)
			.eq(DataAgentSkillVersion::getStatus, "DRAFT")
			.orderByDesc(DataAgentSkillVersion::getVersionNo)
			.last("LIMIT 1"));
	}

	/**
	 * 计算 Skill 的下一个版本号（当前最大版本号 + 1，无版本时从 1 开始）；非并发安全，调用方需自行防重。
	 */
	default int nextVersionNo(Long skillId) {
		DataAgentSkillVersion version = selectOne(Wraps.<DataAgentSkillVersion>lbQ()
			.eq(DataAgentSkillVersion::getSkillId, skillId)
			.orderByDesc(DataAgentSkillVersion::getVersionNo)
			.last("LIMIT 1"));
		return version == null || version.getVersionNo() == null ? 1 : version.getVersionNo() + 1;
	}

}
