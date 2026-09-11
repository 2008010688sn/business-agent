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
  <div class="datasource-config">
    <div class="panel-header">
      <div>
        <h2>数据源配置</h2>
        <p>绑定当前 Skill 可访问的数据源，并维护表、字段和逻辑关系。</p>
      </div>
      <div class="header-actions">
        <ElButton class="create-action action-button" @click="dialogVisible = true">
          <ElIcon class="button-glyph"><component :is="Connection" /></ElIcon>
          <span>绑定数据源</span>
        </ElButton>
        <ElButton class="sync-action action-button" :disabled="initStatus" @click="initSkillDatasource">
          <ElIcon class="button-glyph" :class="{ 'is-loading': initStatus }">
            <component :is="initStatus ? Loading : UploadFilled" />
          </ElIcon>
          <span>{{ initStatus ? '初始化中...' : '初始化数据源' }}</span>
        </ElButton>
      </div>
    </div>
    <ElDivider></ElDivider>
    <div class="table-card">
      <div class="table-card-head">
        <div>
          <h3>数据源列表</h3>
          <span>{{ datasource.length }} 个已绑定数据源</span>
        </div>
      </div>

      <ElTable :data="datasource" class="config-table" border @expand-change="handleExpandChange">
        <ElTableColumn type="expand" width="100" label="选择数据表">
          <template #default="scope">
            <div v-if="scope.row.status === 'active'" class="table-expand">
              <div class="expand-head">
                <div>
                  <h4>数据表管理</h4>
                  <span>选择当前 Skill 可读取的数据表范围</span>
                </div>
                <ElButton
                  size="small"
                  class="refresh-action"
                  :loading="tableLoadingStates[scope.row.id]"
                  @click="loadDatasourceTables(scope.row)"
                >
                  刷新表列表
                </ElButton>
              </div>

              <div v-if="tableLists[scope.row.id] && tableLists[scope.row.id].length > 0">
                <ElCheckboxGroup v-model="selectedTables[scope.row.id]">
                  <ElRow :gutter="10" class="table-check-grid">
                    <ElCol v-for="table in tableLists[scope.row.id]" :key="table" :span="6">
                      <ElCheckbox :label="table">
                        {{ table }}
                      </ElCheckbox>
                    </ElCol>
                  </ElRow>
                </ElCheckboxGroup>

                <div class="expand-actions">
                  <ElButton
                    size="small"
                    class="save-action"
                    :loading="updateLoadingStates[scope.row.id]"
                    @click="updateDatasourceTables(scope.row)"
                  >
                    更新数据表
                  </ElButton>
                  <ElButton size="small" class="preview-action" @click="openColumnVisibilityDialog(scope.row)">
                    字段可见性
                  </ElButton>
                  <ElButton size="small" class="refresh-action" @click="selectAllTables(scope.row)">全选</ElButton>
                  <ElButton size="small" class="refresh-action" @click="clearAllTables(scope.row)">清空</ElButton>
                </div>
              </div>
              <div v-else-if="tableLoadingStates[scope.row.id]" class="expand-empty">
                <ElIcon class="is-loading"><Loading /></ElIcon>
                <div>正在加载表列表...</div>
              </div>
              <div v-else class="expand-empty">
                <ElIcon><FolderOpened /></ElIcon>
                <div>暂无表数据，请点击刷新表列表</div>
              </div>
            </div>
            <div v-else class="expand-empty locked">
              <ElIcon><Lock /></ElIcon>
              <div>请先启用数据源以管理表</div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="数据源名称" min-width="120px" />
        <ElTableColumn prop="type" label="数据源类型" min-width="100px" />
        <ElTableColumn label="连接地址" min-width="200px">
          <template #default="scope">
            <ElTooltip
              :content="formatDatasourceEndpoint(scope.row)"
              placement="top"
              :disabled="formatDatasourceEndpoint(scope.row).length <= 50"
            >
              <span class="connection-url-text">
                {{ truncateText(formatDatasourceEndpoint(scope.row), 50) || '-' }}
              </span>
            </ElTooltip>
          </template>
        </ElTableColumn>
        <ElTableColumn label="连接状态" min-width="80px">
          <template #default="scope">
            <ElTag :type="scope.row.testStatus === 'success' ? 'success' : 'danger'" round>
              {{ scope.row.testStatus === 'success' ? '连接成功' : '连接失败' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" min-width="70px">
          <template #default="scope">
            <ElTag :type="scope.row.status === 'active' ? 'success' : 'info'" round>
              {{ scope.row.status === 'active' ? '启用' : '禁用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="createTime" label="创建时间" min-width="180px">
          <template #default="scope">
            <span>{{ getTimeAll(scope.row.createTime) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="188px">
          <template #default="scope">
            <div class="table-actions">
              <ElTooltip :content="scope.row.status === 'active' ? '禁用数据源' : '启用数据源'" placement="top">
                <ElButton
                  class="icon-action"
                  :class="scope.row.status === 'active' ? 'action-detail' : 'action-enable'"
                  text
                  :aria-label="scope.row.status === 'active' ? '禁用数据源' : '启用数据源'"
                  @click="changeDatasource(scope.row, scope.row.status !== 'active')"
                >
                  <ElIcon>
                    <component :is="scope.row.status === 'active' ? Close : Check" />
                  </ElIcon>
                </ElButton>
              </ElTooltip>
              <ElTooltip content="测试连接" placement="top">
                <ElButton
                  class="icon-action action-test"
                  text
                  aria-label="测试连接"
                  :disabled="scope.row.status !== 'active'"
                  @click="testConnection(scope.row)"
                >
                  <ElIcon><component :is="Connection" /></ElIcon>
                </ElButton>
              </ElTooltip>
              <ElTooltip content="逻辑外键配置" placement="top">
                <ElButton
                  class="icon-action action-edit"
                  text
                  aria-label="逻辑外键配置"
                  :disabled="scope.row.status !== 'active'"
                  @click="openForeignKeyDialog(scope.row)"
                >
                  <ElIcon><component :is="Link" /></ElIcon>
                </ElButton>
              </ElTooltip>
              <ElTooltip content="移除数据源" placement="top">
                <ElButton
                  class="icon-action action-delete"
                  text
                  aria-label="移除数据源"
                  @click="removeSkillDatasource(scope.row)"
                >
                  <ElIcon><component :is="Delete" /></ElIcon>
                </ElButton>
              </ElTooltip>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </div>
  </div>

  <!-- 绑定数据源Dialog -->
  <ElDialog v-model="dialogVisible" title="绑定已有数据源" width="900">
    <ElTable
      :data="allDatasource"
      highlight-current-row
      class="dialog-table"
      @current-change="handleSelectDatasourceChange"
    >
      <ElTableColumn property="name" label="数据源名称" min-width="150" />
      <ElTableColumn property="type" label="数据源类型" width="120" />
      <ElTableColumn property="host" label="Host" min-width="140" />
      <ElTableColumn property="port" label="Port" width="80" />
      <ElTableColumn property="description" label="描述" min-width="260" show-overflow-tooltip />
    </ElTable>
    <template #footer>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton class="save-action action-button" @click="addSelectDatasource">绑定选中数据源</ElButton>
    </template>
  </ElDialog>

  <ElDialog v-model="columnDialogVisible" title="字段可见性配置" width="900px" :close-on-click-modal="false">
    <div v-if="currentColumnDatasource">
      <div class="dialog-source-banner">
        <div class="dialog-source-label">
          当前数据源：
          <span>{{ currentColumnDatasource.name }}</span>
        </div>
      </div>

      <div v-if="currentColumnTables.length === 0" class="dialog-empty">
        当前没有已保存的数据表，请先配置并保存数据表。
      </div>

      <div v-else class="column-card-list">
        <div v-for="tableName in currentColumnTables" :key="tableName" class="column-card">
          <div class="column-card-head">
            <div>
              <div class="column-table-name">{{ tableName }}</div>
              <div class="column-hint">关闭限制时，默认该表所有字段可见；开启后仅允许下方勾选字段可见。</div>
            </div>
            <ElSwitch
              :model-value="columnRestrictionEnabled[currentColumnDatasourceId]?.[tableName]"
              active-text="限制字段"
              inactive-text="全部字段"
              @change="toggleColumnRestriction(tableName, $event)"
            />
          </div>

          <div
            v-if="columnLoadingStates[getColumnLoadingKey(currentColumnDatasourceId, tableName)]"
            class="column-skeleton"
          >
            <ElSkeleton :rows="2" animated />
          </div>

          <div
            v-else-if="!(columnOptionsByDatasource[currentColumnDatasourceId]?.[tableName] || []).length"
            class="column-empty"
          >
            未加载到字段信息。
          </div>

          <div v-else>
            <div class="column-actions">
              <ElButton
                size="small"
                class="refresh-action"
                :disabled="!columnRestrictionEnabled[currentColumnDatasourceId]?.[tableName]"
                @click="selectAllColumnsForTable(tableName)"
              >
                全选字段
              </ElButton>
              <ElButton
                size="small"
                plain
                :disabled="!columnRestrictionEnabled[currentColumnDatasourceId]?.[tableName]"
                @click="clearColumnsForTable(tableName)"
              >
                清空字段
              </ElButton>
            </div>

            <ElCheckboxGroup
              v-model="selectedColumns[currentColumnDatasourceId][tableName]"
              :disabled="!columnRestrictionEnabled[currentColumnDatasourceId]?.[tableName]"
            >
              <ElRow :gutter="10">
                <ElCol
                  v-for="column in columnOptionsByDatasource[currentColumnDatasourceId]?.[tableName] || []"
                  :key="column"
                  :span="8"
                  class="column-option"
                >
                  <ElCheckbox :label="column">{{ column }}</ElCheckbox>
                </ElCol>
              </ElRow>
            </ElCheckboxGroup>
          </div>
        </div>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer-actions">
        <ElButton @click="columnDialogVisible = false">取消</ElButton>
        <ElButton class="save-action action-button" :loading="savingColumnVisibility" @click="saveDatasourceColumns">
          保存字段可见性
        </ElButton>
      </div>
    </template>
  </ElDialog>

  <!-- 逻辑外键配置Dialog（逻辑外键管理） -->
  <ElDialog v-model="foreignKeyDialogVisible" title="逻辑外键配置" width="900px" :close-on-click-modal="false">
    <div v-if="currentForeignKeyDatasource">
      <div class="dialog-source-banner">
        <p class="dialog-source-label">
          当前配置数据源：
          <span>{{ currentForeignKeyDatasource.name }}</span>
        </p>
      </div>

      <!-- 已生效的逻辑外键列表 -->
      <div class="foreign-section">
        <h4 class="foreign-section-title">已生效的逻辑外键 (Logical Foreign Keys)</h4>
        <ElTable :data="foreignKeyList" border class="dialog-table" size="small">
          <ElTableColumn prop="sourceTableName" label="主表 (Source)" min-width="100px">
            <template #default="scope">
              <span class="relation-table source">
                {{ scope.row.sourceTableName }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="sourceColumnName" label="字段" min-width="80px">
            <template #default="scope">
              <span class="relation-code">{{ scope.row.sourceColumnName }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="关系类型" min-width="90px" align="center">
            <template #default="scope">
              <span class="relation-icon">
                <ElIcon><Link /></ElIcon>
              </span>
              <span class="relation-code">
                {{ scope.row.relationType || '-' }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="targetTableName" label="关联表 (Target)" min-width="100px">
            <template #default="scope">
              <span class="relation-table target">
                {{ scope.row.targetTableName }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="targetColumnName" label="字段" min-width="80px">
            <template #default="scope">
              <span class="relation-code">{{ scope.row.targetColumnName }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="description" label="描述" min-width="120px" />
          <ElTableColumn label="操作" width="140px" align="right">
            <template #default="scope">
              <ElButton size="small" link @click="editForeignKey(scope.row)">编辑</ElButton>
              <ElButton size="small" type="danger" link @click="deleteForeignKey(scope.row, scope.$index)">
                删除
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <div v-if="!foreignKeyList || foreignKeyList.length === 0" class="dialog-empty framed">
          <ElIcon><FolderOpened /></ElIcon>
          <div>暂无逻辑外键配置</div>
        </div>
      </div>

      <!-- 新增/编辑关联关系表单 -->
      <div class="relation-form-card">
        <h4 class="relation-form-title">
          <ElIcon>
            <Link v-if="!editingForeignKey" />
            <Edit v-else />
          </ElIcon>
          <span>{{ editingForeignKey ? '编辑关联关系' : '新增关联关系' }}</span>
        </h4>

        <ElRow :gutter="10">
          <!-- 主表 -->
          <ElCol :span="5">
            <div class="field-label">
              <label>主表 (Left Table)</label>
            </div>
            <ElSelect
              v-model="newForeignKey.sourceTableName"
              placeholder="请选择表..."
              class="wide-control"
              clearable
              filterable
              @change="handleSourceTableChange"
            >
              <ElOption v-for="table in tableList" :key="table" :label="table" :value="table" />
            </ElSelect>
          </ElCol>

          <!-- 主表字段 -->
          <ElCol :span="4">
            <div class="field-label">
              <label>字段</label>
            </div>
            <ElSelect
              v-model="newForeignKey.sourceColumnName"
              placeholder="先选表"
              class="wide-control"
              :disabled="!newForeignKey.sourceTableName"
              clearable
              filterable
            >
              <ElOption v-for="column in sourceColumnList" :key="column" :label="column" :value="column" />
            </ElSelect>
          </ElCol>

          <!-- 关系图标 -->
          <ElCol :span="1" class="relation-arrow">
            <ElIcon :size="20"><Right /></ElIcon>
          </ElCol>

          <!-- 关联表 -->
          <ElCol :span="5">
            <div class="field-label">
              <label>关联表 (Right Table)</label>
            </div>
            <ElSelect
              v-model="newForeignKey.targetTableName"
              placeholder="请选择表..."
              class="wide-control"
              clearable
              filterable
              @change="handleTargetTableChange"
            >
              <ElOption v-for="table in tableList" :key="table" :label="table" :value="table" />
            </ElSelect>
          </ElCol>

          <!-- 关联表字段 -->
          <ElCol :span="4">
            <div class="field-label">
              <label>字段</label>
            </div>
            <ElSelect
              v-model="newForeignKey.targetColumnName"
              placeholder="先选表"
              class="wide-control"
              :disabled="!newForeignKey.targetTableName"
              clearable
              filterable
            >
              <ElOption v-for="column in targetColumnList" :key="column" :label="column" :value="column" />
            </ElSelect>
          </ElCol>

          <!-- 添加/更新按钮 -->
          <ElCol :span="5" class="relation-submit">
            <ElButton class="save-action action-button full-action" @click="saveOrUpdateForeignKey">
              <ElIcon class="button-glyph"><Check /></ElIcon>
              {{ editingForeignKey ? '更新' : '添加' }}
            </ElButton>
          </ElCol>
        </ElRow>

        <ElRow class="relation-form-row">
          <ElCol :span="24">
            <div class="field-label">
              <label>关系类型 (Relation Type)</label>
            </div>
            <ElSelect
              v-model="newForeignKey.relationType"
              placeholder="选择关系类型（可选）"
              clearable
              class="wide-control"
            >
              <ElOption label="1:1 (一对一)" value="1:1" />
              <ElOption label="1:N (一对多)" value="1:N" />
              <ElOption label="N:1 (多对一)" value="N:1" />
            </ElSelect>
          </ElCol>
        </ElRow>

        <!-- 描述输入框 -->
        <ElRow class="relation-form-row">
          <ElCol :span="24">
            <ElInput
              v-model="newForeignKey.description"
              placeholder="描述（可选）：例如 '订单关联用户'，帮助 LLM 理解语义"
              clearable
            />
          </ElCol>
        </ElRow>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer-actions">
        <ElButton @click="foreignKeyDialogVisible = false">取消</ElButton>
        <ElButton class="save-action action-button" :loading="savingForeignKeys" @click="saveForeignKeyConfig">
          保存全部配置
        </ElButton>
      </div>
    </template>
  </ElDialog>
</template>

<script lang="ts">
import type { Ref } from 'vue';
import { computed, defineComponent, ref, onMounted, watch } from 'vue';
import {
  UploadFilled,
  Loading,
  FolderOpened,
  Lock,
  Close,
  Connection,
  Delete,
  Link,
  Check,
  Right,
  Edit
} from '@element-plus/icons-vue';
import type { Datasource, SkillDatasource } from '@/views/ai-agent/services/datasource';
import type { ApiResponse } from '@/views/ai-agent/services/common';
import { ElMessage, ElMessageBox } from 'element-plus';
import skillDatasourceService from '@/views/ai-agent/services/skillDatasource';
import type { LogicalRelation } from '@/views/ai-agent/services/logicalRelation';
import { formatDatasourceEndpoint } from '@/views/ai-agent/utils/datasourceDisplay';
import { useCommonMixin } from '@/mixins/composition.js';
export default defineComponent({
  name: 'SkillDataSourceConfig',
  props: {
    skillId: {
      type: String,
      required: true
    },
    skillKind: {
      type: String,
      default: ''
    }
  },
  setup(props) {
    const { getTimeAll } = useCommonMixin();

    // 当前 Skill 关联的数据源列表
    const datasource: Ref<Datasource[]> = ref([]);
    const initStatus: Ref<boolean> = ref(false);
    const dialogVisible: Ref<boolean> = ref(false);
    // 所有数据源列表
    const allDatasource: Ref<Datasource[]> = ref([]);
    const selectedDatasourceId: Ref<string | null> = ref(null);

    // 数据表管理相关状态
    const tableLists: Ref<Record<string, string[]>> = ref({});
    const selectedTables: Ref<Record<string, string[]>> = ref({});
    const tableLoadingStates: Ref<Record<string, boolean>> = ref({});
    const updateLoadingStates: Ref<Record<string, boolean>> = ref({});
    const skillDatasourceList: Ref<SkillDatasource[]> = ref([]);
    const selectedColumns: Ref<Record<string, Record<string, string[]>>> = ref({});
    const columnOptionsByDatasource: Ref<Record<string, Record<string, string[]>>> = ref({});
    const columnRestrictionEnabled: Ref<Record<string, Record<string, boolean>>> = ref({});
    const columnLoadingStates: Ref<Record<string, boolean>> = ref({});
    const columnDialogVisible: Ref<boolean> = ref(false);
    const currentColumnDatasource: Ref<Datasource | null> = ref(null);
    const currentColumnDatasourceId = computed(() => currentColumnDatasource.value?.id || '');
    const currentColumnTables: Ref<string[]> = ref([]);
    const savingColumnVisibility: Ref<boolean> = ref(false);

    // 逻辑外键管理相关状态
    const foreignKeyDialogVisible: Ref<boolean> = ref(false);
    const currentForeignKeyDatasource: Ref<Datasource | null> = ref(null);
    const foreignKeyList: Ref<LogicalRelation[]> = ref([]);
    const editingForeignKey: Ref<LogicalRelation | null> = ref(null); // 正在编辑的外键
    const newForeignKey: Ref<LogicalRelation> = ref({
      sourceTableName: '',
      sourceColumnName: '',
      targetTableName: '',
      targetColumnName: '',
      relationType: '',
      description: ''
    } as LogicalRelation);
    const tableList: Ref<string[]> = ref([]);
    const sourceColumnList: Ref<string[]> = ref([]);
    const targetColumnList: Ref<string[]> = ref([]);
    const savingForeignKeys: Ref<boolean> = ref(false);
    const requiresActiveDatasource = computed(() => props.skillKind === 'QUERY');

    watch(dialogVisible, newValue => {
      if (newValue) {
        loadAllDatasource();
      }
    });

    // 初始化 Skill 数据源列表
    const loadSkillDatasource = async () => {
      selectedDatasourceId.value = null;
      try {
        const response = await skillDatasourceService.list(props.skillId);
        skillDatasourceList.value = response || [];
        const skillDatasource: SkillDatasource[] = response || [];
        datasource.value = skillDatasource.map(item => {
          const datasourceItem = { ...item.datasource };
          datasourceItem.status = item.isActive === true ? 'active' : 'inactive';

          const datasourceId = item.datasource?.id;
          if (datasourceId) {
            if (item.selectTables) {
              selectedTables.value[datasourceId] = [...item.selectTables];
            }
            selectedColumns.value[datasourceId] = Object.entries(item.selectColumns || {}).reduce<
              Record<string, string[]>
            >((result, [tableName, columns]) => {
              result[tableName] = [...columns];
              return result;
            }, {});
            columnRestrictionEnabled.value[datasourceId] = {};
            Object.keys(selectedColumns.value[datasourceId]).forEach(tableName => {
              columnRestrictionEnabled.value[datasourceId][tableName] = true;
            });
          }

          return datasourceItem;
        });
      } catch (error) {
        ElMessage.error('加载当前 Skill 的数据源列表失败');
        console.error('Failed to load datasource:', error);
      }
    };

    const getSkillDatasourceByDatasourceId = (datasourceId: string): SkillDatasource | undefined => {
      return skillDatasourceList.value.find(item => item.datasource?.id === datasourceId);
    };

    const getErrorMessage = (error: unknown, fallback: string): string => {
      if (error instanceof Error && error.message.trim()) {
        return error.message;
      }
      return fallback;
    };

    const applySkillDatasourceSnapshot = (snapshot: SkillDatasource): void => {
      const datasourceId = snapshot.datasource?.id;
      if (!datasourceId || !snapshot.datasource) {
        return;
      }

      const nextSnapshot: SkillDatasource = {
        ...snapshot,
        selectTables: [...(snapshot.selectTables || [])],
        selectColumns: Object.entries(snapshot.selectColumns || {}).reduce<Record<string, string[]>>(
          (result, [tableName, columns]) => {
            result[tableName] = [...columns];
            return result;
          },
          {}
        )
      };

      const skillDatasourceIndex = skillDatasourceList.value.findIndex(item => item.datasource?.id === datasourceId);
      if (skillDatasourceIndex >= 0) {
        skillDatasourceList.value[skillDatasourceIndex] = nextSnapshot;
      } else {
        skillDatasourceList.value.push(nextSnapshot);
      }

      const datasourceSnapshot: Datasource = {
        ...nextSnapshot.datasource,
        status: nextSnapshot.isActive === true ? 'active' : 'inactive'
      };
      const datasourceIndex = datasource.value.findIndex(item => item.id === datasourceId);
      if (datasourceIndex >= 0) {
        datasource.value[datasourceIndex] = datasourceSnapshot;
      } else {
        datasource.value.push(datasourceSnapshot);
      }

      selectedTables.value[datasourceId] = [...(nextSnapshot.selectTables || [])];
      selectedColumns.value[datasourceId] = Object.entries(nextSnapshot.selectColumns || {}).reduce<
        Record<string, string[]>
      >((result, [tableName, columns]) => {
        result[tableName] = [...columns];
        return result;
      }, {});
      columnRestrictionEnabled.value[datasourceId] = {};
      (nextSnapshot.selectTables || []).forEach(tableName => {
        columnRestrictionEnabled.value[datasourceId][tableName] =
          (nextSnapshot.selectColumns?.[tableName] || []).length > 0;
      });
    };

    const getSelectedTablesForDatasource = (datasourceId: string): string[] => {
      const currentTables = selectedTables.value[datasourceId];
      if (currentTables && currentTables.length > 0) {
        return [...currentTables];
      }
      return [...(getSkillDatasourceByDatasourceId(datasourceId)?.selectTables || [])];
    };

    const normalizeNameList = (values: string[] = []): string[] => {
      return [...values]
        .map(value => value.trim())
        .filter(Boolean)
        .sort((left, right) => left.localeCompare(right));
    };

    const hasPendingTableChanges = (datasourceRow: Datasource): boolean => {
      if (!datasourceRow.id) {
        return false;
      }
      const savedTables = normalizeNameList(getSkillDatasourceByDatasourceId(datasourceRow.id)?.selectTables || []);
      const currentTables = normalizeNameList(selectedTables.value[datasourceRow.id] || []);
      return savedTables.join('|') !== currentTables.join('|');
    };

    const resolveConfiguredColumns = (
      selectColumns: Record<string, string[]> | undefined,
      tableName: string
    ): string[] => {
      if (!selectColumns) {
        return [];
      }
      if (selectColumns[tableName]) {
        return [...selectColumns[tableName]];
      }
      const matchedKey = Object.keys(selectColumns).find(key => key.toLowerCase() === tableName.toLowerCase());
      return matchedKey ? [...(selectColumns[matchedKey] || [])] : [];
    };

    const getColumnLoadingKey = (datasourceId: string, tableName: string): string => {
      return `${datasourceId}:${tableName}`;
    };

    const loadColumnsForTable = async (datasourceId: string, tableName: string): Promise<void> => {
      const loadingKey = getColumnLoadingKey(datasourceId, tableName);
      columnLoadingStates.value[loadingKey] = true;
      try {
        const columns = await skillDatasourceService.getVisibleTableColumns(
          props.skillId,
          datasourceId,
          tableName
        );
        if (!columnOptionsByDatasource.value[datasourceId]) {
          columnOptionsByDatasource.value[datasourceId] = {};
        }
        columnOptionsByDatasource.value[datasourceId][tableName] = columns;
      } catch (error) {
        ElMessage.error(getErrorMessage(error, `加载表 ${tableName} 的字段失败`));
        console.error('Failed to load datasource columns:', error);
      } finally {
        columnLoadingStates.value[loadingKey] = false;
      }
    };

    const handleSelectDatasourceChange = (value: Datasource) => {
      if (value === null || value === undefined) {
        selectedDatasourceId.value = null;
      } else {
        selectedDatasourceId.value = value.id || null;
      }
    };

    const loadAllDatasource = async () => {
      try {
        const response = await skillDatasourceService.candidates(props.skillId);
        allDatasource.value = response || [];
      } catch (error) {
        ElMessage.error('加载所有数据源列表失败');
        console.error('Failed to load all datasource:', error);
      }
    };

    // 初始化 Skill 数据源
    const initSkillDatasource = async () => {
      if (initStatus.value) {
        return;
      }

      initStatus.value = true;
      try {
        try {
          // 获取智能体配置的启用数据源
          const usedDatasource: SkillDatasource = await skillDatasourceService.getActive(props.skillId);

          if (
            (usedDatasource.datasource === null || usedDatasource.datasource === undefined) &&
            (usedDatasource.datasourceId === null || usedDatasource.datasourceId === undefined)
          ) {
            ElMessage.warning('当前 Skill 没有启用的数据源！请添加一个新数据源，或者启用已有的数据源');
            return;
          } else if (
            usedDatasource.selectTables === null ||
            usedDatasource.selectTables === undefined ||
            usedDatasource.selectTables.length === 0
          ) {
            ElMessage.warning('当前启用的数据源没有选择相应的数据表！请点击相应数据源左侧按钮，选择相应数据表并更新！');
            return;
          }
        } catch {
          ElMessage.warning('当前智能体没有启用的数据源！请添加一个新数据源，或者启用已有的数据源');
          return;
        }

        const response: ApiResponse<null> = await skillDatasourceService.initSchema(props.skillId);
        if (response.success === undefined || response.success === null || !response.success) {
          ElMessage.error(`初始化数据源失败`);
          throw new Error('初始化数据源失败');
        }

        ElMessage.success('初始化当前 Skill 的数据源成功');
      } catch (error) {
        ElMessage.error('初始化当前 Skill 的数据源失败');
        console.error('Failed to init datasource:', error);
      } finally {
        initStatus.value = false;
      }
    };

    // 更改数据源状态
    const changeDatasource = async (row: Datasource, active: boolean) => {
      const datasourceId = row.id;
      if (!datasourceId) {
        ElMessage.error('数据源ID不存在，无法切换状态');
        return;
      }
      try {
        if (active) {
          const response: ApiResponse = await skillDatasourceService.setEnabled(props.skillId, datasourceId, true);
          if (response.success) {
            ElMessage.success('已切换到对应数据源');
            await loadSkillDatasource();
          } else {
            ElMessage.error(response.message || '切换数据源失败！');
            console.error('Failed to switch datasource:', response);
          }
        } else {
          const activeDatasourceCount = datasource.value.filter(item => item.status === 'active').length;
          if (requiresActiveDatasource.value && row.status === 'active' && activeDatasourceCount <= 1) {
            ElMessage.warning('当前 Skill 必须至少保留一个启用中的数据源');
            return;
          }

          const response: ApiResponse = await skillDatasourceService.setEnabled(props.skillId, datasourceId, false);
          if (response.success) {
            ElMessage.success('操作成功！');
            await loadSkillDatasource();
          } else {
            ElMessage.error(response.message || '操作失败！');
            console.error('Failed to disable datasource:', response);
          }
        }
      } catch (error) {
        ElMessage.error(getErrorMessage(error, active ? '切换数据源失败！' : '操作失败！'));
        console.error('Failed to change datasource:', error);
      }
    };

    // 测试数据源连接
    const testConnection = async (row: Datasource) => {
      const datasourceId = row.id;
      if (!datasourceId) {
        return;
      }
      if (row.status !== 'active') {
        ElMessage.warning('禁用的数据源无需测试连接，请先启用');
        return;
      }
      try {
        const connected = await skillDatasourceService.testConnection(props.skillId, datasourceId);
        if (connected) {
          ElMessage.success('测试连接成功！');
          row.testStatus = 'success';
        } else {
          ElMessage.error('测试连接失败！');
          // 此分支是接口正常返回 false（连不上），没有异常对象可打；原先打的 response 是未定义变量，
          // 抛出的 ReferenceError 会被下方 catch 接住，导致错误提示弹两次、日志还指向错误原因。
          console.error('Failed to test connection: backend reported unreachable, datasourceId=', datasourceId);
          row.testStatus = 'fail';
        }
      } catch (error) {
        ElMessage.error('测试连接失败！');
        console.error('Failed to test connection:', error);
        row.testStatus = 'fail';
      }
    };

    // 移除Skill数据源
    const removeSkillDatasource = async (row: Datasource) => {
      const datasourceId = row.id;
      if (!datasourceId) {
        return;
      }

      try {
        await ElMessageBox.confirm('是否要删除当前数据源吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });
      } catch (error) {
        return;
      }

      try {
        const response: ApiResponse = await skillDatasourceService.remove(props.skillId, datasourceId);
        if (response.success) {
          ElMessage.success('移除成功！');
          datasource.value = datasource.value.filter(item => item.id !== datasourceId);
        } else {
          ElMessage.error('移除失败！');
          console.error('Failed to remove datasource:', response);
        }
      } catch (error) {
        ElMessage.error('移除失败！');
        console.error('Failed to remove datasource:', error);
      }
    };

    const addDatasourceToSkill = async (datasourceId: string) => {
      try {
        await skillDatasourceService.bind(props.skillId, datasourceId);
        await loadSkillDatasource();
        ElMessage.success('添加数据源成功');
        dialogVisible.value = false;
      } catch (error) {
        ElMessage.error('添加数据源失败');
        console.error('Failed to add datasource:', error);
      }
    };

    const addSelectDatasource = async () => {
      const datasourceId = selectedDatasourceId.value;
      if (datasourceId === null || datasourceId === undefined) {
        ElMessage.warning('请选择一个数据源');
        return;
      }
      await addDatasourceToSkill(datasourceId);
    };

    // 加载数据源的表列表
    const loadDatasourceTables = async (datasource: Datasource) => {
      if (!datasource.id) return;
      if (datasource.status !== 'active') {
        ElMessage.warning('禁用的数据源无需加载表结构，请先启用');
        return;
      }

      tableLoadingStates.value[datasource.id] = true;
      try {
        const tables = await skillDatasourceService.availableTables(props.skillId, datasource.id);
        tableLists.value[datasource.id] = tables;

        // 如果没有初始化已选择的表，则使用当前已选择的表
        if (!selectedTables.value[datasource.id]) {
          const skillDatasource = skillDatasourceList.value.find(item => item.datasource?.id === datasource.id);
          selectedTables.value[datasource.id] = skillDatasource?.selectTables || [];
        }

        ElMessage.success(`成功加载 ${tables.length} 个表`);
      } catch (error) {
        ElMessage.error('加载表列表失败');
        console.error('Failed to load datasource tables:', error);
      } finally {
        tableLoadingStates.value[datasource.id] = false;
      }
    };

    // 更新数据源的表列表
    const updateDatasourceTables = async (datasource: Datasource) => {
      if (!datasource.id) return;

      updateLoadingStates.value[datasource.id] = true;
      try {
        const response = await skillDatasourceService.updateTables(
          props.skillId,
          datasource.id,
          selectedTables.value[datasource.id] || []
        );

        if (response.success && response.data) {
          applySkillDatasourceSnapshot(response.data);
          ElMessage.success('数据表更新成功');
        } else {
          ElMessage.error(response.message || '数据表更新失败');
        }
      } catch (error) {
        ElMessage.error(getErrorMessage(error, '数据表更新失败'));
        console.error('Failed to update datasource tables:', error);
      } finally {
        updateLoadingStates.value[datasource.id] = false;
      }
    };

    const toggleColumnRestriction = (tableName: string, enabled: boolean | string | number) => {
      const datasourceId = currentColumnDatasource.value?.id;
      if (!datasourceId) {
        return;
      }
      if (!columnRestrictionEnabled.value[datasourceId]) {
        columnRestrictionEnabled.value[datasourceId] = {};
      }
      columnRestrictionEnabled.value[datasourceId][tableName] = Boolean(enabled);
      if (!enabled) {
        selectedColumns.value[datasourceId][tableName] = [];
      }
    };

    const openColumnVisibilityDialog = async (datasourceRow: Datasource) => {
      const datasourceId = datasourceRow.id;
      if (!datasourceId) {
        return;
      }
      if (hasPendingTableChanges(datasourceRow)) {
        ElMessage.warning('请先点击“更新数据表”保存当前表配置，再设置字段可见性');
        return;
      }

      const tables = getSelectedTablesForDatasource(datasourceId);
      if (tables.length === 0) {
        ElMessage.warning('请先选择并保存数据表，再配置字段可见性');
        return;
      }

      currentColumnDatasource.value = datasourceRow;
      currentColumnTables.value = [...tables];

      if (!selectedColumns.value[datasourceId]) {
        selectedColumns.value[datasourceId] = {};
      }
      if (!columnRestrictionEnabled.value[datasourceId]) {
        columnRestrictionEnabled.value[datasourceId] = {};
      }
      if (!columnOptionsByDatasource.value[datasourceId]) {
        columnOptionsByDatasource.value[datasourceId] = {};
      }

      const skillDatasource = getSkillDatasourceByDatasourceId(datasourceId);
      tables.forEach(tableName => {
        const configuredColumns = resolveConfiguredColumns(skillDatasource?.selectColumns, tableName);
        selectedColumns.value[datasourceId][tableName] = configuredColumns;
        columnRestrictionEnabled.value[datasourceId][tableName] = configuredColumns.length > 0;
      });

      await Promise.all(tables.map(tableName => loadColumnsForTable(datasourceId, tableName)));
      columnDialogVisible.value = true;
    };

    const selectAllColumnsForTable = (tableName: string) => {
      const datasourceId = currentColumnDatasource.value?.id;
      if (!datasourceId) {
        return;
      }
      selectedColumns.value[datasourceId][tableName] = [
        ...(columnOptionsByDatasource.value[datasourceId]?.[tableName] || [])
      ];
    };

    const clearColumnsForTable = (tableName: string) => {
      const datasourceId = currentColumnDatasource.value?.id;
      if (!datasourceId) {
        return;
      }
      selectedColumns.value[datasourceId][tableName] = [];
    };

    const saveDatasourceColumns = async () => {
      const datasourceId = currentColumnDatasource.value?.id;
      if (!datasourceId) {
        return;
      }

      const invalidTables = currentColumnTables.value.filter(tableName => {
        return (
          columnRestrictionEnabled.value[datasourceId]?.[tableName] &&
          !(selectedColumns.value[datasourceId]?.[tableName] || []).length
        );
      });
      if (invalidTables.length > 0) {
        ElMessage.warning(`请至少为以下数据表选择一个字段：${invalidTables.join('、')}`);
        return;
      }

      savingColumnVisibility.value = true;
      try {
        const tables = currentColumnTables.value
          .filter(tableName => columnRestrictionEnabled.value[datasourceId]?.[tableName])
          .map(tableName => ({
            tableName,
            columns: [...(selectedColumns.value[datasourceId]?.[tableName] || [])]
          }));

        const response = await skillDatasourceService.updateColumns(props.skillId, datasourceId, tables);

        if (response.success && response.data) {
          applySkillDatasourceSnapshot(response.data);
          ElMessage.success('字段可见性更新成功');
          columnDialogVisible.value = false;
        } else {
          ElMessage.error(response.message || '字段可见性更新失败');
        }
      } catch (error) {
        ElMessage.error(getErrorMessage(error, '字段可见性更新失败'));
        console.error('Failed to update datasource columns:', error);
      } finally {
        savingColumnVisibility.value = false;
      }
    };

    // 全选表
    const selectAllTables = (datasource: Datasource) => {
      if (!datasource.id || !tableLists.value[datasource.id]) return;
      selectedTables.value[datasource.id] = [...tableLists.value[datasource.id]];
    };

    // 清空选择的表
    const clearAllTables = (datasource: Datasource) => {
      if (!datasource.id) return;
      selectedTables.value[datasource.id] = [];
    };

    // 文本截断函数
    const truncateText = (text: string, maxLength: number): string => {
      if (!text || text.length <= maxLength) {
        return text;
      }
      return `${text.substring(0, maxLength)}...`;
    };

    // 处理表格展开事件
    const handleExpandChange = (row: Datasource, expandedRows: Datasource[]) => {
      // 如果当前行被展开（在expandedRows数组中），则自动加载表列表
      if (expandedRows.includes(row) && row.status === 'active' && row.id) {
        loadDatasourceTables(row);
      }
    };

    onMounted(() => {
      loadSkillDatasource();
    });

    // ==================== 逻辑外键管理功能 ====================

    // 打开逻辑外键配置模态框
    const openForeignKeyDialog = async (datasourceRow: Datasource) => {
      if (!datasourceRow.id) {
        ElMessage.warning('数据源ID不存在');
        return;
      }

      if (datasourceRow.status !== 'active') {
        ElMessage.warning('禁用的数据源无需配置逻辑关系，请先启用');
        return;
      }
      currentForeignKeyDatasource.value = datasourceRow;
      foreignKeyDialogVisible.value = true;

      // 加载表列表
      try {
        tableList.value = await skillDatasourceService.availableTables(props.skillId, datasourceRow.id);
      } catch (error) {
        ElMessage.error('加载表列表失败');
        console.error('Failed to load table list:', error);
      }

      // 加载现有的逻辑外键
      await loadForeignKeys(datasourceRow.id);

      // 重置表单
      resetForeignKeyForm();
    };

    // 加载逻辑外键列表
    const loadForeignKeys = async (datasourceId: string) => {
      try {
        foreignKeyList.value = await skillDatasourceService.logicalRelations(props.skillId, datasourceId);
      } catch (error) {
        ElMessage.error('加载逻辑外键列表失败');
        console.error('Failed to load logical relations:', error);
      }
    };

    // 主表选择变化，加载字段列表
    const handleSourceTableChange = async (tableName: string) => {
      if (!tableName || !currentForeignKeyDatasource.value?.id) {
        sourceColumnList.value = [];
        newForeignKey.value.sourceColumnName = '';
        return;
      }

      try {
        sourceColumnList.value = await skillDatasourceService.availableColumns(
          props.skillId,
          currentForeignKeyDatasource.value.id,
          tableName
        );
        newForeignKey.value.sourceColumnName = '';
      } catch (error) {
        ElMessage.error('加载字段列表失败');
        console.error('Failed to load source columns:', error);
      }
    };

    // 关联表选择变化，加载字段列表
    const handleTargetTableChange = async (tableName: string) => {
      if (!tableName || !currentForeignKeyDatasource.value?.id) {
        targetColumnList.value = [];
        newForeignKey.value.targetColumnName = '';
        return;
      }

      try {
        targetColumnList.value = await skillDatasourceService.availableColumns(
          props.skillId,
          currentForeignKeyDatasource.value.id,
          tableName
        );
        newForeignKey.value.targetColumnName = '';
      } catch (error) {
        ElMessage.error('加载字段列表失败');
        console.error('Failed to load target columns:', error);
      }
    };

    // 编辑逻辑外键
    const editForeignKey = async (foreignKey: LogicalRelation) => {
      editingForeignKey.value = foreignKey;

      // 加载数据到表单
      newForeignKey.value = {
        id: foreignKey.id,
        datasourceId: foreignKey.datasourceId,
        sourceTableName: foreignKey.sourceTableName,
        sourceColumnName: foreignKey.sourceColumnName,
        targetTableName: foreignKey.targetTableName,
        targetColumnName: foreignKey.targetColumnName,
        relationType: foreignKey.relationType || '',
        description: foreignKey.description || ''
      };

      // 加载对应的字段列表
      if (foreignKey.sourceTableName && currentForeignKeyDatasource.value?.id) {
        try {
          sourceColumnList.value = await skillDatasourceService.availableColumns(
            props.skillId,
            currentForeignKeyDatasource.value.id,
            foreignKey.sourceTableName
          );
        } catch (error) {
          console.error('Failed to load source columns:', error);
        }
      }

      if (foreignKey.targetTableName && currentForeignKeyDatasource.value?.id) {
        try {
          targetColumnList.value = await skillDatasourceService.availableColumns(
            props.skillId,
            currentForeignKeyDatasource.value.id,
            foreignKey.targetTableName
          );
        } catch (error) {
          console.error('Failed to load target columns:', error);
        }
      }

      ElMessage.info('正在编辑逻辑外键，修改后点击"更新"按钮');
    };

    // 添加或更新逻辑外键
    const saveOrUpdateForeignKey = async () => {
      // 表单验证
      if (
        !newForeignKey.value.sourceTableName ||
        !newForeignKey.value.sourceColumnName ||
        !newForeignKey.value.targetTableName ||
        !newForeignKey.value.targetColumnName
      ) {
        ElMessage.warning('请完整填写主表、字段、关联表和字段');
        return;
      }

      // 检查是否重复（编辑模式时排除自己）
      const isDuplicate = foreignKeyList.value.some(
        fk =>
          fk.id !== editingForeignKey.value?.id &&
          fk.sourceTableName === newForeignKey.value.sourceTableName &&
          fk.sourceColumnName === newForeignKey.value.sourceColumnName &&
          fk.targetTableName === newForeignKey.value.targetTableName &&
          fk.targetColumnName === newForeignKey.value.targetColumnName
      );

      if (isDuplicate) {
        ElMessage.warning('该逻辑外键关系已存在');
        return;
      }

      // 判断是编辑还是新增
      if (editingForeignKey.value && editingForeignKey.value.id) {
        // 更新模式
        const index = foreignKeyList.value.findIndex(fk => fk.id === editingForeignKey.value!.id);
        if (index !== -1) {
          foreignKeyList.value[index] = {
            ...foreignKeyList.value[index],
            sourceTableName: newForeignKey.value.sourceTableName,
            sourceColumnName: newForeignKey.value.sourceColumnName,
            targetTableName: newForeignKey.value.targetTableName,
            targetColumnName: newForeignKey.value.targetColumnName,
            relationType: newForeignKey.value.relationType || '',
            description: newForeignKey.value.description || ''
          };
        }
        ElMessage.success('更新成功，请点击"保存全部配置"以保存到数据库');
      } else {
        // 添加模式
        foreignKeyList.value.push({
          sourceTableName: newForeignKey.value.sourceTableName,
          sourceColumnName: newForeignKey.value.sourceColumnName,
          targetTableName: newForeignKey.value.targetTableName,
          targetColumnName: newForeignKey.value.targetColumnName,
          relationType: newForeignKey.value.relationType || '',
          description: newForeignKey.value.description || ''
        });
        ElMessage.success('添加成功，请点击"保存全部配置"以保存到数据库');
      }

      // 重置表单
      resetForeignKeyForm();
    };

    // 删除逻辑外键
    const deleteForeignKey = async (foreignKey: LogicalRelation, index: number) => {
      try {
        await ElMessageBox.confirm('确定要删除这条逻辑外键关系吗？', '确认删除', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });

        foreignKeyList.value.splice(index, 1);
        ElMessage.success('删除成功，请点击"保存全部配置"以保存到数据库');
      } catch {
        // 用户取消操作
      }
    };

    // 保存逻辑外键配置
    const saveForeignKeyConfig = async () => {
      if (!currentForeignKeyDatasource.value?.id) {
        ElMessage.error('数据源ID不存在');
        return;
      }

      savingForeignKeys.value = true;
      try {
        await skillDatasourceService.saveLogicalRelations(
          props.skillId,
          currentForeignKeyDatasource.value.id,
          foreignKeyList.value
        );
        ElMessage.success('保存成功');
        foreignKeyDialogVisible.value = false;
      } catch (error) {
        ElMessage.error('保存失败');
        console.error('Failed to save logical relations:', error);
      } finally {
        savingForeignKeys.value = false;
      }
    };

    // 重置逻辑外键表单
    const resetForeignKeyForm = () => {
      editingForeignKey.value = null; // 重置编辑状态
      newForeignKey.value = {
        sourceTableName: '',
        sourceColumnName: '',
        targetTableName: '',
        targetColumnName: '',
        relationType: '',
        description: ''
      } as LogicalRelation;
      sourceColumnList.value = [];
      targetColumnList.value = [];
    };

    return {
      props,
      getTimeAll,
      UploadFilled,
      Loading,
      FolderOpened,
      Lock,
      Close,
      Delete,
      datasource,
      initStatus,
      dialogVisible,
      allDatasource,
      tableLists,
      selectedTables,
      selectedColumns,
      columnOptionsByDatasource,
      columnRestrictionEnabled,
      columnLoadingStates,
      columnDialogVisible,
      currentColumnDatasource,
      currentColumnDatasourceId,
      currentColumnTables,
      savingColumnVisibility,
      tableLoadingStates,
      updateLoadingStates,
      initSkillDatasource,
      changeDatasource,
      testConnection,
      removeSkillDatasource,
      loadAllDatasource,
      addSelectDatasource,
      handleSelectDatasourceChange,
      loadDatasourceTables,
      updateDatasourceTables,
      openColumnVisibilityDialog,
      saveDatasourceColumns,
      selectAllColumnsForTable,
      clearColumnsForTable,
      toggleColumnRestriction,
      getColumnLoadingKey,
      selectAllTables,
      clearAllTables,
      truncateText,
      formatDatasourceEndpoint,
      handleExpandChange,
      // 逻辑外键管理
      Connection,
      Link,
      Check,
      Right,
      Edit,
      foreignKeyDialogVisible,
      currentForeignKeyDatasource,
      foreignKeyList,
      newForeignKey,
      tableList,
      sourceColumnList,
      targetColumnList,
      savingForeignKeys,
      openForeignKeyDialog,
      handleSourceTableChange,
      handleTargetTableChange,
      editForeignKey,
      saveOrUpdateForeignKey,
      deleteForeignKey,
      saveForeignKeyConfig,
      editingForeignKey
    };
  }
});
</script>

<style scoped>
.datasource-config {
  padding: 16px;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.panel-header h2 {
  margin: 0 0 5px;
  font-size: 20px;
}

.panel-header p {
  margin: 0;
  color: gray;
  font-size: 13px;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.table-card {
  overflow: hidden;
}

.table-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.table-card-head h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.table-card-head span {
  color: gray;
  font-size: 12px;
}

.config-table,
.dialog-table {
  margin-top: 5px;
  width: 100%;
}

.table-expand {
  padding: 18px;
  border: 1px solid #dfeadb;
  border-radius: 8px;
  background: #f8fbf6;
}

.expand-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.expand-head h4 {
  margin: 0 0 3px;
  color: #183627;
  font-size: 14px;
  font-weight: 600;
}

.expand-head span {
  color: #7c9082;
  font-size: 12px;
}

.table-check-grid :deep(.el-col) {
  margin-bottom: 10px;
}

.expand-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #e4eee0;
}

.expand-empty,
.dialog-empty {
  display: flex;
  min-height: 96px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #829485;
  font-size: 13px;
}

.expand-empty .el-icon,
.dialog-empty .el-icon {
  color: #7b9f42;
  font-size: 28px;
}

.expand-empty.locked .el-icon {
  color: #9aa99c;
}

.dialog-source-banner {
  margin-bottom: 16px;
  padding: 12px 14px;
  border: 1px solid #dfeadb;
  border-radius: 8px;
  background: #f8fbf6;
}

.dialog-source-label {
  margin: 0;
  color: #6b7f70;
  font-size: 14px;
}

.dialog-source-label span {
  color: #167243;
  font-weight: 600;
}

.column-card-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.column-card {
  padding: 16px;
  border: 1px solid #dfeadb;
  border-radius: 8px;
  background: #ffffff;
}

.column-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.column-table-name {
  color: #183627;
  font-size: 15px;
  font-weight: 600;
}

.column-hint {
  margin-top: 4px;
  color: #829485;
  font-size: 12px;
}

.column-skeleton {
  padding: 16px 0;
}

.column-empty {
  padding: 12px 0;
  color: #829485;
}

.column-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 10px;
}

.column-option {
  margin-bottom: 10px;
}

.foreign-section {
  margin-bottom: 24px;
}

.foreign-section-title {
  margin: 0 0 14px;
  padding-left: 10px;
  border-left: 4px solid #167243;
  color: #183627;
  font-size: 14px;
  font-weight: 600;
}

.relation-table,
.relation-code {
  font-family: var(--font-family-mono);
}

.relation-table.source {
  color: #167243;
  font-weight: 600;
}

.relation-table.target {
  color: #627f2e;
  font-weight: 600;
}

.relation-icon {
  margin-right: 4px;
  color: #7c9082;
}

.dialog-empty.framed {
  margin-top: 12px;
  border: 1px dashed #d6e3d0;
  border-radius: 8px;
  background: #fbfdf9;
}

.relation-form-card {
  padding: 18px;
  border: 1px solid #cfe3ca;
  border-radius: 8px;
  background: #f4faf2;
}

.relation-form-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 14px;
  color: #183627;
  font-size: 14px;
  font-weight: 600;
}

.relation-form-title .el-icon {
  color: #167243;
}

.field-label {
  margin-bottom: 6px;
}

.field-label label {
  color: #5f7467;
  font-size: 12px;
  font-weight: 600;
}

.relation-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 70px;
  color: #7c9082;
}

.relation-submit {
  display: flex;
  align-items: flex-end;
  min-height: 70px;
}

.full-action,
.wide-control {
  width: 100%;
}

.relation-form-row {
  margin-top: 12px;
}

.dialog-footer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
