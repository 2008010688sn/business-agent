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
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * 将扫描件 PDF 前 N 页渲染为 PNG，交给已有视觉模型。PDFBox 缺失时失败可见，不拖垮启动路径之外的问答。
 */
@Component
public class PdfPageRenderer {

	private static final float MAX_RENDER_SCALE = 4f;

	public List<byte[]> render(byte[] bytes, int maxPages, int maxEdgePx, long timeoutMs) {
		if (bytes == null || bytes.length == 0) {
			throw CheckedException.badRequest("文档内容为空");
		}
		requirePdfBox();
		if (!hasPdfHeader(bytes)) {
			throw CheckedException.badRequest("无法解析 PDF 文件");
		}
		int pages = Math.max(1, maxPages);
		int edge = Math.max(1, maxEdgePx);
		long waitMs = timeoutMs > 0 ? timeoutMs : 8000L;
		return runWithTimeout(() -> renderPages(bytes, pages, edge), waitMs);
	}

	private static List<byte[]> renderPages(byte[] bytes, int maxPages, int maxEdgePx) {
		try (PDDocument document = Loader.loadPDF(bytes)) {
			int pageCount = document.getNumberOfPages();
			if (pageCount <= 0) {
				return List.of();
			}
			PDFRenderer renderer = new PDFRenderer(document);
			int limit = Math.min(pageCount, maxPages);
			List<byte[]> pages = new ArrayList<>(limit);
			for (int i = 0; i < limit; i++) {
				byte[] png = renderOnePage(document, renderer, i, maxEdgePx);
				if (png != null && png.length > 0) {
					pages.add(png);
				}
			}
			return pages;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw CheckedException.badRequest(
					"扫描件页渲染失败：" + (ex.getMessage() == null ? "pdf" : ex.getMessage()));
		}
	}

	private static byte[] renderOnePage(PDDocument document, PDFRenderer renderer, int pageIndex, int maxEdgePx)
			throws Exception {
		PDPage page = document.getPage(pageIndex);
		PDRectangle box = page == null ? null : page.getCropBox();
		if (box == null || box.getWidth() <= 0 || box.getHeight() <= 0) {
			box = page == null ? null : page.getMediaBox();
		}
		float longest = 1f;
		if (box != null) {
			longest = Math.max(1f, Math.max(box.getWidth(), box.getHeight()));
		}
		float scale = Math.min(MAX_RENDER_SCALE, maxEdgePx / longest);
		BufferedImage image = renderer.renderImage(pageIndex, Math.max(0.1f, scale), ImageType.RGB);
		if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
			return new byte[0];
		}
		return writePng(scaleToMaxEdge(image, maxEdgePx));
	}

	private static BufferedImage scaleToMaxEdge(BufferedImage image, int maxEdgePx) {
		int width = image.getWidth();
		int height = image.getHeight();
		int longest = Math.max(width, height);
		if (longest <= maxEdgePx) {
			return image;
		}
		double scale = maxEdgePx / (double) longest;
		int targetW = Math.max(1, (int) Math.round(width * scale));
		int targetH = Math.max(1, (int) Math.round(height * scale));
		BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = resized.createGraphics();
		graphics.drawImage(image.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH), 0, 0, null);
		graphics.dispose();
		return resized;
	}

	private static byte[] writePng(BufferedImage image) throws Exception {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", output) || output.size() == 0) {
			throw CheckedException.badRequest("扫描件页渲染失败：无法写出 PNG");
		}
		return output.toByteArray();
	}

	private static List<byte[]> runWithTimeout(Callable<List<byte[]>> task, long timeoutMs) {
		ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "pdf-page-render");
			thread.setDaemon(true);
			return thread;
		});
		Future<List<byte[]>> future = executor.submit(task);
		try {
			return future.get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw CheckedException.badRequest("扫描件页渲染超时");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw CheckedException.fail("扫描件页渲染被中断");
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			if (cause instanceof CheckedException checked) {
				throw checked;
			}
			if (cause instanceof ClassNotFoundException || cause instanceof NoClassDefFoundError) {
				throw missingPdfBox();
			}
			throw CheckedException.badRequest(
					"扫描件页渲染失败：" + (cause.getMessage() == null ? "pdf" : cause.getMessage()));
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static void requirePdfBox() {
		try {
			Class.forName("org.apache.pdfbox.Loader");
			Class.forName("org.apache.pdfbox.pdmodel.PDDocument");
			Class.forName("org.apache.pdfbox.rendering.PDFRenderer");
		}
		catch (ClassNotFoundException ex) {
			throw missingPdfBox();
		}
	}

	private static CheckedException missingPdfBox() {
		return CheckedException.badRequest("扫描件 PDF 需要页渲染能力（PDFBox），当前环境未加载");
	}

	private static boolean hasPdfHeader(byte[] bytes) {
		return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F'
				&& bytes[4] == '-';
	}

}
