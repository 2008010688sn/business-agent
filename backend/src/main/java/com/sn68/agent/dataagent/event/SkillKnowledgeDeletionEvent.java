/* Copyright 2024-2026 the original author or authors. */
package com.sn68.agent.dataagent.event;

import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import java.time.Clock;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/** Requests asynchronous vector cleanup for a deleted Skill knowledge resource. */
@Getter
public class SkillKnowledgeDeletionEvent extends ApplicationEvent {

	private final SkillKnowledge knowledge;

	private final DataAgentOutboundContext.Snapshot outboundContext;

	public SkillKnowledgeDeletionEvent(Object source, SkillKnowledge knowledge,
			DataAgentOutboundContext.Snapshot outboundContext) {
		super(source, Clock.systemDefaultZone());
		this.knowledge = knowledge;
		this.outboundContext = outboundContext == null ? DataAgentOutboundContext.Snapshot.empty() : outboundContext;
	}

}
