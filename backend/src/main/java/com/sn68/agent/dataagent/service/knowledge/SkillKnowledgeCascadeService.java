/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.knowledge;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 删除整个 Skill 时级联清理它名下的知识行与知识向量。
 *
 * <p>调用契约是两段式，顺序不可颠倒：{@link #deleteKnowledgeRows(Long)} 必须与删除 Skill 主行处在同一个短事务内，
 * {@link #purgeKnowledgeVectors(Long, DeletedSkillKnowledge)} 必须在该事务提交之后、事务之外执行。
 *
 * <p><b>为什么是先库后向量</b>：反过来先删向量，一旦库这边回滚，Skill 还在而它的知识已经检索不到，属于
 * 「库里有、搜不到」的静默降级（与 {@code BusinessKnowledgeServiceImpl#deleteKnowledge} 修的是同一种病）。
 * 先库后向量的残留方向则是无害的：Skill 行已墓碑化，运行时按 skillId 再也解析不出这个 Skill，没有任何检索会
 * 带上它，剩下的向量是纯存储占用而非可召回内容。
 *
 * <p><b>为什么可以越过「已发布版本固定的知识不许删」这道守卫</b>：逐条删除知识时
 * （{@code SkillKnowledgeServiceImpl#delete} / {@code BusinessKnowledgeServiceImpl#deleteKnowledge}）会拒绝
 * 被已发布版本引用的行，防止已发布版本读到被改动过的内容。整个 Skill 删除时这层保护已无对象：发布快照只可能
 * 引用同一个 Skill 自己的知识行（见 {@code SkillVersionResourceSnapshotService}，两处快照都过滤
 * {@code knowledge.skillId == skill.id}，库侧外键同样把知识行绑死在单个 Skill 上），而删除本身已经要求该 Skill
 * 不再被任何 Agent 绑定，其发布版本随主行墓碑化一起失去可执行性。若在这里改为「有知识就拒绝删除」，反而会
 * 死锁——被发布版本引用的知识逐条删不掉，版本也没有任何退役入口，那个 Skill 将永远删不掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillKnowledgeCascadeService {

	private final BusinessKnowledgeMapper businessKnowledgeMapper;

	private final SkillKnowledgeMapper skillKnowledgeMapper;

	private final AgentVectorStoreService agentVectorStoreService;

	/**
	 * 逻辑删除该 Skill 名下的全部知识行，返回被删除的主键以便调用方记录与报错。
	 *
	 * <p>取的是该 Skill 的全部存活行，不复用 {@code selectBySkillId}：后者带
	 * {@code superseded_by_id IS NULL} 条件，而被新副本取代的旧行恰恰是仍持有向量的那一批。
	 */
	public DeletedSkillKnowledge deleteKnowledgeRows(Long skillId) {
		requireSkillId(skillId);
		List<Long> businessKnowledgeIds = businessKnowledgeMapper
			.selectList(Wraps.<BusinessKnowledge>lbQ()
				.select(BusinessKnowledge::getId)
				.eq(BusinessKnowledge::getSkillId, skillId))
			.stream()
			.map(BusinessKnowledge::getId)
			.filter(Objects::nonNull)
			.toList();
		List<Long> skillKnowledgeIds = skillKnowledgeMapper
			.selectList(Wraps.<SkillKnowledge>lbQ()
				.select(SkillKnowledge::getId)
				.eq(SkillKnowledge::getSkillId, skillId))
			.stream()
			.map(SkillKnowledge::getId)
			.filter(Objects::nonNull)
			.toList();
		// 按 id 而非按 skillId 更新，保证「报告出去的 id」与「真正被删的行」是同一批；
		// 空集合必须提前挡住，Wraps 会静默丢弃空 in 条件，那样就变成无条件全表逻辑删除。
		if (!businessKnowledgeIds.isEmpty()) {
			businessKnowledgeMapper.update(null, Wraps.<BusinessKnowledge>lbU()
				.in(BusinessKnowledge::getId, businessKnowledgeIds)
				.set(BusinessKnowledge::getDeleted, true)
				.set(BusinessKnowledge::getLastModifyTime, Instant.now()));
		}
		if (!skillKnowledgeIds.isEmpty()) {
			skillKnowledgeMapper.update(null, Wraps.<SkillKnowledge>lbU()
				.in(SkillKnowledge::getId, skillKnowledgeIds)
				.set(SkillKnowledge::getDeleted, true)
				.set(SkillKnowledge::getLastModifyTime, Instant.now()));
		}
		return new DeletedSkillKnowledge(businessKnowledgeIds, skillKnowledgeIds);
	}

	/**
	 * 清理该 Skill 的知识向量。必须在 {@link #deleteKnowledgeRows(Long)} 所在事务提交之后调用。
	 *
	 * <p><b>非事务补偿点</b>：清理失败时行已经删掉了，残留的向量既没有对应行、也不会再被任何检索命中，
	 * 但 Skill 已不可访问，既有的知识库重建入口再也覆盖不到它们，只能由运维按 skillId 清理。因此这里把失败
	 * 抛给调用方，并在日志里留下 skillId 与被删除的知识 id。
	 */
	public void purgeKnowledgeVectors(Long skillId, DeletedSkillKnowledge deleted) {
		requireSkillId(skillId);
		DeletedSkillKnowledge rows = deleted == null ? DeletedSkillKnowledge.none() : deleted;
		String scopedSkillId = String.valueOf(skillId);
		List<String> failedVectorTypes = new ArrayList<>();
		// 按 vectorType 整体清扫而不是逐行删：Skill 整体消失后，此前清理失败留下的孤儿向量在库里已经没有
		// 对应行，逐行删永远够不到它们，而这正是本次要收口的泄漏。
		for (String vectorType : List.of(DocumentMetadataConstant.BUSINESS_TERM,
				DocumentMetadataConstant.SKILL_KNOWLEDGE)) {
			try {
				agentVectorStoreService.deleteSkillDocumentsByVectorType(scopedSkillId, vectorType);
			}
			catch (Exception ex) {
				log.error("Skill 已删除但知识向量清理失败，需按 skillId 手工清理. skillId={}, vectorType={}, "
						+ "businessKnowledgeIds={}, skillKnowledgeIds={}", skillId, vectorType,
						rows.businessKnowledgeIds(), rows.skillKnowledgeIds(), ex);
				failedVectorTypes.add(vectorType);
			}
		}
		if (!failedVectorTypes.isEmpty()) {
			throw CheckedException.fail("Skill 已删除，但知识向量清理失败（skillId=" + skillId + "，vectorType="
					+ String.join(",", failedVectorTypes) + "），该 Skill 已无法访问、重建入口覆盖不到这些向量，"
					+ "需按 skillId 手工清理");
		}
		log.info("Skill knowledge cascade finished. skillId={}, businessKnowledgeRows={}, skillKnowledgeRows={}",
				skillId, rows.businessKnowledgeIds().size(), rows.skillKnowledgeIds().size());
	}

	private void requireSkillId(Long skillId) {
		if (skillId == null) {
			throw CheckedException.badRequest("skillId cannot be null");
		}
	}

	/** 一次级联中被逻辑删除的知识主键。 */
	public record DeletedSkillKnowledge(List<Long> businessKnowledgeIds, List<Long> skillKnowledgeIds) {

		public DeletedSkillKnowledge {
			businessKnowledgeIds = businessKnowledgeIds == null ? List.of() : List.copyOf(businessKnowledgeIds);
			skillKnowledgeIds = skillKnowledgeIds == null ? List.of() : List.copyOf(skillKnowledgeIds);
		}

		public static DeletedSkillKnowledge none() {
			return new DeletedSkillKnowledge(List.of(), List.of());
		}

	}

}
