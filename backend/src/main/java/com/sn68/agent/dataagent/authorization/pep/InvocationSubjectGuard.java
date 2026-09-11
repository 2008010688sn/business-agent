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

import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * PEP 空主体校验（PR-3c 交付物 4：Hook 拒绝空主体）。
 *
 * <p>平台硬基线：鉴权决策不允许在缺 tenant/subject 的上下文中产生。ENFORCE 模式下缺失即抛
 * {@link CheckedException}（消息携带 BUSINESS_DENIED 语义，供上游映射为稳定错误码）；
 * SHADOW 模式只返回缺失清单交由影子日志记录，不拦截（现网行为不变）。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Component
@RequiredArgsConstructor
public class InvocationSubjectGuard {

	/**
	 * 校验决策上下文的主体完备性。
	 *
	 * @param context      PEP 决策上下文
	 * @param effectiveMode 生效模式（由接缝侧解析后传入）
	 * @return 校验结论（缺失字段清单；compliant=true 表示主体完备）
	 */
	public SubjectCheckResult checkSubject(PepDecisionContext context, PepAuthorizationMode effectiveMode) {
		List<String> missing = new ArrayList<>();
		if (!StringUtils.hasText(context.getTenantId())) {
			missing.add("tenantId");
		}
		if (context.getSubjectKind() == null) {
			missing.add("subjectKind");
		}
		if (!StringUtils.hasText(context.getSubjectId())) {
			missing.add("subjectId");
		}
		return new SubjectCheckResult(missing.isEmpty(), missing);
	}

	/**
	 * ENFORCE 硬拒绝：缺 tenant/subject（策略求值无法归属主体）必抛。
	 *
	 * @param result 空主体校验结论
	 * @param scene  拒绝场景（日志与异常消息定位）
	 */
	public void rejectIfMissingSubject(SubjectCheckResult result, String scene) {
		if (result.isCompliant()) {
			return;
		}
		throw CheckedException.fail("PEP 空主体拒绝（BUSINESS_DENIED）：鉴权决策要求租户与请求主体完备，缺失字段="
				+ String.join(",", result.missingFields()) + "，scene=" + scene);
	}

	/**
	 * 空主体校验结论。
	 *
	 * @param compliant     是否完备
	 * @param missingFields 缺失字段名清单
	 */
	public record SubjectCheckResult(boolean compliant, List<String> missingFields) {

		/**
		 * bean 风格便捷读法（record 组件访问器为 compliant()，消费方习惯 isXxx）。
		 */
		public boolean isCompliant() {
			return compliant;
		}
	}

}
