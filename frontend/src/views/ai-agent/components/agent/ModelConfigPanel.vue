<template>
  <div class="agent-model-config">
    <div class="panel-header">
      <div>
        <h2>模型配置</h2>
        <p>配置当前 Agent 的默认对话模型，以及用户问答页允许切换的模型范围。</p>
      </div>
      <el-button type="primary" class="save-action action-button" :loading="saving" @click="save">
        <el-icon class="button-glyph"><component :is="Check" /></el-icon>
        <span>保存</span>
      </el-button>
    </div>

    <el-alert
      v-if="allChatModels.length === 0"
      type="warning"
      show-icon
      title="暂无 CHAT 模型，请先在平台模型配置中添加对话模型。"
      :closable="false"
    />

    <el-form label-width="140px" class="model-form">
      <el-form-item label="默认模型">
        <el-select
          v-model="defaultModelConfigId"
          placeholder="选择默认模型"
          filterable
          clearable
          style="width: 100%"
        >
          <el-option
            v-for="model in allChatModels"
            :key="model.id"
            :label="formatModelLabel(model)"
            :value="model.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="用户可选模型">
        <el-table :data="modelRows" border>
          <el-table-column label="允许" width="90">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" />
            </template>
          </el-table-column>
          <el-table-column label="问答页可切换" width="130">
            <template #default="{ row }">
              <el-switch v-model="row.userSelectable" :disabled="!row.enabled" />
            </template>
          </el-table-column>
          <el-table-column label="模型">
            <template #default="{ row }">
              <div class="model-name">{{ row.modelName }}</div>
              <div class="model-meta">{{ row.provider }} / {{ row.baseUrl }}</div>
              <div class="model-capability-meta">
                {{ row.endpointDialect || 'OPENAI_COMPATIBLE' }} ·
                {{ row.capabilityProfile || 'AUTO' }} ·
                {{ row.reasoningMode || 'AUTO' }} / {{ row.reasoningLevel || 'DEFAULT' }} ·
                {{ row.structuredOutputMode || 'AUTO' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="平台默认" width="100">
            <template #default="{ row }">
              <el-tag :type="row.isActive ? 'success' : 'info'" effect="plain">
                {{ row.isActive ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-form-item>
    </el-form>
  </div>
</template>

<script lang="ts">
  import { computed, defineComponent, onMounted, ref } from 'vue';
  import { ElMessage } from 'element-plus';
  import { Check } from '@element-plus/icons-vue';
  import modelConfigService, {
    isChatModelConfig,
    type ModelConfig,
    type ModelConfigId,
  } from '@/views/ai-agent/services/modelConfig';
  import agentModelConfigService, { type AgentModelConfigItem } from '@/views/ai-agent/services/agentModelConfig';

  interface ModelRow extends ModelConfig {
    id: ModelConfigId;
    enabled: boolean;
    userSelectable: boolean;
  }

  export default defineComponent({
    name: 'AgentModelConfigPanel',
    props: {
      agentId: {
        type: String,
        required: true,
      },
    },
    setup(props) {
      const allModels = ref<ModelConfig[]>([]);
      const agentConfigs = ref<AgentModelConfigItem[]>([]);
      const modelRows = ref<ModelRow[]>([]);
      const defaultModelConfigId = ref<ModelConfigId | undefined>();
      const saving = ref(false);

      const allChatModels = computed(() => allModels.value.filter(isChatModelConfig));

      const buildModelRows = (models: Array<ModelConfig & { id: ModelConfigId }>) =>
        models.map(model => {
          const config = agentConfigs.value.find(item => item.modelConfigId === model.id);
          return {
            ...model,
            id: model.id,
            enabled: config?.enabled ?? Boolean(config?.isDefault),
            userSelectable: config?.userSelectable ?? true,
          };
        });

      const syncRowsFromConfigs = () => {
        modelRows.value = buildModelRows(allChatModels.value);
      };

      const syncDefaultFromConfigs = () => {
        defaultModelConfigId.value =
          agentConfigs.value.find(item => item.isDefault)?.modelConfigId ??
          agentConfigs.value.find(item => item.modelConfig?.isActive)?.modelConfigId ??
          allChatModels.value.find(model => model.isActive)?.id;
      };

      const load = async () => {
        const models = await modelConfigService.list();
        allModels.value = models;

        try {
          const configs = await agentModelConfigService.list(props.agentId);
          agentConfigs.value = configs;
          syncDefaultFromConfigs();
        } catch (error) {
          console.error('加载 Agent 模型配置失败:', error);
          agentConfigs.value = [];
          ElMessage.warning('Agent 模型配置加载失败，当前仅展示平台模型列表');
        }

        if (!defaultModelConfigId.value) {
          defaultModelConfigId.value = allChatModels.value.find(model => model.isActive)?.id;
        }
        syncRowsFromConfigs();
      };

      const save = async () => {
        try {
          saving.value = true;
          const enabledRows = modelRows.value.filter(row => row.enabled);
          if (
            defaultModelConfigId.value &&
            !enabledRows.some(row => row.id === defaultModelConfigId.value)
          ) {
            ElMessage.warning('默认模型需要先加入允许列表');
            return;
          }
          agentConfigs.value = await agentModelConfigService.update(props.agentId, {
            defaultModelConfigId: defaultModelConfigId.value,
            models: enabledRows.map(row => ({
              modelConfigId: row.id,
              userSelectable: row.userSelectable,
              enabled: row.enabled,
            })),
          });
          syncDefaultFromConfigs();
          syncRowsFromConfigs();
          ElMessage.success('模型配置已保存');
        } finally {
          saving.value = false;
        }
      };

      const formatModelLabel = (model: ModelConfig) => {
        const active = model.isActive ? ' / 平台默认' : '';
        return `${model.modelName} / ${model.provider}${active}`;
      };

      onMounted(() => {
        void load();
      });

      return {
        allChatModels,
        Check,
        defaultModelConfigId,
        formatModelLabel,
        modelRows,
        save,
        saving,
      };
    },
  });
</script>

<style scoped>
  .panel-header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 18px;
    border-bottom: 1px solid #edf0f5;
    padding-bottom: 18px;
  }

  .panel-header h2 {
    margin: 0 0 6px;
    font-size: 20px;
    color: #1f2937;
  }

  .panel-header p {
    margin: 0;
    color: #667085;
    font-size: 13px;
  }

  .model-form {
    max-width: 980px;
  }

  .model-name {
    color: #1f2937;
    font-weight: 600;
  }

  .model-meta {
    margin-top: 4px;
    color: #667085;
    font-size: 12px;
  }

  .model-capability-meta {
    margin-top: 3px;
    color: #98a2b3;
    font-size: 11px;
  }
</style>
