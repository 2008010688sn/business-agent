/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import com.sn68.agent.dataagent.dto.routing.RouteProfileBuildStatusResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileCurrentResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileCreateReq;
import com.sn68.agent.dataagent.dto.routing.RouteProfileResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileModifyReq;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;

/**
 * 路由画像服务契约：路由画像的增改、能力探测、物料重建、激活与策略视图转换。
 */
public interface RouteProfileService {

	/**
	 * 查询路由画像总览：激活中、编辑中与回滚窗口内最近退役的画像视图及配置约束。
	 */
	RouteProfileCurrentResp current();

	/**
	 * 创建草稿态路由画像（revision 从 0 开始，能力探测由后续 probe/reprobe 触发）。
	 */
	RouteProfileResp create(RouteProfileCreateReq request);

	/**
	 * 基于乐观锁修改画像配置；revision 过期抛出业务异常。
	 */
	RouteProfileResp modify(Long id, RouteProfileModifyReq request);

	/**
	 * 对可编辑画像执行路由模型与 Embedding 能力探测并落库结果。
	 */
	RouteProfileResp probe(Long id);

	/**
	 * 重新探测画像能力：激活态画像走带指纹比对的复检流程，非激活态等同 probe。
	 */
	RouteProfileResp reprobe(Long id);

	/**
	 * 触发画像语义物料重建并返回构建状态。
	 */
	RouteProfileBuildStatusResp rebuild(Long id);

	/**
	 * 查询画像物料构建进度与状态。
	 */
	RouteProfileBuildStatusResp buildStatus(Long id);

	/**
	 * 激活画像（READY 或回滚窗口内的 RETIRED 可激活），同时退役原激活画像。
	 */
	RouteProfileResp activate(Long id);

	/**
	 * 取当前激活且运行时可用的画像；不存在或不可用时抛出异常。
	 */
	DataAgentRouteProfile requireActive();

	/**
	 * 校验并返回可用画像；allowDraft 为 true 时草稿/就绪态也可用于预览。
	 */
	DataAgentRouteProfile requireUsable(Long profileId, boolean allowDraft);

	/**
	 * 将画像持久化配置转换为路由引擎使用的策略视图。
	 */
	RoutePolicy toPolicy(DataAgentRouteProfile profile);
}
