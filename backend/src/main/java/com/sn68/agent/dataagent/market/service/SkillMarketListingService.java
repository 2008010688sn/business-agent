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
package com.sn68.agent.dataagent.market.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingCreateReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingDetailResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingModifyReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingPageQueryReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketReviewReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketSubmitReviewReq;

/**
 * 能力市场条目服务：条目维护 + 平台审核流（DRAFT → REVIEWING → APPROVED/REJECTED → REVOKED）。
 */
public interface SkillMarketListingService {

	/**
	 * 分页查询市场条目。
	 */
	IPage<SkillMarketListingResp> pageListings(SkillMarketListingPageQueryReq request);

	/**
	 * 创建市场条目，初始状态 DRAFT。
	 */
	Long createListing(SkillMarketListingCreateReq request);

	/**
	 * 修改市场条目，仅 DRAFT/REJECTED 可修改。
	 */
	void modifyListing(Long id, SkillMarketListingModifyReq request);

	/**
	 * 查询条目详情（含版本历史与审核历史）。
	 */
	SkillMarketListingDetailResp getListingDetail(Long id);

	/**
	 * 提交平台审核：快照引用的 Skill 版本为不可变条目版本，DRAFT/REJECTED → REVIEWING。
	 */
	void submitReview(Long id, SkillMarketSubmitReviewReq request);

	/**
	 * 平台审核：REVIEWING → APPROVED/REJECTED，落审核记录。由控制器权限码拦截。
	 */
	void review(Long id, SkillMarketReviewReq request);

	/**
	 * 撤销已通过的条目：APPROVED → REVOKED（终态）。由控制器权限码拦截。
	 */
	void revoke(Long id);

	/**
	 * 删除条目，仅 DRAFT/REJECTED 可删除（逻辑删除）。
	 */
	void deleteListing(Long id);

	/**
	 * 分页查询租户可使用的已审核且未撤销市场技能（可见范围由服务统一收敛）。
	 * - 用于数字员工能力标签页左侧「可安装技能」列表。
	 */
	IPage<SkillMarketListingResp> pageAvailableSkillsForEmployee(SkillMarketListingPageQueryReq request);

}
