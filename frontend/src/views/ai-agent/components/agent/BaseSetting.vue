<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->

<template>
  <div class="agent-base-setting">
    <div class="panel-header">
      <div>
        <h2>基本信息</h2>
        <p>{{ editStatusHint }}</p>
      </div>
    </div>
    <ElRow :gutter="20" class="form-grid">
      <ElCol :span="24">
        <div class="form-item">
          <label>智能体名称</label>
          <ElInput v-model="props.agent.name" placeholder="请输入智能体名称" />
        </div>
      </ElCol>
    </ElRow>

    <ElRow :gutter="20">
      <ElCol :span="12">
        <div class="form-item">
          <label>智能体类型</label>
          <ElSelect v-model="props.agent.agentType" style="width: 100%">
            <ElOption
              v-for="option in agentTypeOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </ElSelect>
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>ReAct 最大循环轮数</label>
          <ElInputNumber
            v-model="props.agent.reactMaxIterations"
            :min="1"
            :step="1"
            placeholder="继承平台配置"
            style="width: 100%"
          />
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>最大模型调用次数</label>
          <ElInputNumber
            v-model="props.agent.maxModelCalls"
            :min="1"
            :step="1"
            placeholder="继承平台配置"
            style="width: 100%"
          />
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>最大工具调用次数</label>
          <ElInputNumber
            v-model="props.agent.maxToolCalls"
            :min="1"
            :step="1"
            placeholder="继承平台配置"
            style="width: 100%"
          />
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>最大 Prompt Token</label>
          <ElInputNumber
            v-model="props.agent.maxPromptTokens"
            :min="1"
            :step="1000"
            placeholder="继承平台配置"
            style="width: 100%"
          />
        </div>
      </ElCol>
    </ElRow>

    <ElRow :gutter="20" class="temporal-policy-row">
      <ElCol :span="12">
        <div class="form-item">
          <label>时间解释时区</label>
          <ElInput v-model="temporalPolicy.zoneId" placeholder="例如 Asia/Shanghai" />
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>语言区域</label>
          <ElInput v-model="temporalPolicy.locale" placeholder="例如 zh-CN" />
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>每周起始日</label>
          <ElSelect v-model="temporalPolicy.weekStartsOn" style="width: 100%">
            <ElOption label="星期一" value="MONDAY" />
            <ElOption label="星期日" value="SUNDAY" />
          </ElSelect>
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>歧义时间处理</label>
          <ElSelect v-model="temporalPolicy.ambiguityStrategy" style="width: 100%">
            <ElOption label="询问用户" value="ASK" />
          </ElSelect>
        </div>
      </ElCol>
    </ElRow>

    <ElRow :gutter="20">
      <ElCol :span="12">
        <div class="form-item">
          <label>总运行超时（秒）</label>
          <ElInputNumber v-model="props.agent.runtimeTimeoutSeconds" :min="1" :step="10" style="width: 100%" />
        </div>
      </ElCol>
    </ElRow>

    <ElRow :gutter="20">
      <ElCol :span="24">
        <div class="form-item">
          <label>描述</label>
          <ElInput v-model="props.agent.description" :rows="4" type="textarea" placeholder="请输入智能体描述" />
        </div>
      </ElCol>
    </ElRow>

    <ElRow :gutter="20">
      <ElCol :span="24">
        <div class="form-item">
          <label>{{ promptLabel }}</label>
          <ElInput v-model="props.agent.prompt" :rows="4" type="textarea" :placeholder="promptPlaceholder" />
        </div>
      </ElCol>
    </ElRow>

    <ElRow :gutter="20">
      <ElCol :span="24">
        <div class="form-item">
          <label>标签</label>
          <ElInput v-model="props.agent.tags" placeholder="多个标签用逗号分隔" />
        </div>
      </ElCol>
    </ElRow>

    <ElRow :gutter="20">
      <ElCol :span="12">
        <div class="form-item">
          <label>创建时间</label>
          <ElInput disabled :model-value="formattedCreateTime"></ElInput>
        </div>
      </ElCol>
      <ElCol :span="12">
        <div class="form-item">
          <label>更新时间</label>
          <ElInput disabled :model-value="formattedUpdateTime"></ElInput>
        </div>
      </ElCol>
    </ElRow>

    <div class="button-group">
      <ElButton class="save-action action-button" @click="updateAgent">
        <ElIcon class="button-glyph"><component :is="Edit" /></ElIcon>
        <span>保存</span>
      </ElButton>
      <ElButton type="danger" class="delete-action action-button" @click="handleDeleteAgent">
        <ElIcon class="button-glyph"><component :is="Delete" /></ElIcon>
        <span>删除智能体</span>
      </ElButton>
    </div>
  </div>
</template>

<script lang="ts">
import { computed, defineComponent } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, Edit } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import agentService, { type Agent } from '@/views/ai-agent/services/agent';
import { AGENT_TYPE, AGENT_TYPE_OPTIONS, normalizeAgentType } from '@/views/ai-agent/constants/agentTypes';

export default defineComponent({
  name: 'AgentBaseSetting',
  props: {
    agent: {
      type: Object as () => Agent,
      required: true
    }
  },
  setup(props) {
    const router = useRouter();
    const temporalPolicy = computed(() => {
      if (!props.agent.temporalPolicy) {
        props.agent.temporalPolicy = {};
      }
      return props.agent.temporalPolicy;
    });
    const isPublished = computed(() => props.agent.status === 'published');
    const isOrchestrator = computed(() => normalizeAgentType(props.agent.agentType) === AGENT_TYPE.ORCHESTRATOR);
    const promptLabel = computed(() => (isOrchestrator.value ? '编排说明' : '系统提示词'));
    const promptPlaceholder = computed(() =>
      isOrchestrator.value ? '请输入路由、澄清和结果返回约束' : '请输入系统提示词'
    );
    const editStatusHint = computed(() => {
      if (props.agent.status === 'published') {
        return '维护智能体名称、类型、描述和提示词。当前已发布，保存后会立即影响线上运行与协作调用。';
      }
      if (props.agent.status === 'offline') {
        return '维护智能体名称、类型、描述和提示词。当前已下线，保存后仍保持下线，可在详情页重新发布。';
      }
      return '维护智能体名称、类型、描述和提示词。当前待发布，保存后仍保持待发布。';
    });

    const mergeAgent = (agent: Agent | null) => {
      if (!agent) {
        return false;
      }
      Object.assign(props.agent, agent);
      return true;
    };

    const updateAgent = async () => {
      if (isPublished.value) {
        try {
          await ElMessageBox.confirm(
            '当前智能体已发布，保存后会立即影响运行页和协作调用。确定继续保存吗？',
            '保存已发布智能体',
            {
              confirmButtonText: '继续保存',
              cancelButtonText: '取消',
              type: 'warning',
              dangerouslyUseHTMLString: false
            }
          );
        } catch {
          return;
        }
      }

      try {
        const agent = await agentService.update(props.agent.id, props.agent);
        if (agent === null) {
          console.error('更新智能体失败', agent);
          ElMessage.error('更新失败：未知错误');
        } else {
          mergeAgent(agent);
          ElMessage.success('更新成功');
        }
      } catch (e) {
        console.error('更新智能体失败', e);
        ElMessage.error(`更新失败：${e instanceof Error ? e.message : '未知错误'}`);
      }
    };

    const handleDeleteAgent = async () => {
      try {
        await ElMessageBox.confirm(`确定要删除智能体"${props.agent.name}"吗？删除后将无法恢复。`, '删除智能体', {
          confirmButtonText: '确定删除',
          cancelButtonText: '取消',
          type: 'warning',
          dangerouslyUseHTMLString: false
        });

        const result = await agentService.delete(props.agent.id);
        if (result) {
          ElMessage.success('智能体已删除');
          await router.push('/ai-agent/agents');
        } else {
          ElMessage.error('删除失败：智能体不存在');
        }
      } catch {
        // 用户取消删除时不提示错误。
      }
    };

    const formatDateTime = (dateString?: string | Date): string => {
      if (!dateString) return '-';
      const date = new Date(dateString);
      return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      });
    };

    const formattedCreateTime = computed(() => formatDateTime(props.agent.createTime));
    const formattedUpdateTime = computed(() => formatDateTime(props.agent.lastModifyTime ?? props.agent.updateTime));

    return {
      Edit,
      Delete,
      props,
      editStatusHint,
      promptLabel,
      promptPlaceholder,
      temporalPolicy,
      agentTypeOptions: AGENT_TYPE_OPTIONS,
      updateAgent,
      handleDeleteAgent,
      formattedCreateTime,
      formattedUpdateTime
    };
  }
});
</script>

<style scoped>
.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 15px;
}

.panel-header h2 {
  margin: 0 0 5px;
  font-size: 20px;
}

.panel-header p {
  margin: 0;
  font-size: 13px;
  color: gray;
}

.form-grid {
  margin-top: 0;
}

.form-item {
  margin-bottom: 22px;
}

.form-item label {
  display: block;
  margin-bottom: 10px;
  font-size: 14px;
}

.form-switch {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 15px;
}

.button-group {
  display: flex;
  gap: 12px;
  margin-top: 10px;
  justify-content: end;
  padding: 18px 20px;
}

.button-group :deep(.el-button) {
  min-width: 120px;
}
</style>
