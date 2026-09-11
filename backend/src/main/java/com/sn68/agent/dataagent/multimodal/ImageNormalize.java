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

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将图片最长边压到预算内。ImageIO 读不了的格式（如部分 webp）原样返回，不静默变空。
 */
@Component
public class ImageNormalize {

	public NormalizedImage normalize(byte[] bytes, String contentType, int maxEdgePx) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("图片内容为空");
		}
		int edge = Math.max(1, maxEdgePx);
		try {
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
			if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
				return new NormalizedImage(bytes, contentType, 0, 0);
			}
			int width = image.getWidth();
			int height = image.getHeight();
			int longest = Math.max(width, height);
			if (longest <= edge) {
				return new NormalizedImage(bytes, contentType, width, height);
			}
			double scale = edge / (double) longest;
			int targetW = Math.max(1, (int) Math.round(width * scale));
			int targetH = Math.max(1, (int) Math.round(height * scale));
			BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = resized.createGraphics();
			graphics.drawImage(image.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH), 0, 0, null);
			graphics.dispose();
			String format = formatName(contentType);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			if (!ImageIO.write(resized, format, output) || output.size() == 0) {
				return new NormalizedImage(bytes, contentType, width, height);
			}
			return new NormalizedImage(output.toByteArray(), mimeForFormat(format), targetW, targetH);
		}
		catch (Exception ex) {
			return new NormalizedImage(bytes, contentType, 0, 0);
		}
	}

	private static String formatName(String contentType) {
		if (!StringUtils.hasText(contentType)) {
			return "jpg";
		}
		String mime = contentType.toLowerCase();
		if (mime.contains("png")) {
			return "png";
		}
		if (mime.contains("jpeg") || mime.contains("jpg")) {
			return "jpg";
		}
		return "jpg";
	}

	private static String mimeForFormat(String format) {
		return "png".equals(format) ? "image/png" : "image/jpeg";
	}

	public record NormalizedImage(byte[] bytes, String contentType, int width, int height) {
	}

}
