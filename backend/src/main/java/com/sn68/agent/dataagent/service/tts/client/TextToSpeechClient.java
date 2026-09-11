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
package com.sn68.agent.dataagent.service.tts.client;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.entity.TtsVoiceSample;

/**
 * TextTo语音合成Client服务契约。
 */
public interface TextToSpeechClient {

	/**
	 * 处理TextTo语音合成Client。
	 */
	boolean supports(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig, TtsVoiceProfile voiceProfile);

	/**
	 * 按模型与音色配置执行一次语音合成，失败抛业务异常。
	 */
	AudioSpeechResult synthesize(ModelConfigDTO modelConfig, ModelTtsConfig ttsConfig, TtsVoiceProfile voiceProfile,
			TtsVoiceSample voiceSample, AudioSpeechReq request);

}
