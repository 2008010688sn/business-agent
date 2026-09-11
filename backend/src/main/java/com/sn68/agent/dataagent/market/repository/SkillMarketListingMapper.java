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
package com.sn68.agent.dataagent.market.repository;

import com.sn68.agent.dataagent.market.entity.SkillMarketListing;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 能力市场条目 Mapper。
 *
 * <p>条目无 {@code tenant_id} 列，发布者隔离列是 {@code publisher_tenant_id}。
 * 读、写、安装都按发布者租户收敛，走 {@link #findByIdAndPublisherTenant} 或等价谓词。
 */
@Repository
public interface SkillMarketListingMapper extends SuperMapper<SkillMarketListing> {

	/**
	 * 按主键 + 发布者租户查询市场条目。读/写/安装的租户防线；跨租户按不存在处理。
	 */
	default SkillMarketListing findByIdAndPublisherTenant(Long id, String publisherTenantId) {
		if (id == null) {
			return null;
		}
		return selectOne(tenantScoped(Wraps.lbQ(), publisherTenantId)
			.eq(SkillMarketListing::getId, id)
			.last(" limit 1"));
	}

	private static LbqWrapper<SkillMarketListing> tenantScoped(LbqWrapper<SkillMarketListing> wrapper,
			String publisherTenantId) {
		if (!StringUtils.hasText(publisherTenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问市场条目");
		}
		return wrapper.eq(SkillMarketListing::getPublisherTenantId, publisherTenantId.trim());
	}

}
