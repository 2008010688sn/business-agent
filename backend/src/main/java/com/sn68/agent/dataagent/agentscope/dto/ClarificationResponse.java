/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Public continuation payload. Option ids are opaque and server-issued. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Business clarification or confirmation response")
public class ClarificationResponse {

	@Schema(description = "business-clarify/v1 or confirm/v1")
	private String schemaVersion;

	@Schema(description = "Opaque server-issued continuation id")
	private String clarificationId;

	@Schema(description = "Opaque option ids shown by the server")
	private List<String> optionIds;

	@Schema(description = "Optional free-text business supplement")
	private String freeText;

}
