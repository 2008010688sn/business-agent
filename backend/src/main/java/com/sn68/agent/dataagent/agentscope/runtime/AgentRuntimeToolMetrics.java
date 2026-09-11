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
package com.sn68.agent.dataagent.agentscope.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sn68.agent.dataagent.util.SqlUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 记录单次运行中的工具调用指标，用于流式进度和诊断展示。
 */
public class AgentRuntimeToolMetrics {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	/**
	 * 只读工具入参中承载 SQL 的字段名（datasource / sqlguard 两类请求都用它）。
	 */
	private static final String SQL_INPUT_FIELD = "sql";

	private final AtomicInteger toolCount = new AtomicInteger();

	private final AtomicInteger toolFailCount = new AtomicInteger();

	private final AtomicReference<String> lastFailureMessage = new AtomicReference<>();

	private final ConcurrentSkipListMap<Integer, ToolFailureObservation> toolFailuresBySequence =
			new ConcurrentSkipListMap<>();

	private final AtomicLong toolDurationMs = new AtomicLong();

	private final AtomicInteger reactIterationCount = new AtomicInteger();

	private final AtomicBoolean maxIterationsReached = new AtomicBoolean();

	private final AtomicBoolean protocolRepairAttempted = new AtomicBoolean();

	private final AtomicInteger protocolRepairCount = new AtomicInteger();

	private final AtomicReference<AgentRuntimeTerminalOutcome> terminalOutcome =
			new AtomicReference<>(AgentRuntimeTerminalOutcome.IN_PROGRESS);

	private final AtomicReference<String> clarificationReason = new AtomicReference<>();

	private final AtomicReference<String> originalQuery = new AtomicReference<>();

	private final AtomicBoolean knowledgeNoEffectiveHit = new AtomicBoolean();

	private final AtomicBoolean semanticNoEffectiveHit = new AtomicBoolean();

	private final AtomicBoolean controlledFieldSearchStarted = new AtomicBoolean();

	private final AtomicBoolean controlledFieldSearchResolved = new AtomicBoolean();

	private final AtomicInteger modelCallCount = new AtomicInteger();

	private final AtomicInteger modelRetryCount = new AtomicInteger();

	private final AtomicLong promptTokens = new AtomicLong();

	private final AtomicLong peakPromptTokens = new AtomicLong();

	private final AtomicLong completionTokens = new AtomicLong();

	private final AtomicLong modelDurationMs = new AtomicLong();

	private final AtomicReference<String> lastFailedModelSignature = new AtomicReference<>();

	private final Map<String, Map<String, AtomicInteger>> identicalReadOnlyCalls = new ConcurrentHashMap<>();

	private final Map<String, String> lastReadOnlyResultCategory = new ConcurrentHashMap<>();

	private final int maxCompletedIdenticalCalls;

	private final int maxModelCalls;

	private final int maxToolCalls;

	private final long maxPromptTokens;

	private final AtomicBoolean emptySearchNoProgressEnabled = new AtomicBoolean();

	private final AtomicInteger emptySearchNoProgressMaxConsecutive = new AtomicInteger(2);

	private final AtomicInteger consecutiveEmptySearchCount = new AtomicInteger();

	private final AtomicReference<String> lastEmptySearchSql = new AtomicReference<>();

	/** SEARCH 执行失败（可见性 / SELECT * / EXECUTION_FAILED）连续次数；成功 SEARCH 清零。 */
	private static final int SEARCH_EXECUTION_FAIL_MAX = 2;

	private final AtomicInteger consecutiveSearchExecutionFailures = new AtomicInteger();

	public AgentRuntimeToolMetrics() {
		this(2, 0, 0, 0L);
	}

	public AgentRuntimeToolMetrics(int maxCompletedIdenticalCalls) {
		this(maxCompletedIdenticalCalls, 0, 0, 0L);
	}

	public AgentRuntimeToolMetrics(int maxCompletedIdenticalCalls, int maxModelCalls, int maxToolCalls,
			long maxPromptTokens) {
		this.maxCompletedIdenticalCalls = Math.max(1, maxCompletedIdenticalCalls);
		this.maxModelCalls = Math.max(0, maxModelCalls);
		this.maxToolCalls = Math.max(0, maxToolCalls);
		this.maxPromptTokens = Math.max(0L, maxPromptTokens);
	}

	/**
	 * 连续空 SEARCH 收口闸。默认关闭；打开后，连续 N 次 datasource SEARCH 空结果会拦截下一次 SEARCH。
	 */
	public void configureEmptySearchNoProgress(boolean enabled, int maxConsecutive) {
		emptySearchNoProgressEnabled.set(enabled);
		emptySearchNoProgressMaxConsecutive.set(Math.max(1, maxConsecutive));
	}

	/**
	 * 保存Agent运行时ToolMetrics。
	 */
	public void recordCall() {
		toolCount.incrementAndGet();
	}

	public boolean tryRecordCall() {
		while (true) {
			int current = toolCount.get();
			if (maxToolCalls > 0 && current >= maxToolCalls) {
				return false;
			}
			if (toolCount.compareAndSet(current, current + 1)) {
				return true;
			}
		}
	}

	/**
	 * 保存Agent运行时ToolMetrics。
	 */
	public void recordFailure() {
		toolFailCount.incrementAndGet();
	}

	/**
	 * 保存Agent运行时ToolMetrics。
	 */
	public void recordFailure(String message) {
		recordFailure();
		if (message != null && !message.isBlank()) {
			lastFailureMessage.set(message.trim());
		}
	}

	public void recordFailure(Integer sequenceNo, String errorCode, String message) {
		recordFailure();
		if (sequenceNo != null) {
			toolFailuresBySequence.put(sequenceNo,
					new ToolFailureObservation(sequenceNo, normalize(errorCode), normalize(message)));
			return;
		}
		if (message != null && !message.isBlank()) {
			lastFailureMessage.set(message.trim());
		}
	}

	public boolean allowDatasourceSearch(String toolName, String input) {
		if (!emptySearchNoProgressEnabled.get() || !isDatasourceSearch(toolName, input)) {
			return true;
		}
		return consecutiveEmptySearchCount.get() < emptySearchNoProgressMaxConsecutive.get();
	}

	public String emptySearchNoProgressMessage() {
		String sql = lastEmptySearchSql.get();
		if (sql == null || sql.isBlank()) {
			return "暂无数据。已按当前口径查询过，不要继续探表。";
		}
		return "暂无数据。已按当前口径查询过，不要继续探表。已用口径: " + truncateSql(sql);
	}

	/**
	 * SEARCH 连续执行失败收口。默认开启、阈值 2；空行 SEARCH 不走这里。
	 */
	public boolean allowSearchExecution(String toolName, String input) {
		if (!isDatasourceSearch(toolName, input)) {
			return true;
		}
		return consecutiveSearchExecutionFailures.get() < SEARCH_EXECUTION_FAIL_MAX;
	}

	public String searchExecutionNoProgressMessage() {
		return "当前技能表白名单无法按已有键继续探表，请基于已有结果或材料作答，不要继续探表。";
	}

	public boolean searchExecutionWrappedUp() {
		ToolFailureObservation last = lastFailure();
		return last != null && "NO_PROGRESS_SEARCH_FAILED".equals(last.errorCode());
	}

	public void recordSearchExecutionOutcome(String toolName, String input, boolean executionFailed) {
		if (!isDatasourceSearch(toolName, input)) {
			return;
		}
		if (executionFailed) {
			consecutiveSearchExecutionFailures.incrementAndGet();
			return;
		}
		consecutiveSearchExecutionFailures.set(0);
	}

	public boolean allowReadOnlyCall(String toolName, String input) {
		if (!isReadOnlyTool(toolName)) {
			return true;
		}
		String baseKey = baseKey(toolName, input);
		Map<String, AtomicInteger> categories = identicalReadOnlyCalls.get(baseKey);
		if (categories == null) {
			return true;
		}
		String lastCategory = lastReadOnlyResultCategory.get(baseKey);
		if (lastCategory == null) {
			return true;
		}
		AtomicInteger count = categories.get(lastCategory);
		return count == null || count.get() < maxCompletedIdenticalCalls;
	}

	public void recordReadOnlyCallCompleted(String toolName, String input) {
		recordReadOnlyCallCompleted(toolName, input, "COMPLETED");
	}

	public void recordReadOnlyCallCompleted(String toolName, String input, String resultCategory) {
		if (!isReadOnlyTool(toolName)) {
			return;
		}
		String baseKey = baseKey(toolName, input);
		String category = resultCategory == null ? "COMPLETED" : resultCategory;
		lastReadOnlyResultCategory.put(baseKey, category);
		identicalReadOnlyCalls.computeIfAbsent(baseKey, ignored -> new ConcurrentHashMap<>())
			.computeIfAbsent(category, ignored -> new AtomicInteger())
			.incrementAndGet();
	}

	public boolean allowReadOnlyCall(String toolName, String input, String resultCategory) {
		if (!isReadOnlyTool(toolName)) {
			return true;
		}
		Map<String, AtomicInteger> categories = identicalReadOnlyCalls.get(baseKey(toolName, input));
		AtomicInteger count = categories == null ? null
				: categories.get(resultCategory == null ? "COMPLETED" : resultCategory);
		return count == null || count.get() < maxCompletedIdenticalCalls;
	}

	private String baseKey(String toolName, String input) {
		return toolName + "\n" + canonicalJson(input);
	}

	private String canonicalJson(String input) {
		if (input == null || input.isBlank()) {
			return "";
		}
		try {
			return canonicalNode(OBJECT_MAPPER.readTree(input)).toString();
		}
		catch (Exception ignored) {
			return input.trim().replaceAll("\\s+", " ");
		}
	}

	private JsonNode canonicalNode(JsonNode node) {
		if (node == null || !node.isObject()) {
			if (node != null && node.isArray()) {
				var array = OBJECT_MAPPER.createArrayNode();
				node.forEach(item -> array.add(canonicalNode(item)));
				return array;
			}
			return node;
		}
		ObjectNode object = OBJECT_MAPPER.createObjectNode();
		ArrayList<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		Collections.sort(names);
		for (String name : names) {
			object.set(name, canonicalField(name, node.get(name)));
		}
		return object;
	}

	/**
	 * SQL 入参先做语法树级规范化再参与 key 计算。逐字节比较时，模型只要多打一个空格、
	 * 换一次关键字大小写重发同一条 SQL，就会被算成一次全新的调用，无进展保护从 0 重新计数。
	 */
	private JsonNode canonicalField(String fieldName, JsonNode value) {
		if (SQL_INPUT_FIELD.equalsIgnoreCase(fieldName) && value != null && value.isTextual()) {
			return TextNode.valueOf(SqlUtil.canonicalizeForComparison(value.asText()));
		}
		return canonicalNode(value);
	}

	public void recordDuration(long durationMs) {
		toolDurationMs.addAndGet(Math.max(0L, durationMs));
	}

	/**
	 * 处理Agent运行时ToolMetrics。
	 */
	public int toolCount() {
		return toolCount.get();
	}

	/**
	 * 处理Agent运行时ToolMetrics。
	 */
	public int toolFailCount() {
		return toolFailCount.get();
	}

	/**
	 * 处理Agent运行时ToolMetrics。
	 */
	public String lastFailureMessage() {
		Map.Entry<Integer, ToolFailureObservation> sequencedFailure = toolFailuresBySequence.lastEntry();
		return sequencedFailure == null ? lastFailureMessage.get() : sequencedFailure.getValue().message();
	}

	public ToolFailureObservation lastFailure() {
		Map.Entry<Integer, ToolFailureObservation> sequencedFailure = toolFailuresBySequence.lastEntry();
		return sequencedFailure == null ? null : sequencedFailure.getValue();
	}

	public long toolDurationMs() {
		return toolDurationMs.get();
	}

	public void recordReactIteration(int maxIterations) {
		int count = reactIterationCount.incrementAndGet();
		if (maxIterations > 0 && count >= maxIterations) {
			maxIterationsReached.set(true);
		}
	}

	public int reactIterationCount() {
		return reactIterationCount.get();
	}

	public boolean maxIterationsReached() {
		return maxIterationsReached.get();
	}

	public boolean tryStartProtocolRepair(int maxIterations, AgentRuntimeDeadline deadline, Duration finishBuffer) {
		if (reactIterationCount.get() >= Math.max(1, maxIterations)) {
			return false;
		}
		if (deadline != null && !deadline.canStart(finishBuffer)) {
			return false;
		}
		if (!protocolRepairAttempted.compareAndSet(false, true)) {
			return false;
		}
		protocolRepairCount.incrementAndGet();
		return true;
	}

	public boolean protocolRepairAttempted() {
		return protocolRepairAttempted.get();
	}

	public int protocolRepairCount() {
		return protocolRepairCount.get();
	}

	public void recordTerminalOutcome(AgentRuntimeTerminalOutcome outcome) {
		if (outcome != null) {
			terminalOutcome.compareAndSet(AgentRuntimeTerminalOutcome.IN_PROGRESS, outcome);
		}
	}

	public AgentRuntimeTerminalOutcome terminalOutcome() {
		return terminalOutcome.get();
	}

	public void recordClarificationReason(String reason) {
		if (reason != null && !reason.isBlank()) {
			clarificationReason.compareAndSet(null, reason.trim());
		}
	}

	public String clarificationReason() {
		return clarificationReason.get();
	}

	public void setOriginalQuery(String query) {
		if (query != null && !query.isBlank()) {
			originalQuery.compareAndSet(null, query.trim());
		}
	}

	public boolean allowControlledFieldSearch(String toolName, String input) {
		if (!isControlledFieldSearch(toolName, input) || !unknownBusinessTermExplorationReady()
				|| controlledFieldSearchResolved.get()) {
			return true;
		}
		if (controlledFieldSearchStarted.compareAndSet(false, true)) {
			return true;
		}
		recordClarificationReason("UNKNOWN_BUSINESS_TERM_UNRESOLVED");
		return false;
	}

	public void recordToolResult(String toolName, String input, String result) {
		recordEmptySearchResult(toolName, input, result);
		if (toolName == null || result == null || result.isBlank() || hasExplicitBusinessDimension()) {
			return;
		}
		try {
			JsonNode resultNode = OBJECT_MAPPER.readTree(result);
			if (AgentModelToolName.isKnowledgeTool(toolName)) {
				knowledgeNoEffectiveHit.set(!hasRelevantKnowledgeHit(input, resultNode));
				return;
			}
			if (AgentModelToolName.isSemanticModelTool(toolName)) {
				semanticNoEffectiveHit.set(isNoMatch(resultNode));
				return;
			}
			if (isControlledFieldSearch(toolName, input) && controlledFieldSearchStarted.get()) {
				if (resultNode.path("emptyResult").asBoolean(false)) {
					recordClarificationReason("UNKNOWN_BUSINESS_TERM_UNRESOLVED");
				}
				else {
					controlledFieldSearchResolved.set(true);
				}
			}
		}
		catch (Exception ignored) {
			// Only structured, trusted tool results participate in the clarification gate.
		}
	}

	private boolean unknownBusinessTermExplorationReady() {
		return !hasExplicitBusinessDimension() && knowledgeNoEffectiveHit.get() && semanticNoEffectiveHit.get();
	}

	private boolean hasExplicitBusinessDimension() {
		String query = originalQuery.get();
		if (query == null || query.isBlank()) {
			return false;
		}
		return query.matches("(?s).*(项目(?:名|名称)?|来源类型|业务类型)\\s*(?:为|是|=|:|：)\\s*[^\\s，。；;]{2,}.*");
	}

	private boolean hasRelevantKnowledgeHit(String input, JsonNode resultNode) {
		if (isNoMatch(resultNode)) {
			return false;
		}
		JsonNode hits = resultNode.path("hits");
		if (!hits.isArray() || hits.isEmpty()) {
			return false;
		}
		String candidate = knowledgeCandidate(input);
		if (candidate.isBlank()) {
			return true;
		}
		String hitText = hits.toString().toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。；：！？、]+", "");
		return hitText.contains(candidate);
	}

	private String knowledgeCandidate(String input) {
		try {
			String query = OBJECT_MAPPER.readTree(input == null ? "{}" : input).path("query").asText("");
			return query.toLowerCase(Locale.ROOT)
				.replaceAll("(请问|帮我|查一下|查询|查看|看看|关于|相关|是什么|是什么意思|什么意思|订单|数据|业务|一下|的)", "")
				.replaceAll("[\\s\\p{Punct}，。；：！？、]+", "");
		}
		catch (Exception ignored) {
			return "";
		}
	}

	private boolean isNoMatch(JsonNode resultNode) {
		return resultNode == null || "no_match".equalsIgnoreCase(resultNode.path("resolution").asText())
				|| !resultNode.path("hits").isArray() || resultNode.path("hits").isEmpty();
	}

	private void recordEmptySearchResult(String toolName, String input, String result) {
		if (!emptySearchNoProgressEnabled.get() || !isDatasourceSearch(toolName, input)) {
			return;
		}
		if (isEmptySearchResult(result)) {
			consecutiveEmptySearchCount.incrementAndGet();
			lastEmptySearchSql.set(extractSearchSql(input, result));
			return;
		}
		consecutiveEmptySearchCount.set(0);
	}

	private boolean isDatasourceSearch(String toolName, String input) {
		if (!AgentModelToolName.isDatasourceTool(toolName) || input == null || input.isBlank()) {
			return false;
		}
		try {
			return "SEARCH".equalsIgnoreCase(OBJECT_MAPPER.readTree(input).path("action").asText());
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private boolean isEmptySearchResult(String result) {
		if (result == null || result.isBlank()) {
			return false;
		}
		try {
			JsonNode resultNode = OBJECT_MAPPER.readTree(result);
			if (resultNode.path("emptyResult").asBoolean(false)) {
				return true;
			}
			JsonNode rows = resultNode.path("rows");
			return rows.isArray() && rows.isEmpty() && resultNode.path("returnedRows").asInt(-1) == 0;
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private String extractSearchSql(String input, String result) {
		String fromResult = readSqlField(result);
		if (!fromResult.isBlank()) {
			return fromResult;
		}
		return readSqlField(input);
	}

	private String readSqlField(String json) {
		if (json == null || json.isBlank()) {
			return "";
		}
		try {
			return OBJECT_MAPPER.readTree(json).path("sql").asText("");
		}
		catch (Exception ignored) {
			return "";
		}
	}

	private String truncateSql(String sql) {
		String compact = sql.trim().replaceAll("\\s+", " ");
		if (compact.length() <= 400) {
			return compact;
		}
		return compact.substring(0, 400) + "…";
	}

	private boolean isControlledFieldSearch(String toolName, String input) {
		if (!AgentModelToolName.isDatasourceTool(toolName) || input == null || input.isBlank()) {
			return false;
		}
		try {
			JsonNode request = OBJECT_MAPPER.readTree(input);
			if (!"SEARCH".equalsIgnoreCase(request.path("action").asText())) {
				return false;
			}
			String sql = request.path("sql").asText("");
			return sql.matches("(?is).*\\b(project(?:_name|_code)?|source(?:_type)?|business(?:_type)?)\\b.*");
		}
		catch (Exception ignored) {
			return false;
		}
	}

	public void recordModelCall(String signature) {
		modelCallCount.incrementAndGet();
		if (signature != null && signature.equals(lastFailedModelSignature.get())) {
			modelRetryCount.incrementAndGet();
		}
	}

	public boolean tryRecordModelCall(String signature, long estimatedPromptTokenCount) {
		long estimated = Math.max(0L, estimatedPromptTokenCount);
		if (maxModelCalls > 0 && modelCallCount.get() >= maxModelCalls) {
			return false;
		}
		if (maxPromptTokens > 0 && promptTokens.get() + estimated > maxPromptTokens) {
			return false;
		}
		recordModelCall(signature);
		return true;
	}

	public AgentRuntimeBudgetExceededException modelBudgetExceededException(long estimatedPromptTokenCount) {
		long estimated = Math.max(0L, estimatedPromptTokenCount);
		if (maxModelCalls > 0 && modelCallCount.get() >= maxModelCalls) {
			return new AgentRuntimeBudgetExceededException(AgentRuntimeBudgetExceededException.Reason.MODEL_CALLS,
					maxModelCalls, modelCallCount.get(), 1L);
		}
		return new AgentRuntimeBudgetExceededException(AgentRuntimeBudgetExceededException.Reason.PROMPT_TOKENS,
				maxPromptTokens, promptTokens.get(), estimated);
	}

	public String toolBudgetExceededMessage() {
		return "已达到当前智能体的工具调用次数上限。";
	}

	public AgentRuntimeBudgetExceededException toolBudgetExceededException() {
		return new AgentRuntimeBudgetExceededException(AgentRuntimeBudgetExceededException.Reason.TOOL_CALLS,
				maxToolCalls, toolCount.get(), 1L);
	}

	public void recordModelUsage(long promptTokenCount, long completionTokenCount, long durationMs) {
		long prompt = Math.max(0L, promptTokenCount);
		promptTokens.addAndGet(prompt);
		peakPromptTokens.accumulateAndGet(prompt, Math::max);
		completionTokens.addAndGet(Math.max(0L, completionTokenCount));
		modelDurationMs.addAndGet(Math.max(0L, durationMs));
		lastFailedModelSignature.set(null);
	}

	public void recordModelFailure(String signature, long estimatedPromptTokenCount, long durationMs) {
		long prompt = Math.max(0L, estimatedPromptTokenCount);
		promptTokens.addAndGet(prompt);
		peakPromptTokens.accumulateAndGet(prompt, Math::max);
		modelDurationMs.addAndGet(Math.max(0L, durationMs));
		lastFailedModelSignature.set(signature);
	}

	public int modelCallCount() {
		return modelCallCount.get();
	}

	public int modelRetryCount() {
		return modelRetryCount.get();
	}

	public long promptTokens() {
		return promptTokens.get();
	}

	public long peakPromptTokens() {
		return peakPromptTokens.get();
	}

	public long completionTokens() {
		return completionTokens.get();
	}

	public long modelDurationMs() {
		return modelDurationMs.get();
	}

	private boolean isReadOnlyTool(String toolName) {
		return AgentModelToolName.isDataQueryTool(toolName) || AgentModelToolName.isWebEvidenceTool(toolName)
				|| (toolName != null && toolName.startsWith("query_clarify."));
	}

	private String normalize(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public record ToolFailureObservation(int sequenceNo, String errorCode, String message) {
	}

}
