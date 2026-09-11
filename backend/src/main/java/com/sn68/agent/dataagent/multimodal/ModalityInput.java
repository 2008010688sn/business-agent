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

/**
 * 一条待融合输入。{@code hint} 由文件名和问句推导，不新增前端字段。
 */
public record ModalityInput(ModalityType type, Object payload, String hint, boolean keepAsImage, String sourceRef,
		String contentType, String fileName, int pixelWidth, int pixelHeight) {

	public static ModalityInput text(String text, String hint) {
		return new ModalityInput(ModalityType.TEXT, text, hint, false, null, "text/plain", null, 0, 0);
	}

	public static ModalityInput image(Object payload, String hint, String sourceRef, String contentType,
			String fileName) {
		return new ModalityInput(ModalityType.IMAGE, payload, hint, true, sourceRef, contentType, fileName, 0, 0);
	}

	public static ModalityInput tableMarkdown(String markdown, String hint, String sourceRef) {
		return new ModalityInput(ModalityType.TABLE, markdown, hint, false, sourceRef, "text/markdown", null, 0, 0);
	}

	public ModalityInput withPixels(int width, int height) {
		return new ModalityInput(type, payload, hint, keepAsImage, sourceRef, contentType, fileName, width, height);
	}

}
