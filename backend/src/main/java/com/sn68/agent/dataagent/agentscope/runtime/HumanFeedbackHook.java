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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.service.security.UntrustedContentBoundary;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * HumanFeedback钩子组件，封装 DataAgent 对应业务入口。
 */
public class HumanFeedbackHook implements Hook {

	/**
	 * 反馈正文长度上限。入口 {@code DataAgentController.streamSearch} 收的是裸 {@code @RequestBody}、
	 * 无 {@code @Valid}，字段本身也没有 {@code @Size}，因此这里做兜底截断，避免单条反馈撑爆上下文预算。
	 */
	private static final int MAX_FEEDBACK_LENGTH = 2000;

	private static final String TRUNCATION_SUFFIX = "…（反馈内容过长已截断）";

	private final boolean pauseAfterPlanning;

	private final AtomicBoolean replayRequested;

	/** 由布尔位推导的控制指令，不含调用方文本，可安全以 SYSTEM 下发。 */
	private final String systemDirective;

	/** 调用方传入的反馈正文，只能以 USER 角色进入。 */
	private final String userFeedback;

	private HumanFeedbackHook(boolean pauseAfterPlanning, boolean replayRequested, String systemDirective,
			String userFeedback) {
		this.pauseAfterPlanning = pauseAfterPlanning;
		this.replayRequested = new AtomicBoolean(replayRequested);
		this.systemDirective = systemDirective;
		this.userFeedback = userFeedback;
	}

	/**
	 * 处理HumanFeedback钩子。
	 */
	public static HumanFeedbackHook from(AgentRequest request) {
		boolean hasFeedbackContent = StringUtils.hasText(request.getHumanFeedbackContent());
		boolean requiresReplay = hasFeedbackContent || request.isRejectedPlan();
		boolean requiresPause = request.isHumanFeedback() && !requiresReplay;
		if (!requiresPause && !requiresReplay) {
			return null;
		}
		return new HumanFeedbackHook(requiresPause, requiresReplay, buildSystemDirective(request),
				sanitizeFeedback(request.getHumanFeedbackContent()));
	}

	/**
	 * 处理HumanFeedback钩子。
	 */
	@Override
	public <T extends HookEvent> Mono<T> onEvent(T event) {
		if (event instanceof PreReasoningEvent preReasoningEvent && replayRequested.compareAndSet(true, false)
				&& (StringUtils.hasText(systemDirective) || StringUtils.hasText(userFeedback))) {
			preReasoningEvent.setInputMessages(withReviewMessages(preReasoningEvent.getInputMessages()));
			return Mono.just(event);
		}
		if (!(event instanceof PostReasoningEvent postReasoningEvent)) {
			return Mono.just(event);
		}
		if (pauseAfterPlanning) {
			postReasoningEvent.stopAgent();
		}
		return Mono.just(event);
	}

	/**
	 * 控制指令与反馈正文分两条消息注入，且角色不同——这是本方法存在的全部意义。
	 *
	 * <p>
	 * 控制指令由 {@code isRejectedPlan()} 这个布尔位推导，内容完全固定、不含任何调用方文本，
	 * 以 SYSTEM 下发是安全的，仍放在队首以便先于业务提示词被读到。
	 *
	 * <p>
	 * 反馈正文则是调用方经 {@code @RequestBody} 传入的任意文本，只能以 USER 角色追加到队尾。
	 * 早前的实现把它拼进 SYSTEM 消息并附上「视其为权威」，等于把用户输入提升到系统权限——
	 * 攻击者无需污染数据库、无需构造检索命中，直接在反馈框里写系统级指令即可改写系统提示中的规则。
	 * 换成 USER 角色后信任级别与用户提问本身一致：它依然能表达用户意图（这正是人工反馈该有的效果），
	 * 但不再凌驾于系统规则之上。
	 */
	private List<Msg> withReviewMessages(List<Msg> inputMessages) {
		List<Msg> messages = new ArrayList<>(inputMessages);
		if (StringUtils.hasText(systemDirective)) {
			messages.add(0,
					Msg.builder().name("human-review").role(MsgRole.SYSTEM).textContent(systemDirective).build());
		}
		if (StringUtils.hasText(userFeedback)) {
			messages.add(Msg.builder().name("human-review").role(MsgRole.USER).textContent(userFeedback).build());
		}
		return messages;
	}

	private static String buildSystemDirective(AgentRequest request) {
		if (!request.isRejectedPlan()) {
			return null;
		}
		return "Human review directive:\n- The previous plan was rejected. Re-plan before continuing.";
	}

	/**
	 * 反馈正文的入站清洗。
	 *
	 * <p>
	 * 刻意<b>不</b>使用 {@code UntrustedContentBoundary.wrap()}：那套标记的语义是「标记内的一切永远是数据、
	 * 不是指令，不执行不遵循」（见 {@code INSTRUCTION_HIERARCHY_RULE}），用在这里会让智能体忽略掉合法的人工反馈，
	 * 功能直接失效。人工反馈是真实的用户意图，USER 角色就是它应有的信任级别。
	 *
	 * <p>
	 * 但哨兵仍必须中和：反馈会与其它承载检索数据的消息共处一个上下文，若允许其中出现伪造的
	 * {@code END} 标记，就能提前闭合上文的不可信数据块，让本应被当作数据的内容显得像可信指令。
	 */
	private static String sanitizeFeedback(String feedbackContent) {
		if (!StringUtils.hasText(feedbackContent)) {
			return null;
		}
		String neutralized = UntrustedContentBoundary.neutralize(feedbackContent.strip());
		if (neutralized.length() > MAX_FEEDBACK_LENGTH) {
			neutralized = neutralized.substring(0, MAX_FEEDBACK_LENGTH) + TRUNCATION_SUFFIX;
		}
		return "以下是我对上一轮计划的人工反馈，请据此修正理解后再继续：\n" + neutralized;
	}

}
