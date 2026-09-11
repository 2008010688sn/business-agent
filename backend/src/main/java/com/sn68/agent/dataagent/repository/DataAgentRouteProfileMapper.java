/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 路由档案（route profile）Mapper：管理路由模型/embedding 能力探测状态、召回阈值与产物构建进度。
 *
 * <p>每个租户至多一个 ACTIVE。HTTP 读/激活必须带 tenantId（平台管理员会被拦截器豁免）。
 * 定时探测/产物扫描仍跨租户按行消费，用档案行上的 tenant_id，不假设登录上下文。
 * 写操作统一走 revision 乐观锁条件更新，命中 0 行表示版本冲突或状态不符。
 */
@Repository
public interface DataAgentRouteProfileMapper extends SuperMapper<DataAgentRouteProfile> {

	/**
	 * 查询指定租户当前 ACTIVE 档案（每租户至多一条）。
	 */
	default DataAgentRouteProfile findActive(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		return selectOne(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getTenantId, tenantId.trim())
			.eq(DataAgentRouteProfile::getStatus, "ACTIVE")
			.last("LIMIT 1"));
	}

	/**
	 * 查询指定租户最近一条工作中的非 ACTIVE 档案（DRAFT/BUILDING/READY/FAILED）。
	 */
	default DataAgentRouteProfile findWorking(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		return selectOne(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getTenantId, tenantId.trim())
			.in(DataAgentRouteProfile::getStatus, List.of("DRAFT", "BUILDING", "READY", "FAILED"))
			.orderByDesc(DataAgentRouteProfile::getLastModifyTime)
			.orderByDesc(DataAgentRouteProfile::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 查询指定租户在 notBefore 之后最近退役的档案，用于管理端回滚展示。
	 */
	default DataAgentRouteProfile findLatestRetired(String tenantId, Instant notBefore) {
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		return selectOne(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getTenantId, tenantId.trim())
			.eq(DataAgentRouteProfile::getStatus, "RETIRED")
			.ge(DataAgentRouteProfile::getLastModifyTime, notBefore)
			.orderByDesc(DataAgentRouteProfile::getLastModifyTime)
			.orderByDesc(DataAgentRouteProfile::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 跨租户查询 notBefore 之后最近退役档案（产物重建守护任务）。
	 */
	default DataAgentRouteProfile findLatestRetired(Instant notBefore) {
		return selectOne(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getStatus, "RETIRED")
			.ge(DataAgentRouteProfile::getLastModifyTime, notBefore)
			.orderByDesc(DataAgentRouteProfile::getLastModifyTime)
			.orderByDesc(DataAgentRouteProfile::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 按主键查询档案（逻辑删除自动过滤，不限状态）。
	 */
	default DataAgentRouteProfile findAvailableById(Long id) {
		return selectOne(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getId, id)
			.last("LIMIT 1"));
	}

	/**
	 * 查询需要维护向量产物的档案：ACTIVE/BUILDING/READY 且语义召回开启、embedding 探测为 SUPPORTED。
	 */
	default List<DataAgentRouteProfile> findArtifactProfiles() {
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.in(DataAgentRouteProfile::getStatus, List.of("ACTIVE", "BUILDING", "READY"))
			.eq(DataAgentRouteProfile::getSemanticRecallEnabled, true)
			.eq(DataAgentRouteProfile::getEmbeddingProbeState, "SUPPORTED")
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 查询构建卡死的档案：BUILDING/ACTIVE 且 buildStatus=RUNNING、最后修改时间早于 cutoff，供巡检恢复。
	 */
	default List<DataAgentRouteProfile> findStaleBuilding(Instant cutoff) {
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.in(DataAgentRouteProfile::getStatus, List.of("BUILDING", "ACTIVE"))
			.eq(DataAgentRouteProfile::getBuildStatus, "RUNNING")
			.le(DataAgentRouteProfile::getLastModifyTime, cutoff)
			.orderByAsc(DataAgentRouteProfile::getLastModifyTime)
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 查询以指定模型作为路由消歧模型且已启用消歧的档案；modelConfigId 为空时返回空集。
	 */
	default List<DataAgentRouteProfile> findRouteModelProfiles(Long modelConfigId) {
		if (modelConfigId == null) {
			return List.of();
		}
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getRouteModelConfigId, modelConfigId)
			.eq(DataAgentRouteProfile::getModelDisambiguationEnabled, true)
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 查询以指定模型作为 embedding 模型且已启用语义召回的档案；modelConfigId 为空时返回空集。
	 */
	default List<DataAgentRouteProfile> findEmbeddingModelProfiles(Long modelConfigId) {
		if (modelConfigId == null) {
			return List.of();
		}
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getEmbeddingModelConfigId, modelConfigId)
			.eq(DataAgentRouteProfile::getSemanticRecallEnabled, true)
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 查询路由模型能力探测已失效（STALE）且消歧开启的档案，供能力重探任务消费。
	 */
	default List<DataAgentRouteProfile> findStaleRouteModelCapabilities() {
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getRouteModelProbeState, "STALE")
			.eq(DataAgentRouteProfile::getModelDisambiguationEnabled, true)
			.orderByAsc(DataAgentRouteProfile::getLastModifyTime)
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 查询 embedding 能力探测已失效（STALE）且语义召回开启的档案，供能力重探任务消费。
	 */
	default List<DataAgentRouteProfile> findStaleEmbeddingCapabilities() {
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.eq(DataAgentRouteProfile::getEmbeddingProbeState, "STALE")
			.eq(DataAgentRouteProfile::getSemanticRecallEnabled, true)
			.orderByAsc(DataAgentRouteProfile::getLastModifyTime)
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 查询因 embedding 变更被标记为待重建产物（NOT_BUILT + REBUILD_PENDING 错误码）的档案。
	 */
	default List<DataAgentRouteProfile> findPendingEmbeddingArtifactRebuilds() {
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.in(DataAgentRouteProfile::getStatus, List.of("DRAFT", "READY", "FAILED", "ACTIVE"))
			.eq(DataAgentRouteProfile::getSemanticRecallEnabled, true)
			.eq(DataAgentRouteProfile::getEmbeddingProbeState, "SUPPORTED")
			.eq(DataAgentRouteProfile::getBuildStatus, "NOT_BUILT")
			.eq(DataAgentRouteProfile::getLastErrorCode, "ROUTE_EMBEDDING_ARTIFACT_REBUILD_PENDING")
			.orderByAsc(DataAgentRouteProfile::getLastModifyTime)
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 查询因执行器拒绝（EXECUTOR_REJECTED）导致构建失败的档案，供重试任务消费。
	 */
	default List<DataAgentRouteProfile> findRejectedArtifactBuilds() {
		return selectList(Wraps.<DataAgentRouteProfile>lbQ()
			.in(DataAgentRouteProfile::getStatus, List.of("DRAFT", "FAILED", "ACTIVE"))
			.eq(DataAgentRouteProfile::getBuildStatus, "FAILED")
			.eq(DataAgentRouteProfile::getLastErrorCode, "ROUTE_RETRIEVAL_EXECUTOR_REJECTED")
			.orderByAsc(DataAgentRouteProfile::getLastModifyTime)
			.orderByAsc(DataAgentRouteProfile::getId));
	}

	/**
	 * 乐观锁状态流转：revision 与期望状态同时匹配才更新，revision 自增；返回 0 表示并发冲突。
	 */
	default int updateStatus(Long id, Long revision, String expectedStatus, String status, String buildStatus,
			String errorCode) {
		return update(null, Wraps.<DataAgentRouteProfile>lbU()
			.eq(DataAgentRouteProfile::getId, id)
			.eq(DataAgentRouteProfile::getRevision, revision)
			.eq(DataAgentRouteProfile::getStatus, expectedStatus)
			.set(DataAgentRouteProfile::getStatus, status)
			.set(DataAgentRouteProfile::getBuildStatus, buildStatus)
			.set(DataAgentRouteProfile::getLastErrorCode, errorCode)
			.set(DataAgentRouteProfile::getRevision, revision + 1)
			.set(DataAgentRouteProfile::getLastModifyTime, Instant.now()));
	}

	/**
	 * 将 BUILDING+RUNNING 的档案整体标记为构建失败（状态与 buildStatus 同置 FAILED），乐观锁保护。
	 */
	default int markBuildFailed(Long id, Long revision, int total, String errorCode) {
		return update(null, Wraps.<DataAgentRouteProfile>lbU()
			.eq(DataAgentRouteProfile::getId, id)
			.eq(DataAgentRouteProfile::getRevision, revision)
			.eq(DataAgentRouteProfile::getStatus, "BUILDING")
			.eq(DataAgentRouteProfile::getBuildStatus, "RUNNING")
			.set(DataAgentRouteProfile::getStatus, "FAILED")
			.set(DataAgentRouteProfile::getBuildStatus, "FAILED")
			.set(DataAgentRouteProfile::getBuildTotal, total)
			.set(DataAgentRouteProfile::getBuildReady, 0)
			.set(DataAgentRouteProfile::getBuildFailed, total)
			.set(DataAgentRouteProfile::getLastErrorCode, errorCode)
			.set(DataAgentRouteProfile::getRevision, revision + 1)
			.set(DataAgentRouteProfile::getLastModifyTime, Instant.now()));
	}

	/**
	 * 将 ACTIVE 档案的后台重建标记为失败：仅置 buildStatus=FAILED，档案保持 ACTIVE 继续服务。
	 */
	default int markActiveBuildFailed(Long id, Long revision, int total, String errorCode) {
		return update(null, Wraps.<DataAgentRouteProfile>lbU()
			.eq(DataAgentRouteProfile::getId, id)
			.eq(DataAgentRouteProfile::getRevision, revision)
			.eq(DataAgentRouteProfile::getStatus, "ACTIVE")
			.eq(DataAgentRouteProfile::getBuildStatus, "RUNNING")
			.set(DataAgentRouteProfile::getBuildStatus, "FAILED")
			.set(DataAgentRouteProfile::getBuildTotal, total)
			.set(DataAgentRouteProfile::getBuildReady, 0)
			.set(DataAgentRouteProfile::getBuildFailed, total)
			.set(DataAgentRouteProfile::getLastErrorCode, errorCode)
			.set(DataAgentRouteProfile::getRevision, revision + 1)
			.set(DataAgentRouteProfile::getLastModifyTime, Instant.now()));
	}

	/**
	 * 新档案激活后，把同租户内除 activeId 外的其余 ACTIVE 档案退役（每租户唯一 ACTIVE）。
	 */
	default int retireActiveExcept(String tenantId, Long activeId) {
		if (!StringUtils.hasText(tenantId) || activeId == null) {
			return 0;
		}
		return update(null, Wraps.<DataAgentRouteProfile>lbU()
			.eq(DataAgentRouteProfile::getTenantId, tenantId.trim())
			.eq(DataAgentRouteProfile::getStatus, "ACTIVE")
			.ne(DataAgentRouteProfile::getId, activeId)
			.set(DataAgentRouteProfile::getStatus, "RETIRED")
			.set(DataAgentRouteProfile::getLastModifyTime, Instant.now()));
	}

	/**
	 * 按 expectedRevision 乐观锁整档更新（显式 set 全量列，含置 null），revision 自增。
	 */
	default int updateWithRevision(DataAgentRouteProfile profile, long expectedRevision) {
		return updateWithRevision(profile, expectedRevision, false);
	}

	/**
	 * 构建期整档更新：在乐观锁基础上额外要求 BUILDING/ACTIVE 且 buildStatus=RUNNING，防止覆盖已终止的构建。
	 */
	default int updateBuildWithRevision(DataAgentRouteProfile profile, long expectedRevision) {
		return updateWithRevision(profile, expectedRevision, true);
	}

	/**
	 * 整档更新公共实现：显式 set 全部业务列以支持置 null；buildOnly 时附加构建中状态守卫。
	 */
	private int updateWithRevision(DataAgentRouteProfile profile, long expectedRevision, boolean buildOnly) {
		var wrapper = Wraps.<DataAgentRouteProfile>lbU()
			.eq(DataAgentRouteProfile::getId, profile.getId())
			.eq(DataAgentRouteProfile::getRevision, expectedRevision);
		if (buildOnly) {
			wrapper.in(DataAgentRouteProfile::getStatus, List.of("BUILDING", "ACTIVE"))
				.eq(DataAgentRouteProfile::getBuildStatus, "RUNNING");
		}
		return update(null, wrapper
			.set(DataAgentRouteProfile::getProfileName, profile.getProfileName())
			.set(DataAgentRouteProfile::getStatus, profile.getStatus())
			.set(DataAgentRouteProfile::getRouteModelConfigId, profile.getRouteModelConfigId())
			.set(DataAgentRouteProfile::getRouteModelProbeState, profile.getRouteModelProbeState())
			.set(DataAgentRouteProfile::getRouteModelProtocol, profile.getRouteModelProtocol())
			.set(DataAgentRouteProfile::getRouteModelFingerprint, profile.getRouteModelFingerprint())
			.set(DataAgentRouteProfile::getRouteModelProbeVersion, profile.getRouteModelProbeVersion())
			.set(DataAgentRouteProfile::getRouteModelCheckedAt, profile.getRouteModelCheckedAt())
			.set(DataAgentRouteProfile::getRouteModelLatencyMs, profile.getRouteModelLatencyMs())
			.set(DataAgentRouteProfile::getRouteModelFailureCode, profile.getRouteModelFailureCode())
			.set(DataAgentRouteProfile::getRouteModelRuntimeReady, profile.getRouteModelRuntimeReady())
			.set(DataAgentRouteProfile::getEmbeddingModelConfigId, profile.getEmbeddingModelConfigId())
			.set(DataAgentRouteProfile::getEmbeddingProbeState, profile.getEmbeddingProbeState())
			.set(DataAgentRouteProfile::getEmbeddingCheckedAt, profile.getEmbeddingCheckedAt())
			.set(DataAgentRouteProfile::getEmbeddingLatencyMs, profile.getEmbeddingLatencyMs())
			.set(DataAgentRouteProfile::getEmbeddingProbeVersion, profile.getEmbeddingProbeVersion())
			.set(DataAgentRouteProfile::getEmbeddingFailureCode, profile.getEmbeddingFailureCode())
			.set(DataAgentRouteProfile::getLexicalAutoSelectEnabled, profile.getLexicalAutoSelectEnabled())
			.set(DataAgentRouteProfile::getSemanticRecallEnabled, profile.getSemanticRecallEnabled())
			.set(DataAgentRouteProfile::getSemanticAutoSelectEnabled, profile.getSemanticAutoSelectEnabled())
			.set(DataAgentRouteProfile::getModelDisambiguationEnabled, profile.getModelDisambiguationEnabled())
			.set(DataAgentRouteProfile::getLexicalMinScore, profile.getLexicalMinScore())
			.set(DataAgentRouteProfile::getLexicalMinGap, profile.getLexicalMinGap())
			.set(DataAgentRouteProfile::getVectorRecallThreshold, profile.getVectorRecallThreshold())
			.set(DataAgentRouteProfile::getVectorAutoSelectThreshold, profile.getVectorAutoSelectThreshold())
			.set(DataAgentRouteProfile::getVectorMinGap, profile.getVectorMinGap())
			.set(DataAgentRouteProfile::getModelConfidenceThreshold, profile.getModelConfidenceThreshold())
			.set(DataAgentRouteProfile::getEmbeddingFingerprint, profile.getEmbeddingFingerprint())
			.set(DataAgentRouteProfile::getEmbeddingDimension, profile.getEmbeddingDimension())
			.set(DataAgentRouteProfile::getStrictSchemaStatus, profile.getStrictSchemaStatus())
			.set(DataAgentRouteProfile::getProbeCheckedAt, profile.getProbeCheckedAt())
			.set(DataAgentRouteProfile::getBuildStatus, profile.getBuildStatus())
			.set(DataAgentRouteProfile::getBuildTotal, profile.getBuildTotal())
			.set(DataAgentRouteProfile::getBuildReady, profile.getBuildReady())
			.set(DataAgentRouteProfile::getBuildFailed, profile.getBuildFailed())
			.set(DataAgentRouteProfile::getLastErrorCode, profile.getLastErrorCode())
			.set(DataAgentRouteProfile::getRevision, expectedRevision + 1)
			.set(DataAgentRouteProfile::getLastModifyTime, Instant.now()));
	}

}
