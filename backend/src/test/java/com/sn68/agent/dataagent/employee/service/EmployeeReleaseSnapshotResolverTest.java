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
package com.sn68.agent.dataagent.employee.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Release 快照事实源解析器聚焦单测（PR-3d：快照解析 / 版本固定 / 失败关闭 / 缓存失效）。
 *
 * <p>快照 JSON 由 {@link EmployeeReleaseSnapshotAssembler} 真实装配（与 Seal 时同一算法），
 * 保证"Seal 冻结 → 运行时消费"链路的摘要一致性端到端可验证；Mapper 全部 Mockito mock。</p>
 */
class EmployeeReleaseSnapshotResolverTest {

	private static final String TENANT = "1";

	private static final Long EMPLOYEE_ID = 77L;

	private static final Long RELEASE_ID = 500L;

	private static final String PRODUCTION = DeploymentEnvironmentDict.PRODUCTION.getValue();

	private DigitalEmployeeReleaseMapper releaseMapper;

	private DigitalEmployeeDeploymentMapper deploymentMapper;

	private EmployeeReleaseSnapshotAssembler assembler;

	private EmployeeReleaseSnapshotResolver resolver;

	private String snapshotJson;

	private String specHash;

	@BeforeEach
	void setUp() {
		releaseMapper = mock(DigitalEmployeeReleaseMapper.class);
		deploymentMapper = mock(DigitalEmployeeDeploymentMapper.class);
		assembler = new EmployeeReleaseSnapshotAssembler(new ObjectMapper(),
				mock(com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper.class),
				mock(com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper.class));
		resolver = new EmployeeReleaseSnapshotResolver(releaseMapper, deploymentMapper, assembler,
				new ObjectMapper());
		// 真实装配：employee 草稿 + 两条启用能力 → 冻结快照 + spec_hash（与 Seal 落库同构，固定 frozenAt）
		snapshotJson = assembler.assemble(employee(), capabilities(), Instant.parse("2026-08-18T00:00:00Z"));
		specHash = assembler.computeSpecHash(snapshotJson);
	}

	@Test
	void resolveByIdParsesPublishedSnapshotEndToEnd() {
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue());

		EmployeeReleaseSnapshot snapshot = resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID);

		assertEquals(RELEASE_ID, snapshot.releaseId());
		assertEquals(EMPLOYEE_ID, snapshot.employeeId());
		assertEquals("1", snapshot.schemaVersion());
		assertEquals(specHash, snapshot.specHash());
		assertEquals("E-001", snapshot.employeeCode());
		assertEquals("员工A", snapshot.employeeName());
		assertEquals("谨慎执行", snapshot.systemInstruction());
		assertEquals(30L, snapshot.modelConfigId());
		assertEquals(40L, snapshot.routeProfileId());
		assertEquals(2, snapshot.capabilities().size());
		assertEquals(900L, snapshot.capabilities().get(0).skillVersionId());
		assertEquals(901L, snapshot.capabilities().get(1).skillVersionId());
		assertEquals(Instant.parse("2026-08-18T00:00:00Z"), snapshot.frozenAt());
		// 草稿态不消费：解析视图只来自 snapshot，与 digital_employee_capability 当前状态无关
		assertTrue(snapshot.capabilityVersions().containsAll(List.of("900", "901")));
	}

	@Test
	void resolveActiveFollowsDeploymentSwitch() {
		stubDeployment(200L, RELEASE_ID);
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue());

		EmployeeReleaseSnapshot first = resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION);
		assertEquals(RELEASE_ID, first.releaseId());

		// 部署 CAS 切换（deployment_version 递增 + activeReleaseId 变更）：下次解析即刻跟随新 Release
		DigitalEmployeeRelease switched = release(600L, EmployeeReleaseStatusDict.PUBLISHED.getValue(),
				assembler.computeSpecHash(snapshotJson));
		stubDeployment(201L, 600L);
		when(releaseMapper.findByIdAndTenantId(600L, TENANT)).thenReturn(switched);

		EmployeeReleaseSnapshot second = resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION);

		assertEquals(600L, second.releaseId());
		verify(deploymentMapper, times(2)).findByEmployeeAndEnvironment(EMPLOYEE_ID, PRODUCTION, TENANT);
	}

	@Test
	void sameSpecHashReusesParsedSnapshotAcrossResolutions() {
		stubDeployment(200L, RELEASE_ID);
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue());

		EmployeeReleaseSnapshot first = resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION);
		EmployeeReleaseSnapshot second = resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION);

		// JSON 解析结果按 releaseId + spec_hash 缓存复用；部署行仍每次新鲜读取（CAS 语义对齐）
		assertSame(first, second);
		verify(releaseMapper, times(2)).findByIdAndTenantId(RELEASE_ID, TENANT);
	}

	@Test
	void noDeploymentFailsClosed() {
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, PRODUCTION, TENANT)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION));
		assertTrue(ex.getMessage().contains("失败关闭"));
		assertTrue(ex.getMessage().contains("无激活部署"));
	}

	@Test
	void deploymentWithoutActiveReleaseFailsClosed() {
		stubDeployment(200L, null);

		assertThrows(CheckedException.class, () -> resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION));
	}

	@Test
	void retiredReleaseFailsClosed() {
		stubDeployment(200L, RELEASE_ID);
		stubRelease(EmployeeReleaseStatusDict.RETIRED.getValue());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION));
		assertTrue(ex.getMessage().contains("未处于已发布状态"));
		assertTrue(ex.getMessage().contains("RETIRED"));
	}

	@Test
	void draftAndSealedReleasesFailClosed() {
		stubRelease(EmployeeReleaseStatusDict.DRAFT.getValue());
		assertThrows(CheckedException.class, () -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));

		stubRelease(EmployeeReleaseStatusDict.SEALED.getValue());
		assertThrows(CheckedException.class, () -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));
	}

	@Test
	void missingOrCrossTenantReleaseFailsClosed() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT)).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));
		assertTrue(ex.getMessage().contains("不存在或不属于当前租户"));
	}

	@Test
	void releaseOwnedByOtherEmployeeFailsClosed() {
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue(), 999L);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));
		assertTrue(ex.getMessage().contains("不属于该员工"));
	}

	@Test
	void equivalentJsonWithReorderedKeysPassesHash() throws Exception {
		String pretty = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
				new ObjectMapper().readTree(snapshotJson));
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT))
			.thenReturn(releaseWithSnapshot(pretty, specHash));

		EmployeeReleaseSnapshot snapshot = resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID);

		assertEquals(RELEASE_ID, snapshot.releaseId());
		assertEquals("员工A", snapshot.employeeName());
	}

	@Test
	void tamperedSnapshotHashFailsClosed() {
		stubDeployment(200L, RELEASE_ID);
		// 快照内容被非常规修改而 spec_hash 未同步：重算摘要不一致 → 失败关闭
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue(), EMPLOYEE_ID,
				assembler.computeSpecHash(snapshotJson.replace("员工A", "员工B")));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveActive(TENANT, EMPLOYEE_ID, PRODUCTION));
		assertTrue(ex.getMessage().contains("疑似内容被篡改"));
	}

	@Test
	void missingSnapshotOrHashFailsClosed() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT)).thenReturn(DigitalEmployeeRelease.builder()
			.id(RELEASE_ID)
			.employeeId(EMPLOYEE_ID)
			.tenantId(TENANT)
			.status(EmployeeReleaseStatusDict.PUBLISHED.getValue())
			.snapshot(snapshotJson)
			.specHash(null)
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));
		assertTrue(ex.getMessage().contains("快照内容或摘要缺失"));
	}

	@Test
	void corruptSnapshotJsonFailsClosed() {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT)).thenReturn(releaseWithSnapshot(
				"{ not-valid-json", assembler.computeSpecHash("{ not-valid-json")));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));
		assertTrue(ex.getMessage().contains("不是合法 JSON"));
	}

	@Test
	void unsupportedSchemaVersionFailsClosed() {
		String legacySnapshot = "{\"schemaVersion\":\"9\",\"employeeId\":\"77\"}";
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT)).thenReturn(releaseWithSnapshot(
				legacySnapshot, assembler.computeSpecHash(legacySnapshot)));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));
		assertTrue(ex.getMessage().contains("不支持的快照 schema 版本"));
	}

	@Test
	void nonNumericLongFieldFailsClosedWithFieldName() {
		// readLong 非数字文本：按字段名转 CheckedException 快速失败，不外抛裸 NumberFormatException
		String corrupted = snapshotJson.replace("\"modelConfigId\":\"30\"", "\"modelConfigId\":\"abc\"");
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT)).thenReturn(releaseWithSnapshot(
				corrupted, assembler.computeSpecHash(corrupted)));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID));
		assertTrue(ex.getMessage().contains("modelConfigId"));
		assertTrue(ex.getMessage().contains("不是合法数字"));
	}

	@Test
	void nullEmployeeIdSkipsOwnershipCheckForCallerStyleValidation() {
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue());

		// CALLER 透传校验场景：无员工上下文时不做归属断言，仅校验租户 + 状态 + 完整性
		EmployeeReleaseSnapshot snapshot = resolver.resolveById(TENANT, null, RELEASE_ID);
		assertEquals(RELEASE_ID, snapshot.releaseId());
	}

	@Test
	void matchCapabilityVersionAnchorsOnSnapshot() {
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue());
		EmployeeReleaseSnapshot snapshot = resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID);

		// 请求版本被快照收录 → 返回请求版本（比对一致）
		assertEquals("900", snapshot.matchCapabilityVersion("900"));
		// 请求版本未被收录（live 版本 / 其它 Release）→ 返回快照锚点（触发版本不一致）
		assertEquals(specHash, snapshot.matchCapabilityVersion("999"));
		// 请求版本为空 → null（PDP 跳过比对）
		assertNull(snapshot.matchCapabilityVersion(null));
		assertNull(snapshot.matchCapabilityVersion(" "));
	}

	@Test
	void invalidEnvironmentRejected() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> resolver.resolveActive(TENANT, EMPLOYEE_ID, "UAT"));
		assertTrue(ex.getMessage().contains("部署环境不合法"));
	}

	private void stubDeployment(Long deploymentVersion, Long activeReleaseId) {
		DigitalEmployeeDeployment deployment = DigitalEmployeeDeployment.builder()
			.id(200L)
			.tenantId(TENANT)
			.employeeId(EMPLOYEE_ID)
			.environment(PRODUCTION)
			.deploymentVersion(deploymentVersion == null ? 0 : deploymentVersion.intValue())
			.activeReleaseId(activeReleaseId)
			.build();
		when(deploymentMapper.findByEmployeeAndEnvironment(EMPLOYEE_ID, PRODUCTION, TENANT))
			.thenReturn(deployment);
	}

	private void stubRelease(String status) {
		stubRelease(status, EMPLOYEE_ID);
	}

	private void stubRelease(String status, Long employeeId) {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT))
			.thenReturn(release(status, employeeId, specHash));
	}

	private void stubRelease(String status, Long employeeId, String hash) {
		when(releaseMapper.findByIdAndTenantId(RELEASE_ID, TENANT))
			.thenReturn(release(status, employeeId, hash));
	}

	private DigitalEmployeeRelease release(String status, String hash) {
		return release(status, EMPLOYEE_ID, hash);
	}

	private DigitalEmployeeRelease release(Long id, String status, String hash) {
		return release(id, status, EMPLOYEE_ID, hash);
	}

	private DigitalEmployeeRelease release(String status, Long employeeId, String hash) {
		return release(RELEASE_ID, status, employeeId, hash);
	}

	private DigitalEmployeeRelease release(Long id, String status, Long employeeId, String hash) {
		return DigitalEmployeeRelease.builder()
			.id(id)
			.tenantId(TENANT)
			.employeeId(employeeId)
			.releaseNo(1)
			.schemaVersion(EmployeeReleaseSnapshotAssembler.SNAPSHOT_SCHEMA_VERSION)
			.snapshot(snapshotJson)
			.specHash(hash)
			.status(status)
			.build();
	}

	private DigitalEmployeeRelease releaseWithSnapshot(String snapshot, String hash) {
		return DigitalEmployeeRelease.builder()
			.id(RELEASE_ID)
			.tenantId(TENANT)
			.employeeId(EMPLOYEE_ID)
			.releaseNo(1)
			.schemaVersion(EmployeeReleaseSnapshotAssembler.SNAPSHOT_SCHEMA_VERSION)
			.snapshot(snapshot)
			.specHash(hash)
			.status(EmployeeReleaseStatusDict.PUBLISHED.getValue())
			.build();
	}

	private DigitalEmployee employee() {
		return DigitalEmployee.builder()
			.id(EMPLOYEE_ID)
			.employeeCode("E-001")
			.employeeName("员工A")
			.jobTitle("运营专员")
			.systemInstruction("谨慎执行")
			.greeting("你好")
			.modelConfigId(30L)
			.routeProfileId(40L)
			.sourceAgentId(50L)
			.autonomyLevel("SUPERVISED")
			.build();
	}

	private List<DigitalEmployeeCapability> capabilities() {
		return List.of(capability(900L), capability(901L));
	}

	private DigitalEmployeeCapability capability(Long skillVersionId) {
		return DigitalEmployeeCapability.builder()
			.tenantId(TENANT)
			.employeeId(EMPLOYEE_ID)
			.skillVersionId(skillVersionId)
			.enabled(true)
			.build();
	}

	@Test
	void blankVersionMatchIgnoresWhitespace() {
		stubRelease(EmployeeReleaseStatusDict.PUBLISHED.getValue());
		EmployeeReleaseSnapshot snapshot = resolver.resolveById(TENANT, EMPLOYEE_ID, RELEASE_ID);
		// " 900 " trim 后命中快照收录版本
		assertEquals("900", snapshot.matchCapabilityVersion(" 900 "));
		assertFalse(snapshot.capabilityVersions().isEmpty());
	}

}
