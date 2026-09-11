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
package com.sn68.agent.dataagent.market.service.impl;

import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeCapabilityMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.market.dto.MarketInstallPrepareResp;
import com.sn68.agent.dataagent.market.entity.SkillMarketListing;
import com.sn68.agent.dataagent.market.entity.SkillMarketListingVersion;
import com.sn68.agent.dataagent.market.enums.MarketListingStatus;
import com.sn68.agent.dataagent.market.repository.SkillMarketListingMapper;
import com.sn68.agent.dataagent.market.repository.SkillMarketListingVersionMapper;
import com.sn68.agent.dataagent.market.service.MarketInstallService;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.skill.SkillVersionStatus;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 能力市场安装服务实现（PR-5：安装目标重建为数字员工 Capability 绑定）。
 *
 * <p>安装链路：条目审核通过（APPROVED）且未撤销 → 当前通过版本存在 →
 * 幂等写入 digital_employee_capability（同 skillVersionId 重复安装恢复启用既有绑定）。
 * 能力绑定是草稿态事实：Seal 时冻结进员工 Release 快照，运行时不读本表（主文档 4.2 / 坑 18），
 * 因此不拦截 ENABLED 员工安装（只改草稿，不影响在跑部署）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketInstallServiceImpl implements MarketInstallService {

	private final SkillMarketListingMapper listingMapper;

	private final SkillMarketListingVersionMapper listingVersionMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeCapabilityMapper capabilityMapper;

	private final AuthenticationContext authenticationContext;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public MarketInstallPrepareResp install(Long listingId, Integer expectedVersionNo, Long digitalEmployeeId) {
		if (listingId == null || expectedVersionNo == null || digitalEmployeeId == null) {
			throw CheckedException.badRequest("市场条目ID、版本号与数字员工ID均不能为空");
		}
		String tenantId = currentTenantId();
		requireInstallableEmployee(digitalEmployeeId, tenantId);
		SkillMarketListing listing = requireApprovedListing(listingId, tenantId);
		SkillMarketListingVersion version = requireCurrentVersion(listing, expectedVersionNo);
		requireInstallableSkillVersion(version.getSkillVersionId(), tenantId);

		capabilityMapper.upsertEnabled(tenantId, digitalEmployeeId, version.getSkillVersionId());
		DigitalEmployeeCapability capability = capabilityMapper.findByEmployeeAndSkillVersion(digitalEmployeeId,
				version.getSkillVersionId(), tenantId);
		if (capability == null) {
			throw CheckedException.fail("能力绑定写入后无法读取安装记录");
		}
		Long capabilityId = capability.getId();
		log.info("市场能力已安装为数字员工能力绑定. listingId={}, employeeId={}, capabilityId={}, skillVersionId={}",
				listingId, digitalEmployeeId, capabilityId, version.getSkillVersionId());
		return MarketInstallPrepareResp.builder()
			.installationId(capabilityId)
			.listingId(listing.getId())
			.listingVersionId(version.getId())
			.versionNo(version.getVersionNo())
			.skillVersionId(version.getSkillVersionId())
			.contentHash(version.getContentHash())
			.defaultUsageQuota(listing.getDefaultUsageQuota())
			.build();
	}

	private DigitalEmployee requireInstallableEmployee(Long digitalEmployeeId, String tenantId) {
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(digitalEmployeeId, tenantId);
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + digitalEmployeeId);
		}
		if (EmployeeStatusDict.ARCHIVED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("已封存的数字员工不可安装能力");
		}
		return employee;
	}

	private SkillMarketListing requireApprovedListing(Long listingId, String tenantId) {
		SkillMarketListing listing = listingMapper.findByIdAndPublisherTenant(listingId, tenantId);
		if (listing == null || Boolean.TRUE.equals(listing.getDeleted())) {
			throw CheckedException.notFound("市场条目不存在: " + listingId);
		}
		if (Boolean.TRUE.equals(listing.getRevoked())) {
			throw CheckedException.badRequest("市场条目已被撤销，不可安装. listingId=" + listingId);
		}
		if (listing.getReviewStatus() != MarketListingStatus.APPROVED) {
			throw CheckedException.badRequest("仅审核通过（APPROVED）的市场条目可安装，当前状态: "
					+ (listing.getReviewStatus() == null ? "null" : listing.getReviewStatus().name()));
		}
		if (listing.getCurrentVersionId() == null) {
			throw CheckedException.badRequest("市场条目没有可安装的当前通过版本. listingId=" + listingId);
		}
		return listing;
	}

	private SkillMarketListingVersion requireCurrentVersion(SkillMarketListing listing, Integer expectedVersionNo) {
		SkillMarketListingVersion version = listingVersionMapper.selectById(listing.getCurrentVersionId());
		if (version == null || Boolean.TRUE.equals(version.getDeleted())) {
			throw CheckedException.notFound("市场条目当前版本不存在: " + listing.getCurrentVersionId());
		}
		if (!Objects.equals(version.getListingId(), listing.getId())) {
			throw CheckedException.badRequest("市场条目当前版本与条目不匹配，不能安装: " + version.getId());
		}
		if (!Objects.equals(version.getVersionNo(), expectedVersionNo)) {
			throw CheckedException.badRequest("市场条目版本已变化，请刷新后重试. expectedVersionNo="
					+ expectedVersionNo + ", currentVersionNo=" + version.getVersionNo());
		}
		if (version.getSkillVersionId() == null) {
			throw CheckedException.badRequest("市场条目当前版本未引用有效 Skill 版本. versionId=" + version.getId());
		}
		return version;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public MarketInstallPrepareResp install(Long listingId, Long digitalEmployeeId) {
		if (listingId == null || digitalEmployeeId == null) {
			throw CheckedException.badRequest("市场条目ID与数字员工ID均不能为空");
		}
		String tenantId = currentTenantId();
		SkillMarketListing listing = listingMapper.findByIdAndPublisherTenant(listingId, tenantId);
		if (listing == null || listing.getCurrentVersionId() == null) {
			return install(listingId, null, digitalEmployeeId);
		}
		SkillMarketListingVersion currentVersion = listingVersionMapper.selectById(listing.getCurrentVersionId());
		return install(listingId, currentVersion == null ? null : currentVersion.getVersionNo(), digitalEmployeeId);
	}

	private DataAgentSkillVersion requireInstallableSkillVersion(Long skillVersionId, String tenantId) {
		DataAgentSkillVersion skillVersion = skillVersionMapper.selectById(skillVersionId);
		if (skillVersion == null || Boolean.TRUE.equals(skillVersion.getDeleted())) {
			throw CheckedException.notFound("来源Skill版本不存在: " + skillVersionId);
		}
		String versionTenantId = skillVersion.getTenantId();
		if (!StringUtils.hasText(versionTenantId) || !tenantId.equals(versionTenantId.trim())) {
			throw CheckedException.notFound("来源Skill版本不存在: " + skillVersionId);
		}
		if (!SkillVersionStatus.PUBLISHED.getValue().equals(skillVersion.getStatus())) {
			throw CheckedException.badRequest("来源Skill版本未发布，当前状态: " + skillVersion.getStatus());
		}
		if (Boolean.TRUE.equals(skillVersion.getRevoked())) {
			throw CheckedException.badRequest("来源Skill版本已被撤销，不能安装: " + skillVersionId);
		}
		return skillVersion;
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止市场能力安装");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止市场能力安装");
		}
		return tenantId.trim();
	}

}
