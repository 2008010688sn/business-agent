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
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeDagScheduler;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeMirrorService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeMirrorService.ScheduledStepSpec;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStepExecutor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 事件驱动协作者执行引擎（W2 深度接线）。
 *
 * <p>把编排协作者步骤写入权威运行时表（agent_runtime_step，含 depends_on）后交给
 * {@link RuntimeDagScheduler} 调度：单个分支进入终态立即释放其就绪下游，互不依赖的分支全程并行，
 * 不再等待整批（ready-batch barrier）。步骤执行体复用既有协作者调用逻辑（由调用方以
 * {@link CollaboratorStepRunner} 提供），SSE 事件、遥测步骤、级联取消与既有链路一致。</p>
 *
 * <p>与整批模式对齐的行为契约：</p>
 * <ul>
 * <li>结果按路由顺序返回；continue 策略下每条路由都有结果（含「前置失败未执行」）。</li>
 * <li>交互型协作者独占执行：执行期间不并行其他步骤，业务澄清挂起时无在途分支。</li>
 * <li>挂起（waiting）后停止释放新步骤并返回部分结果；未执行路由的权威步骤保持 PENDING 供恢复续跑。</li>
 * <li>fail_fast 下首个失败在在途步骤收敛后抛出，下游不再补「前置失败」记录。</li>
 * <li>单步超时由发起线程看护：超时步骤被放弃（级联取消 + TIMED_OUT），其余分支不受影响。</li>
 * </ul>
 *
 * <p>权威运行镜像缺失或步骤准备失败时返回 null，调用方回落整批 barrier 执行（行为等价，仅失去
 * 事件驱动释放的并行度）。</p>
 *
 * <p>开跑前经 {@link DurableCompiledPlanActivator} 把 V1 RoutePlan 编译为 CompiledPlan 并
 * {@code save(ACTIVE)}，无 ACTIVE 计划不得进入调度器。NATIVE 执行链路（CapabilityGateway
 * 逐步调用 + SSE 切流）本轮不落地，步骤执行体仍是协作者回调。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDrivenCollaboratorEngine {

	/**
	 * 发起线程看护节拍：完成事件轮询超时即一次超时看护 + 空转对账计数。
	 */
	private static final long WATCHDOG_TICK_MS = 50L;

	/**
	 * 空转对账阈值：连续该数量的空节拍（无在途、无完成事件、仍有未决路由）后核对权威步骤状态，
	 * 兜底覆盖完成事件缺失（如线程池拒绝、释放遗漏）。
	 */
	private static final int IDLE_TICKS_BEFORE_RECONCILE = 20;

	private static final String ARTIFACT_SCHEMA_VERSION = "collaborator-result/v1";

	private final RuntimeMirrorService runtimeMirrorService;

	private final RuntimeDagScheduler runtimeDagScheduler;

	private final ObjectMapper objectMapper;

	private final DurableCompiledPlanActivator compiledPlanActivator;

	/**
	 * 单条路由的执行体与周边动作，由运行时服务提供（复用既有协作者调用、遥测与 SSE 逻辑）。
	 */
	public interface CollaboratorStepRunner {

		/**
		 * 执行单条协作者路由（构建子请求、注册级联取消、调用协作者）。构建阶段的异常应包装为
		 * {@link EngineFatalException} 抛出（等价 legacy 在父线程构建失败即整单失败）；
		 * 协作者执行阶段的异常应吸收为失败结果返回。
		 */
		CollaboratorExecutionResult runRoute(CollaboratorRoute route,
				List<CollaboratorExecutionResult> dependencyResults, InFlightHandle handle);

		/**
		 * 为未执行的路由落遥测失败步骤并生成失败结果（前置失败阻断、权威侧终止兜底）。
		 */
		CollaboratorExecutionResult markUnexecuted(CollaboratorRoute route, String errorMessage);

		/**
		 * 放弃超时的在途步骤：级联取消子请求、中断执行线程、遥测记 TIMED_OUT，返回超时失败结果。
		 */
		CollaboratorExecutionResult abandonTimedOut(InFlightHandle handle);

		/**
		 * 放弃在途步骤（发起线程被中断时）：仅级联取消子请求，遥测由执行线程的取消分支收敛。
		 */
		void abandon(InFlightHandle handle);

		/**
		 * 校验发起方剩余预算，耗尽时抛出与整批模式一致的 IllegalStateException。
		 */
		void ensureParentBudget();

		boolean interactive(CollaboratorRoute route);

	}

	/**
	 * 构建阶段失败的致命异常包装：引擎收敛在途步骤后原样抛出被包装的原因，
	 * 与整批模式「父线程构建子请求失败即编排失败」的传播路径一致。
	 */
	public static final class EngineFatalException extends RuntimeException {

		public EngineFatalException(RuntimeException cause) {
			super(cause.getMessage(), cause);
		}

		public RuntimeException unwrap() {
			return (RuntimeException) getCause();
		}

	}

	/**
	 * 在途步骤句柄：执行体在构建出子请求后回填，供发起线程做超时看护与放弃。
	 */
	public static final class InFlightHandle {

		private final CollaboratorRoute route;

		private final Thread workerThread;

		private final long startedAtNanos = System.nanoTime();

		private volatile AgentOrchestrationStep telemetryStep;

		private volatile AgentRequest childRequest;

		private volatile long budgetMillis;

		private volatile boolean timedOut;

		InFlightHandle(CollaboratorRoute route, Thread workerThread) {
			this.route = route;
			this.workerThread = workerThread;
		}

		/**
		 * 执行体建好遥测步骤与子请求后挂接，budgetMillis 为该步骤的最长等待预算。
		 */
		public void attach(AgentOrchestrationStep telemetryStep, AgentRequest childRequest, long budgetMillis) {
			this.telemetryStep = telemetryStep;
			this.childRequest = childRequest;
			this.budgetMillis = budgetMillis;
		}

		public CollaboratorRoute route() {
			return route;
		}

		public Thread workerThread() {
			return workerThread;
		}

		public long startedAtNanos() {
			return startedAtNanos;
		}

		public AgentOrchestrationStep telemetryStep() {
			return telemetryStep;
		}

		public AgentRequest childRequest() {
			return childRequest;
		}

		public boolean timedOut() {
			return timedOut;
		}

		boolean deadlineElapsed() {
			return childRequest != null && budgetMillis > 0
					&& (System.nanoTime() - startedAtNanos) / 1_000_000L > budgetMillis;
		}

	}

	/**
	 * 调度执行入口。返回 null 表示当前运行不具备事件驱动条件（权威镜像缺失、路由缺少步骤标识），
	 * 调用方应回落整批 barrier；一旦开始调度，异常语义与整批模式一致（fail_fast 传播、中断传播）。
	 */
	public List<CollaboratorExecutionResult> tryExecute(AgentOrchestrationRun run, List<CollaboratorRoute> routes,
			Map<String, CollaboratorExecutionResult> restoredResults, boolean failFast,
			CollaboratorStepRunner runner) {
		if (run == null || run.getId() == null || routes == null || routes.isEmpty()
				|| routes.stream().anyMatch(route -> route == null || !StringUtils.hasText(route.stepId()))) {
			return null;
		}
		ExecutionState state = new ExecutionState(routes, restoredResults, failFast, runner);
		Long durableRunId = runtimeMirrorService.prepareScheduledSteps(run, buildSpecs(routes, state));
		if (durableRunId == null) {
			return null;
		}
		// 方案第四章第 10 步：ACTIVE 计划落库后才允许调度；失败关闭，不回落整批假装已持久化
		compiledPlanActivator.requireActivePlan(durableRunId, run, routes);
		runtimeDagScheduler.start(durableRunId, new ScheduledCollaboratorExecutor(state));
		return await(state, durableRunId);
	}

	private List<ScheduledStepSpec> buildSpecs(List<CollaboratorRoute> routes, ExecutionState state) {
		List<ScheduledStepSpec> specs = new ArrayList<>();
		for (CollaboratorRoute route : routes) {
			String capabilityHandle = route.collaboratorAgentId() == null ? null
					: "collaborator:" + route.collaboratorAgentId();
			CollaboratorExecutionResult restored = state.resultsByStepId.get(route.stepId());
			if (restored != null) {
				// 恢复链路带来的既定结果：权威行直接是终态，调度器只把它当作已完成上游
				specs.add(ScheduledStepSpec.restored(route.stepId(), route.task(), capabilityHandle,
						route.dependsOn(), restored.success() ? RuntimeStepState.SUCCEEDED : RuntimeStepState.FAILED,
						restored.success() ? null : "RESTORED_NOT_SUCCEEDED", restored.errorMessage()));
				continue;
			}
			// 默认 max_attempts=1（与整批模式单次执行一致），重试策略由调度器按步骤行配置生效
			specs.add(ScheduledStepSpec.pending(route.stepId(), route.task(), capabilityHandle, route.dependsOn(), 1));
		}
		return specs;
	}

	private List<CollaboratorExecutionResult> await(ExecutionState state, Long durableRunId) {
		int idleTicks = 0;
		while (!state.allResolved()) {
			CompletedStep completed;
			try {
				completed = state.completionQueue.poll(WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				state.halted.set(true);
				abandonInFlight(state);
				throw new IllegalStateException("编排协作者等待被中断", ex);
			}
			if (completed != null) {
				idleTicks = 0;
				handleCompletion(state, completed);
			}
			else {
				watchdogTick(state);
				idleTicks = state.inFlight.isEmpty() ? idleTicks + 1 : 0;
			}
			if (state.halted.get()) {
				// 挂起返回与 fail_fast 传播都先等在途步骤收敛，对应整批模式先 join 完当前批次
				drainInFlight(state);
				return finishHalted(state);
			}
			if (state.allResolved()) {
				break;
			}
			state.runner.ensureParentBudget();
			if (idleTicks >= IDLE_TICKS_BEFORE_RECONCILE && !state.allResolved()) {
				idleTicks = 0;
				reconcile(state, durableRunId);
				if (state.halted.get()) {
					drainInFlight(state);
					return finishHalted(state);
				}
			}
		}
		return state.resultsInRouteOrder(false);
	}

	private void handleCompletion(ExecutionState state, CompletedStep completed) {
		if (completed.fatal() != null) {
			state.halted.set(true);
			drainInFlight(state);
			throw completed.fatal() instanceof EngineFatalException fatal ? fatal.unwrap()
					: asRuntime(completed.fatal());
		}
		CollaboratorExecutionResult result = completed.result();
		if (result.waiting()) {
			// 交互独占保证挂起时无其他在途分支；停止释放并按整批模式语义返回部分结果
			state.halted.set(true);
			state.waitingObserved = true;
			return;
		}
		if (result.success()) {
			return;
		}
		if (state.failFast) {
			state.halted.set(true);
			state.firstFailure.compareAndSet(null, result);
			return;
		}
		cascadeBlocked(state);
	}

	private List<CollaboratorExecutionResult> finishHalted(ExecutionState state) {
		if (state.waitingObserved) {
			return state.resultsInRouteOrder(true);
		}
		CollaboratorExecutionResult failure = state.firstFailure.get();
		throw propagateFailure(failure);
	}

	private RuntimeException propagateFailure(CollaboratorExecutionResult failure) {
		Throwable error = failure == null ? null : failure.error();
		if (error instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		return new IllegalStateException(error == null ? "协作者执行失败" : error.getMessage(), error);
	}

	/**
	 * continue 策略下的阻断级联：任一依赖失败的未决路由立即按「前置步骤执行失败，当前协作者未执行」
	 * 落遥测失败步骤并生成结果，传递闭包内的下游随之逐层阻断（与整批模式一致）。
	 */
	private void cascadeBlocked(ExecutionState state) {
		boolean progressed = true;
		while (progressed) {
			progressed = false;
			for (CollaboratorRoute route : state.routes) {
				if (state.resultsByStepId.containsKey(route.stepId())) {
					continue;
				}
				boolean blocked = route.dependsOn().stream().anyMatch(dependency -> {
					CollaboratorExecutionResult dependencyResult = state.resultsByStepId.get(dependency);
					return dependencyResult != null && !dependencyResult.success();
				});
				if (blocked) {
					CollaboratorExecutionResult blockedResult = state.runner.markUnexecuted(route,
							"前置步骤执行失败，当前协作者未执行");
					state.resultsByStepId.putIfAbsent(route.stepId(), blockedResult);
					progressed = true;
				}
			}
		}
	}

	private void watchdogTick(ExecutionState state) {
		for (Map.Entry<String, InFlightHandle> entry : state.inFlight.entrySet()) {
			InFlightHandle handle = entry.getValue();
			if (handle.timedOut || !handle.deadlineElapsed()) {
				continue;
			}
			// 先立超时标记再放弃：执行线程醒来后按 COLLABORATOR_TIMED_OUT 收敛权威步骤
			handle.timedOut = true;
			CollaboratorExecutionResult timeoutResult = state.runner.abandonTimedOut(handle);
			// 强制覆盖写：与整批模式一致，超时后以看护方的超时结果为准，
			// 被放弃执行线程醒来后写入的取消/迟到结果一律丢弃
			state.resultsByStepId.put(entry.getKey(), timeoutResult);
			if (state.failFast) {
				state.halted.set(true);
				state.firstFailure.compareAndSet(null, timeoutResult);
			}
			else {
				cascadeBlocked(state);
			}
		}
	}

	/**
	 * 空转对账：完成事件缺失（线程池拒绝、权威侧独立终止）时按权威步骤状态兜底收敛，
	 * 仍处 PENDING/READY 的步骤重新触发一次幂等调度。
	 */
	private void reconcile(ExecutionState state, Long durableRunId) {
		Map<String, AgentRuntimeStep> stepsByKey = new HashMap<>();
		for (AgentRuntimeStep step : runtimeMirrorService.listScheduledSteps(durableRunId)) {
			stepsByKey.put(step.getStepKey(), step);
		}
		boolean redispatch = false;
		for (CollaboratorRoute route : state.routes) {
			if (state.resultsByStepId.containsKey(route.stepId())) {
				continue;
			}
			AgentRuntimeStep step = stepsByKey.get(route.stepId());
			RuntimeStepState stepState = step == null ? null : RuntimeStepState.of(step.getState());
			if (stepState != null && stepState.terminal()) {
				String message = StringUtils.hasText(step.getErrorMessage()) ? step.getErrorMessage()
						: "步骤在权威运行时中已终止: " + stepState.getValue();
				CollaboratorExecutionResult synthesized = state.runner.markUnexecuted(route, message);
				state.resultsByStepId.putIfAbsent(route.stepId(), synthesized);
				if (state.failFast) {
					state.halted.set(true);
					state.firstFailure.compareAndSet(null, synthesized);
					return;
				}
				cascadeBlocked(state);
			}
			else {
				redispatch = true;
			}
		}
		if (redispatch && !state.halted.get()) {
			log.debug("编排调度对账重派未决步骤. durableRunId={}", durableRunId);
			runtimeDagScheduler.start(durableRunId, new ScheduledCollaboratorExecutor(state));
		}
	}

	/**
	 * 等待在途步骤收敛（挂起返回与 fail_fast 传播前），对应整批模式「当前批次 join 完再返回/抛出」。
	 */
	private void drainInFlight(ExecutionState state) {
		while (!state.inFlight.isEmpty()) {
			try {
				state.completionQueue.poll(WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				abandonInFlight(state);
				throw new IllegalStateException("编排协作者等待被中断", ex);
			}
			watchdogTick(state);
		}
	}

	private void abandonInFlight(ExecutionState state) {
		for (InFlightHandle handle : state.inFlight.values()) {
			state.runner.abandon(handle);
			handle.workerThread().interrupt();
		}
	}

	private RuntimeException asRuntime(Throwable throwable) {
		return throwable instanceof RuntimeException runtimeException ? runtimeException
				: new IllegalStateException(throwable.getMessage(), throwable);
	}

	/**
	 * 调度器回调：在编排线程池的工作线程上执行单条协作者路由，结果同时回填引擎结果集与权威步骤。
	 */
	private final class ScheduledCollaboratorExecutor implements RuntimeStepExecutor {

		private final ExecutionState state;

		private ScheduledCollaboratorExecutor(ExecutionState state) {
			this.state = state;
		}

		@Override
		public boolean canRelease(AgentRuntimeRun run, AgentRuntimeStep step) {
			if (state.halted.get()) {
				return false;
			}
			CollaboratorRoute route = state.routesByStepId.get(step.getStepKey());
			if (route == null) {
				return true;
			}
			if (state.resultsByStepId.containsKey(route.stepId())) {
				return false;
			}
			synchronized (state.gate) {
				if (state.runner.interactive(route)) {
					// 交互型独占入场：任何在途步骤存在都不放行
					return !state.gate.interactiveRunning && state.gate.runningCount == 0;
				}
				// 交互型有已就绪待执行者时优先让位（与整批模式 ready 层交互优先一致）
				return !state.gate.interactiveRunning && !interactiveEligible();
			}
		}

		@Override
		public StepOutcome execute(StepExecution execution) throws Exception {
			String stepKey = execution.step().getStepKey();
			CollaboratorRoute route = state.routesByStepId.get(stepKey);
			if (route == null || state.resultsByStepId.containsKey(stepKey)) {
				return StepOutcome.failure("STEP_NOT_EXECUTABLE", "步骤无待执行路由（已恢复或不属于本次编排）");
			}
			boolean interactive = state.runner.interactive(route);
			enterGate(interactive);
			InFlightHandle handle = new InFlightHandle(route, Thread.currentThread());
			state.inFlight.put(stepKey, handle);
			try {
				List<CollaboratorExecutionResult> dependencyResults = route.dependsOn().stream()
					.map(state.resultsByStepId::get)
					.filter(Objects::nonNull)
					.toList();
				CollaboratorExecutionResult result = state.runner.runRoute(route, dependencyResults, handle);
				boolean recorded = state.resultsByStepId.putIfAbsent(stepKey, result) == null;
				state.completionQueue.offer(new CompletedStep(stepKey, result, null));
				return toOutcome(result, handle, recorded);
			}
			catch (EngineFatalException fatal) {
				state.completionQueue.offer(new CompletedStep(stepKey, null, fatal));
				return StepOutcome.failure("ORCHESTRATION_FATAL", fatal.getMessage());
			}
			catch (RuntimeException leaked) {
				// 等价整批模式 joinCollaboratorResult 的 ExecutionException 分支：
				// 越过协作者守卫的异常按步骤级失败记录，不整单失败
				log.warn("Collaborator execution failed outside the collaborator guard. stepKey={}", stepKey, leaked);
				CollaboratorExecutionResult result = new CollaboratorExecutionResult(route, null, leaked,
						(System.nanoTime() - handle.startedAtNanos()) / 1_000_000L);
				boolean recorded = state.resultsByStepId.putIfAbsent(stepKey, result) == null;
				state.completionQueue.offer(new CompletedStep(stepKey, result, null));
				return toOutcome(result, handle, recorded);
			}
			finally {
				state.inFlight.remove(stepKey);
				exitGate(interactive);
			}
		}

		private StepOutcome toOutcome(CollaboratorExecutionResult result, InFlightHandle handle, boolean recorded) {
			if (result.waiting()) {
				return StepOutcome.waitingInteraction();
			}
			if (handle.timedOut() || !recorded && isTimeoutResult(state.resultsByStepId.get(handle.route().stepId()))) {
				return StepOutcome.failure("COLLABORATOR_TIMED_OUT", "协作者执行超时");
			}
			if (result.success()) {
				return StepOutcome.success(artifactJson(result), ARTIFACT_SCHEMA_VERSION);
			}
			if (result.error() instanceof IllegalStateException && "协作者已被取消".equals(result.errorMessage())) {
				return StepOutcome.failure("RUN_CANCELLED", result.errorMessage());
			}
			return StepOutcome.failure("COLLABORATOR_FAILED", result.errorMessage());
		}

		private boolean isTimeoutResult(CollaboratorExecutionResult result) {
			return result != null && result.error() != null && "协作者执行超时".equals(result.error().getMessage());
		}

		private String artifactJson(CollaboratorExecutionResult result) {
			try {
				Map<String, Object> artifact = new LinkedHashMap<>();
				artifact.put("answer", result.answer());
				if (!result.structuredOutput().isEmpty()) {
					artifact.put("structuredOutput", result.structuredOutput());
				}
				return objectMapper.writeValueAsString(artifact);
			}
			catch (Exception ex) {
				log.warn("协作者产物序列化失败，权威步骤不落产物. stepId={}", result.route().stepId(), ex);
				return null;
			}
		}

		private boolean interactiveEligible() {
			for (CollaboratorRoute route : state.routes) {
				if (!state.runner.interactive(route) || state.resultsByStepId.containsKey(route.stepId())
						|| state.inFlight.containsKey(route.stepId())) {
					continue;
				}
				boolean depsSatisfied = route.dependsOn().stream().allMatch(dependency -> {
					CollaboratorExecutionResult dependencyResult = state.resultsByStepId.get(dependency);
					return dependencyResult != null && dependencyResult.success();
				});
				if (depsSatisfied) {
					return true;
				}
			}
			return false;
		}

		/**
		 * 入场闸门兜底：释放门控与认领之间存在极窄竞态，交互型在此等待在途步骤清空后独占进入，
		 * 非交互型等待交互执行结束（等价整批模式中交互批次先行、其余批次随后）。
		 */
		private void enterGate(boolean interactive) throws InterruptedException {
			synchronized (state.gate) {
				if (interactive) {
					while (state.gate.interactiveRunning || state.gate.runningCount > 0) {
						state.gate.wait(1000L);
					}
					state.gate.interactiveRunning = true;
				}
				else {
					while (state.gate.interactiveRunning) {
						state.gate.wait(1000L);
					}
					state.gate.runningCount++;
				}
			}
		}

		private void exitGate(boolean interactive) {
			synchronized (state.gate) {
				if (interactive) {
					state.gate.interactiveRunning = false;
				}
				else {
					state.gate.runningCount--;
				}
				state.gate.notifyAll();
			}
		}

	}

	private record CompletedStep(String stepKey, CollaboratorExecutionResult result, Throwable fatal) {
	}

	private static final class Gate {

		private int runningCount;

		private boolean interactiveRunning;

	}

	private static final class ExecutionState {

		private final List<CollaboratorRoute> routes;

		private final Map<String, CollaboratorRoute> routesByStepId;

		private final ConcurrentHashMap<String, CollaboratorExecutionResult> resultsByStepId = new ConcurrentHashMap<>();

		private final LinkedBlockingQueue<CompletedStep> completionQueue = new LinkedBlockingQueue<>();

		private final ConcurrentHashMap<String, InFlightHandle> inFlight = new ConcurrentHashMap<>();

		private final AtomicBoolean halted = new AtomicBoolean(false);

		private final AtomicReference<CollaboratorExecutionResult> firstFailure = new AtomicReference<>();

		private final Gate gate = new Gate();

		private final boolean failFast;

		private final CollaboratorStepRunner runner;

		private volatile boolean waitingObserved;

		private ExecutionState(List<CollaboratorRoute> routes, Map<String, CollaboratorExecutionResult> restoredResults,
				boolean failFast, CollaboratorStepRunner runner) {
			this.routes = List.copyOf(routes);
			this.failFast = failFast;
			this.runner = runner;
			Map<String, CollaboratorRoute> byStepId = new LinkedHashMap<>();
			for (CollaboratorRoute route : routes) {
				byStepId.put(route.stepId(), route);
			}
			this.routesByStepId = Map.copyOf(byStepId);
			if (restoredResults != null) {
				restoredResults.forEach((stepId, result) -> {
					if (stepId != null && result != null && byStepId.containsKey(stepId)) {
						resultsByStepId.put(stepId, result);
					}
				});
			}
		}

		private boolean allResolved() {
			return routes.stream().allMatch(route -> resultsByStepId.containsKey(route.stepId()));
		}

		/**
		 * 按路由顺序组装返回结果；partial=true（挂起早退）时跳过尚无结果的路由，
		 * 与整批模式的部分结果返回一致。
		 */
		private List<CollaboratorExecutionResult> resultsInRouteOrder(boolean partial) {
			List<CollaboratorExecutionResult> ordered = new ArrayList<>();
			for (CollaboratorRoute route : routes) {
				CollaboratorExecutionResult result = resultsByStepId.get(route.stepId());
				if (result != null) {
					ordered.add(result);
				}
				else if (!partial) {
					throw new IllegalStateException("编排计划存在未完成或失败的步骤依赖");
				}
			}
			return List.copyOf(ordered);
		}

	}

}
