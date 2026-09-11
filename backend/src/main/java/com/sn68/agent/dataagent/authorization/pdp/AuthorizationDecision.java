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
package com.sn68.agent.dataagent.authorization.pdp;

import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 授权决策结果（PDP 输出契约，v1 冻结）。
 *
 * <p>字段集：allowed、reasonCode、obligations、maskFields、policyHash、evaluatedAt。
 * PR-3b/PR-4 消费时不得删改既有字段语义；扩展字段（如 decisionId、authRevision）由 PR-3c 影子日志承载，
 * 不回写破坏本契约。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Getter
@Builder
public class AuthorizationDecision {

	/**
	 * 是否允许。
	 */
	private final boolean allowed;

	/**
	 * 原因码（冻结枚举）。
	 */
	private final DecisionReasonCode reasonCode;

	/**
	 * 允许时需执行的义务列表（拒绝时为空列表）。
	 */
	private final List<AuthorizationObligation> obligations;

	/**
	 * MASK_FIELDS 义务对应的敏感字段名列表；空列表表示遵循能力契约默认 sensitiveFields。
	 */
	private final List<String> maskFields;

	/**
	 * 参与求值的策略哈希（策略缺失时为 null）。
	 */
	private final String policyHash;

	/**
	 * 决策时间。
	 */
	private final Instant evaluatedAt;
}
