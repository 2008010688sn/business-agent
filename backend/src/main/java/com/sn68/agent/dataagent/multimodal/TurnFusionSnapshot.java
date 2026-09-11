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

/**
 * 会话内可复用的融合快照。只存文本抽取和图片指针，不存像素。
 */
public record TurnFusionSnapshot(String artifactId, String routeSummary, List<SnapshotText> texts,
		List<SnapshotImage> images, String extractFlags) {

	public static final String METADATA_KEY = "fusionExtract";

	public TurnFusionSnapshot(String artifactId, String routeSummary, List<SnapshotText> texts,
			List<SnapshotImage> images) {
		this(artifactId, routeSummary, texts, images, "");
	}

	public TurnFusionSnapshot {
		texts = texts == null ? List.of() : List.copyOf(texts);
		images = images == null ? List.of() : List.copyOf(images);
		routeSummary = routeSummary == null ? "" : routeSummary;
		extractFlags = extractFlags == null ? "" : extractFlags;
	}

	public record SnapshotText(String hint, String sourceRef, String text) {
	}

	public record SnapshotImage(String storageKey, String fileName, String contentType) {
	}

	public static TurnFusionSnapshot from(TurnArtifact artifact) {
		if (artifact == null || artifact.blocks() == null) {
			return new TurnFusionSnapshot("", "", List.of(), List.of(), "");
		}
		java.util.ArrayList<SnapshotText> texts = new java.util.ArrayList<>();
		java.util.ArrayList<SnapshotImage> images = new java.util.ArrayList<>();
		for (FusionBlock block : artifact.blocks()) {
			if (block == null) {
				continue;
			}
			if (FusionBlock.KIND_IMAGE.equals(block.kind()) && block.keepAsImage()) {
				images.add(new SnapshotImage(block.sourceRef(), block.hint(), block.mediaType()));
			}
			else if (FusionBlock.KIND_TEXT.equals(block.kind()) && block.text() != null && !block.text().isBlank()) {
				texts.add(new SnapshotText(block.hint(), block.sourceRef(), block.text()));
			}
		}
		String flags = "";
		for (FusionBlock block : artifact.blocks()) {
			if (block != null && ExtractCard.isExtractHint(block.hint())) {
				flags = block.hint();
				break;
			}
		}
		return new TurnFusionSnapshot(artifact.artifactId(), artifact.routeSummary(), texts, images, flags);
	}

	public boolean hasContent() {
		return !texts.isEmpty() || !images.isEmpty();
	}

}
