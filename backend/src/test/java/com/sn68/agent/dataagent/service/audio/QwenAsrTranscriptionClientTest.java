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

class QwenAsrTranscriptionClientTest {

	private final DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);

	private final QwenAsrTranscriptionClient client = new QwenAsrTranscriptionClient(dynamicModelFactory);

	@Test
	void transcribe_postsInputAudioToChatCompletions() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		when(dynamicModelFactory.createRestClientBuilder(any(ModelConfigDTO.class))).thenReturn(restClientBuilder);
		server.expect(requestTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().string(containsString("\"model\":\"qwen3-asr-flash\"")))
			.andExpect(content().string(containsString("\"type\":\"input_audio\"")))
			.andExpect(content().string(containsString("\"input_audio\"")))
			.andExpect(content().string(containsString("data:audio/wav;base64,")))
			.andExpect(content().string(containsString("\"stream\":false")))
			.andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"转写成功\"}}]}",
					MediaType.APPLICATION_JSON));

		String text = client.transcribe(qwenConfig(), audioFile("audio/wav", "hello"));

		assertEquals("转写成功", text);
		server.verify();
	}

	@Test
	void transcribe_rejectsQwenFlashAudioOverTenMb() {
		byte[] audio = new byte[(int) QwenAsrTranscriptionClient.MAX_QWEN_ASR_AUDIO_SIZE + 1];

		CheckedException ex = assertThrows(CheckedException.class,
				() -> client.transcribe(qwenConfig(), audioFile("audio/wav", audio)));

		assertTrue(ex.getMessage().contains("10MB"));
		verifyNoInteractions(dynamicModelFactory);
	}

	private ModelConfigDTO qwenConfig() {
		return ModelConfigDTO.builder()
			.provider("qwen")
			.apiKey("sk-test")
			.baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
			.modelName("qwen3-asr-flash")
			.modelType("AUDIO_TRANSCRIPTION")
			.proxyEnabled(false)
			.build();
	}

	private MockMultipartFile audioFile(String contentType, String content) {
		return audioFile(contentType, content.getBytes(StandardCharsets.UTF_8));
	}

	private MockMultipartFile audioFile(String contentType, byte[] content) {
		return new MockMultipartFile("file", "audio.wav", contentType, content);
	}

}
