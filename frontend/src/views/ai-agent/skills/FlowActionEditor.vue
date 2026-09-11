<template>
  <div class="flow-action-editor">
    <div class="flow-action-header">
      <span>交互动作（文案用于 Web 展示和支持的 IM 文本动作）</span>
      <ElButton link type="primary" :disabled="!supportsActions" @click="addAction">新增动作</ElButton>
    </div>
    <ElTable :data="actions" border size="small">
      <ElTableColumn label="动作 ID" min-width="145">
        <template #default="{ row, $index }">
          <ElInput :model-value="row.actionId" @update:model-value="updateAction($index, 'actionId', $event)" />
        </template>
      </ElTableColumn>
      <ElTableColumn label="动作类型" min-width="135">
        <template #default="{ row, $index }">
          <ElSelect class="full-width" :model-value="row.type" @update:model-value="updateActionType($index, $event)">
            <ElOption v-for="type in availableTypes" :key="type" :label="type" :value="type" />
          </ElSelect>
        </template>
      </ElTableColumn>
      <ElTableColumn label="Web / IM 文案" min-width="160">
        <template #default="{ row, $index }">
          <ElInput :model-value="row.label" @update:model-value="updateAction($index, 'label', $event)" />
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="70">
        <template #default="{ $index }">
          <ElButton link type="danger" @click="removeAction($index)">删除</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

interface FlowAction {
  actionId?: string;
  type?: string;
  label?: string;
  value?: unknown;
  payload?: Record<string, unknown>;
  [key: string]: unknown;
}

const ACTION_TYPES_BY_NODE: Record<string, string[]> = {
  collect: ['SUBMIT', 'CANCEL'],
  validate: ['SUBMIT', 'CANCEL'],
  review: ['SUBMIT', 'CLEAR_REFERENCE', 'CANCEL'],
  confirm: ['CONFIRM', 'EDIT', 'CANCEL'],
  select: ['SELECT', 'SKIP', 'CANCEL'],
  resolve: ['SELECT', 'SKIP', 'CANCEL']
};
const ACTION_LABELS: Record<string, string> = {
  SUBMIT: '提交',
  CLEAR_REFERENCE: '清除历史参考',
  CONFIRM: '确认',
  EDIT: '返回修改',
  CANCEL: '取消',
  SELECT: '选择',
  SKIP: '跳过'
};

const props = withDefaults(defineProps<{ modelValue?: FlowAction[]; nodeType?: string }>(), {
  modelValue: () => [],
  nodeType: ''
});
const emit = defineEmits<{ 'update:modelValue': [FlowAction[]] }>();
const actions = computed(() => props.modelValue);
const supportsActions = computed(() => Object.hasOwn(ACTION_TYPES_BY_NODE, props.nodeType));
const availableTypes = computed(() => ACTION_TYPES_BY_NODE[props.nodeType] || Object.keys(ACTION_LABELS));

const updateAction = (index: number, field: keyof FlowAction, value: unknown) => {
  emit(
    'update:modelValue',
    actions.value.map((action, actionIndex) => (actionIndex === index ? { ...action, [field]: value } : action))
  );
};
const updateActionType = (index: number, value: string) => {
  const action = actions.value[index] || {};
  const next = { ...action, type: value };
  if (value === 'CONFIRM' && (next.value === null || next.value === undefined)) next.value = true;
  emit(
    'update:modelValue',
    actions.value.map((item, actionIndex) => (actionIndex === index ? next : item))
  );
};
const nextActionId = (type: string) => {
  const base = `${type.toLowerCase()}-action`;
  const existing = new Set(actions.value.map(action => action.actionId));
  let candidate = base;
  let suffix = 2;
  while (existing.has(candidate)) candidate = `${base}-${suffix++}`;
  return candidate;
};
const addAction = () => {
  const type = availableTypes.value[0] || '';
  emit('update:modelValue', [
    ...actions.value,
    {
      actionId: nextActionId(type),
      type,
      label: ACTION_LABELS[type] || '',
      ...(type === 'CONFIRM' ? { value: true } : {})
    }
  ]);
};
const removeAction = (index: number) =>
  emit(
    'update:modelValue',
    actions.value.filter((_, actionIndex) => actionIndex !== index)
  );
</script>

<style scoped>
.flow-action-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.full-width {
  width: 100%;
}
</style>
