/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.market.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingCreateReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingDetailResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingModifyReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingPageQueryReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketListingResp;
import com.sn68.agent.dataagent.market.dto.SkillMarketReviewReq;
import com.sn68.agent.dataagent.market.dto.SkillMarketSubmitReviewReq;
import com.sn68.agent.dataagent.market.entity.SkillMarketListing;
import com.sn68.agent.dataagent.market.entity.SkillMarketListingVersion;
import com.sn68.agent.dataagent.market.entity.SkillMarketReview;
import com.sn68.agent.dataagent.market.enums.MarketListingStatus;
import com.sn68.agent.dataagent.market.enums.MarketReviewConclusion;
import com.sn68.agent.dataagent.market.repository.SkillMarketListingMapper;
import com.sn68.agent.dataagent.market.repository.SkillMarketListingVersionMapper;
import com.sn68.agent.dataagent.market.repository.SkillMarketReviewMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.dataagent.skill.SkillVersionStatus;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 能力市场条目服务的越权防线测试：条目归属校验、引用 Skill 版本归属校验、
 * 可见范围收敛，以及撤销级联不得回写发布者源版本。
 */
class SkillMarketListingServiceImplTest {

	private static final String CURRENT_TENANT = "7";

	private static final String OTHER_TENANT = "9";

	@BeforeAll
	static void initTableInfo() {
		// pageListings 走 Wraps.<SkillMarketListing>lbQ()，lambda 列名解析依赖 MP 的 TableInfo 缓存；
		// 纯单测没有 Spring 做 mapper 扫描，按 CompiledPlanPersistenceServiceImplTest 同款写法手动注册。
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				SkillMarketListing.class);
	}

	private final SkillMarketListingMapper listingMapper = mock(SkillMarketListingMapper.class);

	private final SkillMarketListingVersionMapper listingVersionMapper = mock(SkillMarketListingVersionMapper.class);

	private final SkillMarketReviewMapper reviewMapper = mock(SkillMarketReviewMapper.class);

	private final DataAgentSkillVersionMapper skillVersionMapper = mock(DataAgentSkillVersionMapper.class);

	private final DigitalEmployeeReleaseMapper digitalEmployeeReleaseMapper = mock(DigitalEmployeeReleaseMapper.class);

	private final PlatformScopePermissionService platformScopePermissionService = mock(
			PlatformScopePermissionService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final SkillMarketListingServiceImpl service = new SkillMarketListingServiceImpl(listingVersionMapper,
			reviewMapper, skillVersionMapper, digitalEmployeeReleaseMapper, platformScopePermissionService,
			authenticationContext);

	@BeforeEach
	void setUp() {
		// baseMapper 由 Spring 注入到 ServiceImpl，纯单测手动塞入
		ReflectionTestUtils.setField(service, "baseMapper", listingMapper);
		when(platformScopePermissionService.currentTenantId()).thenReturn(CURRENT_TENANT);
		when(platformScopePermissionService.isPlatformAdmin()).thenReturn(false);
		when(listingMapper.findByIdAndPublisherTenant(anyLong(), anyString())).thenAnswer(invocation -> {
			SkillMarketListing listing = listingMapper.selectById(invocation.getArgument(0));
			if (listing == null) {
				return null;
			}
			String tenantId = invocation.getArgument(1);
			return tenantId != null && tenantId.equals(listing.getPublisherTenantId()) ? listing : null;
		});
	}

	@Test
	void modifyListingRejectsListingPublishedByAnotherTenant() {
		when(listingMapper.selectById(10L)).thenReturn(listing(10L, OTHER_TENANT, MarketListingStatus.DRAFT));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.modifyListing(10L, modifyReq()));

		assertEquals(403, ex.getCode());
		assertTrue(ex.getMessage().contains("其他租户"));
		verify(listingMapper, never()).updateById(any(SkillMarketListing.class));
	}

	@Test
	void deleteListingRejectsListingPublishedByAnotherTenant() {
		when(listingMapper.selectById(10L)).thenReturn(listing(10L, OTHER_TENANT, MarketListingStatus.DRAFT));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.deleteListing(10L));

		assertEquals(403, ex.getCode());
		verify(listingMapper, never()).deleteById(anyLong());
	}

	@Test
	void writeOperationsRejectLegacyListingWithoutPublisherTenant() {
		// 历史无主数据不能与"同样没有租户上下文"的调用方互相匹配，一律失败关闭
		when(listingMapper.selectById(10L)).thenReturn(listing(10L, null, MarketListingStatus.DRAFT));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.modifyListing(10L, modifyReq()));

		assertEquals(403, ex.getCode());
		verify(listingMapper, never()).updateById(any(SkillMarketListing.class));
	}

	@Test
	void createListingRejectsMissingTenantContext() {
		when(platformScopePermissionService.currentTenantId()).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createListing(createReq()));

		assertEquals(403, ex.getCode());
		assertTrue(ex.getMessage().contains("租户"));
		verify(listingMapper, never()).insert(any(SkillMarketListing.class));
	}

	@Test
	void submitReviewRejectsListingPublishedByAnotherTenant() {
		when(listingMapper.selectById(10L)).thenReturn(listing(10L, OTHER_TENANT, MarketListingStatus.DRAFT));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.submitReview(10L, new SkillMarketSubmitReviewReq(300L, "初版")));

		assertEquals(403, ex.getCode());
		verifyNoInteractions(skillVersionMapper);
		verify(listingVersionMapper, never()).insert(any(SkillMarketListingVersion.class));
	}

	@Test
	void submitReviewRejectsSkillVersionOwnedByAnotherTenant() {
		when(listingMapper.selectById(10L)).thenReturn(listing(10L, CURRENT_TENANT, MarketListingStatus.DRAFT));
		when(skillVersionMapper.selectById(300L)).thenReturn(skillVersion(300L, OTHER_TENANT));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.submitReview(10L, new SkillMarketSubmitReviewReq(300L, "初版")));

		assertEquals(403, ex.getCode());
		assertTrue(ex.getMessage().contains("其他租户"));
		verify(listingVersionMapper, never()).insert(any(SkillMarketListingVersion.class));
		verify(listingMapper, never()).updateById(any(SkillMarketListing.class));
	}

	@Test
	void submitReviewSnapshotsOwnSkillVersion() {
		when(listingMapper.selectById(10L)).thenReturn(listing(10L, CURRENT_TENANT, MarketListingStatus.DRAFT));
		when(skillVersionMapper.selectById(300L)).thenReturn(skillVersion(300L, CURRENT_TENANT));
		when(listingVersionMapper.nextVersionNo(10L)).thenReturn(1);

		service.submitReview(10L, new SkillMarketSubmitReviewReq(300L, "初版"));

		ArgumentCaptor<SkillMarketListingVersion> captor = ArgumentCaptor.forClass(SkillMarketListingVersion.class);
		verify(listingVersionMapper).insert(captor.capture());
		assertEquals(300L, captor.getValue().getSkillVersionId());
		assertEquals(1, captor.getValue().getVersionNo());
	}

	@Test
	void submitReviewRejectsPlatformSharedSkillVersion() {
		when(listingMapper.selectById(10L)).thenReturn(listing(10L, CURRENT_TENANT, MarketListingStatus.DRAFT));
		when(skillVersionMapper.selectById(300L)).thenReturn(skillVersion(300L, "1"));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.submitReview(10L, new SkillMarketSubmitReviewReq(300L, "平台技能上架")));

		assertEquals(403, ex.getCode());
		verify(listingVersionMapper, never()).insert(any(SkillMarketListingVersion.class));
	}

	@Test
	void pageListingsLimitsToCurrentPublisherTenant() {
		stubEmptyPage();

		service.pageListings(new SkillMarketListingPageQueryReq());

		LbqWrapper<SkillMarketListing> wrapper = capturedPageWrapper();
		String sql = wrapper.getTargetSql();
		assertTrue(sql.contains("publisher_tenant_id"), sql);
		assertTrue(wrapper.getParamNameValuePairs().containsValue(CURRENT_TENANT));
		assertFalse(wrapper.getParamNameValuePairs().containsValue(MarketListingStatus.APPROVED));
	}

	@Test
	void pageListingsScopesPlatformAdminToCurrentTenant() {
		when(platformScopePermissionService.isPlatformAdmin()).thenReturn(true);
		stubEmptyPage();

		service.pageListings(new SkillMarketListingPageQueryReq());

		LbqWrapper<SkillMarketListing> wrapper = capturedPageWrapper();
		String sql = wrapper.getTargetSql();
		assertTrue(sql.contains("publisher_tenant_id"), sql);
		assertTrue(wrapper.getParamNameValuePairs().containsValue(CURRENT_TENANT));
	}

	@Test
	void pageListingsRejectsMissingTenantContext() {
		when(platformScopePermissionService.currentTenantId()).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.pageListings(new SkillMarketListingPageQueryReq()));

		assertEquals(403, ex.getCode());
		verify(listingMapper, never()).selectPage(any(), any());
	}

	@Test
	void pageListingsReturnsDraftWithoutCurrentVersion() {
		Page<SkillMarketListing> page = new Page<>(1, 10, 1);
		page.setRecords(List.of(listing(10L, CURRENT_TENANT, MarketListingStatus.DRAFT)));
		when(listingMapper.selectPage(any(), any())).thenReturn(page);

		IPage<SkillMarketListingResp> result = service.pageListings(new SkillMarketListingPageQueryReq());

		assertEquals(1, result.getRecords().size());
		assertEquals(10L, result.getRecords().get(0).id());
		assertNull(result.getRecords().get(0).currentVersionId());
		assertNull(result.getRecords().get(0).currentVersionNo());
		verifyNoInteractions(listingVersionMapper);
	}

	@Test
	void pageListingsMapsCurrentSourceSkillVersionIdSeparately() {
		Page<SkillMarketListing> page = new Page<>(1, 10, 1);
		page.setRecords(List.of(SkillMarketListing.builder()
			.id(10L)
			.publisherTenantId(CURRENT_TENANT)
			.reviewStatus(MarketListingStatus.APPROVED)
			.revoked(false)
			.currentVersionId(88L)
			.build()));
		when(listingMapper.selectPage(any(), any())).thenReturn(page);
		when(listingVersionMapper.selectByIds(anyCollection())).thenReturn(List.of(SkillMarketListingVersion.builder()
			.id(88L)
			.listingId(10L)
			.versionNo(3)
			.skillVersionId(900L)
			.build()));

		SkillMarketListingResp response = service.pageListings(new SkillMarketListingPageQueryReq())
				.getRecords().get(0);

		assertEquals(88L, response.currentVersionId());
		assertEquals(900L, response.currentSkillVersionId());
		assertEquals(3, response.currentVersionNo());
	}

	@Test
	void availableSkillsForceApprovedUnrevokedAndStayOnCurrentTenant() {
		Page<SkillMarketListing> page = new Page<>(1, 10, 1);
		page.setRecords(List.of(SkillMarketListing.builder()
			.id(10L)
			.publisherTenantId(CURRENT_TENANT)
			.reviewStatus(MarketListingStatus.APPROVED)
			.revoked(false)
			.currentVersionId(88L)
			.build()));
		when(listingMapper.selectPage(any(), any())).thenReturn(page);
		when(listingVersionMapper.selectByIds(anyCollection())).thenReturn(List.of(SkillMarketListingVersion.builder()
			.id(88L)
			.listingId(10L)
			.versionNo(3)
			.skillVersionId(900L)
			.build()));

		SkillMarketListingPageQueryReq request = new SkillMarketListingPageQueryReq();
		request.setReviewStatus(MarketListingStatus.REJECTED);
		request.setRevoked(true);

		SkillMarketListingResp response = service.pageAvailableSkillsForEmployee(request).getRecords().get(0);

		assertEquals(MarketListingStatus.APPROVED, request.getReviewStatus());
		assertFalse(request.getRevoked());
		assertEquals(900L, response.currentSkillVersionId());
		assertEquals(3, response.currentVersionNo());
		LbqWrapper<SkillMarketListing> wrapper = capturedPageWrapper();
		String sql = wrapper.getTargetSql();
		assertTrue(sql.contains("review_status"), sql);
		assertTrue(sql.contains("revoked"), sql);
		assertTrue(sql.contains("publisher_tenant_id"), sql);
		assertTrue(wrapper.getParamNameValuePairs().containsValue(CURRENT_TENANT));
		assertTrue(wrapper.getParamNameValuePairs().values().stream().anyMatch(value -> MarketListingStatus.APPROVED.equals(value)
				|| "APPROVED".equals(String.valueOf(value))), wrapper.getParamNameValuePairs().toString());
	}

	@Test
	void listingDetailHidesOtherTenantDraft() {
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.getListingDetail(10L));

		assertEquals(404, ex.getCode());
		verifyNoInteractions(listingVersionMapper);
		verifyNoInteractions(reviewMapper);
	}

	@Test
	void listingDetailHidesApprovedListingFromAnotherTenant() {
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.getListingDetail(10L));

		assertEquals(404, ex.getCode());
		verifyNoInteractions(listingVersionMapper);
		verifyNoInteractions(reviewMapper);
	}

	@Test
	void listingDetailHidesOtherTenantDraftFromPlatformAdmin() {
		when(platformScopePermissionService.isPlatformAdmin()).thenReturn(true);
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.getListingDetail(10L));

		assertEquals(404, ex.getCode());
		verifyNoInteractions(listingVersionMapper);
		verifyNoInteractions(reviewMapper);
	}

	@Test
	void listingDetailExposesOwnTenantApprovedWithReviews() {
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT))
			.thenReturn(listing(10L, CURRENT_TENANT, MarketListingStatus.APPROVED));
		when(listingVersionMapper.findByListingId(10L)).thenReturn(List.of());
		when(reviewMapper.findByListingId(10L)).thenReturn(List.of(SkillMarketReview.builder()
			.id(1L)
			.listingId(10L)
			.opinion("通过")
			.build()));

		SkillMarketListingDetailResp detail = service.getListingDetail(10L);

		assertEquals(MarketListingStatus.APPROVED, detail.listing().reviewStatus());
		assertEquals(1, detail.reviews().size());
	}

	/** 无已封/已发布员工引用时，撤销只改 listing 状态，不得回写发布者源技能版本或已封 snapshot。 */
	@Test
	void revokeUpdatesListingWithoutTouchingPublisherSkillVersions() {
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT))
			.thenReturn(listing(10L, CURRENT_TENANT, MarketListingStatus.APPROVED));
		when(listingVersionMapper.findByListingId(10L)).thenReturn(List.of(SkillMarketListingVersion.builder()
			.id(88L)
			.listingId(10L)
			.skillVersionId(900L)
			.build()));
		when(digitalEmployeeReleaseMapper.existsSealedOrPublishedReferencingSkillVersion(900L)).thenReturn(false);

		service.revoke(10L);

		verify(platformScopePermissionService, never()).requirePlatformAdmin(anyString());
		verify(listingMapper).updateById(any(SkillMarketListing.class));
		verify(digitalEmployeeReleaseMapper).existsSealedOrPublishedReferencingSkillVersion(900L);
		// 发布者的 data_agent_skill_version 是来源资产，撤销上架条目不得回写它
		verifyNoInteractions(skillVersionMapper);
	}

	@Test
	void revokeRejectsWhenSealedOrPublishedEmployeeStillReferencesSkill() {
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT))
			.thenReturn(listing(10L, CURRENT_TENANT, MarketListingStatus.APPROVED));
		when(listingVersionMapper.findByListingId(10L)).thenReturn(List.of(SkillMarketListingVersion.builder()
			.id(88L)
			.listingId(10L)
			.skillVersionId(900L)
			.build()));
		when(digitalEmployeeReleaseMapper.existsSealedOrPublishedReferencingSkillVersion(900L)).thenReturn(true);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.revoke(10L));

		assertTrue(ex.getMessage().contains("已封版"), ex.getMessage());
		assertTrue(ex.getMessage().contains("900"), ex.getMessage());
		verify(listingMapper, never()).updateById(any(SkillMarketListing.class));
		verifyNoInteractions(skillVersionMapper);
	}

	@Test
	void reviewRejectsListingPublishedByAnotherTenant() {
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.review(10L, new SkillMarketReviewReq(MarketReviewConclusion.APPROVED, "欧克")));

		assertTrue(ex.getMessage().contains("不存在") || ex.getMessage().contains("无权"), ex.getMessage());
		verify(reviewMapper, never()).insert(any(SkillMarketReview.class));
		verify(listingMapper, never()).updateById(any(SkillMarketListing.class));
	}

	@Test
	void reviewApprovesWithoutPlatformAdminUserType() {
		when(listingMapper.findByIdAndPublisherTenant(10L, CURRENT_TENANT))
			.thenReturn(listing(10L, CURRENT_TENANT, MarketListingStatus.REVIEWING));
		when(listingVersionMapper.findLatestByListingId(10L)).thenReturn(SkillMarketListingVersion.builder()
			.id(88L)
			.listingId(10L)
			.versionNo(1)
			.build());

		service.review(10L, new SkillMarketReviewReq(MarketReviewConclusion.APPROVED, "欧克"));

		verify(platformScopePermissionService, never()).requirePlatformAdmin(anyString());
		verify(reviewMapper).insert(any(SkillMarketReview.class));
		ArgumentCaptor<SkillMarketListing> captor = ArgumentCaptor.forClass(SkillMarketListing.class);
		verify(listingMapper).updateById(captor.capture());
		assertEquals(MarketListingStatus.APPROVED, captor.getValue().getReviewStatus());
		assertEquals(88L, captor.getValue().getCurrentVersionId());
	}

	private void stubEmptyPage() {
		Page<SkillMarketListing> page = new Page<>(1, 10, 0);
		page.setRecords(List.of());
		when(listingMapper.selectPage(any(), any())).thenReturn(page);
	}

	@SuppressWarnings("unchecked")
	private LbqWrapper<SkillMarketListing> capturedPageWrapper() {
		ArgumentCaptor<Wrapper<SkillMarketListing>> captor = ArgumentCaptor.forClass(Wrapper.class);
		verify(listingMapper).selectPage(any(), captor.capture());
		return (LbqWrapper<SkillMarketListing>) captor.getValue();
	}

	private SkillMarketListing listing(Long id, String publisherTenantId, MarketListingStatus status) {
		return SkillMarketListing.builder()
			.id(id)
			.listingName("对账助手")
			.publisherTenantId(publisherTenantId)
			.reviewStatus(status)
			.revoked(false)
			.build();
	}

	private DataAgentSkillVersion skillVersion(Long id, String tenantId) {
		return DataAgentSkillVersion.builder()
			.id(id)
			.tenantId(tenantId)
			.status(SkillVersionStatus.PUBLISHED.name())
			.revoked(false)
			.checksum("hash-" + id)
			.build();
	}

	private SkillMarketListingModifyReq modifyReq() {
		return SkillMarketListingModifyReq.builder().listingName("改名").build();
	}

	private SkillMarketListingCreateReq createReq() {
		return SkillMarketListingCreateReq.builder().listingName("对账助手").build();
	}

}
