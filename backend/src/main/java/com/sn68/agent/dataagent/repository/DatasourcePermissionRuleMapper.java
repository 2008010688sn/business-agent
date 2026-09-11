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
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 数据源PermissionRuleMapper服务契约。
 */
@Repository
public interface DatasourcePermissionRuleMapper extends SuperMapper<DatasourcePermissionRule> {

	/**
	 * 查询数据源全部数据权限规则（含停用），按表名、列名升序。
	 */
	default List<DatasourcePermissionRule> selectByDatasourceId(Long datasourceId) {
		return selectList(Wraps.<DatasourcePermissionRule>lbQ()
			.eq(DatasourcePermissionRule::getDatasourceId, datasourceId)
			.orderByAsc(DatasourcePermissionRule::getTableName, DatasourcePermissionRule::getColumnName));
	}

	/**
	 * 查询数据源已启用（enabled=true）的数据权限规则，按表名、列名升序（SQL 改写时的生效规则集）。
	 */
	default List<DatasourcePermissionRule> selectEnabledByDatasourceId(Long datasourceId) {
		return selectList(new LambdaQueryWrapper<DatasourcePermissionRule>()
			.eq(DatasourcePermissionRule::getDatasourceId, datasourceId)
			.eq(DatasourcePermissionRule::getEnabled, true)
			.orderByAsc(DatasourcePermissionRule::getTableName, DatasourcePermissionRule::getColumnName));
	}

	/**
	 * 按数据源 ID 逻辑删除其全部数据权限规则（保存规则前的全量重建清理）。
	 *
	 * <p>{@code Wraps} 跳过空值，datasourceId 为空会退化成全表删除；唯一调用方
	 * {@code DatasourceServiceImpl#savePermissionRules} 先经 {@code requireDatasource} 失败关闭。
	 */
	default int deleteByDatasourceId(Long datasourceId) {
		return delete(Wraps.<DatasourcePermissionRule>lbQ()
			.eq(DatasourcePermissionRule::getDatasourceId, datasourceId));
	}

}
