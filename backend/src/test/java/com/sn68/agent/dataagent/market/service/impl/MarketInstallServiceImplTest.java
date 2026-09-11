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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.skill.SkillVersionStatus;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 市场安装测试（PR-5 重建）：APPROVED + 未撤销 + 版本可用 → 幂等写数字员工 Capability 绑定；
 * 非法状态全部显式拒绝（无假安装）。
 */
class MarketInstallServiceImplTest {

	private static final String TENANT_ID = "7";

	private static final Long EMPLOYEE_ID = 9L;

	private static final Long LISTING_ID = 100L;

	private static final Long VERSION_ID = 200L;

	private final SkillMarketListingMapper listingMapper = mock(SkillMarketListingMapper.class);

	private final SkillMarketListingVersionMapper listingVersionMapper = mock(SkillMarketListingVersionMapper.class);

	private final DataAgentSkillVersionMapper skillVersionMapper = mock(DataAgentSkillVersionMapper.class);

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final DigitalEmployeeCapabilityMapper capabilityMapper = mock(DigitalEmployeeCapabilityMapper.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private MarketInstallServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new MarketInstallServiceImpl(listingMapper, listingVersionMapper, skillVersionMapper, employeeMapper,
				capabilityMapper, authenticationContext);
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.status(EmployeeStatusDict.ENABLED.getValue())
			.build());
		when(skillVersionMapper.selectById(5L)).thenReturn(DataAgentSkillVersion.builder()
			.id(5L)
			.tenantId(TENANT_ID)
			.status(SkillVersionStatus.PUBLISHED.getValue())
			.deleted(false)
			.revoked(false)
			.build());
	}

	private SkillMarketListing approvedListing() {
		return SkillMarketListing.builder()
			.id(LISTING_ID)
			.publisherTenantId(TENANT_ID)
			.reviewStatus(MarketListingStatus.APPROVED)
			.revoked(false)
			.currentVersionId(VERSION_ID)
			.defaultUsageQuota(50)
			.build();
	}

	private SkillMarketListingVersion currentVersion() {
		return SkillMarketListingVersion.builder()
			.id(VERSION_ID)
			.listingId(LISTING_ID)
			.versionNo(2)
			.skillVersionId(5L)
			.contentHash("hash-abc")
			.build();
	}

	@Test
	@DisplayName("合法条目安装成功：新建 Capability 绑定并返回来源追溯信息")
	void installSucceedsWithApprovedListing() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		when(capabilityMapper.findByEmployeeAndSkillVersion(EMPLOYEE_ID, 5L, TENANT_ID))
			.thenReturn(DigitalEmployeeCapability.builder().id(300L).build());

		MarketInstallPrepareResp resp = service.install(LISTING_ID, 2, EMPLOYEE_ID);

		assertEquals(300L, resp.installationId());
		assertEquals(LISTING_ID, resp.listingId());
		assertEquals(VERSION_ID, resp.listingVersionId());
		assertEquals(5L, resp.skillVersionId());
		assertEquals("hash-abc", resp.contentHash());
		assertEquals(50, resp.defaultUsageQuota());
	}

	@Test
	@DisplayName("重复安装幂等：恢复启用既有绑定，不再插入新行")
	void installIsIdempotentByReEnablingExistingBinding() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		when(capabilityMapper.findByEmployeeAndSkillVersion(EMPLOYEE_ID, 5L, TENANT_ID))
			.thenReturn(DigitalEmployeeCapability.builder().id(300L).employeeId(EMPLOYEE_ID).skillVersionId(5L).build());

		MarketInstallPrepareResp resp = service.install(LISTING_ID, 2, EMPLOYEE_ID);

		assertEquals(300L, resp.installationId());
		verify(capabilityMapper).upsertEnabled(TENANT_ID, EMPLOYEE_ID, 5L);
	}

	@Test
	@DisplayName("已撤销条目拒绝安装")
	void installRejectsRevokedListing() {
		SkillMarketListing revoked = approvedListing();
		revoked.setRevoked(true);
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(revoked);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("撤销"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("非 APPROVED（审核中）条目拒绝安装")
	void installRejectsUnapprovedListing() {
		SkillMarketListing reviewing = approvedListing();
		reviewing.setReviewStatus(MarketListingStatus.REVIEWING);
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(reviewing);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("APPROVED"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("已封存员工拒绝安装能力")
	void installRejectsArchivedEmployee() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.tenantId(TENANT_ID)
			.status(EmployeeStatusDict.ARCHIVED.getValue())
			.build());

		CheckedException ex = assertThrows(CheckedException.class, () -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("封存"), ex.getMessage());
		verify(listingMapper, never()).findByIdAndPublisherTenant(anyLong(), anyString());
	}

	@Test
	@DisplayName("员工不存在时拒绝安装")
	void installRejectsMissingEmployee() {
		when(employeeMapper.findByIdAndTenantId(EMPLOYEE_ID, TENANT_ID)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
	}

	@Test
	@DisplayName("路径版本号必须匹配当前通过版本")
	void installRejectsStaleVersionNumber() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 1, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("版本已变化"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("当前市场版本不得指向其他条目")
	void installRejectsVersionOwnedByAnotherListing() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		SkillMarketListingVersion foreignVersion = currentVersion();
		foreignVersion.setListingId(999L);
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(foreignVersion);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("不匹配"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("来源技能版本撤销后拒绝安装")
	void installRejectsRevokedSkillVersion() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		DataAgentSkillVersion revoked = DataAgentSkillVersion.builder()
			.id(5L)
			.tenantId(TENANT_ID)
			.status(SkillVersionStatus.PUBLISHED.getValue())
			.revoked(true)
			.build();
		when(skillVersionMapper.selectById(5L)).thenReturn(revoked);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("撤销"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("来源技能版本不存在时拒绝安装")
	void installRejectsMissingSkillVersion() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		when(skillVersionMapper.selectById(5L)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("来源Skill版本不存在"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("来源技能版本逻辑删除时拒绝安装")
	void installRejectsDeletedSkillVersion() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		when(skillVersionMapper.selectById(5L)).thenReturn(DataAgentSkillVersion.builder()
			.id(5L)
			.tenantId(TENANT_ID)
			.status(SkillVersionStatus.PUBLISHED.getValue())
			.deleted(true)
			.revoked(false)
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("来源Skill版本不存在"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("来源技能版本未发布时拒绝安装")
	void installRejectsUnpublishedSkillVersion() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		when(skillVersionMapper.selectById(5L)).thenReturn(DataAgentSkillVersion.builder()
			.id(5L)
			.tenantId(TENANT_ID)
			.status(SkillVersionStatus.DRAFT.getValue())
			.deleted(false)
			.revoked(false)
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("未发布"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("其他租户发布的市场条目不可安装")
	void installRejectsForeignListing() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("审核通过的市场条目不得消费其他租户的技能版本")
	void installRejectsCrossTenantSkillVersion() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		when(skillVersionMapper.selectById(5L)).thenReturn(DataAgentSkillVersion.builder()
			.id(5L)
			.tenantId("other-tenant")
			.status(SkillVersionStatus.PUBLISHED.getValue())
			.revoked(false)
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

	@Test
	@DisplayName("租户 1 的技能版本不再视为平台共享")
	void installRejectsPlatformSharedSkillVersion() {
		when(listingMapper.findByIdAndPublisherTenant(LISTING_ID, TENANT_ID)).thenReturn(approvedListing());
		when(listingVersionMapper.selectById(VERSION_ID)).thenReturn(currentVersion());
		when(skillVersionMapper.selectById(5L)).thenReturn(DataAgentSkillVersion.builder()
			.id(5L)
			.tenantId("1")
			.status(SkillVersionStatus.PUBLISHED.getValue())
			.revoked(false)
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.install(LISTING_ID, 2, EMPLOYEE_ID));

		assertTrue(ex.getMessage().contains("不存在"), ex.getMessage());
		verifyNoInteractions(capabilityMapper);
	}

}
