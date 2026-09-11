/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationPageQueryReq;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityApplication;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent可见性ApplicationMapper服务契约。
 */
@Repository
public interface DataAgentVisibilityApplicationMapper extends SuperMapper<DataAgentVisibilityApplication> {

	/**
	 * 分页查询可见性申请单，支持 Agent/申请人/状态/审批模式/业务单号/提交时间区间过滤，按创建时间倒序。
	 */
	default IPage<DataAgentVisibilityApplication> selectApplicationPage(IPage<DataAgentVisibilityApplication> page,
			AgentVisibilityApplicationPageQueryReq request) {
		return selectPage(page, buildQuery(request).orderByDesc(DataAgentVisibilityApplication::getCreateTime));
	}

	/**
	 * 查询申请人在指定 Agent 上待审批（PENDING）的申请单（防止重复提交）；参数缺失时返回 null。
	 */
	default DataAgentVisibilityApplication findPending(Long agentId, String applicantUserId) {
		if (agentId == null || !StringUtils.hasText(applicantUserId)) {
			return null;
		}
		return selectOne(Wraps.<DataAgentVisibilityApplication>lbQ()
			.eq(DataAgentVisibilityApplication::getAgentId, agentId)
			.eq(DataAgentVisibilityApplication::getApplicantUserId, applicantUserId.trim())
			.eq(DataAgentVisibilityApplication::getStatus, AgentVisibilityConstant.APPLICATION_STATUS_PENDING)
			.last("limit 1"));
	}

	/**
	 * 按主键行级锁（FOR UPDATE）读取申请单，供审批事务内防并发重复处理；必须在事务中调用。
	 */
	default DataAgentVisibilityApplication selectByIdForUpdate(Long id) {
		if (id == null) {
			return null;
		}
		return selectOne(Wraps.<DataAgentVisibilityApplication>lbQ()
			.eq(DataAgentVisibilityApplication::getId, id)
			.last("for update"));
	}

	/**
	 * 组装申请单查询条件（状态/审批模式统一大写归一化），空条件自动忽略。
	 */
	private static LbqWrapper<DataAgentVisibilityApplication> buildQuery(
			AgentVisibilityApplicationPageQueryReq request) {
		AgentVisibilityApplicationPageQueryReq query = request == null
				? new AgentVisibilityApplicationPageQueryReq() : request;
		LbqWrapper<DataAgentVisibilityApplication> wrapper = Wraps.<DataAgentVisibilityApplication>lbQ();
		if (query.getAgentId() != null) {
			wrapper.eq(DataAgentVisibilityApplication::getAgentId, query.getAgentId());
		}
		if (StringUtils.hasText(query.getAgentName())) {
			wrapper.like(DataAgentVisibilityApplication::getAgentName, query.getAgentName().trim());
		}
		if (StringUtils.hasText(query.getApplicantUserId())) {
			wrapper.eq(DataAgentVisibilityApplication::getApplicantUserId, query.getApplicantUserId().trim());
		}
		if (StringUtils.hasText(query.getApplicantNickName())) {
			wrapper.like(DataAgentVisibilityApplication::getApplicantNickName, query.getApplicantNickName().trim());
		}
		if (StringUtils.hasText(query.getStatus())) {
			wrapper.eq(DataAgentVisibilityApplication::getStatus, query.getStatus().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getApprovalMode())) {
			wrapper.eq(DataAgentVisibilityApplication::getApprovalMode, query.getApprovalMode().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getBusinessCode())) {
			wrapper.like(DataAgentVisibilityApplication::getBusinessCode, query.getBusinessCode().trim());
		}
		if (query.getSubmitTimeStart() != null) {
			wrapper.ge(DataAgentVisibilityApplication::getSubmitTime, query.getSubmitTimeStart());
		}
		if (query.getSubmitTimeEnd() != null) {
			wrapper.le(DataAgentVisibilityApplication::getSubmitTime, query.getSubmitTimeEnd());
		}
		return wrapper;
	}

}
