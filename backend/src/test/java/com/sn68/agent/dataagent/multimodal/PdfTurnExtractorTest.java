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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PdfTurnExtractorTest {

	private final PdfTurnExtractor extractor = new PdfTurnExtractor();

	@Test
	void emptyBytesThrow() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> extractor.extractText(new byte[0], "empty.pdf", 15, 8000L));
		assertTrue(ex.getMessage().contains("文档内容为空"));
	}

	@Test
	void invalidPdfHeaderThrows() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> extractor.extractText("%PDF".getBytes(StandardCharsets.US_ASCII), "bad.pdf", 15, 8000L));
		assertTrue(ex.getMessage().contains("无法解析 PDF"));
	}

	@Test
	void shortTextIsScannedWithoutThrow() {
		PdfTurnExtractor.ExtractResult result = extractor.extractText("too short".getBytes(StandardCharsets.UTF_8),
				"note.txt", 15, 8000L);
		assertTrue(result.scanned());
		assertTrue(result.text().length() < 40);
	}

	@Test
	void blankTextIsScannedWithoutThrow() {
		PdfTurnExtractor.ExtractResult result = extractor.extractText("   \n".getBytes(StandardCharsets.UTF_8),
				"blank.txt", 15, 8000L);
		assertTrue(result.scanned());
	}

	@Test
	void enoughTextIsNotScanned() {
		String text = "Customer A receivable 1200. This monthly statement needs a cross check with system data.";
		PdfTurnExtractor.ExtractResult result = extractor.extractText(text.getBytes(StandardCharsets.UTF_8),
				"bill.txt", 15, 8000L);
		assertFalse(result.scanned());
		assertTrue(result.text().contains("receivable 1200"));
	}

}
