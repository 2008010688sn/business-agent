/* Copyright 2024-2026 the original author or authors. */
package com.sn68.agent.dataagent.event;

import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.service.knowledge.SkillKnowledgeResourceManager;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Handles asynchronous Skill knowledge embedding and cleanup. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillKnowledgeEventListener {

	private final SkillKnowledgeMapper mapper;

	private final SkillKnowledgeResourceManager resourceManager;

	@Async("dbOperationExecutor")
	@EventListener
	public void handleEmbedding(SkillKnowledgeEmbeddingEvent event) {
		SkillKnowledge knowledge = mapper.selectById(event.getKnowledgeId());
		if (knowledge == null) {
			return;
		}
		DataAgentOutboundContext.set(event.getOutboundContext());
		try {
			update(knowledge, EmbeddingStatus.PROCESSING, null);
			resourceManager.embed(knowledge);
			update(knowledge, EmbeddingStatus.COMPLETED, null);
		}
		catch (Exception ex) {
			log.error("Failed to embed Skill knowledge. id={}", knowledge.getId(), ex);
			update(knowledge, EmbeddingStatus.FAILED, ex.getMessage());
		}
		finally {
			DataAgentOutboundContext.clear();
		}
	}

	@Async("dbOperationExecutor")
	@EventListener
	public void handleDeletion(SkillKnowledgeDeletionEvent event) {
		SkillKnowledge knowledge = event.getKnowledge();
		if (knowledge == null) {
			return;
		}
		DataAgentOutboundContext.set(event.getOutboundContext());
		try {
			if (resourceManager.deleteFromVectorStore(knowledge.getSkillId(), knowledge.getId())
					&& resourceManager.deleteKnowledgeFile(knowledge)) {
				mapper.markResourceCleaned(knowledge.getId(), true);
			}
		}
		finally {
			DataAgentOutboundContext.clear();
		}
	}

	private void update(SkillKnowledge knowledge, EmbeddingStatus status, String error) {
		knowledge.setEmbeddingStatus(status);
		knowledge.setLastModifyTime(Instant.now());
		knowledge.setErrorMsg(error == null ? null : error.length() > 250 ? error.substring(0, 250) : error);
		mapper.touch(knowledge);
	}

}
