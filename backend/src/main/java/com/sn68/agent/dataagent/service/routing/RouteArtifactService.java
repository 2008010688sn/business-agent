/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;

/**
 * 路由物料服务契约：负责路由画像语义检索物料（技能/协作者 Embedding 文档）的重建、预备与失效。
 */
public interface RouteArtifactService {

	/**
	 * 按指定构建版本全量重建画像的语义检索物料，构建结果回写画像的构建状态。
	 */
	void rebuildProfile(Long profileId, Long buildRevision);

	/**
	 * 为单个技能版本增量预备语义物料（向量化并写入物料表）。
	 */
	void prepareSkillVersion(DataAgentSkill skill, DataAgentSkillVersion version);

	/**
	 * 为单个协作者增量预备语义物料。
	 */
	void prepareCollaborator(AgentCollaborator collaborator);

	/**
	 * 目标 Agent 信息变化后，刷新所有指向它的协作者物料。
	 */
	void refreshCollaboratorsForTargetAgent(Long collaboratorAgentId);

	/**
	 * 使指定画像的 Embedding 物料失效（能力指纹变化后触发）。
	 */
	void invalidateEmbeddingArtifacts(Long profileId);

	/**
	 * 是否存在可用的规则路由来源（技能绑定或协作者）。
	 */
	boolean hasValidRuleSources();

	/**
	 * 画像的语义物料是否完整覆盖当前全部候选来源。
	 */
	boolean hasCompleteArtifacts(DataAgentRouteProfile profile);
}
