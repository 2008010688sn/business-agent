/* Copyright 2024-2026 the original author or authors. */
package com.sn68.agent.dataagent.service.knowledge;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.converter.SkillKnowledgeConverter;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeCreateReq;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeQueryReq;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeUpdateReq;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.enums.KnowledgeType;
import com.sn68.agent.dataagent.event.SkillKnowledgeDeletionEvent;
import com.sn68.agent.dataagent.event.SkillKnowledgeEmbeddingEvent;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.service.file.FilePreviewResp;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.skill.PublishedSkillResourceReferenceService;
import com.sn68.agent.dataagent.service.skill.SkillResourceAccessService;
import com.sn68.agent.dataagent.vo.SkillKnowledgeVO;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/** Default tenant-safe Skill knowledge service. */
@Service
@RequiredArgsConstructor
public class SkillKnowledgeServiceImpl implements SkillKnowledgeService {

	private final SkillKnowledgeMapper mapper;

	private final SkillKnowledgeConverter converter;

	private final SkillResourceAccessService accessService;

	private final LocalFileService localFileService;

	private final ApplicationEventPublisher eventPublisher;

	private final PublishedSkillResourceReferenceService publishedResourceReferenceService;

	@Override
	public SkillKnowledgeVO get(Long skillId, Long id) {
		accessService.requireKnowledgeBaseResourceSkill(skillId);
		SkillKnowledge item = requireOwned(skillId, id);
		SkillKnowledgeVO result = enrich(converter.toVo(item), item);
		result.setPublishedReferenced(publishedResourceReferenceService.isSkillKnowledgeReferenced(skillId, id));
		return result;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillKnowledgeVO create(Long skillId, SkillKnowledgeCreateReq request) {
		accessService.requireKnowledgeBaseResourceSkill(skillId);
		validate(request);
		if (KnowledgeType.DOCUMENT.getCode().equals(request.getType())) {
			localFileService.requirePreview(request.getFilePath());
		}
		SkillKnowledge item = converter.toEntity(request, skillId);
		if (mapper.insert(item) <= 0) {
			throw CheckedException.fail("Failed to create Skill knowledge resource");
		}
		publishEmbedding(item.getId());
		return converter.toVo(item);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillKnowledgeVO update(Long skillId, Long id, SkillKnowledgeUpdateReq request) {
		accessService.requireKnowledgeBaseResourceSkill(skillId);
		SkillKnowledge item = requireOwned(skillId, id);
		requireEditable(item, "Published Skill knowledge cannot be edited in place");
		if (StringUtils.hasText(request.getTitle())) {
			item.setTitle(request.getTitle().trim());
		}
		if (StringUtils.hasText(request.getContent())) {
			item.setContent(request.getContent());
		}
		if (StringUtils.hasText(request.getQuestion())) {
			item.setQuestion(request.getQuestion());
		}
		item.setEmbeddingStatus(EmbeddingStatus.PENDING);
		item.setErrorMsg(null);
		mapper.touch(item);
		publishEmbedding(item.getId());
		return converter.toVo(item);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long skillId, Long id) {
		accessService.requireKnowledgeBaseResourceSkill(skillId);
		SkillKnowledge item = requireOwned(skillId, id);
		requireEditable(item, "Published Skill knowledge cannot be deleted");
		if (mapper.deleteById(id) > 0) {
			publishDeletion(item);
		}
	}

	@Override
	public IPage<SkillKnowledgeVO> page(Long skillId, SkillKnowledgeQueryReq request) {
		accessService.requireKnowledgeBaseResourceSkill(skillId);
		LbqWrapper<SkillKnowledge> wrapper = Wraps.<SkillKnowledge>lbQ()
			.eq(SkillKnowledge::getSkillId, skillId).isNull(SkillKnowledge::getSupersededById)
			.like(StringUtils.hasText(request.getTitle()), SkillKnowledge::getTitle, request.getTitle())
			.eq(StringUtils.hasText(request.getType()), SkillKnowledge::getType,
					StringUtils.hasText(request.getType()) ? KnowledgeType.fromCode(request.getType()) : null)
			.eq(StringUtils.hasText(request.getEmbeddingStatus()), SkillKnowledge::getEmbeddingStatus,
					StringUtils.hasText(request.getEmbeddingStatus())
							? EmbeddingStatus.fromValue(request.getEmbeddingStatus()) : null)
			.orderByDesc(SkillKnowledge::getCreateTime);
		IPage<SkillKnowledge> page = mapper.selectPage(request.buildPage(), wrapper);
		Map<String, FilePreviewResp> previews = findPreviews(page.getRecords());
		Set<Long> publishedIds = publishedResourceReferenceService.referencedSkillKnowledgeIds(skillId);
		return page.convert(item -> {
			SkillKnowledgeVO result = enrich(converter.toVo(item), item, previews);
			result.setPublishedReferenced(publishedIds.contains(item.getId()));
			return result;
		});
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillKnowledgeVO updateRecallStatus(Long skillId, Long id, Boolean recalled) {
		accessService.requireKnowledgeBaseResourceSkill(skillId);
		SkillKnowledge item = requireOwned(skillId, id);
		requireEditable(item, "Published Skill knowledge recall state cannot be changed");
		item.setIsRecall(Boolean.TRUE.equals(recalled));
		mapper.touch(item);
		return converter.toVo(item);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void retryEmbedding(Long skillId, Long id) {
		accessService.requireKnowledgeBaseResourceSkill(skillId);
		SkillKnowledge item = requireOwned(skillId, id);
		requireEditable(item, "Published Skill knowledge cannot be re-vectorized in place");
		if (item.getEmbeddingStatus() == EmbeddingStatus.PROCESSING) {
			throw CheckedException.badRequest("Embedding is still processing");
		}
		if (!Boolean.TRUE.equals(item.getIsRecall())) {
			throw CheckedException.badRequest("Recall the resource before retrying embedding");
		}
		item.setEmbeddingStatus(EmbeddingStatus.PENDING);
		item.setErrorMsg(null);
		mapper.touch(item);
		publishEmbedding(item.getId());
	}

	private SkillKnowledge requireOwned(Long skillId, Long id) {
		SkillKnowledge item = mapper.selectById(id);
		if (item == null || !java.util.Objects.equals(skillId, item.getSkillId())
				|| Boolean.TRUE.equals(item.getDeleted())) {
			throw CheckedException.notFound("Skill knowledge resource does not exist");
		}
		return item;
	}

	private void requireEditable(SkillKnowledge item, String message) {
		if (publishedResourceReferenceService.isSkillKnowledgeReferenced(item.getSkillId(), item.getId())) {
			throw CheckedException.badRequest(message);
		}
	}

	private void validate(SkillKnowledgeCreateReq request) {
		if (request == null || !StringUtils.hasText(request.getType())) {
			throw CheckedException.badRequest("Knowledge type is required");
		}
		KnowledgeType type = KnowledgeType.fromCode(request.getType());
		if (type == KnowledgeType.DOCUMENT && !StringUtils.hasText(request.getFilePath())) {
			throw CheckedException.badRequest("Document filePath is required");
		}
		if ((type == KnowledgeType.QA || type == KnowledgeType.FAQ)
				&& (!StringUtils.hasText(request.getQuestion()) || !StringUtils.hasText(request.getContent()))) {
			throw CheckedException.badRequest("Question and content are required for QA/FAQ");
		}
	}

	private SkillKnowledgeVO enrich(SkillKnowledgeVO vo, SkillKnowledge item) {
		return enrich(vo, item, findPreviews(List.of(item)));
	}

	private SkillKnowledgeVO enrich(SkillKnowledgeVO vo, SkillKnowledge item, Map<String, FilePreviewResp> previews) {
		if (vo == null || item == null || !StringUtils.hasText(item.getFilePath())) {
			return vo;
		}
		FilePreviewResp preview = previews.get(item.getFilePath().trim());
		if (preview != null) {
			vo.setFilePreviewUrl(preview.getPreviewUrl());
		}
		return vo;
	}

	private Map<String, FilePreviewResp> findPreviews(List<SkillKnowledge> items) {
		Set<String> paths = new LinkedHashSet<>();
		for (SkillKnowledge item : items == null ? List.<SkillKnowledge>of() : items) {
			if (item != null && KnowledgeType.DOCUMENT.equals(item.getType()) && StringUtils.hasText(item.getFilePath())) {
				paths.add(item.getFilePath().trim());
			}
		}
		return paths.isEmpty() ? Map.of() : localFileService.findDisplayPreviews(paths);
	}

	private void publishEmbedding(Long id) {
		SkillKnowledgeEmbeddingEvent event = new SkillKnowledgeEmbeddingEvent(this, id,
				DataAgentOutboundContext.captureFromRequest());
		publishAfterCommit(event);
	}

	private void publishDeletion(SkillKnowledge item) {
		publishAfterCommit(new SkillKnowledgeDeletionEvent(this, item, DataAgentOutboundContext.captureFromRequest()));
	}

	private void publishAfterCommit(Object event) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			eventPublisher.publishEvent(event);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				eventPublisher.publishEvent(event);
			}
		});
	}

}
