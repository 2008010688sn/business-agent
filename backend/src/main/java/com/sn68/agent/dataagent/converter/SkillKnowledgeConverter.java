/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.converter;

import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeCreateReq;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.enums.KnowledgeType;
import com.sn68.agent.dataagent.vo.SkillKnowledgeVO;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Converts Skill knowledge records to API objects. */
@Component
public class SkillKnowledgeConverter {

	/**
	 * Entity 转列表/详情 VO；source 为空返回 null。
	 */
	public SkillKnowledgeVO toVo(SkillKnowledge source) {
		if (source == null) {
			return null;
		}
		SkillKnowledgeVO target = new SkillKnowledgeVO();
		target.setId(source.getId());
		target.setSkillId(source.getSkillId());
		target.setTitle(source.getTitle());
		target.setType(source.getType() == null ? null : source.getType().getCode());
		target.setQuestion(source.getQuestion());
		target.setContent(source.getContent());
		target.setIsRecall(Boolean.TRUE.equals(source.getIsRecall()));
		target.setEmbeddingStatus(source.getEmbeddingStatus());
		target.setErrorMsg(source.getErrorMsg());
		target.setSplitterType(source.getSplitterType());
		target.setSourceFilename(source.getSourceFilename());
		target.setFilePath(source.getFilePath());
		target.setFileSize(source.getFileSize());
		target.setFileType(source.getFileType());
		target.setCreatedTime(source.getCreateTime());
		target.setUpdatedTime(source.getLastModifyTime());
		return target;
	}

	/**
	 * 新增请求转 Entity：初始向量化状态置 PENDING，切分器空白时回退 recursive。
	 */
	public SkillKnowledge toEntity(SkillKnowledgeCreateReq request, Long skillId) {
		SkillKnowledge target = new SkillKnowledge();
		target.setSkillId(skillId);
		target.setTitle(request.getTitle());
		target.setType(KnowledgeType.valueOf(request.getType()));
		target.setQuestion(request.getQuestion());
		target.setContent(request.getContent());
		target.setIsRecall(true);
		target.setEmbeddingStatus(EmbeddingStatus.PENDING);
		target.setIsResourceCleaned(false);
		target.setDeleted(false);
		target.setCreateTime(Instant.now());
		target.setLastModifyTime(Instant.now());
		target.setSourceFilename(request.getSourceFilename());
		target.setFilePath(request.getFilePath());
		target.setFileSize(request.getFileSize());
		target.setFileType(request.getFileType());
		target.setSplitterType(request.getSplitterType() == null || request.getSplitterType().isBlank()
			? "recursive" : request.getSplitterType());
		return target;
	}

}
