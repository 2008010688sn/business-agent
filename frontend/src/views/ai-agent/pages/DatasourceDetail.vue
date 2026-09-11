<template>
  <BaseLayout>
    <main class="detail-page flex flex-col gap-8px">
      <ElCard
        class="detail-head card-wrapper"
        shadow="never"
        body-class="!p-14px flex items-start justify-between gap-12px lt-md:flex-col lt-md:items-stretch"
      >
        <div class="detail-title-block">
          <div class="detail-copy">
            <div class="detail-meta-row">
              <span class="detail-eyebrow">数据源详情</span>
            </div>
            <h1>{{ datasource?.name || '数据源详情' }}</h1>
            <p v-if="datasourceSummary">{{ datasourceSummary }}</p>
          </div>
        </div>
        <div class="head-actions">
          <ElButton @click="loadAll">
            <ElIcon class="mr-3px"><component :is="Refresh" /></ElIcon>
            <span>刷新</span>
          </ElButton>
          <ElButton :loading="syncing" @click="syncCatalog">
            <ElIcon class="mr-3px"><component :is="RefreshRight" /></ElIcon>
            <span>同步目录</span>
          </ElButton>
          <ElButton aria-label="返回数据源列表" @click="goBack">
            <ElIcon><component :is="ArrowLeft" /></ElIcon>
            <span>返回数据源</span>
          </ElButton>
        </div>
      </ElCard>

      <ElTabs v-model="activeTab" class="work-tabs">
        <ElTabPane label="表目录" name="catalog">
          <section class="split-pane">
            <ElCard class="list-card card-wrapper" shadow="never">
              <template #header>
                <div class="card-head">
                  <span>数据表</span>
                  <ElTag size="small">{{ tables.length }}</ElTag>
                </div>
              </template>
              <ElInput v-model="tableKeyword" :prefix-icon="Search" placeholder="搜索表" clearable />
              <ElTable
                v-loading="loading"
                :data="filteredTables"
                height="560"
                row-key="tableName"
                @row-click="selectTable"
              >
                <ElTableColumn prop="tableName" label="表名" min-width="180" />
                <ElTableColumn prop="enabled" label="启用" width="78">
                  <template #default="{ row }">
                    <ElTag :type="row.enabled ? 'success' : 'info'" size="small">
                      {{ row.enabled ? '是' : '否' }}
                    </ElTag>
                  </template>
                </ElTableColumn>
              </ElTable>
            </ElCard>

            <ElCard class="columns-card card-wrapper" shadow="never">
              <template #header>
                <div class="card-head">
                  <span>{{ selectedTable || '字段目录' }}</span>
                  <ElTag size="small">{{ selectedColumns.length }}</ElTag>
                </div>
              </template>
              <ElTable :data="selectedColumns" height="620" row-key="columnName">
                <ElTableColumn prop="columnName" label="字段" min-width="180" />
                <ElTableColumn prop="columnType" label="类型" width="160" />
                <ElTableColumn prop="columnComment" label="注释" min-width="220" />
                <ElTableColumn prop="enabled" label="启用" width="78">
                  <template #default="{ row }">
                    <ElTag :type="row.enabled ? 'success' : 'info'" size="small">
                      {{ row.enabled ? '是' : '否' }}
                    </ElTag>
                  </template>
                </ElTableColumn>
              </ElTable>
            </ElCard>
          </section>
        </ElTabPane>

        <ElTabPane label="行级权限字段映射" name="permission">
          <ElCard class="rule-card card-wrapper" shadow="never">
            <template #header>
              <div class="card-head">
                <span>行级权限字段映射</span>
                <div class="rule-actions">
                  <ElButton type="success" :loading="inferringRules" @click="inferRules">
                    <ElIcon class="mr-3px"><component :is="MagicStick" /></ElIcon>
                    <span>识别候选权限字段{{ selectedTable ? `：${selectedTable}` : '' }}</span>
                  </ElButton>
                  <ElButton @click="addRule">
                    <ElIcon class="mr-3px"><component :is="Connection" /></ElIcon>
                    <span>新增规则</span>
                  </ElButton>
                  <ElButton type="primary" :loading="savingRules" @click="saveRules">
                    <ElIcon class="mr-3px"><component :is="Check" /></ElIcon>
                    <span>保存规则</span>
                  </ElButton>
                </div>
              </div>
            </template>

            <ElAlert
              class="permission-rule-alert"
              type="info"
              :closable="false"
              show-icon
              title="可选配置：仅在动态 SQL 实际引用已启用映射的表时，复用当前用户的公司、网点、项目等行级权限；未配置的表不会阻止查询。"
            />

            <ElTable :data="permissionRules" row-key="id">
              <ElTableColumn label="表" min-width="180">
                <template #default="{ row }">
                  <ElSelect
                    v-model="row.tableName"
                    filterable
                    placeholder="选择表"
                    @change="handleRuleTableChange(row)"
                  >
                    <ElOption
                      v-for="table in tables"
                      :key="table.tableName || ''"
                      :label="table.tableName || ''"
                      :value="table.tableName || ''"
                    />
                  </ElSelect>
                </template>
              </ElTableColumn>
              <ElTableColumn label="字段" min-width="180">
                <template #default="{ row }">
                  <ElSelect
                    v-model="row.columnName"
                    filterable
                    allow-create
                    default-first-option
                    :disabled="!row.tableName"
                    placeholder="选择或输入字段"
                  >
                    <ElOption
                      v-for="column in getColumnsForRule(row)"
                      :key="`${row.tableName || 'unknown'}:${column.columnName || ''}`"
                      :label="formatColumnOption(column)"
                      :value="column.columnName || ''"
                    />
                  </ElSelect>
                </template>
              </ElTableColumn>
              <ElTableColumn label="权限维度" min-width="170">
                <template #default="{ row }">
                  <ElSelect v-model="row.dataRefType" placeholder="DataRefType">
                    <ElOption label="公司 company" value="company" />
                    <ElOption label="网点 site" value="site" />
                    <ElOption label="操作网点 operationSite" value="operationSite" />
                    <ElOption label="二级项目 twoProject" value="twoProject" />
                    <ElOption label="投资方 investor" value="investor" />
                    <ElOption label="用户 user" value="user" />
                  </ElSelect>
                </template>
              </ElTableColumn>
              <ElTableColumn label="值类型" width="130">
                <template #default="{ row }">
                  <ElSelect v-model="row.javaType">
                    <ElOption label="String" value="String" />
                    <ElOption label="Long" value="Long" />
                    <ElOption label="Integer" value="Integer" />
                  </ElSelect>
                </template>
              </ElTableColumn>
              <ElTableColumn label="状态" width="150">
                <template #default="{ row }">
                  <ElSwitch v-model="row.enabled" />
                  <span class="rule-status-text">{{ row.enabled ? '参与 SQL 重写' : '不参与 SQL 重写' }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="72">
                <template #default="{ $index }">
                  <ElTooltip content="删除规则" placement="top">
                    <ElButton
                      class="icon-action action-delete"
                      native-type="button"
                      aria-label="删除规则"
                      @click="removeRule($index)"
                    >
                      <ElIcon><component :is="Delete" /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                </template>
              </ElTableColumn>
            </ElTable>
          </ElCard>
        </ElTabPane>
      </ElTabs>
    </main>
  </BaseLayout>
</template>

<script lang="ts">
import { computed, defineComponent, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  ArrowLeft,
  Check,
  Connection,
  Delete,
  MagicStick,
  Refresh,
  RefreshRight,
  Search
} from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import datasourceService from '@/views/ai-agent/services/datasource';
import type {
  Datasource,
  DatasourceColumn,
  DatasourcePermissionRule,
  DatasourceTable
} from '@/views/ai-agent/services/datasource';
import { formatDatasourceEndpoint } from '@/views/ai-agent/utils/datasourceDisplay';

export default defineComponent({
  name: 'DatasourceDetail',
  components: {
    BaseLayout
  },
  setup() {
    const route = useRoute();
    const router = useRouter();
    const rawDatasourceId = route.query.id;
    const datasourceId = (Array.isArray(rawDatasourceId) ? rawDatasourceId[0] : rawDatasourceId)?.trim() || '';
    const activeTab = ref('catalog');
    const loading = ref(false);
    const savingRules = ref(false);
    const inferringRules = ref(false);
    const syncing = ref(false);
    const tableKeyword = ref('');
    const datasource = ref<Datasource | null>(null);
    const tables = ref<DatasourceTable[]>([]);
    const columnsByTable = ref<Record<string, DatasourceColumn[]>>({});
    const permissionRules = ref<DatasourcePermissionRule[]>([]);
    const rulesLoaded = ref(false);
    const selectedTable = ref('');

    const filteredTables = computed(() => {
      const kw = tableKeyword.value.trim().toLowerCase();
      return tables.value.filter(table => !kw || (table.tableName || '').toLowerCase().includes(kw));
    });

    const datasourceSummary = computed(() => {
      const source = datasource.value;
      if (!source) return '';
      return [source.type, formatDatasourceEndpoint(source)].filter(Boolean).join(' · ');
    });

    const selectedColumns = computed(() => {
      if (!selectedTable.value) return [];
      return columnsByTable.value[selectedTable.value] || [];
    });

    const getColumnsForRule = (rule: DatasourcePermissionRule): DatasourceColumn[] => {
      if (!rule.tableName) return [];
      return (columnsByTable.value[rule.tableName] || []).filter(column => Boolean(column.columnName));
    };

    const formatColumnOption = (column: DatasourceColumn): string => {
      const parts = [column.columnName, column.columnType, column.columnComment].filter(Boolean);
      return parts.join(' · ');
    };

    const handleRuleTableChange = (rule: DatasourcePermissionRule) => {
      if (!rule.tableName || !rule.columnName) return;
      const exists = getColumnsForRule(rule).some(column => column.columnName === rule.columnName);
      if (!exists) {
        rule.columnName = '';
      }
    };

    const loadDatasource = async () => {
      datasource.value = await datasourceService.getDatasourceById(datasourceId);
    };

    const loadCatalog = async () => {
      const catalog = await datasourceService.getDatasourceCatalog(datasourceId);
      tables.value = catalog.tables || [];
      columnsByTable.value = catalog.columnsByTable || {};
      if (selectedTable.value && !tables.value.some(table => table.tableName === selectedTable.value)) {
        selectedTable.value = '';
      }
    };

    const loadRules = async () => {
      permissionRules.value = (await datasourceService.getPermissionRules(datasourceId)).map(rule => ({
        ...rule,
        missingPolicy: 'ALLOW_ON_MISSING'
      }));
      rulesLoaded.value = true;
    };

    const loadAll = async () => {
      loading.value = true;
      try {
        const tasks = [loadDatasource(), loadCatalog()];
        if (activeTab.value === 'permission') {
          tasks.push(loadRules());
        }
        await Promise.all(tasks);
      } finally {
        loading.value = false;
      }
    };

    const ensureRulesLoaded = async () => {
      if (!rulesLoaded.value) {
        await loadRules();
      }
    };

    const syncCatalog = async () => {
      syncing.value = true;
      try {
        const catalog = await datasourceService.syncDatasourceCatalog(datasourceId);
        tables.value = catalog.tables || [];
        columnsByTable.value = catalog.columnsByTable || {};
        if (selectedTable.value && !tables.value.some(table => table.tableName === selectedTable.value)) {
          selectedTable.value = '';
        }
        ElMessage.success('目录已同步');
      } finally {
        syncing.value = false;
      }
    };

    const selectTable = (row: DatasourceTable) => {
      selectedTable.value = row.tableName || '';
    };

    const addRule = () => {
      permissionRules.value.push({
        tableName: selectedTable.value || '',
        columnName: '',
        dataRefType: 'site',
        javaType: 'String',
        enabled: true,
        missingPolicy: 'ALLOW_ON_MISSING'
      });
    };

    const inferRules = async () => {
      if (!selectedTable.value) {
        ElMessage.warning('请先选中表才能进行推断');
        return;
      }
      inferringRules.value = true;
      try {
        await ensureRulesLoaded();
        const inferredRules = await datasourceService.inferPermissionRulesByTable(datasourceId, selectedTable.value);
        const retainedRules = permissionRules.value.filter(rule => rule.tableName !== selectedTable.value);
        permissionRules.value = [
          ...retainedRules,
          ...inferredRules.map(rule => ({ ...rule, missingPolicy: 'ALLOW_ON_MISSING' }))
        ];
        activeTab.value = 'permission';
        ElMessage.success(
          inferredRules.length
            ? `已生成 ${selectedTable.value} 的候选规则，请确认后保存`
            : `${selectedTable.value} 未识别到权限字段`
        );
      } finally {
        inferringRules.value = false;
      }
    };

    const saveRules = async () => {
      savingRules.value = true;
      try {
        await ensureRulesLoaded();
        const rules = permissionRules.value.map(rule => ({ ...rule, missingPolicy: 'ALLOW_ON_MISSING' }));
        permissionRules.value = await datasourceService.savePermissionRules(datasourceId, rules);
        rulesLoaded.value = true;
        ElMessage.success('权限规则已保存');
      } finally {
        savingRules.value = false;
      }
    };

    const removeRule = (index: number) => {
      permissionRules.value.splice(index, 1);
    };

    const goBack = () => router.push('/ai-agent/data-sources');

    watch(activeTab, value => {
      if (value === 'permission') {
        ensureRulesLoaded();
      }
    });

    onMounted(async () => {
      if (!/^\d+$/.test(datasourceId)) {
        ElMessage.error('数据源 ID 无效');
        await router.replace('/ai-agent/data-sources');
        return;
      }
      await loadAll();
    });

    return {
      ArrowLeft,
      Check,
      Connection,
      Delete,
      MagicStick,
      Refresh,
      RefreshRight,
      Search,
      activeTab,
      columnsByTable,
      datasource,
      datasourceSummary,
      filteredTables,
      formatColumnOption,
      getColumnsForRule,
      handleRuleTableChange,
      inferringRules,
      loading,
      permissionRules,
      savingRules,
      selectedColumns,
      selectedTable,
      syncing,
      tableKeyword,
      tables,
      addRule,
      goBack,
      inferRules,
      loadAll,
      removeRule,
      saveRules,
      selectTable,
      syncCatalog
    };
  }
});
</script>

<style scoped>
.detail-page {
  width: 100%;
  min-height: auto;
  margin: 0 auto;
  background: var(--el-bg-color-page);
}

.card-head,
.rule-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.detail-title-block {
  min-width: 0;
  display: flex;
  align-items: flex-start;
}

.detail-meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.detail-back-control {
  width: fit-content;
  min-height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease,
    transform 0.18s ease;
}

.detail-back-control:hover,
.detail-back-control:focus-visible {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  transform: none;
  outline: none;
}

.detail-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.detail-eyebrow {
  display: inline-flex;
  margin: 0;
  color: var(--el-text-color);
  font-size: 18px;
  font-weight: 600;
}

.detail-head h1 {
  margin: 0 0 3px;
  color: var(--el-text-color-primary);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0;
}

.detail-head p {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.head-actions {
  display: flex;
  gap: 10px;
}

.work-tabs {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 8px 12px 12px;
  box-shadow: none;
}

.split-pane {
  display: grid;
  grid-template-columns: minmax(320px, 420px) minmax(0, 1fr);
  gap: 14px;
}

.list-card,
.columns-card,
.rule-card {
  border-radius: 8px;
  border-color: var(--el-border-color-light);
  overflow: hidden;
  box-shadow: none;
}

.list-card :deep(.el-card__header),
.columns-card :deep(.el-card__header),
.rule-card :deep(.el-card__header) {
  background: var(--el-fill-color-extra-light);
  border-bottom-color: var(--el-border-color-lighter);
}

.list-card .el-input {
  margin-bottom: 12px;
}

.permission-rule-alert {
  margin-bottom: 12px;
}

.rule-status-text {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 900px) {
  .detail-page {
    padding: 1rem;
  }

  .detail-head,
  .split-pane {
    display: flex;
    flex-direction: column;
    align-items: stretch;
  }

  .detail-title-block {
    align-items: flex-start;
  }

  .detail-head p {
    white-space: normal;
  }
}
</style>
