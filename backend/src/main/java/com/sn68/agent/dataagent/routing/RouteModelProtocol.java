/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

public final class RouteModelProtocol {

	public static final String JSON_SCHEMA = """
			{
			  "type":"object",
			  "additionalProperties":false,
			  "required":["decision","confidence"],
			  "properties":{
			    "decision":{"type":"string","enum":["SELECT","MULTI_SELECT","CLARIFY","NO_MATCH"]},
			    "candidates":{"type":"array","maxItems":5,"uniqueItems":true,"items":{"type":"string","pattern":"^c[1-5]$"}},
			    "steps":{
			      "type":"array",
			      "minItems":2,
			      "maxItems":5,
			      "items":{
			        "type":"object",
			        "additionalProperties":false,
			        "required":["stepId","handle","queryFragment","expectedOutput","dependsOn"],
			        "properties":{
			          "stepId":{"type":"string","minLength":1,"maxLength":64},
			          "handle":{"type":"string","pattern":"^c[1-5]$"},
			          "queryFragment":{"type":"string","minLength":1,"maxLength":1000},
			          "expectedOutput":{"type":"string","minLength":1,"maxLength":500},
			          "dependsOn":{"type":"array","maxItems":5,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":64}}
			        }
			      }
			    },
			    "clarificationQuestion":{"type":"string","minLength":1,"maxLength":1000},
			    "confidence":{"type":"number","minimum":0,"maximum":1}
			  }
			}
			""";

	private RouteModelProtocol() {
	}
}
