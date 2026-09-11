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
package com.sn68.agent.dataagent.multimodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.chat.ChatAttachmentDTO;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.audio.AudioTranscriptionService;
import com.sn68.agent.dataagent.service.chat.ChatMessageService;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话附件的两阶段融合：先记指针，澄清后再下载抽取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnFusionService {

	public static final String TYPE_IMAGE = "image";

	public static final String TYPE_PDF = "pdf";

	public static final String TYPE_DOCX = "docx";

	public static final String TYPE_XLSX = "xlsx";

	public static final String TYPE_CSV = "csv";

	public static final String TYPE_TXT = "txt";

	public static final String TYPE_MD = "md";

	public static final String TYPE_LOG = "log";

	public static final String TYPE_AUDIO = "audio";

	public static final String CITATION_PROMPT = """
			附件使用规则：
			- 引用附件中的数字必须给出文件名，以及页码、表名或图号。
			- 从图上读出的金额/件数必须与表格或 SQL 交叉校验；对不上标低置信，禁止只信看图。
			- 截断的 SQL 结果禁止做合计、平均或同比。
			""";

	private static final Pattern ATTACHMENT_REF = Pattern
		.compile("这张|这份|附件|截图|图片|照片|pdf|PDF|对账单|签收|语音|录音|日志");

	private static final Pattern DEFER_ATTACHMENT_REF = Pattern
		.compile("这张|这份|这个|附件|截图|图片|照片|pdf|PDF|对账单|签收|语音|录音|日志|运单");

	private static final Set<String> DOCUMENT_TYPES = Set.of(TYPE_PDF, TYPE_DOCX, TYPE_XLSX, TYPE_CSV, TYPE_TXT,
			TYPE_MD, TYPE_LOG, TYPE_AUDIO);

	private static final int LOG_KEEP_ALL_LINES = 200;

	private static final int LOG_HEAD_LINES = 80;

	private static final int LOG_TAIL_LINES = 40;

	private static final int LOG_MAX_CHARS = 16_000;

	private final MultiModalFuser multiModalFuser;

	private final LocalFileService localFileService;

	private final DataAgentProperties dataAgentProperties;

	private final PdfTurnExtractor pdfTurnExtractor;

	private final ImageNormalize imageNormalize;

	private final ChatMessageService chatMessageService;

	private final PdfPageRenderer pdfPageRenderer;

	private final AudioTranscriptionService audioTranscriptionService;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public boolean hasAttachments(AgentRequest request) {
		return request != null && request.getAttachments() != null
				&& request.getAttachments().stream().anyMatch(item -> item != null && StringUtils.hasText(item.getStorageKey()));
	}

	public static boolean refersToAttachment(String query) {
		if (!StringUtils.hasText(query)) {
			return false;
		}
		return ATTACHMENT_REF.matcher(stripSelfProducedHeaders(query)).find();
	}

	public boolean skipAttachmentSlotClarify(AgentRequest request) {
		if (!refersToAttachment(request == null ? null : request.getQuery())) {
			return false;
		}
		return hasAttachments(request) || findLatestSnapshot(request) != null;
	}

	public TurnArtifact fuseOrReuse(AgentRequest request) {
		if (request == null || !multimodal().isEnabled()) {
			return request == null ? null : request.getTurnArtifact();
		}
		if (hasAttachments(request)) {
			TurnArtifact artifact = fuseInto(request);
			persistSnapshot(request, artifact);
			return artifact;
		}
		if (request.isCollaboratorChild() && parseSessionId(request) == null) {
			Long parentSessionId = parseNumericId(request.getParentThreadId());
			if (parentSessionId != null) {
				TurnFusionSnapshot snapshot = findLatestSnapshot(parentSessionId);
				if (snapshot == null || !snapshot.hasContent()) {
					return request.getTurnArtifact();
				}
				return applyPointerSnapshot(request, snapshot);
			}
		}
		if (!refersToAttachment(request.getQuery())) {
			return request.getTurnArtifact();
		}
		TurnFusionSnapshot snapshot = findLatestSnapshot(request);
		if (snapshot == null || !snapshot.hasContent()) {
			return request.getTurnArtifact();
		}
		return applySnapshot(request, snapshot);
	}

	public List<ChatAttachmentDTO> validatePointers(List<ChatAttachmentDTO> requestAttachments) {
		if (requestAttachments == null || requestAttachments.isEmpty()) {
			return List.of();
		}
		DataAgentProperties.ChatAttachment imageProps = chatAttachment();
		DataAgentProperties.Multimodal multimodal = multimodal();
		int imageCount = 0;
		int documentCount = 0;
		long declaredTotal = 0L;
		List<ChatAttachmentDTO> attachments = new ArrayList<>();
		for (ChatAttachmentDTO attachment : requestAttachments) {
			String type = normalizeType(attachment);
			if (TYPE_IMAGE.equals(type)) {
				imageCount++;
				if (imageCount > imageProps.getMaxImageCount()) {
					throw CheckedException.badRequest("每轮最多支持 " + imageProps.getMaxImageCount() + " 张图片");
				}
				if (attachment.getSize() != null && attachment.getSize() > imageProps.getMaxImageSize()) {
					throw CheckedException.badRequest("图片大小超过限制");
				}
			}
			else if (DOCUMENT_TYPES.contains(type)) {
				documentCount++;
				if (documentCount > multimodal.getMaxDocumentCount()) {
					throw CheckedException.badRequest("每轮最多支持 " + multimodal.getMaxDocumentCount() + " 个文档");
				}
				if (attachment.getSize() != null && attachment.getSize() > multimodal.getMaxDocumentSize()) {
					throw CheckedException.badRequest("文档大小超过限制");
				}
			}
			else {
				throw CheckedException.badRequest("不支持的附件类型：" + (attachment == null ? "null" : attachment.getType()));
			}
			if (!StringUtils.hasText(attachment.getStorageKey())) {
				throw CheckedException.badRequest("附件 storageKey 不能为空");
			}
			if (attachment.getSize() != null) {
				declaredTotal += attachment.getSize();
			}
			if (declaredTotal > imageProps.getMaxTotalSize()) {
				throw CheckedException.badRequest("附件总大小超过限制");
			}
			attachments.add(ChatAttachmentDTO.builder()
				.type(type)
				.storageKey(attachment.getStorageKey().trim())
				.contentType(attachment.getContentType())
				.fileName(attachment.getFileName())
				.size(attachment.getSize())
				.build());
		}
		return attachments;
	}

	public String identifySummary(List<ChatAttachmentDTO> attachments) {
		if (attachments == null || attachments.isEmpty()) {
			return "";
		}
		int images = 0;
		int documents = 0;
		List<String> names = new ArrayList<>();
		for (ChatAttachmentDTO attachment : attachments) {
			if (attachment == null) {
				continue;
			}
			if (TYPE_IMAGE.equalsIgnoreCase(attachment.getType())) {
				images++;
			}
			else {
				documents++;
			}
			if (StringUtils.hasText(attachment.getFileName()) && names.size() < 3) {
				names.add(attachment.getFileName());
			}
		}
		List<String> parts = new ArrayList<>();
		if (images > 0) {
			parts.add("图片 " + images + " 张");
		}
		if (documents > 0) {
			parts.add("文档 " + documents + " 份");
		}
		if (!names.isEmpty()) {
			parts.add(String.join("、", names));
		}
		return parts.isEmpty() ? "" : "附件：" + String.join("；", parts);
	}

	public TurnArtifact fuseInto(AgentRequest request) {
		if (request == null || !hasAttachments(request) || !multimodal().isEnabled()) {
			return request == null ? null : request.getTurnArtifact();
		}
		if (request.getTurnArtifact() != null) {
			return request.getTurnArtifact();
		}
		List<ModalityInput> inputs = new ArrayList<>();
		if (StringUtils.hasText(request.getQuery())) {
			inputs.add(ModalityInput.text(request.getQuery(), "用户问句"));
		}
		int modelImages = 0;
		DataAgentProperties.ChatAttachment imageProps = chatAttachment();
		List<ChatAttachmentDTO> refreshed = new ArrayList<>();
		for (ChatAttachmentDTO attachment : request.getAttachments()) {
			String type = normalizeType(attachment);
			if (TYPE_IMAGE.equals(type)) {
				if (modelImages >= imageProps.getMaxModelImageCount()) {
					throw CheckedException.badRequest(
							"最多 " + imageProps.getMaxModelImageCount() + " 张图片进入模型，请减少附件数量");
				}
				refreshed.add(fuseImage(attachment, inputs, imageProps));
				modelImages++;
			}
			else {
				refreshed.add(fuseDocument(attachment, inputs));
			}
		}
		request.setAttachments(refreshed);
		if (!StringUtils.hasText(request.getOriginalUserQuery())) {
			request.setOriginalUserQuery(request.getQuery());
		}
		FusionResult fused = multiModalFuser.fuse(inputs);
		request.setTurnArtifact(fused.artifact());
		request.setQuery(fused.artifact().augmentQuery(request.getQuery()));
		return fused.artifact();
	}

	TurnArtifact applySnapshot(AgentRequest request, TurnFusionSnapshot snapshot) {
		List<ModalityInput> inputs = new ArrayList<>();
		if (StringUtils.hasText(request.getQuery())) {
			inputs.add(ModalityInput.text(request.getQuery(), "用户问句"));
		}
		for (TurnFusionSnapshot.SnapshotText text : snapshot.texts()) {
			if (text == null || !StringUtils.hasText(text.text())) {
				continue;
			}
			inputs.add(new ModalityInput(ModalityType.TEXT, text.text(), firstText(text.hint(), "附件"), false,
					text.sourceRef(), "text/plain", text.hint(), 0, 0));
		}
		DataAgentProperties.ChatAttachment imageProps = chatAttachment();
		DataAgentProperties.Multimodal multimodal = multimodal();
		int modelImages = 0;
		Set<String> renderedPdfKeys = new HashSet<>();
		for (TurnFusionSnapshot.SnapshotImage image : snapshot.images()) {
			if (image == null || !StringUtils.hasText(image.storageKey())) {
				continue;
			}
			if (modelImages >= imageProps.getMaxModelImageCount()) {
				break;
			}
			if (isRenderedPdfPage(image)) {
				if (!renderedPdfKeys.add(image.storageKey())) {
					continue;
				}
				LocalFileService.DownloadedFile file = localFileService.downloadDocument(image.storageKey(),
						multimodal.getMaxDocumentSize(), multimodal.getAllowedDocumentContentTypes(),
						imageProps.getDownloadTimeoutMs());
				int before = inputs.size();
				addScannedPdfPages(ChatAttachmentDTO.builder()
					.type(TYPE_PDF)
					.storageKey(image.storageKey())
					.fileName(image.fileName())
					.build(), firstText(image.fileName(), "document"), file.bytes(), multimodal, inputs);
				modelImages += Math.max(0, inputs.size() - before);
				continue;
			}
			fuseImage(ChatAttachmentDTO.builder()
				.type(TYPE_IMAGE)
				.storageKey(image.storageKey())
				.fileName(image.fileName())
				.contentType(image.contentType())
				.build(), inputs, imageProps);
			modelImages++;
		}
		if (!StringUtils.hasText(request.getOriginalUserQuery())) {
			request.setOriginalUserQuery(request.getQuery());
		}
		FusionResult fused = multiModalFuser.fuse(inputs);
		request.setTurnArtifact(fused.artifact());
		request.setQuery(fused.artifact().augmentQuery(request.getQuery()));
		return fused.artifact();
	}

	TurnFusionSnapshot findLatestSnapshot(AgentRequest request) {
		return findLatestSnapshot(parseSessionId(request));
	}

	TurnFusionSnapshot findLatestSnapshot(Long sessionId) {
		if (sessionId == null || chatMessageService == null) {
			return null;
		}
		List<DataChatMessage> messages = chatMessageService.findBySessionId(sessionId);
		if (messages == null || messages.isEmpty()) {
			return null;
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			TurnFusionSnapshot snapshot = readSnapshot(messages.get(i));
			if (snapshot != null && snapshot.hasContent()) {
				return snapshot;
			}
		}
		return null;
	}

	public void persistSnapshot(AgentRequest request, TurnArtifact artifact) {
		TurnFusionSnapshot snapshot = TurnFusionSnapshot.from(artifact);
		if (!snapshot.hasContent()) {
			return;
		}
		Long sessionId = parseSessionId(request);
		if (sessionId == null || chatMessageService == null) {
			return;
		}
		List<DataChatMessage> messages = chatMessageService.findBySessionId(sessionId);
		if (messages == null || messages.isEmpty()) {
			return;
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			DataChatMessage message = messages.get(i);
			if (message == null || !"user".equalsIgnoreCase(message.getRole())) {
				continue;
			}
			try {
				ObjectNode metadata = parseMetadataObject(message.getMetadata());
				metadata.set(TurnFusionSnapshot.METADATA_KEY, objectMapper.valueToTree(snapshot));
				message.setMetadata(objectMapper.writeValueAsString(metadata));
				chatMessageService.updateById(message);
			}
			catch (Exception ex) {
				log.warn("保存附件抽取快照失败：{}", ex.getMessage(), ex);
			}
			return;
		}
	}

	private TurnFusionSnapshot readSnapshot(DataChatMessage message) {
		if (message == null || !StringUtils.hasText(message.getMetadata())) {
			return null;
		}
		try {
			JsonNode root = objectMapper.readTree(message.getMetadata());
			JsonNode extract = root.path(TurnFusionSnapshot.METADATA_KEY);
			if (extract.isMissingNode() || extract.isNull()) {
				return null;
			}
			return objectMapper.treeToValue(extract, TurnFusionSnapshot.class);
		}
		catch (Exception ex) {
			return null;
		}
	}

	private ObjectNode parseMetadataObject(String metadata) throws Exception {
		if (!StringUtils.hasText(metadata)) {
			return objectMapper.createObjectNode();
		}
		JsonNode node = objectMapper.readTree(metadata);
		if (node != null && node.isObject()) {
			return (ObjectNode) node;
		}
		return objectMapper.createObjectNode();
	}

	private static Long parseSessionId(AgentRequest request) {
		return request == null ? null : parseNumericId(request.getThreadId());
	}

	private static Long parseNumericId(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	public List<ContentBlock> buildUserContentBlocks(AgentRequest request) {
		TurnArtifact artifact = request == null ? null : request.getTurnArtifact();
		if (artifact == null || artifact.blocks() == null) {
			return List.of();
		}
		List<ContentBlock> blocks = new ArrayList<>();
		for (FusionBlock block : artifact.blocks()) {
			if (block == null || !block.keepAsImage() || !(block.payload() instanceof byte[] bytes) || bytes.length == 0
					|| !StringUtils.hasText(block.mediaType())) {
				continue;
			}
			blocks.add(ImageBlock.builder()
				.source(Base64Source.builder()
					.mediaType(block.mediaType())
					.data(Base64.getEncoder().encodeToString(bytes))
					.build())
				.build());
		}
		return blocks;
	}

	public String citationPrompt(AgentRequest request) {
		if (request == null || request.getTurnArtifact() == null || request.getTurnArtifact().blocks().isEmpty()) {
			return "";
		}
		return CITATION_PROMPT;
	}

	public boolean shouldDeferSkillForUnreadImage(AgentRequest request, SkillExecutionMode mode) {
		if (request == null || request.getTurnArtifact() == null) {
			return false;
		}
		if (mode != SkillExecutionMode.FLOW && mode != SkillExecutionMode.DETERMINISTIC
				&& mode != SkillExecutionMode.KNOWLEDGE) {
			return false;
		}
		if (!hasImageBlock(request.getTurnArtifact())) {
			return false;
		}
		if (!originalRefersToAttachment(request.getOriginalUserQuery())) {
			return false;
		}
		ExtractCard card = resolveExtractCard(request);
		return card == null || card.hasUnreadReason() || card.needsVisionFollowup()
				|| !card.hasHighConfidenceField();
	}

	public TurnArtifact restorePixels(AgentRequest request, ModelConfigDTO modelConfig) {
		if (request == null) {
			return null;
		}
		TurnArtifact artifact = request.getTurnArtifact();
		if (artifact == null || !needsPixelRestore(request, artifact)) {
			return artifact;
		}
		if (modelConfig == null || !Boolean.TRUE.equals(modelConfig.getSupportVision())) {
			return artifact;
		}
		List<FusionBlock> cloned = new ArrayList<>();
		DataAgentProperties.ChatAttachment imageProps = chatAttachment();
		for (FusionBlock block : artifact.blocks()) {
			if (block != null && block.keepAsImage() && !block.hasPixelPayload()
					&& StringUtils.hasText(block.sourceRef())) {
				List<ModalityInput> inputs = new ArrayList<>();
				fuseImage(ChatAttachmentDTO.builder()
					.type(TYPE_IMAGE)
					.storageKey(block.sourceRef())
					.fileName(firstText(block.hint(), "图片"))
					.contentType(block.mediaType())
					.build(), inputs, imageProps);
				if (!inputs.isEmpty()) {
					ModalityInput input = inputs.get(0);
					cloned.add(FusionBlock.image(input.payload(), input.contentType(), block.hint(),
							block.sourceRef()));
					continue;
				}
			}
			cloned.add(block);
		}
		TurnArtifact restored = new TurnArtifact(artifact.artifactId(), cloned, artifact.events(),
				artifact.totalTokensEstimate(), artifact.routeSummary());
		request.setTurnArtifact(restored);
		return restored;
	}

	TurnArtifact applyPointerSnapshot(AgentRequest request, TurnFusionSnapshot snapshot) {
		List<FusionBlock> blocks = new ArrayList<>();
		String extractText = "";
		for (TurnFusionSnapshot.SnapshotText text : snapshot.texts()) {
			if (text == null || !StringUtils.hasText(text.text())) {
				continue;
			}
			String hint = text.hint();
			if (ExtractCard.isExtractHint(hint)) {
				extractText = text.text();
				blocks.add(FusionBlock.text(text.text(), hint, text.sourceRef()));
			}
			else {
				blocks.add(FusionBlock.text(text.text(), firstText(hint, "附件"), text.sourceRef()));
			}
			if (!StringUtils.hasText(request.getOriginalUserQuery()) && "用户问句".equals(hint)) {
				request.setOriginalUserQuery(text.text());
			}
		}
		for (TurnFusionSnapshot.SnapshotImage image : snapshot.images()) {
			if (image == null || !StringUtils.hasText(image.storageKey())) {
				continue;
			}
			blocks.add(FusionBlock.imagePointer(image.storageKey(), image.contentType(), image.fileName()));
		}
		TurnArtifact artifact = new TurnArtifact(snapshot.artifactId(), blocks, List.of(), 0, snapshot.routeSummary());
		request.setTurnArtifact(artifact);
		ExtractCard card = ExtractCard.fromFlags(snapshot.extractFlags(), extractText);
		if (card != null) {
			request.setExtractCard(card);
		}
		return artifact;
	}

	private boolean needsPixelRestore(AgentRequest request, TurnArtifact artifact) {
		boolean hasPointer = false;
		for (FusionBlock block : artifact.blocks()) {
			if (block != null && block.keepAsImage() && !block.hasPixelPayload()) {
				hasPointer = true;
				break;
			}
		}
		if (!hasPointer) {
			return false;
		}
		ExtractCard card = request.getExtractCard();
		if (card != null && !card.hasUnreadReason() && !card.needsVisionFollowup()
				&& card.hasHighConfidenceField()) {
			return false;
		}
		return request.isCollaboratorChild() || originalRefersToAttachment(request.getOriginalUserQuery());
	}

	private static boolean hasImageBlock(TurnArtifact artifact) {
		if (artifact == null || artifact.blocks() == null) {
			return false;
		}
		for (FusionBlock block : artifact.blocks()) {
			if (block != null && FusionBlock.KIND_IMAGE.equals(block.kind())) {
				return true;
			}
		}
		return false;
	}

	private static ExtractCard resolveExtractCard(AgentRequest request) {
		if (request.getExtractCard() != null) {
			return request.getExtractCard();
		}
		TurnArtifact artifact = request.getTurnArtifact();
		FusionBlock block = artifact == null ? null : artifact.extractCardBlock();
		if (block == null) {
			return null;
		}
		return ExtractCard.fromFlags(block.hint(), block.text());
	}

	private static boolean originalRefersToAttachment(String originalUserQuery) {
		if (!StringUtils.hasText(originalUserQuery)) {
			return false;
		}
		return DEFER_ATTACHMENT_REF.matcher(stripSelfProducedHeaders(originalUserQuery)).find();
	}

	private static String stripSelfProducedHeaders(String query) {
		String stripped = query.replace(ExtractCard.BLOCK_HEADER, " ");
		stripped = stripped.replace("「附件摘要", " ");
		stripped = stripped.replace("【附件摘要", " ");
		return stripped;
	}

	private ChatAttachmentDTO fuseImage(ChatAttachmentDTO attachment, List<ModalityInput> inputs,
			DataAgentProperties.ChatAttachment imageProps) {
		LocalFileService.DownloadedFile file = localFileService.downloadImage(attachment.getStorageKey(),
				imageProps.getMaxImageSize(), imageProps.getAllowedContentTypes(), imageProps.getDownloadTimeoutMs());
		ImageNormalize.NormalizedImage normalized = imageNormalize.normalize(file.bytes(), file.contentType(),
				multimodal().getImageMaxEdgePx());
		String hint = firstText(attachment.getFileName(), file.preview() == null ? null : file.preview().getOriginalName(),
				"图片");
		inputs.add(ModalityInput.image(normalized.bytes(), hint, attachment.getStorageKey(), normalized.contentType(),
				hint).withPixels(normalized.width(), normalized.height()));
		return ChatAttachmentDTO.builder()
			.type(TYPE_IMAGE)
			.storageKey(attachment.getStorageKey())
			.contentType(normalized.contentType())
			.fileName(hint)
			.size((long) normalized.bytes().length)
			.url(file.preview() == null ? null : file.preview().getPreviewUrl())
			.previewUrl(file.preview() == null ? null : file.preview().getPreviewUrl())
			.build();
	}

	private ChatAttachmentDTO fuseDocument(ChatAttachmentDTO attachment, List<ModalityInput> inputs) {
		DataAgentProperties.Multimodal multimodal = multimodal();
		LocalFileService.DownloadedFile file = localFileService.downloadDocument(attachment.getStorageKey(),
				multimodal.getMaxDocumentSize(), multimodal.getAllowedDocumentContentTypes(),
				chatAttachment().getDownloadTimeoutMs());
		String fileName = firstText(attachment.getFileName(),
				file.preview() == null ? null : file.preview().getOriginalName(), "document");
		String type = normalizeType(attachment);
		if (TYPE_AUDIO.equals(type)) {
			fuseAudio(attachment, fileName, file, inputs);
		}
		else if (TYPE_LOG.equals(type) || looksLikeLog(fileName)) {
			String compacted = compactLog(new String(file.bytes(), StandardCharsets.UTF_8), fileName);
			inputs.add(new ModalityInput(ModalityType.TEXT, compacted, fileName, false, attachment.getStorageKey(),
					"text/plain", fileName, 0, 0));
		}
		else if (TYPE_CSV.equals(type) || looksLikeCsv(fileName, file.contentType())) {
			String text = new String(file.bytes(), StandardCharsets.UTF_8);
			inputs.add(ModalityInput.tableMarkdown(csvToMarkdown(text), fileName, attachment.getStorageKey()));
		}
		else {
			PdfTurnExtractor.ExtractResult extracted = pdfTurnExtractor.extractText(file.bytes(), fileName,
					multimodal.getPdfMaxPagesInContext(), multimodal.getFusionTimeoutMs());
			if (TYPE_PDF.equals(type) && extracted.scanned()) {
				addScannedPdfPages(attachment, fileName, file.bytes(), multimodal, inputs);
			}
			else {
				inputs.add(new ModalityInput(ModalityType.TEXT, "【附件 " + fileName + "】\n" + extracted.text(),
						fileName, false, attachment.getStorageKey(), "text/plain", fileName, 0, 0));
			}
		}
		return ChatAttachmentDTO.builder()
			.type(type)
			.storageKey(attachment.getStorageKey())
			.contentType(file.contentType())
			.fileName(fileName)
			.size((long) file.bytes().length)
			.url(file.preview() == null ? null : file.preview().getPreviewUrl())
			.previewUrl(file.preview() == null ? null : file.preview().getPreviewUrl())
			.build();
	}

	private void addScannedPdfPages(ChatAttachmentDTO attachment, String fileName, byte[] bytes,
			DataAgentProperties.Multimodal multimodal, List<ModalityInput> inputs) {
		if (!multimodal.isOcrEnabled()) {
			throw CheckedException.badRequest("当前仅支持可选中文字的 PDF，扫描件 OCR 尚未开放");
		}
		int maxPages = Math.min(Math.max(1, multimodal.getPdfMaxPagesInContext()),
				Math.max(1, multimodal.getPdfMaxKeptCharts()));
		List<byte[]> pages = pdfPageRenderer.render(bytes, maxPages, multimodal.getImageMaxEdgePx(),
				multimodal.getFusionTimeoutMs());
		if (pages == null || pages.isEmpty()) {
			throw CheckedException.badRequest("扫描件无法渲染为图片");
		}
		int added = 0;
		for (int i = 0; i < pages.size(); i++) {
			byte[] png = pages.get(i);
			if (png == null || png.length == 0) {
				continue;
			}
			String hint = fileName + " p." + (i + 1);
			inputs.add(ModalityInput.image(png, hint, attachment.getStorageKey(), "image/png", hint));
			added++;
		}
		if (added == 0) {
			throw CheckedException.badRequest("扫描件无法渲染为图片");
		}
	}

	private String normalizeType(ChatAttachmentDTO attachment) {
		if (attachment == null) {
			throw CheckedException.badRequest("附件不能为空");
		}
		String declared = attachment.getType() == null ? "" : attachment.getType().trim().toLowerCase(Locale.ROOT);
		if (TYPE_IMAGE.equals(declared) || DOCUMENT_TYPES.contains(declared)) {
			return declared;
		}
		ModalityType decided = ModalityType.decide(attachment.getContentType(), attachment.getFileName(), false);
		if (decided == ModalityType.IMAGE) {
			return TYPE_IMAGE;
		}
		if (decided == ModalityType.PDF) {
			return TYPE_PDF;
		}
		if (decided == ModalityType.TABLE) {
			String name = defaultText(attachment.getFileName()).toLowerCase(Locale.ROOT);
			if (name.endsWith(".csv")) {
				return TYPE_CSV;
			}
			return TYPE_XLSX;
		}
		if (decided == ModalityType.AUDIO) {
			return TYPE_AUDIO;
		}
		if (decided == ModalityType.LOG) {
			return TYPE_LOG;
		}
		if (decided == ModalityType.TEXT) {
			String name = defaultText(attachment.getFileName()).toLowerCase(Locale.ROOT);
			if (name.endsWith(".md")) {
				return TYPE_MD;
			}
			return TYPE_TXT;
		}
		throw CheckedException.badRequest("不支持的附件类型，请上传图片、PDF、Word、Excel、CSV、文本、日志或音频");
	}

	private void fuseAudio(ChatAttachmentDTO attachment, String fileName, LocalFileService.DownloadedFile file,
			List<ModalityInput> inputs) {
		if (audioTranscriptionService == null) {
			throw CheckedException.badRequest("未配置语音转写模型，无法分析音频附件");
		}
		String transcript = audioTranscriptionService
			.transcribe(new InMemoryMultipartFile(fileName, file.contentType(), file.bytes()));
		if (!StringUtils.hasText(transcript)) {
			throw CheckedException.badRequest("没有识别到有效语音内容");
		}
		inputs.add(new ModalityInput(ModalityType.TEXT, "【语音 " + fileName + "】\n" + transcript.trim(), fileName, false,
				attachment.getStorageKey(), "text/plain", fileName, 0, 0));
	}

	private static String compactLog(String raw, String fileName) {
		String text = raw == null ? "" : raw.replace("\u0000", "");
		String[] lines = text.split("\\r?\\n", -1);
		int totalLines = 0;
		for (String line : lines) {
			if (StringUtils.hasText(line)) {
				totalLines++;
			}
		}
		boolean overLines = lines.length > LOG_KEEP_ALL_LINES;
		boolean overChars = text.length() > LOG_MAX_CHARS;
		if (!overLines && !overChars) {
			return "【日志 " + fileName + "】\n" + text.trim();
		}
		StringBuilder builder = new StringBuilder();
		builder.append("【日志 ").append(fileName).append("】\n");
		builder.append("原文 ").append(Math.max(totalLines, lines.length)).append(" 行，已截断；禁止对截断日志做合计。\n");
		int head = Math.min(LOG_HEAD_LINES, lines.length);
		for (int i = 0; i < head; i++) {
			builder.append(lines[i]).append('\n');
		}
		builder.append("...[已省略中间日志]...\n");
		int tailStart = Math.max(head, lines.length - LOG_TAIL_LINES);
		for (int i = tailStart; i < lines.length; i++) {
			builder.append(lines[i]).append('\n');
		}
		return builder.toString().trim();
	}

	private static boolean isRenderedPdfPage(TurnFusionSnapshot.SnapshotImage image) {
		String name = defaultText(image.fileName()).toLowerCase(Locale.ROOT);
		String key = defaultText(image.storageKey()).toLowerCase(Locale.ROOT);
		return name.contains(" p.") && (name.contains(".pdf") || key.contains(".pdf"));
	}

	private static boolean looksLikeCsv(String fileName, String contentType) {
		String name = defaultText(fileName).toLowerCase(Locale.ROOT);
		String mime = defaultText(contentType).toLowerCase(Locale.ROOT);
		return name.endsWith(".csv") || mime.contains("csv");
	}

	private static boolean looksLikeLog(String fileName) {
		return defaultText(fileName).toLowerCase(Locale.ROOT).endsWith(".log");
	}

	private static String csvToMarkdown(String csv) {
		if (!StringUtils.hasText(csv)) {
			return "";
		}
		String[] lines = csv.split("\\r?\\n");
		StringBuilder builder = new StringBuilder();
		boolean headerWritten = false;
		for (String line : lines) {
			if (!StringUtils.hasText(line)) {
				continue;
			}
			String[] cells = line.split(",", -1);
			builder.append('|').append(String.join("|", cells)).append('|').append('\n');
			if (!headerWritten) {
				builder.append('|');
				for (int i = 0; i < cells.length; i++) {
					builder.append("---|");
				}
				builder.append('\n');
				headerWritten = true;
			}
		}
		return builder.toString().trim();
	}

	private DataAgentProperties.ChatAttachment chatAttachment() {
		return dataAgentProperties.getChatAttachment() == null ? new DataAgentProperties.ChatAttachment()
				: dataAgentProperties.getChatAttachment();
	}

	private DataAgentProperties.Multimodal multimodal() {
		return dataAgentProperties.getMultimodal() == null ? new DataAgentProperties.Multimodal()
				: dataAgentProperties.getMultimodal();
	}

	private static String firstText(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private static String defaultText(String value) {
		return value == null ? "" : value;
	}

}
