<template>
  <BaseLayout class="datasource-shell">
    <main class="datasource-page flex flex-col gap-8px">
      <ElCard class="card-wrapper" shadow="never" body-class="!p-14px">
        <div class="flex items-center justify-between gap-16px lt-md:flex-col lt-md:items-stretch">
          <div>
            <h1 class="text-20px text-primary font-bold leading-28px">数据中心</h1>
            <p class="mt-6px text-13px text-[var(--el-text-color-secondary)]">
              统一维护 DataAgent 可绑定的数据源，Agent 只负责选择可见表和字段。
            </p>
          </div>

          <div class="grid grid-cols-3 gap-10px lt-sm:grid-cols-1">
            <div class="stat-card">
              <ElIcon class="stat-icon"><component :is="View" /></ElIcon>
              <strong>{{ totalCount }}</strong>
              <span>数据源总数</span>
            </div>
            <div class="stat-card">
              <ElIcon class="stat-icon"><component :is="Connection" /></ElIcon>
              <strong>{{ connectedCount }}</strong>
              <span>连接正常</span>
            </div>
            <div class="stat-card">
              <ElIcon class="stat-icon"><component :is="Search" /></ElIcon>
              <strong>{{ typeCount }}</strong>
              <span>数据源类型</span>
            </div>
          </div>
        </div>
      </ElCard>

      <ElCard class="card-wrapper" shadow="never" body-class="!p-12px">
        <div class="flex items-center justify-between gap-12px lt-md:flex-col lt-md:items-stretch">
          <div class="flex items-center gap-8px">
            <ElButton type="primary" @click="openCreateDialog">
              <ElIcon class="mr-3px"><component :is="Connection" /></ElIcon>
              新增数据源
            </ElButton>
            <ElButton @click="loadDatasources">
              <ElIcon class="mr-3px"><component :is="Refresh" /></ElIcon>
              刷新
            </ElButton>
          </div>

          <div class="min-w-0 flex flex-1 justify-end gap-10px lt-md:w-full lt-md:flex-none">
            <ElInput
              v-model="keyword"
              class="max-w-340px lt-md:max-w-none"
              :prefix-icon="Search"
              placeholder="搜索名称、主机或库名"
              clearable
            />
            <ElSelect v-model="typeFilter" class="w-180px lt-md:w-180px" placeholder="全部类型" clearable>
              <ElOption
                v-for="type in datasourceTypes"
                :key="type.typeName"
                :label="type.displayName || type.typeName"
                :value="type.typeName"
              />
            </ElSelect>
          </div>
        </div>
      </ElCard>

      <ElCard class="datasource-list-card card-wrapper" shadow="never">
        <template #header>
          <span class="text-16px text-base-text font-bold leading-22px">数据源目录</span>
          <span class="ml-5px text-12px text-[var(--el-text-color-secondary)]">
            共 {{ filteredDatasources.length }} 条匹配结果
          </span>
        </template>
        <div v-loading="loading" class="datasource-card-grid">
          <article v-for="row in filteredDatasources" :key="row.id || row.name" class="datasource-card">
            <header class="datasource-card-header">
              <div class="name-cell">
                <ElIcon class="source-mark"><component :is="Connection" /></ElIcon>
                <div class="name-main">
                  <strong>{{ row.name || '-' }}</strong>
                  <span>{{ row.description || '暂无描述' }}</span>
                </div>
              </div>
              <span class="type-badge" :class="getDatasourceTypeClass(row.type)">
                {{ displayDatasourceType(row.type) }}
              </span>
            </header>

            <div class="datasource-card-body">
              <div class="datasource-card-field">
                <span>连接</span>
                <div class="connection-cell">
                  <span>{{ row.host || '-' }}:{{ row.port || '-' }}</span>
                  <small>{{ row.databaseName || '-' }}</small>
                </div>
              </div>
              <div class="datasource-card-field">
                <span>测试状态</span>
                <span class="status-pill" :class="getStatusClass(row.testStatus)">
                  {{ getStatusText(row.testStatus) }}
                </span>
              </div>
              <div class="datasource-card-field">
                <span>密码状态</span>
                <ElTag :type="row.passwordConfigured ? 'success' : 'info'" size="small" effect="light">
                  {{ row.passwordConfigured ? '已配置' : '未配置' }}
                </ElTag>
              </div>
            </div>

            <footer class="datasource-card-footer">
              <ElTooltip content="查看详情" placement="top">
                <ElButton text aria-label="查看详情" @click="goDetail(row.id)">
                  <ElIcon><component :is="View" /></ElIcon>
                </ElButton>
              </ElTooltip>
              <ElTooltip content="测试连接" placement="top">
                <ElButton text type="primary" aria-label="测试连接" @click="testDatasource(row)">
                  <ElIcon><component :is="Connection" /></ElIcon>
                </ElButton>
              </ElTooltip>
              <ElTooltip content="编辑数据源" placement="top">
                <ElButton text type="warning" aria-label="编辑数据源" @click="openEditDialog(row)">
                  <ElIcon><component :is="Edit" /></ElIcon>
                </ElButton>
              </ElTooltip>
              <ElTooltip content="删除数据源" placement="top">
                <ElButton text type="danger" aria-label="删除数据源" @click="deleteDatasource(row)">
                  <ElIcon><component :is="Delete" /></ElIcon>
                </ElButton>
              </ElTooltip>
            </footer>
          </article>
          <ElEmpty
            v-if="!loading && filteredDatasources.length === 0"
            class="datasource-empty"
            description="暂无数据源"
          />
        </div>
      </ElCard>

      <ElDialog v-model="dialogVisible" :title="editingId ? '编辑数据源' : '新增数据源'" width="640px" destroy-on-close>
        <ElForm ref="formRef" :model="form" label-width="110px">
          <ElFormItem label="名称" required>
            <ElInput v-model="form.name" placeholder="例如：运营 PostgreSQL" />
          </ElFormItem>
          <ElFormItem label="类型" required>
            <ElSelect v-model="form.type" placeholder="选择类型">
              <ElOption
                v-for="type in datasourceTypes"
                :key="type.typeName"
                :label="type.displayName || type.typeName"
                :value="type.typeName"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="主机/端口" required>
            <div class="flex items-center gap-10px">
              <ElInput v-model="form.host" class="flex-1" placeholder="127.0.0.1" />
              <ElInputNumber v-model="form.port" :min="1" :max="65535" controls-position="right" />
            </div>
          </ElFormItem>
          <ElFormItem label="库/Schema" required>
            <ElInput v-model="form.databaseName" placeholder="PostgreSQL 可填 database|schema" />
          </ElFormItem>
          <ElFormItem label="用户名">
            <ElInput
              v-model="form.username"
              :placeholder="editingId ? '留空则保留原账号' : '请输入用户名'"
              autocomplete="off"
            />
          </ElFormItem>
          <ElFormItem label="密码">
            <ElInput
              v-model="form.password"
              type="password"
              show-password
              autocomplete="new-password"
              :placeholder="editingId && form.passwordConfigured ? '********' : '请输入密码'"
            />
            <!-- <div v-if="editingId" class="form-tip">
              {{ form.passwordConfigured ? '已配置，留空则保留原密码' : '未配置，请输入新密码' }}
            </div> -->
          </ElFormItem>
          <ElFormItem label="描述">
            <ElInput v-model="form.description" type="textarea" :rows="3" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="saving" @click="saveDatasource">保存</ElButton>
        </template>
      </ElDialog>
    </main>
  </BaseLayout>
</template>

<script lang="ts">
import { computed, defineComponent, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Connection, Delete, Edit, Refresh, Search, View } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import datasourceService from '@/views/ai-agent/services/datasource';
import type { Datasource, DatasourceType } from '@/views/ai-agent/services/datasource';

const emptyForm = (): Datasource => ({
  name: '',
  type: '',
  host: '',
  port: 5432,
  databaseName: '',
  username: '',
  password: '',
  passwordConfigured: false,
  description: '',
  status: 'active'
});

export default defineComponent({
  name: 'DatasourceList',
  components: {
    BaseLayout
  },
  setup() {
    const router = useRouter();
    const loading = ref(false);
    const saving = ref(false);
    const keyword = ref('');
    const typeFilter = ref('');
    const dialogVisible = ref(false);
    const editingId = ref<string | null>(null);
    const datasources = ref<Datasource[]>([]);
    const datasourceTypes = ref<DatasourceType[]>([]);
    const form = reactive<Datasource>(emptyForm());
    const maskedUsername = ref('');

    const filteredDatasources = computed(() => {
      const list = Array.isArray(datasources.value) ? datasources.value : [];
      const kw = keyword.value.trim().toLowerCase();
      return list.filter(item => {
        const matchType = !typeFilter.value || item.type === typeFilter.value;
        const text = [item.name, item.type, item.host, item.databaseName].join(' ').toLowerCase();
        return matchType && (!kw || text.includes(kw));
      });
    });
    const totalCount = computed(() => (Array.isArray(datasources.value) ? datasources.value : []).length);
    const connectedCount = computed(
      () =>
        (Array.isArray(datasources.value) ? datasources.value : []).filter(item => item.testStatus === 'success').length
    );
    const typeCount = computed(
      () =>
        new Set((Array.isArray(datasources.value) ? datasources.value : []).map(item => item.type).filter(Boolean)).size
    );

    const loadTypes = async () => {
      datasourceTypes.value = (await datasourceService.getDatasourceTypes()).data || [];
    };

    const loadDatasources = async () => {
      loading.value = true;
      try {
        const list = await datasourceService.getAllDatasource();
        datasources.value = Array.isArray(list) ? list : [];
      } finally {
        loading.value = false;
      }
    };

    const resetForm = (source?: Datasource) => {
      Object.assign(form, emptyForm(), source || {});
      form.password = '';
      // 列表/详情返回的 username 是掩码串，直接摆进输入框会被当成真实账号再提交回去
      maskedUsername.value = source?.username || '';
      form.username = '';
    };

    const openCreateDialog = () => {
      editingId.value = null;
      resetForm();
      dialogVisible.value = true;
    };

    const openEditDialog = (row: Datasource) => {
      editingId.value = row.id || null;
      resetForm(row);
      dialogVisible.value = true;
    };

    const saveDatasource = async () => {
      saving.value = true;
      try {
        const payload = buildSubmitPayload(form);
        if (editingId.value) {
          await datasourceService.updateDatasource(editingId.value, payload);
        } else {
          await datasourceService.createDatasource(payload);
        }
        ElMessage.success('数据源已保存');
        dialogVisible.value = false;
        await loadDatasources();
      } finally {
        saving.value = false;
      }
    };

    const buildSubmitPayload = (datasource: Datasource): Datasource => {
      const payload: Datasource = { ...datasource };
      delete payload.passwordConfigured;
      delete payload.connectionUrlConfigured;
      // 连接串由服务端按 host/port/databaseName 重新生成，客户端不持有也不回写
      delete payload.connectionUrl;
      payload.password = payload.password?.trim() || '';
      // 编辑时留空表示沿用原账号：必须把服务端下发的掩码串原样回填，服务端凭掩码回读库内真实值。
      // 送空串或不送该字段都会被写成空账号（见 DatasourceServiceImpl.updateDatasource）。
      payload.username = payload.username?.trim() || (editingId.value ? maskedUsername.value : '');
      return payload;
    };

    const testDatasource = async (row: Datasource) => {
      if (!row.id) return;
      const result = await datasourceService.testConnection(row.id);
      ElMessage[result.data ? 'success' : 'warning'](result.data ? '连接成功' : '连接失败');
      await loadDatasources();
    };

    const deleteDatasource = async (row: Datasource) => {
      if (!row.id) return;
      await ElMessageBox.confirm(`确认删除数据源「${row.name || row.id}」？`, '删除数据源', {
        type: 'warning'
      });
      await datasourceService.deleteDatasource(row.id);
      ElMessage.success('数据源已删除');
      await loadDatasources();
    };

    const goDetail = (id?: string) => {
      if (id) {
        router.push({
          name: 'ai-agent_data-sources_detail',
          query: { id }
        });
      }
    };

    const displayDatasourceType = (typeName?: string) => {
      return datasourceTypes.value.find(type => type.typeName === typeName)?.displayName || typeName || '-';
    };

    const getDatasourceTypeClass = (typeName?: string) => {
      const normalizedType = (typeName || '').toLowerCase();
      if (normalizedType.includes('postgres')) return 'type-postgres';
      if (normalizedType.includes('mysql')) return 'type-mysql';
      if (normalizedType.includes('oracle')) return 'type-oracle';
      return 'type-default';
    };

    const getStatusText = (status?: string) => {
      const statusMap: Record<string, string> = {
        success: '连接正常',
        failed: '连接失败',
        fail: '连接失败',
        error: '连接异常',
        unknown: '未测试'
      };
      return statusMap[status || 'unknown'] || '未测试';
    };

    const getStatusClass = (status?: string) => {
      if (status === 'success') return 'status-success';
      if (status === 'failed' || status === 'fail' || status === 'error') return 'status-error';
      return 'status-unknown';
    };

    onMounted(async () => {
      await Promise.all([loadTypes(), loadDatasources()]);
    });

    return {
      Connection,
      Delete,
      Edit,
      Refresh,
      Search,
      View,
      datasourceTypes,
      datasources,
      dialogVisible,
      editingId,
      filteredDatasources,
      form,
      keyword,
      loading,
      saving,
      connectedCount,
      displayDatasourceType,
      getDatasourceTypeClass,
      getStatusClass,
      getStatusText,
      typeFilter,
      totalCount,
      typeCount,
      deleteDatasource,
      goDetail,
      loadDatasources,
      openCreateDialog,
      openEditDialog,
      saveDatasource,
      testDatasource
    };
  }
});
</script>

<style scoped>
.datasource-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.datasource-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
  width: 100%;
  margin: 0 auto;
}

.datasource-page > .card-wrapper {
  flex: 0 0 auto;
}

.datasource-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0 !important;
  min-height: 0;
}

.datasource-list-card :deep(.el-card__header) {
  flex: 0 0 auto;
}

.datasource-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.datasource-page > .card-wrapper:first-child h1 {
  margin: 0 !important;
  color: var(--el-text-color-primary) !important;
  font-size: 22px !important;
  font-weight: 600 !important;
  line-height: 28px !important;
  letter-spacing: 0 !important;
}

.datasource-page > .card-wrapper:first-child p {
  margin: 4px 0 0 !important;
  color: var(--el-text-color-secondary) !important;
  font-size: 13px !important;
  line-height: 1.5 !important;
}

.stat-card {
  min-width: 132px;
  display: grid;
  grid-template-columns: 34px auto;
  align-items: center;
  align-content: center;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.stat-icon {
  width: 34px;
  height: 34px;
  grid-row: span 2;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
}

.stat-card strong {
  display: block;
  min-width: 0;
  color: var(--el-color-primary);
  font-size: 22px;
  line-height: 1;
}

.stat-card span:last-child {
  display: block;
  min-width: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.datasource-card-grid {
  display: grid;
  overflow-y: auto;
  align-content: start;
  flex: 1 1 0;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  min-height: 160px;
  padding-right: 4px;
  scrollbar-color: rgba(144, 147, 153, 0.22) transparent;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
}

.datasource-card-grid::-webkit-scrollbar {
  width: 4px;
  height: 4px;
}

.datasource-card-grid::-webkit-scrollbar-button {
  -webkit-appearance: none;
  appearance: none;
  display: none;
  width: 0;
  height: 0;
  background: transparent;
}

.datasource-card-grid::-webkit-scrollbar-corner,
.datasource-card-grid::-webkit-scrollbar-track-piece,
.datasource-card-grid::-webkit-scrollbar-track {
  background: transparent;
}

.datasource-card-grid::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background-color: rgba(144, 147, 153, 0.22);
}

.datasource-card {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-width: 0;
  min-height: 210px;
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: 0 6px 18px rgb(15 23 42 / 4%);
  transition:
    border-color var(--el-transition-duration-fast),
    box-shadow var(--el-transition-duration-fast),
    transform var(--el-transition-duration-fast);
}

.datasource-card:hover {
  border-color: var(--el-color-primary-light-7);
}

.datasource-card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.datasource-card-header .name-cell {
  flex: 1 1 0;
}

.datasource-card-header .type-badge {
  flex: 0 0 auto;
}

.datasource-card-body {
  display: grid;
  flex: 1 1 auto;
  gap: 10px;
  padding: 12px 0;
}

.datasource-card-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
}

.datasource-card-field > span:first-child {
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.datasource-card-field > :last-child {
  min-width: 0;
}

.datasource-card-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.datasource-empty {
  grid-column: 1 / -1;
  min-height: 160px;
}

.name-cell {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12px;
}

.source-mark {
  width: 36px;
  height: 36px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
}

.name-main {
  flex: 1;
  min-width: 0;
  max-width: calc(100% - 48px);
}

.name-main strong {
  display: block;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-main span {
  display: block;
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.type-badge,
.status-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid transparent;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.type-postgres {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.type-mysql {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-7);
}

.type-oracle {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-7);
}

.type-default {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color-light);
}

.connection-cell {
  width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.connection-cell span {
  display: block;
  color: var(--el-text-color-primary);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.connection-cell small {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-pill::before {
  content: '';
  width: 6px;
  height: 6px;
  margin-right: 6px;
  border-radius: 999px;
  background: currentColor;
}

.status-success {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-7);
}

.status-error {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-7);
}

.status-unknown {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color-light);
}

@media (max-width: 1440px) {
  .datasource-card-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1180px) {
  .datasource-card-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .datasource-card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
