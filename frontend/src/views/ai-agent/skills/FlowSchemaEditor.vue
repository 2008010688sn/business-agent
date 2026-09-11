<template>
  <div class="flow-schema-editor">
    <ElForm :inline="true" label-position="top">
      <ElFormItem label="Schema 版本">
        <ElSelect :model-value="schemaVersion" @update:model-value="updateField('schemaVersion', $event)">
          <ElOption label="v2（新建默认）" value="skill-flow/v2" />
          <ElOption label="v1（兼容导入）" value="skill-flow/v1" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="起始节点">
        <ElInput
          :model-value="startNode"
          placeholder="例如 collect"
          @update:model-value="updateField('startNode', $event)"
        />
      </ElFormItem>
      <ElFormItem label="中断策略">
        <ElSelect :model-value="interruptPolicy" @update:model-value="updateField('interruptPolicy', $event)">
          <ElOption label="询问用户" value="ASK" />
          <ElOption label="自动结束" value="END" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

type FlowDefinition = Record<string, unknown>;

const props = withDefaults(defineProps<{ modelValue?: FlowDefinition }>(), { modelValue: () => ({}) });
const emit = defineEmits<{ 'update:modelValue': [FlowDefinition] }>();

const schemaVersion = computed(() => String(props.modelValue.schemaVersion || 'skill-flow/v2'));
const startNode = computed(() => String(props.modelValue.startNode || ''));
const interruptPolicy = computed(() => String(props.modelValue.interruptPolicy || 'ASK'));

const updateField = (field: string, value: unknown) => {
  emit('update:modelValue', { ...props.modelValue, [field]: value });
};
</script>

<style scoped>
.flow-schema-editor :deep(.el-form-item) {
  margin-bottom: 10px;
}
.flow-schema-editor :deep(.el-select) {
  width: 180px;
}
.flow-schema-editor :deep(.el-input) {
  width: 180px;
}
</style>
