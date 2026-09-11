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
package com.sn68.agent.dataagent.authorization.gate;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.sn68.agent.dataagent.authorization.gate.dto.EnforceGateEvaluation;
import com.sn68.agent.dataagent.authorization.gate.dto.EnforceGateOverview;
import com.sn68.agent.dataagent.authorization.observability.ShadowDiffReport;
import com.sn68.agent.dataagent.authorization.observability.ShadowDiffReportGenerator;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PR-9 灰度 ENFORCE 门禁服务（v1.2 清单 PR-9：影子差异归零后按 enforceTenantIds 逐租户 ENFORCE）。
 *
 * <p>门禁是「放行决策」的守卫而非 PR-10 差异统计的重写：只消费
 * {@link ShadowDiffReportGenerator#latestReport()} 的既有结论（enforceGatePassed），
 * 不重复聚合。全部拒绝路径 fail-closed——门禁未启用、latest 报告缺失（对应 REST
 * /authorization-shadow-reports/latest 的 404 语义）、租户样本缺失、样本量不足、
 * 差异率超标，一律不放行。</p>
 *
 * <p>ENFORCE 切换保护：放行走 {@link #admitTenant} 登记进程级登记簿，checklist
 * {@link #assertRolloutIntegrity()} 检测两类违规——已放行租户静默回退 SHADOW（禁止）、
 * 白名单租户未经门禁登记（重启或白名单新增后须逐租户重新 admit）。进程重启后登记簿
 * 清零属预期：latest 报告同为进程内存态，重启后必须先重建报告再重新登记，
 * 二者的生命周期刻意对齐。</p>
 *
 * @author Terry (PR-9 灰度 ENFORCE 门禁)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnforceGateService {

	private final ShadowDiffReportGenerator reportGenerator;

	private final PepAuthorizationProperties pepProperties;

	private final DataAgentProperties dataAgentProperties;

	/**
	 * 进程级放行登记簿：本进程内已通过门禁校验的租户（ENFORCE 切换保护的参照）。
	 */
	private final Set<String> admittedTenantIds = ConcurrentHashMap.newKeySet();

	/**
	 * 评估指定租户能否进入 ENFORCE 白名单（只读，不登记）。
	 *
	 * @param tenantId 租户ID（与 enforceTenantIds 同口径）
	 * @return 评估结果（含结论码与完整证据链），永不返回 null
	 */
	public EnforceGateEvaluation evaluate(String tenantId) {
		String tenant = requireTenantId(tenantId);
		DataAgentProperties.Authorization.EnforceGate gate = gateConfig();
		if (!gate.isEnabled()) {
			return rejected(tenant, EnforceGateDecision.REJECTED_GATE_DISABLED, null);
		}
		ShadowDiffReport report = reportGenerator.latestReport();
		if (report == null) {
			return rejected(tenant, EnforceGateDecision.REJECTED_REPORT_MISSING, null);
		}
		ShadowDiffReport.TenantSummary summary = findTenantSummary(report, tenant);
		if (summary == null) {
			return rejected(tenant, EnforceGateDecision.REJECTED_TENANT_MISSING, report);
		}
		if (summary.getComparable() < gate.getMinComparable()) {
			return rejected(tenant, EnforceGateDecision.REJECTED_INSUFFICIENT_SAMPLES, report);
		}
		if (!summary.isEnforceGatePassed()) {
			return rejected(tenant, EnforceGateDecision.REJECTED_THRESHOLD_EXCEEDED, report);
		}
		return EnforceGateEvaluation.builder()
			.tenantId(tenant)
			.allowed(true)
			.decision(EnforceGateDecision.ADMITTED)
			.reason(EnforceGateDecision.ADMITTED.getLabel())
			.reportWindowFrom(report.getWindowFrom())
			.reportWindowTo(report.getWindowTo())
			.comparable(summary.getComparable())
			.matched(summary.getMatched())
			.mismatched(summary.getMismatched())
			.originalOnly(summary.getOriginalOnly())
			.mismatchRate(summary.getMismatchRate())
			.enforceGateThreshold(report.getEnforceGateThreshold())
			.reportGatePassed(summary.isEnforceGatePassed())
			.build();
	}

	/**
	 * 门禁放行登记（灰度操作手册核心动作）：评估未通过 fail-closed 抛 {@link CheckedException}，
	 * 通过后写入进程级登记簿供切换保护比对；重复登记幂等（重新评估后覆盖登记）。
	 *
	 * @param tenantId 租户ID
	 * @return 放行评估结果（证据链）
	 */
	public EnforceGateEvaluation admitTenant(String tenantId) {
		String tenant = requireTenantId(tenantId);
		EnforceGateEvaluation evaluation = evaluate(tenant);
		if (!evaluation.isAllowed()) {
			log.warn("ENFORCE 门禁拒绝放行租户. tenantId={}, decision={}, reason={}", tenant,
					evaluation.getDecision().getCode(), evaluation.getReason());
			throw CheckedException.badRequest("租户 {0} 未通过 ENFORCE 门禁: {1}", tenant, evaluation.getReason());
		}
		admittedTenantIds.add(tenant);
		log.info("ENFORCE 门禁放行租户登记完成. tenantId={}, mismatchRate={}, comparable={}, reportWindowTo={}",
				tenant, evaluation.getMismatchRate(), evaluation.getComparable(), evaluation.getReportWindowTo());
		return evaluation;
	}

	/**
	 * 灰度 checklist 汇总（上线操作前后各查一次）：白名单逐租户评估 + 登记簿状态 +
	 * ENFORCE 切换保护检测（静默回退 / 未验证租户）。
	 */
	public EnforceGateOverview overview() {
		DataAgentProperties.Authorization.EnforceGate gate = gateConfig();
		ShadowDiffReport report = reportGenerator.latestReport();
		List<String> whitelist = List.copyOf(pepProperties.getEnforceTenantIds());

		List<EnforceGateEvaluation> evaluations = whitelist.stream().map(this::evaluate).toList();
		Set<String> admitted = Set.copyOf(admittedTenantIds);

		Set<String> unverified = new LinkedHashSet<>();
		for (String tenant : whitelist) {
			if (!admitted.contains(StrUtil.trim(tenant))) {
				unverified.add(tenant);
			}
		}
		Set<String> silentRollbacks = new TreeSet<>();
		for (String tenant : admitted) {
			if (!pepProperties.resolveMode(tenant).enforce()) {
				silentRollbacks.add(tenant);
			}
		}
		boolean integrityPassed = unverified.isEmpty() && silentRollbacks.isEmpty();

		return EnforceGateOverview.builder()
			.gateEnabled(gate.isEnabled())
			.minComparable(gate.getMinComparable())
			.reportPresent(report != null)
			.reportGeneratedAt(report == null ? null : report.getGeneratedAt())
			.enforceTenantIds(whitelist)
			.admittedTenantIds(new ArrayList<>(new TreeSet<>(admitted)))
			.tenantEvaluations(evaluations)
			.unverifiedEnforcedTenantIds(new ArrayList<>(unverified))
			.silentRollbackTenantIds(new ArrayList<>(silentRollbacks))
			.rolloutIntegrityPassed(integrityPassed)
			.integrityActionRequired(integrityPassed ? null : buildIntegrityActions(unverified, silentRollbacks))
			.decisionCounts(countDecisions(evaluations))
			.build();
	}

	/**
	 * ENFORCE 切换保护断言（checklist 硬校验，供脚本/CI 调用）：存在静默回退或未验证的
	 * ENFORCE 租户即抛 {@link CheckedException}，阻止「已 ENFORCE 租户静默回退」。
	 */
	public void assertRolloutIntegrity() {
		EnforceGateOverview overview = overview();
		if (overview.isRolloutIntegrityPassed()) {
			log.debug("ENFORCE 灰度切换保护校验通过. enforceTenantIds={}, admittedTenantIds={}",
					overview.getEnforceTenantIds(), overview.getAdmittedTenantIds());
			return;
		}
		log.warn("ENFORCE 灰度切换保护校验未通过. unverified={}, silentRollback={}",
				overview.getUnverifiedEnforcedTenantIds(), overview.getSilentRollbackTenantIds());
		throw CheckedException.badRequest("ENFORCE 灰度切换保护校验未通过: {0}", overview.getIntegrityActionRequired());
	}

	/**
	 * 门禁配置视图（Authorization.enforceGate 恒有默认实例，防空指针）。
	 */
	private DataAgentProperties.Authorization.EnforceGate gateConfig() {
		DataAgentProperties.Authorization authorization = dataAgentProperties.getAuthorization();
		if (authorization == null || authorization.getEnforceGate() == null) {
			return new DataAgentProperties.Authorization.EnforceGate();
		}
		return authorization.getEnforceGate();
	}

	/**
	 * 租户ID卫语句：空白即参数错误（fail-fast，不进入门禁矩阵）。
	 */
	private String requireTenantId(String tenantId) {
		if (StrUtil.isBlank(tenantId)) {
			throw CheckedException.badRequest("租户ID不能为空, ENFORCE 门禁评估需要明确的租户");
		}
		return tenantId.trim();
	}

	/**
	 * 报告租户段定位（tenantId 与报告 TenantSummary 同为字符串口径）。
	 */
	private ShadowDiffReport.TenantSummary findTenantSummary(ShadowDiffReport report, String tenantId) {
		if (CollUtil.isEmpty(report.getTenants())) {
			return null;
		}
		return report.getTenants()
			.stream()
			.filter(summary -> tenantId.equals(summary.getTenantId()))
			.findFirst()
			.orElse(null);
	}

	/**
	 * 拒绝评估（fail-closed 统一构造；报告可用时附证据链便于定位）。
	 */
	private EnforceGateEvaluation rejected(String tenantId, EnforceGateDecision decision, ShadowDiffReport report) {
		ShadowDiffReport.TenantSummary summary = report == null ? null : findTenantSummary(report, tenantId);
		return EnforceGateEvaluation.builder()
			.tenantId(tenantId)
			.allowed(false)
			.decision(decision)
			.reason(decision.getLabel())
			.reportWindowFrom(report == null ? null : report.getWindowFrom())
			.reportWindowTo(report == null ? null : report.getWindowTo())
			.comparable(summary == null ? 0L : summary.getComparable())
			.matched(summary == null ? 0L : summary.getMatched())
			.mismatched(summary == null ? 0L : summary.getMismatched())
			.originalOnly(summary == null ? 0L : summary.getOriginalOnly())
			.mismatchRate(summary == null ? 0D : summary.getMismatchRate())
			.enforceGateThreshold(report == null ? ShadowDiffReportGenerator.ENFORCE_GATE_THRESHOLD
					: report.getEnforceGateThreshold())
			.reportGatePassed(summary != null && summary.isEnforceGatePassed())
			.build();
	}

	/**
	 * 切换保护整改说明（中文逐条，checklist 输出与异常共用同一文案源）。
	 */
	private String buildIntegrityActions(Set<String> unverified, Set<String> silentRollbacks) {
		StringBuilder actions = new StringBuilder();
		if (CollUtil.isNotEmpty(silentRollbacks)) {
			actions.append("以下已放行租户已脱离 ENFORCE 保护构成静默回退, 须按回滚 playbook 显式确认或恢复白名单: ")
				.append(String.join(", ", silentRollbacks))
				.append("; ");
		}
		if (CollUtil.isNotEmpty(unverified)) {
			actions.append("以下 ENFORCE 租户未完成门禁放行登记, 须重建影子差异报告后逐租户重新执行 admit: ")
				.append(String.join(", ", unverified));
		}
		return actions.toString();
	}

	/**
	 * 评估结论计数（按枚举声明序稳定输出）。
	 */
	private List<EnforceGateOverview.DecisionCount> countDecisions(List<EnforceGateEvaluation> evaluations) {
		Map<EnforceGateDecision, Long> counts = new EnumMap<>(EnforceGateDecision.class);
		for (EnforceGateEvaluation evaluation : evaluations) {
			counts.merge(evaluation.getDecision(), 1L, Long::sum);
		}
		return counts.entrySet()
			.stream()
			.sorted(Comparator.comparingInt(entry -> entry.getKey().ordinal()))
			.map(entry -> EnforceGateOverview.DecisionCount.builder()
				.decision(entry.getKey())
				.count(entry.getValue())
				.build())
			.toList();
	}

}
