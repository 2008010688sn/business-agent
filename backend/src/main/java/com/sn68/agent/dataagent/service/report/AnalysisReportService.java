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
package com.sn68.agent.dataagent.service.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateReq;
import com.sn68.agent.dataagent.dto.chat.ChatReportGenerateResp;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.KnowledgeHitView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.SemanticHitView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolStepView;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnDecision;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnIntentClassifier;
import com.sn68.agent.dataagent.service.security.DataAgentOutputSanitizer;
import com.sn68.agent.dataagent.service.llm.LlmService;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 分析报告生成服务：基于问答轨迹组装提示词并生成脱敏后的分析报告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisReportService {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final int MAX_ANSWER_CHARS = 6000;

	private static final int MAX_CONTEXT_CHARS = 9000;

	private static final int MAX_CHART_CONTEXT_CHARS = 2000;

	private static final int MAX_STRUCTURED_CONTEXT_CHARS = 5000;

	private static final int MAX_STRUCTURED_CONTEXT_SNAPSHOTS = 2;

	private static final int MAX_STRUCTURED_CONTEXT_COLUMNS = 12;

	private static final int MAX_STRUCTURED_CONTEXT_CELL_CHARS = 80;

	private static final int MIN_PUBLIC_TABLE_ROWS = 2;

	private static final int MAX_TEXT_CHANNEL_ROWS = 20;

	private static final int MAX_WHITELIST_ANSWER_NUMBERS = 500;

	private static final Pattern NUMBER_TOKEN = Pattern.compile("-?[0-9][0-9,]*(?:\\.[0-9]+)?%?");

	private static final Pattern SQL_CODE_BLOCK = Pattern.compile("(?is)```\\s*sql\\s+.*?```");

	private static final Pattern RANKING_DETAIL_SECTION = Pattern
		.compile("(?ms)(?:\\n)*^#{2,4}\\s*完整排名明细[^\\n]*\\n.*?(?=^#{1,3}\\s|\\z)");

	private static final Pattern MARKDOWN_TABLE_BLOCK = Pattern.compile("(?m)(?:^[ \\t]*\\|.*\\|[ \\t]*\\R?)+");

	private static final Pattern ECHARTS_CODE_BLOCK = Pattern.compile("(?is)`{3,}\\s*echarts\\s+(.*?)`{3,}");

	private static final Pattern NO_CHART_CLAIM = Pattern.compile(
			"(?m)^.*(?:暂无可用图表候选|暂无适合生成图表|不展示可视化图表|不适合生成图表).*(?:\\R|$)");

	private static final Pattern UNVERIFIED_CHART_CLAIM = Pattern.compile(
			"(?s).*(?:适合使用|建议使用|可以生成|柱状图|折线图|饼图|可视化展示|图表展示).*");

	private static final Pattern ANSWER_METRIC_LINE = Pattern
		.compile("(?m)^\\s*(?:[-*]\\s*)?(?:\\*\\*)?([^：:\\n]{1,40})(?:\\*\\*)?\\s*[：:]\\s*([0-9][0-9,]*(?:\\.\\d+)?)\\s*(?:单|个|件|次|条)?");

	private static final Pattern SQL_STATEMENT = Pattern.compile(
			"(?is)\\b(select|with)\\b[\\s\\S]{0,240}\\b(from|join|where|group\\s+by|order\\s+by)\\b[\\s\\S]{0,240}");

	private static final List<String> FORBIDDEN_TECH_TERMS = List.of("SQL", "select", "from", "where", "join",
			"group by", "order by", "表名", "字段名", "数据源", "tool:", "datasource_", "semantic_model_search",
			"sql_guard_check");

	private final LlmService llmService;

	private final ChartCandidateBuilder chartCandidateBuilder;

	private final ObjectMapper objectMapper;

	private final DataAgentOutputSanitizer outputSanitizer;

	private final DataAgentProperties dataAgentProperties;

	private final AnalysisReportModelService reportModelService;

	private final ReportSnapshotSelector snapshotSelector = new ReportSnapshotSelector();

	private final InsightEngine insightEngine = new InsightEngine();

	private final ReportNarrativeValidator narrativeValidator = new ReportNarrativeValidator();

	private final AnalysisResultUiAssembler analysisUiAssembler = new AnalysisResultUiAssembler();

	public Flux<String> generateReportFlux(AnswerTraceExplainView explain) {
		return Mono.fromCallable(() -> generateDeterministicReport(explain)).subscribeOn(Schedulers.boundedElastic())
			.flux();
	}

	public String generateReport(AnswerTraceExplainView explain) {
		return generateNarrativeInternal(explain).content();
	}

	/**
	 * 专业模式报告：在确定性洞察之上追加 LLM 叙事，经数值一致性校验后返回；
	 * 生成失败、超时或校验不过时回落确定性报告，并通过 degraded 标记告知调用方。
	 */
	public ChatReportGenerateResp generateProfessionalReportResponse(AnswerTraceExplainView explain) {
		try {
			// 必须在当前请求线程跑：subscribeOn(boundedElastic) 会丢掉 Sa-Token/租户，
			// AnalysisReportModelService.requireAgent 会变成 Tenant context is required，专业解读永远降级。
			NarrativeOutcome outcome = Mono.fromCallable(() -> generateNarrativeInternal(explain))
				.timeout(Duration.ofMillis(Math.max(1000, dataAgentProperties.getReport().getNarrativeTimeoutMs())))
				.block();
			if (outcome != null && outcome.fromLlm()) {
				return ChatReportGenerateResp.builder()
					.runtimeRequestId(explain == null ? null : explain.getRuntimeRequestId())
					.content(outcome.content())
					.reportLevel(ChatReportGenerateReq.REPORT_LEVEL_PROFESSIONAL)
					.degraded(false)
					.build();
			}
		}
		catch (Exception ex) {
			log.warn("Professional narrative failed, fallback to deterministic report. runtimeRequestId={}, reason={}",
					explain == null ? null : explain.getRuntimeRequestId(), ex.getMessage());
		}
		return standardReportResponse(explain, true);
	}

	private ChatReportGenerateResp standardReportResponse(AnswerTraceExplainView explain, boolean degraded) {
		return ChatReportGenerateResp.builder()
			.runtimeRequestId(explain == null ? null : explain.getRuntimeRequestId())
			.content(generateDeterministicReport(explain))
			.reportLevel(ChatReportGenerateReq.REPORT_LEVEL_STANDARD)
			.degraded(degraded)
			.build();
	}

	private NarrativeOutcome generateNarrativeInternal(AnswerTraceExplainView explain) {
		long start = System.currentTimeMillis();
		String prompt = buildPrompt(explain);
		long promptMs = System.currentTimeMillis() - start;
		String report;
		try {
			report = generateRawReportFlux(prompt, explain)
				.map(chunk -> sanitizeChunk(chunk, explain))
				.collectList()
				.map(parts -> String.join("", parts))
				.block();
		}
		catch (RuntimeException ex) {
			log.warn("Generate analysis report failed, using deterministic fallback. runtimeRequestId={}",
					explain == null ? null : explain.getRuntimeRequestId(), ex);
			return new NarrativeOutcome(generateFallbackReport(explain), false);
		}
		long llmMs = System.currentTimeMillis() - start - promptMs;
		// 数值一致性校验必须在后端图表注入之前执行：图表 JSON 含坐标轴等派生数字，
		// 属于平台生成的受信内容，不参与模型叙事的防幻觉校验。
		String sanitized = sanitizeText(report, explain);
		if (!StringUtils.hasText(sanitized)) {
			return new NarrativeOutcome(generateFallbackReport(explain), false);
		}
		List<ChartCandidate> candidates = buildChartCandidates(explain, sanitized);
		String textOnly = ensureFixedSections(normalizeChartBlocks(sanitized.trim(), candidates));
		ReportNarrativeValidator.ValidationResult validation = narrativeValidator.validate(textOnly,
				narrativeNumberWhitelist(explain));
		if (!validation.passed()) {
			log.warn("Narrative number consistency check failed, using deterministic fallback. runtimeRequestId={}, unknownValues={}",
					explain == null ? null : explain.getRuntimeRequestId(), validation.unknownValues());
			return new NarrativeOutcome(generateFallbackReport(explain), false);
		}
		String normalized = insertDeterministicRankingTable(applyBackendCharts(textOnly, candidates), explain);
		long totalMs = System.currentTimeMillis() - start;
		log.info(
				"Analysis report timing. runtimeRequestId={}, promptChars={}, rawChars={}, reportChars={}, unknownValueCount={}, promptMs={}, llmMs={}, normalizeMs={}, totalMs={}",
				explain == null ? null : explain.getRuntimeRequestId(), prompt.length(),
				report == null ? 0 : report.length(), normalized.length(), validation.unknownValues().size(), promptMs,
				llmMs, totalMs - promptMs - llmMs, totalMs);
		return new NarrativeOutcome(normalized, true);
	}

	private record NarrativeOutcome(String content, boolean fromLlm) {
	}

	/**
	 * 汇总叙事可引用的全部确定性数值：快照单元格、行列统计、分类分布计数，
	 * 以及模型被允许复述的合计/均值/极值/占比派生值。校验白名单只认这份集合。
	 */
	private Set<BigDecimal> narrativeNumberWhitelist(AnswerTraceExplainView explain) {
		Set<BigDecimal> allowed = new HashSet<>();
		for (ReportDataSnapshot snapshot : contextSnapshots(explain)) {
			collectSnapshotNumbers(snapshot, allowed);
		}
		collectAnswerNumbers(explain == null ? null : explain.getAnswer(), allowed);
		return allowed;
	}

	private void collectAnswerNumbers(String answer, Set<BigDecimal> allowed) {
		if (!StringUtils.hasText(answer) || allowed.size() >= MAX_WHITELIST_ANSWER_NUMBERS) {
			return;
		}
		Matcher matcher = NUMBER_TOKEN.matcher(answer);
		while (matcher.find() && allowed.size() < MAX_WHITELIST_ANSWER_NUMBERS) {
			String token = matcher.group().replace(",", "").replace("%", "").trim();
			BigDecimal value = decimal(token);
			if (value != null) {
				allowed.add(value);
			}
		}
	}

	private void collectSnapshotNumbers(ReportDataSnapshot snapshot, Set<BigDecimal> allowed) {
		List<String> numericColumns = new ArrayList<>();
		for (String column : snapshot.getColumns().stream().limit(MAX_STRUCTURED_CONTEXT_COLUMNS).toList()) {
			List<BigDecimal> values = snapshot.getRows()
				.stream()
				.map(row -> decimal(row.get(column)))
				.filter(value -> value != null)
				.toList();
			if (values.isEmpty()) {
				continue;
			}
			numericColumns.add(column);
			allowed.addAll(values);
			allowed.add(values.get(0));
			allowed.add(values.get(values.size() - 1));
			BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			BigDecimal min = values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
			BigDecimal max = values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
			BigDecimal average = sum.divide(BigDecimal.valueOf(values.size()), 4, java.math.RoundingMode.HALF_UP);
			allowed.add(sum);
			allowed.add(min);
			allowed.add(max);
			allowed.add(max.subtract(min));
			allowed.add(average);
			allowed.add(average.setScale(2, java.math.RoundingMode.HALF_UP));
			allowed.add(average.setScale(0, java.math.RoundingMode.HALF_UP));
			if (sum.abs().compareTo(BigDecimal.ZERO) > 0) {
				for (BigDecimal value : values) {
					allowed.add(value.divide(sum.abs(), 4, java.math.RoundingMode.HALF_UP));
				}
			}
		}
		for (String column : snapshot.getColumns().stream().filter(value -> !numericColumns.contains(value)).toList()) {
			Map<String, Long> distribution = snapshot.getRows()
				.stream()
				.map(row -> safeStructuredCell(row.get(column)))
				.filter(StringUtils::hasText)
				.collect(java.util.stream.Collectors.groupingBy(value -> value, LinkedHashMap::new,
						java.util.stream.Collectors.counting()));
			distribution.values().forEach(count -> allowed.add(BigDecimal.valueOf(count)));
		}
		if (snapshot.getReturnedRows() != null) {
			allowed.add(BigDecimal.valueOf(snapshot.getReturnedRows()));
		}
		if (snapshot.getTotalRows() != null) {
			allowed.add(BigDecimal.valueOf(snapshot.getTotalRows()));
		}
	}

	public String normalizeInlineReport(String markdown, AnswerTraceExplainView explain) {
		return normalizeInlineReport(markdown, null, explain);
	}

	public String normalizeInlineReport(String markdown, String chartIntentJson, AnswerTraceExplainView explain) {
		String sanitized = sanitizeText(markdown, explain);
		if (!StringUtils.hasText(sanitized)) {
			return generateFallbackReport(explain);
		}
		List<ReportDataSnapshot> snapshots = authoritativeSnapshots(explain);
		List<ChartCandidate> candidates = snapshots.isEmpty()
				? parseVerifiedInlineChartCandidates(chartIntentJson, explain) : chartCandidateBuilder.build(snapshots);
		String fixed = ensureFixedSections(normalizeChartBlocks(sanitized.trim(), candidates));
		String withCharts = candidates.isEmpty() ? removeUnverifiedChartClaims(fixed)
				: applyBackendCharts(fixed, candidates);
		return insertDeterministicRankingTable(withCharts, explain);
	}

	public String sanitizeInlineResult(String text, AnswerTraceExplainView explain) {
		return sanitizeText(text, explain);
	}

	public String ensureCompleteTopNAnswer(String text, AnswerTraceExplainView explain) {
		String sanitized = sanitizeText(text, explain);
		if (StringUtils.hasText(buildPublicResultSetJson(explain))) {
			return stripDuplicateTables(sanitized);
		}
		return appendRankingTable(sanitized, explain, "### 完整排名明细");
	}

	/**
	 * 钉钉等文本通道没有 RESULT_SET SSE：短结论后附快照清单，不用 Markdown 表。
	 */
	public String completeTextChannelAnswer(String text, AnswerTraceExplainView explain) {
		String body = stripDuplicateTables(sanitizeText(text, explain));
		String listing = formatPublicRowsForTextChannel(explain);
		if (!StringUtils.hasText(listing)) {
			return body;
		}
		if (StringUtils.hasText(body) && body.contains(listing)) {
			return body;
		}
		return StringUtils.hasText(body) ? body + "\n\n" + listing : listing;
	}

	public String formatPublicRowsForTextChannel(AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> snapshots = publicTableSnapshots(explain);
		Optional<ReportDataSnapshot> merged = ReportSnapshotMerger.merge(snapshots);
		List<ReportDataSnapshot> outgoing = merged.map(List::of).orElse(snapshots);
		List<String> blocks = new ArrayList<>();
		boolean titled = outgoing.size() > 1;
		for (ReportDataSnapshot snapshot : outgoing) {
			String block = formatSnapshotListing(snapshot, titled);
			if (StringUtils.hasText(block)) {
				blocks.add(block);
			}
		}
		return String.join("\n\n", blocks);
	}

	/**
	 * 前端按 {@code textType=RESULT_SET} 渲染表格。SEARCH 结果达到 2 行即下发，不依赖模型是否写成 Markdown 表。
	 */
	public String buildPublicResultSetJson(AnswerTraceExplainView explain) {
		List<String> jsons = buildPublicResultSetJsons(explain);
		return jsons.isEmpty() ? "" : jsons.get(0);
	}

	/**
	 * 编排挂了多份子查询快照时，每份够行数的表单独出一份 RESULT_SET，避免只露出第一张。
	 */
	public List<String> buildPublicResultSetJsons(AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> snapshots = publicTableSnapshots(explain);
		Optional<ReportDataSnapshot> merged = ReportSnapshotMerger.merge(snapshots);
		List<ReportDataSnapshot> outgoing = merged.map(List::of).orElse(snapshots);
		List<String> jsons = new ArrayList<>();
		for (ReportDataSnapshot snapshot : outgoing) {
			String json = serializePublicResultSet(snapshot, explain);
			if (StringUtils.hasText(json)) {
				jsons.add(json);
			}
		}
		return jsons;
	}

	public String generateFallbackReport(AnswerTraceExplainView explain) {
		return generateDeterministicReport(explain);
	}

	public String generateDeterministicReport(AnswerTraceExplainView explain) {
		long start = System.currentTimeMillis();
		try {
			String report = normalizeReport(insightReport(explain), explain);
			long compileMs = System.currentTimeMillis() - start;
			int warnMs = Math.max(1, dataAgentProperties.getReport().getCompileWarnMs());
			if (compileMs > warnMs) {
				log.warn("Analysis report compile exceeded budget. runtimeRequestId={}, compileMs={}, warnMs={}",
						explain == null ? null : explain.getRuntimeRequestId(), compileMs, warnMs);
			}
			else {
				log.info("Analysis report compiled. runtimeRequestId={}, compileMs={}, reportChars={}",
						explain == null ? null : explain.getRuntimeRequestId(), compileMs, report.length());
			}
			return report;
		}
		catch (RuntimeException ex) {
			log.warn("Deterministic report compile failed, using template fallback. runtimeRequestId={}",
					explain == null ? null : explain.getRuntimeRequestId(), ex);
			return normalizeReport(fallbackReport(explain), explain);
		}
	}

	public ChatReportGenerateResp generateReportResponse(AnswerTraceExplainView explain) {
		return ChatReportGenerateResp.builder()
			.runtimeRequestId(explain == null ? null : explain.getRuntimeRequestId())
			.content(generateDeterministicReport(explain))
			.build();
	}

	/**
	 * Pack the current report into an {@code analysis-result} UI message. Analysis turns stay read-only.
	 */
	public AgentUiMessage buildAnalysisUiMessage(AnswerTraceExplainView explain) {
		return buildAnalysisUiMessage(explain, null, AnalysisUiOptions.defaults());
	}

	public AgentUiMessage buildAnalysisUiMessage(AnswerTraceExplainView explain, AnalysisUiOptions options) {
		return buildAnalysisUiMessage(explain, null, options);
	}

	public AgentUiMessage buildAnalysisUiMessage(AnswerTraceExplainView explain, String markdown,
			AnalysisUiOptions options) {
		return analysisUiAssembler.toMessage(explain, markdown == null ? "" : markdown, options);
	}

	/**
	 * Follow-up chips for the report footer. Does not emit a second analysis-result card.
	 */
	public List<Map<String, Object>> buildAnalysisFollowUps(AnswerTraceExplainView explain, AnalysisUiOptions options) {
		AgentUiMessage ui = buildAnalysisUiMessage(explain, "", options);
		if (ui == null || ui.actions() == null || ui.actions().isEmpty()) {
			return List.of();
		}
		List<Map<String, Object>> followUps = new ArrayList<>();
		for (AgentUiMessage.Action action : ui.actions()) {
			if (action == null || !StringUtils.hasText(action.type())) {
				continue;
			}
			String type = action.type().trim().toUpperCase(Locale.ROOT);
			if (!"DRILL".equals(type) && !"START_FLOW".equals(type) && !"ASK_WRITE".equals(type)) {
				continue;
			}
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("type", type);
			if (StringUtils.hasText(action.label())) {
				item.put("label", action.label().trim());
			}
			if (action.value() != null) {
				item.put("value", action.value());
			}
			if (action.payload() != null) {
				copyFollowUpPayload(action.payload(), item);
			}
			followUps.add(item);
		}
		return List.copyOf(followUps);
	}

	private void copyFollowUpPayload(Map<String, Object> payload, Map<String, Object> target) {
		for (String key : List.of("query", "grain", "skillCode", "toolName", "confirm")) {
			if (payload.containsKey(key) && payload.get(key) != null) {
				target.put(key, payload.get(key));
			}
		}
	}

	public AnalysisUiOptions optionsForTurn(String query, boolean hasAttachment, AnalysisConfig config,
			AnswerTraceExplainView explain) {
		AnalysisConfig safe = config == null ? AnalysisConfig.empty() : config;
		Set<String> snapshotColumns = new LinkedHashSet<>();
		if (explain != null && explain.getReportDataSnapshots() != null) {
			for (ReportDataSnapshot snapshot : explain.getReportDataSnapshots()) {
				if (snapshot != null && snapshot.getColumns() != null) {
					snapshotColumns.addAll(snapshot.getColumns());
				}
			}
		}
		AnalysisTurnIntentClassifier classifier = new AnalysisTurnIntentClassifier();
		Set<String> extracted = classifier.extractJoinKeys(query, safe, snapshotColumns);
		AnalysisTurnDecision decision = classifier.classify(query, hasAttachment, safe, extracted);
		return AnalysisUiOptions.from(decision, safe);
	}

	private Flux<String> generateRawReportFlux(String prompt, AnswerTraceExplainView explain) {
		if (reportModelService != null) {
			return Flux.just(reportModelService.generate(prompt, explain));
		}
		return llmService.toStringFlux(llmService.callUser(prompt));
	}

	private String buildPrompt(AnswerTraceExplainView explain) {
		String question = safe(explain == null ? null : explain.getQuestion());
		String answer = abbreviate(safe(explain == null ? null : explain.getAnswer()), MAX_ANSWER_CHARS);
		String structuredContext = abbreviate(buildStructuredDataContext(explain), MAX_STRUCTURED_CONTEXT_CHARS);
		String context = abbreviate(buildBusinessContext(explain), MAX_CONTEXT_CHARS);
		String chartContext = abbreviate(buildChartContext(explain), MAX_CHART_CONTEXT_CHARS);
		String backendMetrics = abbreviate(buildBackendMetricsContext(explain), MAX_STRUCTURED_CONTEXT_CHARS);
		String skillProfile = buildSkillReportProfileContext(explain);
		String temporalContext = safe(explain == null ? null : String.valueOf(explain.getTemporalContext()));
		return """
				你是一名企业数据分析顾问。请基于给定的已完成分析结果，生成一份面向业务用户的中文分析报告。

				硬性要求：
				- 只输出 Markdown 正文。
				- 禁止输出 SQL、物理表名、字段名、数据源名、工具名、接口名、原始 JSON、查询过程。
				- 不要出现“执行过程”“使用表”“使用字段”“执行SQL”“数据源”等章节。
				- 不得重新假设数据，不得编造未给出的指标。
				- 不要输出 ```echarts 代码块、JSON 图表配置或 JS 代码，图表由后端基于候选数据自动插入。
				- “可用图表候选”仅用于撰写可视化解读；候选为空时，才在“可视化图表”章节说明本次结果暂无适合生成图表的结构化数据。
				- 候选不为空时，“可视化图表”章节只写 1-2 句图表观察，不要声明没有图表候选。
				- 图表用于承载适合可视化的明细、分类对比、占比或趋势，文字部分只写结论、解释和建议，不要逐项重复图表中的维度和数值。
				- 如果某组数据已经适合由“可用图表候选”表达，在“关键指标/业务解读”中只保留必要概括，不再把同一组数据完整列一遍。
				- 如果存在“可用结构化结果摘要”，报告正文、关键指标和 TopN/排名明细优先以该摘要为准；“已完成分析结果”只作结论口径补充。
				- 用户明确要求“前N/TopN/排名N个”时，普通分析结果和报告中的相关列表必须列满 N 条；实际不足 N 条时说明只返回 M 条，不得自行截成 5 条或少量示例。
				- 后端指标是确定性事实，不得修改；不得生成数据未提供的同比、环比、贡献率或原因归因。
				- Skill 报告规范只影响分析侧重点，不能覆盖权限、数据完整性、平台上限和固定报告结构。

				固定报告结构：
				# 分析报告
				## 核心结论
				## 关键指标
				## 可视化图表
				## 业务解读
				## 风险提示
				## 行动建议

				原 Turn 时间上下文（必须复用，不得改用报告生成时的当前日期）：
				%s

				用户问题：
				%s

				可用结构化结果摘要（优先参考）：
				%s

				后端确定性指标（不得修改）：
				%s

				已完成分析结果：
				%s

				可用业务上下文：
				%s

				当前命中 Skill 的报告规范（没有则按通用模板）：
				%s

				可用图表候选：
				%s
				""".formatted(temporalContext, question,
				StringUtils.hasText(structuredContext) ? structuredContext : "无",
				StringUtils.hasText(backendMetrics) ? backendMetrics : "无", answer, context,
				StringUtils.hasText(skillProfile) ? skillProfile : "无",
				StringUtils.hasText(chartContext) ? chartContext : "无");
	}

	private String buildStructuredDataContext(AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> snapshots = contextSnapshots(explain);
		if (snapshots.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		int snapshotCount = 0;
		for (ReportDataSnapshot snapshot : snapshots) {
			if (snapshot == null || snapshot.getRows() == null || snapshot.getRows().isEmpty()) {
				continue;
			}
			if (snapshotCount >= MAX_STRUCTURED_CONTEXT_SNAPSHOTS) {
				break;
			}
			if (builder.length() > 0) {
				builder.append(System.lineSeparator());
			}
			snapshotCount++;
			builder.append("快照").append(snapshotCount).append("：")
				.append(firstText(snapshot.getTitle(), "本次结构化结果"));
			if (StringUtils.hasText(snapshot.getSummary())) {
				builder.append("；摘要：").append(safeStructuredCell(snapshot.getSummary()));
			}
			if (snapshot.getTotalRows() != null) {
				builder.append("；总行数：").append(snapshot.getTotalRows());
			}
			if (Boolean.TRUE.equals(snapshot.getTruncated())) {
				builder.append("；结果达到平台或查询限制");
			}
			if (snapshot.getCoverageStatus() != null) {
				builder.append("；覆盖状态：").append(snapshot.getCoverageStatus());
			}
			builder.append(System.lineSeparator());

			List<String> displayColumns = safeStructuredColumns(snapshot.getColumns());
			if (!displayColumns.isEmpty()) {
				builder.append("字段：").append(String.join("、", displayColumns)).append(System.lineSeparator());
			}
			int rowIndex = 0;
			for (Map<String, Object> row : snapshot.getRows().stream()
				.limit(promptPreviewRows())
				.toList()) {
				String rowText = formatStructuredRow(row, snapshot.getColumns());
				if (!StringUtils.hasText(rowText)) {
					continue;
				}
				rowIndex++;
				builder.append("- 第").append(rowIndex).append("行：").append(rowText).append(System.lineSeparator());
			}
			if (snapshot.getRows().size() > rowIndex
					|| (snapshot.getTotalRows() != null && snapshot.getTotalRows() > rowIndex)) {
				builder.append("（仅展示前").append(rowIndex).append("行用于生成报告）")
					.append(System.lineSeparator());
			}
		}
		return sanitizeText(builder.toString().trim(), explain);
	}

	private List<String> safeStructuredColumns(List<String> columns) {
		if (columns == null || columns.isEmpty()) {
			return List.of();
		}
		return columns.stream()
			.map(this::safeStructuredCell)
			.filter(StringUtils::hasText)
			.limit(MAX_STRUCTURED_CONTEXT_COLUMNS)
			.toList();
	}

	private String formatStructuredRow(Map<String, Object> row, List<String> preferredColumns) {
		if (row == null || row.isEmpty()) {
			return "";
		}
		List<String> cells = new ArrayList<>();
		if (preferredColumns != null && !preferredColumns.isEmpty()) {
			for (String column : preferredColumns) {
				if (row.containsKey(column)) {
					addStructuredCell(cells, column, row.get(column));
				}
			}
		}
		if (cells.isEmpty()) {
			row.entrySet()
				.stream()
				.limit(MAX_STRUCTURED_CONTEXT_COLUMNS)
				.forEach(entry -> addStructuredCell(cells, entry.getKey(), entry.getValue()));
		}
		return String.join("；", cells);
	}

	private void addStructuredCell(List<String> cells, String column, Object value) {
		if (cells.size() >= MAX_STRUCTURED_CONTEXT_COLUMNS) {
			return;
		}
		String safeColumn = safeStructuredCell(column);
		String safeValue = safeStructuredCell(value);
		if (StringUtils.hasText(safeColumn) && StringUtils.hasText(safeValue)) {
			cells.add(safeColumn + "=" + safeValue);
		}
	}

	private String safeStructuredCell(Object value) {
		if (value == null) {
			return "";
		}
		String text = String.valueOf(value).replace('\r', ' ').replace('\n', ' ').trim();
		if (text.length() <= MAX_STRUCTURED_CONTEXT_CELL_CHARS) {
			return text;
		}
		return text.substring(0, MAX_STRUCTURED_CONTEXT_CELL_CHARS) + "...";
	}

	private String buildBusinessContext(AnswerTraceExplainView explain) {
		if (explain == null) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		addLine(lines, "结果范围", explain.getResultScope());
		addLine(lines, "决策说明", explain.getDecisionReason());
		if (explain.getToolDecisionReasons() != null) {
			explain.getToolDecisionReasons().stream().filter(StringUtils::hasText).limit(3)
				.forEach(reason -> addLine(lines, "分析口径", reason));
		}
		if (explain.getResultScopeDetails() != null) {
			explain.getResultScopeDetails().stream().filter(StringUtils::hasText).limit(3)
				.forEach(detail -> addLine(lines, "范围说明", detail));
		}
		if (explain.getSemanticHits() != null) {
			for (SemanticHitView hit : explain.getSemanticHits().stream().limit(6).toList()) {
				addLine(lines, "业务语义", firstText(hit.getBusinessName(), hit.getBusinessDescription(),
						hit.getRelationHint()));
			}
		}
		if (explain.getKnowledgeHits() != null) {
			for (KnowledgeHitView hit : explain.getKnowledgeHits().stream().limit(4).toList()) {
				addLine(lines, "知识摘要", firstText(hit.getTitle(), hit.getSummary(), hit.getSnippet()));
			}
		}
		if (explain.getToolSteps() != null) {
			for (ToolStepView step : explain.getToolSteps().stream().limit(5).toList()) {
				addLine(lines, "分析摘要", step.getSummary());
			}
		}
		if (explain.getWarnings() != null) {
			explain.getWarnings().stream().filter(StringUtils::hasText).limit(3)
				.forEach(warning -> addLine(lines, "提示", warning));
		}
		return String.join(System.lineSeparator(), sanitizeLines(lines, explain));
	}

	private String buildBackendMetricsContext(AnswerTraceExplainView explain) {
		Optional<ReportDataSnapshot> snapshotOptional = authoritativeSnapshot(explain);
		if (snapshotOptional.isEmpty()) {
			return "";
		}
		ReportDataSnapshot snapshot = snapshotOptional.get();
		List<String> lines = new ArrayList<>();
		int returnedRows = snapshot.getReturnedRows() == null ? snapshot.getRows().size() : snapshot.getReturnedRows();
		lines.add("返回结果行数=" + returnedRows);
		if (snapshot.getRequestedRows() != null) {
			lines.add("请求结果行数=" + snapshot.getRequestedRows());
		}
		if (snapshot.getCoverageStatus() != null) {
			lines.add("覆盖状态=" + snapshot.getCoverageStatus());
		}
		List<String> numericColumns = new ArrayList<>();
		for (String column : snapshot.getColumns().stream().limit(MAX_STRUCTURED_CONTEXT_COLUMNS).toList()) {
			List<BigDecimal> values = snapshot.getRows()
				.stream()
				.map(row -> decimal(row.get(column)))
				.filter(value -> value != null)
				.toList();
			if (values.isEmpty()) {
				continue;
			}
			numericColumns.add(column);
			BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
			BigDecimal min = values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
			BigDecimal max = values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
			BigDecimal average = sum.divide(BigDecimal.valueOf(values.size()), 4, java.math.RoundingMode.HALF_UP);
			lines.add("%s：合计=%s，均值=%s，最小值=%s，最大值=%s，极差=%s".formatted(column,
					formatDecimal(sum), formatDecimal(average), formatDecimal(min), formatDecimal(max),
					formatDecimal(max.subtract(min))));
		}
		for (String column : snapshot.getColumns().stream().filter(value -> !numericColumns.contains(value)).toList()) {
			Map<String, Long> distribution = snapshot.getRows()
				.stream()
				.map(row -> safeStructuredCell(row.get(column)))
				.filter(StringUtils::hasText)
				.collect(java.util.stream.Collectors.groupingBy(value -> value, LinkedHashMap::new,
						java.util.stream.Collectors.counting()));
			if (distribution.size() >= 2 && distribution.size() <= 12) {
				lines.add("%s分类分布：%s".formatted(column,
						distribution.entrySet()
							.stream()
							.map(entry -> entry.getKey() + "=" + entry.getValue())
							.collect(java.util.stream.Collectors.joining("，"))));
			}
			if (!numericColumns.isEmpty() && isTimeSeries(snapshot, column)) {
				appendTrendEndpoints(lines, snapshot, column, numericColumns.get(0));
			}
		}
		return sanitizeText(String.join(System.lineSeparator(), lines), explain);
	}

	private boolean isTimeSeries(ReportDataSnapshot snapshot, String column) {
		List<String> values = snapshot.getRows()
			.stream()
			.map(row -> safeStructuredCell(row.get(column)))
			.filter(StringUtils::hasText)
			.toList();
		return values.size() >= 2 && values.stream().allMatch(this::isTimeValue) && values.stream().distinct().count() >= 2;
	}

	private boolean isTimeValue(String value) {
		return value != null && (value.matches("\\d{4}-\\d{1,2}(?:-\\d{1,2})?.*")
				|| value.matches("\\d{4}/\\d{1,2}(?:/\\d{1,2})?.*"));
	}

	private void appendTrendEndpoints(List<String> lines, ReportDataSnapshot snapshot, String timeColumn,
			String metricColumn) {
		Map<String, Object> first = snapshot.getRows().stream().filter(row -> decimal(row.get(metricColumn)) != null)
			.findFirst()
			.orElse(null);
		Map<String, Object> last = snapshot.getRows()
			.stream()
			.filter(row -> decimal(row.get(metricColumn)) != null)
			.reduce((left, right) -> right)
			.orElse(null);
		if (first == null || last == null || first == last) {
			return;
		}
		BigDecimal firstValue = decimal(first.get(metricColumn));
		BigDecimal lastValue = decimal(last.get(metricColumn));
		lines.add("%s趋势端点：%s=%s，%s=%s，变化=%s".formatted(metricColumn,
				safeStructuredCell(first.get(timeColumn)), formatDecimal(firstValue),
				safeStructuredCell(last.get(timeColumn)), formatDecimal(lastValue),
				formatDecimal(lastValue.subtract(firstValue))));
	}

	private String buildSkillReportProfileContext(AnswerTraceExplainView explain) {
		SkillReportProfile profile = explain == null ? null : explain.getSkillReportProfile();
		if (profile == null || profile.isEmpty()) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		addProfileLine(lines, "关注指标", profile.getMetrics());
		addProfileLine(lines, "推荐维度", profile.getDimensions());
		addProfileLine(lines, "重点风险", profile.getRisks());
		addProfileLine(lines, "行动建议方向", profile.getActionDirections());
		addProfileLine(lines, "推荐子章节", profile.getSubSections());
		addProfileLine(lines, "领域术语", profile.getTerms());
		return String.join(System.lineSeparator(), lines);
	}

	private void addProfileLine(List<String> lines, String title, List<String> values) {
		if (values != null && !values.isEmpty()) {
			lines.add(title + "：" + String.join("；", values));
		}
	}

	private String buildChartContext(AnswerTraceExplainView explain) {
		List<ChartCandidate> candidates = buildChartCandidates(explain);
		if (candidates.isEmpty()) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		for (ChartCandidate candidate : candidates) {
			String metrics = candidate.getMetricNames() == null ? "" : String.join("、", candidate.getMetricNames());
			lines.add("- " + safe(candidate.getTitle()) + "，图表类型：" + safe(candidate.getChartType()) + "，维度："
					+ safe(candidate.getDimensionName()) + "，指标：" + metrics + "，数据点："
					+ (candidate.getData() == null ? 0 : candidate.getData().size()));
		}
		return String.join(System.lineSeparator(), lines);
	}

	private List<String> sanitizeLines(List<String> lines, AnswerTraceExplainView explain) {
		if (lines == null || lines.isEmpty()) {
			return List.of();
		}
		return lines.stream().map(line -> sanitizeText(line, explain)).filter(StringUtils::hasText).distinct().toList();
	}

	private String normalizeReport(String report, AnswerTraceExplainView explain) {
		String sanitized = sanitizeText(report, explain);
		if (!StringUtils.hasText(sanitized)) {
			sanitized = sanitizeText(fallbackReport(explain), explain);
		}
		List<ChartCandidate> candidates = buildChartCandidates(explain, sanitized);
		String fixed = ensureFixedSections(normalizeChartBlocks(sanitized.trim(), candidates));
		return insertDeterministicRankingTable(applyBackendCharts(fixed, candidates), explain);
	}

	private List<ChartCandidate> parseVerifiedInlineChartCandidates(String chartIntentJson, AnswerTraceExplainView explain) {
		if (!StringUtils.hasText(chartIntentJson)) {
			log.info("Inline report chart skipped. runtimeRequestId={}, reason={}",
					explain == null ? null : explain.getRuntimeRequestId(), "missing_chart_intent");
			return List.of();
		}
		try {
			JsonNode root = objectMapper.readTree(chartIntentJson);
			if (!root.isArray()) {
				log.info("Inline report chart skipped. runtimeRequestId={}, reason={}",
						explain == null ? null : explain.getRuntimeRequestId(), "chart_intent_not_array");
				return List.of();
			}
			List<ChartCandidate> candidates = new ArrayList<>();
			for (JsonNode node : root) {
				parseVerifiedInlineChartCandidate(node, explain).ifPresent(candidates::add);
			}
			log.info("Inline report chart parsed. runtimeRequestId={}, accepted={}, requested={}",
					explain == null ? null : explain.getRuntimeRequestId(), candidates.size(), root.size());
			return candidates;
		}
		catch (Exception ex) {
			log.info("Inline report chart skipped. runtimeRequestId={}, reason={}",
					explain == null ? null : explain.getRuntimeRequestId(),
					"invalid_chart_intent_json");
			return List.of();
		}
	}

	private Optional<ChartCandidate> parseVerifiedInlineChartCandidate(JsonNode node, AnswerTraceExplainView explain) {
		if (node == null || !node.isObject()) {
			return Optional.empty();
		}
		String title = text(node, "title");
		String chartType = text(node, "chartType");
		String dimensionName = text(node, "dimensionName");
		List<String> metricNames = stringArray(node.get("metricNames"));
		JsonNode dataNode = node.get("data");
		if (!StringUtils.hasText(title) || !isSupportedInlineChartType(chartType) || !StringUtils.hasText(dimensionName)
				|| metricNames.isEmpty() || dataNode == null || !dataNode.isArray() || dataNode.isEmpty()
				|| !isSafeInlineChartText(title, explain) || !isSafeInlineChartText(dimensionName, explain)
				|| metricNames.stream().anyMatch(metric -> !isSafeInlineChartText(metric, explain))) {
			return Optional.empty();
		}
		List<Map<String, Object>> data = new ArrayList<>();
		for (JsonNode row : dataNode) {
			if (!row.isObject()) {
				return Optional.empty();
			}
			String name = text(row, "name");
			if (!StringUtils.hasText(name) || !isSafeInlineChartText(name, explain)) {
				return Optional.empty();
			}
			Map<String, Object> point = new LinkedHashMap<>();
			point.put("name", name);
			for (String metric : metricNames) {
				BigDecimal value = decimal(row.get(metric));
				if (value == null) {
					return Optional.empty();
				}
				point.put(metric, value);
			}
			data.add(point);
		}
		ChartCandidate candidate = ChartCandidate.builder()
			.title(title)
			.chartType(chartType)
			.dimensionName(dimensionName)
			.metricName(metricNames.get(0))
			.metricNames(metricNames)
			.data(data)
			.note("基于本次分析结果中的结构化图表数据生成。")
				.build();
		return Optional.of(candidate);
	}

	private String sanitizeChunk(String chunk, AnswerTraceExplainView explain) {
		return sanitizeText(chunk, explain);
	}

	private String sanitizeText(String text, AnswerTraceExplainView explain) {
		return outputSanitizer.sanitizeText(text, explain);
	}

	private String normalizeChartBlocks(String report, List<ChartCandidate> candidates) {
		if (!StringUtils.hasText(report)) {
			return "";
		}
		Matcher matcher = ECHARTS_CODE_BLOCK.matcher(report);
		StringBuilder builder = new StringBuilder();
		while (matcher.find()) {
			String replacement = validateChartJson(matcher.group(1), candidates).orElse("");
			matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(builder);
		return builder.toString();
	}

	private Optional<String> validateChartJson(String json, List<ChartCandidate> candidates) {
		if (!StringUtils.hasText(json) || candidates == null || candidates.isEmpty()) {
			return Optional.empty();
		}
		try {
			JsonNode node = objectMapper.readTree(json);
			if (!node.isObject() || !node.has("series")) {
				return Optional.empty();
			}
			Map<String, Object> option = objectMapper.convertValue(node, MAP_TYPE);
			if (!matchesCandidateData(option, candidates)) {
				return fallbackChartOption(candidates).map(this::toEchartsBlock);
			}
			return Optional.of(toEchartsBlock(option));
		}
		catch (Exception ex) {
			// The user silently receives a different chart than the model designed.
			log.warn("Failed to build the model chart option, falling back to a generated chart. candidateCount={}",
					candidates == null ? 0 : candidates.size(), ex);
			return fallbackChartOption(candidates).map(this::toEchartsBlock);
		}
	}

	private boolean matchesCandidateData(Map<String, Object> option, List<ChartCandidate> candidates) {
		String optionText = String.valueOf(option);
		return candidates.stream().anyMatch(candidate -> candidate.getData()
			.stream()
			.anyMatch(row -> optionText.contains(String.valueOf(row.get("name")))
					&& candidate.getMetricNames()
						.stream()
						.map(row::get)
						.filter(value -> value != null)
						.anyMatch(value -> optionText.contains(String.valueOf(value)))));
	}

	private String applyBackendCharts(String report, List<ChartCandidate> candidates) {
		if (candidates.isEmpty()) {
			return report;
		}
		int limit = Math.max(1, dataAgentProperties.getReportVisualization().getMaxCharts());
		List<String> blocks = new ArrayList<>();
		for (ChartCandidate candidate : candidates) {
			if (blocks.size() >= limit) {
				break;
			}
			Optional<Map<String, Object>> option = chartCandidateBuilder.toEchartsOption(candidate);
			if (option.isEmpty()) {
				continue;
			}
			String block = toEchartsBlock(option.get());
			if (StringUtils.hasText(block)) {
				String caption = StringUtils.hasText(candidate.getTitle()) ? "**" + candidate.getTitle() + "**" : "";
				blocks.add(StringUtils.hasText(caption) ? caption + "\n\n" + block : block);
			}
		}
		if (blocks.isEmpty()) {
			log.info("Report charts skipped after option build. runtimeRequestId={}, candidateCount={}",
					null, candidates.size());
			return report;
		}
		return insertVisualizationSection(removeNoChartClaims(report), String.join("\n\n", blocks));
	}

	private String removeNoChartClaims(String report) {
		return NO_CHART_CLAIM.matcher(report).replaceAll("");
	}

	private String removeUnverifiedChartClaims(String report) {
		int sectionStart = report.indexOf("## 可视化图表");
		if (sectionStart < 0) {
			return report;
		}
		int bodyStart = sectionStart + "## 可视化图表".length();
		int nextSection = report.indexOf("\n## ", bodyStart);
		String body = nextSection < 0 ? report.substring(bodyStart) : report.substring(bodyStart, nextSection);
		if (!UNVERIFIED_CHART_CLAIM.matcher(body).matches()) {
			return report;
		}
		String section = "## 可视化图表\n本次未生成可渲染图表，保留文字说明。\n";
		if (nextSection < 0) {
			return report.substring(0, sectionStart) + section;
		}
		return report.substring(0, sectionStart) + section + report.substring(nextSection);
	}

	private String insertVisualizationSection(String report, String chartBlock) {
		String section = "## 可视化图表\n" + chartBlock + "\n";
		if (!report.contains("## 可视化图表")) {
			int nextSection = report.indexOf("## 业务解读");
			if (nextSection >= 0) {
				return report.substring(0, nextSection) + section + "\n" + report.substring(nextSection);
			}
			return report + "\n\n" + section;
		}
		int sectionStart = report.indexOf("## 可视化图表");
		int nextSection = report.indexOf("\n## ", sectionStart + "## 可视化图表".length());
		if (nextSection < 0) {
			return report.substring(0, sectionStart) + section;
		}
		return report.substring(0, sectionStart) + section + report.substring(nextSection);
	}

	private Optional<Map<String, Object>> fallbackChartOption(List<ChartCandidate> candidates) {
		return candidates.stream().map(chartCandidateBuilder::toEchartsOption).flatMap(Optional::stream).findFirst();
	}

	private String toEchartsBlock(Map<String, Object> option) {
		try {
			String json = objectMapper.writeValueAsString(option);
			objectMapper.readTree(json);
			return "````echarts\n" + json + "\n````";
		}
		catch (JsonProcessingException ex) {
			log.warn("Unable to serialize the ECharts option, the chart is dropped from the report. optionKeys={}",
					option == null ? null : option.keySet(), ex);
			return "";
		}
	}

	private List<ChartCandidate> buildChartCandidates(AnswerTraceExplainView explain) {
		return buildChartCandidates(explain, null);
	}

	private List<ChartCandidate> buildChartCandidates(AnswerTraceExplainView explain, String reportText) {
		if (explain == null) {
			return Collections.emptyList();
		}
		List<ReportDataSnapshot> snapshots = snapshotSelector.select(explain).forCharts();
		List<ChartCandidate> candidates = chartCandidateBuilder.build(snapshots);
		if (!snapshots.isEmpty()) {
			if (candidates.isEmpty()) {
				log.info("Report charts skipped. runtimeRequestId={}, snapshotCount={}, reason={}",
						explain.getRuntimeRequestId(), snapshots.size(), InsightEngine.NO_NUMERIC_METRIC);
			}
			return candidates;
		}
		candidates = buildCandidatesFromAnswer(reportText);
		if (!candidates.isEmpty()) {
			return candidates;
		}
		return buildCandidatesFromAnswer(explain.getAnswer());
	}

	private List<ChartCandidate> buildCandidatesFromAnswer(String answer) {
		if (!StringUtils.hasText(answer)) {
			return Collections.emptyList();
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		Matcher matcher = ANSWER_METRIC_LINE.matcher(answer);
		while (matcher.find() && rows.size() < 20) {
			String name = cleanMetricName(matcher.group(1));
			String value = matcher.group(2).replace(",", "");
			if (!StringUtils.hasText(name) || isDetailFieldMetric(name)) {
				continue;
			}
			rows.add(Map.of("统计项", name, "统计值", value));
		}
		if (rows.size() < 2) {
			return Collections.emptyList();
		}
		ReportDataSnapshot snapshot = ReportDataSnapshot.builder()
			.title("关键指标对比")
			.columns(List.of("统计项", "统计值"))
			.rows(rows)
			.totalRows(rows.size())
			.truncated(false)
			.build();
		return chartCandidateBuilder.build(List.of(snapshot));
	}

	private List<ReportDataSnapshot> authoritativeSnapshots(AnswerTraceExplainView explain) {
		return authoritativeSnapshot(explain).map(List::of).orElseGet(List::of);
	}

	private List<ReportDataSnapshot> contextSnapshots(AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> authoritative = authoritativeSnapshots(explain);
		if (!authoritative.isEmpty()) {
			return authoritative;
		}
		return explain == null || explain.getReportDataSnapshots() == null ? List.of() : explain.getReportDataSnapshots();
	}

	private Optional<ReportDataSnapshot> publicTableSnapshot(AnswerTraceExplainView explain) {
		List<ReportDataSnapshot> snapshots = publicTableSnapshots(explain);
		return snapshots.isEmpty() ? Optional.empty() : Optional.of(snapshots.get(0));
	}

	private List<ReportDataSnapshot> publicTableSnapshots(AnswerTraceExplainView explain) {
		if (explain == null || explain.getReportDataSnapshots() == null || explain.getReportDataSnapshots().isEmpty()) {
			return publicTableSnapshotFallback(explain);
		}
		List<ReportDataSnapshot> snapshots = new ArrayList<>();
		for (ReportDataSnapshot snapshot : explain.getReportDataSnapshots()) {
			if (hasPublicTableRows(snapshot) && !Boolean.TRUE.equals(snapshot.getProbe())) {
				snapshots.add(snapshot);
			}
		}
		return snapshots.isEmpty() ? publicTableSnapshotFallback(explain) : snapshots;
	}

	private List<ReportDataSnapshot> publicTableSnapshotFallback(AnswerTraceExplainView explain) {
		ReportSnapshotSelector.Selection selection = snapshotSelector.select(explain);
		return selection.ranking()
			.filter(this::hasPublicTableRows)
			.filter(snapshot -> !Boolean.TRUE.equals(snapshot.getProbe()))
			.or(() -> selection.primary().filter(this::hasPublicTableRows)
				.filter(snapshot -> !Boolean.TRUE.equals(snapshot.getProbe())))
			.map(List::of)
			.orElseGet(List::of);
	}

	private String formatSnapshotListing(ReportDataSnapshot snapshot, boolean includeTitle) {
		List<String> columns = publicColumns(snapshot);
		if (columns.isEmpty() || snapshot.getRows() == null || snapshot.getRows().isEmpty()) {
			return "";
		}
		String nameColumn = columns.stream().filter(ReportColumnSemantics::isBusinessNameColumn).findFirst()
			.orElse(columns.get(0));
		List<String> metrics = columns.stream().filter(column -> !column.equals(nameColumn)).toList();
		StringBuilder builder = new StringBuilder();
		if (includeTitle && StringUtils.hasText(snapshot.getTitle()) && !"本次分析结果".equals(snapshot.getTitle())) {
			builder.append(snapshot.getTitle()).append('\n');
		}
		int limit = Math.min(snapshot.getRows().size(), MAX_TEXT_CHANNEL_ROWS);
		int rank = 0;
		for (int index = 0; index < limit; index++) {
			Map<String, Object> row = snapshot.getRows().get(index);
			String name = listingCell(row == null ? null : row.get(nameColumn));
			if (!StringUtils.hasText(name)) {
				continue;
			}
			builder.append(++rank).append(". ").append(name);
			if (!metrics.isEmpty()) {
				builder.append('：');
				List<String> parts = new ArrayList<>();
				for (String metric : metrics) {
					String value = listingCell(row == null ? null : row.get(metric));
					parts.add(metric + " " + (StringUtils.hasText(value) ? value : "暂无"));
				}
				builder.append(String.join("，", parts));
			}
			builder.append('\n');
		}
		return builder.toString().trim();
	}

	private List<String> publicColumns(ReportDataSnapshot snapshot) {
		if (snapshot == null || snapshot.getColumns() == null) {
			return List.of();
		}
		return snapshot.getColumns()
			.stream()
			.filter(column -> !ReportColumnSemantics.isInternalColumn(column))
			.filter(column -> !ReportColumnSemantics.isIdentifierColumn(column))
			.filter(column -> !ReportColumnSemantics.isIdentifierValueColumn(snapshot.getRows(), column))
			.limit(MAX_STRUCTURED_CONTEXT_COLUMNS)
			.toList();
	}

	private String listingCell(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
		}
		String text = String.valueOf(value).trim().replace('\r', ' ').replace('\n', ' ');
		if (!StringUtils.hasText(text) || "null".equalsIgnoreCase(text)) {
			return "";
		}
		try {
			return new BigDecimal(text).stripTrailingZeros().toPlainString();
		}
		catch (NumberFormatException ignored) {
			return text;
		}
	}

	private String serializePublicResultSet(ReportDataSnapshot snapshot, AnswerTraceExplainView explain) {
		if (snapshot == null) {
			return "";
		}
		List<String> columns = publicColumns(snapshot);
		if (columns.isEmpty()) {
			return "";
		}
		Map<String, String> displayNames = userFacingDisplayNames(columns);
		List<Map<String, Object>> data = snapshot.getRows().stream().map(row -> {
			Map<String, Object> dataRow = new LinkedHashMap<>();
			for (String column : columns) {
				Object value = row == null ? null : row.get(column);
				dataRow.put(displayNames.get(column), value == null ? "" : value);
			}
			return dataRow;
		}).toList();
		Map<String, Object> resultSet = new LinkedHashMap<>();
		resultSet.put("column", new ArrayList<>(displayNames.values()));
		resultSet.put("data", data);
		Map<String, Object> payload = new LinkedHashMap<>();
		if (StringUtils.hasText(snapshot.getTitle())) {
			payload.put("title", snapshot.getTitle());
		}
		payload.put("resultSet", resultSet);
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JsonProcessingException ex) {
			log.warn("Unable to serialize the public result set. runtimeRequestId={}",
					explain == null ? null : explain.getRuntimeRequestId(), ex);
			return "";
		}
	}

	/**
	 * RESULT_SET 用户面列名硬不变量：列头与 data keys 一律经
	 * {@link SearchResultColumnNamer#enforceUserFacing}，永不出现物理字段原文；
	 * 多列兜底重名时按序补 2/3 保持唯一。
	 */
	private Map<String, String> userFacingDisplayNames(List<String> columns) {
		Map<String, String> displayNames = new LinkedHashMap<>();
		Set<String> used = new LinkedHashSet<>();
		for (String column : columns) {
			String base = SearchResultColumnNamer.enforceUserFacing(column);
			String candidate = base;
			int index = 2;
			while (!used.add(candidate)) {
				candidate = base + index++;
			}
			displayNames.put(column, candidate);
		}
		return displayNames;
	}

	private boolean hasPublicTableRows(ReportDataSnapshot snapshot) {
		return snapshot != null && snapshot.getRows() != null && snapshot.getRows().size() >= MIN_PUBLIC_TABLE_ROWS
				&& snapshot.getColumns() != null && !snapshot.getColumns().isEmpty();
	}

	private Optional<ReportDataSnapshot> authoritativeSnapshot(AnswerTraceExplainView explain) {
		if (explain == null || explain.getReportDataSnapshots() == null) {
			return Optional.empty();
		}
		List<ReportDataSnapshot> snapshots = explain.getReportDataSnapshots();
		for (int index = snapshots.size() - 1; index >= 0; index--) {
			ReportDataSnapshot snapshot = snapshots.get(index);
			if (snapshot != null && snapshot.getCoverageStatus() != null && snapshot.getRows() != null
					&& !snapshot.getRows().isEmpty()) {
				return Optional.of(snapshot);
			}
		}
		return Optional.empty();
	}

	private int promptPreviewRows() {
		return Math.max(1, dataAgentProperties.getReport().getPromptPreviewRows());
	}

	private String insertDeterministicRankingTable(String report, AnswerTraceExplainView explain) {
		String table = buildRankingTable(explain, "### 完整排名明细", true);
		if (!StringUtils.hasText(table) || report.contains("### 完整排名明细")) {
			return report;
		}
		int sectionStart = report.indexOf("## 关键指标");
		if (sectionStart < 0) {
			return report + "\n\n" + table;
		}
		int nextSection = report.indexOf("\n## ", sectionStart + "## 关键指标".length());
		if (nextSection < 0) {
			return report + "\n\n" + table;
		}
		return report.substring(0, nextSection) + "\n\n" + table + report.substring(nextSection);
	}

	private String stripDuplicateTables(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		String stripped = RANKING_DETAIL_SECTION.matcher(text).replaceAll("");
		stripped = MARKDOWN_TABLE_BLOCK.matcher(stripped).replaceAll("");
		return stripped.replaceAll("(?m)[ \\t]+$", "").replaceAll("\\n{3,}", "\n\n").trim();
	}

	private String appendRankingTable(String text, AnswerTraceExplainView explain, String title) {
		String table = buildRankingTable(explain, title, false);
		if (!StringUtils.hasText(table) || text.contains(title)) {
			return text;
		}
		return StringUtils.hasText(text) ? text.trim() + "\n\n" + table : table;
	}

	private String buildRankingTable(AnswerTraceExplainView explain, String title, boolean reportMode) {
		Optional<ReportDataSnapshot> snapshotOptional = reportMode ? snapshotSelector.select(explain).ranking()
				: authoritativeSnapshot(explain);
		if (snapshotOptional.isEmpty() || !Boolean.TRUE.equals(snapshotOptional.get().getRanking())) {
			return "";
		}
		ReportDataSnapshot snapshot = snapshotOptional.get();
		List<String> columns = snapshot.getColumns()
			.stream()
			.filter(column -> !ReportColumnSemantics.isInternalColumn(column))
			.filter(column -> !ReportColumnSemantics.isIdentifierColumn(column))
			.filter(column -> !ReportColumnSemantics.isIdentifierValueColumn(snapshot.getRows(), column))
			.limit(MAX_STRUCTURED_CONTEXT_COLUMNS)
			.toList();
		if (columns.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder(title).append("（").append(snapshot.getRows().size())
			.append("条）\n\n");
		String coverageNote = coverageNote(snapshot);
		if (StringUtils.hasText(coverageNote)) {
			builder.append(coverageNote).append("\n\n");
		}
		builder.append("| 排名 | ").append(String.join(" | ", columns)).append(" |\n");
		builder.append("| ---: | ")
			.append(columns.stream()
				.map(ignored -> "---")
				.collect(java.util.stream.Collectors.joining(" | ")))
			.append(" |\n");
		int rank = 0;
		for (Map<String, Object> row : snapshot.getRows()) {
			builder.append("| ").append(++rank).append(" | ");
			builder.append(columns.stream().map(column -> markdownCell(row.get(column)))
				.collect(java.util.stream.Collectors.joining(" | ")));
			builder.append(" |\n");
		}
		return builder.toString().trim();
	}

	private String coverageNote(ReportDataSnapshot snapshot) {
		int returnedRows = snapshot.getReturnedRows() == null ? snapshot.getRows().size() : snapshot.getReturnedRows();
		if (snapshot.getCoverageStatus() == null) {
			return "";
		}
		return switch (snapshot.getCoverageStatus()) {
			case FULL -> "已完整展示本次要求的 %d 条排名结果。".formatted(returnedRows);
			case SOURCE_SHORT -> "实际数据仅有 %d 条，以下按真实数量完整展示。".formatted(returnedRows);
			case PLATFORM_LIMITED -> "受平台单次查询上限限制，本次覆盖前 %d 条。".formatted(returnedRows);
			case LIMIT_REACHED_UNKNOWN -> "本次结果达到 %d 条限制，无法确认限制之后是否仍有更多数据。".formatted(returnedRows);
		};
	}

	private String markdownCell(Object value) {
		return value == null ? "" : String.valueOf(value).replace("|", "\\|").replace('\r', ' ').replace('\n', ' ');
	}

	private String formatDecimal(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

	private String cleanMetricName(String rawName) {
		if (!StringUtils.hasText(rawName)) {
			return "";
		}
		return rawName.replace("*", "")
			.replace("`", "")
			.replace("-", "")
			.replace("•", "")
			.replaceAll("^\\d+[.、)]\\s*", "")
			.trim();
	}

	private boolean isDetailFieldMetric(String name) {
		String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
		return normalized.contains("编号") || normalized.contains("编码") || normalized.contains("名称")
				|| normalized.contains("状态") || normalized.contains("周期") || normalized.contains("日期")
				|| normalized.contains("明细") || normalized.contains("产品") || normalized.contains("客户")
				|| normalized.contains("账单") || normalized.contains("id") || normalized.contains("code")
				|| normalized.contains("no") || normalized.contains("date") || normalized.contains("time")
				|| normalized.contains("status");
	}

	private Set<String> forbiddenTokens(AnswerTraceExplainView explain) {
		Set<String> tokens = new LinkedHashSet<>();
		if (explain == null) {
			return tokens;
		}
		addToken(tokens, explain.getDatasource());
		addToken(tokens, explain.getSql());
		addSensitiveTokens(tokens, explain.getUsedTables());
		addSensitiveTokens(tokens, explain.getUsedColumns());
		if (explain.getSemanticHits() != null) {
			for (SemanticHitView hit : explain.getSemanticHits()) {
				addSensitiveToken(tokens, hit.getTableName());
				addSensitiveToken(tokens, hit.getColumnName());
			}
		}
		if (explain.getToolSteps() != null) {
			for (ToolStepView step : explain.getToolSteps()) {
				addToken(tokens, step.getToolName());
				addToken(tokens, step.getDetail());
				addToken(tokens, step.getDatasource());
			}
		}
		return tokens;
	}

	private String ensureFixedSections(String report) {
		String normalized = report.startsWith("# ") ? report : "# 分析报告\n\n" + report;
		StringBuilder builder = new StringBuilder(normalized);
		for (String section : List.of("## 核心结论", "## 关键指标", "## 可视化图表", "## 业务解读", "## 风险提示",
				"## 行动建议")) {
			if (!normalized.contains(section)) {
				builder.append("\n\n").append(section).append("\n").append(defaultSectionText(section));
			}
		}
		return builder.toString();
	}

	private String defaultSectionText(String section) {
		if ("## 可视化图表".equals(section)) {
			return "本次结果暂无适合生成图表的结构化数据。";
		}
		return "暂无更多可确认内容。";
	}

	private String insightReport(AnswerTraceExplainView explain) {
		ReportSnapshotSelector.Selection selection = snapshotSelector.select(explain);
		InsightEngine.InsightPack pack = insightEngine.analyze(selection, explain);
		String answer = sanitizeText(safe(explain == null ? null : explain.getAnswer()), explain);
		return """
				# 分析报告

				## 核心结论
				%s

				## 关键指标
				%s

				## 可视化图表
				%s

				## 业务解读
				%s

				## 风险提示
				%s

				## 行动建议
				%s
				""".formatted(coreConclusion(answer, pack), keyMetricsSection(pack, answer),
				chartSkipText(pack.chartSkipReason()), joinLines(pack.interpretationLines()),
				joinLines(pack.riskLines()), joinLines(pack.actionLines()));
	}

	private String coreConclusion(String answer, InsightEngine.InsightPack pack) {
		String facts = joinLines(pack.factLines());
		if (StringUtils.hasText(facts)) {
			return facts;
		}
		if (StringUtils.hasText(answer)) {
			return answer;
		}
		return "本次分析未形成可用结论。";
	}

	private String keyMetricsSection(InsightEngine.InsightPack pack, String answer) {
		String metrics = joinLines(pack.kpiLines());
		String table = markdownTableFrom(answer);
		if (StringUtils.hasText(metrics) && StringUtils.hasText(table)) {
			return metrics + "\n\n" + table;
		}
		if (StringUtils.hasText(metrics)) {
			return metrics;
		}
		return StringUtils.hasText(table) ? table : "";
	}

	private String markdownTableFrom(String answer) {
		if (!StringUtils.hasText(answer) || !answer.contains("|")) {
			return "";
		}
		StringBuilder table = new StringBuilder();
		boolean inTable = false;
		for (String line : answer.split("\\R")) {
			if (line.trim().startsWith("|")) {
				inTable = true;
				table.append(line).append('\n');
			}
			else if (inTable) {
				break;
			}
		}
		return table.toString().trim();
	}

	private String joinLines(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return "";
		}
		return String.join(System.lineSeparator(), lines);
	}

	private String chartSkipText(String reason) {
		if (!StringUtils.hasText(reason)) {
			return "本次结果暂无适合生成图表的结构化数据。";
		}
		return switch (reason) {
			case InsightEngine.SINGLE_ROW_DETAIL -> "本次结果是单条明细，不适合生成对比或趋势图。";
			case InsightEngine.NO_NUMERIC_METRIC -> "本轮结果没有可绘制的数值指标。";
			case InsightEngine.NO_SNAPSHOT -> "本轮没有可确认的结构化结果，因此未生成图表。";
			default -> "本次结果暂无适合生成图表的结构化数据。";
		};
	}

	private String fallbackReport(AnswerTraceExplainView explain) {
		String answer = safe(explain == null ? null : explain.getAnswer());
		if (!StringUtils.hasText(answer)) {
			answer = "本次分析未形成可用结论。";
		}
		return """
				# 分析报告

				## 核心结论
				%s

				## 关键指标
				暂无可结构化提取的关键指标。

				## 可视化图表
				本次结果暂无适合生成图表的结构化数据。

				## 业务解读
				本报告基于本次已完成分析结果生成，内部查询细节已隐藏。

				## 风险提示
				请结合实际业务口径复核关键结论。

				## 行动建议
				建议围绕核心结论继续跟进异常项、趋势变化和责任归属。
				""".formatted(answer);
	}

	private void addLine(List<String> lines, String label, String value) {
		if (StringUtils.hasText(value)) {
			lines.add(label + "：" + value.trim());
		}
	}

	private void addToken(Set<String> tokens, String value) {
		if (StringUtils.hasText(value)) {
			String trimmed = value.trim();
			if (trimmed.length() <= 200) {
				tokens.add(trimmed);
			}
		}
	}

	private void addSensitiveToken(Set<String> tokens, String value) {
		if (isSensitivePhysicalToken(value)) {
			addToken(tokens, value);
		}
	}

	private void addSensitiveTokens(Set<String> tokens, List<String> values) {
		if (values != null) {
			values.forEach(value -> addSensitiveToken(tokens, value));
		}
	}

	private boolean isSensitivePhysicalToken(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		String trimmed = value.trim();
		return trimmed.contains(".") || trimmed.contains("_") || isIdentifierToken(trimmed)
				|| isLikelyPhysicalToken(trimmed);
	}

	private boolean isIdentifierToken(String token) {
		return token != null && token.matches("[A-Za-z_][A-Za-z0-9_.$-]*");
	}

	private boolean isLikelyPhysicalToken(String value) {
		String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "").trim();
		return normalized.equals("id") || normalized.endsWith("id") || normalized.endsWith("code")
				|| normalized.endsWith("no") || normalized.contains("time") || normalized.contains("date")
				|| normalized.contains("amount") || normalized.contains("amt") || normalized.contains("count")
				|| normalized.contains("cnt") || normalized.contains("num") || normalized.contains("qty")
				|| normalized.contains("price") || normalized.contains("cost") || normalized.contains("fee")
				|| normalized.contains("rate") || normalized.contains("type") || normalized.contains("status")
				|| normalized.contains("deleted");
	}

	private String firstText(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private boolean isSupportedInlineChartType(String chartType) {
		return "bar".equals(chartType) || "bar-multi".equals(chartType) || "line".equals(chartType)
				|| "pie".equals(chartType);
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value == null || value.isNull() ? "" : value.asText("").trim();
	}

	private List<String> stringArray(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			String value = item.asText("").trim();
			if (StringUtils.hasText(value)) {
				values.add(value);
			}
		}
		return values;
	}

	private boolean isSafeInlineChartText(String text, AnswerTraceExplainView explain) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		String normalized = text.toLowerCase(Locale.ROOT);
		if (SQL_STATEMENT.matcher(text).find()) {
			return false;
		}
		if (normalized.contains("<script") || normalized.contains("javascript:") || normalized.contains("function(")
				|| normalized.contains("=>")) {
			return false;
		}
		for (String token : forbiddenTokens(explain)) {
			if (StringUtils.hasText(token) && normalized.contains(token.toLowerCase(Locale.ROOT))) {
				return false;
			}
		}
		for (String term : FORBIDDEN_TECH_TERMS) {
			if (normalized.contains(term.toLowerCase(Locale.ROOT))) {
				return false;
			}
		}
		return true;
	}

	private BigDecimal decimal(Object value) {
		if (value instanceof BigDecimal decimal) {
			return decimal;
		}
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
		}
		if (value instanceof JsonNode node) {
			if (!node.isNumber() && !node.isTextual()) {
				return null;
			}
			return decimal(node.asText());
		}
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).replace(",", "").replace("%", "").trim();
		if (!StringUtils.hasText(text)) {
			return null;
		}
		try {
			return new BigDecimal(text);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private String abbreviate(String value, int maxChars) {
		if (value == null || value.length() <= maxChars) {
			return value == null ? "" : value;
		}
		return value.substring(0, maxChars) + "\n（内容过长，已截断）";
	}

}
