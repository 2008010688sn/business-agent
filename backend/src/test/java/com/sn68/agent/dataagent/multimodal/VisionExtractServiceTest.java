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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageContext;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

class VisionExtractServiceTest {

	private final DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);

	private final AgentTokenUsageService tokenUsageService = mock(AgentTokenUsageService.class);

	private final ChatModel chatModel = mock(ChatModel.class);

	private DataAgentProperties properties;

	private VisionExtractService service;

	@BeforeEach
	void setUp() {
		properties = new DataAgentProperties();
		service = new VisionExtractService(properties, dynamicModelFactory, tokenUsageService);
		when(dynamicModelFactory.createBoundedStructuredModel(any(ModelConfigDTO.class), any(Duration.class),
				anyLong(), anyString())).thenReturn(chatModel);
		when(tokenUsageService.buildContext(any(), any(), anyString()))
			.thenReturn(AgentTokenUsageContext.builder().usageSource(VisionExtractService.USAGE_SOURCE).build());
	}

	@Test
	void noPixelImageReturnsNull() {
		TurnArtifact artifact = new TurnArtifact("art",
				List.of(FusionBlock.imagePointer("k", "image/png", "图")), List.of(), 1, "");

		assertNull(service.extract(request("看附件"), visionConfig(), artifact));
		assertNull(service.extract(request("看附件"), visionConfig(), null));
		verify(dynamicModelFactory, never()).createBoundedStructuredModel(any(), any(), anyLong(), anyString());
		verify(tokenUsageService, never()).callAndRecord(any(), any(Prompt.class), any());
	}

	@Test
	void alreadyHasExtractBlockSkips() {
		TurnArtifact artifact = new TurnArtifact("art",
				List.of(FusionBlock.image(new byte[] { 1 }, "image/png", "图", "k"),
						ExtractCard.skipped("already").toTextBlock()),
				List.of(), 1, "");

		assertNull(service.extract(request("看附件"), visionConfig(), artifact));
		verify(dynamicModelFactory, never()).createBoundedStructuredModel(any(), any(), anyLong(), anyString());
	}

	@Test
	void supportVisionFalseReturnsSkipped() {
		ModelConfigDTO config = visionConfig();
		config.setSupportVision(false);

		VisionExtractService.ExtractResult result = service.extract(request("看附件"), config, pixelArtifact(1));

		assertNotNull(result);
		assertEquals(ExtractCard.STATUS_SKIPPED, result.card().extractStatus());
		assertTrue(result.card().unreadReason().contains("不支持图片"));
		verify(dynamicModelFactory, never()).createBoundedStructuredModel(any(), any(), anyLong(), anyString());
	}

	@Test
	void disabledExtractReturnsSkipped() {
		properties.getMultimodal().setVisionExtractEnabled(false);

		VisionExtractService.ExtractResult result = service.extract(request("看附件"), visionConfig(), pixelArtifact(1));

		assertEquals(ExtractCard.STATUS_SKIPPED, result.card().extractStatus());
		assertTrue(result.card().unreadReason().contains("未启用"));
		verify(dynamicModelFactory, never()).createBoundedStructuredModel(any(), any(), anyLong(), anyString());
	}

	@Test
	void modelJsonWithBillNumberIsOk() {
		when(tokenUsageService.callAndRecord(eq(chatModel), any(Prompt.class), any()))
			.thenReturn("```json\n" + billJson("ZD-20260801") + "\n```");
		AgentRequest request = request("查账单 ZD-20260801");
		ModelConfigDTO config = visionConfig();

		VisionExtractService.ExtractResult result = service.extract(request, config, pixelArtifact(1));

		assertEquals(ExtractCard.STATUS_OK, result.card().extractStatus());
		assertEquals(1, result.card().fieldCount());
		assertTrue(result.card().hasHighConfidenceField());
		assertTrue(result.card().render().contains("账单编号"));
		assertEquals("ZD-20260801", result.card().visibleFields().get(0).value());
		assertEquals("statement", result.card().docHint());
		assertTrue(result.durationMs() >= 0L);
		ArgumentCaptor<ModelConfigDTO> configCaptor = ArgumentCaptor.forClass(ModelConfigDTO.class);
		verify(dynamicModelFactory).createBoundedStructuredModel(configCaptor.capture(), eq(Duration.ofMillis(8000L)),
				eq(640L), anyString());
		assertEquals(0D, configCaptor.getValue().getTemperature());
		assertEquals(0.7D, config.getTemperature());
	}

	@Test
	void timeoutReturnsFailedWithoutThrowing() {
		when(tokenUsageService.callAndRecord(eq(chatModel), any(Prompt.class), any()))
			.thenThrow(new RuntimeException(new TimeoutException("deadline has expired")));

		VisionExtractService.ExtractResult result = service.extract(request("看附件"), visionConfig(), pixelArtifact(1));

		assertEquals(ExtractCard.STATUS_FAILED, result.card().extractStatus());
		assertTrue(result.card().unreadReason().contains("超时"));
		assertTrue(result.card().needsVisionFollowup());
	}

	@Test
	void exceptionReturnsFailedWithoutThrowing() {
		when(tokenUsageService.callAndRecord(eq(chatModel), any(Prompt.class), any()))
			.thenThrow(new RuntimeException("upstream boom"));

		VisionExtractService.ExtractResult result = service.extract(request("看附件"), visionConfig(), pixelArtifact(1));

		assertEquals(ExtractCard.STATUS_FAILED, result.card().extractStatus());
		assertEquals("视觉抽取失败", result.card().unreadReason());
	}

	@Test
	void moreThanFourImagesSendsOnlyFirstFour() {
		when(tokenUsageService.callAndRecord(eq(chatModel), any(Prompt.class), any())).thenReturn(billJson("ZD-1"));

		VisionExtractService.ExtractResult result = service.extract(request("看附件"), visionConfig(), pixelArtifact(5));

		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		verify(tokenUsageService).callAndRecord(eq(chatModel), promptCaptor.capture(), any());
		UserMessage userMessage = assertInstanceOf(UserMessage.class, promptCaptor.getValue().getInstructions().get(0));
		assertEquals(4, userMessage.getMedia().size());
		assertArrayEquals(new byte[] { 1 }, userMessage.getMedia().get(0).getDataAsByteArray());
		assertArrayEquals(new byte[] { 4 }, userMessage.getMedia().get(3).getDataAsByteArray());
		assertTrue(result.card().needsVisionFollowup());
		assertTrue(result.card().caption().contains("其余"));
		assertTrue(result.card().caption().contains("未读"));
		assertFalse(result.card().hasUnreadReason());
	}

	private AgentRequest request(String query) {
		return AgentRequest.builder().query(query).originalUserQuery(query).build();
	}

	private ModelConfigDTO visionConfig() {
		ModelConfigDTO config = new ModelConfigDTO();
		config.setSupportVision(true);
		config.setProvider("openai");
		config.setBaseUrl("http://localhost");
		config.setModelName("qwen-vl");
		config.setModelType("CHAT");
		config.setApiKey("test-key");
		config.setTemperature(0.7D);
		return config;
	}

	private TurnArtifact pixelArtifact(int images) {
		List<FusionBlock> blocks = new ArrayList<>();
		for (int i = 0; i < images; i++) {
			blocks.add(FusionBlock.image(new byte[] { (byte) (i + 1) }, "image/png", "图", "k" + i));
		}
		return new TurnArtifact("art-1", blocks, List.of(), 1, "");
	}

	private String billJson(String billNo) {
		return "{\"caption\":\"对账单截图\",\"docHint\":\"statement\",\"visibleFields\":["
				+ "{\"label\":\"账单编号\",\"value\":\"" + billNo + "\",\"confidence\":\"high\"}],"
				+ "\"needsVisionFollowup\":false}";
	}

}
