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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.market.SkillMarketConstant;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingCreateReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingDetailResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingModifyReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingPageQueryReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingVersionResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketReviewReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketReviewResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketSubmitReviewReq;
import com.sn68.agent.dataagent.market.entity.SkillMarketListing;
import com.sn68.agent.dataagent.market.entity.SkillMarketListingVersion;
import com.sn68.agent.dataagent.market.entity.SkillMarketReview;
import com.sn68.agent.dataagent.market.enums.MarketListingStatus;
import com.sn68.agent.dataagent.market.enums.MarketReviewConclusion;
import com.sn68.agent.dataagent.market.enums.MarketRiskLevel;
import com.sn68.agent.dataagent.market.repository.SkillMarketListingMapper;
import com.sn68.agent.dataagent.market.repository.SkillMarketListingVersionMapper;
import com.sn68.agent.dataagent.market.repository.SkillMarketReviewMapper;
import com.sn68.agent.dataagent.market.service.SkillMarketListingService;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.dataagent.skill.SkillVersionStatus;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 能力市场条目服务实现。状态机校验统一走 {@link MarketListingStatus#requireTransitionTo}，
 * 非法流转抛 CheckedException；发布者侧动作（修改/删除/提交审核）校验条目归属租户，
 * 列表/详情只看当前登录租户发布的条目，审核与撤销由控制器权限码拦截。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillMarketListingServiceImpl extends SuperServiceImpl<SkillMarketListingMapper, SkillMarketListing>
		implements SkillMarketListingService {

	/**
	 * HTTP 403。
	 * <p>
	 * 跨租户越权操作市场条目走带 code 的通用工厂 {@code CheckedException.badRequest(int, String)}，
	 * 传本常量即「403 + 自定义提示文案」，提示需点明是条目归属问题而非登录问题，
	 * 与 {@link PlatformScopePermissionService} 的处理保持一致。
	 */
	private static final int HTTP_FORBIDDEN = 403;

	private static final String ACTION_QUERY = "查询市场条目";

	private static final String ACTION_DETAIL = "查看市场条目详情";

	private static final String ACTION_CREATE = "创建市场条目";

	private static final String ACTION_MODIFY = "修改市场条目";

	private static final String ACTION_SUBMIT_REVIEW = "提交市场条目审核";

	private static final String ACTION_DELETE = "删除市场条目";

	private final SkillMarketListingVersionMapper listingVersionMapper;

	private final SkillMarketReviewMapper reviewMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final DigitalEmployeeReleaseMapper digitalEmployeeReleaseMapper;

	private final PlatformScopePermissionService platformScopePermissionService;

	private final AuthenticationContext authenticationContext;

	@Override
	public IPage<SkillMarketListingResp> pageListings(SkillMarketListingPageQueryReq request) {
		SkillMarketListingPageQueryReq query = request == null ? new SkillMarketListingPageQueryReq() : request;
		LbqWrapper<SkillMarketListing> wrapper = Wraps.<SkillMarketListing>lbQ()
			.like(SkillMarketListing::getListingName, query.getListingName())
			.eq(SkillMarketListing::getCategory, query.getCategory())
			.like(SkillMarketListing::getPublisherName, query.getPublisherName())
			.eq(SkillMarketListing::getReviewStatus, query.getReviewStatus())
			.eq(SkillMarketListing::getRiskLevel, query.getRiskLevel())
			.eq(SkillMarketListing::getRevoked, query.getRevoked());
		applyVisibleScope(wrapper, ACTION_QUERY);
		IPage<SkillMarketListing> page = baseMapper.selectPage(query.buildPage(),
				wrapper.orderByDesc(SkillMarketListing::getCreateTime).orderByDesc(SkillMarketListing::getId));
		Map<Long, CurrentVersionInfo> versionInfoMap = currentVersionInfoMap(page.getRecords());
		Page<SkillMarketListingResp> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
		result.setRecords(page.getRecords()
			.stream()
			.map(listing -> toListingDTO(listing, versionInfo(versionInfoMap, listing.getCurrentVersionId())))
			.toList());
		return result;
	}

	@Override
	public IPage<SkillMarketListingResp> pageAvailableSkillsForEmployee(SkillMarketListingPageQueryReq request) {
		// 1. 固定过滤：仅 APPROVED（已通过平台审核）且未撤销
		if (request == null) {
			request = new SkillMarketListingPageQueryReq();
		}
		request.setReviewStatus(MarketListingStatus.APPROVED);
		request.setRevoked(false);

		// 2. 复用现有分页查询，但只返回租户可见范围
		return pageListings(request);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Long createListing(SkillMarketListingCreateReq request) {
		if (request == null) {
			throw CheckedException.badRequest("市场条目请求不能为空");
		}
		Instant now = Instant.now();
		SkillMarketListing listing = SkillMarketListing.builder()
			.listingName(request.listingName().trim())
			.description(request.description())
			.category(request.category())
			.publisherId(safeUserId())
			.publisherName(safeNickName())
			// 归属租户是后续所有写操作的判定依据，缺失即失败关闭，不允许落无主条目
			.publisherTenantId(requireTenantId(ACTION_CREATE))
			.ioSchemaSummary(request.ioSchemaSummary())
			.permissionScope(request.permissionScope())
			.dataScope(request.dataScope())
			.riskLevel(request.riskLevel() == null ? MarketRiskLevel.LOW : request.riskLevel())
			.dependentResources(request.dependentResources())
			.defaultUsageQuota(normalizeQuota(request.defaultUsageQuota()))
			.compatibleEngineVersion(request.compatibleEngineVersion())
			.revoked(false)
			.reviewStatus(MarketListingStatus.DRAFT)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		baseMapper.insert(listing);
		log.info("能力市场条目创建. listingId={}, name={}, publisherTenantId={}", listing.getId(),
				listing.getListingName(), listing.getPublisherTenantId());
		return listing.getId();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void modifyListing(Long id, SkillMarketListingModifyReq request) {
		if (request == null) {
			throw CheckedException.badRequest("市场条目请求不能为空");
		}
		SkillMarketListing listing = requireOwnedListing(id, ACTION_MODIFY);
		if (!listing.getReviewStatus().modifiable()) {
			throw CheckedException.badRequest("仅草稿或已驳回状态的市场条目可修改，当前状态: "
					+ listing.getReviewStatus().getLabel());
		}
		listing.setListingName(request.listingName().trim());
		listing.setDescription(request.description());
		listing.setCategory(request.category());
		listing.setIoSchemaSummary(request.ioSchemaSummary());
		listing.setPermissionScope(request.permissionScope());
		listing.setDataScope(request.dataScope());
		listing.setRiskLevel(request.riskLevel() == null ? listing.getRiskLevel() : request.riskLevel());
		listing.setDependentResources(request.dependentResources());
		listing.setDefaultUsageQuota(normalizeQuota(request.defaultUsageQuota()));
		listing.setCompatibleEngineVersion(request.compatibleEngineVersion());
		listing.setLastModifyTime(Instant.now());
		baseMapper.updateById(listing);
	}

	@Override
	public SkillMarketListingDetailResp getListingDetail(Long id) {
		SkillMarketListing listing = requireVisibleListing(id);
		List<SkillMarketListingVersion> versions = listingVersionMapper.findByListingId(id);
		CurrentVersionInfo currentVersionInfo = versions.stream()
			.filter(version -> Objects.equals(version.getId(), listing.getCurrentVersionId()))
			.map(version -> new CurrentVersionInfo(version.getVersionNo(), version.getSkillVersionId()))
			.findFirst()
			.orElse(null);
		return SkillMarketListingDetailResp.builder()
			.listing(toListingDTO(listing, currentVersionInfo))
			.versions(versions.stream().map(this::toVersionDTO).toList())
			.reviews(visibleReviews(listing))
			.build();
	}

	/**
	 * 审核历史带平台审核人与审核意见（可能是内部判断记录），只对发布者本租户与平台管理员开放；
	 * 其他租户浏览已通过条目时不返回审核记录。
	 */
	private List<SkillMarketReviewResp> visibleReviews(SkillMarketListing listing) {
		if (!isPublishedBy(listing, requireTenantId(ACTION_DETAIL))) {
			return List.of();
		}
		return reviewMapper.findByListingId(listing.getId()).stream().map(this::toReviewDTO).toList();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void submitReview(Long id, SkillMarketSubmitReviewReq request) {
		if (request == null || request.skillVersionId() == null) {
			throw CheckedException.badRequest("提交审核必须指定引用的Skill版本");
		}
		SkillMarketListing listing = requireOwnedListing(id, ACTION_SUBMIT_REVIEW);
		listing.getReviewStatus().requireTransitionTo(MarketListingStatus.REVIEWING);
		DataAgentSkillVersion skillVersion = skillVersionMapper.selectById(request.skillVersionId());
		if (skillVersion == null) {
			throw CheckedException.notFound("引用的Skill版本不存在: " + request.skillVersionId());
		}
		requireOwnedSkillVersion(skillVersion, requireTenantId(ACTION_SUBMIT_REVIEW));
		if (!SkillVersionStatus.PUBLISHED.name().equals(skillVersion.getStatus())) {
			throw CheckedException.badRequest("仅已发布的Skill版本可提交市场审核，当前状态: " + skillVersion.getStatus());
		}
		if (Boolean.TRUE.equals(skillVersion.getRevoked())) {
			throw CheckedException.badRequest("引用的Skill版本已被撤销，不能提交市场审核");
		}
		Instant now = Instant.now();
		// 不可变版本快照：记录来源 Skill 版本与内容哈希，供安装追溯与安装侧内容漂移校验
		SkillMarketListingVersion listingVersion = SkillMarketListingVersion.builder()
			.listingId(listing.getId())
			.versionNo(listingVersionMapper.nextVersionNo(listing.getId()))
			.skillVersionId(skillVersion.getId())
			.contentHash(resolveContentHash(skillVersion))
			.changeNote(request.changeNote())
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		listingVersionMapper.insert(listingVersion);
		listing.setReviewStatus(MarketListingStatus.REVIEWING);
		listing.setLastModifyTime(now);
		baseMapper.updateById(listing);
		log.info("能力市场条目提交审核. listingId={}, listingVersionId={}, versionNo={}, skillVersionId={}",
				listing.getId(), listingVersion.getId(), listingVersion.getVersionNo(), skillVersion.getId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void review(Long id, SkillMarketReviewReq request) {
		if (request == null || request.conclusion() == null) {
			throw CheckedException.badRequest("审核结论不能为空");
		}
		SkillMarketListing listing = requireVisibleListing(id);
		MarketListingStatus target = MarketReviewConclusion.APPROVED.equals(request.conclusion())
				? MarketListingStatus.APPROVED : MarketListingStatus.REJECTED;
		listing.getReviewStatus().requireTransitionTo(target);
		SkillMarketListingVersion latestVersion = listingVersionMapper.findLatestByListingId(id);
		if (latestVersion == null) {
			throw CheckedException.badRequest("市场条目缺少待审核版本，请先提交审核");
		}
		Instant now = Instant.now();
		SkillMarketReview review = SkillMarketReview.builder()
			.listingId(listing.getId())
			.listingVersionId(latestVersion.getId())
			.reviewerId(safeUserId())
			.reviewerName(safeNickName())
			.conclusion(request.conclusion())
			.opinion(request.opinion())
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		reviewMapper.insert(review);
		listing.setReviewStatus(target);
		if (MarketListingStatus.APPROVED.equals(target)) {
			// 审核通过后当前版本引用指向最新通过版本，市场展示与安装以此为准
			listing.setCurrentVersionId(latestVersion.getId());
		}
		listing.setLastModifyTime(now);
		baseMapper.updateById(listing);
		log.info("能力市场条目审核完成. listingId={}, listingVersionId={}, conclusion={}, reviewer={}",
				listing.getId(), latestVersion.getId(), request.conclusion(), safeUserId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void revoke(Long id) {
		SkillMarketListing listing = requireVisibleListing(id);
		listing.getReviewStatus().requireTransitionTo(MarketListingStatus.REVOKED);
		requireNoSealedOrPublishedEmployeeReference(listing.getId());
		listing.setReviewStatus(MarketListingStatus.REVOKED);
		listing.setRevoked(true);
		listing.setLastModifyTime(Instant.now());
		baseMapper.updateById(listing);
		log.info("能力市场条目已撤销. listingId={}, operator={}", listing.getId(), safeUserId());
	}

	/**
	 * 撤销失败关闭：SEALED/PUBLISHED 员工仍引用该 listing 技能版本时拒绝。
	 * Seal 快照不可变，不得静默级联改写已封 snapshot。
	 */
	private void requireNoSealedOrPublishedEmployeeReference(Long listingId) {
		List<SkillMarketListingVersion> versions = listingVersionMapper.findByListingId(listingId);
		if (versions == null || versions.isEmpty()) {
			return;
		}
		for (SkillMarketListingVersion version : versions) {
			if (version == null || version.getSkillVersionId() == null) {
				continue;
			}
			Long skillVersionId = version.getSkillVersionId();
			if (digitalEmployeeReleaseMapper.existsSealedOrPublishedReferencingSkillVersion(skillVersionId)) {
				throw CheckedException.badRequest(
						"存在已封版或已发布的数字员工版本仍引用该市场技能，无法撤销。"
								+ "Seal 快照不可变，请先退役或切换相关版本后再撤销。skillVersionId="
								+ skillVersionId);
			}
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void deleteListing(Long id) {
		SkillMarketListing listing = requireOwnedListing(id, ACTION_DELETE);
		// REVIEWING 需先出审核结论，APPROVED 需先撤销，REVOKED 保留审计痕迹，均不可删除
		if (!listing.getReviewStatus().modifiable()) {
			throw CheckedException.badRequest("仅草稿或已驳回状态的市场条目可删除，当前状态: "
					+ listing.getReviewStatus().getLabel());
		}
		baseMapper.deleteById(id);
	}

	/**
	 * 仅做存在性校验，不含归属判定。只允许审核/撤销入口（控制器权限码
	 * {@link SkillMarketConstant#PERMISSION_LISTING_REVIEW} 把关）与
	 * {@link #requireOwnedListing}/{@link #requireVisibleListing} 调用。
	 */
	private SkillMarketListing requireListing(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("市场条目ID不能为空");
		}
		SkillMarketListing listing = baseMapper.selectById(id);
		if (listing == null) {
			throw CheckedException.notFound("市场条目不存在: " + id);
		}
		return listing;
	}

	/**
	 * 发布者侧写操作（修改/删除/提交审核）的统一入口：条目必须由当前租户发布，否则 403。
	 * <p>
	 * 这里不给平台管理员放行：审核与撤销是平台侧动作，已各有独立入口；平台管理员若能直接改写
	 * 他人条目内容，审核留痕与发布者提交内容就对不上了。
	 */
	private SkillMarketListing requireOwnedListing(Long id, String action) {
		String tenantId = requireTenantId(action);
		if (id == null) {
			throw CheckedException.badRequest("市场条目ID不能为空");
		}
		SkillMarketListing listing = baseMapper.findByIdAndPublisherTenant(id, tenantId);
		if (listing != null) {
			return listing;
		}
		listing = requireListing(id);
		log.warn("跨租户操作市场条目被拒绝. action={}, listingId={}, publisherTenantId={}, currentTenantId={}",
				action, id, listing.getPublisherTenantId(), tenantId);
		throw CheckedException.badRequest(HTTP_FORBIDDEN, "无权" + action + "，该条目由其他租户发布: " + id);
	}

	/**
	 * 读取可见范围：只看当前登录租户发布的条目。平台管理员也跟当前租户走，
	 * 审核/撤销另有按 ID 的入口。不可见按「不存在」返回，避免详情变成他人条目的存在性探测器。
	 */
	private SkillMarketListing requireVisibleListing(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("市场条目ID不能为空");
		}
		String tenantId = requireTenantId(ACTION_DETAIL);
		SkillMarketListing listing = baseMapper.findByIdAndPublisherTenant(id, tenantId);
		if (listing != null) {
			return listing;
		}
		log.warn("跨租户查看市场条目被拒绝. listingId={}, currentTenantId={}", id, tenantId);
		throw CheckedException.notFound("市场条目不存在或无权访问: " + id);
	}

	/**
	 * 分页可见范围：只看当前登录租户发布的条目。平台管理员切租户后列表跟着变，
	 * 不再把已审核条目当成全平台目录。
	 */
	private void applyVisibleScope(LbqWrapper<SkillMarketListing> wrapper, String action) {
		wrapper.eq(SkillMarketListing::getPublisherTenantId, requireTenantId(action));
	}

	/**
	 * 归属判定。发布者租户为空的历史数据无法证明归属，一律按「不属于当前租户」处理（失败关闭），
	 * 否则租户上下文同样为空的调用方会与这些无主条目"匹配"上。
	 */
	private boolean isPublishedBy(SkillMarketListing listing, String tenantId) {
		String publisherTenantId = listing.getPublisherTenantId();
		return StringUtils.hasText(publisherTenantId) && publisherTenantId.trim().equals(tenantId);
	}

	/**
	 * 引用校验：只能把本租户自己的 Skill 版本挂到市场条目上提交审核。
	 * <p>
	 * 缺这道校验时，遍历 skillVersionId 就能把他人私有 Skill（提示词、路由规则、流程定义、IO Schema）
	 * 挂到自己的条目上过审，再经安装分发出去。不再把 tenant_id 为空或租户 1 当作平台共享。
	 */
	private void requireOwnedSkillVersion(DataAgentSkillVersion skillVersion, String tenantId) {
		String versionTenantId = skillVersion.getTenantId();
		if (StringUtils.hasText(versionTenantId) && tenantId.equals(versionTenantId.trim())) {
			return;
		}
		log.warn("跨租户引用Skill版本提交市场审核被拒绝. skillVersionId={}, skillVersionTenantId={}, currentTenantId={}",
				skillVersion.getId(), versionTenantId, tenantId);
		throw CheckedException.badRequest(HTTP_FORBIDDEN,
				"无权引用其他租户的Skill版本提交市场审核: " + skillVersion.getId());
	}

	/**
	 * 当前租户，缺失即拒绝：条目归属与可见范围都以租户为准，无租户上下文时不能退化成全平台可见可改。
	 */
	private String requireTenantId(String action) {
		String tenantId = platformScopePermissionService.currentTenantId();
		if (!StringUtils.hasText(tenantId)) {
			log.warn("当前租户上下文不可用, 已拒绝{}", action);
			throw CheckedException.badRequest(HTTP_FORBIDDEN, "当前登录信息缺少租户上下文，无法" + action);
		}
		return tenantId;
	}

	/**
	 * 内容哈希：优先复用 Skill 版本发布时生成的 checksum，缺失时对核心内容字段计算 SHA-256。
	 */
	private String resolveContentHash(DataAgentSkillVersion version) {
		if (StringUtils.hasText(version.getChecksum())) {
			return version.getChecksum();
		}
		String source = String.join("|",
				Objects.toString(version.getId(), ""),
				Objects.toString(version.getSkillMarkdown(), ""),
				Objects.toString(version.getRouteRules(), ""),
				Objects.toString(version.getReactConfig(), ""),
				Objects.toString(version.getFlowDefinition(), ""),
				Objects.toString(version.getInputSchema(), ""),
				Objects.toString(version.getOutputSchema(), ""),
				Objects.toString(version.getRuntimeConfig(), ""));
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw CheckedException.fail("计算Skill版本内容哈希失败");
		}
	}

	private Map<Long, CurrentVersionInfo> currentVersionInfoMap(List<SkillMarketListing> listings) {
		List<Long> versionIds = listings.stream()
			.map(SkillMarketListing::getCurrentVersionId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		if (versionIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return listingVersionMapper.selectByIds(versionIds)
			.stream()
			.collect(Collectors.toMap(SkillMarketListingVersion::getId,
					version -> new CurrentVersionInfo(version.getVersionNo(), version.getSkillVersionId()),
					(left, right) -> left));
	}

	private CurrentVersionInfo versionInfo(Map<Long, CurrentVersionInfo> versionInfoMap, Long versionId) {
		if (versionId == null) {
			return null;
		}
		return versionInfoMap.get(versionId);
	}

	private SkillMarketListingResp toListingDTO(SkillMarketListing listing, CurrentVersionInfo currentVersionInfo) {
		return SkillMarketListingResp.builder()
			.id(listing.getId())
			.listingName(listing.getListingName())
			.description(listing.getDescription())
			.category(listing.getCategory())
			.publisherId(listing.getPublisherId())
			.publisherName(listing.getPublisherName())
			.publisherTenantId(listing.getPublisherTenantId())
			.currentVersionId(listing.getCurrentVersionId())
			.currentSkillVersionId(currentVersionInfo == null ? null : currentVersionInfo.skillVersionId())
			.currentVersionNo(currentVersionInfo == null ? null : currentVersionInfo.versionNo())
			.ioSchemaSummary(listing.getIoSchemaSummary())
			.permissionScope(listing.getPermissionScope())
			.dataScope(listing.getDataScope())
			.riskLevel(listing.getRiskLevel())
			.dependentResources(listing.getDependentResources())
			.defaultUsageQuota(listing.getDefaultUsageQuota())
			.compatibleEngineVersion(listing.getCompatibleEngineVersion())
			.revoked(Boolean.TRUE.equals(listing.getRevoked()))
			.reviewStatus(listing.getReviewStatus())
			.createTime(listing.getCreateTime())
			.lastModifyTime(listing.getLastModifyTime())
			.build();
	}

	private record CurrentVersionInfo(Integer versionNo, Long skillVersionId) {
	}

	private SkillMarketListingVersionResp toVersionDTO(SkillMarketListingVersion version) {
		return SkillMarketListingVersionResp.builder()
			.id(version.getId())
			.listingId(version.getListingId())
			.versionNo(version.getVersionNo())
			.skillVersionId(version.getSkillVersionId())
			.contentHash(version.getContentHash())
			.changeNote(version.getChangeNote())
			.createTime(version.getCreateTime())
			.createName(version.getCreateName())
			.build();
	}

	private SkillMarketReviewResp toReviewDTO(SkillMarketReview review) {
		return SkillMarketReviewResp.builder()
			.id(review.getId())
			.listingId(review.getListingId())
			.listingVersionId(review.getListingVersionId())
			.reviewerId(review.getReviewerId())
			.reviewerName(review.getReviewerName())
			.conclusion(review.getConclusion())
			.opinion(review.getOpinion())
			.createTime(review.getCreateTime())
			.build();
	}

	private Integer normalizeQuota(Integer quota) {
		if (quota == null) {
			return null;
		}
		if (quota < 0) {
			throw CheckedException.badRequest("使用配额默认值不能为负数");
		}
		return quota;
	}

	private String safeUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String safeNickName() {
		try {
			return authenticationContext.nickName();
		}
		catch (Exception ex) {
			return null;
		}
	}

}
