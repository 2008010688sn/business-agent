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

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 多模态输入形态。决策卡是纯函数：空间关系是信号则留图，否则转文本/表。
 */
public enum ModalityType {

	TEXT,
	IMAGE,
	TABLE,
	LOG,
	PDF,
	AUDIO,
	SQL_RESULT;

	/**
	 * 按 MIME、文件名和强制留图标记选择形态。识别不出时返回 {@code null}，由调用方显式失败。
	 */
	public static ModalityType decide(String contentType, String fileName, boolean keepAsImage) {
		if (keepAsImage) {
			return IMAGE;
		}
		String mime = normalize(contentType);
		String name = normalize(fileName);
		if (mime.startsWith("image/")) {
			return IMAGE;
		}
		if (mime.contains("pdf") || name.endsWith(".pdf")) {
			return PDF;
		}
		if (isTableMime(mime) || isTableName(name)) {
			return TABLE;
		}
		if (mime.startsWith("audio/") || isAudioName(name)) {
			return AUDIO;
		}
		if (isLogName(name)) {
			return LOG;
		}
		if (isTextMime(mime) || isTextName(name)) {
			return TEXT;
		}
		return null;
	}

	private static boolean isTableMime(String mime) {
		return mime.contains("csv") || mime.contains("spreadsheet") || mime.contains("excel")
				|| "application/vnd.ms-excel".equals(mime);
	}

	private static boolean isTableName(String name) {
		return name.endsWith(".csv") || name.endsWith(".xlsx") || name.endsWith(".xls");
	}

	private static boolean isAudioName(String name) {
		return name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a") || name.endsWith(".ogg");
	}

	private static boolean isTextMime(String mime) {
		return mime.startsWith("text/") || mime.contains("markdown") || mime.contains("json");
	}

	private static boolean isLogName(String name) {
		return name.endsWith(".log");
	}

	private static boolean isTextName(String name) {
		return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json");
	}

	private static String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
	}

}
