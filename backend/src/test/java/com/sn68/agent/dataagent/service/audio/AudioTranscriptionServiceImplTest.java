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
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AudioTranscriptionServiceImplTest {

	private final ModelConfigDataService modelConfigDataService = mock(ModelConfigDataService.class);

	private final StepFunAsrTranscriptionClient stepFunAsrTranscriptionClient = mock(StepFunAsrTranscriptionClient.class);

	private final QwenAsrTranscriptionClient qwenAsrTranscriptionClient = mock(QwenAsrTranscriptionClient.class);

	private final OpenAiCompatibleAudioTranscriptionClient openAiCompatibleAudioTranscriptionClient = mock(
			OpenAiCompatibleAudioTranscriptionClient.class);

	private final AudioTranscriptionServiceImpl service = new AudioTranscriptionServiceImpl(modelConfigDataService,
			stepFunAsrTranscriptionClient, qwenAsrTranscriptionClient, openAiCompatibleAudioTranscriptionClient);

	@Test
	void transcribe_routesStepFunConfigurationBeforeOtherClients() {
		ModelConfigDTO config = stepFunConfig();
		MockMultipartFile file = new MockMultipartFile("file", "audio.wav", "audio/wav",
				"hello".getBytes(StandardCharsets.UTF_8));
		when(modelConfigDataService.getActiveRuntimeConfigByType(ModelType.AUDIO_TRANSCRIPTION)).thenReturn(config);
		when(stepFunAsrTranscriptionClient.supports(config)).thenReturn(true);
		when(stepFunAsrTranscriptionClient.transcribe(config, file)).thenReturn("转写成功");

		assertEquals("转写成功", service.transcribe(file));
		verify(stepFunAsrTranscriptionClient).transcribe(config, file);
		verify(qwenAsrTranscriptionClient, never()).supports(any());
		verifyNoInteractions(openAiCompatibleAudioTranscriptionClient);
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

}
