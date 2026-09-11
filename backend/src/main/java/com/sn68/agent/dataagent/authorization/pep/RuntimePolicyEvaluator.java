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
package com.sn68.agent.dataagent.authorization.pep;

import cn.hutool.core.util.StrUtil;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationBinding;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicyVersion;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.PolicyValidator;
import com.sn68.agent.dataagent.authorization.observability.AuthorizationMetrics;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationRequest;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationBindingMapper;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationPolicyVersionMapper;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * PEP 运行时策略串联器（PR-3c 交付物 1）。
 *
 * <p>串联链路：策略绑定解析（带 bind_revision 失效的进程内缓存，即"释放缓存（revision 比对）"）
 * → PR-3a 冻结求值器 {@link AuthorizationPolicyEvaluator#evaluate} 求值（三参重载：数字员工 owner
 * 以 PUBLISHED Release 快照为策略绑定能力版本源，PR-3d）→ 原因码原样传播 →
 * IAM auth_revision 比对结论附挂。PR-3a 冻结类零改动：求值顺序、原因码语义、computeHash 算法全部复用。</p>
 *
 * <p>异常语义：绑定缺失/版本不可用 → policy=null 交由冻结求值器按 MISSING_POLICY 求值（不抛错）；
 * 策略 JSON 损坏属于管理数据完整性问题，本类显式抛出 IllegalArgumentException 由接缝侧决定
 * SHADOW（quiet + log.warn）或 ENFORCE（fail-closed 上抛）；数字员工快照解析失败（未部署/
 * RETIRED/摘要篡改，PR-3d）同样显式上抛，由接缝侧按模式裁决。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimePolicyEvaluator {

	/**
	 * 策略缓存条目上限：超出后新键直接绕过缓存（防无界增长；命中已有键仍复用）。
	 */
	private static final int MAX_CACHE_ENTRIES = 2048;

	private final AgentAuthorizationBindingMapper bindingMapper;

	private final AgentAuthorizationPolicyVersionMapper versionMapper;

	private final AuthorizationPolicyEvaluator policyEvaluator;

	private final PepAuthorizationProperties properties;

	// PR-3d：数字员工 owner 的策略绑定能力版本源（PUBLISHED Release 快照事实源）。
	private final EmployeeReleaseSnapshotResolver employeeSnapshotResolver;

	// PR-10 指标挂点：评估耗时/异常旁路计时（挂点是本方法唯一汇聚口，覆盖全部场景）
	private final AuthorizationMetrics metrics;

	/**
	 * 策略解析缓存：key = tenant|ownerType|ownerId|environment，value = (bindRevision, policy)。
	 * bind_revision 变化即视为缓存失效（CAS 切换策略版本后下次求值自动重载）。
	 */
	private final ConcurrentHashMap<String, CachedPolicy> policyCache = new ConcurrentHashMap<>();

	/**
	 * 求值入口：解析租户生效模式 → 解析绑定策略（缓存 + bindRevision 失效）→ 冻结求值器求值
	 * → 附加 revision 比对结论。
	 *
	 * @param context PEP 决策上下文
	 * @return 决策结果（永不返回 null；decision 字段同样非 null）
	 */
	public PepDecisionResult evaluate(PepDecisionContext context) {
		long startNanos = System.nanoTime();
		PepAuthorizationMode mode = properties.resolveMode(context.getTenantId());
		try {
			return doEvaluate(context, mode, startNanos);
		}
		catch (RuntimeException ex) {
			// 异常原样上抛（SHADOW 由接缝侧 quiet、ENFORCE 失败关闭），仅旁路计数
			metrics.recordEvaluationError(mode.getCode(), System.nanoTime() - startNanos);
			throw ex;
		}
	}

	/**
	 * PR-10 拆分：原求值方法体平移至本私有方法（决策语义与求值顺序零变化），
	 * 外层 evaluate 仅旁路计时（耗时/异常计数），不影响决策结果。
	 */
	private PepDecisionResult doEvaluate(PepDecisionContext context, PepAuthorizationMode mode, long startNanos) {
		ResolvedPolicy resolved = resolvePolicy(context);
		AuthorizationRequest pdpRequest = AuthorizationRequest.builder()
			.subjectKind(context.getSubjectKind())
			.capabilityCode(context.getCapabilityCode())
			.action(context.getAction())
			.capabilityVersion(context.getCapabilityVersion())
			.iamAvailable(context.getIamAvailable() == null || context.getIamAvailable())
			.build();
		// PR-3d 三参接线：数字员工 owner 以 Release 快照固定策略绑定能力版本（授权第 6 层只收窄）；
		// 其余 owner 返回 null，PDP 内"任一为空跳过比对"，现网 DataAgent 行为不变。
		AuthorizationDecision decision = policyEvaluator.evaluate(resolved.policy(), pdpRequest,
				resolvePolicyBoundCapabilityVersion(context));
		PepDecisionResult result = PepDecisionResult.builder()
			.decisionId(newDecisionId())
			.subjectKind(context.getSubjectKind())
			.decision(decision)
			.policyVersionId(resolved.policyVersionId())
			.bindRevision(resolved.bindRevision())
			.revisionMatched(matchRevision(context))
			.effectiveMode(mode)
			.evaluatedAt(Instant.now())
			.build();
		// PR-10 指标挂点（旁路）：评估耗时按模式维度计时
		metrics.recordEvaluationDuration(mode.getCode(), System.nanoTime() - startNanos);
		return result;
	}

	/**
	 * 策略绑定解析（缓存 + bind_revision 失效比对）。
	 *
	 * @param context 决策上下文
	 * @return 解析结果：绑定缺失或版本不可用时 policy 为 null（MISSING_POLICY 路径）
	 */
	private ResolvedPolicy resolvePolicy(PepDecisionContext context) {
		if (context.getOwnerType() == null || context.getOwnerId() == null
				|| StrUtil.isBlank(context.getTenantId())) {
			return new ResolvedPolicy(null, null, null);
		}
		String cacheKey = String.join("|", context.getTenantId().trim(), context.getOwnerType().getCode(),
				String.valueOf(context.getOwnerId()), context.getEnvironment().getCode());
		AgentAuthorizationBinding binding = bindingMapper.findBinding(context.getOwnerType(), context.getOwnerId(),
				context.getEnvironment(), context.getTenantId());
		if (binding == null) {
			// 绑定已删除：清理残留缓存，避免旧策略继续参与影子判定
			policyCache.remove(cacheKey);
			return new ResolvedPolicy(null, null, null);
		}
		CachedPolicy cached = policyCache.get(cacheKey);
		if (cached != null && Objects.equals(cached.bindRevision(), binding.getBindRevision())) {
			return new ResolvedPolicy(cached.policy(), binding.getPolicyVersionId(), binding.getBindRevision());
		}
		AgentAuthorizationPolicyVersion version = versionMapper.findByIdAndTenant(binding.getPolicyVersionId(),
				context.getTenantId());
		if (version == null || !Boolean.TRUE.equals(version.getPublished())) {
			log.warn("PEP 策略绑定指向的版本不可用, 按未绑定策略求值. ownerType={}, ownerId={}, environment={}, "
					+ "versionId={}", context.getOwnerType(), context.getOwnerId(), context.getEnvironment(),
					binding.getPolicyVersionId());
			policyCache.remove(cacheKey);
			return new ResolvedPolicy(null, binding.getPolicyVersionId(), binding.getBindRevision());
		}
		AuthorizationPolicy policy = PolicyValidator.validateAndParse(version.getPolicyJson());
		if (policyCache.size() < MAX_CACHE_ENTRIES) {
			policyCache.put(cacheKey, new CachedPolicy(binding.getBindRevision(), policy));
		}
		return new ResolvedPolicy(policy, binding.getPolicyVersionId(), binding.getBindRevision());
	}

	/**
	 * PR-3d：解析策略绑定的能力版本（PDP 三参 evaluate 第三参）。
	 *
	 * <p>仅数字员工 owner 参与 Release 快照版本固定（快照事实源，匹配语义见
	 * {@link EmployeeReleaseSnapshot#matchCapabilityVersion}）；DATA_AGENT / 无 owner 场景
	 * 返回 null，PDP 跳过版本比对，现网行为不变。数字员工解析失败（未部署/RETIRED/摘要篡改）
	 * 显式上抛：SHADOW 由接缝侧 quiet，ENFORCE 失败关闭。</p>
	 */
	private String resolvePolicyBoundCapabilityVersion(PepDecisionContext context) {
		if (context.getOwnerType() != AuthorizationOwnerType.DIGITAL_EMPLOYEE || context.getOwnerId() == null
				|| StrUtil.isBlank(context.getTenantId())) {
			return null;
		}
		String environment = context.getEnvironment() == null ? AuthorizationEnvironment.PRODUCTION.getCode()
				: context.getEnvironment().getCode();
		EmployeeReleaseSnapshot snapshot = employeeSnapshotResolver.resolveActive(context.getTenantId().trim(),
				context.getOwnerId(), environment);
		return snapshot.matchCapabilityVersion(context.getCapabilityVersion());
	}

	/**
	 * IAM auth_revision 比对：两侧都非空才比对；不一致只标记不拒绝（ENFORCE 消费方按
	 * WAITING_AUTH 触发重签发，SHADOW 供影子日志观测），不新增 PDP 原因码（契约冻结）。
	 */
	private Boolean matchRevision(PepDecisionContext context) {
		if (context.getExpectedAuthRevision() == null || context.getCurrentAuthRevision() == null) {
			return null;
		}
		return Objects.equals(context.getExpectedAuthRevision(), context.getCurrentAuthRevision());
	}

	/**
	 * 决策ID：UUID 标准形式，作为影子日志 event_key 与 invocation 审计列的贯穿标识。
	 */
	private String newDecisionId() {
		return UUID.randomUUID().toString();
	}

	/**
	 * 已解析策略（policy 为 null 表示未绑定，交冻结求值器按 MISSING_POLICY 求值）。
	 */
	private record ResolvedPolicy(AuthorizationPolicy policy, Long policyVersionId, Long bindRevision) {
	}

	/**
	 * 缓存条目：bind_revision 与解析后策略的不可变对。
	 */
	private record CachedPolicy(Long bindRevision, AuthorizationPolicy policy) {
	}

}
