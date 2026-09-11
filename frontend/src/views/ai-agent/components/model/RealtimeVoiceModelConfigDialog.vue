<template>
  <ElDialog v-model="visible" title="Realtime 语音模型配置" width="760px" :close-on-click-modal="false">
    <div v-if="!modelConfig" class="model-config-empty">请选择 REALTIME_VOICE 模型配置</div>
    <ElSkeleton v-else-if="loading" :rows="7" animated />
    <ElForm v-else label-width="140px">
      <ElFormItem label="WebSocket 地址">
        <ElInput v-model="form.websocketUrl" placeholder="供应商实时语音 WebSocket 地址" />
      </ElFormItem>
      <ElFormItem label="WebRTC 地址">
        <ElInput v-model="form.webrtcUrl" placeholder="后续启用 WebRTC 时填写" />
      </ElFormItem>
      <div class="realtime-form-grid">
        <ElFormItem label="输入格式">
          <ElSelect v-model="form.inputAudioFormat" style="width: 100%">
            <ElOption label="PCM16" value="PCM16" />
            <ElOption label="G711_ULAW" value="G711_ULAW" />
            <ElOption label="OPUS" value="OPUS" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="输出格式">
          <ElSelect v-model="form.outputAudioFormat" style="width: 100%">
            <ElOption label="PCM16" value="PCM16" />
            <ElOption label="MP3" value="MP3" />
            <ElOption label="OPUS" value="OPUS" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="输入采样率">
          <ElSelect v-model="form.inputSampleRate" style="width: 100%">
            <ElOption label="16000" :value="16000" />
            <ElOption label="24000" :value="24000" />
            <ElOption label="48000" :value="48000" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="输出采样率">
          <ElSelect v-model="form.outputSampleRate" style="width: 100%">
            <ElOption label="16000" :value="16000" />
            <ElOption label="24000" :value="24000" />
            <ElOption label="48000" :value="48000" />
          </ElSelect>
        </ElFormItem>
      </div>
      <ElFormItem label="轮次检测">
        <ElSelect v-model="form.turnDetectionType" style="width: 100%">
          <ElOption label="server_vad" value="server_vad" />
          <ElOption label="semantic_vad" value="semantic_vad" />
          <ElOption label="manual" value="manual" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="默认音色">
        <ElInput v-model="form.voiceName" placeholder="例如 alloy / cosyvoice-v1 / longxiaochun" />
      </ElFormItem>
      <ElFormItem label="返回转写">
        <ElSwitch v-model="form.transcriptEnabled" />
      </ElFormItem>
      <ElFormItem label="支持工具调用">
        <ElSwitch v-model="form.toolCallEnabled" />
      </ElFormItem>
      <ElFormItem label="支持打断">
        <ElSwitch v-model="form.interruptSupported" />
      </ElFormItem>
      <ElFormItem label="扩展选项（JSON）">
        <JsonObjectEditor
          v-model="form.options"
          :rows="5"
          placeholder='例如：{"modalities":["text","audio"]}'
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
import realtimeVoiceService, { type ModelRealtimeVoiceConfig } from '@/views/ai-agent/services/realtimeVoice';

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
const form = ref<ModelRealtimeVoiceConfig>({
  inputAudioFormat: 'PCM16',
  outputAudioFormat: 'PCM16',
  inputSampleRate: 16000,
  outputSampleRate: 24000,
  turnDetectionType: 'server_vad',
  transcriptEnabled: true,
  toolCallEnabled: true,
  interruptSupported: true,
  options: {}
});

const load = async () => {
  if (!visible.value || !modelConfig.value?.id) {
    return;
  }
  loading.value = true;
  optionsValid.value = true;
  try {
    const config = await realtimeVoiceService.getRealtimeVoiceModelConfig(modelConfig.value.id);
    form.value = {
      inputAudioFormat: 'PCM16',
      outputAudioFormat: 'PCM16',
      inputSampleRate: 16000,
      outputSampleRate: 24000,
      turnDetectionType: 'server_vad',
      transcriptEnabled: true,
      toolCallEnabled: true,
      interruptSupported: true,
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
    await realtimeVoiceService.saveRealtimeVoiceModelConfig(modelConfig.value.id, {
      ...form.value,
      modelConfigId: modelConfig.value.id,
      options: form.value.options || {}
    });
    ElMessage.success('Realtime 语音模型配置已保存');
    emit('saved');
    visible.value = false;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存 Realtime 语音模型配置失败');
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

.realtime-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}
</style>
