/* Copyright 2024-2026 the original author or authors. */
package com.sn68.agent.dataagent.service.knowledge;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeCreateReq;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeQueryReq;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeUpdateReq;
import com.sn68.agent.dataagent.vo.SkillKnowledgeVO;

/** Management contract for QA Skill knowledge-base resources. */
public interface SkillKnowledgeService {

	SkillKnowledgeVO get(Long skillId, Long id);

	SkillKnowledgeVO create(Long skillId, SkillKnowledgeCreateReq request);

	SkillKnowledgeVO update(Long skillId, Long id, SkillKnowledgeUpdateReq request);

	void delete(Long skillId, Long id);

	IPage<SkillKnowledgeVO> page(Long skillId, SkillKnowledgeQueryReq request);

	SkillKnowledgeVO updateRecallStatus(Long skillId, Long id, Boolean recalled);

	void retryEmbedding(Long skillId, Long id);

}
