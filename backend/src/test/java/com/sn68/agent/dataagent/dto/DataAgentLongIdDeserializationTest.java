/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.schema.SemanticModelStatusModifyReq;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingV2DTO;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingsUpdateReq;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataAgentLongIdDeserializationTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void numericStringDeserializesToExactLong() throws Exception {
		Long id = objectMapper.readValue("\"2082710652612034561\"", Long.class);

		assertEquals(2082710652612034561L, id);
	}

	@Test
	void numericStringArrayDeserializesToExactLongList() throws Exception {
		List<Long> ids = objectMapper.readValue(
				"[\"2082711394685075458\",\"2082711394685075459\"]", new TypeReference<>() {
				});

		assertEquals(List.of(2082711394685075458L, 2082711394685075459L), ids);
	}

	@Test
	void skillBindingRequestAcceptsNumericStringIds() throws Exception {
		AgentSkillBindingsUpdateReq request = objectMapper.readValue("""
				{
				  "bindings": [
				    {
				      "skillId": "2082710652612034561",
				      "pinnedSkillVersionId": "2082710652737863682",
				      "priority": 0,
				      "enabled": true
				    }
				  ]
				}
				""", AgentSkillBindingsUpdateReq.class);

		assertEquals(List.of(new AgentSkillBindingV2DTO(2082710652612034561L, 2082710652737863682L, 0, true)),
				request.bindings());
	}

	@Test
	void semanticModelStatusRequestAcceptsNumericStringIds() throws Exception {
		SemanticModelStatusModifyReq request = objectMapper.readValue("""
				{
				  "ids": ["2082711394685075458", "2082711394685075459"],
				  "enabled": true
				}
				""", SemanticModelStatusModifyReq.class);

		assertEquals(List.of(2082711394685075458L, 2082711394685075459L), request.ids());
		assertEquals(true, request.enabled());
	}

}
