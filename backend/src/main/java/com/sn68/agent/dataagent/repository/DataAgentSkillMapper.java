/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.dto.skill.SkillPageQueryReq;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Skill catalog mapper.
 */
@Repository
public interface DataAgentSkillMapper extends SuperMapper<DataAgentSkill> {

	/**
	 * 分页查询租户可见的 Skill（scope=TENANT 且租户匹配），支持状态/执行模式过滤与
	 * 编码/名称/描述关键字模糊搜索，按展示顺序、编码升序。
	 */
	default IPage<DataAgentSkill> selectVisiblePage(IPage<DataAgentSkill> page, SkillPageQueryReq request,
			String tenantId) {
		SkillPageQueryReq query = request == null ? new SkillPageQueryReq() : request;
		var wrapper = Wraps.<DataAgentSkill>lbQ()
			.eq(DataAgentSkill::getStatus, trim(query.getStatus()))
			.eq(DataAgentSkill::getExecutionMode, trim(query.getExecutionMode()));
		applyVisibleScope(wrapper, tenantId);
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(item -> item.like(DataAgentSkill::getSkillCode, keyword)
				.or()
				.like(DataAgentSkill::getSkillName, keyword)
				.or()
				.like(DataAgentSkill::getDescription, keyword));
		}
		return selectPage(page, wrapper.orderByAsc(DataAgentSkill::getDisplayOrder)
			.orderByAsc(DataAgentSkill::getSkillCode));
	}

	/**
	 * 按 Skill 编码查询租户可见的 Skill；skillCode 为空时返回 null。
	 */
	default DataAgentSkill findVisibleByCode(String skillCode, String tenantId) {
		if (!StringUtils.hasText(skillCode)) {
			return null;
		}
		var wrapper = Wraps.<DataAgentSkill>lbQ()
			.eq(DataAgentSkill::getSkillCode, skillCode.trim());
		applyVisibleScope(wrapper, tenantId);
		return selectOne(wrapper);
	}

	/**
	 * 查询租户可见的全部 Skill，按展示顺序、编码升序。
	 */
	default List<DataAgentSkill> findVisible(String tenantId) {
		var wrapper = Wraps.<DataAgentSkill>lbQ();
		applyVisibleScope(wrapper, tenantId);
		return selectList(wrapper
			.orderByAsc(DataAgentSkill::getDisplayOrder)
			.orderByAsc(DataAgentSkill::getSkillCode));
	}

	/**
	 * 追加租户可见范围条件：仅 scope=TENANT 且 tenant_id 匹配（租户隔离的统一入口）。
	 */
	private static void applyVisibleScope(LbqWrapper<DataAgentSkill> wrapper, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			wrapper.apply("1 = 0");
			return;
		}
		wrapper.eq(DataAgentSkill::getScope, "TENANT")
			.eq(DataAgentSkill::getTenantId, tenantId.trim());
	}

	/**
	 * 空白串归一化为 null，配合 Wraps 自动跳过空条件。
	 */
	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
