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
package com.sn68.agent.dataagent.agentscope.memory;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Compresses {@link AutoContextMemory} before each LLM reasoning call.
 *
 * <p>AgentScope 2.0 {@code ReActAgent} no longer exposes {@code getMemory()}, so the memory
 * instance is injected at construction. {@link ContextOffloadTool} is registered once on the
 * agent's toolkit when the first {@link PreCallEvent} arrives.
 */
public class AutoContextHook implements Hook {

	private static final Logger log = LoggerFactory.getLogger(AutoContextHook.class);

	private static final String OFFLOAD_INSTRUCTION = """

			You may see compressed messages containing <!-- CONTEXT_OFFLOAD uuid=... -->.
			- Use the UUID to call context_reload if you need full details.
			- NEVER mention, quote, or refer to UUIDs, offload tags, or internal metadata in your response.
			""";

	private final AutoContextMemory autoContextMemory;

	private final AtomicBoolean toolRegistered = new AtomicBoolean(false);

	public AutoContextHook(AutoContextMemory autoContextMemory) {
		this.autoContextMemory = Objects.requireNonNull(autoContextMemory, "autoContextMemory must not be null");
	}

	@Override
	public <T extends HookEvent> Mono<T> onEvent(T event) {
		if (event instanceof PreCallEvent preCallEvent) {
			@SuppressWarnings("unchecked")
			Mono<T> result = (Mono<T>) handlePreCall(preCallEvent);
			return result;
		}
		if (event instanceof PreReasoningEvent preReasoningEvent) {
			@SuppressWarnings("unchecked")
			Mono<T> result = (Mono<T>) handlePreReasoning(preReasoningEvent);
			return result;
		}
		return Mono.just(event);
	}

	@Override
	public int priority() {
		return 0;
	}

	private Mono<PreCallEvent> handlePreCall(PreCallEvent event) {
		if (!toolRegistered.compareAndSet(false, true)) {
			return Mono.just(event);
		}
		Toolkit toolkit = toolkitOf(event.getAgent());
		if (toolkit == null) {
			toolRegistered.set(false);
			return Mono.just(event);
		}
		if (toolkit.getTool("context_reload") != null) {
			return Mono.just(event);
		}
		try {
			toolkit.registerTool(new ContextOffloadTool(autoContextMemory));
			log.debug("ContextOffloadTool registered for agent: {}", event.getAgent().getClass().getSimpleName());
		}
		catch (RuntimeException ex) {
			toolRegistered.set(false);
			log.error("Failed to register ContextOffloadTool for agent: {}",
					event.getAgent().getClass().getSimpleName(), ex);
		}
		return Mono.just(event);
	}

	private Mono<PreReasoningEvent> handlePreReasoning(PreReasoningEvent event) {
		List<Msg> originalInputMessages = event.getInputMessages();
		// 2.0 copies history into AgentStateStore and passes the current UserMessage on call().
		// 1.0 had already appended that turn to AutoContextMemory; restore it before compress
		// so lastKeep / offload cannot drop the live user query from the LLM prompt.
		appendCurrentTurnIfMissing(originalInputMessages);
		autoContextMemory.compressIfNeeded();
		List<Msg> newInputMessages = new ArrayList<>();
		if (originalInputMessages != null && !originalInputMessages.isEmpty()
				&& originalInputMessages.get(0).getRole() == MsgRole.SYSTEM) {
			Msg originalSystemMsg = originalInputMessages.get(0);
			String originalSystemText = originalSystemMsg.getTextContent();
			String newSystemText = originalSystemText != null ? originalSystemText + OFFLOAD_INSTRUCTION
					: OFFLOAD_INSTRUCTION.trim();
			newInputMessages.add(Msg.builder()
				.role(MsgRole.SYSTEM)
				.name(originalSystemMsg.getName())
				.content(TextBlock.builder().text(newSystemText).build())
				.metadata(originalSystemMsg.getMetadata())
				.build());
		}
		else {
			newInputMessages.add(Msg.builder()
				.role(MsgRole.SYSTEM)
				.name("system")
				.content(TextBlock.builder().text(OFFLOAD_INSTRUCTION.trim()).build())
				.build());
		}
		newInputMessages.addAll(autoContextMemory.getMessages());
		event.setInputMessages(newInputMessages);
		return Mono.just(event);
	}

	private void appendCurrentTurnIfMissing(List<Msg> originalInputMessages) {
		if (originalInputMessages == null || originalInputMessages.isEmpty()) {
			return;
		}
		int start = 0;
		if (originalInputMessages.get(0).getRole() == MsgRole.SYSTEM) {
			start = 1;
		}
		List<Msg> memory = autoContextMemory.getMessages();
		for (int i = start; i < originalInputMessages.size(); i++) {
			Msg incoming = originalInputMessages.get(i);
			if (incoming == null || incoming.getRole() == MsgRole.SYSTEM || alreadyInMemory(memory, incoming)) {
				continue;
			}
			autoContextMemory.addMessage(incoming);
			memory = autoContextMemory.getMessages();
		}
	}

	private static boolean alreadyInMemory(List<Msg> memory, Msg incoming) {
		if (memory == null || memory.isEmpty() || incoming == null) {
			return false;
		}
		for (Msg existing : memory) {
			if (existing == incoming) {
				return true;
			}
			if (incoming.getId() != null && incoming.getId().equals(existing.getId())) {
				return true;
			}
		}
		return false;
	}

	private static Toolkit toolkitOf(Agent agent) {
		if (agent instanceof ReActAgent reActAgent) {
			return reActAgent.getToolkit();
		}
		return null;
	}

}
