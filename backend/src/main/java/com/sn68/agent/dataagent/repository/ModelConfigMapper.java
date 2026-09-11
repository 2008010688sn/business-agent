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
import com.sn68.agent.dataagent.dto.ModelConfigPageQueryReq;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * 模型配置Mapper服务契约。
 */
@Repository
public interface ModelConfigMapper extends SuperMapper<ModelConfig> {

	/**
	 * 查询全部模型配置，按创建时间倒序。无租户时返回空列表。
	 */
	default List<ModelConfig> findAll() {
		return findAllByTenant(null);
	}

	default List<ModelConfig> findAllByTenant(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<ModelConfig>lbQ()
			.eq(ModelConfig::getTenantId, tenantId.trim())
			.orderByDesc(ModelConfig::getCreateTime));
	}

	/**
	 * 分页查询模型配置，支持平台/启用状态/模型类型过滤与平台、地址、模型名关键字模糊搜索。
	 */
	default IPage<ModelConfig> selectConfigPage(IPage<ModelConfig> page, ModelConfigPageQueryReq request) {
		return selectConfigPage(page, request, null);
	}

	default IPage<ModelConfig> selectConfigPage(IPage<ModelConfig> page, ModelConfigPageQueryReq request,
			String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return page;
		}
		return selectPage(page, buildQuery(request).eq(ModelConfig::getTenantId, tenantId.trim())
			.orderByDesc(ModelConfig::getCreateTime)
			.orderByDesc(ModelConfig::getId));
	}

	/**
	 * 按与分页相同的条件查询模型配置全量列表（导出/下拉场景）。
	 */
	default List<ModelConfig> selectConfigList(ModelConfigPageQueryReq request) {
		return selectList(buildQuery(request).orderByDesc(ModelConfig::getCreateTime)
			.orderByDesc(ModelConfig::getId));
	}

	/**
	 * 按主键查询模型配置；逻辑删除自动过滤。
	 */
	default ModelConfig findById(Long id) {
		return selectOne(new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getId, id));
	}

	/**
	 * 在当前事务内锁定可用的模型配置。
	 */
	default ModelConfig findByIdForUpdate(Long id) {
		if (id == null) {
			return null;
		}
		return selectOne(Wraps.<ModelConfig>lbQ()
			.eq(ModelConfig::getId, id)
			.last("for update"));
	}

	/**
	 * 查询指定类型当前激活（isActive=true）的模型配置；同类型应仅一条激活，取任意一条。
	 */
	default ModelConfig selectActiveByType(String modelType) {
		return selectActiveByType(modelType, null);
	}

	default ModelConfig selectActiveByType(String modelType, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<ModelConfig>()
			.eq(ModelConfig::getTenantId, tenantId.trim())
			.eq(ModelConfig::getModelType, ModelType.fromCode(modelType))
			.eq(ModelConfig::getIsActive, true)
			.last(" limit 1"));
	}

	/**
	 * 关停本租户同类型的其它激活配置。
	 */
	default void deactivateOthers(String modelType, Long currentId) {
		deactivateOthers(modelType, currentId, null);
	}

	default void deactivateOthers(String modelType, Long currentId, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return;
		}
		update(null, Wraps.<ModelConfig>lbU().eq(ModelConfig::getModelType, ModelType.fromCode(modelType))
			.eq(ModelConfig::getTenantId, tenantId.trim())
			.ne(ModelConfig::getId, currentId)
			.set(ModelConfig::getIsActive, false)
			.set(ModelConfig::getLastModifyTime, Instant.now()));
	}

	/** Returns the active configurations whose activation state will be changed by a switch. */
	default List<Long> findActiveIdsByTypeExcluding(String modelType, Long currentId) {
		return findActiveIdsByTypeExcluding(modelType, currentId, null);
	}

	default List<Long> findActiveIdsByTypeExcluding(String modelType, Long currentId, String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<ModelConfig>lbQ()
			.eq(ModelConfig::getTenantId, tenantId.trim())
			.eq(ModelConfig::getModelType, ModelType.fromCode(modelType))
			.eq(ModelConfig::getIsActive, true)
			.ne(ModelConfig::getId, currentId))
			.stream()
			.map(ModelConfig::getId)
			.filter(java.util.Objects::nonNull)
			.toList();
	}

	/**
	 * 组合条件查询模型配置：平台/启用状态/maxTokens/类型精确匹配 + 平台、地址、模型名关键字模糊匹配，
	 * 条件为空时自动忽略（带 boolean 重载显式控制）。
	 */
	default List<ModelConfig> findByConditions(String provider, String keyword, Boolean isActive, Long maxTokens,
			String modelType) {
		return selectList(Wraps.<ModelConfig>lbQ()
			.eq(provider != null && !provider.isBlank(), ModelConfig::getProvider, provider)
			.eq(isActive != null, ModelConfig::getIsActive, isActive)
			.eq(maxTokens != null, ModelConfig::getMaxTokens, maxTokens)
			.eq(modelType != null && !modelType.isBlank(), ModelConfig::getModelType,
					modelType != null && !modelType.isBlank() ? ModelType.fromCode(modelType) : null)
			.and(keyword != null && !keyword.isBlank(), item -> item.like(ModelConfig::getProvider, keyword)
				.or()
				.like(ModelConfig::getBaseUrl, keyword)
				.or()
				.like(ModelConfig::getModelName, keyword))
			.orderByDesc(ModelConfig::getCreateTime));
	}

	/**
	 * 组装模型配置查询条件，空条件由 Wraps 自动忽略。
	 */
	private static LbqWrapper<ModelConfig> buildQuery(ModelConfigPageQueryReq request) {
		ModelConfigPageQueryReq query = request == null ? new ModelConfigPageQueryReq() : request;
		LbqWrapper<ModelConfig> wrapper = Wraps.<ModelConfig>lbQ()
			.eq(ModelConfig::getProvider, trim(query.getProvider()))
			.eq(ModelConfig::getIsActive, query.getIsActive())
			.eq(ModelConfig::getModelType, toModelType(query.getModelType()));
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(item -> item.like(ModelConfig::getProvider, keyword)
				.or()
				.like(ModelConfig::getBaseUrl, keyword)
				.or()
				.like(ModelConfig::getModelName, keyword));
		}
		return wrapper;
	}

	/**
	 * 空白串归一化为 null，配合 Wraps 自动跳过空条件。
	 */
	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	/**
	 * 模型类型编码转枚举；空白返回 null（跳过条件），非法编码由 fromCode 抛出异常。
	 */
	private static ModelType toModelType(String value) {
		return StringUtils.hasText(value) ? ModelType.fromCode(value) : null;
	}

}
