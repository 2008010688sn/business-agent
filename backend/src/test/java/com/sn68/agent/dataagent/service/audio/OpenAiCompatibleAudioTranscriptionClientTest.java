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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleAudioTranscriptionClientTest {

	private final DynamicModelFactory dynamicModelFactory = mock(DynamicModelFactory.class);

	private final OpenAiCompatibleAudioTranscriptionClient client = new OpenAiCompatibleAudioTranscriptionClient(
			dynamicModelFactory);

	@Test
	void transcribe_postsMultipartToDefaultAudioTranscriptionsPath() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		when(dynamicModelFactory.createRestClientBuilder(any(ModelConfigDTO.class))).thenReturn(restClientBuilder);
		server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
			.andExpect(content().string(containsString("name=\"file\"")))
			.andExpect(content().string(containsString("name=\"model\"")))
			.andExpect(content().string(containsString("whisper-1")))
			.andExpect(content().string(containsString("name=\"stream\"")))
			.andExpect(content().string(containsString("false")))
			.andRespond(withSuccess("{\"text\":\"hello world\"}", MediaType.APPLICATION_JSON));

		String text = client.transcribe(openAiConfig(null), audioFile());

		assertEquals("hello world", text);
		server.verify();
	}

	@Test
	void transcribe_usesConfiguredTranscriptionsPath() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		when(dynamicModelFactory.createRestClientBuilder(any(ModelConfigDTO.class))).thenReturn(restClientBuilder);
		server.expect(requestTo("https://api.z.ai/api/paas/v4/audio/transcriptions"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().string(containsString("glm-asr-2512")))
			.andRespond(withSuccess("{\"text\":\"智谱转写\"}", MediaType.APPLICATION_JSON));

		ModelConfigDTO config = openAiConfig("/api/paas/v4/audio/transcriptions");
		config.setProvider("zhipu");
		config.setBaseUrl("https://api.z.ai");
		config.setModelName("glm-asr-2512");

		String text = client.transcribe(config, audioFile());

		assertEquals("智谱转写", text);
		server.verify();
	}

	private ModelConfigDTO openAiConfig(String transcriptionsPath) {
		return ModelConfigDTO.builder()
			.provider("openai")
			.apiKey("sk-test")
			.baseUrl("https://api.openai.com")
			.modelName("whisper-1")
			.modelType("AUDIO_TRANSCRIPTION")
			.transcriptionsPath(transcriptionsPath)
			.proxyEnabled(false)
			.build();
	}

	private MockMultipartFile audioFile() {
		return new MockMultipartFile("file", "audio.wav", "audio/wav",
				"hello".getBytes(StandardCharsets.UTF_8));
	}

}
