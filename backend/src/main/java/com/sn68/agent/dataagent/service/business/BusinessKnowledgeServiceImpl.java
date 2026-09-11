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
package com.sn68.agent.dataagent.service.business;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.util.DocumentConverterUtil;
import com.sn68.agent.dataagent.converter.BusinessKnowledgeConverter;
import com.sn68.agent.dataagent.dto.knowledge.businessknowledge.CreateBusinessKnowledgeDTO;
import com.sn68.agent.dataagent.dto.knowledge.businessknowledge.UpdateBusinessKnowledgeDTO;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.service.skill.PublishedSkillResourceReferenceService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.vo.BusinessKnowledgeVO;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 业务知识管理实现：维护业务术语与口径知识的增删改查及向量化联动。
 */
@Slf4j
@Service
@AllArgsConstructor
public class BusinessKnowledgeServiceImpl implements BusinessKnowledgeService {

	private final BusinessKnowledgeMapper businessKnowledgeMapper;

	private final AgentVectorStoreService agentVectorStoreService;

	private final BusinessKnowledgeConverter businessKnowledgeConverter;

	private final PublishedSkillResourceReferenceService publishedResourceReferenceService;

	private final TransactionTemplate transactionTemplate;

	@Override
	public List<BusinessKnowledgeVO> listKnowledge(Long skillId, String keyword) {
		if (StringUtils.hasText(keyword)) {
			return searchKnowledge(skillId, keyword);
		}
		return getKnowledge(skillId);
	}

	@Override
	public List<BusinessKnowledgeVO> getKnowledge(Long skillId) {
		List<BusinessKnowledge> businessKnowledges = businessKnowledgeMapper.selectBySkillId(skillId);
		if (CollectionUtils.isEmpty(businessKnowledges)) {
			return Collections.emptyList();
		}
		return businessKnowledges.stream().map(businessKnowledgeConverter::toVo).toList();
	}

	@Override
	public List<BusinessKnowledgeVO> searchKnowledge(Long skillId, String keyword) {
		List<BusinessKnowledge> businessKnowledges = businessKnowledgeMapper.searchInSkill(skillId, keyword);
		if (CollectionUtils.isEmpty(businessKnowledges)) {
			return Collections.emptyList();
		}
		return businessKnowledges.stream().map(businessKnowledgeConverter::toVo).toList();
	}

	@Override
	public BusinessKnowledgeVO getKnowledgeById(Long id) {
		BusinessKnowledge businessKnowledge = businessKnowledgeMapper.selectById(id);
		if (businessKnowledge == null) {
			return null;
		}
		return businessKnowledgeConverter.toVo(businessKnowledge);
	}

	@Override
	public BusinessKnowledgeVO requireKnowledgeById(Long id) {
		BusinessKnowledgeVO knowledge = getKnowledgeById(id);
		if (knowledge == null) {
			throw CheckedException.notFound("Business knowledge does not exist");
		}
		return knowledge;
	}

	@Override
	public BusinessKnowledge requireBySkillId(Long skillId, Long id) {
		if (skillId == null || id == null) {
			throw CheckedException.badRequest("skillId and knowledgeId cannot be null");
		}
		BusinessKnowledge knowledge = businessKnowledgeMapper.selectById(id);
		if (knowledge == null || !Objects.equals(skillId, knowledge.getSkillId())) {
			throw CheckedException.notFound("Business knowledge does not exist");
		}
		return knowledge;
	}

	@Override
	public List<BusinessKnowledge> getRecalledKnowledgeBySkillId(Long skillId) {
		if (skillId == null) {
			return List.of();
		}
		return businessKnowledgeMapper.selectBySkillId(skillId).stream()
			.filter(knowledge -> Boolean.TRUE.equals(knowledge.getIsRecall()))
			.toList();
	}

	@Override
	public List<Long> getRecalledKnowledgeIds(Long skillId) {
		if (skillId == null) {
			return List.of();
		}
		return businessKnowledgeMapper.selectRecalledKnowledgeIds(skillId);
	}

	/**
	 * 新增业务知识。
	 *
	 * <p>刻意不加 {@code @Transactional}：向量库写入是远程调用，放在事务里既把事务拉长到一次远端往返，又会在
	 * 后续任何一步失败回滚时留下「库里没有记录、向量库却有文档」的孤儿向量。改为先库后向量——短事务落库并提交，
	 * 事务外写向量，再用一个短事务回写向量化状态。
	 *
	 * <p><b>非事务补偿点</b>：向量写入失败不回滚已提交的记录，只把状态标成 {@link EmbeddingStatus#FAILED} 并记
	 * 错误原因，由既有重建路径 {@link #retryEmbedding(Long)} / {@link #refreshAllKnowledgeToVectorStore(Long)} 补齐。
	 */
	@Override
	public BusinessKnowledgeVO addKnowledge(CreateBusinessKnowledgeDTO knowledgeDTO) {
		BusinessKnowledge entity = businessKnowledgeConverter.toEntityForCreate(knowledgeDTO);

		// 插入数据库
		transactionTemplate.executeWithoutResult(status -> {
			if (businessKnowledgeMapper.insert(entity) <= 0) {
				throw CheckedException.fail("业务知识新增失败, skillId=" + entity.getSkillId());
			}
		});

		try {
			Document document = DocumentConverterUtil.convertBusinessKnowledgeToDocument(entity);
			agentVectorStoreService.addSkillDocuments(entity.getSkillId().toString(), List.of(document));
			entity.setEmbeddingStatus(EmbeddingStatus.COMPLETED);
			entity.setErrorMsg(null);
		}
		catch (Exception e) {
			String errorMsg = "Failed to add to vector store: " + e.getMessage();
			entity.setEmbeddingStatus(EmbeddingStatus.FAILED);
			entity.setErrorMsg(errorMsg);
			log.error("Failed to add knowledge to vector store for id: {}, error: {}", entity.getId(), errorMsg);
		}
		transactionTemplate.executeWithoutResult(status -> businessKnowledgeMapper.updateById(entity));
		return businessKnowledgeConverter.toVo(entity);
	}

	/**
	 * 修改业务知识。事务边界与补偿方式同 {@link #addKnowledge(CreateBusinessKnowledgeDTO)}。
	 */
	@Override
	public BusinessKnowledgeVO updateKnowledge(Long id, UpdateBusinessKnowledgeDTO knowledgeDTO) {
		// 从数据库获取原始数据
		BusinessKnowledge knowledge = businessKnowledgeMapper.selectById(id);
		if (knowledge == null) {
			throw CheckedException.notFound("业务知识不存在, id=" + id);
		}
		if (!Objects.equals(knowledge.getSkillId(), knowledgeDTO.getSkillId())) {
			throw CheckedException.notFound("Business knowledge does not exist");
		}
		if (publishedResourceReferenceService.isBusinessKnowledgeReferenced(knowledge.getSkillId(), knowledge.getId())) {
			return copyKnowledgeForPublishedVersion(knowledge, knowledgeDTO);
		}
		// 更新属性
		knowledge.setBusinessTerm(knowledgeDTO.getBusinessTerm());
		knowledge.setDescription(knowledgeDTO.getDescription());
		if (StringUtils.hasText(knowledgeDTO.getSynonyms()))
			knowledge.setSynonyms(knowledgeDTO.getSynonyms());

		// 设置初始状态为处理中
		knowledge.setEmbeddingStatus(EmbeddingStatus.PROCESSING);

		// 先更新数据库
		transactionTemplate.executeWithoutResult(status -> {
			if (businessKnowledgeMapper.updateById(knowledge) <= 0) {
				throw CheckedException.fail("业务知识更新失败, id=" + knowledge.getId());
			}
		});

		// 尝试更新向量库
		try {
			syncToVectorStore(knowledge);
			knowledge.setEmbeddingStatus(EmbeddingStatus.COMPLETED);
			knowledge.setErrorMsg(null);
		}
		catch (Exception e) {
			// 向量库更新失败，不回滚已提交的记录，只标记状态为失败，交给重建路径补偿
			String errorMsg = "Failed to update vector store: " + e.getMessage();
			knowledge.setEmbeddingStatus(EmbeddingStatus.FAILED);
			knowledge.setErrorMsg(errorMsg);
			log.error("Failed to update vector store for knowledge id: {}, error: {}", id, errorMsg);
		}
		transactionTemplate.executeWithoutResult(status -> businessKnowledgeMapper.updateById(knowledge));
		return businessKnowledgeConverter.toVo(knowledge);
	}

	/**
	 * 更新向量库中的知识向量
	 */
	private BusinessKnowledgeVO copyKnowledgeForPublishedVersion(BusinessKnowledge source,
			UpdateBusinessKnowledgeDTO update) {
		BusinessKnowledge replacement = BusinessKnowledge.builder()
			.businessTerm(update.getBusinessTerm())
			.description(update.getDescription())
			.synonyms(StringUtils.hasText(update.getSynonyms()) ? update.getSynonyms() : source.getSynonyms())
			.isRecall(source.getIsRecall())
			.skillId(source.getSkillId())
			.supersededById(source.getId())
			.embeddingStatus(EmbeddingStatus.PROCESSING)
			.build();
		transactionTemplate.executeWithoutResult(status -> {
			if (businessKnowledgeMapper.insert(replacement) <= 0) {
				throw CheckedException.fail("Failed to copy business knowledge");
			}
		});
		try {
			agentVectorStoreService.addSkillDocuments(replacement.getSkillId().toString(),
					List.of(DocumentConverterUtil.convertBusinessKnowledgeToDocument(replacement)));
		}
		catch (Exception ex) {
			// 非事务补偿点：副本已落库，向量失败只标状态，重建路径会重新灌入
			replacement.setEmbeddingStatus(EmbeddingStatus.FAILED);
			replacement.setErrorMsg("Failed to add to vector store: " + ex.getMessage());
			transactionTemplate.executeWithoutResult(status -> businessKnowledgeMapper.updateById(replacement));
			log.error("Failed to copy business knowledge to vector store. sourceId={}, replacementId={}", source.getId(),
				replacement.getId(), ex);
			return businessKnowledgeConverter.toVo(replacement);
		}
		replacement.setEmbeddingStatus(EmbeddingStatus.COMPLETED);
		replacement.setErrorMsg(null);
		// 副本状态与新旧记录的 supersededById 指向必须一起生效，放同一个短事务
		transactionTemplate.executeWithoutResult(status -> {
			businessKnowledgeMapper.updateById(replacement);
			source.setSupersededById(replacement.getId());
			businessKnowledgeMapper.updateById(source);
			replacement.setSupersededById(null);
			businessKnowledgeMapper.updateById(replacement);
		});
		return businessKnowledgeConverter.toVo(replacement);
	}

	private void syncToVectorStore(BusinessKnowledge knowledge) {
		// 先删除旧的向量数据
		this.doDelVector(knowledge);

		// 添加新的向量数据
		Document newDocument = DocumentConverterUtil.convertBusinessKnowledgeToDocument(knowledge);
		agentVectorStoreService.addSkillDocuments(knowledge.getSkillId().toString(), List.of(newDocument));

		log.info("Successfully updated vector store for knowledge id: {}", knowledge.getId());
	}

	/**
	 * 删除业务知识。
	 *
	 * <p>同样改成先库后向量：旧实现先删向量再删库且整段在一个事务里，一旦逻辑删除抛异常，向量已经删掉而记录还在，
	 * 该条知识就变成「库里有、检索不到」，且这种情况原来没有任何补偿（补偿只覆盖影响行数为 0 的分支）。
	 *
	 * <p><b>非事务补偿点</b>：逻辑删除提交后向量清理失败，会残留孤儿向量，需要对该 Skill 走
	 * {@link #refreshAllKnowledgeToVectorStore(Long)} 重建（它会先按向量类型整体清空再灌入存活记录）。
	 * 这里显式抛错上报，不静默吞。
	 */
	@Override
	public void deleteKnowledge(Long id) {
		// 从数据库获取原始数据
		BusinessKnowledge knowledge = businessKnowledgeMapper.selectById(id);
		if (knowledge == null) {
			throw CheckedException.notFound("Business knowledge does not exist");
		}
		if (publishedResourceReferenceService.isBusinessKnowledgeReferenced(knowledge.getSkillId(), knowledge.getId())) {
			throw CheckedException.badRequest("Published Skill business knowledge cannot be deleted");
		}

		transactionTemplate.executeWithoutResult(status -> {
			if (businessKnowledgeMapper.logicalDelete(id, true) <= 0) {
				throw CheckedException.fail("业务知识逻辑删除失败, id=" + id);
			}
		});

		try {
			doDelVector(knowledge);
		}
		catch (Exception ex) {
			log.error("业务知识已逻辑删除但向量清理失败, 需重建该 Skill 知识库. knowledgeId={}, skillId={}", knowledge.getId(),
					knowledge.getSkillId(), ex);
			throw CheckedException.fail("业务知识已删除，但向量清理失败，请对该 Skill 重建知识库");
		}
	}

	private void doDelVector(BusinessKnowledge knowledge) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(DocumentMetadataConstant.DB_BUSINESS_TERM_ID, knowledge.getId().toString());
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.BUSINESS_TERM);
		agentVectorStoreService.deleteSkillDocumentsByMetadata(knowledge.getSkillId().toString(), metadata);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void recallKnowledge(Long id, Boolean isRecall) {
		// 从数据库获取原始数据
		BusinessKnowledge knowledge = businessKnowledgeMapper.selectById(id);
		if (knowledge == null) {
			throw CheckedException.notFound("业务知识不存在, id=" + id);
		}
		if (publishedResourceReferenceService.isBusinessKnowledgeReferenced(knowledge.getSkillId(), knowledge.getId())) {
			throw CheckedException.badRequest("Published Skill business knowledge recall state cannot be changed");
		}

		// 更新数据库即可，不需要更新向量库，混合检索的的时候DynamicFilterService会根据 isRecall 字段过滤了
		knowledge.setIsRecall(Boolean.TRUE.equals(isRecall));
		businessKnowledgeMapper.updateById(knowledge);

	}

	@Override
	public void refreshAllKnowledgeToVectorStore(String skillId) throws Exception {
		Long resolvedSkillId = Long.valueOf(skillId);
		// 先取存活集合再清空向量：顺序反过来会平白拉长「向量已清空但还没灌回」的空窗
		Map<Long, BusinessKnowledge> knowledgeById = collectRebuildableKnowledge(resolvedSkillId);
		reportVectorInconsistency(skillId, knowledgeById.keySet());

		agentVectorStoreService.deleteSkillDocumentsByVectorType(skillId, DocumentMetadataConstant.BUSINESS_TERM);

		// 转换为 Document 并插入到 vectorStore
		if (!knowledgeById.isEmpty()) {
			List<Document> documents = knowledgeById.values().stream()
				.map(DocumentConverterUtil::convertBusinessKnowledgeToDocument)
				.toList();
			agentVectorStoreService.addSkillDocuments(skillId, documents);
		}
	}

	/**
	 * 应当存在于向量库中的业务知识：本 Skill 下未删除且已召回的记录，外加已发布版本仍在引用的记录
	 * （后者可能已被取消召回或被新副本取代，但发布快照仍指向它，不能漏灌）。
	 */
	private Map<Long, BusinessKnowledge> collectRebuildableKnowledge(Long skillId) {
		Set<Long> publishedKnowledgeIds = publishedResourceReferenceService.referencedBusinessKnowledgeIds(skillId);
		Map<Long, BusinessKnowledge> knowledgeById = new java.util.LinkedHashMap<>();
		businessKnowledgeMapper.selectBySkillId(skillId).stream()
			.filter(knowledge -> knowledge != null && !Boolean.TRUE.equals(knowledge.getDeleted()))
			.filter(knowledge -> Boolean.TRUE.equals(knowledge.getIsRecall()))
			.forEach(knowledge -> knowledgeById.put(knowledge.getId(), knowledge));
		if (!publishedKnowledgeIds.isEmpty()) {
			businessKnowledgeMapper.selectByIds(publishedKnowledgeIds).stream()
				.filter(knowledge -> knowledge != null && !Boolean.TRUE.equals(knowledge.getDeleted()))
				.filter(knowledge -> Objects.equals(skillId, knowledge.getSkillId()))
				.forEach(knowledge -> knowledgeById.put(knowledge.getId(), knowledge));
		}
		return knowledgeById;
	}

	/**
	 * 重建前对账，把库与向量库的两向不一致写进日志：孤儿向量（向量在、行没了）与漏灌（行在、向量没有）。
	 *
	 * <p>重建本身会把两者都抹平，但抹平之后就再也看不出曾经偏了多少。先记一笔才能事后判断这条 Skill
	 * 是不是在反复出问题。
	 *
	 * <p>对账只是诊断，读向量库失败不应连带让修复入口失败，因此这里带异常记 WARN 后继续重建。
	 */
	private void reportVectorInconsistency(String skillId, Set<Long> liveKnowledgeIds) {
		Set<String> stored;
		try {
			stored = agentVectorStoreService.findSkillDocumentResourceIds(skillId,
					DocumentMetadataConstant.BUSINESS_TERM);
		}
		catch (Exception ex) {
			log.warn("Failed to reconcile business knowledge vectors before rebuild. skillId={}", skillId, ex);
			return;
		}
		Set<String> expected = new java.util.LinkedHashSet<>();
		liveKnowledgeIds.forEach(id -> expected.add(String.valueOf(id)));
		Set<String> orphanVectorIds = new java.util.LinkedHashSet<>(stored);
		orphanVectorIds.removeAll(expected);
		Set<String> rowsWithoutVectors = new java.util.LinkedHashSet<>(expected);
		rowsWithoutVectors.removeAll(stored);
		if (orphanVectorIds.isEmpty() && rowsWithoutVectors.isEmpty()) {
			log.info("Business knowledge vectors were already consistent before rebuild. skillId={}, count={}", skillId,
					expected.size());
			return;
		}
		log.warn("Business knowledge vectors diverged from the database, rebuilding now. skillId={}, "
				+ "orphanVectorIds={}, rowsWithoutVectors={}", skillId, orphanVectorIds, rowsWithoutVectors);
	}

	@Override
	public void refreshAllKnowledgeToVectorStore(Long skillId) {
		if (skillId == null) {
			throw CheckedException.badRequest("skillId cannot be null");
		}
		try {
			refreshAllKnowledgeToVectorStore(skillId.toString());
		}
		catch (Exception e) {
			log.error("Failed to refresh vector store for skillId: {}", skillId, e);
			throw CheckedException.fail("Failed to refresh vector store");
		}
	}

	@Override
	public void retryEmbedding(Long id) {
		BusinessKnowledge knowledge = businessKnowledgeMapper.selectById(id);
		if (knowledge == null) {
			throw CheckedException.notFound("业务知识不存在, id=" + id);
		}
		if (publishedResourceReferenceService.isBusinessKnowledgeReferenced(knowledge.getSkillId(), knowledge.getId())) {
			throw CheckedException.badRequest("Published Skill business knowledge cannot be re-vectorized in place");
		}

		if (knowledge.getEmbeddingStatus().equals(EmbeddingStatus.PROCESSING)) {
			throw CheckedException.badRequest("业务知识正在向量化处理中，请稍后重试, id=" + id);
		}

		// 非召回的不处理
		if (!Boolean.TRUE.equals(knowledge.getIsRecall())) {
			throw CheckedException.badRequest("业务知识未召回，请先召回后再重试向量化, id=" + id);
		}

		try {
			syncToVectorStore(knowledge);
			knowledge.setEmbeddingStatus(EmbeddingStatus.COMPLETED);
			knowledge.setErrorMsg(null);
			businessKnowledgeMapper.updateById(knowledge);
		}
		catch (Exception e) {
			// 先落日志保住原始堆栈：下面的 errorMsg 截断与静态工厂都不携带 cause
			log.error("Retry embedding failed. id={}, skillId={}", id, knowledge.getSkillId(), e);
			// 再次失败，更新错误信息
			knowledge.setEmbeddingStatus(EmbeddingStatus.FAILED);
			knowledge.setErrorMsg(e.getMessage().length() > 200 ? e.getMessage().substring(0, 200) : e.getMessage());
			businessKnowledgeMapper.updateById(knowledge);
			throw CheckedException.fail("重试失败, id=" + id + ": " + e.getMessage());
		}

	}

}
