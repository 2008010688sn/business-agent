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
  <!-- todo: 添加分页 -->
  <div class="business-knowledge-config">
    <div class="business-knowledge-header">
      <h2>业务知识管理</h2>
      <ElDivider />
    </div>

    <div class="business-knowledge-toolbar">
      <ElRow class="business-knowledge-toolbar-row">
        <ElCol :span="12">
          <h3>业务知识列表</h3>
        </ElCol>
        <ElCol :span="12" class="business-knowledge-actions">
          <ElInput
            v-model="searchKeyword"
            placeholder="请输入关键词，并按回车搜索"
            class="business-knowledge-search"
            clearable
            @clear="handleSearch"
            @keyup.enter="handleSearch"
          >
            <template #prefix>
              <ElIcon><SearchIcon /></ElIcon>
            </template>
          </ElInput>
          <ElButton v-if="!refreshLoading" @click="refreshVectorStore">
            <ElIcon class="button-glyph"><component :is="Document" /></ElIcon>
            <span>同步到向量库</span>
          </ElButton>
          <ElButton v-else type="info" loading>
            <ElIcon class="button-glyph"><component :is="Document" /></ElIcon>
            <span>同步中...</span>
          </ElButton>
          <ElButton type="primary" class="create-action action-button" @click="openCreateDialog">
            <ElIcon class="button-glyph"><component :is="Document" /></ElIcon>
            <span>添加知识</span>
          </ElButton>
        </ElCol>
      </ElRow>
    </div>

    <ElTable :data="businessKnowledgeList" class="business-knowledge-table" border>
      <ElTableColumn prop="businessTerm" label="业务名词" min-width="120px" />
      <ElTableColumn prop="description" label="描述" min-width="150px" />
      <ElTableColumn prop="synonyms" label="同义词" min-width="120px" />
      <ElTableColumn label="向量化状态" min-width="120px">
        <template #default="scope">
          <ElTag :type="getVectorStatusType(scope.row.embeddingStatus)" round>
            {{ scope.row.embeddingStatus || '未知' }}
            <ElTooltip
              v-if="scope.row.embeddingStatus === 'FAILED' && scope.row.errorMsg"
              :content="scope.row.errorMsg"
              placement="top"
            >
              <ElIcon class="business-knowledge-warning-icon">
                <Warning />
              </ElIcon>
            </ElTooltip>
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="是否召回" min-width="80px">
        <template #default="scope">
          <ElTag :type="scope.row.isRecall ? 'success' : 'info'" round>
            {{ scope.row.isRecall ? '是' : '否' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn prop="createdTime" label="创建时间" min-width="120px" />
      <ElTableColumn label="操作" width="188px" align="center" header-align="center">
        <template #default="scope">
          <div class="table-actions">
            <ElTooltip content="编辑" placement="top">
              <ElButton class="icon-action action-edit" text aria-label="编辑" @click="editKnowledge(scope.row)">
                <ElIcon><component :is="Edit" /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip v-if="scope.row.embeddingStatus === 'FAILED'" content="重试向量化" placement="top">
              <ElButton
                class="icon-action action-test"
                text
                aria-label="重试向量化"
                :disabled="retryLoadingMap[scope.row.id]"
                @click="retryEmbedding(scope.row)"
              >
                <ElIcon :class="{ 'is-loading': retryLoadingMap[scope.row.id] }">
                  <component :is="retryLoadingMap[scope.row.id] ? Loading : Refresh" />
                </ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip v-if="scope.row.isRecall" content="取消召回" placement="top">
              <ElButton
                class="icon-action action-detail"
                text
                aria-label="取消召回"
                @click="toggleRecall(scope.row, false)"
              >
                <ElIcon><component :is="Close" /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip v-else content="设为召回" placement="top">
              <ElButton
                class="icon-action action-enable"
                text
                aria-label="设为召回"
                @click="toggleRecall(scope.row, true)"
              >
                <ElIcon><component :is="Check" /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip content="删除" placement="top">
              <ElButton class="icon-action action-delete" text aria-label="删除" @click="deleteKnowledge(scope.row)">
                <ElIcon><component :is="Delete" /></ElIcon>
              </ElButton>
            </ElTooltip>
          </div>
        </template>
      </ElTableColumn>
    </ElTable>
  </div>

  <!-- 添加/编辑业务知识Dialog -->
  <ElDialog v-model="dialogVisible" :title="isEdit ? '编辑业务知识' : '添加业务知识'" width="800">
    <ElForm ref="knowledgeFormRef" :model="knowledgeForm" label-width="100px">
      <ElFormItem label="业务名词" prop="businessTerm" required>
        <ElInput v-model="knowledgeForm.businessTerm" placeholder="请输入业务名词" />
      </ElFormItem>

      <ElFormItem label="描述" prop="description" required>
        <ElInput v-model="knowledgeForm.description" type="textarea" :rows="3" placeholder="请输入业务知识描述" />
      </ElFormItem>

      <ElFormItem label="同义词" prop="synonyms">
        <ElInput
          v-model="knowledgeForm.synonyms"
          type="textarea"
          :rows="2"
          placeholder="请输入同义词，多个同义词用逗号分隔"
        />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <div class="business-knowledge-dialog-footer">
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saveLoading" :disabled="saveLoading" @click="saveKnowledge">
          {{ isEdit ? '更新' : '创建' }}
        </ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script lang="ts">
import type { Ref } from 'vue';
import { defineComponent, ref, onMounted } from 'vue';
import {
  Check,
  Close,
  Delete,
  Document,
  Edit,
  Loading,
  Refresh,
  Search as SearchIcon,
  Warning
} from '@element-plus/icons-vue';
import type {
  BusinessKnowledgeVO,
  CreateBusinessKnowledgeDTO,
  UpdateBusinessKnowledgeDTO
} from '@/views/ai-agent/services/skillBusinessKnowledge';
import skillBusinessKnowledgeService from '@/views/ai-agent/services/skillBusinessKnowledge';
import { ElMessage, ElMessageBox } from 'element-plus';

export default defineComponent({
  name: 'SkillKnowledgeConfig',
  components: {
    SearchIcon,
    Warning
  },
  props: {
    skillId: {
      type: String,
      required: true
    }
  },
  setup(props) {
    const businessKnowledgeList: Ref<BusinessKnowledgeVO[]> = ref([]);
    const dialogVisible: Ref<boolean> = ref(false);
    const isEdit: Ref<boolean> = ref(false);
    const searchKeyword: Ref<string> = ref('');
    const knowledgeForm: Ref<BusinessKnowledgeVO> = ref({
      businessTerm: '',
      description: '',
      synonyms: '',
      isRecall: false
    } as BusinessKnowledgeVO);

    const currentEditId: Ref<string | null> = ref(null);
    const refreshLoading: Ref<boolean> = ref(false);
    const saveLoading: Ref<boolean> = ref(false);
    const retryLoadingMap: Ref<Record<string, boolean>> = ref({});

    const openCreateDialog = () => {
      isEdit.value = false;
      dialogVisible.value = true;
    };

    // 处理搜索
    const handleSearch = () => {
      loadBusinessKnowledge();
    };

    // 加载业务知识列表
    const loadBusinessKnowledge = async () => {
      try {
        businessKnowledgeList.value = await skillBusinessKnowledgeService.list(
          props.skillId,
          searchKeyword.value || undefined
        );
      } catch (error) {
        ElMessage.error('加载业务知识列表失败');
        console.error('Failed to load business knowledge:', error);
      }
    };

    // 编辑业务知识
    const editKnowledge = (knowledge: BusinessKnowledgeVO) => {
      isEdit.value = true;
      currentEditId.value = knowledge.id || null;
      knowledgeForm.value = { ...knowledge };
      dialogVisible.value = true;
    };

    // 删除业务知识
    const deleteKnowledge = async (knowledge: BusinessKnowledgeVO) => {
      if (!knowledge.id) return;

      try {
        await ElMessageBox.confirm(`确定要删除业务知识 "${knowledge.businessTerm}" 吗？`, '确认删除', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });

        const result = await skillBusinessKnowledgeService.delete(props.skillId, knowledge.id);
        if (result) {
          ElMessage.success('删除成功');
          await loadBusinessKnowledge();
        } else {
          ElMessage.error('删除失败');
        }
      } catch {
        // 用户取消操作时不显示错误消息
      }
    };

    // 切换召回状态
    const toggleRecall = async (knowledge: BusinessKnowledgeVO, isRecall: boolean) => {
      if (!knowledge.id) return;

      try {
        const result = await skillBusinessKnowledgeService.setRecall(props.skillId, knowledge.id, isRecall);
        if (result) {
          ElMessage.success(`${isRecall ? '设为召回' : '取消召回'}成功`);
          knowledge.isRecall = isRecall;
        } else {
          ElMessage.error(`${isRecall ? '设为召回' : '取消召回'}失败`);
        }
      } catch (error) {
        ElMessage.error(`${isRecall ? '设为召回' : '取消召回'}失败`);
        console.error('Failed to toggle recall:', error);
      }
    };

    // 保存业务知识
    const saveKnowledge = async () => {
      saveLoading.value = true;
      try {
        if (isEdit.value && currentEditId.value) {
          // 更新操作使用 UpdateBusinessKnowledgeDTO
          const updateData: UpdateBusinessKnowledgeDTO = {
            businessTerm: knowledgeForm.value.businessTerm,
            description: knowledgeForm.value.description,
            synonyms: knowledgeForm.value.synonyms
          };

          const result = await skillBusinessKnowledgeService.update(props.skillId, currentEditId.value, updateData);
          if (result) {
            ElMessage.success('更新成功');
          } else {
            ElMessage.error('更新失败');
            return;
          }
        } else {
          // 创建操作使用 CreateBusinessKnowledgeDTO
          const createData: CreateBusinessKnowledgeDTO = {
            businessTerm: knowledgeForm.value.businessTerm,
            description: knowledgeForm.value.description,
            synonyms: knowledgeForm.value.synonyms,
            isRecall: knowledgeForm.value.isRecall
          };

          await skillBusinessKnowledgeService.create(props.skillId, createData);
          ElMessage.success('创建成功');
        }

        dialogVisible.value = false;
        await loadBusinessKnowledge();
      } catch (error) {
        ElMessage.error(`${isEdit.value ? '更新' : '创建'}失败`);
        console.error('Failed to save knowledge:', error);
      } finally {
        saveLoading.value = false;
      }
    };

    // 刷新向量存储
    const refreshVectorStore = async () => {
      try {
        await ElMessageBox.confirm(
          '如果所有向量状态正常，即无需同步。确定要清除现有数据并开始重新同步吗？',
          '确认同步',
          {
            confirmButtonText: '确定',
            cancelButtonText: '取消',
            type: 'warning'
          }
        );

        refreshLoading.value = true;
        const result = await skillBusinessKnowledgeService.refreshVectorStore(props.skillId);
        if (result) {
          ElMessage.success('同步到向量库成功');
        } else {
          ElMessage.error('同步到向量库失败');
        }
      } catch (error) {
        if (error !== 'cancel') {
          ElMessage.error('同步到向量库失败');
          console.error('Failed to refresh vector store:', error);
        }
      } finally {
        refreshLoading.value = false;
      }
    };

    // 重试向量化
    const retryEmbedding = async (knowledge: BusinessKnowledgeVO) => {
      if (!knowledge.id) return;

      try {
        // 设置加载状态为true
        retryLoadingMap.value[knowledge.id] = true;

        const result = await skillBusinessKnowledgeService.retryEmbedding(props.skillId, knowledge.id);
        if (result) {
          ElMessage.success('重试向量化成功');
          // 刷新列表以更新状态
          await loadBusinessKnowledge();
        } else {
          ElMessage.error('重试向量化失败');
        }
      } catch (error) {
        ElMessage.error('重试向量化失败');
        console.error('Failed to retry vectorization:', error);
      } finally {
        // 无论成功还是失败，都将加载状态设置为false
        retryLoadingMap.value[knowledge.id] = false;
      }
    };

    // 获取向量化状态对应的标签类型
    const getVectorStatusType = (status?: string): string => {
      switch (status) {
        case 'COMPLETED':
          return 'success';
        case 'FAILED':
          return 'danger';
        case 'PENDING':
          return 'warning';
        case 'PROCESSING':
          return 'primary';
        default:
          return 'info';
      }
    };

    onMounted(() => {
      loadBusinessKnowledge();
    });

    return {
      SearchIcon,
      Document,
      Check,
      Close,
      Delete,
      Edit,
      Loading,
      Refresh,
      businessKnowledgeList,
      dialogVisible,
      isEdit,
      searchKeyword,
      knowledgeForm,
      refreshLoading,
      saveLoading,
      retryLoadingMap,
      openCreateDialog,
      editKnowledge,
      deleteKnowledge,
      toggleRecall,
      saveKnowledge,
      handleSearch,
      refreshVectorStore,
      retryEmbedding,
      getVectorStatusType
    };
  }
});
</script>

<style scoped>
.business-knowledge-config {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 0;
}

.business-knowledge-header h2 {
  font-size: 20px;
}

:deep(.el-divider) {
  margin-bottom: 0px !important;
}

.business-knowledge-toolbar {
  overflow: hidden;
}

.business-knowledge-toolbar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
}

.business-knowledge-toolbar h3 {
  margin: 0;
  font-size: 16px;
}

.business-knowledge-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.business-knowledge-search {
  width: 260px;
}

.business-knowledge-table {
  width: 100%;
}

.business-knowledge-warning-icon {
  margin-left: 4px;
}

:deep(.el-table) {
  overflow: hidden;
  border-radius: 0 0 8px 8px;
  box-shadow: 0 10px 24px rgba(31, 63, 43, 0.06);
}

:deep(.el-table__header th) {
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-primary);
  font-weight: 650;
}

:deep(.el-table__cell) {
  padding: 10px 0;
}

.table-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  width: 100%;
}

:deep(.el-tag) {
  font-weight: 600;
}

:deep(.el-dialog__body) {
  padding-top: 16px;
}

:deep(.el-form-item__label) {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.business-knowledge-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
