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

import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.permission.PermissionBehavior;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * v2 {@code onActing} 工具门：readOnly + checkPermissions → PEP / SQL 守卫 / 能力网关口径。
 *
 * <p>ALLOW 继续执行；DENY 回 observation 错误、不中断 run；写工具在 {@code hitlEnabled}
 * 为 true 时 ASK（HITL）发 {@link RequireUserConfirmEvent}，为 false 时 DENY observation
 * （不 ASK、不落 PENDING）。Confirm / {@code ALLOWED} 只跳过 ASK，仍过 SQL / PEP / FILE_ONLY；
 * 钉死 {@code toolCallId +} 规范化参数指纹。分析 / FILE_ONLY 禁止执行 MCP 与写工具。
 * 租户 / owner / run 只取 {@link V2RuntimeSnapshot}，禁止从请求头推导。
 *
 * <p>分析意图由运行时写入 {@link #ANALYSIS_INTENT_KEY}；toolkit 先经 {@link V2ToolkitFilter}。
 */
@Slf4j
public class V2ActingPermissionMiddleware implements MiddlewareBase {

	public static final String ANALYSIS_INTENT_KEY = "v2AnalysisIntent";

	static final String NEEDS_CONFIRM_OBSERVATION = "工具写操作需要确认（needs confirm），本次未执行。";

	static final String HITL_DISABLED_OBSERVATION = "需要在对话里确认后执行";

	private static final int ORDER = 90;

	private final RuntimePolicyEvaluator pep;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final PepAuthorizationProperties pepProperties;

	private final V2ConfirmCredentialStore confirmStore;

	public V2ActingPermissionMiddleware(RuntimePolicyEvaluator pep,
			AgentExecutionResourceVersionMapper resourceVersionMapper, PepAuthorizationProperties pepProperties,
			V2ConfirmCredentialStore confirmStore) {
		this.pep = pep;
		this.resourceVersionMapper = resourceVersionMapper;
		this.pepProperties = pepProperties;
		this.confirmStore = confirmStore;
	}

	public V2ActingPermissionMiddleware(RuntimePolicyEvaluator pep,
			AgentExecutionResourceVersionMapper resourceVersionMapper, PepAuthorizationProperties pepProperties) {
		this(pep, resourceVersionMapper, pepProperties, null);
	}

	public V2ActingPermissionMiddleware(RuntimePolicyEvaluator pep,
			AgentExecutionResourceVersionMapper resourceVersionMapper) {
		this(pep, resourceVersionMapper, null);
	}

	public V2ActingPermissionMiddleware(RuntimePolicyEvaluator pep) {
		this(pep, null, null);
	}

	public V2ActingPermissionMiddleware() {
		this(null, null, null);
	}

	@Override
	public int order() {
		return ORDER;
	}

	@Override
	public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
			Function<ActingInput, Flux<AgentEvent>> next) {
		List<ToolUseBlock> toolCalls = input == null || input.toolCalls() == null ? List.of() : input.toolCalls();
		if (toolCalls.isEmpty()) {
			return next.apply(input == null ? new ActingInput(List.of()) : input);
		}
		V2RuntimeSnapshot snapshot = snapshot(ctx);
		List<ToolUseBlock> allowed = new ArrayList<>();
		List<DeniedCall> denied = new ArrayList<>();
		List<ToolUseBlock> ask = new ArrayList<>();
		for (ToolUseBlock call : toolCalls) {
			GateDecision decision = checkPermissions(ctx, snapshot, call);
			if (decision.behavior() == PermissionBehavior.DENY) {
				denied.add(new DeniedCall(call, decision.message()));
			}
			else if (decision.behavior() == PermissionBehavior.ASK) {
				ask.add(call);
			}
			else {
				consumeConfirmedWrite(snapshot, call);
				allowed.add(call);
			}
		}
		Flux<AgentEvent> deniedEvents = emitDenied(denied);
		Flux<AgentEvent> askEvents = emitAsk(ctx, ask);
		if (allowed.isEmpty()) {
			return deniedEvents.concatWith(askEvents);
		}
		return deniedEvents.concatWith(askEvents).concatWith(next.apply(new ActingInput(allowed)));
	}

	/**
	 * 单次工具调用的 ALLOW / ASK / DENY。租户只读快照，不读 HTTP 头。
	 */
	GateDecision checkPermissions(RuntimeContext ctx, V2RuntimeSnapshot snapshot, ToolUseBlock call) {
		if (call == null || !StringUtils.hasText(call.getName())) {
			return GateDecision.deny("工具调用缺少名称，已拒绝执行");
		}
		String toolName = call.getName();
		Map<String, Object> input = call.getInput() == null ? Map.of() : call.getInput();
		String analysisIntent = analysisIntent(ctx);
		GateDecision analysis = denyAnalysisWrite(toolName, analysisIntent);
		if (analysis != null) {
			return analysis;
		}
		GateDecision sql = denySqlWrite(toolName, input);
		if (sql != null) {
			return sql;
		}
		GateDecision pepDeny = denyByPep(snapshot, toolName);
		if (pepDeny != null) {
			return pepDeny;
		}
		return askOrAllowWrite(snapshot, toolName, analysisIntent, alreadyConfirmed(ctx, call));
	}

	private GateDecision denyAnalysisWrite(String toolName, String analysisIntent) {
		if (!V2ToolkitFilter.isAnalysisChannel(analysisIntent)) {
			return null;
		}
		if (V2ToolkitFilter.isExecuteMcp(toolName) || V2ToolkitFilter.isWriteToolName(toolName)) {
			return GateDecision.deny("分析通道禁止执行 MCP 与写工具, tool=" + toolName);
		}
		if (V2ToolkitFilter.isFileOnly(analysisIntent) && V2ToolkitFilter.isJdbcTool(toolName)) {
			return GateDecision.deny("FILE_ONLY 不注册 JDBC 工具, tool=" + toolName);
		}
		return null;
	}

	private GateDecision denySqlWrite(String toolName, Map<String, Object> input) {
		if (AgentModelToolName.isSqlGuardTool(toolName)) {
			return null;
		}
		String sql = sqlArgument(input);
		if (!StringUtils.hasText(sql)) {
			return null;
		}
		if (!AgentModelToolName.isDatasourceTool(toolName) && !looksLikeSqlTool(toolName, input)) {
			return null;
		}
		try {
			if (isReadonlySelect(sql)) {
				return null;
			}
			return GateDecision.deny("SQL 守卫拒绝写语句，仅允许 SELECT / WITH, tool=" + toolName);
		}
		catch (RuntimeException ex) {
			return GateDecision.deny("SQL 守卫拒绝无法解析的语句, tool=" + toolName);
		}
	}

	private GateDecision denyByPep(V2RuntimeSnapshot snapshot, String toolName) {
		if (pep == null) {
			return null;
		}
		boolean enforce = enforceMode(snapshot, null);
		if (!hasPepBinding(snapshot)) {
			return enforce ? GateDecision.deny("PEP 空主体拒绝：ENFORCE 要求租户、用户与 owner 完备, tool=" + toolName)
					: null;
		}
		try {
			PepDecisionResult result = pep.evaluate(pepContext(snapshot, toolName));
			if (result == null) {
				return enforce ? GateDecision.deny("PEP 评估结果为空，工具未执行, tool=" + toolName) : null;
			}
			if (result.allowed()) {
				return null;
			}
			if (!enforceMode(snapshot, result)) {
				return null;
			}
			return GateDecision.deny("PEP 拒绝工具执行, tool=" + toolName + ", reason="
					+ (result.reasonCode() == null ? "UNKNOWN" : result.reasonCode().name()));
		}
		catch (RuntimeException ex) {
			log.warn("v2 checkPermissions PEP 评估失败, 按 DENY observation 处理. tool={}, errorType={}", toolName,
					ex.getClass().getSimpleName());
			return GateDecision.deny("PEP 评估失败，工具未执行, tool=" + toolName);
		}
	}

	private PepDecisionContext pepContext(V2RuntimeSnapshot snapshot, String toolName) {
		AuthorizationOwnerType ownerType = resolveOwnerType(snapshot);
		return PepDecisionContext.builder()
			.tenantId(snapshot.tenantId())
			.runId(snapshot.durableRunId())
			.stepKey(snapshot.runtimeRequestId())
			.ownerType(ownerType)
			.ownerId(resolveOwnerId(snapshot, ownerType))
			.subjectKind(ownerType == AuthorizationOwnerType.DIGITAL_EMPLOYEE ? SubjectKind.DIGITAL_EMPLOYEE
					: SubjectKind.CALLER)
			.subjectId(snapshot.userId())
			.capabilityCode(toolName)
			.action(AuthorizationAction.EXECUTE)
			.build();
	}

	private GateDecision askOrAllowWrite(V2RuntimeSnapshot snapshot, String toolName, String analysisIntent,
			boolean confirmed) {
		boolean write = V2ToolkitFilter.isWriteToolName(toolName) || V2ToolkitFilter.isExecuteMcp(toolName)
				|| catalogWrite(toolName);
		if (!write) {
			return GateDecision.allow();
		}
		if (V2ToolkitFilter.isAnalysisChannel(analysisIntent)) {
			return GateDecision.deny("分析通道禁止写工具, tool=" + toolName);
		}
		if (confirmed) {
			return GateDecision.allow();
		}
		if (snapshot != null && !snapshot.hitlEnabled()) {
			return GateDecision.deny(HITL_DISABLED_OBSERVATION);
		}
		return GateDecision.ask(NEEDS_CONFIRM_OBSERVATION);
	}

	private boolean catalogWrite(String toolName) {
		if (resourceVersionMapper == null || !StringUtils.hasText(toolName)) {
			return false;
		}
		try {
			AgentExecutionResourceVersion version = resourceVersionMapper.findLatestPublished(toolName);
			if (version == null) {
				return false;
			}
			return "WRITE".equalsIgnoreCase(version.getAccessMode())
					|| Boolean.TRUE.equals(version.getConfirmRequired());
		}
		catch (RuntimeException ex) {
			log.warn("v2 能力网关目录查询失败, 按写工具 ASK 处理. tool={}, errorType={}", toolName,
					ex.getClass().getSimpleName());
			return true;
		}
	}

	private boolean alreadyConfirmed(RuntimeContext ctx, ToolUseBlock call) {
		ConfirmResult confirm = ctx == null ? null : ctx.get(ConfirmResult.class);
		if (confirm != null && confirm.isConfirmed() && confirm.getToolCall() != null) {
			return sameApprovedCall(call, confirm.getToolCall());
		}
		if (call.getState() == ToolCallState.ALLOWED) {
			return true;
		}
		if (confirmStore == null) {
			return false;
		}
		V2RuntimeSnapshot snapshot = snapshot(ctx);
		if (confirmStore.isApproved(snapshot, call)) {
			return true;
		}
		return confirmStore.restore(snapshot, call)
			.filter(ConfirmResult::isConfirmed)
			.map(ConfirmResult::getToolCall)
			.filter(approved -> sameApprovedCall(call, approved))
			.isPresent();
	}

	private void consumeConfirmedWrite(V2RuntimeSnapshot snapshot, ToolUseBlock call) {
		if (confirmStore != null) {
			confirmStore.consume(snapshot, call);
		}
	}

	private static boolean sameApprovedCall(ToolUseBlock current, ToolUseBlock approved) {
		if (current.getId() == null || !current.getId().equals(approved.getId())) {
			return false;
		}
		return argumentFingerprint(current.getInput()).equals(argumentFingerprint(approved.getInput()));
	}

	static String argumentFingerprint(Map<String, Object> input) {
		return canonicalize(input == null ? Map.of() : input);
	}

	private static String canonicalize(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Map<?, ?> map) {
			TreeMap<String, String> sorted = new TreeMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() == null) {
					continue;
				}
				sorted.put(String.valueOf(entry.getKey()).trim(), canonicalize(entry.getValue()));
			}
			return sorted.toString();
		}
		if (value instanceof Collection<?> items) {
			List<String> parts = new ArrayList<>();
			for (Object item : items) {
				parts.add(canonicalize(item));
			}
			return parts.toString();
		}
		return String.valueOf(value).trim();
	}

	private boolean enforceMode(V2RuntimeSnapshot snapshot, PepDecisionResult result) {
		if (pepProperties != null
				&& pepProperties.resolveMode(snapshot == null ? null : snapshot.tenantId()).enforce()) {
			return true;
		}
		return result != null && result.getEffectiveMode() != null && result.getEffectiveMode().enforce();
	}

	private static boolean hasPepBinding(V2RuntimeSnapshot snapshot) {
		if (snapshot == null || !StringUtils.hasText(snapshot.tenantId()) || !StringUtils.hasText(snapshot.userId())) {
			return false;
		}
		return resolveOwnerId(snapshot, resolveOwnerType(snapshot)) != null;
	}

	private static AuthorizationOwnerType resolveOwnerType(V2RuntimeSnapshot snapshot) {
		AuthorizationOwnerType ownerType = snapshot == null ? null : AuthorizationOwnerType.fromCode(snapshot.ownerType());
		return ownerType == null ? AuthorizationOwnerType.DATA_AGENT : ownerType;
	}

	private static Long resolveOwnerId(V2RuntimeSnapshot snapshot, AuthorizationOwnerType ownerType) {
		if (snapshot == null) {
			return null;
		}
		if (ownerType == AuthorizationOwnerType.DIGITAL_EMPLOYEE && snapshot.ownerId() != null) {
			return snapshot.ownerId();
		}
		return parseLong(snapshot.agentId());
	}

	private static Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Flux<AgentEvent> emitDenied(List<DeniedCall> denied) {
		if (denied.isEmpty()) {
			return Flux.empty();
		}
		String replyId = "v2-deny-" + UUID.randomUUID().toString().replace("-", "");
		List<AgentEvent> events = new ArrayList<>();
		for (DeniedCall item : denied) {
			events.addAll(observationEvents(replyId, item.call(), item.message(), ToolResultState.DENIED));
		}
		return Flux.fromIterable(events);
	}

	private Flux<AgentEvent> emitAsk(RuntimeContext ctx, List<ToolUseBlock> ask) {
		if (ask.isEmpty()) {
			return Flux.empty();
		}
		String replyId = "v2-ask-" + UUID.randomUUID().toString().replace("-", "");
		if (confirmStore != null) {
			V2RuntimeSnapshot snapshot = snapshot(ctx);
			for (ToolUseBlock call : ask) {
				confirmStore.savePending(snapshot, replyId, call);
			}
		}
		return Flux.just(new RequireUserConfirmEvent(replyId, ask));
	}

	private List<AgentEvent> observationEvents(String replyId, ToolUseBlock call, String message,
			ToolResultState state) {
		String id = call.getId() == null ? UUID.randomUUID().toString() : call.getId();
		String name = call.getName();
		return List.of(new ToolResultStartEvent(replyId, id, name),
				new ToolResultTextDeltaEvent(replyId, id, name, message),
				new ToolResultEndEvent(replyId, id, name, state));
	}

	private static V2RuntimeSnapshot snapshot(RuntimeContext ctx) {
		return ctx == null ? null : ctx.get(V2RuntimeSnapshot.class);
	}

	private static String analysisIntent(RuntimeContext ctx) {
		if (ctx == null) {
			return null;
		}
		Object intent = ctx.get(ANALYSIS_INTENT_KEY);
		return intent instanceof String text && StringUtils.hasText(text) ? text : null;
	}

	private static String sqlArgument(Map<String, Object> input) {
		Object sql = input.get("sql");
		if (sql == null) {
			sql = input.get("SQL");
		}
		return sql == null ? null : String.valueOf(sql).trim();
	}

	private static boolean looksLikeSqlTool(String toolName, Map<String, Object> input) {
		return input.containsKey("sql") || input.containsKey("SQL") || toolName.toLowerCase().contains("sql");
	}

	static boolean isReadonlySelect(String sql) {
		String normalized = stripTrailingSemicolons(sql);
		if (!StringUtils.hasText(normalized)) {
			throw new IllegalArgumentException("SQL 不能为空");
		}
		List<Statement> statements;
		try {
			statements = CCJSqlParserUtil.parseStatements(normalized).getStatements();
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("SQL 解析失败", ex);
		}
		if (statements == null || statements.isEmpty()) {
			throw new IllegalArgumentException("SQL 不能为空");
		}
		if (statements.size() > 1) {
			return false;
		}
		return statements.get(0) instanceof Select;
	}

	private static String stripTrailingSemicolons(String sql) {
		String trimmed = sql == null ? "" : sql.trim();
		while (trimmed.endsWith(";")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	record GateDecision(PermissionBehavior behavior, String message) {

		static GateDecision allow() {
			return new GateDecision(PermissionBehavior.ALLOW, "allow");
		}

		static GateDecision deny(String message) {
			return new GateDecision(PermissionBehavior.DENY, message);
		}

		static GateDecision ask(String message) {
			return new GateDecision(PermissionBehavior.ASK, message);
		}

	}

	private record DeniedCall(ToolUseBlock call, String message) {
	}

}
