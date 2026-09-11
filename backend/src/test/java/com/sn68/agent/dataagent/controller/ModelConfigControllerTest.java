/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.ModelCapabilityDescriptorResp;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigOpsService;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsResolver;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ModelConfigControllerTest {

	@Test
	void capabilitiesEndpointReturnsResolverContractDirectly() throws Exception {
		ModelRequestOptionsResolver resolver = new ModelRequestOptionsResolver();
		ModelConfigController controller = new ModelConfigController(mock(ModelConfigDataService.class),
				mock(ModelConfigOpsService.class), resolver);

		List<ModelCapabilityDescriptorResp> descriptors = controller.capabilities();
		Method method = ModelConfigController.class.getMethod("capabilities");

		assertEquals(resolver.capabilityDescriptors(), descriptors);
		assertArrayEquals(new String[] { "/capabilities" }, method.getAnnotation(GetMapping.class).value());
	}

}
