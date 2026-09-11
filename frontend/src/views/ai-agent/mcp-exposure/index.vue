<template>
  <BaseLayout class="mcp-exposure-shell">
    <main class="mcp-exposure-page">
      <ElCard>
        <header class="page-toolbar">
          <div>
            <h1>MCP中心</h1>
            <p>管理平台对外暴露的 MCP Tool，只把稳定的高层能力加入服务白名单。</p>
          </div>
          <div class="toolbar-actions">
            <ElButton :icon="Refresh" :loading="loading" @click="reload">刷新列表</ElButton>
            <ElButton :icon="Connection" :loading="syncing" :disabled="syncing" @click="syncRuntime">
              同步运行时
            </ElButton>
            <ElButton type="primary" @click="openEditor()">新建服务</ElButton>
          </div>
        </header>
      </ElCard>
      <ElCard class="mcp-exposure-list-card">
        <section class="filter-panel">
          <ElForm :model="filters" label-width="70px">
            <ElRow :gutter="24">
              <ElCol :span="6">
                <ElFormItem label="toolKey">
                  <ElInput v-model="filters.toolKey" clearable placeholder="toolKey" />
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="状态">
                  <ElSelect v-model="filters.status" clearable placeholder="状态">
                    <ElOption label="enabled" value="enabled" />
                    <ElOption label="disabled" value="disabled" />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElButton type="primary" :loading="loading" @click="handleSearch">
                <ElIcon><Search /></ElIcon>
                查询
              </ElButton>
              <ElButton @click="resetFilters">
                <ElIcon><Refresh /></ElIcon>
                重置
              </ElButton>
            </ElRow>
          </ElForm>
        </section>
        <div class="mcp-exposure-table-wrap">
          <ElTable v-loading="loading" :data="exposures" border stripe row-key="exposureCode" height="100%">
            <ElTableColumn prop="exposureCode" label="服务编码" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="exposureName" label="名称" min-width="160" show-overflow-tooltip />
            <ElTableColumn prop="toolKey" label="内部 Tool" min-width="210" show-overflow-tooltip />
            <ElTableColumn prop="exposedToolName" label="MCP Tool" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="riskLevel" label="风险" width="100" />
            <ElTableColumn label="用户上下文" width="110">
              <template #default="{ row }">
                <ElTag :type="row.requireUserContext === false ? 'info' : 'success'" size="small">
                  {{ row.requireUserContext === false ? '不要求' : '要求' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="status" label="状态" width="100" />
            <ElTableColumn label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <ElButton link type="primary" @click="openEditor(row)">编辑</ElButton>
                <ElButton link type="danger" @click="removeExposure(row)">删除</ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>
        <ElPagination
          v-model:current-page="pageQuery.current"
          v-model:page-size="pageQuery.size"
          class="page-pagination"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50, 100]"
          :total="pageTotal"
          @size-change="handlePageSizeChange"
          @current-change="loadExposures"
        />
      </ElCard>

      <ElDrawer v-model="editorVisible" :title="editingId ? '编辑 MCP 服务' : '新建 MCP 服务'" size="560px">
        <ElForm :model="form" label-position="top">
          <div class="grid-two">
            <ElFormItem label="服务编码"><ElInput v-model="form.exposureCode" /></ElFormItem>
            <ElFormItem label="名称"><ElInput v-model="form.exposureName" /></ElFormItem>
          </div>
          <ElFormItem label="内部 Tool">
            <ElSelect v-model="form.toolKey" filterable class="full-width" @visible-change="handleResourcesVisible">
              <ElOption
                v-for="item in resources"
                :key="item.resourceKey"
                :label="`${item.resourceName || item.resourceKey} / ${item.resourceKey}`"
                :value="item.resourceKey || ''"
              />
            </ElSelect>
          </ElFormItem>
          <div class="grid-two">
            <ElFormItem label="MCP Tool Name"><ElInput v-model="form.exposedToolName" /></ElFormItem>
            <ElFormItem label="类型"><ElInput v-model="form.exposureType" /></ElFormItem>
          </div>
          <div class="grid-two">
            <ElFormItem label="风险级别">
              <ElSelect v-model="form.riskLevel" class="full-width">
                <ElOption label="LOW" value="LOW" />
                <ElOption label="MEDIUM" value="MEDIUM" />
                <ElOption label="HIGH" value="HIGH" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="状态">
              <ElSelect v-model="form.status" class="full-width">
                <ElOption label="enabled" value="enabled" />
                <ElOption label="disabled" value="disabled" />
              </ElSelect>
            </ElFormItem>
          </div>
          <ElFormItem label="用户上下文">
            <ElSwitch v-model="form.requireUserContext" active-text="调用时要求用户上下文" />
          </ElFormItem>
          <ElFormItem label="扩展配置（JSON）">
            <JsonObjectEditor v-model="form.extConfig" :rows="8" @validity-change="extConfigValid = $event" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="editorVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="saving" :disabled="!extConfigValid" @click="saveExposure">保存</ElButton>
        </template>
      </ElDrawer>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Connection, Refresh, Search } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import toolService, { type ToolResource } from '@/views/ai-agent/services/tool';
import platformService, { type McpExposure } from '@/views/ai-agent/services/platform';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

const loading = ref(false);
const saving = ref(false);
const syncing = ref(false);
const editorVisible = ref(false);
const extConfigValid = ref(true);
const editingId = ref<string>('');
const exposures = ref<McpExposure[]>([]);
const resources = ref<ToolResource[]>([]);
const resourcesLoaded = ref(false);
const filters = reactive<Record<string, string>>({ toolKey: '', status: '' });
const pageQuery = reactive({
  current: 1,
  size: 20
});
const pageTotal = ref(0);
const form = reactive<McpExposure>(emptyExposure());

function resetFilters() {
  Object.assign(filters, {
    toolKey: '',
    status: ''
  });
  pageQuery.current = 1;
  loadExposures();
}

function handleSearch() {
  pageQuery.current = 1;
  loadExposures();
}

function handlePageSizeChange() {
  pageQuery.current = 1;
  loadExposures();
}

function emptyExposure(): McpExposure {
  return {
    exposureCode: '',
    exposureName: '',
    toolKey: '',
    exposedToolName: '',
    exposureType: 'TOOL',
    riskLevel: 'LOW',
    requireUserContext: true,
    status: 'disabled',
    displayOrder: 0,
    extConfig: {}
  };
}

async function reload() {
  await loadExposures();
}

async function syncRuntime() {
  if (syncing.value) return;
  syncing.value = true;
  try {
    const result = await platformService.syncMcpExposureRuntime();
    if (result.notifiedPeerCount > 0) {
      ElMessage.success(`本实例已同步，已通知 ${result.notifiedPeerCount} 个其他在线实例`);
    } else {
      ElMessage.success('本实例已同步，当前未发现其他在线实例');
    }
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '同步运行时 MCP Tool 失败'));
    }
  } finally {
    syncing.value = false;
  }
}

async function loadExposures() {
  loading.value = true;
  try {
    const page = await platformService.pageMcpExposures({
      current: pageQuery.current,
      size: pageQuery.size,
      toolKey: filters.toolKey || undefined,
      status: filters.status || undefined
    });
    exposures.value = page.data;
    pageTotal.value = page.total;
    pageQuery.current = page.pageNum || pageQuery.current;
    pageQuery.size = page.pageSize || pageQuery.size;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载 MCP 服务白名单失败'));
    }
    exposures.value = [];
    pageTotal.value = 0;
  } finally {
    loading.value = false;
  }
}

async function loadResources(force = false) {
  if (resourcesLoaded.value && !force) {
    return;
  }
  try {
    const page = await toolService.page({ current: 1, size: 500 });
    resources.value = page.data;
    resourcesLoaded.value = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载 Tool 列表失败'));
    }
  }
}

function handleResourcesVisible(visible: boolean) {
  if (visible) {
    loadResources();
  }
}

async function openEditor(row?: McpExposure) {
  await loadResources();
  extConfigValid.value = true;
  Object.assign(form, emptyExposure(), row ? JSON.parse(JSON.stringify(row)) : {});
  editingId.value = row?.id || '';
  editorVisible.value = true;
}

async function saveExposure() {
  if (!extConfigValid.value) {
    ElMessage.warning('请先修正扩展配置中的 JSON 格式错误');
    return;
  }
  if (!form.exposureCode || !form.toolKey || !form.exposedToolName) {
    ElMessage.warning('请填写服务编码、内部 Tool 和 MCP Tool Name');
    return;
  }
  if (form.status === 'enabled') {
    try {
      await ElMessageBox.confirm(
        `确认将内部 Tool「${form.toolKey}」作为「${form.exposedToolName}」对外提供？` +
          `风险级别：${form.riskLevel || 'LOW'}；用户上下文：${form.requireUserContext === false ? '不要求' : '要求'}。`,
        '启用 MCP Tool',
        {
          type: 'warning',
          confirmButtonText: '确认启用',
          cancelButtonText: '取消'
        }
      );
    } catch {
      return;
    }
  }
  saving.value = true;
  try {
    if (editingId.value) {
      await platformService.updateMcpExposure(editingId.value, form);
    } else {
      await platformService.createMcpExposure(form);
    }
    ElMessage.success('配置已保存');
    editorVisible.value = false;
    await loadExposures();
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '保存 MCP 服务失败'));
    }
  } finally {
    saving.value = false;
  }
}

async function removeExposure(row: McpExposure) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(`确认删除 MCP 服务 ${row.exposureCode || ''}?`, '删除 MCP 服务', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  await platformService.deleteMcpExposure(row.id);
  ElMessage.success('MCP 服务已删除');
  await loadExposures();
}

onMounted(loadExposures);
</script>

<style scoped>
.mcp-exposure-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.mcp-exposure-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.page-toolbar,
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-toolbar {
  justify-content: space-between;
}

.page-toolbar h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: 0;
}

.page-toolbar p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.filter-panel {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-input),
.filter-panel :deep(.el-select) {
  width: 100%;
}

.filter-panel :deep(.el-form-item__content) {
  gap: 8px;
  flex-wrap: nowrap;
}

.mcp-exposure-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.mcp-exposure-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.mcp-exposure-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.mcp-exposure-table-wrap :deep(.el-table) {
  height: 100%;
}

.page-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.grid-two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.full-width {
  width: 100%;
}

@media (max-width: 900px) {
  .page-toolbar,
  .filter-panel {
    align-items: stretch;
    flex-direction: column;
  }

  .filter-panel :deep(.el-input),
  .filter-panel :deep(.el-select),
  .grid-two {
    max-width: none;
    grid-template-columns: 1fr;
  }
}
</style>
