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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 fuse 的产物：回合 Artifact + 健康检查。
 */
public record FusionResult(TurnArtifact artifact, Map<String, String> health) {

	public FusionResult {
		health = health == null ? Map.of() : Map.copyOf(health);
	}

	public static FusionResult empty(String artifactId) {
		TurnArtifact artifact = new TurnArtifact(artifactId, List.of(), List.of(), 0, "");
		return new FusionResult(artifact, Map.of("status", "no fusion events"));
	}

	public boolean imageTokenOvershoot() {
		return health != null && health.containsKey("image_token_overshoot");
	}

	public boolean logTokenOvershoot() {
		return health != null && health.containsKey("log_token_overshoot");
	}

	static Map<String, String> healthOf(java.util.List<FusionEvent> events) {
		if (events == null || events.isEmpty()) {
			return Map.of("status", "no fusion events");
		}
		int total = events.stream().mapToInt(FusionEvent::tokensOut).sum();
		int safeTotal = Math.max(total, 1);
		Map<String, String> report = new LinkedHashMap<>();
		int imageTokens = tokens(events, ModalityType.IMAGE);
		int logTokens = tokens(events, ModalityType.LOG);
		double imageRatio = imageTokens / (double) safeTotal;
		double logRatio = logTokens / (double) safeTotal;
		if (imageRatio > 0.5) {
			report.put("image_token_overshoot",
					"image tokens = " + percent(imageRatio) + ", check whether some charts should be tables or markdown");
		}
		if (logRatio > 0.4) {
			report.put("log_token_overshoot", "log tokens = " + percent(logRatio) + ", bash filtering may not be working");
		}
		if (report.isEmpty()) {
			report.put("status", "ok");
		}
		return report;
	}

	private static int tokens(java.util.List<FusionEvent> events, ModalityType type) {
		return events.stream().filter(event -> event.modality() == type).mapToInt(FusionEvent::tokensOut).sum();
	}

	private static String percent(double ratio) {
		return Math.round(ratio * 1000d) / 10d + "%";
	}

}
