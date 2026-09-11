/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/** Persistence contract for Skill-owned knowledge-base resources. */
@Repository
public interface SkillKnowledgeMapper extends SuperMapper<SkillKnowledge> {

	/**
	 * 查询 Skill 下当前有效（未被新版本取代，supersededById 为空）的知识条目，按创建时间倒序。
	 */
	default List<SkillKnowledge> selectBySkillId(Long skillId) {
		return selectList(new LambdaQueryWrapper<SkillKnowledge>().eq(SkillKnowledge::getSkillId, skillId)
			.isNull(SkillKnowledge::getSupersededById).orderByDesc(SkillKnowledge::getCreateTime));
	}

	/**
	 * 查询 Skill 下开启召回（isRecall=true）且未被取代的知识 ID 集合，供向量检索过滤使用。
	 */
	default List<Long> selectRecalledKnowledgeIds(Long skillId) {
		return selectList(new LambdaQueryWrapper<SkillKnowledge>().select(SkillKnowledge::getId)
			.eq(SkillKnowledge::getSkillId, skillId).isNull(SkillKnowledge::getSupersededById)
			.eq(SkillKnowledge::getIsRecall, true)).stream().map(SkillKnowledge::getId)
			.filter(Objects::nonNull).toList();
	}

	/**
	 * 标记知识库资源（向量、文件）已清理完毕。
	 *
	 * <p>刻意保留原生 SQL：唯一调用方 {@code SkillKnowledgeEventListener#handleDeletion} 在
	 * {@code deleteById} 提交之后才异步执行，此时目标行 {@code deleted} 已为 true；改用 MyBatis-Plus API
	 * 会被 {@code @TableLogic} 追加 {@code deleted = false}，UPDATE 命中 0 行，清理标记将永远写不进去。
	 */
	@Update("UPDATE skill_knowledge SET is_resource_cleaned = #{cleaned}, last_modify_time = CURRENT_TIMESTAMP WHERE id = #{id}")
	int markResourceCleaned(@Param("id") Long id, @Param("cleaned") Boolean cleaned);

	/**
	 * 按主键整行更新知识条目（updateById 的语义别名，用于刷新 embedding 状态等运行时字段）。
	 */
	default int touch(SkillKnowledge knowledge) {
		return updateById(knowledge);
	}

}
