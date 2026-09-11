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
package com.sn68.agent.dataagent.agentscope.session;

import com.sn68.agent.dataagent.runtime.durable.service.RuntimeCancellationService;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 运行时请求注册表 —— W2 起降级为「本地缓存」。
 *
 * <p>新定位：本类的 ConcurrentHashMap 只是本节点在途请求（取消标记、运行线程、父子级联）的
 * JVM 视图，权威取消状态在数据库（agent_runtime_interruption / agent_runtime_run.cancellation_epoch，
 * 见 {@link RuntimeCancellationService}）。协作规则：</p>
 * <ul>
 * <li>写穿：{@link #markCancelled} 在完成本地级联中断后，把整棵子树的取消标记写入中断表并递增
 * 权威 Run 的取消纪元，使取消对其他节点与重启后可见；本地无状态（请求不在本节点）时同样写穿，
 * 支持跨节点取消。</li>
 * <li>回源：{@link #isCancelled} 本地 miss 时回源数据库；本地命中但未取消时按
 * {@value #DB_CHECK_INTERVAL_MS}ms 节流回源，发现跨节点取消后回填本地标记并中断运行线程。</li>
 * <li>{@link #isActive} / {@link #hasActiveRequest} 维持本节点语义（独占注册、SSE 挂接均是节点内动作），
 * 不做 DB 回源。</li>
 * <li>数据库不可用时写穿/回源降级为本地行为并记录日志，不阻断运行链路。</li>
 * </ul>
 */
@Slf4j
@Component
public class AgentRuntimeRegistry {

	/**
	 * 本地命中且未取消时的 DB 回源节流间隔：兼顾跨节点取消时效（秒级）与热路径查询开销。
	 */
	private static final long DB_CHECK_INTERVAL_MS = 2000L;

	private final ConcurrentHashMap<String, ConcurrentHashMap<String, RequestExecutionState>> requestStatesByThreadId = new ConcurrentHashMap<>();

	/**
	 * 父请求 -> 在途子请求。协作者跑在自己的 threadId / runtimeRequestId 上，是另一个 key，
	 * 没有这张表时对父请求 markCancelled 完全不会触及它们。
	 */
	private final ConcurrentHashMap<RequestKey, Set<RequestKey>> childKeysByParentKey = new ConcurrentHashMap<>();

	/**
	 * 权威取消状态的写穿/回源通道；为空表示无持久化环境（仅单元测试），全部行为退化为纯内存。
	 */
	private final RuntimeCancellationService cancellationService;

	@Autowired
	public AgentRuntimeRegistry(RuntimeCancellationService cancellationService) {
		this.cancellationService = cancellationService;
	}

	/**
	 * 仅供单元测试：无持久化通道的纯内存注册表（写穿与回源为空操作）。
	 */
	public AgentRuntimeRegistry() {
		this.cancellationService = null;
	}

	public void register(String threadId, String runtimeRequestId) {
		RequestExecutionState state = getOrCreateState(threadId, runtimeRequestId, null, null);
		state.cancelled.set(false);
		state.runningThread.set(null);
		inheritPersistedCancellation(threadId, runtimeRequestId, state);
	}

	public boolean tryRegisterExclusive(String threadId, String runtimeRequestId) {
		return tryRegisterExclusive(threadId, runtimeRequestId, null, null);
	}

	public boolean tryRegisterExclusive(String threadId, String runtimeRequestId, String requestSource, String agentId) {
		validateKey(threadId, runtimeRequestId);
		AtomicBoolean registered = new AtomicBoolean(false);
		requestStatesByThreadId.compute(threadId, (key, states) -> {
			if (states != null && !states.isEmpty()) {
				return states;
			}
			ConcurrentHashMap<String, RequestExecutionState> newStates = new ConcurrentHashMap<>();
			RequestExecutionState state = new RequestExecutionState(requestSource, agentId);
			state.cancelled.set(false);
			state.runningThread.set(null);
			newStates.put(runtimeRequestId, state);
			registered.set(true);
			return newStates;
		});
		if (registered.get()) {
			RequestExecutionState state = getState(threadId, runtimeRequestId);
			if (state != null) {
				inheritPersistedCancellation(threadId, runtimeRequestId, state);
			}
		}
		return registered.get();
	}

	/**
	 * 把子请求挂到父请求下，使父请求被取消时能级联到已经跑起来的子请求。
	 * 父键为空表示这是顶层请求，不需要关联。
	 */
	public void registerChild(String parentThreadId, String parentRuntimeRequestId, String childThreadId,
			String childRuntimeRequestId) {
		if (parentThreadId == null || parentThreadId.isBlank() || parentRuntimeRequestId == null
				|| parentRuntimeRequestId.isBlank()) {
			return;
		}
		RequestKey parentKey = new RequestKey(parentThreadId, parentRuntimeRequestId);
		RequestKey childKey = new RequestKey(childThreadId, childRuntimeRequestId);
		RequestExecutionState childState = getOrCreateState(childThreadId, childRuntimeRequestId, null, null);
		childState.parentKey.set(parentKey);
		childKeysByParentKey.computeIfAbsent(parentKey, key -> ConcurrentHashMap.newKeySet()).add(childKey);
		// 关联动作和父请求取消可能并发：父请求已经取消完才挂上来的子请求要立即继承取消状态，
		// 否则它会错过这一次级联，继续跑到自己的 deadline。
		if (isCancelled(parentThreadId, parentRuntimeRequestId)) {
			markCancelled(childThreadId, childRuntimeRequestId);
		}
	}

	public boolean markCancelled(String threadId, String runtimeRequestId) {
		RequestExecutionState state = getState(threadId, runtimeRequestId);
		boolean persisted = false;
		if (state == null) {
			// 本节点无状态：请求可能在其他节点运行，取消标记写穿到 DB 供其回源发现
			persisted = writeThroughCancellation(threadId, runtimeRequestId);
			return persisted;
		}
		// 先把整棵在途子树都打上取消标记，再统一中断线程：
		// 反过来的话父线程可能在子请求还没标记取消时就被唤醒，按「未取消」继续走完后续分支。
		List<CancelledEntry> cancelledEntries = collectCancelledSubtree(threadId, runtimeRequestId);
		for (CancelledEntry cancelled : cancelledEntries) {
			Thread runningThread = cancelled.state().runningThread.get();
			if (runningThread != null) {
				runningThread.interrupt();
			}
		}
		// 写穿整棵子树：协作者子请求各自对应独立的权威 Run/中断记录
		for (CancelledEntry cancelled : cancelledEntries) {
			writeThroughCancellation(cancelled.key().threadId(), cancelled.key().runtimeRequestId());
		}
		return true;
	}

	private List<CancelledEntry> collectCancelledSubtree(String threadId, String runtimeRequestId) {
		List<CancelledEntry> cancelledEntries = new ArrayList<>();
		Set<RequestKey> visited = new HashSet<>();
		Deque<RequestKey> pending = new ArrayDeque<>();
		pending.add(new RequestKey(threadId, runtimeRequestId));
		while (!pending.isEmpty()) {
			RequestKey key = pending.poll();
			if (!visited.add(key)) {
				continue;
			}
			RequestExecutionState state = getState(key.threadId(), key.runtimeRequestId());
			// 已经跑完的子请求不在状态表里，天然不受级联影响。
			if (state == null) {
				continue;
			}
			state.cancelled.set(true);
			cancelledEntries.add(new CancelledEntry(key, state));
			Set<RequestKey> children = childKeysByParentKey.get(key);
			if (children != null) {
				pending.addAll(children);
			}
		}
		return cancelledEntries;
	}

	public void markRunning(String threadId, String runtimeRequestId, Thread thread) {
		getOrCreateState(threadId, runtimeRequestId, null, null).runningThread.set(thread);
	}

	public void clearRunning(String threadId, String runtimeRequestId) {
		RequestExecutionState state = getState(threadId, runtimeRequestId);
		if (state != null) {
			state.runningThread.set(null);
		}
	}

	public boolean isActive(String threadId, String runtimeRequestId) {
		RequestExecutionState state = getState(threadId, runtimeRequestId);
		return state != null && !state.cancelled.get();
	}

	public boolean hasActiveRequest(String threadId) {
		if (threadId == null || threadId.isBlank()) {
			return false;
		}
		ConcurrentHashMap<String, RequestExecutionState> states = requestStatesByThreadId.get(threadId);
		return states != null && states.values().stream().anyMatch(state -> !state.cancelled.get());
	}

	public RuntimeExecutionStateView activeRequest(String threadId) {
		if (threadId == null || threadId.isBlank()) {
			return null;
		}
		ConcurrentHashMap<String, RequestExecutionState> states = requestStatesByThreadId.get(threadId);
		if (states == null || states.isEmpty()) {
			return null;
		}
		return states.entrySet()
			.stream()
			.filter(entry -> !entry.getValue().cancelled.get())
			.findFirst()
			.map(entry -> toView(threadId, entry.getKey(), entry.getValue()))
			.orElse(null);
	}

	public boolean isCancelled(String threadId, String runtimeRequestId) {
		RequestExecutionState state = getState(threadId, runtimeRequestId);
		if (state == null) {
			// 本地 miss：请求不在本节点（或尚未注册），回源权威取消状态
			return readThroughCancellation(runtimeRequestId);
		}
		if (state.cancelled.get()) {
			return true;
		}
		// 本地命中但未取消：节流回源，捕捉其他节点发起的取消并回填本地标记
		if (shouldCheckDb(state) && readThroughCancellation(runtimeRequestId)) {
			state.cancelled.set(true);
			Thread runningThread = state.runningThread.get();
			if (runningThread != null) {
				runningThread.interrupt();
			}
			return true;
		}
		return false;
	}

	public void finish(String threadId, String runtimeRequestId) {
		if (threadId == null || threadId.isBlank() || runtimeRequestId == null || runtimeRequestId.isBlank()) {
			return;
		}
		RequestExecutionState state = getState(threadId, runtimeRequestId);
		RequestKey parentKey = state == null ? null : state.parentKey.get();
		requestStatesByThreadId.computeIfPresent(threadId, (key, states) -> {
			states.remove(runtimeRequestId);
			return states.isEmpty() ? null : states;
		});
		// 父子关联表必须和状态表同生共死，否则每一轮编排都会往 map 里留下永不回收的键。
		RequestKey requestKey = new RequestKey(threadId, runtimeRequestId);
		childKeysByParentKey.remove(requestKey);
		if (parentKey != null) {
			childKeysByParentKey.computeIfPresent(parentKey, (key, children) -> {
				children.remove(requestKey);
				return children.isEmpty() ? null : children;
			});
		}
	}

	/**
	 * 仅供测试与诊断：当前仍被跟踪的运行时请求数量。
	 */
	int trackedRequestCount() {
		return requestStatesByThreadId.values().stream().mapToInt(ConcurrentHashMap::size).sum();
	}

	/**
	 * 仅供测试与诊断：当前仍被跟踪的父子关联数量。
	 */
	int trackedParentLinkCount() {
		return childKeysByParentKey.values().stream().mapToInt(Set::size).sum();
	}

	/**
	 * 注册时继承已写穿的取消标记，覆盖「先取消后注册」的跨节点竞态。
	 */
	private void inheritPersistedCancellation(String threadId, String runtimeRequestId, RequestExecutionState state) {
		if (readThroughCancellation(runtimeRequestId)) {
			state.cancelled.set(true);
			log.info("注册时发现已持久化的取消标记，直接继承. threadId={}, runtimeRequestId={}", threadId, runtimeRequestId);
		}
	}

	private boolean writeThroughCancellation(String threadId, String runtimeRequestId) {
		if (cancellationService == null || runtimeRequestId == null || runtimeRequestId.isBlank()) {
			return false;
		}
		try {
			return cancellationService.requestCancelByRuntimeRequestId(threadId, runtimeRequestId, "用户取消");
		}
		catch (RuntimeException ex) {
			// 写穿失败不阻断本地取消：本地级联已生效，权威侧由重复取消请求自愈
			log.error("取消标记写穿失败. threadId={}, runtimeRequestId={}", threadId, runtimeRequestId, ex);
			return false;
		}
	}

	private boolean readThroughCancellation(String runtimeRequestId) {
		if (cancellationService == null || runtimeRequestId == null || runtimeRequestId.isBlank()) {
			return false;
		}
		try {
			return cancellationService.isCancelRequested(runtimeRequestId);
		}
		catch (RuntimeException ex) {
			log.warn("取消状态回源失败，按本地状态继续. runtimeRequestId={}", runtimeRequestId, ex);
			return false;
		}
	}

	private boolean shouldCheckDb(RequestExecutionState state) {
		if (cancellationService == null) {
			return false;
		}
		long now = System.currentTimeMillis();
		long last = state.lastDbCheckAtMillis.get();
		return now - last >= DB_CHECK_INTERVAL_MS && state.lastDbCheckAtMillis.compareAndSet(last, now);
	}

	private RequestExecutionState getOrCreateState(String threadId, String runtimeRequestId, String requestSource,
			String agentId) {
		validateKey(threadId, runtimeRequestId);
		ConcurrentHashMap<String, RequestExecutionState> states = requestStatesByThreadId.computeIfAbsent(threadId,
				key -> new ConcurrentHashMap<>());
		return states.computeIfAbsent(runtimeRequestId, key -> new RequestExecutionState(requestSource, agentId));
	}

	private RequestExecutionState getState(String threadId, String runtimeRequestId) {
		if (threadId == null || threadId.isBlank() || runtimeRequestId == null || runtimeRequestId.isBlank()) {
			return null;
		}
		ConcurrentHashMap<String, RequestExecutionState> states = requestStatesByThreadId.get(threadId);
		return states == null ? null : states.get(runtimeRequestId);
	}

	private void validateKey(String threadId, String runtimeRequestId) {
		if (threadId == null || threadId.isBlank() || runtimeRequestId == null || runtimeRequestId.isBlank()) {
			throw new IllegalArgumentException("threadId 和 runtimeRequestId 不能为空");
		}
	}

	private RuntimeExecutionStateView toView(String threadId, String runtimeRequestId, RequestExecutionState state) {
		Thread runningThread = state.runningThread.get();
		return new RuntimeExecutionStateView(threadId, runtimeRequestId, state.cancelled.get(),
				runningThread != null && runningThread.isAlive(), state.createdAt, state.requestSource, state.agentId);
	}

	public record RuntimeExecutionStateView(String threadId, String runtimeRequestId, boolean cancelled,
			boolean running, Instant startedAt, String requestSource, String agentId) {
	}

	private record RequestKey(String threadId, String runtimeRequestId) {
	}

	private record CancelledEntry(RequestKey key, RequestExecutionState state) {
	}

	private static final class RequestExecutionState {

		private final Instant createdAt = Instant.now();

		private final String requestSource;

		private final String agentId;

		private final AtomicBoolean cancelled = new AtomicBoolean(false);

		private final AtomicReference<Thread> runningThread = new AtomicReference<>();

		private final AtomicReference<RequestKey> parentKey = new AtomicReference<>();

		/**
		 * 最近一次取消状态 DB 回源时间（毫秒），用于热路径节流。
		 */
		private final AtomicLong lastDbCheckAtMillis = new AtomicLong(0L);

		private RequestExecutionState(String requestSource, String agentId) {
			this.requestSource = requestSource;
			this.agentId = agentId;
		}

	}

}
