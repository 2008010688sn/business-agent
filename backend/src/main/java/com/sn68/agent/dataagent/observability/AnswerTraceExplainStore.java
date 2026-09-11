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
package com.sn68.agent.dataagent.observability;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.dto.GroundedFacts;
import com.sn68.agent.dataagent.agentscope.dto.GroundedKey;
import com.sn68.agent.dataagent.agentscope.runtime.QueryClarifyService.QueryClarifyAssessment;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerResult;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerAction;
import com.sn68.agent.dataagent.agentscope.tool.datasource.ResultCoverageStatus;
import com.sn68.agent.dataagent.agentscope.tool.semantic.SemanticModelSearchHit;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.DomainKnowledgeSearchResult;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.KnowledgeHit;
import com.sn68.agent.dataagent.service.report.ReportColumnSemantics;
import com.sn68.agent.dataagent.service.report.ReportDataSnapshot;
import com.sn68.agent.dataagent.service.report.SearchResultColumnNamer;
import com.sn68.agent.dataagent.service.report.SkillReportProfile;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.util.TopNLimitResolver;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 答案可解释性追踪的进程内存储：按会话/请求记录工具执行步骤、知识与语义命中、报表快照等轨迹，
 * 供"这个答案怎么来的"类问题回放；容量按会话数与每会话请求数双上限做 LRU 淘汰，仅存最近数据。
 */
@Component
public class AnswerTraceExplainStore {

	private static final int MAX_SESSION_COUNT = 128;

	private static final int MAX_REQUEST_COUNT_PER_SESSION = 24;

	private static final int MAX_REPORT_SNAPSHOT_COUNT = 5;

	private static final int ATTACH_MAX_ROWS = 200;

	private static final int ATTACH_MAX_COLUMNS = 12;

	public static final String STEP_TYPE_EXECUTION = "EXECUTION";

	public static final String STEP_TYPE_EXPLAIN = "EXPLAIN";

	private final Object monitor = new Object();

	private final ThreadLocal<ExplainContext> currentContext = new ThreadLocal<>();

	private final LinkedHashMap<String, LinkedHashMap<String, ExplainAssembly>> explainsBySession = new LinkedHashMap<>(
			32, 0.75f, true);

	public void openScope(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		synchronized (monitor) {
			ExplainAssembly assembly = resolveAssemblyLocked(request.getThreadId(), request.getRuntimeRequestId());
			applyRequestContext(assembly, request);
			assembly.updatedAt = Instant.now().toEpochMilli();
			currentContext.set(new ExplainContext(request.getThreadId(), request.getRuntimeRequestId()));
			evictOverflowLocked();
		}
	}

	public void closeScope() {
		currentContext.remove();
	}

	public void recordFinalAnswer(String answer) {
		withCurrentAssembly(assembly -> {
			assembly.answer = answer;
			assembly.updatedAt = Instant.now().toEpochMilli();
		});
	}

	public void recordWarning(String warning) {
		if (!StringUtils.hasText(warning)) {
			return;
		}
		withCurrentAssembly(assembly -> {
			assembly.warnings.add(warning.trim());
			assembly.updatedAt = Instant.now().toEpochMilli();
		});
	}

	public void recordClarifyAssessment(AgentRequest request, QueryClarifyAssessment assessment) {
		if (assessment == null) {
			return;
		}
		withAssembly(request, assembly -> applyClarifyAssessment(assembly, assessment));
	}

	public void recordLinkResolve(AgentRequest request, GroundedFacts facts) {
		if (facts == null) {
			return;
		}
		withAssembly(request, assembly -> applyLinkResolve(assembly, facts));
	}

	public void recordSemanticSearch(String query, String summary, List<SemanticModelSearchHit> hits) {
		withCurrentAssembly(assembly -> applySemanticSearch(assembly, query, summary, hits));
	}

	public void recordSemanticSearch(AgentRequest request, String query, String summary,
			List<SemanticModelSearchHit> hits) {
		withAssembly(request, assembly -> applySemanticSearch(assembly, query, summary, hits));
	}

	public void recordKnowledgeSearch(DomainKnowledgeSearchResult result) {
		if (result == null) {
			return;
		}
		withCurrentAssembly(assembly -> applyKnowledgeSearch(assembly, result));
	}

	public void recordKnowledgeSearch(AgentRequest request, DomainKnowledgeSearchResult result) {
		if (result == null) {
			return;
		}
		withAssembly(request, assembly -> applyKnowledgeSearch(assembly, result));
	}

	public void recordDatasourceResult(DatasourceExplorerResult result) {
		if (result == null) {
			return;
		}
		withCurrentAssembly(assembly -> applyDatasourceResult(assembly, result));
	}

	public void recordDatasourceResult(AgentRequest request, DatasourceExplorerResult result) {
		if (result == null) {
			return;
		}
		withAssembly(request, assembly -> applyDatasourceResult(assembly, result));
	}

	/**
	 * 仅报告模式父请求使用：把协作者 SEARCH 快照压缩后挂到父 assembly，避免隔离 threadId 被 LRU 挤掉后父报告没数。
	 */
	public void attachSnapshots(AgentRequest parent, List<ReportDataSnapshot> snapshots) {
		if (parent == null || CollectionUtils.isEmpty(snapshots)) {
			return;
		}
		withAssembly(parent, assembly -> {
			for (ReportDataSnapshot snapshot : snapshots) {
				ReportDataSnapshot compact = compactSnapshot(snapshot);
				if (compact == null) {
					continue;
				}
				assembly.reportDataSnapshots.add(compact);
				while (assembly.reportDataSnapshots.size() > MAX_REPORT_SNAPSHOT_COUNT) {
					assembly.reportDataSnapshots.remove(0);
				}
			}
			assembly.updatedAt = Instant.now().toEpochMilli();
		});
	}

	public void recordToolExecution(AgentRequest request, ToolExecutionRecord record) {
		if (record == null) {
			return;
		}
		withAssembly(request, assembly -> applyToolExecution(assembly, null, record));
	}

	public Integer reserveToolExecutionSequenceNo(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return null;
		}
		synchronized (monitor) {
			ExplainAssembly assembly = resolveAssemblyLocked(request.getThreadId(), request.getRuntimeRequestId());
			applyRequestContext(assembly, request);
			Integer sequenceNo = nextToolExecutionSequenceNo(assembly);
			assembly.updatedAt = Instant.now().toEpochMilli();
			evictOverflowLocked();
			return sequenceNo;
		}
	}

	public void recordToolExecution(AgentRequest request, Integer sequenceNo, ToolExecutionRecord record) {
		if (record == null) {
			return;
		}
		withAssembly(request, assembly -> applyToolExecution(assembly, sequenceNo, record));
	}

	public Optional<AnswerTraceExplainView> getExplain(String sessionId, String runtimeRequestId) {
		if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(runtimeRequestId)) {
			return Optional.empty();
		}
		synchronized (monitor) {
			LinkedHashMap<String, ExplainAssembly> explainsByRequest = explainsBySession.get(sessionId);
			if (explainsByRequest == null) {
				return Optional.empty();
			}
			ExplainAssembly assembly = explainsByRequest.get(runtimeRequestId);
			if (assembly == null) {
				return Optional.empty();
			}
			return Optional.of(assembly.toView());
		}
	}

	public Optional<AnswerTraceExplainView> getLatestExplain(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return Optional.empty();
		}
		synchronized (monitor) {
			LinkedHashMap<String, ExplainAssembly> explainsByRequest = explainsBySession.get(sessionId);
			if (explainsByRequest == null || explainsByRequest.isEmpty()) {
				return Optional.empty();
			}
			ExplainAssembly latestAssembly = explainsByRequest.values()
				.stream()
				.max(java.util.Comparator.comparingLong(assembly -> assembly.updatedAt))
				.orElse(null);
			return latestAssembly == null ? Optional.empty() : Optional.of(latestAssembly.toView());
		}
	}

	public Optional<ExplainMirrorSummary> getMirrorSummary(String sessionId, String runtimeRequestId) {
		return getExplain(sessionId, runtimeRequestId).map(explain -> {
			ExplainMirrorSummary summary = new ExplainMirrorSummary();
			summary.setDatasource(explain.getDatasource());
			summary.setSemanticHitCount(explain.getSemanticHits() == null ? 0 : explain.getSemanticHits().size());
			summary.setKnowledgeHitCount(explain.getKnowledgeHits() == null ? 0 : explain.getKnowledgeHits().size());
			summary.setToolStepCount(explain.getToolSteps() == null ? 0 : explain.getToolSteps().size());
			return summary;
		});
	}

	private void withCurrentAssembly(java.util.function.Consumer<ExplainAssembly> consumer) {
		ExplainContext context = currentContext.get();
		if (context == null) {
			return;
		}
		synchronized (monitor) {
			ExplainAssembly assembly = resolveAssemblyLocked(context.sessionId, context.runtimeRequestId);
			consumer.accept(assembly);
		}
	}

	private void withAssembly(AgentRequest request, java.util.function.Consumer<ExplainAssembly> consumer) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		synchronized (monitor) {
			ExplainAssembly assembly = resolveAssemblyLocked(request.getThreadId(), request.getRuntimeRequestId());
			applyRequestContext(assembly, request);
			consumer.accept(assembly);
			evictOverflowLocked();
		}
	}

	private ExplainAssembly resolveAssemblyLocked(String sessionId, String runtimeRequestId) {
		LinkedHashMap<String, ExplainAssembly> explainsByRequest = explainsBySession.computeIfAbsent(sessionId,
				ignored -> new LinkedHashMap<>(8, 0.75f, true));
		return explainsByRequest.computeIfAbsent(runtimeRequestId, ignored -> new ExplainAssembly());
	}

	private void applyRequestContext(ExplainAssembly assembly, AgentRequest request) {
		if (assembly == null || request == null) {
			return;
		}
		if (StringUtils.hasText(request.getThreadId())) {
			assembly.sessionId = request.getThreadId();
		}
		if (StringUtils.hasText(request.getRuntimeRequestId())) {
			assembly.runtimeRequestId = request.getRuntimeRequestId();
		}
		if (StringUtils.hasText(request.getAgentId())) {
			assembly.agentId = request.getAgentId();
		}
		assembly.chatModelConfigId = request.getChatModelConfigId();
		assembly.rootRuntimeRequestId = request.getRootRuntimeRequestId();
		assembly.parentRuntimeRequestId = request.getParentRuntimeRequestId();
		assembly.tenantId = request.getTenantIdSnapshot();
		assembly.tenantCode = request.getTenantCodeSnapshot();
		assembly.userId = request.getUserIdSnapshot();
		assembly.userNickName = request.getUserNickNameSnapshot();
		assembly.agentName = request.getAgentNameSnapshot();
		assembly.requestSource = request.getRequestSource();
		if (StringUtils.hasText(request.getQuery())) {
			assembly.question = request.getQuery();
		}
		if (StringUtils.hasText(request.getRoutedSkillCode())) {
			assembly.routedSkillCode = request.getRoutedSkillCode();
		}
		if (request.getRoutedSkillVersionId() != null) {
			assembly.routedSkillVersionId = request.getRoutedSkillVersionId();
		}
		if (request.getRoutedSkillReportProfile() != null) {
			assembly.skillReportProfile = request.getRoutedSkillReportProfile();
		}
		if (request.getTemporalContext() != null) {
			assembly.temporalContext = Map.of(
					"referenceInstant", request.getTemporalContext().referenceInstant().toString(),
					"zoneId", request.getTemporalContext().zoneId().getId(),
					"localDate", request.getTemporalContext().localDate().toString(),
					"locale", request.getTemporalContext().locale().toLanguageTag(),
					"weekStartsOn", request.getTemporalContext().weekStartsOn().name());
		}
		if (request.getRoutedSkillExecutionMode() == SkillExecutionMode.DETERMINISTIC) {
			assembly.deterministicSkill = true;
		}
		if (assembly.deterministicSkill) {
			redactDeterministicInternals(assembly);
		}
	}

	private void applySemanticSearch(ExplainAssembly assembly, String query, String summary,
			List<SemanticModelSearchHit> hits) {
		assembly.toolSteps.add(ToolStepView.builder()
			.sequenceNo(nextToolStepSequenceNo(assembly))
			.stepType(STEP_TYPE_EXPLAIN)
			.toolName(AgentModelToolName.SEMANTIC_MODEL_SEARCH)
			.title("语义匹配")
			.summary(summary)
			.detail(assembly.deterministicSkill ? null : query)
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		if (hits != null) {
			for (SemanticModelSearchHit hit : hits) {
				if (hit == null) {
					continue;
				}
				assembly.semanticHits.add(SemanticHitView.builder()
					.tableName(hit.getTableName())
					.columnName(hit.getColumnName())
					.businessName(hit.getBusinessName())
					.businessDescription(hit.getBusinessDescription())
					.synonyms(hit.getSynonyms())
					.columnComment(hit.getColumnComment())
					.dataType(hit.getDataType())
					.matchedBy(hit.getMatchedBy())
					.score(hit.getScore())
					.relationHint(hit.getRelationHint())
					.build());
			}
		}
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyKnowledgeSearch(ExplainAssembly assembly, DomainKnowledgeSearchResult result) {
		assembly.toolSteps.add(ToolStepView.builder()
			.sequenceNo(nextToolStepSequenceNo(assembly))
			.stepType(STEP_TYPE_EXPLAIN)
			.toolName(AgentModelToolName.DOMAIN_BUSINESS_KNOWLEDGE_SEARCH)
			.title("业务知识检索")
			.summary("检索到 %d 条知识命中".formatted(result.hits() == null ? 0 : result.hits().size()))
			.detail(assembly.deterministicSkill ? null : result.resolution())
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		if (result.hits() != null) {
			for (KnowledgeHit hit : result.hits()) {
				if (hit == null) {
					continue;
				}
				assembly.knowledgeHits.add(KnowledgeHitView.builder()
					.vectorType(hit.vectorType())
					.knowledgeId(hit.knowledgeId())
					.title(hit.title())
					.summary(hit.summary())
					.snippet(hit.snippet())
					.source(hit.source())
					.concreteType(hit.concreteType())
					.build());
			}
		}
		if (result.warnings() != null) {
			assembly.warnings
				.addAll(result.warnings().stream().filter(StringUtils::hasText).map(String::trim).toList());
		}
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyDatasourceResult(ExplainAssembly assembly, DatasourceExplorerResult result) {
		if (assembly.deterministicSkill) {
			redactDeterministicInternals(assembly);
		}
		else {
			if (StringUtils.hasText(result.getDatasource())) {
				assembly.datasource = result.getDatasource();
			}
			if (StringUtils.hasText(result.getSql())) {
				assembly.sql = result.getSql();
			}
			if (result.getUsedTables() != null && !result.getUsedTables().isEmpty()) {
				assembly.usedTables.clear();
				assembly.usedTables
					.addAll(result.getUsedTables().stream().filter(StringUtils::hasText).map(String::trim).toList());
			}
			if (result.getUsedColumns() != null && !result.getUsedColumns().isEmpty()) {
				assembly.usedColumns.clear();
				assembly.usedColumns
					.addAll(result.getUsedColumns().stream().filter(StringUtils::hasText).map(String::trim).toList());
			}
			if (result.getRelationEvidence() != null && !result.getRelationEvidence().isEmpty()) {
				assembly.relationEvidence.clear();
				assembly.relationEvidence.addAll(result.getRelationEvidence());
			}
		}
		if (StringUtils.hasText(result.getResultScope())) {
			assembly.resultScope = result.getResultScope();
		}
		if (!assembly.deterministicSkill && StringUtils.hasText(result.getDecisionReason())) {
			assembly.decisionReason = result.getDecisionReason();
		}
		if (!assembly.deterministicSkill) {
			if (result.getToolDecisionReasons() != null && !result.getToolDecisionReasons().isEmpty()) {
				assembly.toolDecisionReasons.clear();
				assembly.toolDecisionReasons.addAll(
						result.getToolDecisionReasons().stream().filter(StringUtils::hasText).map(String::trim).toList());
			}
			if (result.getResultScopeDetails() != null && !result.getResultScopeDetails().isEmpty()) {
				assembly.resultScopeDetails.clear();
				assembly.resultScopeDetails.addAll(
						result.getResultScopeDetails().stream().filter(StringUtils::hasText).map(String::trim).toList());
			}
		}
		buildReportSnapshot(assembly, result).ifPresent(snapshot -> {
			assembly.reportDataSnapshots.add(snapshot);
			while (assembly.reportDataSnapshots.size() > MAX_REPORT_SNAPSHOT_COUNT) {
				assembly.reportDataSnapshots.remove(0);
			}
		});
		assembly.toolSteps.add(ToolStepView.builder()
			.sequenceNo(nextToolStepSequenceNo(assembly))
			.stepType(STEP_TYPE_EXPLAIN)
			.toolName("datasource.explorer")
			.title(result.getAction())
			.summary(result.getSummary())
			.detail(assembly.deterministicSkill ? null : result.getSql())
			.datasource(assembly.deterministicSkill ? null : result.getDatasource())
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyToolExecution(ExplainAssembly assembly, Integer sequenceNo, ToolExecutionRecord record) {
		long timestampEpochMs = record.endEpochMs() > 0 ? record.endEpochMs() : record.startEpochMs();
		assembly.toolSteps.add(ToolStepView.builder()
			.sequenceNo(resolveToolExecutionSequenceNo(assembly, sequenceNo))
			.stepType(STEP_TYPE_EXECUTION)
			.toolName(record.toolName())
			.title("工具调用")
			.summary(record.summary())
			.detail(assembly.deterministicSkill ? null : record.detail())
			.status(record.status())
			.startEpochMs(record.startEpochMs())
			.endEpochMs(record.endEpochMs())
			.durationMs(record.durationMs())
			.inputSummary(assembly.deterministicSkill ? null : record.inputSummary())
			.outputSummary(assembly.deterministicSkill ? null : record.outputSummary())
			.errorCode(record.errorCode())
			.errorMessage(assembly.deterministicSkill ? null : record.errorMessage())
			.timestampEpochMs(timestampEpochMs)
			.build());
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void redactDeterministicInternals(ExplainAssembly assembly) {
		assembly.datasource = null;
		assembly.sql = null;
		assembly.decisionReason = null;
		assembly.usedTables.clear();
		assembly.usedColumns.clear();
		assembly.relationEvidence.clear();
		assembly.toolDecisionReasons.clear();
		assembly.resultScopeDetails.clear();
		assembly.toolSteps.forEach(step -> {
			step.setDetail(null);
			step.setDatasource(null);
			step.setInputSummary(null);
			step.setOutputSummary(null);
			step.setErrorMessage(null);
		});
	}

	private int nextToolStepSequenceNo(ExplainAssembly assembly) {
		return assembly == null ? 1 : assembly.toolSteps.size() + 1;
	}

	private int nextToolExecutionSequenceNo(ExplainAssembly assembly) {
		if (assembly == null) {
			return 1;
		}
		return assembly.nextToolExecutionSequenceNo++;
	}

	private int resolveToolExecutionSequenceNo(ExplainAssembly assembly, Integer sequenceNo) {
		if (assembly == null) {
			return sequenceNo == null || sequenceNo <= 0 ? 1 : sequenceNo;
		}
		if (sequenceNo != null && sequenceNo > 0) {
			assembly.nextToolExecutionSequenceNo = Math.max(assembly.nextToolExecutionSequenceNo, sequenceNo + 1);
			return sequenceNo;
		}
		return nextToolExecutionSequenceNo(assembly);
	}

	private ReportDataSnapshot compactSnapshot(ReportDataSnapshot snapshot) {
		if (snapshot == null || CollectionUtils.isEmpty(snapshot.getRows())
				|| CollectionUtils.isEmpty(snapshot.getColumns())) {
			return null;
		}
		List<String> columns = snapshot.getColumns()
			.stream()
			.filter(column -> !ReportColumnSemantics.isIdentifierColumn(column))
			.filter(column -> !ReportColumnSemantics.isIdentifierValueColumn(snapshot.getRows(), column))
			.limit(ATTACH_MAX_COLUMNS)
			.toList();
		if (columns.isEmpty()) {
			return null;
		}
		List<Map<String, Object>> rows = snapshot.getRows().stream().limit(ATTACH_MAX_ROWS).map(row -> {
			Map<String, Object> compact = new LinkedHashMap<>();
			for (String column : columns) {
				if (row != null && row.containsKey(column)) {
					compact.put(column, row.get(column));
				}
			}
			return compact;
		}).filter(row -> !row.isEmpty()).toList();
		if (rows.isEmpty()) {
			return null;
		}
		return ReportDataSnapshot.builder()
			.title(snapshot.getTitle())
			.summary(snapshot.getSummary())
			.columns(columns)
			.rows(rows)
			.totalRows(snapshot.getTotalRows())
			.truncated(Boolean.TRUE.equals(snapshot.getTruncated()) || snapshot.getRows().size() > ATTACH_MAX_ROWS)
			.requestedRows(snapshot.getRequestedRows())
			.returnedRows(snapshot.getReturnedRows())
			.appliedLimit(snapshot.getAppliedLimit())
			.hasMore(snapshot.getHasMore())
			.coverageStatus(snapshot.getCoverageStatus())
			.ranking(snapshot.getRanking())
			.probe(snapshot.getProbe())
			.build();
	}

	private Optional<ReportDataSnapshot> buildReportSnapshot(ExplainAssembly assembly, DatasourceExplorerResult result) {
		if (result == null || !DatasourceExplorerAction.SEARCH.name().equals(result.getAction())
				|| !Boolean.TRUE.equals(result.getSearchReady()) || result.getRows() == null || result.getRows().isEmpty()) {
			return Optional.empty();
		}
		List<String> originalColumns = extractResultColumns(result);
		if (originalColumns.isEmpty()) {
			return Optional.empty();
		}
		Map<String, String> displayNames = buildDisplayNames(result, assembly);
		boolean probe = isProbeSearch(result, originalColumns);
		List<String> keptColumns = originalColumns.stream()
			.filter(column -> StringUtils.hasText(displayNames.get(column)))
			.filter(column -> !ReportColumnSemantics.isInternalColumn(column))
			.filter(column -> !ReportColumnSemantics.isInternalColumn(displayNames.get(column)))
			.filter(column -> !ReportColumnSemantics.isIdentifierColumn(column))
			.filter(column -> !ReportColumnSemantics.isIdentifierColumn(displayNames.get(column)))
			.filter(column -> SearchResultColumnNamer.isMetricLabel(displayNames.get(column))
					|| !ReportColumnSemantics.isIdentifierValueColumn(result.getRows(), column))
			.toList();
		if (keptColumns.isEmpty()) {
			return Optional.empty();
		}
		List<String> displayColumns = keptColumns.stream().map(displayNames::get).toList();
		List<Map<String, Object>> safeRows = result.getRows()
			.stream()
			.map(row -> toSafeReportRow(row, keptColumns, displayNames))
			.filter(row -> !row.isEmpty())
			.toList();
		if (safeRows.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(ReportDataSnapshot.builder()
			.title(ReportColumnSemantics.metricSnapshotTitle(displayColumns))
			.summary(result.getResultScope())
			.columns(displayColumns)
			.rows(safeRows)
			.totalRows(result.getReturnedRows() == null ? result.getRows().size() : result.getReturnedRows())
			.truncated(result.getCoverageStatus() == ResultCoverageStatus.PLATFORM_LIMITED
					|| result.getCoverageStatus() == ResultCoverageStatus.LIMIT_REACHED_UNKNOWN)
			.requestedRows(result.getRequestedRows())
			.returnedRows(result.getReturnedRows())
			.appliedLimit(result.getAppliedLimit())
			.hasMore(result.getHasMore())
			.coverageStatus(result.getCoverageStatus())
			.ranking(assembly != null && TopNLimitResolver.isRankingQuery(assembly.question))
			.probe(probe)
			.build());
	}

	private boolean isProbeSearch(DatasourceExplorerResult result, List<String> originalColumns) {
		if (originalColumns != null && originalColumns.stream().anyMatch(ReportColumnSemantics::isInternalColumn)) {
			return true;
		}
		String sql = result == null ? null : result.getSql();
		if (!StringUtils.hasText(sql)) {
			return false;
		}
		int rows = result.getReturnedRows() != null ? result.getReturnedRows()
				: result.getRows() == null ? 0 : result.getRows().size();
		return rows <= 5 && !sql.matches("(?is).*\\bgroup\\s+by\\b.*");
	}

	private List<String> extractResultColumns(DatasourceExplorerResult result) {
		List<String> columns = new ArrayList<>();
		if (result.getColumns() != null) {
			for (Map<String, Object> column : result.getColumns()) {
				Object name = column == null ? null : column.get("name");
				if (name != null && StringUtils.hasText(String.valueOf(name))) {
					columns.add(String.valueOf(name).trim());
				}
			}
		}
		if (columns.isEmpty() && result.getRows() != null && !result.getRows().isEmpty()) {
			columns.addAll(result.getRows().get(0).keySet().stream().map(String::valueOf).toList());
		}
		return columns.stream().filter(StringUtils::hasText).distinct().toList();
	}

	private Map<String, String> buildDisplayNames(DatasourceExplorerResult result, ExplainAssembly assembly) {
		List<String> columns = extractResultColumns(result);
		SearchResultColumnNamer.Lexicon lexicon = SearchResultColumnNamer.lexicon()
			.addHeaders(result == null ? List.of() : result.getColumns())
			.addSemanticHits(assembly == null ? List.of() : assembly.semanticHits);
		return SearchResultColumnNamer.resolve(columns, assembly == null ? null : assembly.sql, lexicon);
	}

	private Map<String, Object> toSafeReportRow(Map<String, Object> row, List<String> columns,
			Map<String, String> displayNames) {
		Map<String, Object> safeRow = new LinkedHashMap<>();
		for (String column : columns) {
			Object value = row.get(column);
			String displayName = displayNames.get(column);
			if (!StringUtils.hasText(displayName)) {
				continue;
			}
			if (value == null || !StringUtils.hasText(String.valueOf(value).trim())) {
				if (!SearchResultColumnNamer.isMetricLabel(displayName)) {
					continue;
				}
				value = BigDecimal.ZERO;
			}
			safeRow.put(displayName, normalizeReportValue(value));
		}
		return safeRow;
	}

	private Object normalizeReportValue(Object value) {
		BigDecimal number = parseNumber(value);
		if (number != null) {
			return number;
		}
		String text = String.valueOf(value).trim();
		if (text.length() > 80) {
			return text.substring(0, 80);
		}
		return text;
	}

	private BigDecimal parseNumber(Object value) {
		if (value instanceof Number number) {
			return new BigDecimal(number.toString());
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

	private void applyLinkResolve(ExplainAssembly assembly, GroundedFacts facts) {
		assembly.linkResolve.clear();
		List<Map<String, Object>> keys = new ArrayList<>();
		if (!CollectionUtils.isEmpty(facts.getKeys())) {
			for (GroundedKey key : facts.getKeys()) {
				if (key == null || !StringUtils.hasText(key.getName())) {
					continue;
				}
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("name", key.getName());
				item.put("value", key.getValue());
				item.put("trust", key.getTrust());
				keys.add(item);
			}
		}
		assembly.linkResolve.put("keys", keys);
		assembly.linkResolve.put("ownOrigin", facts.isOwnOrigin());
		if (StringUtils.hasText(facts.getFailOpenReason())) {
			assembly.linkResolve.put("failOpenReason", facts.getFailOpenReason());
		}
		assembly.toolSteps.add(ToolStepView.builder()
			.sequenceNo(nextToolStepSequenceNo(assembly))
			.stepType(STEP_TYPE_EXPLAIN)
			.toolName("link_resolve")
			.title("链接解析")
			.summary("keys=" + keys.size() + ", ownOrigin=" + facts.isOwnOrigin())
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void applyClarifyAssessment(ExplainAssembly assembly, QueryClarifyAssessment assessment) {
		assembly.clarify.put("riskLevel", assessment.riskLevel().value());
		assembly.clarify.put("clarifyRequired", assessment.clarifyRequired());
		assembly.clarify.put("missingDimensions", assessment.missingDimensions());
		assembly.clarify.put("followUpQuestions", assessment.followUpQuestions());
		assembly.clarify.put("suggestedAssumptions", assessment.suggestedAssumptions());
		assembly.clarify.put("summary", assessment.summary());
		assembly.clarify.put("userMessage", assessment.userMessage());
		assembly.clarify.put("shouldBlockExecution", assessment.shouldBlockExecution());
		if (StringUtils.hasText(assessment.feedbackContent())) {
			assembly.clarify.put("humanFeedbackContent", assessment.feedbackContent());
		}
		assembly.toolSteps.add(ToolStepView.builder()
			.sequenceNo(nextToolStepSequenceNo(assembly))
			.stepType(STEP_TYPE_EXPLAIN)
			.toolName("query_clarify.check")
			.title("问题歧义检查")
			.summary(assessment.summary())
			.detail(assembly.deterministicSkill ? null : assessment.userMessage())
			.timestampEpochMs(Instant.now().toEpochMilli())
			.build());
		if (assessment.shouldBlockExecution()) {
			assembly.warnings.add("riskLevel=high，已禁止直接查库，需先补充信息或明确假设。");
		}
		assembly.updatedAt = Instant.now().toEpochMilli();
	}

	private void evictOverflowLocked() {
		while (explainsBySession.size() > MAX_SESSION_COUNT) {
			String eldestSessionId = explainsBySession.keySet().iterator().next();
			explainsBySession.remove(eldestSessionId);
		}
		explainsBySession.values().forEach(this::evictRequestOverflowLocked);
	}

	private void evictRequestOverflowLocked(LinkedHashMap<String, ExplainAssembly> explainsByRequest) {
		while (explainsByRequest.size() > MAX_REQUEST_COUNT_PER_SESSION) {
			String eldestRequestId = explainsByRequest.keySet().iterator().next();
			explainsByRequest.remove(eldestRequestId);
		}
	}

	private record ExplainContext(String sessionId, String runtimeRequestId) {
	}

	private static final class ExplainAssembly {

		private String sessionId;

		private String runtimeRequestId;

		private String agentId;

		private Long chatModelConfigId;

		private String rootRuntimeRequestId;

		private String parentRuntimeRequestId;

		private String tenantId;

		private String tenantCode;

		private String userId;

		private String userNickName;

		private String agentName;

		private String requestSource;

		private String question;

		private String routedSkillCode;

		private Long routedSkillVersionId;

		private SkillReportProfile skillReportProfile;

		private String answer;

		private String datasource;

		private String sql;

		private String decisionReason;

		private String resultScope;

		private boolean deterministicSkill;

		private final List<String> usedTables = new ArrayList<>();

		private final List<String> usedColumns = new ArrayList<>();

		private final List<Map<String, Object>> relationEvidence = new ArrayList<>();

		private final List<String> toolDecisionReasons = new ArrayList<>();

		private final List<String> resultScopeDetails = new ArrayList<>();

		private final List<SemanticHitView> semanticHits = new ArrayList<>();

		private final List<KnowledgeHitView> knowledgeHits = new ArrayList<>();

		private final List<ToolStepView> toolSteps = new ArrayList<>();

		private final List<ReportDataSnapshot> reportDataSnapshots = new ArrayList<>();

		private final Map<String, Object> clarify = new LinkedHashMap<>();

		private final Map<String, Object> linkResolve = new LinkedHashMap<>();

		private Map<String, Object> temporalContext = Map.of();

		private final Set<String> warnings = new LinkedHashSet<>();

		private int nextToolExecutionSequenceNo = 1;

		private long updatedAt;

		private AnswerTraceExplainView toView() {
			return AnswerTraceExplainView.builder()
				.sessionId(sessionId)
				.runtimeRequestId(runtimeRequestId)
				.agentId(agentId)
				.chatModelConfigId(chatModelConfigId)
				.rootRuntimeRequestId(rootRuntimeRequestId)
				.parentRuntimeRequestId(parentRuntimeRequestId)
				.tenantId(tenantId)
				.tenantCode(tenantCode)
				.userId(userId)
				.userNickName(userNickName)
				.agentName(agentName)
				.requestSource(requestSource)
				.question(question)
				.routedSkillCode(routedSkillCode)
				.routedSkillVersionId(routedSkillVersionId)
				.skillReportProfile(skillReportProfile)
				.answer(answer)
				.datasource(datasource)
				.sql(sql)
				.decisionReason(decisionReason)
				.resultScope(resultScope)
				.usedTables(List.copyOf(usedTables))
				.usedColumns(List.copyOf(usedColumns))
				.relationEvidence(List.copyOf(relationEvidence))
				.toolDecisionReasons(List.copyOf(toolDecisionReasons))
				.resultScopeDetails(List.copyOf(resultScopeDetails))
				.semanticHits(deterministicSkill ? List.of() : List.copyOf(semanticHits))
				.knowledgeHits(List.copyOf(knowledgeHits))
				.toolSteps(List.copyOf(toolSteps))
				.reportDataSnapshots(List.copyOf(reportDataSnapshots))
				.clarify(new LinkedHashMap<>(clarify))
				.linkResolve(new LinkedHashMap<>(linkResolve))
				.temporalContext(new LinkedHashMap<>(temporalContext))
				.warnings(List.copyOf(warnings))
				.updatedAt(updatedAt)
				.build();
		}

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AnswerTraceExplainView {

		private String sessionId;

		private String runtimeRequestId;

		private String agentId;

		private Long chatModelConfigId;

		private String rootRuntimeRequestId;

		private String parentRuntimeRequestId;

		private String tenantId;

		private String tenantCode;

		private String userId;

		private String userNickName;

		private String agentName;

		private String requestSource;

		private String question;

		private String routedSkillCode;

		private Long routedSkillVersionId;

		private SkillReportProfile skillReportProfile;

		private String answer;

		private String datasource;

		private String sql;

		private String decisionReason;

		private String resultScope;

		@Builder.Default
		private List<String> usedTables = List.of();

		@Builder.Default
		private List<String> usedColumns = List.of();

		@Builder.Default
		private List<Map<String, Object>> relationEvidence = List.of();

		@Builder.Default
		private List<String> toolDecisionReasons = List.of();

		@Builder.Default
		private List<String> resultScopeDetails = List.of();

		@Builder.Default
		private List<SemanticHitView> semanticHits = List.of();

		@Builder.Default
		private List<KnowledgeHitView> knowledgeHits = List.of();

		@Builder.Default
		private List<ToolStepView> toolSteps = List.of();

		@Builder.Default
		private List<ReportDataSnapshot> reportDataSnapshots = List.of();

		@Builder.Default
		private Map<String, Object> clarify = Map.of();

		@Builder.Default
		private Map<String, Object> linkResolve = Map.of();

		@Builder.Default
		private Map<String, Object> temporalContext = Map.of();

		@Builder.Default
		private List<String> warnings = List.of();

		private long updatedAt;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class SemanticHitView {

		private String tableName;

		private String columnName;

		private String businessName;

		private String businessDescription;

		private String synonyms;

		private String columnComment;

		private String dataType;

		private String matchedBy;

		private Integer score;

		private String relationHint;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class KnowledgeHitView {

		private String vectorType;

		private String knowledgeId;

		private String title;

		private String summary;

		private String snippet;

		private String source;

		private String concreteType;

	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ToolStepView {

		private Integer sequenceNo;

		private String stepType;

		private String toolName;

		private String title;

		private String summary;

		private String detail;

		private String datasource;

		private String status;

		private Long startEpochMs;

		private Long endEpochMs;

		private Long durationMs;

		private String inputSummary;

		private String outputSummary;

		private String errorCode;

		private String errorMessage;

		private long timestampEpochMs;

	}

	public record ToolExecutionRecord(String toolName, String status, long startEpochMs, long endEpochMs,
			long durationMs, String inputSummary, String outputSummary, String errorCode, String errorMessage,
			String summary, String detail) {
	}

	@Data
	@Builder
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class ExplainMirrorSummary {

		private String datasource;

		private int semanticHitCount;

		private int knowledgeHitCount;

		private int toolStepCount;

	}

}
