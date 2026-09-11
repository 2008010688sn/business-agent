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
  <div style="padding: 16px">
    <div style="margin-bottom: 20px">
      <h2 class="font-size-20px">语义模型管理</h2>
    </div>
    <ElDivider />

    <div style="margin-bottom: 30px">
      <ElRow style="display: flex; justify-content: space-between; align-items: center">
        <ElCol :span="12">
          <h3 class="font-size-16px">语义模型列表</h3>
          <ElButton
            v-if="selectedModels.length > 0"
            :icon="Delete"
            plain
            size="default"
            style="margin-left: 10px"
            type="danger"
            @click="batchDeleteModels"
          >
            批量删除 ({{ selectedModels.length }})
          </ElButton>
        </ElCol>
        <ElCol :span="12" style="text-align: right">
          <ElInput
            v-model="searchKeyword"
            clearable
            placeholder="请输入关键词后回车搜索"
            style="width: 250px; margin-right: 10px"
            @clear="handleSearch"
            @keyup.enter="handleSearch"
          >
            <template #prefix>
              <ElIcon><SearchIcon /></ElIcon>
            </template>
          </ElInput>
          <ElButton @click="openBatchImportDialog">
            <ElIcon><Upload /></ElIcon>
            <span>批量导入</span>
          </ElButton>
          <ElButton class="create-action action-button" type="primary" @click="openCreateDialog">
            <ElIcon class="button-glyph"><component :is="DataLine" /></ElIcon>
            <span>添加语义模型</span>
          </ElButton>
        </ElCol>
      </ElRow>
    </div>

    <ElTable :data="semanticModelList" border style="width: 100%" @selection-change="handleSelectionChange">
      <ElTableColumn type="selection" width="55" />
      <ElTableColumn label="表名" min-width="120px" prop="tableName" />
      <ElTableColumn label="字段名" min-width="120px" prop="columnName" />
      <ElTableColumn label="业务名称" min-width="120px" prop="businessName" />
      <ElTableColumn label="同义词" min-width="120px" prop="synonyms" />
      <ElTableColumn label="数据类型" min-width="80px" prop="dataType" />
      <ElTableColumn label="状态" min-width="80px">
        <template #default="scope">
          <ElTag :type="scope.row.status ? 'success' : 'info'" round>
            {{ scope.row.status ? '启用' : '停用' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="创建时间" min-width="160px">
        <template #default="scope">
          {{ formatModelCreatedTime(scope.row) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="156px">
        <template #default="scope">
          <div class="table-actions">
            <ElTooltip content="编辑" placement="top">
              <ElButton
                class="icon-action action-edit"
                native-type="button"
                aria-label="编辑"
                @click="editModel(scope.row)"
              >
                <ElIcon><component :is="Edit" /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip v-if="!scope.row.status" content="启用" placement="top">
              <ElButton
                class="icon-action action-enable"
                native-type="button"
                aria-label="启用"
                @click="toggleStatus(scope.row, true)"
              >
                <ElIcon><component :is="Check" /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip v-else content="停用" placement="top">
              <ElButton
                class="icon-action action-detail"
                native-type="button"
                aria-label="停用"
                @click="toggleStatus(scope.row, false)"
              >
                <ElIcon><component :is="Close" /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip content="删除" placement="top">
              <ElButton
                class="icon-action action-delete"
                native-type="button"
                aria-label="删除"
                @click="deleteModel(scope.row)"
              >
                <ElIcon><component :is="Delete" /></ElIcon>
              </ElButton>
            </ElTooltip>
          </div>
        </template>
      </ElTableColumn>
    </ElTable>
  </div>

  <ElDialog v-model="dialogVisible" :title="isEdit ? '编辑语义模型' : '添加语义模型'" width="800">
    <ElForm ref="modelFormRef" :model="modelForm" label-width="120px">
      <ElFormItem label="表名" prop="tableName" required>
        <ElInput v-model="modelForm.tableName" :disabled="isEdit" placeholder="请输入表名" />
      </ElFormItem>

      <ElFormItem label="数据库字段名" prop="columnName" required>
        <ElInput v-model="modelForm.columnName" :disabled="isEdit" placeholder="请输入数据库字段名" />
      </ElFormItem>

      <ElFormItem label="业务名称" prop="businessName" required>
        <ElInput v-model="modelForm.businessName" placeholder="请输入业务名称" />
      </ElFormItem>

      <ElFormItem label="同义词" prop="synonyms">
        <ElInput
          v-model="modelForm.synonyms"
          :rows="2"
          placeholder="请输入同义词，多个同义词使用逗号分隔"
          type="textarea"
        />
      </ElFormItem>

      <ElFormItem label="业务描述" prop="businessDescription">
        <ElInput
          v-model="modelForm.businessDescription"
          :rows="3"
          placeholder="请输入业务描述，用于帮助模型理解字段含义"
          type="textarea"
        />
      </ElFormItem>

      <ElFormItem label="字段注释" prop="columnComment">
        <ElInput v-model="modelForm.columnComment" :rows="2" placeholder="请输入数据库字段原始注释" type="textarea" />
      </ElFormItem>

      <ElFormItem label="数据类型" prop="dataType" required>
        <ElInput v-model="modelForm.dataType" placeholder="请输入数据类型，例如 int、varchar(20)" />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <div style="text-align: right">
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" @click="saveModel">
          {{ isEdit ? '更新' : '创建' }}
        </ElButton>
      </div>
    </template>
  </ElDialog>

  <BatchImportDialog
    v-model="batchImportDialogVisible"
    :json-template="jsonTemplate"
    :on-download-excel-template="downloadExcelTemplate"
    :on-excel-import="executeExcelImport"
    :on-json-import="executeBatchImport"
    :validate-excel-file="validateExcelFile"
    :validate-json="validateJson"
    title="批量导入语义模型"
    @imported="handleBatchImported"
  />
</template>

<script lang="ts">
import { defineComponent, onMounted, ref, type Ref } from 'vue';
import { Check, Close, DataLine, Delete, Edit, Search as SearchIcon, UploadFilled } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import BatchImportDialog from './BatchImportDialog.vue';
import skillDatasourceService from '@/views/ai-agent/services/skillDatasource';
import skillSemanticModelService, {
  type SemanticModel,
  type SemanticModelAddDto,
  type SemanticModelImportItem,
  type SemanticModelUpdateDto
} from '@/views/ai-agent/services/skillSemanticModel';

type SemanticModelView = SemanticModel & {
  createTime?: string;
};

const createEmptyModel = (): SemanticModel => ({
  tableName: '',
  columnName: '',
  businessName: '',
  synonyms: '',
  businessDescription: '',
  columnComment: '',
  dataType: '',
  status: true
});

export default defineComponent({
  name: 'SkillSemanticsConfig',
  components: {
    BatchImportDialog,
    UploadFilled,
    SearchIcon
  },
  props: {
    skillId: {
      type: String,
      required: true
    }
  },
  setup(props) {
    const semanticModelList: Ref<SemanticModel[]> = ref([]);
    const dialogVisible = ref(false);
    const batchImportDialogVisible = ref(false);
    const isEdit = ref(false);
    const searchKeyword = ref('');
    const selectedModels: Ref<SemanticModel[]> = ref([]);
    const currentEditId = ref<string | null>(null);
    const modelForm: Ref<SemanticModel> = ref(createEmptyModel());

    const jsonTemplate = [
      {
        tableName: 'work_order',
        columnName: 'order_type',
        businessName: '工单类型',
        synonyms: '类型,工单种类',
        businessDesc: '用于区分工单种类，例如 1=资产工单, 2=账号工单',
        dataType: 'int'
      },
      {
        tableName: 'work_order',
        columnName: 'status',
        businessName: '工单状态',
        synonyms: '状态,处理状态',
        businessDesc: '工单当前处理状态，例如 0=待处理, 1=处理中, 2=已完成, 3=已关闭',
        dataType: 'int'
      }
    ];

    const getErrorMessage = (error: unknown, fallback: string): string => {
      if (error instanceof Error && error.message.trim()) {
        return error.message;
      }
      return fallback;
    };

    const getActiveDatasourceId = async (): Promise<string> => {
      const activeSkillDatasource = await skillDatasourceService.getActive(props.skillId);
      if (!activeSkillDatasource.datasourceId) {
        throw new Error('当前未找到启用的数据源');
      }
      return activeSkillDatasource.datasourceId;
    };

    const loadSemanticModels = async () => {
      try {
        semanticModelList.value = await skillSemanticModelService.list(props.skillId, searchKeyword.value || undefined);
      } catch (error) {
        ElMessage.error(getErrorMessage(error, '加载语义模型列表失败'));
        console.error('Failed to load semantic models:', error);
      }
    };

    const handleSearch = () => {
      loadSemanticModels();
    };

    const openCreateDialog = () => {
      isEdit.value = false;
      currentEditId.value = null;
      modelForm.value = createEmptyModel();
      dialogVisible.value = true;
    };

    const openBatchImportDialog = () => {
      batchImportDialogVisible.value = true;
    };

    const handleSelectionChange = (selection: SemanticModel[]) => {
      selectedModels.value = selection;
    };

    const editModel = (model: SemanticModel) => {
      isEdit.value = true;
      currentEditId.value = model.id || null;
      modelForm.value = { ...model };
      dialogVisible.value = true;
    };

    const deleteModel = async (model: SemanticModel) => {
      if (!model.id) {
        return;
      }

      try {
        await ElMessageBox.confirm(`确定要删除语义模型“${model.businessName}”吗？`, '确认删除', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });

        const result = await skillSemanticModelService.delete(props.skillId, model.id);
        if (!result) {
          ElMessage.error('删除失败');
          return;
        }

        ElMessage.success('删除成功');
        await loadSemanticModels();
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Failed to delete semantic model:', error);
        }
      }
    };

    const batchDeleteModels = async () => {
      if (selectedModels.value.length === 0) {
        ElMessage.warning('请先选择要删除的语义模型');
        return;
      }

      try {
        await ElMessageBox.confirm(`确定要删除选中的 ${selectedModels.value.length} 个语义模型吗？`, '确认批量删除', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });

        const ids = selectedModels.value.map(model => model.id).filter((id): id is string => id !== undefined);
        const result = await skillSemanticModelService.batchDelete(props.skillId, ids);
        if (!result) {
          ElMessage.error('批量删除失败');
          return;
        }

        ElMessage.success(`成功删除 ${ids.length} 个语义模型`);
        selectedModels.value = [];
        await loadSemanticModels();
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Failed to batch delete semantic models:', error);
        }
      }
    };

    const toggleStatus = async (model: SemanticModel, status: boolean) => {
      if (!model.id) {
        return;
      }

      try {
        const ids = [model.id];
        const result = await skillSemanticModelService.setEnabled(props.skillId, ids, status);

        if (!result) {
          ElMessage.error(`${status ? '启用' : '停用'}失败`);
          return;
        }

        ElMessage.success(`${status ? '启用' : '停用'}成功`);
        model.status = status;
      } catch (error) {
        ElMessage.error(getErrorMessage(error, `${status ? '启用' : '停用'}失败`));
        console.error('Failed to toggle status:', error);
      }
    };

    const saveModel = async () => {
      try {
        if (isEdit.value && currentEditId.value) {
          const formData: SemanticModelUpdateDto = {
            businessName: modelForm.value.businessName,
            synonyms: modelForm.value.synonyms,
            businessDescription: modelForm.value.businessDescription,
            columnComment: modelForm.value.columnComment,
            dataType: modelForm.value.dataType
          };
          const result = await skillSemanticModelService.update(props.skillId, currentEditId.value, formData);
          if (!result) {
            ElMessage.error('更新失败');
            return;
          }
          ElMessage.success('更新成功');
        } else {
          const datasourceId = await getActiveDatasourceId();
          const formData: SemanticModelAddDto = {
            datasourceId,
            tableName: modelForm.value.tableName,
            columnName: modelForm.value.columnName,
            businessName: modelForm.value.businessName,
            synonyms: modelForm.value.synonyms,
            businessDescription: modelForm.value.businessDescription,
            columnComment: modelForm.value.columnComment,
            dataType: modelForm.value.dataType
          };
          const result = await skillSemanticModelService.create(props.skillId, formData);
          if (!result) {
            ElMessage.error('创建失败');
            return;
          }
          ElMessage.success('创建成功');
        }

        dialogVisible.value = false;
        await loadSemanticModels();
      } catch (error) {
        ElMessage.error(getErrorMessage(error, isEdit.value ? '更新失败' : '创建失败'));
        console.error('Failed to save model:', error);
      }
    };

    const validateJson = (jsonText: string) => {
      try {
        const data = JSON.parse(jsonText);
        if (!Array.isArray(data)) {
          ElMessage.error('JSON 格式错误：数据必须是数组');
          return false;
        }
        if (data.length === 0) {
          ElMessage.error('导入数据不能为空');
          return false;
        }

        for (let i = 0; i < data.length; i++) {
          const item = data[i];
          if (!item.tableName || !item.columnName || !item.businessName || !item.dataType) {
            ElMessage.error(`第 ${i + 1} 条记录缺少必填字段（tableName、columnName、businessName、dataType）`);
            return false;
          }
        }

        ElMessage.success('JSON 格式校验通过');
        return true;
      } catch (error) {
        ElMessage.error(`JSON 格式错误：${(error as Error).message}`);
        return false;
      }
    };

    const executeBatchImport = async (items: SemanticModelImportItem[]) => {
      try {
        const datasourceId = await getActiveDatasourceId();
        return await skillSemanticModelService.batchImport(props.skillId, {
          datasourceId,
          items
        });
      } catch (error) {
        ElMessage.error(`批量导入失败：${getErrorMessage(error, '导入失败')}`);
        console.error('Failed to batch import:', error);
        throw error;
      }
    };

    const validateExcelFile = (file: File | null) => {
      if (!file) {
        ElMessage.error('请先选择 Excel 文件');
        return false;
      }
      return true;
    };

    const downloadExcelTemplate = async () => {
      try {
        await skillSemanticModelService.downloadTemplate(props.skillId);
        ElMessage.success('模板下载成功');
      } catch (error) {
        ElMessage.error(`模板下载失败：${getErrorMessage(error, '下载失败')}`);
        console.error('Failed to download template:', error);
      }
    };

    const executeExcelImport = async (file: File) => {
      try {
        const datasourceId = await getActiveDatasourceId();
        return await skillSemanticModelService.importExcel(props.skillId, file, datasourceId);
      } catch (error) {
        ElMessage.error(`Excel 导入失败：${getErrorMessage(error, '导入失败')}`);
        console.error('Failed to import excel:', error);
        throw error;
      }
    };

    const handleBatchImported = async () => {
      await loadSemanticModels();
    };

    const formatDateTime = (dateTime?: string) => {
      if (!dateTime) {
        return '-';
      }
      try {
        const date = new Date(dateTime);
        return date.toLocaleString('zh-CN', {
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
          hour12: false
        });
      } catch {
        return dateTime;
      }
    };

    const formatModelCreatedTime = (model: SemanticModelView) => formatDateTime(model.createdTime || model.createTime);

    onMounted(() => {
      loadSemanticModels();
    });

    return {
      SearchIcon,
      DataLine,
      Check,
      Close,
      Delete,
      Edit,
      semanticModelList,
      dialogVisible,
      batchImportDialogVisible,
      isEdit,
      searchKeyword,
      selectedModels,
      modelForm,
      jsonTemplate,
      openCreateDialog,
      openBatchImportDialog,
      handleSelectionChange,
      batchDeleteModels,
      handleSearch,
      editModel,
      deleteModel,
      toggleStatus,
      saveModel,
      validateJson,
      validateExcelFile,
      executeBatchImport,
      downloadExcelTemplate,
      executeExcelImport,
      handleBatchImported,
      formatModelCreatedTime
    };
  }
});
</script>

<style scoped></style>
