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

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 回合级融合产物。挂在 {@code AgentRequest} 上，不写入 {@code agent_runtime_event}。
 */
public record TurnArtifact(String artifactId, List<FusionBlock> blocks, List<FusionEvent> events,
		int totalTokensEstimate, String routeSummary) {

	public TurnArtifact {
		blocks = blocks == null ? List.of() : List.copyOf(blocks);
		events = events == null ? List.of() : List.copyOf(events);
		routeSummary = routeSummary == null ? "" : routeSummary;
	}

	public boolean hasKeepAsImage() {
		return blocks.stream().anyMatch(FusionBlock::keepAsImage);
	}

	public boolean hasPixelImage() {
		return blocks.stream().anyMatch(FusionBlock::hasPixelPayload);
	}

	public TurnArtifact toPointerArtifact() {
		if (blocks.isEmpty() || !hasPixelImage()) {
			return this;
		}
		return new TurnArtifact(artifactId, blocks.stream().map(FusionBlock::withoutPixels).toList(), events,
				totalTokensEstimate, routeSummary);
	}

	public TurnArtifact withExtractCard(ExtractCard card) {
		if (card == null) {
			return this;
		}
		java.util.ArrayList<FusionBlock> next = new java.util.ArrayList<>();
		boolean replaced = false;
		for (FusionBlock block : blocks) {
			if (block != null && FusionBlock.KIND_TEXT.equals(block.kind())
					&& ExtractCard.isExtractHint(block.hint())) {
				if (!replaced) {
					next.add(card.toTextBlock());
					replaced = true;
				}
				continue;
			}
			next.add(block);
		}
		if (!replaced) {
			next.add(card.toTextBlock());
		}
		return new TurnArtifact(artifactId, next, events, totalTokensEstimate, routeSummary);
	}

	public FusionBlock extractCardBlock() {
		for (FusionBlock block : blocks) {
			if (block != null && FusionBlock.KIND_TEXT.equals(block.kind())
					&& ExtractCard.isExtractHint(block.hint())) {
				return block;
			}
		}
		return null;
	}

	/**
	 * 路由用问句：空问句回填「请根据附件回答」，并追加形态摘要。
	 */
	public String extractedContext() {
		if (blocks.isEmpty()) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		for (FusionBlock block : blocks) {
			if (block == null || !FusionBlock.KIND_TEXT.equals(block.kind()) || !StringUtils.hasText(block.text())) {
				continue;
			}
			if ("用户问句".equals(block.hint())) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append("\n\n");
			}
			builder.append(block.text().trim());
		}
		return builder.toString();
	}

	public String augmentQuery(String query) {
		String summary = routeSummary;
		String extracted = extractedContext();
		if (!StringUtils.hasText(query)) {
			query = "请根据附件回答";
		}
		if (StringUtils.hasText(summary) && !query.contains(summary)) {
			query = query + "\n" + summary;
		}
		if (StringUtils.hasText(extracted) && !query.contains(extracted)) {
			query = query + "\n\n" + extracted;
		}
		return query;
	}

}
