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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

class PdfPageRendererTest {

	private final PdfPageRenderer renderer = new PdfPageRenderer();

	@Test
	void emptyBytesThrow() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> renderer.render(new byte[0], 1, 256, 8000L));
		assertTrue(ex.getMessage().contains("文档内容为空"));
	}

	@Test
	void invalidBytesThrowCheckedException() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> renderer.render("not-a-pdf".getBytes(StandardCharsets.UTF_8), 1, 256, 8000L));
		assertTrue(ex.getMessage().contains("无法解析 PDF"));
	}

	@Test
	void malformedPdfThrowsCheckedExceptionNotNpe() {
		byte[] bytes = "%PDF-1.4 this is not a valid body".getBytes(StandardCharsets.US_ASCII);
		CheckedException ex = assertThrows(CheckedException.class, () -> renderer.render(bytes, 1, 256, 8000L));
		assertTrue(ex.getMessage() != null && !ex.getMessage().isBlank());
	}

	@Test
	void blankPagePdfRendersPng() throws Exception {
		byte[] pdf = blankPagePdf();
		List<byte[]> pages = renderer.render(pdf, 1, 256, 8000L);
		assertFalse(pages.isEmpty());
		byte[] png = pages.get(0);
		assertTrue(png.length > 8);
		assertTrue(png[0] == (byte) 0x89 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G');
	}

	private static byte[] blankPagePdf() throws Exception {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage(PDRectangle.A4));
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			document.save(output);
			return output.toByteArray();
		}
	}

}
