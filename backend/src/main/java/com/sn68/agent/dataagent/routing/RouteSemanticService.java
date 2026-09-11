/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteSemanticMatch;
import java.time.Duration;
import java.util.List;

public interface RouteSemanticService {

	List<RouteSemanticMatch> search(RouteContext context, RoutePolicy policy, List<RouteCandidate> eligibleCandidates,
			int topK, Duration timeout);

}
