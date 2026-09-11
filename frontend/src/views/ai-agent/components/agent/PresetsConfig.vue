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
  <div>
    <div style="margin-bottom: 20px">
      <h2 class="font-size-20px">预设问题管理</h2>
    </div>
    <ElDivider />

    <div style="margin-bottom: 30px">
      <ElRow style="display: flex; justify-content: space-between; align-items: center">
        <ElCol :span="12">
          <h3 class="font-size-16px">预设问题列表</h3>
        </ElCol>
        <ElCol :span="12" style="text-align: right">
          <ElButton type="primary" class="create-action action-button" @click="openCreateDialog">
            <ElIcon class="button-glyph"><component :is="ChatDotSquare" /></ElIcon>
            <span>添加问题</span>
          </ElButton>
        </ElCol>
      </ElRow>
    </div>

    <ElTable :data="presetQuestionList" style="width: 100%" border>
      <ElTableColumn prop="question" label="问题" min-width="250px" show-overflow-tooltip />
      <ElTableColumn prop="sortOrder" label="排序" min-width="80px" />
      <ElTableColumn label="状态" min-width="80px">
        <template #default="scope">
          <ElTag :type="scope.row.isActive ? 'success' : 'info'" round>
            {{ scope.row.isActive ? '启用' : '禁用' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="createTime" label="创建时间" min-width="150px" />
      <ElTableColumn label="操作" width="96px">
        <template #default="scope">
          <div class="table-actions">
            <ElTooltip content="编辑" placement="top">
              <ElButton class="icon-action action-edit" text aria-label="编辑" @click="editQuestion(scope.row)">
                <ElIcon><component :is="Edit" /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip content="删除" placement="top">
              <ElButton class="icon-action action-delete" text aria-label="删除" @click="deleteQuestion(scope.row)">
                <ElIcon><component :is="Delete" /></ElIcon>
              </ElButton>
            </ElTooltip>
          </div>
        </template>
      </ElTableColumn>
    </ElTable>
  </div>

  <!-- 添加/编辑预设问题Dialog -->
  <ElDialog v-model="dialogVisible" :title="isEdit ? '编辑预设问题' : '添加预设问题'" width="600">
    <ElForm ref="questionFormRef" :model="questionForm" label-width="80px">
      <ElFormItem label="问题" prop="question" required>
        <ElInput v-model="questionForm.question" type="textarea" :rows="4" placeholder="请输入预设问题" />
      </ElFormItem>

      <ElFormItem label="排序" prop="sortOrder">
        <ElInputNumber v-model="questionForm.sortOrder" :min="0" controls-position="right" />
      </ElFormItem>

      <ElFormItem label="状态" prop="isActive">
        <ElSwitch v-model="questionForm.isActive" />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <div style="text-align: right">
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" @click="saveQuestion">
          {{ isEdit ? '更新' : '创建' }}
        </ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script lang="ts">
import type { Ref } from 'vue';
import { defineComponent, ref, onMounted } from 'vue';
import { ChatDotSquare, Delete, Edit } from '@element-plus/icons-vue';
import type { PresetQuestion, PresetQuestionDTO } from '@/views/ai-agent/services/presetQuestion';
import presetQuestionService from '@/views/ai-agent/services/presetQuestion';
import { ElMessage, ElMessageBox } from 'element-plus';

export default defineComponent({
  name: 'AgentPresetsConfig',
  props: {
    agentId: {
      type: String,
      required: true
    }
  },
  setup(props) {
    const presetQuestionList: Ref<PresetQuestion[]> = ref([]);
    const dialogVisible: Ref<boolean> = ref(false);
    const isEdit: Ref<boolean> = ref(false);
    const questionForm: Ref<PresetQuestion> = ref({
      agentId: props.agentId,
      question: '',
      sortOrder: 0,
      isActive: true
    });
    const currentEditId: Ref<string | null> = ref(null);

    const openCreateDialog = () => {
      isEdit.value = false;
      questionForm.value = {
        agentId: props.agentId,
        question: '',
        sortOrder: 0,
        isActive: true
      };
      dialogVisible.value = true;
    };

    const loadPresetQuestions = async () => {
      try {
        const list = await presetQuestionService.list(props.agentId);
        presetQuestionList.value = Array.isArray(list) ? list : [];
      } catch (error) {
        ElMessage.error('加载预设问题列表失败');
        console.error('加载预设问题失败:', error);
      }
    };

    const editQuestion = (question: PresetQuestion) => {
      isEdit.value = true;
      currentEditId.value = question.id || null;
      questionForm.value = { ...question };
      dialogVisible.value = true;
    };

    const deleteQuestion = async (question: PresetQuestion) => {
      if (!question.id) return;

      try {
        await ElMessageBox.confirm(`确定要删除预设问题 "${question.question.substring(0, 50)}..." 吗？`, '确认删除', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });

        const result = await presetQuestionService.delete(props.agentId, question.id);
        if (result) {
          ElMessage.success('删除成功');
          await loadPresetQuestions();
        } else {
          ElMessage.error('删除失败');
        }
      } catch {
        // 用户点击取消按钮，忽略此错误
      }
    };

    const saveQuestion = async () => {
      try {
        if (!questionForm.value.question || questionForm.value.question.trim() === '') {
          ElMessage.error('请输入预设问题');
          return;
        }

        let questionsToSave: PresetQuestionDTO[] = [];

        if (isEdit.value && currentEditId.value) {
          questionsToSave = presetQuestionList.value.map(q => {
            const dto: PresetQuestionDTO = {
              question: q.id === currentEditId.value ? questionForm.value.question : q.question
            };
            if (q.id === currentEditId.value) {
              dto.isActive = questionForm.value.isActive === true;
            } else {
              dto.isActive = q.isActive === true;
            }
            return dto;
          });
        } else {
          questionsToSave = [
            ...presetQuestionList.value.map(q => ({
              question: q.question,
              isActive: q.isActive === true
            })),
            {
              question: questionForm.value.question,
              isActive: questionForm.value.isActive === true
            }
          ];
        }

        console.log('发送的数据:', JSON.stringify(questionsToSave));

        const result = await presetQuestionService.batchSave(props.agentId, questionsToSave);
        if (result) {
          ElMessage.success(isEdit.value ? '更新成功' : '创建成功');
        } else {
          ElMessage.error(isEdit.value ? '更新失败' : '创建失败');
          return;
        }

        dialogVisible.value = false;
        await loadPresetQuestions();
      } catch (error) {
        ElMessage.error(`${isEdit.value ? '更新' : '创建'}失败`);
        console.error('保存预设问题失败:', error);
      }
    };

    onMounted(() => {
      loadPresetQuestions();
    });

    return {
      ChatDotSquare,
      Delete,
      Edit,
      presetQuestionList,
      dialogVisible,
      isEdit,
      questionForm,
      openCreateDialog,
      editQuestion,
      deleteQuestion,
      saveQuestion
    };
  }
});
</script>

<style scoped></style>
