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

import com.sn68.agent.dataagent.market.entity.SkillMarketListingVersion;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 能力市场条目版本 Mapper（版本不可变，只增不改）。
 */
@Repository
public interface SkillMarketListingVersionMapper extends SuperMapper<SkillMarketListingVersion> {

	default List<SkillMarketListingVersion> findByListingId(Long listingId) {
		return selectList(Wraps.<SkillMarketListingVersion>lbQ()
			.eq(SkillMarketListingVersion::getListingId, listingId)
			.orderByDesc(SkillMarketListingVersion::getVersionNo));
	}

	default SkillMarketListingVersion findLatestByListingId(Long listingId) {
		return selectOne(Wraps.<SkillMarketListingVersion>lbQ()
			.eq(SkillMarketListingVersion::getListingId, listingId)
			.orderByDesc(SkillMarketListingVersion::getVersionNo)
			.last(" limit 1"));
	}

	default int nextVersionNo(Long listingId) {
		SkillMarketListingVersion latest = findLatestByListingId(listingId);
		return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
	}

	/**
	 * 根据 listingId 和 versionNo 查询版本记录。
	 */
	default SkillMarketListingVersion findByListingIdAndVersionNo(Long listingId, Integer versionNo) {
		return selectOne(Wraps.<SkillMarketListingVersion>lbQ()
			.eq(SkillMarketListingVersion::getListingId, listingId)
			.eq(SkillMarketListingVersion::getVersionNo, versionNo));
	}

}
