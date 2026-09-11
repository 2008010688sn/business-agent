/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageQueryReq;
import com.sn68.agent.dataagent.entity.AgentTokenUsage;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentToken用量Mapper服务契约。
 * <p>
 * 租户谓词只接受服务端解析出的租户，不读取请求体自带的 {@code tenantId}；跨租户读取必须显式走
 * {@code *AcrossTenants} 方法，避免省略条件就静默退化成全平台查询。
 */
@Repository
public interface AgentTokenUsageMapper extends SuperMapper<AgentTokenUsage> {

	/**
	 * 按指定租户分页查询 Token 用量，租户谓词恒定生效。
	 */
	default IPage<AgentTokenUsage> selectUsagePage(IPage<AgentTokenUsage> page, AgentTokenUsageQueryReq request,
			String tenantId) {
		return selectPage(page, tenantScoped(buildQuery(request), tenantId)
			.orderByDesc(AgentTokenUsage::getCreateTime)
			.orderByDesc(AgentTokenUsage::getId));
	}

	/**
	 * 不带租户谓词分页查询 Token 用量，仅供已校验平台管理员身份的入口调用。
	 */
	default IPage<AgentTokenUsage> selectUsagePageAcrossTenants(IPage<AgentTokenUsage> page,
			AgentTokenUsageQueryReq request) {
		return selectPage(page, buildQuery(request)
			.orderByDesc(AgentTokenUsage::getCreateTime)
			.orderByDesc(AgentTokenUsage::getId));
	}

	/**
	 * 按指定租户查询 Token 用量明细，租户谓词恒定生效。
	 */
	default List<AgentTokenUsage> selectUsageList(AgentTokenUsageQueryReq request, String tenantId) {
		return selectList(tenantScoped(buildQuery(request), tenantId).orderByDesc(AgentTokenUsage::getCreateTime));
	}

	/**
	 * 不带租户谓词查询 Token 用量明细，仅供已校验平台管理员身份的入口调用。
	 */
	default List<AgentTokenUsage> selectUsageListAcrossTenants(AgentTokenUsageQueryReq request) {
		return selectList(buildQuery(request).orderByDesc(AgentTokenUsage::getCreateTime));
	}

	/**
	 * 追加租户强制过滤：tenantId 缺失时直接失败关闭（抛异常），防止静默退化成全平台查询。
	 */
	private static LbqWrapper<AgentTokenUsage> tenantScoped(LbqWrapper<AgentTokenUsage> wrapper, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			// Wraps 会跳过空值条件，此处不失败关闭就会退化成读取全平台租户的用量。
			// badRequest(int, String) 是带 code 的通用工厂，传 403 即「禁止访问 + 自定义提示文案」。
			throw CheckedException.badRequest(403, "缺少租户上下文，无法查询 Token 用量");
		}
		return wrapper.eq(AgentTokenUsage::getTenantId, tenantId.trim());
	}

	/**
	 * 组装用量查询条件：用户/Agent/模型/来源/状态等精确匹配 + 创建时间区间，空条件自动忽略。
	 */
	private static LbqWrapper<AgentTokenUsage> buildQuery(AgentTokenUsageQueryReq request) {
		AgentTokenUsageQueryReq query = request == null ? new AgentTokenUsageQueryReq() : request;
		LbqWrapper<AgentTokenUsage> wrapper = Wraps.lbQ();
		if (StringUtils.hasText(query.getUserId())) {
			wrapper.eq(AgentTokenUsage::getUserId, query.getUserId().trim());
		}
		if (query.getAgentId() != null) {
			wrapper.eq(AgentTokenUsage::getAgentId, query.getAgentId());
		}
		if (query.getModelConfigId() != null) {
			wrapper.eq(AgentTokenUsage::getModelConfigId, query.getModelConfigId());
		}
		if (StringUtils.hasText(query.getProvider())) {
			wrapper.eq(AgentTokenUsage::getProvider, query.getProvider().trim());
		}
		if (StringUtils.hasText(query.getModelName())) {
			wrapper.eq(AgentTokenUsage::getModelName, query.getModelName().trim());
		}
		if (StringUtils.hasText(query.getUsageSource())) {
			wrapper.eq(AgentTokenUsage::getUsageSource, query.getUsageSource().trim());
		}
		if (StringUtils.hasText(query.getMeteringMode())) {
			wrapper.eq(AgentTokenUsage::getMeteringMode, query.getMeteringMode().trim());
		}
		if (StringUtils.hasText(query.getStatus())) {
			wrapper.eq(AgentTokenUsage::getStatus, query.getStatus().trim());
		}
		if (StringUtils.hasText(query.getRequestSource())) {
			wrapper.eq(AgentTokenUsage::getRequestSource, query.getRequestSource().trim());
		}
		if (StringUtils.hasText(query.getRuntimeRequestId())) {
			wrapper.eq(AgentTokenUsage::getRuntimeRequestId, query.getRuntimeRequestId().trim());
		}
		if (StringUtils.hasText(query.getRootRuntimeRequestId())) {
			wrapper.eq(AgentTokenUsage::getRootRuntimeRequestId, query.getRootRuntimeRequestId().trim());
		}
		if (query.getStartTime() != null) {
			wrapper.ge(AgentTokenUsage::getCreateTime, query.getStartTime());
		}
		if (query.getEndTime() != null) {
			wrapper.le(AgentTokenUsage::getCreateTime, query.getEndTime());
		}
		return wrapper;
	}

}
