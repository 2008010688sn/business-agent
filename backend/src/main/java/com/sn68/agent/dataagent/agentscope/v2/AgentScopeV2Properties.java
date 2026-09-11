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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.constant.Constant;
import java.time.Duration;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * AgentScope 2.0 Harness 运行时配置。Bean 常驻，ReAct 一律 Harness；回滚只能回退版本。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = AgentScopeV2Properties.PREFIX)
public class AgentScopeV2Properties {

	public static final String PREFIX = Constant.PROJECT_PROPERTIES_PREFIX + ".agentscope.v2";

	/**
	 * v2 Redis 键前缀。与 1.0 {@code data_agentscope_session_state} 无前缀模块名隔离。
	 */
	private String redisKeyPrefix = "as2:";

	/**
	 * v2 Redis 会话 TTL。PG 备份无 TTL。
	 */
	private Duration redisTtl = Duration.ofDays(7);

	public static final int DEFAULT_MAX_ITERS = 16;

	public static final Duration DEFAULT_MAX_DURATION = Duration.ofSeconds(180);

	/**
	 * 与平台 {@code spring.ai.agent.runtime.max-prompt-tokens} 默认 5000000 对齐。
	 * 信封按本轮提问 input+output 累计（含文件关联分析），不是整段会话寿命。
	 */
	public static final long DEFAULT_MAX_TOKENS = 5_000_000L;

	/**
	 * 单次 run 费用上限，单位 millicents（$0.00001）。按 $5/1M 与
	 * {@link #DEFAULT_MAX_TOKENS} 对齐：5000000 tokens = 2500000 millicents。
	 */
	public static final long DEFAULT_MAX_COST = 2_500_000L;

	public static final int DEFAULT_FINGERPRINT_REPEAT_LIMIT = 3;

	/**
	 * 四轴预算：轮次上限。父 run 与子代理共用同一信封。
	 */
	private int maxIters = DEFAULT_MAX_ITERS;

	/**
	 * 四轴预算：墙钟时长上限。与 {@link com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline} 取更紧者。
	 */
	private Duration maxDuration = DEFAULT_MAX_DURATION;

	/**
	 * 四轴预算：累计 token 上限（input+output，含子代理）。
	 */
	private long maxTokens = DEFAULT_MAX_TOKENS;

	/**
	 * 四轴预算：累计费用上限，单位 millicents。无独立定价表，按保守 token 费率估算。
	 */
	private long maxCost = DEFAULT_MAX_COST;

	/**
	 * 相同工具+规范化参数指纹允许的次数，超过则可见地停流。默认 3。
	 */
	private int fingerprintRepeatLimit = DEFAULT_FINGERPRINT_REPEAT_LIMIT;

	/**
	 * 三层上下文预算（源上裁剪 → 工具结果驱逐 → 压缩）与压缩摘要模型配置。
	 */
	private final ContextGovernance contextGovernance = new ContextGovernance();

	public String resolvedRedisKeyPrefix() {
		String prefix = StringUtils.hasText(redisKeyPrefix) ? redisKeyPrefix.trim() : "as2:";
		return prefix.endsWith(":") ? prefix : prefix + ":";
	}

	public int resolvedMaxIters() {
		return maxIters > 0 ? maxIters : DEFAULT_MAX_ITERS;
	}

	public Duration resolvedMaxDuration() {
		return maxDuration == null || maxDuration.isZero() || maxDuration.isNegative() ? DEFAULT_MAX_DURATION
				: maxDuration;
	}

	public long resolvedMaxTokens() {
		return maxTokens > 0L ? maxTokens : DEFAULT_MAX_TOKENS;
	}

	public long resolvedMaxCost() {
		return maxCost > 0L ? maxCost : DEFAULT_MAX_COST;
	}

	public int resolvedFingerprintRepeatLimit() {
		return fingerprintRepeatLimit > 0 ? fingerprintRepeatLimit : DEFAULT_FINGERPRINT_REPEAT_LIMIT;
	}

	/**
	 * 三层上下文预算：NL2SQL 单份 schema dump 实测 15KB、一轮工具结果约 46KB，全文进上下文
	 * 会挤占模型窗口并推高时延与费用。第一层在工具结果进入上下文前源上裁剪；第二层由框架
	 * 把超限旧结果驱逐成预览；第三层在窗口压力下压缩历史。三层参数均可按需调整。
	 */
	@Getter
	@Setter
	public static class ContextGovernance {

		public static final int DEFAULT_TOOL_RESULT_MAX_CHARS = 8_000;

		public static final int DEFAULT_TOOL_RESULT_HEAD_KEEP_CHARS = 6_000;

		/**
		 * 只读工具白名单：仅这些工具允许同请求内结果缓存。写/执行类工具绝不进入该名单，
		 * 重复执行会改变状态。query_clarify.check 为澄清检查的规范名（与
		 * AgentRuntimeToolMetrics/AnswerTraceExplainStore 一致），当前无真实注册回调，预留。
		 */
		public static final Set<String> DEFAULT_RESULT_CACHEABLE_TOOLS = Set.of("datasource_skill_search",
				"semantic_model_search", "domain_business_knowledge_search", "sql_guard_check", "query_clarify.check");

		public static final int DEFAULT_RESULT_CACHE_MAX_ENTRIES = 32;

		public static final int DEFAULT_TOOL_RESULT_EVICTION_MAX_CHARS = 8_000;

		public static final int DEFAULT_TOOL_RESULT_EVICTION_PREVIEW_CHARS = 600;

		public static final int DEFAULT_COMPACTION_TRIGGER_TOKENS = 20_000;

		public static final int DEFAULT_COMPACTION_TRIGGER_MESSAGES = 48;

		public static final int DEFAULT_COMPACTION_KEEP_MESSAGES = 8;

		public static final int DEFAULT_COMPACTION_KEEP_TOKENS = 8_000;

		public static final int DEFAULT_COMPACTION_RESERVED = 2_000;

		/**
		 * 工具结果进上下文的单条上限，超限源上裁剪：JSON 结果按结构截前 N 个元素，
		 * 解析失败退化为保留头部。
		 */
		private int toolResultMaxChars = DEFAULT_TOOL_RESULT_MAX_CHARS;

		/**
		 * 源上裁剪保留的头部字符数，JSON 结构化裁剪不可用时兜底。
		 */
		private int toolResultHeadKeepChars = DEFAULT_TOOL_RESULT_HEAD_KEEP_CHARS;

		/**
		 * 是否启用同请求内工具结果缓存。命中直接复用上次回给模型的最终字符串并跳过底层工具执行；
		 * 背景：一轮内模型会对只读工具原参重试（如 no_match 后原参重试、同参第 3 次调用），
		 * 每多一轮实测约 20s。缓存随请求级 Toolkit 每请求重建，不跨请求共享，写工具绝不缓存。
		 */
		private boolean resultCacheEnabled = true;

		/**
		 * 结果缓存最大条目数，超限按插入序淘汰最旧。
		 */
		private int resultCacheMaxEntries = DEFAULT_RESULT_CACHE_MAX_ENTRIES;

		/**
		 * 可缓存的只读工具白名单，逗号分隔配置绑定。
		 */
		private Set<String> resultCacheableTools = DEFAULT_RESULT_CACHEABLE_TOOLS;

		/**
		 * 是否启用框架工具结果驱逐。驱逐是压缩之外的第二道防线，超限旧结果只留预览。
		 */
		private boolean toolResultEvictionEnabled = true;

		/**
		 * 框架驱逐的单条结果字符上限。
		 */
		private int toolResultEvictionMaxChars = DEFAULT_TOOL_RESULT_EVICTION_MAX_CHARS;

		/**
		 * 框架驱逐后保留的预览字符数。
		 */
		private int toolResultEvictionPreviewChars = DEFAULT_TOOL_RESULT_EVICTION_PREVIEW_CHARS;

		/**
		 * 是否启用框架压缩。关闭后长会话不再自动摘要。
		 */
		private boolean compactionEnabled = true;

		/**
		 * 压缩触发 token 阈值：单次上下文接近该值才压，避免小会话频繁摘要。
		 */
		private int compactionTriggerTokens = DEFAULT_COMPACTION_TRIGGER_TOKENS;

		/**
		 * 压缩触发消息数阈值：与 triggerTokens 任一满足即触发。
		 */
		private int compactionTriggerMessages = DEFAULT_COMPACTION_TRIGGER_MESSAGES;

		/**
		 * 压缩保留的最近消息条数。必须小于实际消息数，否则 cutoff=1 等于压不动。
		 */
		private int compactionKeepMessages = DEFAULT_COMPACTION_KEEP_MESSAGES;

		/**
		 * 压缩保留的最近消息 token 数。必须小于实际总 token，否则压不动。
		 */
		private int compactionKeepTokens = DEFAULT_COMPACTION_KEEP_TOKENS;

		/**
		 * 压缩为回复预留的 token 数。必须覆盖框架默认值，否则 32k 窗口会过早开火。
		 */
		private int compactionReserved = DEFAULT_COMPACTION_RESERVED;

		/**
		 * 压缩摘要用的小模型配置 id（model_config 主键）。空则用主模型做摘要。
		 */
		private Long compactionSummaryModelConfigId;

		public int resolvedToolResultMaxChars() {
			return toolResultMaxChars > 0 ? toolResultMaxChars : DEFAULT_TOOL_RESULT_MAX_CHARS;
		}

		public int resolvedToolResultHeadKeepChars() {
			return toolResultHeadKeepChars > 0 ? toolResultHeadKeepChars : DEFAULT_TOOL_RESULT_HEAD_KEEP_CHARS;
		}

		public boolean resolvedResultCacheEnabled() {
			return resultCacheEnabled;
		}

		public int resolvedResultCacheMaxEntries() {
			return resultCacheMaxEntries > 0 ? resultCacheMaxEntries : DEFAULT_RESULT_CACHE_MAX_ENTRIES;
		}

		public Set<String> resolvedResultCacheableTools() {
			return resultCacheableTools == null || resultCacheableTools.isEmpty() ? DEFAULT_RESULT_CACHEABLE_TOOLS
					: resultCacheableTools;
		}

		public int resolvedToolResultEvictionMaxChars() {
			return toolResultEvictionMaxChars > 0 ? toolResultEvictionMaxChars : DEFAULT_TOOL_RESULT_EVICTION_MAX_CHARS;
		}

		public int resolvedToolResultEvictionPreviewChars() {
			return toolResultEvictionPreviewChars > 0 ? toolResultEvictionPreviewChars
					: DEFAULT_TOOL_RESULT_EVICTION_PREVIEW_CHARS;
		}

		public int resolvedCompactionTriggerTokens() {
			return compactionTriggerTokens > 0 ? compactionTriggerTokens : DEFAULT_COMPACTION_TRIGGER_TOKENS;
		}

		public int resolvedCompactionTriggerMessages() {
			return compactionTriggerMessages > 0 ? compactionTriggerMessages : DEFAULT_COMPACTION_TRIGGER_MESSAGES;
		}

		public int resolvedCompactionKeepMessages() {
			return compactionKeepMessages > 0 ? compactionKeepMessages : DEFAULT_COMPACTION_KEEP_MESSAGES;
		}

		public int resolvedCompactionKeepTokens() {
			return compactionKeepTokens > 0 ? compactionKeepTokens : DEFAULT_COMPACTION_KEEP_TOKENS;
		}

		public int resolvedCompactionReserved() {
			return compactionReserved > 0 ? compactionReserved : DEFAULT_COMPACTION_RESERVED;
		}

	}

}
