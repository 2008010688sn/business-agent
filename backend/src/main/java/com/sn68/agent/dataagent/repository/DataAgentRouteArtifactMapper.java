/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentRouteArtifact;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 路由候选产物（route artifact）Mapper：管理按 profile + 租户 + 目标 key 维度的路由向量产物。
 *
 * <p>tenantId 由调用方显式传入做租户隔离；逻辑删除由 {@code @TableLogic} 自动过滤。
 */
@Repository
public interface DataAgentRouteArtifactMapper extends SuperMapper<DataAgentRouteArtifact> {

	/**
	 * 查询可复用的 READY 产物：源内容 checksum 与 embedding 指纹都未变化时命中，避免重复构建。
	 */
	default DataAgentRouteArtifact findReusable(Long profileId, String tenantId, String targetKey,
			String sourceChecksum, String embeddingFingerprint) {
		return selectOne(Wraps.<DataAgentRouteArtifact>lbQ()
			.eq(DataAgentRouteArtifact::getProfileId, profileId)
			.eq(DataAgentRouteArtifact::getTenantId, tenantId)
			.eq(DataAgentRouteArtifact::getTargetKey, targetKey)
			.eq(DataAgentRouteArtifact::getSourceChecksum, sourceChecksum)
			.eq(DataAgentRouteArtifact::getEmbeddingFingerprint, embeddingFingerprint)
			.eq(DataAgentRouteArtifact::getStatus, "READY")
			.last("LIMIT 1"));
	}

	/**
	 * 查询目标 key 在指定源 checksum 下的最新一条产物（不限状态，按 ID 倒序取一条）。
	 */
	default DataAgentRouteArtifact findCurrent(Long profileId, String tenantId, String targetKey,
			String sourceChecksum) {
		return selectOne(Wraps.<DataAgentRouteArtifact>lbQ()
			.eq(DataAgentRouteArtifact::getProfileId, profileId)
			.eq(DataAgentRouteArtifact::getTenantId, tenantId)
			.eq(DataAgentRouteArtifact::getTargetKey, targetKey)
			.eq(DataAgentRouteArtifact::getSourceChecksum, sourceChecksum)
			.orderByDesc(DataAgentRouteArtifact::getId)
			.last("LIMIT 1"));
	}

	/**
	 * 批量查询目标 key 集合中状态为 READY 的产物；targetKeys 为空时直接返回空集，避免全表扫描。
	 */
	default List<DataAgentRouteArtifact> findReady(Long profileId, String tenantId, Collection<String> targetKeys) {
		if (targetKeys == null || targetKeys.isEmpty()) {
			return List.of();
		}
		return selectList(Wraps.<DataAgentRouteArtifact>lbQ()
			.eq(DataAgentRouteArtifact::getProfileId, profileId)
			.eq(DataAgentRouteArtifact::getTenantId, tenantId)
			.in(DataAgentRouteArtifact::getTargetKey, targetKeys)
			.eq(DataAgentRouteArtifact::getStatus, "READY")
			.orderByAsc(DataAgentRouteArtifact::getId));
	}

	/**
	 * 查询 profile 下全部产物（跨租户，供重建/巡检任务使用），按 ID 升序。
	 */
	default List<DataAgentRouteArtifact> findByProfile(Long profileId) {
		return selectList(Wraps.<DataAgentRouteArtifact>lbQ()
			.eq(DataAgentRouteArtifact::getProfileId, profileId)
			.orderByAsc(DataAgentRouteArtifact::getId));
	}

	/**
	 * 查询单个目标 key 的全部产物（不限状态），按 ID 升序。
	 */
	default List<DataAgentRouteArtifact> findByTarget(Long profileId, String tenantId, String targetKey) {
		return selectList(Wraps.<DataAgentRouteArtifact>lbQ()
			.eq(DataAgentRouteArtifact::getProfileId, profileId)
			.eq(DataAgentRouteArtifact::getTenantId, tenantId)
			.eq(DataAgentRouteArtifact::getTargetKey, targetKey)
			.orderByAsc(DataAgentRouteArtifact::getId));
	}

	/**
	 * 统计 profile 下 READY 产物条数（跨租户，用于构建进度上报）。
	 */
	default long countReady(Long profileId) {
		return selectCount(Wraps.<DataAgentRouteArtifact>lbQ()
			.eq(DataAgentRouteArtifact::getProfileId, profileId)
			.eq(DataAgentRouteArtifact::getStatus, "READY"));
	}

	/**
	 * 将 profile 下全部产物批量置为 FAILED 并记录失败码（embedding 模型/指纹变更后整体作废）。
	 */
	default int invalidateEmbeddingArtifacts(Long profileId, String failureCode) {
		return update(null, Wraps.<DataAgentRouteArtifact>lbU()
			.eq(DataAgentRouteArtifact::getProfileId, profileId)
			.set(DataAgentRouteArtifact::getStatus, "FAILED")
			.set(DataAgentRouteArtifact::getFailureCode, failureCode)
			.set(DataAgentRouteArtifact::getLastModifyTime, Instant.now()));
	}

}
