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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按形态分发的融合器。M0 只做 TEXT / IMAGE 直通和已是 markdown 的 TABLE；其余形态显式失败。
 */
@Component
public class MultiModalFuser {

	static final int DEFAULT_IMAGE_TOKENS = 1400;

	static final int IMAGE_TOKEN_PIXEL_DIVISOR = 750;

	public FusionResult fuse(List<ModalityInput> inputs) {
		return fuse(inputs, UUID.randomUUID().toString());
	}

	public FusionResult fuse(List<ModalityInput> inputs, String artifactId) {
		String id = StringUtils.hasText(artifactId) ? artifactId : UUID.randomUUID().toString();
		if (inputs == null || inputs.isEmpty()) {
			return FusionResult.empty(id);
		}
		List<FusionBlock> blocks = new ArrayList<>();
		List<FusionEvent> events = new ArrayList<>();
		int imageCount = 0;
		int tableCount = 0;
		int textCount = 0;
		for (ModalityInput input : inputs) {
			if (input == null || input.type() == null) {
				throw CheckedException.badRequest("多模态输入缺少形态类型");
			}
			long started = System.nanoTime();
			FusedPiece piece = fuseOne(input);
			long processingMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
			blocks.add(piece.block());
			events.add(FusionEvent.of(input.type(), piece.tokens(), processingMs, piece.method(), id));
			if (FusionBlock.KIND_IMAGE.equals(piece.block().kind())) {
				imageCount++;
			}
			else if (input.type() == ModalityType.TABLE) {
				tableCount++;
			}
			else if (input.type() == ModalityType.TEXT) {
				textCount++;
			}
		}
		int totalTokens = events.stream().mapToInt(FusionEvent::tokensOut).sum();
		TurnArtifact artifact = new TurnArtifact(id, blocks, events, totalTokens,
				routeSummary(textCount, imageCount, tableCount));
		return new FusionResult(artifact, FusionResult.healthOf(events));
	}

	private FusedPiece fuseOne(ModalityInput input) {
		if (input.keepAsImage() || input.type() == ModalityType.IMAGE) {
			return fuseImage(input);
		}
		return switch (input.type()) {
			case TEXT -> fuseText(input, "direct");
			case TABLE -> fuseTable(input);
			case PDF, LOG, AUDIO, SQL_RESULT -> throw CheckedException.badRequest(
					"暂不支持 " + input.type().name() + " 形态融合，请先走已开放的文本或图片路径");
			case IMAGE -> fuseImage(input);
		};
	}

	private FusedPiece fuseText(ModalityInput input, String method) {
		String text = stringify(input.payload());
		return new FusedPiece(FusionBlock.text(text, input.hint(), input.sourceRef()), estimateTextTokens(text), method);
	}

	private FusedPiece fuseTable(ModalityInput input) {
		if (!(input.payload() instanceof CharSequence markdown) || !StringUtils.hasText(markdown.toString())) {
			throw CheckedException.badRequest("表格融合需要 markdown 文本，当前抽取器尚未接入");
		}
		String text = markdown.toString();
		return new FusedPiece(FusionBlock.text(text, input.hint(), input.sourceRef()), estimateTextTokens(text),
				"table_to_md");
	}

	private FusedPiece fuseImage(ModalityInput input) {
		if (input.payload() == null && !StringUtils.hasText(input.sourceRef())) {
			throw CheckedException.badRequest("图片融合需要 storageKey 或图像内容");
		}
		return new FusedPiece(FusionBlock.image(input.payload(), input.contentType(), input.hint(), input.sourceRef()),
				estimateImageTokens(input), "vision");
	}

	private static int estimateTextTokens(String text) {
		if (!StringUtils.hasText(text)) {
			return 0;
		}
		return Math.max(1, text.length() / 4);
	}

	private static int estimateImageTokens(ModalityInput input) {
		if (input.pixelWidth() > 0 && input.pixelHeight() > 0) {
			return Math.max(1, input.pixelWidth() * input.pixelHeight() / IMAGE_TOKEN_PIXEL_DIVISOR);
		}
		return DEFAULT_IMAGE_TOKENS;
	}

	private static String stringify(Object payload) {
		return payload == null ? "" : String.valueOf(payload);
	}

	private static String routeSummary(int textCount, int imageCount, int tableCount) {
		List<String> parts = new ArrayList<>();
		if (imageCount > 0) {
			parts.add("图片 " + imageCount + " 张");
		}
		if (tableCount > 0) {
			parts.add("表格 " + tableCount + " 份");
		}
		if (textCount > 0) {
			parts.add("文本 " + textCount + " 段");
		}
		return parts.isEmpty() ? "" : "附件：" + String.join("；", parts);
	}

	private record FusedPiece(FusionBlock block, int tokens, String method) {
	}

}
