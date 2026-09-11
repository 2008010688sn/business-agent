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
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Data Source Mapper Interface
 *
 * @author Alibaba Cloud AI
 */
@Repository
public interface DatasourceMapper extends SuperMapper<Datasource> {

	/**
	 * 查询租户下全部数据源，按创建时间倒序。
	 *
	 * <p>{@code Wraps} 会跳过空值，租户谓词一旦被跳过就是跨租户全表扫描；调用方
	 * {@code DatasourceServiceImpl#getAllDatasourceForTenant} 已对空租户失败关闭，新增调用方必须同样保证 tenantId 非空。
	 */
	default List<Datasource> selectByTenantId(String tenantId) {
		return selectList(Wraps.<Datasource>lbQ().eq(Datasource::getTenantId, tenantId)
			.orderByDesc(Datasource::getCreateTime));
	}

	/**
	 * System-only query used by the sensitive-config migration before tenant context exists.
	 */
	default List<Datasource> selectAll() {
		return selectList(Wraps.<Datasource>lbQ().orderByDesc(Datasource::getCreateTime));
	}

	/**
	 * 同 {@link #selectByTenantId(String)}：调用方
	 * {@code DatasourceServiceImpl#requireDatasourceForTenant} 已对空 id 与空租户失败关闭。
	 */
	default Datasource selectByIdAndTenantId(Long id, String tenantId) {
		return selectOne(Wraps.<Datasource>lbQ().eq(Datasource::getId, id)
			.eq(Datasource::getTenantId, tenantId));
	}

	/**
	 * 按主键刷新数据源连接测试状态。
	 */
	default int updateTestStatusById(Long id, String testStatus) {
		return update(null, Wraps.<Datasource>lbU().eq(Datasource::getId, id)
			.set(Datasource::getTestStatus, testStatus)
			.set(Datasource::getLastModifyTime, Instant.now()));
	}

	/**
	 * Query data source list by status
	 */
	default List<Datasource> selectByStatusAndTenantId(String status, String tenantId) {
		return selectList(new LambdaQueryWrapper<Datasource>().eq(Datasource::getTenantId, tenantId)
			.eq(Datasource::getStatus, status)
			.orderByDesc(Datasource::getCreateTime));
	}

	/**
	 * Query data source list by type
	 */
	default List<Datasource> selectByTypeAndTenantId(String type, String tenantId) {
		return selectList(new LambdaQueryWrapper<Datasource>().eq(Datasource::getTenantId, tenantId)
			.eq(Datasource::getType, type)
			.orderByDesc(Datasource::getCreateTime));
	}

}
