/* Copyright 2024-2026 the original author or authors. */
package com.sn68.agent.dataagent.event;

import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import java.time.Clock;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/** Requests asynchronous embedding for one Skill knowledge resource. */
@Getter
public class SkillKnowledgeEmbeddingEvent extends ApplicationEvent {

	private final Long knowledgeId;

	private final DataAgentOutboundContext.Snapshot outboundContext;

	public SkillKnowledgeEmbeddingEvent(Object source, Long knowledgeId,
			DataAgentOutboundContext.Snapshot outboundContext) {
		super(source, Clock.systemDefaultZone());
		this.knowledgeId = knowledgeId;
		this.outboundContext = outboundContext == null ? DataAgentOutboundContext.Snapshot.empty() : outboundContext;
	}

}
