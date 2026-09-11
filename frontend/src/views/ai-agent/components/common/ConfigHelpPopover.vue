<template>
  <ElPopover placement="right-start" :width="420" trigger="click">
    <template #reference>
      <ElButton class="config-help-trigger" link type="info" aria-label="查看配置说明">
        <ElIcon><InfoFilled /></ElIcon>
      </ElButton>
    </template>
    <div class="config-help">
      <div class="config-help-title">{{ help.title }}</div>
      <p class="config-help-description">{{ help.description }}</p>
      <ElDescriptions v-if="help.fields?.length" :column="1" border size="small">
        <ElDescriptionsItem v-for="field in help.fields" :key="field.name" :label="field.name">
          {{ field.description }}
        </ElDescriptionsItem>
      </ElDescriptions>
      <div v-if="help.minimalExample" class="config-help-example">
        <div class="config-help-example-title">
          <span>最小案例</span>
          <ElButton link type="primary" @click="copyExample(help.minimalExample)">复制</ElButton>
        </div>
        <pre>{{ help.minimalExample }}</pre>
      </div>
      <div v-if="help.completeExample" class="config-help-example">
        <div class="config-help-example-title">
          <span>完整案例</span>
          <ElButton link type="primary" @click="copyExample(help.completeExample)">复制</ElButton>
        </div>
        <pre>{{ help.completeExample }}</pre>
      </div>
      <ElAlert
        v-if="help.commonErrors?.length"
        class="config-help-errors"
        type="warning"
        :closable="false"
        title="常见错误"
      >
        <ul>
          <li v-for="error in help.commonErrors" :key="error">{{ error }}</li>
        </ul>
      </ElAlert>
    </div>
  </ElPopover>
</template>

<script setup lang="ts">
import { InfoFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import type { ConfigHelp } from './configHelp';

defineProps<{ help: ConfigHelp }>();

const copyExample = async (value: string) => {
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success('案例已复制');
  } catch {
    ElMessage.warning('当前浏览器不允许复制，请手动选择案例');
  }
};
</script>

<style scoped>
.config-help-trigger {
  padding: 0 2px;
  font-size: 14px;
}
.config-help-title {
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
}
.config-help-description {
  margin: 8px 0 12px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}
.config-help-example {
  margin-top: 12px;
}
.config-help-example-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--el-text-color-regular);
  font-size: 12px;
}
.config-help-example pre {
  max-height: 180px;
  margin: 6px 0 0;
  overflow: auto;
  padding: 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
}
.config-help-errors {
  margin-top: 12px;
}
.config-help-errors ul {
  margin: 0;
  padding-left: 18px;
}
</style>
