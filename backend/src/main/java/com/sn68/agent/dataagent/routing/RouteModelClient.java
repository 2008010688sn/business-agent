/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteModelResult;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import java.time.Duration;
import java.util.List;

public interface RouteModelClient {

	RouteModelResult disambiguate(RouteContext context, RoutePolicy policy, List<ScoredCandidate> candidates,
			Duration timeout);

}
