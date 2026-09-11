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
  <div class="preset-questions-wrapper" :class="[`preset-questions-${variant}`]">
    <div class="preset-questions-container">
      <ElButton
        v-if="collapsible"
        native-type="button"
        class="questions-toggle"
        :class="{ expanded: questionsExpanded }"
        :aria-expanded="questionsExpanded"
        @click="toggleQuestions"
      >
        <span class="questions-toggle-main">
          <ElIcon class="header-icon"><ChatLineRound /></ElIcon>
          <span class="header-title">预设问题</span>
          <span v-if="!loading && activeQuestions.length > 0" class="questions-count">
            {{ activeQuestions.length }}
          </span>
        </span>
        <span class="questions-toggle-action">
          {{ loading ? '加载中' : questionsExpanded ? '收起' : '展开' }}
          <ElIcon class="questions-toggle-icon"><ArrowRight /></ElIcon>
        </span>
      </ElButton>
      <div v-else class="questions-header">
        <ElIcon class="header-icon"><ChatLineRound /></ElIcon>
        <span class="header-title">预设问题</span>
      </div>

      <div v-if="!collapsible || questionsExpanded" class="questions-content">
        <div v-if="loading" class="questions-loading">
          <ElIcon class="is-loading"><Loading /></ElIcon>
          <span>加载中...</span>
        </div>

        <div v-else-if="activeQuestions.length === 0" class="questions-empty">
          <span>暂无预设问题</span>
        </div>

        <div v-else class="questions-list">
          <div
            v-for="question in activeQuestions"
            :key="question.id"
            class="question-item"
            @click="handleQuestionClick(question)"
          >
            <span class="question-text">{{ question.question }}</span>
            <ElIcon class="question-arrow"><ArrowRight /></ElIcon>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import type { PropType } from 'vue';
import { defineComponent, ref, computed, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { ChatLineRound, ArrowRight, Loading } from '@element-plus/icons-vue';
import PresetQuestionService, { type PresetQuestion } from '@/views/ai-agent/services/presetQuestion';

export default defineComponent({
  name: 'PresetQuestions',
  components: {
    ChatLineRound,
    ArrowRight,
    Loading
  },
  props: {
    agentId: {
      type: String,
      required: true
    },
    variant: {
      type: String as PropType<'card' | 'inline'>,
      default: 'card'
    },
    collapsible: {
      type: Boolean,
      default: false
    },
    onQuestionClick: {
      type: Function as PropType<(question: string) => void>,
      required: true
    }
  },
  setup(props) {
    const questions = ref<PresetQuestion[]>([]);
    const loading = ref(false);
    const questionsExpanded = ref(!props.collapsible);
    let loadRequestId = 0;
    const activeQuestions = computed(() => {
      const list = Array.isArray(questions.value) ? questions.value : [];
      return list.filter(q => q.isActive !== false);
    });

    const loadPresetQuestions = async () => {
      const currentRequestId = ++loadRequestId;
      questions.value = [];
      loading.value = true;
      try {
        const nextQuestions = await PresetQuestionService.list(props.agentId);
        if (currentRequestId === loadRequestId) {
          questions.value = nextQuestions;
        }
      } catch (error) {
        ElMessage.error('加载预设问题失败');
      } finally {
        if (currentRequestId === loadRequestId) {
          loading.value = false;
        }
      }
    };

    const handleQuestionClick = (question: PresetQuestion) => {
      if (props.onQuestionClick) {
        props.onQuestionClick(question.question);
      }
    };
    const toggleQuestions = () => {
      questionsExpanded.value = !questionsExpanded.value;
    };

    watch(() => props.agentId, loadPresetQuestions, { immediate: true });

    return {
      questions,
      loading,
      activeQuestions,
      questionsExpanded,
      handleQuestionClick,
      toggleQuestions
    };
  }
});
</script>

<style scoped>
.preset-questions-wrapper {
  margin-bottom: 16px;
}

.preset-questions-inline {
  margin-bottom: 0;
}

.preset-questions-container {
  background: white;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 12px 16px;
}

.preset-questions-inline .preset-questions-container {
  padding: 0;
  background: transparent;
  border: 0;
}

.questions-toggle {
  appearance: none;
  width: 100%;
  min-height: 34px;
  padding: 7px 10px;
  border: 1px dashed #d6e2d0;
  border-radius: 8px;
  background: #f7fbf7;
  color: #45614f;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  transition:
    background 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.questions-toggle:hover {
  background: #eef7eb;
  border-color: #b8d1b2;
  color: #167243;
}

.questions-toggle-main,
.questions-toggle-action {
  display: inline-flex;
  align-items: center;
  min-width: 0;
}

.questions-toggle-main {
  gap: 7px;
}

.questions-toggle-action {
  flex: 0 0 auto;
  gap: 4px;
  color: #167243;
  font-size: 13px;
}

.questions-count {
  min-width: 20px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #e8f3e8;
  color: #42664b;
  font-size: 12px;
  line-height: 18px;
  text-align: center;
}

.questions-toggle-icon {
  transition: transform 0.2s ease;
}

.questions-toggle.expanded .questions-toggle-icon {
  transform: rotate(90deg);
}

.preset-questions-inline .questions-content {
  margin-top: 8px;
}

.questions-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 0;
  color: #909399;
  font-size: 13px;
}

.questions-loading .el-icon {
  font-size: 16px;
  color: #167243;
}

.questions-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12px 0;
  color: #909399;
  font-size: 13px;
}

.questions-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}

.preset-questions-inline .questions-header {
  margin-bottom: 8px;
  padding-bottom: 0;
  border-bottom: 0;
}

.header-icon {
  font-size: 16px;
  color: #167243;
}

.header-title {
  font-size: 14px;
  font-weight: 500;
  color: #606266;
}

.questions-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-height: calc(3 * (28px + 8px));
  overflow-y: auto;
}

.preset-questions-inline .questions-list {
  max-height: none;
  overflow: visible;
}

.question-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: #f8f9fa;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
  max-width: calc(50% - 4px);
}

.preset-questions-inline .question-item {
  max-width: min(100%, 320px);
  background: #f7fbf7;
  border-color: #dce7d7;
  border-radius: 999px;
}

.question-item:hover {
  background: #edf8ef;
  border-color: #167243;
}

.preset-questions-inline .question-item:hover {
  background: #eef7eb;
}

.question-item:active {
  transform: translateY(0);
}

.question-text {
  flex: 1;
  font-size: 13px;
  color: #303133;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.question-item:hover .question-text {
  color: #167243;
}

.question-arrow {
  flex-shrink: 0;
  font-size: 14px;
  color: #c0c4cc;
  transition: all 0.2s ease;
}

.question-item:hover .question-arrow {
  color: #167243;
  transform: translateX(2px);
}
</style>
