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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.chat.ChatAttachmentDTO;
import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.audio.AudioTranscriptionService;
import com.sn68.agent.dataagent.service.chat.ChatMessageService;
import com.sn68.agent.dataagent.service.file.FilePreviewResp;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TurnFusionServiceTest {

	private static final byte[] PNG = Base64.getDecoder()
		.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

	private final LocalFileService localFileService = mock(LocalFileService.class);

	private final PdfTurnExtractor pdfTurnExtractor = mock(PdfTurnExtractor.class);

	private final ChatMessageService chatMessageService = mock(ChatMessageService.class);

	private final PdfPageRenderer pdfPageRenderer = mock(PdfPageRenderer.class);

	private final AudioTranscriptionService audioTranscriptionService = mock(AudioTranscriptionService.class);

	private TurnFusionService service;

	@BeforeEach
	void setUp() {
		service = new TurnFusionService(new MultiModalFuser(), localFileService, new DataAgentProperties(),
				pdfTurnExtractor, new ImageNormalize(), chatMessageService, pdfPageRenderer, audioTranscriptionService);
	}

	@Test
	void validatePointersRejectsUnknownType() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.validatePointers(List.of(ChatAttachmentDTO.builder()
					.type("video")
					.storageKey("https://bucket.oss-cn.example.com/a.mp4")
					.build())));
		assertTrue(ex.getMessage().contains("不支持"));
	}

	@Test
	void validatePointersAcceptsPdfWithoutDownloading() {
		List<ChatAttachmentDTO> saved = service.validatePointers(List.of(ChatAttachmentDTO.builder()
			.type("pdf")
			.storageKey("https://bucket.oss-cn.example.com/bill.pdf")
			.fileName("bill.pdf")
			.size(1200L)
			.data("should-not-keep")
			.build()));
		assertEquals("pdf", saved.get(0).getType());
		assertEquals(null, saved.get(0).getData());
	}

	@Test
	void fuseImageBuildsKeepAsImageBlocks() {
		when(localFileService.downloadImage(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/a.png",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/a.png")
						.originalName("sign.png")
						.previewUrl("https://preview.example.com/a.png")
						.build(),
					PNG, "image/png"));
		AgentRequest request = AgentRequest.builder()
			.query("这张签收图写了什么")
			.attachments(List.of(ChatAttachmentDTO.builder()
				.type("image")
				.storageKey("https://bucket.oss-cn.example.com/a.png")
				.fileName("sign.png")
				.build()))
			.build();

		TurnArtifact artifact = service.fuseInto(request);

		assertTrue(artifact.hasKeepAsImage());
		assertFalse(service.buildUserContentBlocks(request).isEmpty());
		assertTrue(request.getQuery().contains("请根据附件回答") || request.getQuery().contains("附件"));
		assertTrue(service.citationPrompt(request).contains("交叉校验"));
	}

	@Test
	void fusePdfUsesExtractedTextNotVision() {
		when(localFileService.downloadDocument(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/bill.pdf",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/bill.pdf")
						.originalName("bill.pdf")
						.previewUrl("https://preview.example.com/bill.pdf")
						.build(),
					"%PDF-1.4 text".getBytes(), "application/pdf"));
		when(pdfTurnExtractor.extractText(any(), anyString(), anyInt(), anyLong()))
			.thenReturn(new PdfTurnExtractor.ExtractResult("客户甲 应收 1200", false));
		AgentRequest request = AgentRequest.builder()
			.query("这份对账单合计多少")
			.attachments(List.of(ChatAttachmentDTO.builder()
				.type("pdf")
				.storageKey("https://bucket.oss-cn.example.com/bill.pdf")
				.fileName("bill.pdf")
				.build()))
			.build();

		TurnArtifact artifact = service.fuseInto(request);

		assertFalse(artifact.hasKeepAsImage());
		assertTrue(artifact.blocks().stream().anyMatch(block -> block.text() != null && block.text().contains("应收 1200")));
		assertTrue(service.buildUserContentBlocks(request).isEmpty());
		verify(pdfPageRenderer, never()).render(any(), anyInt(), anyInt(), anyLong());
	}

	@Test
	void fuseScannedPdfRendersKeepAsImagePages() {
		when(localFileService.downloadDocument(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/scan.pdf",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/scan.pdf")
						.originalName("scan.pdf")
						.previewUrl("https://preview.example.com/scan.pdf")
						.build(),
					"%PDF-1.4 scanned".getBytes(), "application/pdf"));
		when(pdfTurnExtractor.extractText(any(), anyString(), anyInt(), anyLong()))
			.thenReturn(new PdfTurnExtractor.ExtractResult("", true));
		when(pdfPageRenderer.render(any(), anyInt(), anyInt(), anyLong())).thenReturn(List.of(PNG));
		AgentRequest request = AgentRequest.builder()
			.query("这份扫描件写了什么")
			.attachments(List.of(ChatAttachmentDTO.builder()
				.type("pdf")
				.storageKey("https://bucket.oss-cn.example.com/scan.pdf")
				.fileName("scan.pdf")
				.build()))
			.build();

		TurnArtifact artifact = service.fuseInto(request);

		assertTrue(artifact.hasKeepAsImage());
		assertFalse(service.buildUserContentBlocks(request).isEmpty());
	}

	@Test
	void fuseScannedPdfThrowsWhenOcrDisabled() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getMultimodal().setOcrEnabled(false);
		service = new TurnFusionService(new MultiModalFuser(), localFileService, properties, pdfTurnExtractor,
				new ImageNormalize(), chatMessageService, pdfPageRenderer, audioTranscriptionService);
		when(localFileService.downloadDocument(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/scan.pdf",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/scan.pdf")
						.originalName("scan.pdf")
						.build(),
					"%PDF-1.4 scanned".getBytes(), "application/pdf"));
		when(pdfTurnExtractor.extractText(any(), anyString(), anyInt(), anyLong()))
			.thenReturn(new PdfTurnExtractor.ExtractResult("", true));
		AgentRequest request = AgentRequest.builder()
			.query("这份扫描件写了什么")
			.attachments(List.of(ChatAttachmentDTO.builder()
				.type("pdf")
				.storageKey("https://bucket.oss-cn.example.com/scan.pdf")
				.fileName("scan.pdf")
				.build()))
			.build();

		CheckedException ex = assertThrows(CheckedException.class, () -> service.fuseInto(request));
		assertTrue(ex.getMessage().contains("扫描件 OCR 尚未开放"));
		verify(pdfPageRenderer, never()).render(any(), anyInt(), anyInt(), anyLong());
	}

	@Test
	void defersFlowWhenImageHasNoBusinessId() {
		TurnArtifact pixelImage = new TurnArtifact("a1",
				List.of(FusionBlock.image(PNG, "image/png", "签收", "k")), List.of(), 10, "附件：图片 1 张");
		AgentRequest request = AgentRequest.builder()
			.query("这张签收图")
			.originalUserQuery("这张签收图")
			.turnArtifact(pixelImage)
			.build();
		assertTrue(service.shouldDeferSkillForUnreadImage(request, SkillExecutionMode.FLOW));

		request.setOriginalUserQuery("查运单 YD20260824001");
		assertTrue(service.shouldDeferSkillForUnreadImage(request, SkillExecutionMode.FLOW));

		ExtractCard highConf = new ExtractCard("签收单", "pod",
				List.of(new ExtractCard.VisibleField("运单号", "YD20260824001", "high")), false, "",
				ExtractCard.STATUS_OK);
		request.setOriginalUserQuery("帮我看看这个");
		request.setExtractCard(highConf);
		assertFalse(service.shouldDeferSkillForUnreadImage(request, SkillExecutionMode.FLOW));

		request.setQuery("本月应收\n" + ExtractCard.BLOCK_HEADER + "\n运单号：YD1");
		request.setOriginalUserQuery("本月应收");
		request.setExtractCard(null);
		assertFalse(service.shouldDeferSkillForUnreadImage(request, SkillExecutionMode.FLOW));
	}

	@Test
	void fuseOrReuseRestoresDocumentTextWithoutRedownload() throws Exception {
		TurnFusionSnapshot snapshot = new TurnFusionSnapshot("art-1", "附件：文档 1 份",
				List.of(new TurnFusionSnapshot.SnapshotText("bill.pdf", "https://bucket.oss-cn.example.com/bill.pdf",
						"【附件 bill.pdf】\n客户甲 应收 1200")),
				List.of());
		ObjectMapper mapper = new ObjectMapper();
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of(DataChatMessage.builder()
			.id(9L)
			.sessionId(100L)
			.role("user")
			.metadata(mapper.writeValueAsString(java.util.Map.of(TurnFusionSnapshot.METADATA_KEY, snapshot)))
			.build()));
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.query("这份对账单合计多少")
			.build();

		TurnArtifact artifact = service.fuseOrReuse(request);

		assertTrue(artifact.blocks().stream().anyMatch(block -> block.text() != null && block.text().contains("应收 1200")));
		assertTrue(request.getQuery().contains("附件"));
		verify(localFileService, never()).downloadDocument(anyString(), anyLong(), anyCollection(), anyInt());
		verify(pdfTurnExtractor, never()).extractText(any(), anyString(), anyInt(), anyLong());
	}

	@Test
	void skipClarifyWhenSessionHasPreviousAttachment() throws Exception {
		TurnFusionSnapshot snapshot = new TurnFusionSnapshot("art-1", "附件：文档 1 份",
				List.of(new TurnFusionSnapshot.SnapshotText("bill.pdf", "k", "客户甲 应收 1200")), List.of());
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of(DataChatMessage.builder()
			.sessionId(100L)
			.role("user")
			.metadata(new ObjectMapper()
				.writeValueAsString(java.util.Map.of(TurnFusionSnapshot.METADATA_KEY, snapshot)))
			.build()));

		assertTrue(service.skipAttachmentSlotClarify(
				AgentRequest.builder().threadId("100").query("这份表再对一下").build()));
		assertFalse(service.skipAttachmentSlotClarify(
				AgentRequest.builder().threadId("100").query("本月订单量多少").build()));
	}

	@Test
	void fuseOrReuseRerendersScannedPdfPagesInsteadOfImageDownload() throws Exception {
		TurnFusionSnapshot snapshot = new TurnFusionSnapshot("art-1", "附件：图片 1 张", List.of(),
				List.of(new TurnFusionSnapshot.SnapshotImage("https://bucket.oss-cn.example.com/scan.pdf",
						"scan.pdf p.1", "image/png")));
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of(DataChatMessage.builder()
			.sessionId(100L)
			.role("user")
			.metadata(new ObjectMapper()
				.writeValueAsString(java.util.Map.of(TurnFusionSnapshot.METADATA_KEY, snapshot)))
			.build()));
		when(localFileService.downloadDocument(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/scan.pdf",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/scan.pdf")
						.originalName("scan.pdf")
						.previewUrl("https://preview.example.com/scan.pdf")
						.build(),
					"%PDF-1.4 scan".getBytes(), "application/pdf"));
		when(pdfPageRenderer.render(any(), anyInt(), anyInt(), anyLong())).thenReturn(List.of(PNG));
		AgentRequest request = AgentRequest.builder().threadId("100").query("这张扫描件写了什么").build();

		TurnArtifact artifact = service.fuseOrReuse(request);

		assertTrue(artifact.hasKeepAsImage());
		verify(localFileService).downloadDocument(anyString(), anyLong(), anyCollection(), anyInt());
		verify(localFileService, never()).downloadImage(anyString(), anyLong(), anyCollection(), anyInt());
		verify(pdfPageRenderer).render(any(), anyInt(), anyInt(), anyLong());
	}

	@Test
	void fuseAudioTranscribesToText() {
		when(localFileService.downloadDocument(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/note.wav",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/note.wav")
						.originalName("note.wav")
						.build(),
					"audio-bytes".getBytes(), "audio/wav"));
		when(audioTranscriptionService.transcribe(any())).thenReturn("客户甲 应收 1200");
		AgentRequest request = AgentRequest.builder()
			.query("这段语音说了什么")
			.attachments(List.of(ChatAttachmentDTO.builder()
				.type("audio")
				.storageKey("https://bucket.oss-cn.example.com/note.wav")
				.fileName("note.wav")
				.build()))
			.build();

		TurnArtifact artifact = service.fuseInto(request);

		assertFalse(artifact.hasKeepAsImage());
		assertTrue(artifact.blocks().stream().anyMatch(block -> block.text() != null && block.text().contains("应收 1200")));
		verify(audioTranscriptionService).transcribe(any());
	}

	@Test
	void fuseLogCompactsLongText() {
		StringBuilder log = new StringBuilder();
		for (int i = 1; i <= 260; i++) {
			log.append(String.format("line-%03d", i)).append('\n');
		}
		when(localFileService.downloadDocument(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/app.log",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/app.log")
						.originalName("app.log")
						.build(),
					log.toString().getBytes(), "text/plain"));
		AgentRequest request = AgentRequest.builder()
			.query("这份日志报错了什么")
			.attachments(List.of(ChatAttachmentDTO.builder()
				.type("log")
				.storageKey("https://bucket.oss-cn.example.com/app.log")
				.fileName("app.log")
				.build()))
			.build();

		TurnArtifact artifact = service.fuseInto(request);

		String text = artifact.blocks()
			.stream()
			.map(FusionBlock::text)
			.filter(item -> item != null && item.contains("【日志"))
			.findFirst()
			.orElse("");
		assertTrue(text.contains("已截断"));
		assertTrue(text.contains("line-001"));
		assertTrue(text.contains("line-260"));
		assertFalse(text.contains("line-120"));
	}

	@Test
	void persistSnapshotWarnsInsteadOfThrowing() throws Exception {
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of(DataChatMessage.builder()
			.id(9L)
			.sessionId(100L)
			.role("user")
			.metadata("{}")
			.build()));
		doThrow(new RuntimeException("db down")).when(chatMessageService).updateById(any());
		AgentRequest request = AgentRequest.builder().threadId("100").query("这份对账单").build();
		TurnArtifact artifact = new TurnArtifact("a1", List.of(FusionBlock.text("客户甲", "bill.pdf", "k")), List.of(),
				4, "附件：文档 1 份");
		assertDoesNotThrow(() -> service.persistSnapshot(request, artifact));
	}

	@Test
	void fuseOrReuseCollaboratorLoadsParentSnapshotAsPointers() throws Exception {
		ExtractCard card = new ExtractCard("签收单", "pod",
				List.of(new ExtractCard.VisibleField("运单号", "YD1", "high")), false, "", ExtractCard.STATUS_OK);
		TurnFusionSnapshot snapshot = new TurnFusionSnapshot("art-9", "附件：图片 1 张",
				List.of(new TurnFusionSnapshot.SnapshotText("用户问句", null, "这张签收图写了什么"),
						new TurnFusionSnapshot.SnapshotText(card.flags(), "k", card.render())),
				List.of(new TurnFusionSnapshot.SnapshotImage("https://bucket.oss-cn.example.com/a.png", "sign.png",
						"image/png")),
				card.flags());
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of(DataChatMessage.builder()
			.sessionId(100L)
			.role("user")
			.metadata(new ObjectMapper()
				.writeValueAsString(java.util.Map.of(TurnFusionSnapshot.METADATA_KEY, snapshot)))
			.build()));
		AgentRequest request = AgentRequest.builder()
			.collaboratorChild(true)
			.threadId("100-collab-8-abcd1234")
			.parentThreadId("100")
			.query("查签收")
			.build();

		TurnArtifact artifact = service.fuseOrReuse(request);

		assertEquals("查签收", request.getQuery());
		assertFalse(artifact.hasPixelImage());
		assertTrue(artifact.hasKeepAsImage());
		assertEquals("art-9", artifact.artifactId());
		assertEquals("这张签收图写了什么", request.getOriginalUserQuery());
		assertEquals(ExtractCard.STATUS_OK, request.getExtractCard().extractStatus());
		verify(localFileService, never()).downloadImage(anyString(), anyLong(), anyCollection(), anyInt());
		verify(localFileService, never()).downloadDocument(anyString(), anyLong(), anyCollection(), anyInt());
	}

	@Test
	void fuseOrReuseCollaboratorLeavesArtifactWhenParentSnapshotMissing() {
		when(chatMessageService.findBySessionId(100L)).thenReturn(List.of());
		AgentRequest request = AgentRequest.builder()
			.collaboratorChild(true)
			.threadId("100-collab-8-abcd1234")
			.parentThreadId("100")
			.query("查签收")
			.build();
		assertNull(service.fuseOrReuse(request));
		assertNull(request.getTurnArtifact());
	}

	@Test
	void restorePixelsDownloadsCopyOnWrite() {
		when(localFileService.downloadImage(anyString(), anyLong(), anyCollection(), anyInt()))
			.thenReturn(new LocalFileService.DownloadedFile("https://bucket.oss-cn.example.com/a.png",
					FilePreviewResp.builder()
						.path("https://bucket.oss-cn.example.com/a.png")
						.originalName("sign.png")
						.previewUrl("https://preview.example.com/a.png")
						.build(),
					PNG, "image/png"));
		FusionBlock pointer = FusionBlock.imagePointer("https://bucket.oss-cn.example.com/a.png", "image/png",
				"sign.png");
		TurnArtifact original = new TurnArtifact("a1", List.of(pointer), List.of(), 10, "附件：图片 1 张");
		AgentRequest request = AgentRequest.builder()
			.originalUserQuery("这张签收图")
			.turnArtifact(original)
			.build();
		ModelConfigDTO vision = ModelConfigDTO.builder().supportVision(true).build();

		TurnArtifact restored = service.restorePixels(request, vision);

		assertTrue(restored.hasPixelImage());
		assertFalse(original.hasPixelImage());
		assertNotSame(pointer, restored.blocks().get(0));
		assertEquals("a1", restored.artifactId());
		assertEquals(restored, request.getTurnArtifact());

		ModelConfigDTO noVision = ModelConfigDTO.builder().supportVision(false).build();
		request.setTurnArtifact(original);
		assertFalse(service.restorePixels(request, noVision).hasPixelImage());
	}

	@Test
	void validatePointersAcceptsAudioAndLog() {
		List<ChatAttachmentDTO> saved = service.validatePointers(List.of(
				ChatAttachmentDTO.builder()
					.type("audio")
					.storageKey("https://bucket.oss-cn.example.com/a.wav")
					.fileName("a.wav")
					.size(100L)
					.build(),
				ChatAttachmentDTO.builder()
					.type("log")
					.storageKey("https://bucket.oss-cn.example.com/a.log")
					.fileName("a.log")
					.size(200L)
					.build()));
		assertEquals("audio", saved.get(0).getType());
		assertEquals("log", saved.get(1).getType());
	}

}
