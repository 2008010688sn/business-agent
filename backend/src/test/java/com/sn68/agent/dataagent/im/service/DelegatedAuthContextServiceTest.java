/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.im.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextReq;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import org.junit.jupiter.api.Test;

class DelegatedAuthContextServiceTest {

	@Test
	void issueReturnsFailedInStandaloneMode() {
		DelegatedAuthContextService service = new DelegatedAuthContextService(new DataAgentAsyncContextBridge());
		DelegatedAuthContextReq req = DelegatedAuthContextReq.builder()
			.userId("42")
			.provider(ImConstants.PROVIDER_DINGTALK)
			.connectorCode("ding-1")
			.runtimeRequestId("req-1")
			.build();

		DelegatedAuthContextService.IssueResult result = service.issue(req);

		assertFalse(result.succeeded());
		assertEquals(DelegatedAuthContextService.IssueResult.Status.FAILED, result.status());
		assertEquals("standalone-no-iam", result.reason());
		assertNull(result.context());
	}

	@Test
	void issueReturnsFailedWhenRequestEmpty() {
		DelegatedAuthContextService service = new DelegatedAuthContextService(new DataAgentAsyncContextBridge());

		DelegatedAuthContextService.IssueResult result = service.issue(null);

		assertEquals("request-empty", result.reason());
	}

}
