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
package com.sn68.agent.dataagent.task.repository;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.task.dto.AgentTaskPageQueryReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent任务定义Mapper服务契约。用户可达查询必须显式带租户条件，租户为空失败关闭。
 */
@Repository
public interface AgentTaskDefinitionMapper extends SuperMapper<AgentTaskDefinition> {

	/**
	 * 分页查询当前租户的任务定义。
	 */
	default IPage<AgentTaskDefinition> selectPageByTenant(IPage<AgentTaskDefinition> page, String tenantId,
			AgentTaskPageQueryReq request) {
		requireTenantId(tenantId);
		AgentTaskPageQueryReq query = request == null ? new AgentTaskPageQueryReq() : request;
		LambdaQueryWrapper<AgentTaskDefinition> wrapper = new LambdaQueryWrapper<AgentTaskDefinition>()
			.eq(AgentTaskDefinition::getTenantId, tenantId.trim())
			.orderByDesc(AgentTaskDefinition::getCreateTime)
			.orderByDesc(AgentTaskDefinition::getId);
		if (query.getDigitalEmployeeId() != null) {
			wrapper.eq(AgentTaskDefinition::getDigitalEmployeeId, query.getDigitalEmployeeId());
		}
		if (StringUtils.hasText(query.getTaskType())) {
			wrapper.eq(AgentTaskDefinition::getTaskType, query.getTaskType().trim());
		}
		if (StringUtils.hasText(query.getStatus())) {
			wrapper.eq(AgentTaskDefinition::getStatus, query.getStatus().trim());
		}
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(nested -> nested.like(AgentTaskDefinition::getTaskName, keyword)
				.or()
				.like(AgentTaskDefinition::getTaskDescription, keyword));
		}
		if (StringUtils.hasText(query.getTriggerType())) {
			String triggerType = query.getTriggerType().trim();
			wrapper.apply(
					"EXISTS (SELECT 1 FROM agent_task_trigger t WHERE t.definition_id = agent_task_definition.id"
							+ " AND t.deleted = false AND t.trigger_type = {0})",
					triggerType);
		}
		return selectPage(page, wrapper);
	}

	/**
	 * 按租户 + 主键查询任务定义。
	 */
	default AgentTaskDefinition findByTenantAndId(String tenantId, Long id) {
		requireTenantId(tenantId);
		if (id == null) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentTaskDefinition>()
			.eq(AgentTaskDefinition::getTenantId, tenantId.trim())
			.eq(AgentTaskDefinition::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按员工 Release 统计仍引用该发布版本的任务定义数（Release 退役/删除拦截用）。
	 * releaseId 为主键引用、天然租户隔离，直接全局计数即可。
	 */
	default Long countByEmployeeReleaseId(Long employeeReleaseId) {
		if (employeeReleaseId == null) {
			return 0L;
		}
		return selectCount(new LambdaQueryWrapper<AgentTaskDefinition>()
			.eq(AgentTaskDefinition::getEmployeeReleaseId, employeeReleaseId));
	}

	/**
	 * PR-6 FORBID 并发槽占用（active_run_id 镜像 CAS）：仅当槽位空闲时写入本次运行ID。
	 * 手写条件更新：CAS 语义需要「无值才写」谓词，MP wrapper 的 set 链不便表达且易错。
	 *
	 * @return 1 表示占用成功；0 表示槽位已被其他运行占用（镜像不一致时以 run 表索引为准）
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("""
			UPDATE agent_task_definition
			SET active_run_id = #{runId},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{definitionId}
			  AND deleted = false
			  AND active_run_id IS NULL
			""")
	int occupySlot(@Param("definitionId") Long definitionId, @Param("runId") Long runId);

	/**
	 * PR-6 FORBID 并发槽定向释放：仅当镜像仍指向本次运行时清空，防止误释放后续竞得槽的新运行。
	 *
	 * @return 1 表示释放生效；0 表示镜像已指向其他运行或已为空
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Update("""
			UPDATE agent_task_definition
			SET active_run_id = NULL,
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{definitionId}
			  AND deleted = false
			  AND active_run_id = #{runId}
			""")
	int releaseSlot(@Param("definitionId") Long definitionId, @Param("runId") Long runId);

	/**
	 * PR-6 对账扫描：并发槽镜像非空的全部定义（TaskRunStatusSyncJob 逐个核对镜像指向的运行
	 * 是否仍活跃，非活跃则定向释放，回收崩溃/异常路径残留的镜像）。跨租户扫描是有意行为。
	 */
	default List<AgentTaskDefinition> findOccupiedSlotDefinitions(int limit) {
		return selectList(Wraps.<AgentTaskDefinition>lbQ()
			.isNotNull(AgentTaskDefinition::getActiveRunId)
			.orderByAsc(AgentTaskDefinition::getId)
			.last(" limit " + Math.max(1, limit)));
	}

	private static void requireTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问任务定义");
		}
	}

}
