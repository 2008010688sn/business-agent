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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeReleaseStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDeploymentMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数字员工 Release 快照运行时事实源解析器（PR-3d 核心交付物）。
 *
 * <p>事实源语义（修正 prepareRuntimeConfiguration 读 live 配置的历史问题，主文档 5.3.4 第 6 层）：
 * 运行时能力清单 / 模型参数 / 策略版本锚点一律从 {@code digital_employee_release.snapshot}
 * （Seal 冻结）解析，digital_employee 与 digital_employee_capability 草稿态不参与运行时消费。
 * Live 配置修改后，已发布 Release 的任务/对话仍按本解析器返回的快照视图运行。</p>
 *
 * <p>失败关闭路径（全部抛 {@link CheckedException}，不静默降级）：
 * <ul>
 * <li>员工在指定环境无部署行或部署未激活 → 拒绝（无快照可消费）；</li>
 * <li>Release 不存在 / 跨租户 → 拒绝；</li>
 * <li>Release 状态非 PUBLISHED（DRAFT/SEALED/RETIRED）→ 拒绝（RETIRED 即撤回事实源）；</li>
 * <li>snapshot / spec_hash 为空，或重算 SHA-256 与 spec_hash 不一致 → 拒绝（防篡改）；</li>
 * <li>schema_version 不支持或快照 JSON 损坏 → 拒绝（数据完整性）。</li>
 * </ul></p>
 *
 * <p>缓存与失效（与 bind_revision / deployment_version 语义对齐，参照 RuntimePolicyEvaluator 模式）：
 * 部署行与 Release 行每次新鲜读取（deployment_version 的 CAS 切换即刻反映）；
 * 仅快照 JSON 的反序列化结果按 releaseId 进程内缓存，spec_hash 不一致即视为失效重解析
 * （Seal 后快照内容理论不可变，比对是防御性的）。缓存条目上限 2048，超出后新键绕过缓存。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeReleaseSnapshotResolver {

	/**
	 * 快照解析缓存条目上限：超出后新键直接绕过缓存（防无界增长；命中已有键仍复用）。
	 */
	private static final int MAX_CACHE_ENTRIES = 2048;

	private final DigitalEmployeeReleaseMapper releaseMapper;

	private final DigitalEmployeeDeploymentMapper deploymentMapper;

	private final EmployeeReleaseSnapshotAssembler snapshotAssembler;

	private final ObjectMapper objectMapper;

	/**
	 * 快照解析缓存：key = releaseId，value = (specHash, 解析产物)。Seal 后 spec_hash 不变，
	 * 条目实际长期有效；比对是防数据被非常规修改的兜底。
	 */
	private final ConcurrentHashMap<Long, CachedParse> parsedByReleaseId = new ConcurrentHashMap<>();

	/**
	 * 按员工 + 环境解析当前部署激活的 Release 快照（对话入口 / PEP 版本固定路径）。
	 *
	 * <p>每次读取部署行获取 activeReleaseId：部署 CAS 激活/回滚（deployment_version 递增）后，
	 * 下一次调用即刻解析新 Release，无陈旧窗口。</p>
	 *
	 * @param tenantId    租户ID（必填）
	 * @param employeeId  数字员工ID（必填）
	 * @param environment 部署环境（SANDBOX/PRODUCTION，空按 PRODUCTION）
	 * @return 快照解析产物（非 null）
	 */
	public EmployeeReleaseSnapshot resolveActive(String tenantId, Long employeeId, String environment) {
		requireTenantAndEmployee(tenantId, employeeId);
		String env = normalizeEnvironment(environment);
		DigitalEmployeeDeployment deployment = deploymentMapper.findByEmployeeAndEnvironment(employeeId, env,
				tenantId.trim());
		if (deployment == null || deployment.getActiveReleaseId() == null) {
			throw CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：员工在环境 " + env
					+ " 无激活部署，运行时无快照事实源可消费, employeeId=" + employeeId + ", tenantId=" + tenantId);
		}
		return resolveReleaseRow(tenantId, employeeId, deployment.getActiveReleaseId());
	}

	/**
	 * 按指定 releaseId 解析快照（任务 / 对话链路钉死 Release 的路径）。
	 *
	 * @param tenantId   租户ID（必填）
	 * @param employeeId 数字员工ID；非空时校验 Release 归属，空表示调用方无员工上下文（如 CALLER 透传校验）
	 * @param releaseId  发布版本ID（必填）
	 * @return 快照解析产物（非 null）
	 */
	public EmployeeReleaseSnapshot resolveById(String tenantId, Long employeeId, Long releaseId) {
		if (StrUtil.isBlank(tenantId)) {
			throw CheckedException.badRequest("数字员工 Release 快照解析失败：tenantId 不能为空");
		}
		if (releaseId == null) {
			throw CheckedException.badRequest("数字员工 Release 快照解析失败：releaseId 不能为空");
		}
		return resolveReleaseRow(tenantId, employeeId, releaseId);
	}

	/**
	 * Release 行解析：租户谓词 → 状态（仅 PUBLISHED）→ 归属 → 防篡改 → JSON 解析（缓存）。
	 */
	private EmployeeReleaseSnapshot resolveReleaseRow(String tenantId, Long employeeId, Long releaseId) {
		DigitalEmployeeRelease release = releaseMapper.findByIdAndTenantId(releaseId, tenantId.trim());
		if (release == null) {
			throw CheckedException.notFound("数字员工 Release 快照解析失败：发布版本不存在或不属于当前租户, releaseId="
					+ releaseId + ", tenantId=" + tenantId);
		}
		if (!EmployeeReleaseStatusDict.PUBLISHED.getValue().equals(release.getStatus())) {
			throw CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：发布版本未处于已发布状态, releaseId="
					+ releaseId + ", status=" + release.getStatus());
		}
		if (employeeId != null && !employeeId.equals(release.getEmployeeId())) {
			throw CheckedException.badRequest("数字员工 Release 快照解析失败：发布版本不属于该员工, releaseId=" + releaseId
					+ ", employeeId=" + employeeId);
		}
		if (StrUtil.isBlank(release.getSnapshot()) || StrUtil.isBlank(release.getSpecHash())) {
			throw CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：快照内容或摘要缺失, releaseId="
					+ releaseId);
		}
		// 防篡改：以 Seal 时同一算法（EmployeeReleaseSnapshotAssembler）重算并比对 spec_hash，
		// 每次新鲜计算不进缓存——库内快照被非常规修改而摘要未同步时必须当场拒绝。
		String recomputedHash = snapshotAssembler.computeSpecHash(release.getSnapshot());
		if (!recomputedHash.equals(release.getSpecHash())) {
			log.error("数字员工 Release 快照摘要校验失败（疑似篡改）, 已失败关闭. releaseId={}, tenantId={}, "
					+ "frozenSpecHash={}, recomputedSpecHash={}", releaseId, tenantId, release.getSpecHash(),
					recomputedHash);
			throw CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：快照摘要校验不一致，疑似内容被篡改, "
					+ "releaseId=" + releaseId);
		}
		CachedParse cached = parsedByReleaseId.get(releaseId);
		if (cached != null && Objects.equals(cached.specHash(), release.getSpecHash())) {
			return cached.snapshot();
		}
		EmployeeReleaseSnapshot parsed = parseSnapshot(release);
		if (parsedByReleaseId.size() < MAX_CACHE_ENTRIES) {
			parsedByReleaseId.put(releaseId, new CachedParse(release.getSpecHash(), parsed));
		}
		return parsed;
	}

	/**
	 * 快照 JSON → 强类型视图。schemaVersion 不支持 / 结构损坏属于数据完整性问题，快速失败。
	 */
	private EmployeeReleaseSnapshot parseSnapshot(DigitalEmployeeRelease release) {
		JsonNode root;
		try {
			root = objectMapper.readTree(release.getSnapshot());
		}
		catch (Exception ex) {
			throw CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：快照不是合法 JSON, releaseId="
					+ release.getId() + ", errorType=" + ex.getClass().getSimpleName());
		}
		if (root == null || !root.isObject()) {
			throw CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：快照不是 JSON 对象, releaseId="
					+ release.getId());
		}
		String schemaVersion = root.path("schemaVersion").asText(null);
		if (!EmployeeReleaseSnapshotAssembler.SNAPSHOT_SCHEMA_VERSION.equals(schemaVersion)) {
			throw CheckedException.fail("数字员工 Release 快照解析失败（失败关闭）：不支持的快照 schema 版本, releaseId="
					+ release.getId() + ", schemaVersion=" + schemaVersion);
		}
		List<EmployeeReleaseSnapshot.CapabilityRef> capabilities = new ArrayList<>();
		for (JsonNode item : root.path("capabilities")) {
			if (item == null || !item.isObject()) {
				continue;
			}
		capabilities.add(new EmployeeReleaseSnapshot.CapabilityRef(readLong(item, "skillVersionId"),
					readLong(item, "boundCapabilityId"), readText(item, "executionMode")));
		}
		return new EmployeeReleaseSnapshot(release.getId(), release.getEmployeeId(), release.getReleaseNo(),
				schemaVersion, release.getSpecHash(), readText(root, "employeeCode"), readText(root, "employeeName"),
				readText(root, "jobTitle"), readText(root, "systemInstruction"), readText(root, "greeting"),
				readLong(root, "modelConfigId"), readLong(root, "routeProfileId"), readLong(root, "sourceAgentId"),
				readText(root, "autonomyLevel"), readExecutionPolicy(root), List.copyOf(capabilities),
				readLongList(root, "availableModelConfigIds"), readFrozenAt(root));
	}

	/**
	 * executionPolicy 忠实还原：JSON 对象转 Map，非对象形态（历史快照的原始字符串）原样保留为 String，
	 * 不做静默降级。
	 */
	private Object readExecutionPolicy(JsonNode root) {
		JsonNode node = root.get("executionPolicy");
		if (node == null || node.isNull()) {
			return Map.of();
		}
		if (node.isObject()) {
			return objectMapper.convertValue(node, Map.class);
		}
		return node.asText();
	}

	private Instant readFrozenAt(JsonNode root) {
		String text = readText(root, "frozenAt");
		if (StrUtil.isBlank(text)) {
			return null;
		}
		try {
			return Instant.parse(text);
		}
		catch (Exception ex) {
			// frozenAt 仅作审计展示，非法值不阻断事实源消费，记日志后置空。
			log.warn("数字员工 Release 快照 frozenAt 解析失败, 置空处理. value={}", text);
			return null;
		}
	}

	private String readText(JsonNode root, String field) {
		JsonNode node = root.get(field);
		return node == null || node.isNull() ? null : node.asText();
	}

	/**
	 * 快照装配时 Long 字段统一 String 化写入；解析侧兼容字符串 / 数字两种形态。
	 * 非数字文本属快照数据完整性问题，按字段名快速失败，不向外抛裸 NumberFormatException。
	 */
	private Long readLong(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		if (value.isNumber()) {
			return value.asLong();
		}
		String text = value.asText();
		if (StrUtil.isBlank(text)) {
			return null;
		}
		try {
			return Long.valueOf(text.trim());
		}
		catch (NumberFormatException ex) {
			throw CheckedException.badRequest("数字员工 Release 快照解析失败：字段 " + field + " 不是合法数字: " + text);
		}
	}

	private List<Long> readLongList(JsonNode root, String field) {
		JsonNode node = root.get(field);
		if (node == null || node.isNull() || !node.isArray()) {
			return List.of();
		}
		List<Long> values = new ArrayList<>();
		for (JsonNode item : node) {
			if (item == null || item.isNull()) {
				continue;
			}
			if (item.isNumber()) {
				values.add(item.asLong());
				continue;
			}
			String text = item.asText();
			if (StrUtil.isBlank(text)) {
				continue;
			}
			try {
				values.add(Long.valueOf(text.trim()));
			}
			catch (NumberFormatException ex) {
				throw CheckedException.badRequest("数字员工 Release 快照解析失败：字段 " + field + " 含非法数字: " + text);
			}
		}
		return List.copyOf(values);
	}

	private void requireTenantAndEmployee(String tenantId, Long employeeId) {
		if (StrUtil.isBlank(tenantId)) {
			throw CheckedException.badRequest("数字员工 Release 快照解析失败：tenantId 不能为空");
		}
		if (employeeId == null || employeeId <= 0L) {
			throw CheckedException.badRequest("数字员工 Release 快照解析失败：employeeId 不能为空");
		}
	}

	private String normalizeEnvironment(String environment) {
		if (StrUtil.isBlank(environment)) {
			return DeploymentEnvironmentDict.PRODUCTION.getValue();
		}
		String env = environment.trim();
		if (DeploymentEnvironmentDict.of(env) == null) {
			throw CheckedException.badRequest("数字员工 Release 快照解析失败：部署环境不合法（SANDBOX/PRODUCTION）: "
					+ env);
		}
		return env;
	}

	/**
	 * 缓存条目：spec_hash 与解析产物的不可变对。
	 */
	private record CachedParse(String specHash, EmployeeReleaseSnapshot snapshot) {
	}

}
