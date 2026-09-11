/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentFlowEvent;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * FLOW event mapper.
 */
@Repository
public interface DataAgentFlowEventMapper extends SuperMapper<DataAgentFlowEvent> {

	/**
	 * 查询流程实例的全部节点事件，按创建时间升序（用于执行轨迹回放）；逻辑删除自动过滤。
	 */
	default List<DataAgentFlowEvent> findByInstanceId(Long flowInstanceId) {
		return selectList(Wraps.<DataAgentFlowEvent>lbQ()
			.eq(DataAgentFlowEvent::getFlowInstanceId, flowInstanceId)
			.orderByAsc(DataAgentFlowEvent::getCreateTime));
	}

}
