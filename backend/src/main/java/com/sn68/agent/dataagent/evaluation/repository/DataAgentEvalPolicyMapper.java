/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.dto.EvalPolicyQueryRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalPolicy;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent 评估策略 Mapper。
 */
@Repository
public interface DataAgentEvalPolicyMapper extends SuperMapper<DataAgentEvalPolicy> {

	/**
	 * 按租户 + 策略编码查询未删除的评估策略（编码唯一性校验/详情定位用）。
	 */
	default DataAgentEvalPolicy findByCode(String tenantId, String policyCode) {
		return selectOne(Wraps.<DataAgentEvalPolicy>lbQ()
			.eq(DataAgentEvalPolicy::getDeleted, false)
			.eq(DataAgentEvalPolicy::getTenantId, tenantId)
			.eq(DataAgentEvalPolicy::getPolicyCode, trim(policyCode))
			.last(" limit 1"));
	}

	/**
	 * 按 ID 查询启用且未删除的评估策略；禁用或已删除返回 null。
	 */
	default DataAgentEvalPolicy findEnabledById(Long id) {
		return selectOne(Wraps.<DataAgentEvalPolicy>lbQ()
			.eq(DataAgentEvalPolicy::getDeleted, false)
			.eq(DataAgentEvalPolicy::getStatus, "enabled")
			.eq(DataAgentEvalPolicy::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 查询租户内指定主体类型的默认启用策略，多条时取版本号/ID 最大的一条。
	 */
	default DataAgentEvalPolicy findDefault(String tenantId, String subjectType) {
		return selectOne(Wraps.<DataAgentEvalPolicy>lbQ()
			.eq(DataAgentEvalPolicy::getDeleted, false)
			.eq(DataAgentEvalPolicy::getStatus, "enabled")
			.eq(DataAgentEvalPolicy::getDefaultFlag, true)
			.eq(DataAgentEvalPolicy::getTenantId, tenantId)
			.eq(DataAgentEvalPolicy::getSubjectType, trim(subjectType))
			.orderByDesc(DataAgentEvalPolicy::getVersionNo)
			.orderByDesc(DataAgentEvalPolicy::getId)
			.last(" limit 1"));
	}

	/**
	 * 查询平台级（tenant_id 为空）默认启用策略，作为租户无默认策略时的回退。
	 */
	default DataAgentEvalPolicy findGlobalDefault(String subjectType) {
		return selectOne(Wraps.<DataAgentEvalPolicy>lbQ()
			.eq(DataAgentEvalPolicy::getDeleted, false)
			.eq(DataAgentEvalPolicy::getStatus, "enabled")
			.eq(DataAgentEvalPolicy::getDefaultFlag, true)
			.isNull(DataAgentEvalPolicy::getTenantId)
			.eq(DataAgentEvalPolicy::getSubjectType, trim(subjectType))
			.orderByDesc(DataAgentEvalPolicy::getVersionNo)
			.orderByDesc(DataAgentEvalPolicy::getId)
			.last(" limit 1"));
	}

	/**
	 * 分页查询租户内评估策略：按编码/名称模糊/状态/主体类型/默认标记可选过滤，未删除，按 ID 倒序。
	 */
	default IPage<DataAgentEvalPolicy> selectPolicyPage(IPage<DataAgentEvalPolicy> page, EvalPolicyQueryRequest request,
			String tenantId) {
		EvalPolicyQueryRequest query = request == null ? new EvalPolicyQueryRequest() : request;
		return selectPage(page, Wraps.<DataAgentEvalPolicy>lbQ()
			.eq(DataAgentEvalPolicy::getDeleted, false)
			.eq(DataAgentEvalPolicy::getTenantId, tenantId)
			.eq(DataAgentEvalPolicy::getPolicyCode, trim(query.getPolicyCode()))
			.like(DataAgentEvalPolicy::getPolicyName, trim(query.getPolicyName()))
			.eq(DataAgentEvalPolicy::getStatus, trim(query.getStatus()))
			.eq(DataAgentEvalPolicy::getSubjectType, trim(query.getSubjectType()))
			.eq(DataAgentEvalPolicy::getDefaultFlag, query.getDefaultFlag())
			.orderByDesc(DataAgentEvalPolicy::getId));
	}

	/**
	 * 清除租户内同主体类型的默认标记（设置新默认策略前调用），excludeId 用于跳过即将成为默认的记录。
	 */
	default int clearDefault(String tenantId, String subjectType, Long excludeId, Instant updateTime) {
		var wrapper = Wraps.<DataAgentEvalPolicy>lbU()
			.eq(DataAgentEvalPolicy::getDeleted, false)
			.eq(DataAgentEvalPolicy::getTenantId, tenantId)
			.eq(DataAgentEvalPolicy::getDefaultFlag, true)
			.eq(DataAgentEvalPolicy::getSubjectType, trim(subjectType))
			.set(DataAgentEvalPolicy::getDefaultFlag, false)
			.set(DataAgentEvalPolicy::getLastModifyTime, updateTime);
		if (excludeId != null) {
			wrapper.ne(DataAgentEvalPolicy::getId, excludeId);
		}
		return update(null, wrapper);
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
