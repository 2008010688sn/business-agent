<template>
  <ElDialog v-model="visible" title="TTS 能力配置" width="920px" :close-on-click-modal="false">
    <div v-if="!modelConfig" class="tts-empty">请先选择一个 TEXT_TO_SPEECH 模型配置。</div>
    <ElSkeleton v-else-if="loading" :rows="8" animated />
    <div v-else class="tts-config-dialog">
      <ElAlert
        type="info"
        show-icon
        :closable="false"
        title="音色、语速、样本等参数在这里维护；通用模型配置只保存 provider、Base URL、模型名和密钥。"
      />

      <ElForm label-width="120px" class="tts-config-form">
        <ElDivider content-position="left">合成接口</ElDivider>
        <div class="tts-form-grid">
          <ElFormItem label="普通合成路径">
            <ElInput v-model="form.config.speechPath" placeholder="留空使用 /v1/audio/speech" />
          </ElFormItem>
          <ElFormItem label="流式合成路径">
            <ElInput v-model="form.config.speechStreamPath" placeholder="供应商支持时填写" />
          </ElFormItem>
          <ElFormItem label="协议">
            <ElSelect v-model="form.config.ttsProtocol" style="width: 100%">
              <ElOption label="HTTP" value="http" />
              <ElOption label="WebSocket" value="websocket" />
              <ElOption label="本地 Sidecar" value="local" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="启用流式">
            <ElSwitch v-model="form.config.streamingEnabled" />
          </ElFormItem>
          <ElFormItem label="默认格式">
            <ElSelect v-model="form.config.defaultFormat" style="width: 100%">
              <ElOption label="MP3" value="mp3" />
              <ElOption label="WAV" value="wav" />
              <ElOption label="OGG" value="ogg" />
              <ElOption label="OPUS" value="opus" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="采样率">
            <ElInputNumber v-model="form.config.defaultSampleRate" :min="8000" :max="48000" :step="1000" />
          </ElFormItem>
        </div>

        <ElDivider content-position="left">音色 Profile</ElDivider>
        <div class="tts-profile-toolbar">
          <ElButton type="primary" @click="addProfile">新增音色</ElButton>
          <ElButton @click="reloadSamples">刷新样本</ElButton>
        </div>
        <ElTable :data="form.voiceProfiles" border size="small" class="tts-profile-table">
          <ElTableColumn label="名称" min-width="130">
            <template #default="{ row }">
              <ElInput v-model="row.profileName" placeholder="如：默认女声" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="来源" width="150">
            <template #default="{ row }">
              <ElSelect v-model="row.voiceSource" style="width: 100%">
                <ElOption label="系统音色" value="SYSTEM" />
                <ElOption label="自定义音色 ID" value="CUSTOM" />
                <ElOption label="本地参考音色" value="LOCAL_REFERENCE" />
              </ElSelect>
            </template>
          </ElTableColumn>
          <ElTableColumn label="音色 ID" min-width="130">
            <template #default="{ row }">
              <ElInput v-model="row.voiceName" placeholder="如 default / alloy / cosyvoice-id" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="展示名" min-width="120">
            <template #default="{ row }">
              <ElInput v-model="row.voiceLabel" placeholder="可选" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="参考样本" min-width="170">
            <template #default="{ row }">
              <ElSelect
                v-model="row.sampleId"
                clearable
                :disabled="row.voiceSource !== 'LOCAL_REFERENCE'"
                placeholder="选择样本"
                style="width: 100%"
              >
                <ElOption
                  v-for="sample in selectableVoiceSamples"
                  :key="sample.id"
                  :label="sample.fileName || sample.fileId"
                  :value="sample.id"
                />
              </ElSelect>
            </template>
          </ElTableColumn>
          <ElTableColumn label="语速" width="110">
            <template #default="{ row }">
              <ElInputNumber v-model="row.speechSpeed" :min="0.5" :max="2" :step="0.1" :precision="2" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="默认" width="80" align="center">
            <template #default="{ $index, row }">
              <ElSwitch :model-value="row.isDefault" @change="setDefaultProfile($index)" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="启用" width="80" align="center">
            <template #default="{ row }">
              <ElSwitch v-model="row.enabled" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="80" align="center">
            <template #default="{ $index }">
              <ElButton text type="danger" @click="removeProfile($index)">删除</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <ElDivider content-position="left">本地参考录音</ElDivider>
        <div class="tts-sample-box">
          <input ref="sampleFileInput" type="file" accept=".wav,.mp3,.m4a,.webm,audio/*" hidden @change="handleSampleFileChange" />
          <ElInput
            v-model="sampleTranscript"
            type="textarea"
            :rows="3"
            placeholder="参考录音对应的转写文本。上传本地参考音色时建议填写，后续可由 ASR 自动补全。"
          />
          <div class="tts-sample-actions">
            <ElCheckbox v-model="sampleConsent">确认拥有该录音与音色的使用授权</ElCheckbox>
            <ElButton :loading="sampleUploading" @click="openSamplePicker">上传样本</ElButton>
          </div>
        </div>
      </ElForm>
    </div>

    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" :disabled="!modelConfig" @click="save">保存</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import AudioService, { type TtsVoiceSample } from '@/views/ai-agent/services/audio';
import type { ModelConfig, ModelConfigId } from '@/views/ai-agent/services/modelConfig';
import TtsConfigService, {
  type ModelTtsConfig,
  type TtsVoiceProfile
} from '@/views/ai-agent/services/ttsConfig';

type TtsConfigForm = {
  config: ModelTtsConfig;
  voiceProfiles: TtsVoiceProfile[];
};

type VoiceSampleWithId = TtsVoiceSample & { id: ModelConfigId };

const props = defineProps<{
  modelValue: boolean;
  modelConfig: ModelConfig | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
});

const loading = ref(false);
const saving = ref(false);
const sampleUploading = ref(false);
const voiceSamples = ref<TtsVoiceSample[]>([]);
const sampleFileInput = ref<HTMLInputElement | null>(null);
const sampleTranscript = ref('');
const sampleConsent = ref(false);

const defaultForm = (): TtsConfigForm => ({
  config: {
    speechPath: '',
    speechStreamPath: '',
    ttsProtocol: 'http',
    streamingEnabled: false,
    defaultFormat: 'mp3',
    defaultSampleRate: 24000,
    options: {}
  },
  voiceProfiles: []
});

const form = ref<TtsConfigForm>(defaultForm());

const modelConfig = computed(() => props.modelConfig);
const selectableVoiceSamples = computed<VoiceSampleWithId[]>(() =>
  voiceSamples.value.filter((sample): sample is VoiceSampleWithId => sample.id !== undefined && sample.id !== null)
);

const normalizeProfile = (profile?: Partial<TtsVoiceProfile>): TtsVoiceProfile => ({
  profileName: profile?.profileName || '默认音色',
  voiceName: profile?.voiceName || 'default',
  voiceLabel: profile?.voiceLabel || '',
  voiceSource: profile?.voiceSource || 'SYSTEM',
  sampleId: profile?.sampleId,
  languageCode: profile?.languageCode || 'zh-CN',
  gender: profile?.gender || '',
  speechSpeed: Number(profile?.speechSpeed ?? 1),
  pitch: Number(profile?.pitch ?? 0),
  volumeGain: Number(profile?.volumeGain ?? 0),
  speechFormat: profile?.speechFormat || '',
  sampleRate: profile?.sampleRate,
  options: profile?.options || {},
  isDefault: profile?.isDefault ?? true,
  enabled: profile?.enabled ?? true,
  id: profile?.id,
  ttsConfigId: profile?.ttsConfigId,
  sample: profile?.sample
});

const ensureDefaultProfile = () => {
  if (form.value.voiceProfiles.length === 0) {
    form.value.voiceProfiles.push(normalizeProfile());
  }
  if (!form.value.voiceProfiles.some(profile => profile.isDefault && profile.enabled !== false)) {
    form.value.voiceProfiles[0].isDefault = true;
  }
};

const load = async () => {
  if (!visible.value || !modelConfig.value?.id) {
    return;
  }
  loading.value = true;
  try {
    const [ttsConfig, samples] = await Promise.all([
      TtsConfigService.get(modelConfig.value.id),
      AudioService.listVoiceSamples()
    ]);
    form.value = {
      config: {
        ...defaultForm().config,
        ...(ttsConfig.config || {})
      },
      voiceProfiles: (ttsConfig.voiceProfiles || []).map(profile => normalizeProfile(profile))
    };
    voiceSamples.value = samples;
    ensureDefaultProfile();
  } catch (error) {
    console.error('Failed to load TTS config:', error);
    ElMessage.error('加载 TTS 配置失败');
  } finally {
    loading.value = false;
  }
};

const reloadSamples = async () => {
  voiceSamples.value = await AudioService.listVoiceSamples().catch(() => []);
};

const openSamplePicker = () => {
  sampleFileInput.value?.click();
};

const addProfile = () => {
  form.value.voiceProfiles.push(
    normalizeProfile({
      profileName: `音色 ${form.value.voiceProfiles.length + 1}`,
      isDefault: form.value.voiceProfiles.length === 0
    })
  );
};

const removeProfile = (index: number) => {
  form.value.voiceProfiles.splice(index, 1);
  ensureDefaultProfile();
};

const setDefaultProfile = (index: number) => {
  form.value.voiceProfiles.forEach((profile, profileIndex) => {
    profile.isDefault = profileIndex === index;
  });
};

const save = async () => {
  if (!modelConfig.value?.id) {
    return;
  }
  ensureDefaultProfile();
  saving.value = true;
  try {
    await TtsConfigService.save(modelConfig.value.id, {
      config: {
        ...form.value.config,
        modelConfigId: modelConfig.value.id,
        options: form.value.config?.options || {}
      },
      voiceProfiles: form.value.voiceProfiles.map(profile => ({
        ...profile,
        options: profile.options || {},
        sampleId: profile.voiceSource === 'LOCAL_REFERENCE' ? profile.sampleId : undefined
      }))
    });
    ElMessage.success('TTS 配置已保存');
    emit('saved');
    visible.value = false;
  } catch (error) {
    console.error('Failed to save TTS config:', error);
    ElMessage.error('保存 TTS 配置失败');
  } finally {
    saving.value = false;
  }
};

const handleSampleFileChange = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) {
    return;
  }
  if (!sampleConsent.value) {
    ElMessage.warning('上传参考录音前必须确认授权');
    return;
  }
  sampleUploading.value = true;
  try {
    const sample = await AudioService.uploadVoiceSample(file, sampleTranscript.value, sampleConsent.value);
    voiceSamples.value = [sample, ...voiceSamples.value.filter(item => item.id !== sample.id)];
    ElMessage.success('参考录音已上传');
  } catch (error) {
    console.error('Failed to upload voice sample:', error);
    ElMessage.error('参考录音上传失败');
  } finally {
    sampleUploading.value = false;
  }
};

watch(
  () => [visible.value, modelConfig.value?.id],
  () => {
    if (visible.value) {
      load();
    }
  }
);
</script>

<style scoped>
.tts-config-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tts-config-form {
  margin-top: 4px;
}

.tts-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.tts-profile-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.tts-profile-table :deep(.el-input-number) {
  width: 100%;
}

.tts-sample-box {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tts-sample-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.tts-empty {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
