<template>
  <ElForm :model="modelValue" label-position="top">
    <ElFormItem label="节点 ID">
      <ElInput
        :model-value="String(modelValue.id || '')"
        :disabled="disabledId"
        @update:model-value="updateField('id', $event)"
      />
    </ElFormItem>
    <ElFormItem label="节点类型">
      <ElSelect
        class="full-width"
        :model-value="String(modelValue.type || '')"
        @update:model-value="updateField('type', $event)"
      >
        <ElOption v-for="type in nodeTypes" :key="type" :label="nodeTypeLabels[type] || type" :value="type" />
      </ElSelect>
    </ElFormItem>
    <ElFormItem label="下一步">
      <ElInput
        :model-value="String(modelValue.next || '')"
        placeholder="留空表示由分支或终止节点处理"
        @update:model-value="updateField('next', $event)"
      />
    </ElFormItem>
    <ElFormItem label="节点说明">
      <ElInput
        :model-value="String(config.description || '')"
        placeholder="说明该节点的业务目的，不参与流程跳转"
        @update:model-value="updateConfigField('description', $event)"
      />
    </ElFormItem>
    <ElFormItem label="节点配置" class="drawer-json">
      <JsonObjectEditor
        v-model="config"
        :rows="14"
        :help="getFlowConfigHelp(String(modelValue.type || ''))"
        @validity-change="emit('validity-change', 'config', $event)"
      />
    </ElFormItem>
    <ElFormItem label="分支配置">
      <JsonObjectEditor
        v-model="branchesObject"
        :rows="8"
        value-type="object"
        placeholder='{"items":[{"condition":{"op":"eq","path":"/input/type","value":"A"},"next":"node-a"}]}'
        @validity-change="emit('validity-change', 'branches', $event)"
      />
    </ElFormItem>
  </ElForm>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import { getFlowConfigHelp } from './flowConfigHelp';

type FlowNodeValue = Record<string, any>;

const props = withDefaults(
  defineProps<{
    modelValue: FlowNodeValue;
    nodeTypes: string[];
    nodeTypeLabels: Record<string, string>;
    disabledId?: boolean;
  }>(),
  { disabledId: false }
);
const emit = defineEmits<{
  'update:modelValue': [FlowNodeValue];
  'validity-change': [field: 'config' | 'branches', value: boolean];
}>();

const updateField = (field: string, value: unknown) => {
  emit('update:modelValue', { ...props.modelValue, [field]: value });
};
const config = computed<Record<string, unknown>>({
  get: () => props.modelValue.config || {},
  set: value => updateField('config', value)
});
const updateConfigField = (field: string, value: unknown) => {
  config.value = { ...config.value, [field]: value };
};
const branchesObject = computed<Record<string, unknown>>({
  get: () => ({ items: Array.isArray(props.modelValue.branches) ? props.modelValue.branches : [] }),
  set: value => {
    const branches = Array.isArray(value.items) ? value.items : [];
    emit('update:modelValue', { ...props.modelValue, branches });
  }
});
</script>
