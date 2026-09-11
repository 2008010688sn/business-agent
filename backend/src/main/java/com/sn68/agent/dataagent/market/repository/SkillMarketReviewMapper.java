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

import com.sn68.agent.dataagent.market.entity.SkillMarketReview;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 能力市场审核记录 Mapper。
 */
@Repository
public interface SkillMarketReviewMapper extends SuperMapper<SkillMarketReview> {

	default List<SkillMarketReview> findByListingId(Long listingId) {
		return selectList(Wraps.<SkillMarketReview>lbQ()
			.eq(SkillMarketReview::getListingId, listingId)
			.orderByDesc(SkillMarketReview::getCreateTime)
			.orderByDesc(SkillMarketReview::getId));
	}

}
