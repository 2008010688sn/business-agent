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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiModalFuserTest {

	private final MultiModalFuser fuser = new MultiModalFuser();

	@Test
	void emptyInputsProduceNoFusionEvents() {
		FusionResult result = fuser.fuse(List.of(), "art-empty");
		assertEquals("no fusion events", result.health().get("status"));
		assertTrue(result.artifact().blocks().isEmpty());
		assertEquals(0, result.artifact().totalTokensEstimate());
	}

	@Test
	void textPassesThroughWithDirectMethod() {
		FusionResult result = fuser.fuse(List.of(ModalityInput.text("本月应收对账", "问句")), "art-text");
		FusionEvent event = result.artifact().events().get(0);
		assertEquals(ModalityType.TEXT, event.modality());
		assertEquals("direct", event.method());
		assertEquals(FusionBlock.KIND_TEXT, result.artifact().blocks().get(0).kind());
		assertTrue(result.artifact().totalTokensEstimate() > 0);
		assertEquals("ok", result.health().get("status"));
	}

	@Test
	void imagePassesThroughAsVisionAndKeepAsImage() {
		ModalityInput image = ModalityInput.image("suite-key", "签收单", "suite-key", "image/jpeg", "sign.jpg");
		FusionResult result = fuser.fuse(List.of(image), "art-image");
		FusionBlock block = result.artifact().blocks().get(0);
		assertEquals(FusionBlock.KIND_IMAGE, block.kind());
		assertTrue(block.keepAsImage());
		assertEquals("vision", result.artifact().events().get(0).method());
		assertEquals(MultiModalFuser.DEFAULT_IMAGE_TOKENS, result.artifact().totalTokensEstimate());
		assertTrue(result.artifact().hasKeepAsImage());
		assertTrue(result.artifact().routeSummary().contains("图片 1 张"));
	}

	@Test
	void tableMarkdownUsesTableToMdMethod() {
		FusionResult result = fuser.fuse(List.of(ModalityInput.tableMarkdown("|客户|金额|\n|a|1|", "对账表", "file-1")),
				"art-table");
		assertEquals("table_to_md", result.artifact().events().get(0).method());
		assertEquals(FusionBlock.KIND_TEXT, result.artifact().blocks().get(0).kind());
		assertTrue(result.artifact().routeSummary().contains("表格 1 份"));
	}

	@Test
	void pdfIsRejectedUntilExtractorLands() {
		ModalityInput pdf = new ModalityInput(ModalityType.PDF, "key", "对账单", false, "key", "application/pdf",
				"bill.pdf", 0, 0);
		CheckedException ex = assertThrows(CheckedException.class, () -> fuser.fuse(List.of(pdf)));
		assertTrue(ex.getMessage().contains("PDF"));
	}

	@Test
	void imageTokenOvershootWhenImageDominates() {
		ModalityInput text = ModalityInput.text("x", "问句");
		ModalityInput image = ModalityInput.image("k", "图", "k", "image/png", "a.png").withPixels(1024, 1024);
		FusionResult result = fuser.fuse(List.of(text, image), "art-overshoot");
		assertTrue(result.imageTokenOvershoot());
		assertFalse(result.logTokenOvershoot());
		assertTrue(result.health().get("image_token_overshoot").contains("image tokens"));
	}

	@Test
	void blankQueryIsReplacedWithAttachmentInstruction() {
		FusionResult result = fuser.fuse(
				List.of(ModalityInput.image("k", "签收照片", "k", "image/jpeg", "s.jpg")), "art-query");
		assertTrue(result.artifact().augmentQuery("  ").contains("请根据附件回答"));
		assertTrue(result.artifact().augmentQuery("  ").contains("附件：图片 1 张"));
		assertTrue(result.artifact().augmentQuery("和本月应收对一下").contains("和本月应收对一下"));
	}

	@Test
	void decideKeepsForceImageAndMapsPdfByName() {
		assertEquals(ModalityType.IMAGE, ModalityType.decide("text/plain", "notes.txt", true));
		assertEquals(ModalityType.PDF, ModalityType.decide(null, "对账单.PDF", false));
		assertEquals(ModalityType.TABLE, ModalityType.decide("text/csv", "a.csv", false));
		assertEquals(ModalityType.IMAGE, ModalityType.decide("image/png", "x.png", false));
	}

}
