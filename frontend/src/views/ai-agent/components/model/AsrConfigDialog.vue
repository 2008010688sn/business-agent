<template>
  <ElDialog v-model="visible" title="ASR 能力配置" width="720px" :close-on-click-modal="false">
    <div v-if="!modelConfig" class="model-config-empty">请选择 AUDIO_TRANSCRIPTION 模型配置</div>
    <ElSkeleton v-else-if="loading" :rows="6" animated />
    <ElForm v-else label-width="130px">
      <ElFormItem label="普通转写路径">
        <ElInput v-model="form.transcriptionPath" placeholder="留空使用模型配置中的 transcriptionsPath 或默认路径" />
      </ElFormItem>
      <ElFormItem label="流式转写地址">
        <ElInput v-model="form.transcriptionStreamPath" placeholder="供应商支持真流式 ASR 时填写" />
      </ElFormItem>
      <ElFormItem label="协议">
        <ElSelect v-model="form.asrProtocol" style="width: 100%">
          <ElOption label="HTTP" value="http" />
          <ElOption label="WebSocket" value="websocket" />
          <ElOption label="本地 Sidecar" value="local" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="启用流式">
        <ElSwitch v-model="form.streamingEnabled" />
      </ElFormItem>
      <ElFormItem label="默认音频格式">
        <ElSelect v-model="form.defaultAudioFormat" style="width: 100%">
          <ElOption label="WEBM" value="webm" />
          <ElOption label="WAV" value="wav" />
          <ElOption label="PCM16" value="pcm16" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="默认采样率">
        <ElSelect v-model="form.defaultSampleRate" style="width: 100%">
          <ElOption label="16000" :value="16000" />
          <ElOption label="24000" :value="24000" />
          <ElOption label="48000" :value="48000" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="VAD 支持">
        <ElSwitch v-model="form.vadSupported" />
      </ElFormItem>
      <ElFormItem label="热词支持">
        <ElSwitch v-model="form.hotwordSupported" />
      </ElFormItem>
      <ElFormItem label="扩展选项（JSON）">
        <JsonObjectEditor
          v-model="form.options"
          :rows="5"
          placeholder='例如：{"language":"zh"}'
          @validity-change="optionsValid = $event"
        />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="saving" :disabled="!modelConfig || !optionsValid" @click="save">
        保存
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import type { ModelConfig } from '@/views/ai-agent/services/modelConfig';
import realtimeVoiceService, { type ModelAsrConfig } from '@/views/ai-agent/services/realtimeVoice';

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
const modelConfig = computed(() => props.modelConfig);
const optionsValid = ref(true);
const form = ref<ModelAsrConfig>({
  asrProtocol: 'http',
  streamingEnabled: false,
  defaultAudioFormat: 'webm',
  defaultSampleRate: 16000,
  vadSupported: true,
  hotwordSupported: false,
  options: {}
});

const load = async () => {
  if (!visible.value || !modelConfig.value?.id) {
    return;
  }
  loading.value = true;
  optionsValid.value = true;
  try {
    const config = await realtimeVoiceService.getAsrConfig(modelConfig.value.id);
    form.value = {
      asrProtocol: 'http',
      streamingEnabled: false,
      defaultAudioFormat: 'webm',
      defaultSampleRate: 16000,
      vadSupported: true,
      hotwordSupported: false,
      options: {},
      ...config,
      options: config.options || {}
    };
  } finally {
    loading.value = false;
  }
};

const save = async () => {
  if (!modelConfig.value?.id) {
    return;
  }
  if (!optionsValid.value) {
    ElMessage.warning('请先修正扩展选项中的 JSON 格式错误');
    return;
  }
  saving.value = true;
  try {
    await realtimeVoiceService.saveAsrConfig(modelConfig.value.id, {
      ...form.value,
      modelConfigId: modelConfig.value.id,
      options: form.value.options || {}
    });
    ElMessage.success('ASR 配置已保存');
    emit('saved');
    visible.value = false;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存 ASR 配置失败');
  } finally {
    saving.value = false;
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
.model-config-empty {
  padding: 24px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
</style>
