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

import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 当轮 PDF/Office 文本抽取。几乎无文本层时标 scanned，由融合层按页渲染给视觉模型。
 */
@Component
public class PdfTurnExtractor {

	private static final int MIN_TEXT_CHARS = 40;

	private static final int CHARS_PER_PAGE = 2000;

	public record ExtractResult(String text, boolean scanned) {

		public ExtractResult {
			text = text == null ? "" : text;
		}

	}

	public ExtractResult extractText(byte[] bytes, String fileName, int maxPagesInContext, long timeoutMs) {
		if (bytes == null || bytes.length == 0) {
			throw CheckedException.badRequest("文档内容为空");
		}
		if (looksLikePdf(bytes) && !hasPdfHeader(bytes)) {
			throw CheckedException.badRequest("无法解析 PDF 文件");
		}
		String name = StringUtils.hasText(fileName) ? fileName : "document";
		int maxChars = Math.max(CHARS_PER_PAGE, Math.max(1, maxPagesInContext) * CHARS_PER_PAGE);
		long waitMs = timeoutMs > 0 ? timeoutMs : 8000L;
		String text = runWithTimeout(() -> readWithTika(bytes, name), waitMs, name);
		String trimmed = text == null ? "" : text.trim();
		if (!StringUtils.hasText(trimmed) || trimmed.length() < MIN_TEXT_CHARS) {
			return new ExtractResult(trimmed, true);
		}
		if (trimmed.length() > maxChars) {
			return new ExtractResult(
					trimmed.substring(0, maxChars) + "\n\n（文档过长，仅分析前 " + maxPagesInContext + " 页等效文本）",
					false);
		}
		return new ExtractResult(trimmed, false);
	}

	private static String readWithTika(byte[] bytes, String fileName) {
		ByteArrayResource resource = new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return fileName;
			}
		};
		List<Document> documents = new TikaDocumentReader(resource).read();
		if (documents == null || documents.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		for (Document document : documents) {
			if (document != null && StringUtils.hasText(document.getText())) {
				if (builder.length() > 0) {
					builder.append('\n');
				}
				builder.append(document.getText().trim());
			}
		}
		return builder.toString();
	}

	private static String runWithTimeout(Callable<String> task, long timeoutMs, String fileName) {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "pdf-turn-extract");
			thread.setDaemon(true);
			return thread;
		});
		Future<String> future = executor.submit(task);
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw CheckedException.badRequest("文档解析超时：" + fileName);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw CheckedException.fail("文档解析被中断");
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof CheckedException checked) {
				throw checked;
			}
			throw CheckedException.badRequest("文档解析失败：" + (cause.getMessage() == null ? fileName : cause.getMessage()));
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static boolean hasPdfHeader(byte[] bytes) {
		return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F'
				&& bytes[4] == '-';
	}

	private static boolean looksLikePdf(byte[] bytes) {
		return bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
	}

}
