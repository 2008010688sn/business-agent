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
package com.sn68.agent.dataagent.service.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StepFunAsrTranscriptionClientTest {

	private final DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);

	private final StepFunAsrTranscriptionClient client = new StepFunAsrTranscriptionClient(dynamicModelFactory,
			new ObjectMapper());

	@Test
	void transcribe_postsJsonToStepPlanSseEndpointAndReturnsFinalText() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		when(dynamicModelFactory.createRestClientBuilder(any(ModelConfigDTO.class))).thenReturn(restClientBuilder);
		server.expect(requestTo("https://api.stepfun.com/step_plan/v1/audio/asr/sse"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
			.andExpect(header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().string(containsString("\"data\":\"aGVsbG8=\"")))
			.andExpect(content().string(containsString("\"model\":\"stepaudio-2.5-asr\"")))
			.andExpect(content().string(containsString("\"type\":\"wav\"")))
			.andRespond(withSuccess("data: {\"type\":\"transcript.text.delta\",\"delta\":\"转写\"}\n\n"
					+ "data: {\"type\":\"transcript.text.done\",\"text\":\"转写成功\"}\n\n",
					MediaType.TEXT_EVENT_STREAM));

		String text = client.transcribe(stepFunConfig(), audioFile("audio/wav"));

		assertEquals("转写成功", text);
		server.verify();
	}

	@Test
	void transcribe_usesDefaultSsePathWhenBaseUrlEndsWithV1() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		when(dynamicModelFactory.createRestClientBuilder(any(ModelConfigDTO.class))).thenReturn(restClientBuilder);
		server.expect(requestTo("https://api.stepfun.com/step_plan/v1/audio/asr/sse"))
			.andRespond(withSuccess("data: {\"type\":\"transcript.text.done\",\"text\":\"完成\"}\n\n",
					MediaType.TEXT_EVENT_STREAM));

		ModelConfigDTO config = stepFunConfig();
		config.setBaseUrl("https://api.stepfun.com/step_plan/v1");
		config.setTranscriptionsPath(null);

		assertEquals("完成", client.transcribe(config, audioFile("audio/wav")));
		server.verify();
	}

	@Test
	void transcribe_rejectsUnsupportedWebmBeforeCallingProvider() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> client.transcribe(stepFunConfig(), audioFile("audio/webm")));

		assertTrue(ex.getMessage().contains("WAV、MP3、OGG"));
		verifyNoInteractions(dynamicModelFactory);
	}

	@Test
	void transcribe_surfacesStepFunErrorEvent() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		when(dynamicModelFactory.createRestClientBuilder(any(ModelConfigDTO.class))).thenReturn(restClientBuilder);
		server.expect(requestTo("https://api.stepfun.com/step_plan/v1/audio/asr/sse"))
			.andRespond(withSuccess("data: {\"type\":\"error\",\"message\":\"请求参数错误\"}\n\n",
					MediaType.TEXT_EVENT_STREAM));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> client.transcribe(stepFunConfig(), audioFile("audio/wav")));

		assertTrue(ex.getMessage().contains("请求参数错误"));
		server.verify();
	}

	@Test
	void transcribe_rejectsSseResponseWithoutFinalEvent() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		when(dynamicModelFactory.createRestClientBuilder(any(ModelConfigDTO.class))).thenReturn(restClientBuilder);
		server.expect(requestTo("https://api.stepfun.com/step_plan/v1/audio/asr/sse"))
			.andRespond(withSuccess("data: {\"type\":\"transcript.text.delta\",\"delta\":\"部分文本\"}\n\n",
					MediaType.TEXT_EVENT_STREAM));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> client.transcribe(stepFunConfig(), audioFile("audio/wav")));

		assertTrue(ex.getMessage().contains("未收到最终转写事件"));
		server.verify();
	}

	@Test
	void supports_matchesStepFunBaseUrlOrSsePath() {
		assertTrue(client.supports(stepFunConfig()));

		ModelConfigDTO ssePathConfig = stepFunConfig();
		ssePathConfig.setBaseUrl("https://proxy.example.com");
		assertTrue(client.supports(ssePathConfig));

		ModelConfigDTO otherModel = stepFunConfig();
		otherModel.setModelName("whisper-1");
		assertFalse(client.supports(otherModel));
	}

	private ModelConfigDTO stepFunConfig() {
		return ModelConfigDTO.builder()
			.provider("openai")
			.apiKey("sk-test")
			.baseUrl("https://api.stepfun.com/step_plan")
			.modelName("stepaudio-2.5-asr")
			.modelType("AUDIO_TRANSCRIPTION")
			.transcriptionsPath("/v1/audio/asr/sse")
			.proxyEnabled(false)
			.build();
	}

	private MockMultipartFile audioFile(String contentType) {
		return new MockMultipartFile("file", "audio.wav", contentType, "hello".getBytes(StandardCharsets.UTF_8));
	}

}
