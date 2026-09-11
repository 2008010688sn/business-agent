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
import com.sn68.agent.dataagent.agentscope.dto.GroundedFacts;
import com.sn68.agent.dataagent.agentscope.dto.GroundedKey;
import com.sn68.agent.dataagent.service.security.UntrustedContentBoundary;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

/**
 * 把本轮可信链接键注入分析期 SYSTEM 指令。groundedFacts 非空才装配。
 */
@Slf4j
public class LinkContextHook implements Hook {

	private final String systemDirective;

	private final AtomicBoolean injected = new AtomicBoolean(false);

	private LinkContextHook(String systemDirective) {
		this.systemDirective = systemDirective;
	}

	public static LinkContextHook from(AgentRequest request) {
		if (request == null || request.getGroundedFacts() == null) {
			return null;
		}
		return new LinkContextHook(buildDirective(request.getGroundedFacts()));
	}

	@Override
	public <T extends HookEvent> Mono<T> onEvent(T event) {
		if (!(event instanceof PreReasoningEvent preReasoningEvent) || !StringUtils.hasText(systemDirective)
				|| !injected.compareAndSet(false, true)) {
			return Mono.just(event);
		}
		preReasoningEvent.setInputMessages(withDirective(preReasoningEvent.getInputMessages()));
		if (log.isDebugEnabled()) {
			log.debug("Link context hook injected. directiveLength={}", systemDirective.length());
		}
		return Mono.just(event);
	}

	private List<Msg> withDirective(List<Msg> inputMessages) {
		List<Msg> messages = inputMessages == null ? new ArrayList<>() : new ArrayList<>(inputMessages);
		messages.add(0, Msg.builder().name("link-context").role(MsgRole.SYSTEM).textContent(systemDirective).build());
		return messages;
	}

	private static String buildDirective(GroundedFacts facts) {
		StringBuilder builder = new StringBuilder();
		builder.append("本轮链接与页面上下文规则：\n");
		builder.append("- 只用 trust=own_origin 或 trust=page_context 的键，在当前技能已绑定的白名单表上查询。\n");
		builder.append("- 不要打开本系统 URL 去抓取页面。\n");
		builder.append("- trust=external 的键只作引用材料，不要当作本系统业务编号去查库。\n");
		builder.append("- 库中无行或无权访问时，改用本轮附件、图片和问句材料作答，并声明来源；禁止编造关联单据。\n");
		builder.append("- 本轮键覆盖会话里的旧单号，不要沿用上一张单。\n");
		builder.append("- 多张表命中同一数字时，列出命中，不要把第一行当成用户要的对象。\n");
		List<GroundedKey> keys = facts.getKeys();
		if (!CollectionUtils.isEmpty(keys)) {
			builder.append('\n').append(UntrustedContentBoundary.wrap("link-keys", formatKeys(keys)));
		}
		if (StringUtils.hasText(facts.getFailOpenReason())) {
			builder.append("\n本轮未能稳定解析链接键（").append(facts.getFailOpenReason())
					.append("），改用材料作答，不要编造关联单据。");
		}
		return builder.toString();
	}

	private static String formatKeys(List<GroundedKey> keys) {
		StringBuilder builder = new StringBuilder();
		for (GroundedKey key : keys) {
			if (key == null || !StringUtils.hasText(key.getName()) || !StringUtils.hasText(key.getValue())) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append('\n');
			}
			builder.append(key.getName()).append('=').append(key.getValue()).append(" trust=")
					.append(key.getTrust());
		}
		return builder.toString();
	}

}
