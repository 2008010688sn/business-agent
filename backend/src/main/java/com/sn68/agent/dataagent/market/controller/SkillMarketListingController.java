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
package com.sn68.agent.dataagent.market.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.market.SkillMarketConstant;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingCreateReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingDetailResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingModifyReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingPageQueryReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketReviewReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketSubmitReviewReq;
import com.sn68.agent.dataagent.market.service.SkillMarketListingService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力市场条目入口：条目维护、平台审核流（提交审核/审核/撤销）与市场展示。
 */
@RestController
@RequestMapping("/skill-market/listings")
@RequiredArgsConstructor
@Tag(name = "能力市场", description = "能力市场条目维护、平台审核与撤销")
public class SkillMarketListingController {

	private final SkillMarketListingService listingService;

	@Operation(summary = "分页查询能力市场条目", description = "按名称/分类/发布者/审核状态/风险等级/撤销状态分页查询市场条目。")
	@PostMapping("/page")
	public IPage<SkillMarketListingResp> page(@RequestBody(required = false) SkillMarketListingPageQueryReq request) {
		return listingService.pageListings(request);
	}

	@Operation(summary = "创建能力市场条目", description = "创建市场条目草稿（DRAFT），后续提交平台审核。")
	@AccessLog(module = "能力市场", description = "创建市场条目")
	@PostMapping("/create")
	public void create(@Valid @RequestBody SkillMarketListingCreateReq request) {
		listingService.createListing(request);
	}

	@Operation(summary = "修改能力市场条目", description = "仅 DRAFT/REJECTED 状态可修改。")
	@AccessLog(module = "能力市场", description = "修改市场条目")
	@PutMapping("/{id}/modify")
	public void modify(@PathVariable Long id, @Valid @RequestBody SkillMarketListingModifyReq request) {
		listingService.modifyListing(id, request);
	}

	@Operation(summary = "查询能力市场条目详情", description = "返回条目信息、版本历史与审核历史。")
	@GetMapping("/{id}/detail")
	public SkillMarketListingDetailResp detail(@PathVariable Long id) {
		return listingService.getListingDetail(id);
	}

	@Operation(summary = "提交能力市场条目审核", description = "快照引用的已发布 Skill 版本为不可变条目版本，进入 REVIEWING。")
	@AccessLog(module = "能力市场", description = "提交市场条目审核")
	@PostMapping("/{id}/submit-review")
	public void submitReview(@PathVariable Long id, @Valid @RequestBody SkillMarketSubmitReviewReq request) {
		listingService.submitReview(id, request);
	}

	@Operation(summary = "平台审核能力市场条目", description = "平台管理员审核：通过（APPROVED）或驳回（REJECTED），落审核记录。")
	@AccessLog(module = "能力市场", description = "平台审核市场条目")
	@PostMapping("/{id}/review")
	public void review(@PathVariable Long id, @Valid @RequestBody SkillMarketReviewReq request) {
		listingService.review(id, request);
	}

	@Operation(summary = "撤销能力市场条目", description = "平台管理员撤销已通过条目（终态），已安装项级联处置见安装服务。")
	@AccessLog(module = "能力市场", description = "撤销市场条目")
	@PostMapping("/{id}/revoke")
	public void revoke(@PathVariable Long id) {
		listingService.revoke(id);
	}

	@Operation(summary = "删除能力市场条目", description = "仅 DRAFT/REJECTED 状态可删除（逻辑删除）。")
	@AccessLog(module = "能力市场", description = "删除市场条目")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		listingService.deleteListing(id);
	}

	@Operation(summary = "查询可用市场技能列表", description = "分页查询已审核且未撤销的市场技能；用于数字员工能力标签页左侧「可安装技能」列表。")
	@PostMapping("/available-for-employee")
	public IPage<SkillMarketListingResp> listAvailableSkills(@RequestBody(required = false) SkillMarketListingPageQueryReq request) {
		return listingService.pageAvailableSkillsForEmployee(request);
	}

}
