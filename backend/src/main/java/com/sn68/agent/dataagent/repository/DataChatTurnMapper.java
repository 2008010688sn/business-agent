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
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnPageQueryReq;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Data会话TurnMapper服务契约。
 */
@Repository
public interface DataChatTurnMapper extends SuperMapper<DataChatTurn> {

	/**
	 * 按会话 + 运行请求 ID 查询单轮对话记录（幂等定位一次运行）。
	 */
	default DataChatTurn findBySessionIdAndRuntimeRequestId(Long sessionId, String runtimeRequestId) {
		return selectOne(new LambdaQueryWrapper<DataChatTurn>()
			.eq(DataChatTurn::getSessionId, sessionId)
			.eq(DataChatTurn::getRuntimeRequestId, runtimeRequestId)
			.last(" limit 1"));
	}

	/**
	 * 查询会话的全部对话轮次，按开始时间、ID 倒序。
	 */
	default List<DataChatTurn> findBySessionId(Long sessionId) {
		return selectList(new LambdaQueryWrapper<DataChatTurn>()
			.eq(DataChatTurn::getSessionId, sessionId)
			.orderByDesc(DataChatTurn::getStartedAt)
			.orderByDesc(DataChatTurn::getId));
	}

	/**
	 * 运行诊断分页查询：支持 Agent/用户/会话/状态/来源/渠道/时间区间/工具调用有无等组合过滤，
	 * 关键字模糊匹配问题、答案、请求 ID 与创建人。
	 */
	default IPage<DataChatTurn> selectDiagnosticsPage(IPage<DataChatTurn> page, DataChatTurnPageQueryReq request) {
		return selectPage(page, buildQuery(request)
			.orderByDesc(DataChatTurn::getStartedAt)
			.orderByDesc(DataChatTurn::getId));
	}

	/**
	 * 将 running 状态的轮次落到终态（条件更新，非 running 不命中，防止覆盖已完成轮次）。
	 *
	 * <p>routeReasonCode / routeDegradeMode 是排障用的路由诊断字段；本方法是路由失败轮次唯一的落库
	 * 入口，缺了它们 ROUTE_UNAVAILABLE 的具体失败码就无处可查。为空时不覆盖已有值。
	 */
	default int updateStatus(Long sessionId, String runtimeRequestId, String status, String errorMessage,
			Instant finishedAt, long durationMs, String routeReasonCode, String routeDegradeMode) {
		return update(null, Wraps.<DataChatTurn>lbU()
			.eq(DataChatTurn::getSessionId, sessionId)
			.eq(DataChatTurn::getRuntimeRequestId, runtimeRequestId)
			.eq(DataChatTurn::getStatus, "running")
			.set(DataChatTurn::getStatus, status)
			.set(DataChatTurn::getErrorMessage, errorMessage)
			.set(DataChatTurn::getFinishedAt, finishedAt)
			.set(DataChatTurn::getDurationMs, durationMs)
			.set(StringUtils.hasText(routeReasonCode), DataChatTurn::getRouteReasonCode, routeReasonCode)
			.set(StringUtils.hasText(routeDegradeMode), DataChatTurn::getRouteDegradeMode, routeDegradeMode)
			.set(DataChatTurn::getLastModifyTime, finishedAt));
	}

	/**
	 * 查询卡在 running 且开始时间早于 startedBefore 的僵尸轮次；limit 收敛到 [1,1000] 防止一次扫描过大。
	 */
	default List<DataChatTurn> findStaleRunning(Instant startedBefore, int limit) {
		int safeLimit = Math.max(1, Math.min(limit, 1000));
		return selectList(Wraps.<DataChatTurn>lbQ()
			.eq(DataChatTurn::getStatus, "running")
			.le(DataChatTurn::getStartedAt, startedBefore)
			.orderByAsc(DataChatTurn::getStartedAt)
			.orderByAsc(DataChatTurn::getId)
			.last(" limit " + safeLimit));
	}

	/**
	 * 查询用户在会话内最近一次成功且路由决策为单选（SELECT 且命中 1 个目标）的轮次，
	 * 用于「继续上次选择」的路由短路；参数缺失时返回 null。
	 */
	default DataChatTurn findRecentSuccessfulSingleSelection(Long agentId, Long sessionId, Long userId,
			Instant startedAfter) {
		if (agentId == null || sessionId == null || userId == null || startedAfter == null) {
			return null;
		}
		return selectOne(Wraps.<DataChatTurn>lbQ()
			.eq(DataChatTurn::getAgentId, agentId)
			.eq(DataChatTurn::getSessionId, sessionId)
			.eq(DataChatTurn::getUserId, userId)
			.eq(DataChatTurn::getStatus, "success")
			.eq(DataChatTurn::getRouteDecision, "SELECT")
			.eq(DataChatTurn::getRouteSelectedCount, 1)
			.isNotNull(DataChatTurn::getRouteTargetType)
			.isNotNull(DataChatTurn::getRouteTargetId)
			.ge(DataChatTurn::getFinishedAt, startedAfter)
			.orderByDesc(DataChatTurn::getFinishedAt)
			.orderByDesc(DataChatTurn::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 将单条僵尸轮次标记为 failed（条件更新：仍为 running 且开始时间早于 startedBefore 才命中，幂等）。
	 */
	default int markStaleFailedIfRunning(Long id, Instant startedBefore, String errorMessage, Instant finishedAt,
			long durationMs) {
		return update(null, Wraps.<DataChatTurn>lbU()
			.eq(DataChatTurn::getId, id)
			.eq(DataChatTurn::getStatus, "running")
			.le(DataChatTurn::getStartedAt, startedBefore)
			.set(DataChatTurn::getStatus, "failed")
			.set(DataChatTurn::getErrorMessage, errorMessage)
			.set(DataChatTurn::getFinishedAt, finishedAt)
			.set(DataChatTurn::getDurationMs, durationMs)
			.set(DataChatTurn::getLastModifyTime, finishedAt));
	}

	/**
	 * 组装诊断查询条件（来源/平台统一大写归一化，hasToolCall 三态：true→>0、false→null 或 0），空条件自动忽略。
	 */
	private static LbqWrapper<DataChatTurn> buildQuery(DataChatTurnPageQueryReq request) {
		DataChatTurnPageQueryReq query = request == null ? new DataChatTurnPageQueryReq() : request;
		LbqWrapper<DataChatTurn> wrapper = Wraps.lbQ();
		if (query.getAgentId() != null) {
			wrapper.eq(DataChatTurn::getAgentId, query.getAgentId());
		}
		if (query.getUserId() != null) {
			wrapper.eq(DataChatTurn::getUserId, query.getUserId());
		}
		if (query.getSessionId() != null) {
			wrapper.eq(DataChatTurn::getSessionId, query.getSessionId());
		}
		if (StringUtils.hasText(query.getRuntimeRequestId())) {
			wrapper.eq(DataChatTurn::getRuntimeRequestId, query.getRuntimeRequestId().trim());
		}
		if (StringUtils.hasText(query.getStatus())) {
			wrapper.eq(DataChatTurn::getStatus, query.getStatus().trim());
		}
		if (StringUtils.hasText(query.getRequestSource())) {
			wrapper.eq(DataChatTurn::getRequestSource, query.getRequestSource().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getProvider())) {
			wrapper.eq(DataChatTurn::getProvider, query.getProvider().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getConnectorCode())) {
			wrapper.eq(DataChatTurn::getConnectorCode, query.getConnectorCode().trim());
		}
		if (StringUtils.hasText(query.getExternalUserId())) {
			wrapper.eq(DataChatTurn::getExternalUserId, query.getExternalUserId().trim());
		}
		if (query.getHasDatasource() != null) {
			wrapper.eq(DataChatTurn::getHasDatasource, query.getHasDatasource());
		}
		if (query.getHasSql() != null) {
			wrapper.eq(DataChatTurn::getHasSql, query.getHasSql());
		}
		if (Boolean.TRUE.equals(query.getHasToolCall())) {
			wrapper.gt(DataChatTurn::getToolCount, 0);
		}
		else if (Boolean.FALSE.equals(query.getHasToolCall())) {
			wrapper.and(nested -> nested.isNull(DataChatTurn::getToolCount).or().eq(DataChatTurn::getToolCount, 0));
		}
		if (query.getStartTime() != null) {
			wrapper.ge(DataChatTurn::getStartedAt, query.getStartTime());
		}
		if (query.getEndTime() != null) {
			wrapper.le(DataChatTurn::getStartedAt, query.getEndTime());
		}
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(nested -> nested.like(DataChatTurn::getQuestion, keyword)
				.or()
				.like(DataChatTurn::getAnswer, keyword)
				.or()
				.like(DataChatTurn::getRuntimeRequestId, keyword)
				.or()
				.like(DataChatTurn::getCreateName, keyword)
				.or()
				.like(DataChatTurn::getCreateBy, keyword));
		}
		return wrapper;
	}

}
