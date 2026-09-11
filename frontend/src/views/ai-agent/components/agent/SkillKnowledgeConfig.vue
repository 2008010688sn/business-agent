<!--
 * Copyright 2024-2026 the original author or authors.
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
  <section class="skill-knowledge-config">
    <div class="knowledge-toolbar">
      <ElInput
        v-model="query.title"
        clearable
        class="knowledge-search"
        placeholder="搜索知识标题"
        @clear="handleSearch"
        @keyup.enter="handleSearch"
      >
        <template #prefix>
          <ElIcon><Search /></ElIcon>
        </template>
      </ElInput>
      <div class="toolbar-actions">
        <ElTooltip content="筛选" placement="top">
          <ElButton
            :type="filterVisible ? 'primary' : ''"
            :icon="Filter"
            circle
            @click="filterVisible = !filterVisible"
          />
        </ElTooltip>
        <ElTooltip content="刷新" placement="top">
          <ElButton :icon="Refresh" circle @click="load" />
        </ElTooltip>
        <ElButton type="primary" :icon="Plus" @click="openCreate">添加知识</ElButton>
      </div>
    </div>

    <ElCollapseTransition>
      <div v-show="filterVisible" class="knowledge-filters">
        <ElSelect v-model="query.type" clearable placeholder="全部类型" @change="handleSearch">
          <ElOption label="文档" value="DOCUMENT" />
          <ElOption label="问答对" value="QA" />
          <ElOption label="常见问题" value="FAQ" />
        </ElSelect>
        <ElSelect v-model="query.embeddingStatus" clearable placeholder="全部处理状态" @change="handleSearch">
          <ElOption label="待处理" value="PENDING" />
          <ElOption label="处理中" value="PROCESSING" />
          <ElOption label="已完成" value="COMPLETED" />
          <ElOption label="失败" value="FAILED" />
        </ElSelect>
        <ElButton :icon="RefreshLeft" @click="clearFilters">清空</ElButton>
      </div>
    </ElCollapseTransition>

    <ElTable v-loading="loading" :data="rows" border row-key="id" empty-text="暂无知识库资源">
      <ElTableColumn prop="title" label="标题" min-width="190" show-overflow-tooltip />
      <ElTableColumn label="类型" width="96">
        <template #default="{ row }">{{ typeLabel(row.type) }}</template>
      </ElTableColumn>
      <ElTableColumn label="来源" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <ElButton v-if="row.filePreviewUrl" link type="primary" :icon="View" @click="preview(row)">
            {{ row.sourceFilename || '查看文档' }}
          </ElButton>
          <span v-else>{{ row.sourceFilename || '-' }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="分块策略" width="108">
        <template #default="{ row }">{{ row.type === 'DOCUMENT' ? splitterLabel(row.splitterType) : '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="处理状态" width="112">
        <template #default="{ row }">
          <ElTooltip :disabled="!row.errorMsg" :content="row.errorMsg" placement="top">
            <ElTag :type="statusType(row.embeddingStatus)" effect="light">
              <ElIcon v-if="row.embeddingStatus === 'FAILED'" class="status-icon"><Warning /></ElIcon>
              {{ statusLabel(row.embeddingStatus) }}
            </ElTag>
          </ElTooltip>
        </template>
      </ElTableColumn>
      <ElTableColumn label="召回" width="82" align="center">
        <template #default="{ row }">
          <ElSwitch
            :model-value="Boolean(row.isRecall)"
            :disabled="row.publishedReferenced"
            @change="value => toggleRecall(row, Boolean(value))"
          />
        </template>
      </ElTableColumn>
      <ElTableColumn label="版本" width="92">
        <template #default="{ row }">
          <ElTag v-if="row.publishedReferenced" type="info" effect="plain">已发布引用</ElTag>
          <span v-else>当前草稿</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="150" fixed="right" align="center">
        <template #default="{ row }">
          <div class="row-actions">
            <ElTooltip content="编辑" placement="top">
              <ElButton
                text
                :icon="Edit"
                aria-label="编辑"
                :disabled="row.publishedReferenced"
                @click="openEdit(row)"
              />
            </ElTooltip>
            <ElTooltip v-if="row.embeddingStatus === 'FAILED'" content="重试向量化" placement="top">
              <ElButton
                text
                :icon="Refresh"
                aria-label="重试向量化"
                :disabled="row.publishedReferenced || !row.isRecall"
                @click="retry(row)"
              />
            </ElTooltip>
            <ElTooltip content="删除" placement="top">
              <ElButton
                text
                type="danger"
                :icon="Delete"
                aria-label="删除"
                :disabled="row.publishedReferenced"
                @click="remove(row)"
              />
            </ElTooltip>
          </div>
        </template>
      </ElTableColumn>
    </ElTable>

    <ElPagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      class="knowledge-pagination"
      background
      layout="total, sizes, prev, pager, next, jumper"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      @size-change="load"
      @current-change="load"
    />

    <ElDialog
      v-model="dialogVisible"
      :title="editingId ? '编辑知识' : '添加知识'"
      width="min(720px, 94vw)"
      :close-on-click-modal="false"
      destroy-on-close
      @closed="resetForm"
    >
      <ElForm label-position="top">
        <ElFormItem label="知识类型" required>
          <ElSegmented
            v-model="form.type"
            :options="typeOptions"
            :disabled="Boolean(editingId)"
            @change="handleTypeChange"
          />
        </ElFormItem>
        <ElFormItem label="知识标题" required>
          <ElInput v-model="form.title" maxlength="255" show-word-limit placeholder="请输入知识标题" />
        </ElFormItem>

        <template v-if="form.type === 'DOCUMENT'">
          <ElFormItem label="分块策略" required>
            <ElSelect v-model="form.splitterType" class="full-width" :disabled="Boolean(editingId)">
              <ElOption
                v-for="option in splitterOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-if="!editingId" label="上传文件" required>
            <ElUpload
              v-model:file-list="fileList"
              class="knowledge-upload"
              drag
              accept=".pdf,.doc,.docx,.txt,.md"
              :auto-upload="false"
              :limit="1"
              :on-change="handleFileChange"
              :on-exceed="handleFileExceed"
            >
              <ElIcon class="el-icon--upload"><UploadFilled /></ElIcon>
              <div class="el-upload__text">
                拖拽文件到此处或
                <em>点击选择</em>
              </div>
              <template #tip>
                <div class="el-upload__tip">PDF、DOC、DOCX、TXT、MD，单个文件不超过 50MB</div>
              </template>
            </ElUpload>
          </ElFormItem>
          <ElFormItem v-else label="当前文件">
            <ElButton v-if="form.filePreviewUrl" link type="primary" :icon="View" @click="preview(form)">
              {{ form.sourceFilename || '查看文档' }}
            </ElButton>
            <span v-else>{{ form.sourceFilename || '文件不可预览' }}</span>
          </ElFormItem>
        </template>

        <template v-else>
          <ElFormItem label="问题" required>
            <ElInput v-model="form.question" type="textarea" :rows="3" maxlength="2000" show-word-limit />
          </ElFormItem>
          <ElFormItem label="答案" required>
            <ElInput v-model="form.content" type="textarea" :rows="7" maxlength="20000" show-word-limit />
          </ElFormItem>
        </template>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="save">{{ editingId ? '保存' : '添加并处理' }}</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import type { UploadFile, UploadUserFile } from 'element-plus';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Delete,
  Edit,
  Filter,
  Plus,
  Refresh,
  RefreshLeft,
  Search,
  UploadFilled,
  View,
  Warning
} from '@element-plus/icons-vue';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import skillKnowledgeService from '@/views/ai-agent/services/skillKnowledge';
import type { SkillKnowledge, SkillKnowledgeQuery } from '@/views/ai-agent/services/skillKnowledge';
import { suiteFileUploadApi } from '@/views/ai-agent/services/suiteFileUpload';

const MAX_UPLOAD_SIZE = 50 * 1024 * 1024;
const props = defineProps<{ skillId: string }>();
const rows = ref<SkillKnowledge[]>([]);
const total = ref(0);
const loading = ref(false);
const saving = ref(false);
const filterVisible = ref(false);
const dialogVisible = ref(false);
const editingId = ref<string>();
const selectedFile = ref<File>();
const fileList = ref<UploadUserFile[]>([]);
const query = reactive<SkillKnowledgeQuery>({ title: '', type: '', embeddingStatus: '', pageNum: 1, pageSize: 10 });
const form = reactive<SkillKnowledge>({
  type: 'DOCUMENT',
  title: '',
  question: '',
  content: '',
  splitterType: 'recursive'
});
const typeOptions = [
  { label: '文档', value: 'DOCUMENT' },
  { label: '问答对', value: 'QA' },
  { label: '常见问题', value: 'FAQ' }
];
const splitterOptions = [
  { label: '递归分块', value: 'recursive' },
  { label: 'Token 分块', value: 'token' },
  { label: '句子分块', value: 'sentence' },
  { label: '段落分块', value: 'paragraph' },
  { label: '语义分块', value: 'semantic' }
];

const load = async () => {
  if (!props.skillId) return;
  loading.value = true;
  try {
    const page = await skillKnowledgeService.page(props.skillId, query);
    rows.value = page.data;
    total.value = page.total;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '加载知识库失败'));
  } finally {
    loading.value = false;
  }
};

const handleSearch = () => {
  query.pageNum = 1;
  load();
};
const clearFilters = () => {
  query.type = '';
  query.embeddingStatus = '';
  handleSearch();
};
const resetForm = () => {
  Object.assign(form, {
    id: undefined,
    type: 'DOCUMENT',
    title: '',
    question: '',
    content: '',
    splitterType: 'recursive',
    sourceFilename: undefined,
    filePreviewUrl: undefined
  });
  editingId.value = undefined;
  selectedFile.value = undefined;
  fileList.value = [];
};
const openCreate = () => {
  resetForm();
  dialogVisible.value = true;
};
const openEdit = (row: SkillKnowledge) => {
  if (row.publishedReferenced) return;
  resetForm();
  editingId.value = row.id;
  Object.assign(form, row);
  dialogVisible.value = true;
};
const handleTypeChange = () => {
  form.question = '';
  form.content = '';
  form.splitterType = 'recursive';
  selectedFile.value = undefined;
  fileList.value = [];
};
const handleFileChange = (file: UploadFile) => {
  if (!file.raw) return;
  if (file.raw.size > MAX_UPLOAD_SIZE) {
    ElMessage.warning('文件大小不能超过 50MB');
    selectedFile.value = undefined;
    fileList.value = [];
    return;
  }
  selectedFile.value = file.raw;
  fileList.value = [file];
  if (!form.title?.trim()) form.title = file.name.replace(/\.[^.]+$/, '');
};
const handleFileExceed = () => ElMessage.warning('每条知识只能上传一个文件');

const save = async () => {
  if (!form.title?.trim()) return ElMessage.warning('请输入知识标题');
  if (form.type === 'DOCUMENT' && !editingId.value && !selectedFile.value) return ElMessage.warning('请选择文件');
  if (form.type !== 'DOCUMENT' && !form.question?.trim()) return ElMessage.warning('请输入问题');
  if (form.type !== 'DOCUMENT' && !form.content?.trim()) return ElMessage.warning('请输入答案');
  saving.value = true;
  try {
    if (editingId.value) {
      await skillKnowledgeService.update(props.skillId, editingId.value, {
        title: form.title.trim(),
        question: form.type === 'DOCUMENT' ? undefined : form.question?.trim(),
        content: form.type === 'DOCUMENT' ? undefined : form.content?.trim()
      });
      ElMessage.success('知识已更新，向量化任务已提交');
    } else {
      const payload: SkillKnowledge = {
        title: form.title.trim(),
        type: form.type,
        question: form.type === 'DOCUMENT' ? undefined : form.question?.trim(),
        content: form.type === 'DOCUMENT' ? undefined : form.content?.trim(),
        splitterType: form.type === 'DOCUMENT' ? form.splitterType : undefined
      };
      if (selectedFile.value) {
        const uploaded = await suiteFileUploadApi.upload(selectedFile.value);
        Object.assign(payload, {
          filePath: uploaded.path,
          sourceFilename: uploaded.fileName,
          fileSize: uploaded.size,
          fileType: uploaded.contentType
        });
      }
      await skillKnowledgeService.create(props.skillId, payload);
      ElMessage.success('知识已添加，向量化任务已提交');
    }
    dialogVisible.value = false;
    await load();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, editingId.value ? '更新知识失败' : '添加知识失败'));
  } finally {
    saving.value = false;
  }
};

const toggleRecall = async (row: SkillKnowledge, recalled: boolean) => {
  if (!row.id || row.publishedReferenced) return;
  try {
    await skillKnowledgeService.updateRecallStatus(props.skillId, row.id, recalled);
    row.isRecall = recalled;
    ElMessage.success(recalled ? '已加入召回' : '已取消召回');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '更新召回状态失败'));
  }
};
const retry = async (row: SkillKnowledge) => {
  if (!row.id || row.publishedReferenced) return;
  try {
    await skillKnowledgeService.retry(props.skillId, row.id);
    ElMessage.success('向量化任务已重新提交');
    await load();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '重试向量化失败'));
  }
};
const remove = async (row: SkillKnowledge) => {
  if (!row.id || row.publishedReferenced) return;
  try {
    await ElMessageBox.confirm(`确认删除“${row.title || ''}”？`, '删除知识', { type: 'warning' });
    await skillKnowledgeService.remove(props.skillId, row.id);
    ElMessage.success('知识已删除');
    if (rows.value.length === 1 && (query.pageNum || 1) > 1) query.pageNum = (query.pageNum || 1) - 1;
    await load();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(extractApiErrorMessage(error, '删除知识失败'));
  }
};
const preview = (row: SkillKnowledge) => {
  if (row.filePreviewUrl) window.open(row.filePreviewUrl, '_blank', 'noopener,noreferrer');
};
const typeLabel = (value?: string) =>
  ({ DOCUMENT: '文档', QA: '问答对', FAQ: '常见问题' })[value || ''] || value || '-';
const splitterLabel = (value?: string) => splitterOptions.find(option => option.value === value)?.label || value || '-';
const statusLabel = (value?: string) =>
  ({ PENDING: '待处理', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '失败' })[value || ''] || value || '-';
const statusType = (value?: string) =>
  ({ COMPLETED: 'success', PROCESSING: 'primary', FAILED: 'danger', PENDING: 'info' })[value || ''] as
    | 'success'
    | 'primary'
    | 'danger'
    | 'info'
    | undefined;

watch(() => props.skillId, load, { immediate: true });
</script>

<style scoped>
.skill-knowledge-config {
  min-width: 0;
}

.knowledge-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.knowledge-search {
  width: min(360px, 100%);
}

.toolbar-actions,
.knowledge-filters,
.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.knowledge-filters {
  padding: 12px;
  margin-bottom: 12px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.knowledge-filters :deep(.el-select) {
  width: 180px;
}

.status-icon {
  margin-right: 3px;
  vertical-align: -2px;
}

.knowledge-pagination {
  justify-content: flex-end;
  margin-top: 14px;
}

.full-width,
.knowledge-upload {
  width: 100%;
}

@media (max-width: 720px) {
  .knowledge-toolbar,
  .knowledge-filters {
    align-items: stretch;
    flex-direction: column;
  }

  .knowledge-search,
  .knowledge-filters :deep(.el-select) {
    width: 100%;
  }

  .toolbar-actions {
    justify-content: flex-end;
  }
}
</style>
