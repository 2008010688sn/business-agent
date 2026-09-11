/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import com.sn68.agent.dataagent.routing.RouteModelOutputProtocol;

public record RoutePolicy(Long profileId, boolean lexicalAutoSelectEnabled, boolean semanticRecallEnabled,
		boolean semanticAutoSelectEnabled, boolean modelDisambiguationEnabled, int lexicalMinScore, int lexicalMinGap,
		double vectorRecallThreshold, double vectorAutoSelectThreshold, double vectorMinGap,
		double modelConfidenceThreshold, Long routeModelConfigId, Long embeddingModelConfigId,
		String embeddingFingerprint, boolean semanticRuntimeReady, boolean routeModelRuntimeReady,
		String routeModelFingerprint, RouteModelOutputProtocol routeModelProtocol) {

	public RoutePolicy(Long profileId, boolean lexicalAutoSelectEnabled, boolean semanticAutoSelectEnabled,
			boolean modelDisambiguationEnabled, int lexicalMinScore, int lexicalMinGap, double vectorRecallThreshold,
			double vectorAutoSelectThreshold, double vectorMinGap, double modelConfidenceThreshold,
			Long routeModelConfigId, Long embeddingModelConfigId, String embeddingFingerprint,
			boolean semanticRuntimeReady, boolean routeModelRuntimeReady, String routeModelFingerprint,
			RouteModelOutputProtocol routeModelProtocol) {
		this(profileId, lexicalAutoSelectEnabled, semanticAutoSelectEnabled, semanticAutoSelectEnabled,
				modelDisambiguationEnabled, lexicalMinScore, lexicalMinGap, vectorRecallThreshold,
				vectorAutoSelectThreshold, vectorMinGap, modelConfidenceThreshold, routeModelConfigId,
				embeddingModelConfigId, embeddingFingerprint, semanticRuntimeReady, routeModelRuntimeReady,
				routeModelFingerprint, routeModelProtocol);
	}

	public static RoutePolicy initial(Long profileId, String embeddingFingerprint) {
		return new RoutePolicy(profileId, true, false, false, true, 70, 15, 0D, 1D, 1D, 0.8D, null, null,
				embeddingFingerprint, false, false, null, RouteModelOutputProtocol.NONE);
	}

}
