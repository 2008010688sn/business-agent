/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.knowledge;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.KnowledgeType;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.util.DocumentConverterUtil;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Embeds and removes resources in the Skill vector namespace. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SkillKnowledgeResourceManager {

	private final TextSplitterFactory textSplitterFactory;

	private final LocalFileService localFileService;

	private final AgentVectorStoreService agentVectorStoreService;

	/**
	 * 重新向量化。先删后写，且删除失败必须中止。
	 *
	 * <p>分片文档的 id 是每次随机生成的，重复写入不会覆盖而是叠加；旧分片与新分片又带同一个
	 * {@code skillKnowledgeId}，检索侧的存活行过滤识别不出哪一份是陈旧的。因此一旦旧向量没删干净还继续
	 * 写入，用户就会同时召回到新旧两份内容，而现场只留下一行 WARN。这里让失败直接抛出，由调用方把该行
	 * 标成 {@link com.sn68.agent.dataagent.enums.EmbeddingStatus#FAILED} 并交给重试入口。
	 */
	public void embed(SkillKnowledge knowledge) {
		if (!deleteFromVectorStore(knowledge.getSkillId(), knowledge.getId())) {
			throw new IllegalStateException("Failed to remove previous Skill knowledge vectors before re-embedding: id="
					+ knowledge.getId());
		}
		if (KnowledgeType.QA.equals(knowledge.getType()) || KnowledgeType.FAQ.equals(knowledge.getType())) {
			agentVectorStoreService.addSkillDocuments(String.valueOf(knowledge.getSkillId()),
					List.of(DocumentConverterUtil.convertSkillKnowledgeToDocument(knowledge)));
			return;
		}
		Resource resource = localFileService.getFileResource(knowledge.getFilePath());
		List<Document> source = new TikaDocumentReader(resource).read();
		TextSplitter splitter = textSplitterFactory.getSplitter(knowledge.getSplitterType());
		List<Document> split = splitter.apply(source);
		if (split == null || split.isEmpty()) {
			throw new IllegalStateException("No documents extracted from uploaded Skill knowledge file");
		}
		agentVectorStoreService.addSkillDocuments(String.valueOf(knowledge.getSkillId()),
				DocumentConverterUtil.convertSkillKnowledgeDocumentsWithMetadata(split, knowledge));
	}

	/**
	 * 删除单条知识的向量。
	 *
	 * <p><b>返回值的真实含义是「调用没抛异常」，不是「确实删掉了向量」</b>：底层
	 * {@code AgentVectorStoreServiceImpl#deleteDocumentsByMetadata} 无论是否匹配到文档都返回 {@code true}。
	 * 唯一的消费方 {@code SkillKnowledgeEventListener#handleDeletion} 据此写 {@code skill_knowledge.is_resource_cleaned}，
	 * 所以该字段同样只能证明「清理调用没有报错」；而全代码库没有任何地方读它，它既不可信也不被使用，
	 * 应当接入对账入口或直接下线（下线属 schema 变更，需维护者决策）。
	 */
	public boolean deleteFromVectorStore(Long skillId, Long knowledgeId) {
		try {
			return Boolean.TRUE.equals(agentVectorStoreService.deleteSkillDocumentsByMetadata(String.valueOf(skillId),
					Map.of(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID, String.valueOf(knowledgeId))));
		}
		catch (Exception ex) {
			log.warn("Failed to remove Skill knowledge vectors. skillId={}, knowledgeId={}", skillId, knowledgeId, ex);
			return false;
		}
	}

	public boolean deleteKnowledgeFile(SkillKnowledge knowledge) {
		if (knowledge == null || !KnowledgeType.DOCUMENT.equals(knowledge.getType())
				|| !StringUtils.hasText(knowledge.getFilePath())) {
			return true;
		}
		// Suite file lifecycle is owned by the file service. Knowledge deletion only
		// marks the resource; this keeps shared files from being removed accidentally.
		return true;
	}

}
