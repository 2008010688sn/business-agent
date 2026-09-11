<template>
  <div class="json-object-editor" :data-json-valid="String(isValid)">
    <div v-if="!readonly" class="json-toolbar">
      <div class="json-toolbar-left">
        <span>{{ typeHint }}</span>
        <ConfigHelpPopover v-if="showHelp && help" :help="help" />
      </div>
      <ElButton link type="primary" :disabled="!isValid" @click="formatDraft">格式化</ElButton>
    </div>
    <ElInput
      :model-value="draft"
      type="textarea"
      :rows="rows"
      :placeholder="resolvedPlaceholder"
      :readonly="readonly"
      spellcheck="false"
      :class="{ 'is-invalid-json': !isValid }"
      @input="handleInput"
      @blur="formatDraft"
    />
    <div v-if="errorMessage" class="json-error">{{ errorMessage }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import ConfigHelpPopover from './ConfigHelpPopover.vue';
import type { ConfigHelp } from './configHelp';

type JsonContainer = Record<string, unknown> | unknown[];
type JsonValueType = 'object' | 'array' | 'any';
type JsonOutputType = 'value' | 'string';

const props = withDefaults(
  defineProps<{
    modelValue?: JsonContainer | string | null;
    rows?: number;
    placeholder?: string;
    valueType?: JsonValueType;
    outputType?: JsonOutputType;
    readonly?: boolean;
    help?: ConfigHelp;
    showHelp?: boolean;
  }>(),
  {
    rows: 3,
    placeholder: '',
    valueType: 'object',
    outputType: 'value',
    readonly: false,
    modelValue: null,
    help: undefined,
    showHelp: true
  }
);

const emit = defineEmits<{
  'update:modelValue': [JsonContainer | string];
  'validity-change': [boolean];
}>();

const draft = ref('');
const errorMessage = ref('');
const isValid = computed(() => !errorMessage.value);
const emptyValue = computed<JsonContainer>(() => (props.valueType === 'array' ? [] : {}));
const typeHint = computed(() => {
  if (props.valueType === 'array') return 'JSON 数组';
  if (props.valueType === 'any') return '合法 JSON';
  return 'JSON 对象';
});
const resolvedPlaceholder = computed(() => props.placeholder || (props.valueType === 'array' ? '[]' : '{}'));

const assertValueType = (value: unknown) => {
  if (props.valueType === 'array' && !Array.isArray(value)) {
    throw new Error('请输入 JSON 数组，例如 [{"name":"示例"}]');
  }
  if (props.valueType === 'object' && (!value || typeof value !== 'object' || Array.isArray(value))) {
    throw new Error('请输入 JSON 对象，例如 {"name":"示例"}');
  }
  return value as JsonContainer;
};

const parseDraft = (value: string) => {
  const text = value.trim();
  return text ? assertValueType(JSON.parse(text)) : emptyValue.value;
};

const toText = (value: JsonContainer | string | null | undefined) => {
  if (typeof value === 'string') return value.trim() || JSON.stringify(emptyValue.value, null, 2);
  try {
    return JSON.stringify(assertValueType(value ?? emptyValue.value), null, 2);
  } catch {
    return JSON.stringify(emptyValue.value, null, 2);
  }
};

const setValidity = (message = '') => {
  const changed = Boolean(errorMessage.value) !== Boolean(message);
  errorMessage.value = message;
  if (changed) emit('validity-change', !message);
};

const emitValue = (parsed: JsonContainer) => {
  emit('update:modelValue', props.outputType === 'string' ? JSON.stringify(parsed, null, 2) : parsed);
};

const validateAndEmit = (value: string) => {
  try {
    const parsed = parseDraft(value);
    setValidity();
    emitValue(parsed);
    return parsed;
  } catch (error) {
    const message =
      error instanceof SyntaxError
        ? 'JSON 格式错误，请检查引号、逗号和括号'
        : error instanceof Error
          ? error.message
          : '请输入合法 JSON';
    setValidity(message);
    return null;
  }
};

watch(
  () => props.modelValue,
  value => {
    const next = toText(value);
    if (next !== draft.value) draft.value = next;
    try {
      parseDraft(next);
      setValidity();
    } catch (error) {
      setValidity(error instanceof Error ? error.message : '请输入合法 JSON');
    }
  },
  { immediate: true, deep: true }
);

const handleInput = (value: string) => {
  draft.value = value;
  validateAndEmit(value);
};

const formatDraft = () => {
  const parsed = validateAndEmit(draft.value);
  if (parsed) draft.value = JSON.stringify(parsed, null, 2);
};
</script>

<style scoped>
.json-object-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.json-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 24px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.json-toolbar-left {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.json-object-editor :deep(.is-invalid-json .el-textarea__inner) {
  border-color: var(--el-color-danger);
  box-shadow: 0 0 0 1px var(--el-color-danger-light-5);
}
.json-object-editor :deep(.el-textarea__inner) {
  font-family: Consolas, Monaco, monospace;
  line-height: 1.55;
}
.json-error {
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.4;
}
</style>
