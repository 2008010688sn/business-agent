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
 * Fuser 产出的一块内容。图片只保留指针或调用方已准备好的像素，不在骨架里下载 Suite。
 */
public record FusionBlock(String kind, String text, String mediaType, Object payload, String hint, String sourceRef,
		boolean keepAsImage) {

	public static final String KIND_TEXT = "text";

	public static final String KIND_IMAGE = "image";

	public static FusionBlock text(String text, String hint, String sourceRef) {
		return new FusionBlock(KIND_TEXT, text, "text/plain", null, hint, sourceRef, false);
	}

	public static FusionBlock image(Object payload, String mediaType, String hint, String sourceRef) {
		return new FusionBlock(KIND_IMAGE, null, mediaType, payload, hint, sourceRef, true);
	}

	public static FusionBlock imagePointer(String sourceRef, String mediaType, String hint) {
		return new FusionBlock(KIND_IMAGE, null, mediaType, null, hint, sourceRef, true);
	}

	public boolean hasPixelPayload() {
		return payload instanceof byte[] bytes && bytes.length > 0;
	}

	public FusionBlock withoutPixels() {
		if (!keepAsImage || !hasPixelPayload()) {
			return this;
		}
		return imagePointer(sourceRef, mediaType, hint);
	}

}
