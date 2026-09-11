<template>
  <div class="memory-config">
    <section class="memory-section">
      <div class="section-head">
        <div>
          <h2>长期记忆</h2>
          <p>控制当前智能体对当前用户的跨会话记忆召回和自动写入。</p>
        </div>
        <el-button type="primary" :loading="saving" @click="saveConfig">
          <el-icon><Check /></el-icon>
          保存配置
        </el-button>
      </div>

      <div class="settings-grid">
        <div class="setting-row">
          <div>
            <strong>启用记忆召回</strong>
            <span>开启后每轮按需检索少量相关长期记忆并注入上下文。</span>
          </div>
          <el-switch v-model="form.recallEnabled" />
        </div>
        <div class="setting-row">
          <div>
            <strong>启用自动写入</strong>
            <span>回答结束后异步抽取偏好、口径和工作方式。</span>
          </div>
          <el-switch v-model="form.writeEnabled" />
        </div>
        <div class="setting-row">
          <div>
            <strong>召回范围</strong>
            <span>V1 固定隔离在当前智能体和当前用户。</span>
          </div>
          <el-tag type="info">仅当前用户</el-tag>
        </div>
        <div class="setting-row">
          <div>
            <strong>仅召回已确认记忆</strong>
            <span>预留开关，当前版本不强制确认流。</span>
          </div>
          <el-switch v-model="form.confirmedOnly" />
        </div>
      </div>

      <div class="type-grid">
        <div>
          <h3>允许召回的记忆类型</h3>
          <el-checkbox-group v-model="form.recallTypes">
            <el-checkbox-button
              v-for="item in memoryTypeOptions"
              :key="`recall-${item.value}`"
              :label="item.value"
            >
              {{ item.label }}
            </el-checkbox-button>
          </el-checkbox-group>
        </div>
        <div>
          <h3>允许自动写入的记忆类型</h3>
          <el-checkbox-group v-model="form.writeTypes">
            <el-checkbox-button
              v-for="item in memoryTypeOptions"
              :key="`write-${item.value}`"
              :label="item.value"
            >
              {{ item.label }}
            </el-checkbox-button>
          </el-checkbox-group>
        </div>
      </div>
    </section>

    <section class="memory-section">
      <div class="section-head compact">
        <div>
          <h2>召回参数</h2>
          <p>限制召回数量、相似度、重要度和注入上下文预算。</p>
        </div>
      </div>
      <div class="param-grid">
        <el-form-item label="召回数量 TopK">
          <el-input-number v-model="form.topK" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="相似度阈值">
          <el-input-number v-model="form.similarityThreshold" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <el-form-item label="注入预算 Token">
          <el-input-number v-model="form.injectionTokenBudget" :min="100" :max="8000" :step="100" />
        </el-form-item>
        <el-form-item label="最小重要度">
          <el-input-number v-model="form.minImportance" :min="0" :max="1" :step="0.05" />
        </el-form-item>
      </div>
    </section>

    <section class="memory-section">
      <div class="section-head">
        <div>
          <h2>记忆列表</h2>
          <p>这里只展示摘要，不展示完整记忆内容。</p>
        </div>
        <div class="table-actions">
          <el-select v-model="queryStatus" clearable placeholder="全部状态" style="width: 130px">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="待审核" value="PENDING_REVIEW" />
            <el-option label="禁用" value="DISABLED" />
            <el-option label="已过期" value="EXPIRED" />
          </el-select>
          <el-select v-model="scopeFilter" clearable placeholder="全部范围" style="width: 140px">
            <el-option v-for="item in memoryScopeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="sensitivityFilter" clearable placeholder="全部敏感级" style="width: 130px">
            <el-option v-for="item in sensitivityOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="consentFilter" clearable placeholder="全部同意状态" style="width: 140px">
            <el-option v-for="item in consentOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-button :loading="loading" @click="loadMemories">
            <el-icon><Refresh /></el-icon>
          </el-button>
          <el-button v-if="canExportMemory" @click="openExportDialog">
            <el-icon><Download /></el-icon>
            导出
          </el-button>
          <el-button type="danger" plain :disabled="memories.length === 0" @click="clearMemories">
            清空我的记忆
          </el-button>
        </div>
      </div>

      <el-table v-loading="loading" :data="memories" class="memory-table">
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag>{{ typeLabel(row.memoryType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="320" show-overflow-tooltip />
        <el-table-column prop="sourceSessionId" label="来源会话" width="130" />
        <el-table-column label="置信度" width="100">
          <template #default="{ row }">{{ formatScore(row.confidence) }}</template>
        </el-table-column>
        <el-table-column label="重要度" width="100">
          <template #default="{ row }">{{ formatScore(row.importance) }}</template>
        </el-table-column>
        <el-table-column prop="useCount" label="使用次数" width="100" />
        <el-table-column label="范围" width="120">
          <template #default="{ row }">{{ scopeLabel(row.subjectType) }}</template>
        </el-table-column>
        <el-table-column label="敏感级" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.sensitivity" :type="sensitivityTag(row.sensitivity)" size="small">
              {{ sensitivityLabel(row.sensitivity) }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="同意状态" width="100">
          <template #default="{ row }">{{ consentLabel(row.consentStatus) }}</template>
        </el-table-column>
        <el-table-column label="最近使用" width="170">
          <template #default="{ row }">{{ formatTime(row.lastUsedTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewMemory(row)">查看</el-button>
            <el-button
              v-if="row.status === 'PENDING_REVIEW' && canReviewMemory"
              link
              type="success"
              @click="approveMemory(row)"
            >
              审核启用
            </el-button>
            <el-button
              v-if="row.status !== 'ACTIVE' && row.status !== 'PENDING_REVIEW'"
              link
              type="success"
              @click="updateStatus(row, 'ACTIVE')"
            >
              启用
            </el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="updateStatus(row, 'DISABLED')">
              禁用
            </el-button>
            <el-button link type="danger" @click="deleteMemory(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="exportDialogVisible" title="导出记忆" width="480px">
      <p class="export-tip">按记忆范围与主体全量导出（含治理字段），导出为 JSON 文件。</p>
      <el-form label-position="top">
        <el-form-item label="记忆范围">
          <el-select v-model="exportForm.scope" style="width: 100%" @change="handleExportScopeChange">
            <el-option v-for="item in memoryScopeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="主体ID">
          <el-input v-model="exportForm.subjectId" :placeholder="exportSubjectPlaceholder" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="exportMemories">导出</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts">
  import { computed, defineComponent, onMounted, reactive, ref, watch } from 'vue';
  import { ElMessage, ElMessageBox } from 'element-plus';
  import { Check, Download, Refresh } from '@element-plus/icons-vue';
  import { haveAuth } from '@/mixins/userAuth.js';
  import { useAuthStore } from '@/store/modules/auth';
  import agentMemoryService, {
    MEMORY_EXPORT_PERMISSION,
    MEMORY_REVIEW_PERMISSION,
    type AgentMemoryConfig,
    type AgentMemoryItem,
    type AgentMemoryStatus,
    type AgentMemoryType,
    type MemoryConsentStatus,
    type MemoryScope,
    type MemorySensitivity,
  } from '@/views/ai-agent/services/agentMemory';
  import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

  const memoryTypeOptions: Array<{ label: string; value: AgentMemoryType }> = [
    { label: '偏好记忆', value: 'PREFERENCE' },
    { label: '事实记忆', value: 'SEMANTIC' },
    { label: '历史结论', value: 'EPISODIC' },
    { label: '工作方式', value: 'PROCEDURAL' },
  ];

  const memoryScopeOptions: Array<{ label: string; value: MemoryScope }> = [
    { label: '会话记忆', value: 'SESSION' },
    { label: '员工用户记忆', value: 'EMPLOYEE_USER' },
    { label: '工作空间记忆', value: 'WORKSPACE' },
    { label: '情景记忆', value: 'EPISODIC' },
    { label: '程序性记忆', value: 'PROCEDURAL' },
  ];

  const sensitivityOptions: Array<{ label: string; value: MemorySensitivity }> = [
    { label: '低', value: 'LOW' },
    { label: '中', value: 'MEDIUM' },
    { label: '高', value: 'HIGH' },
  ];

  const consentOptions: Array<{ label: string; value: MemoryConsentStatus }> = [
    { label: '未指定', value: 'UNSPECIFIED' },
    { label: '已同意', value: 'GRANTED' },
    { label: '已拒绝', value: 'DENIED' },
    { label: '已撤回', value: 'REVOKED' },
  ];

  /** subjectId 为用户ID 的范围（其余：SESSION→会话ID，WORKSPACE→工作空间ID） */
  const USER_OWNED_SCOPES: MemoryScope[] = ['EMPLOYEE_USER', 'EPISODIC', 'PROCEDURAL'];

  export default defineComponent({
    name: 'AgentMemoryConfig',
    components: {
      Check,
      Download,
      Refresh,
    },
    props: {
      agentId: {
        type: String,
        required: true,
      },
    },
    setup(props) {
      const authStore = useAuthStore();
      const saving = ref(false);
      const loading = ref(false);
      const queryStatus = ref<AgentMemoryStatus | ''>('');
      const scopeFilter = ref<MemoryScope | ''>('');
      const sensitivityFilter = ref<MemorySensitivity | ''>('');
      const consentFilter = ref<MemoryConsentStatus | ''>('');
      const memories = ref<AgentMemoryItem[]>([]);
      const exportDialogVisible = ref(false);
      const exporting = ref(false);
      const exportForm = reactive<{ scope: MemoryScope; subjectId: string }>({
        scope: 'EMPLOYEE_USER',
        subjectId: '',
      });

      const canExportMemory = computed(() => haveAuth(MEMORY_EXPORT_PERMISSION));
      const canReviewMemory = computed(() => haveAuth(MEMORY_REVIEW_PERMISSION));

      const exportSubjectPlaceholder = computed(() => {
        if (exportForm.scope === 'SESSION') {
          return '请输入会话ID';
        }
        if (exportForm.scope === 'WORKSPACE') {
          return '请输入工作空间ID';
        }
        return '默认当前用户ID';
      });
      const form = reactive<AgentMemoryConfig>({
        recallEnabled: false,
        writeEnabled: false,
        recallTypes: ['PREFERENCE', 'SEMANTIC', 'EPISODIC', 'PROCEDURAL'],
        writeTypes: ['PREFERENCE', 'SEMANTIC', 'PROCEDURAL'],
        topK: 5,
        similarityThreshold: 0.75,
        injectionTokenBudget: 1200,
        minImportance: 0.5,
        confirmedOnly: false,
      });

      const loadConfig = async () => {
        const config = await agentMemoryService.getConfig(String(props.agentId));
        Object.assign(form, config);
      };

      /** 状态与治理维度筛选全部走服务端，避免客户端过滤在分批返回时漏数据 */
      const loadMemories = async () => {
        loading.value = true;
        try {
          memories.value = await agentMemoryService.listMemories(String(props.agentId), {
            status: queryStatus.value || undefined,
            scope: scopeFilter.value || undefined,
            sensitivity: sensitivityFilter.value || undefined,
            consentStatus: consentFilter.value || undefined,
          });
        } finally {
          loading.value = false;
        }
      };

      const saveConfig = async () => {
        saving.value = true;
        try {
          const saved = await agentMemoryService.saveConfig(String(props.agentId), form);
          Object.assign(form, saved);
          ElMessage.success('记忆配置已保存');
        } finally {
          saving.value = false;
        }
      };

      const updateStatus = async (row: AgentMemoryItem, status: AgentMemoryStatus) => {
        await agentMemoryService.updateMemoryStatus(String(props.agentId), row.id, status);
        ElMessage.success(status === 'ACTIVE' ? '记忆已启用' : '记忆已禁用');
        await loadMemories();
      };

      const deleteMemory = async (row: AgentMemoryItem) => {
        await ElMessageBox.confirm('删除后该记忆不会再被召回，是否继续？', '删除长期记忆', {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消',
        });
        await agentMemoryService.deleteMemory(String(props.agentId), row.id);
        ElMessage.success('记忆已删除');
        await loadMemories();
      };

      const clearMemories = async () => {
        await ElMessageBox.confirm('将清空当前用户在该智能体下的全部长期记忆，是否继续？', '清空我的记忆', {
          type: 'warning',
          confirmButtonText: '清空',
          cancelButtonText: '取消',
        });
        await agentMemoryService.clearMyMemories(String(props.agentId));
        ElMessage.success('已清空长期记忆');
        await loadMemories();
      };

      const viewMemory = (row: AgentMemoryItem) => {
        ElMessageBox.alert(row.summary || '-', '记忆摘要', {
          confirmButtonText: '关闭',
        });
      };

      const approveMemory = async (row: AgentMemoryItem) => {
        try {
          await ElMessageBox.confirm('审核通过后该程序性记忆将转为启用并参与召回，是否继续？', '审核启用', {
            type: 'warning',
            confirmButtonText: '审核通过',
            cancelButtonText: '取消',
          });
        } catch {
          return;
        }
        try {
          await agentMemoryService.approveMemory(String(props.agentId), row.id);
          ElMessage.success('记忆已审核启用');
          await loadMemories();
        } catch (error) {
          if (shouldShowLocalApiError(error)) {
            ElMessage.error(extractApiErrorMessage(error, '审核启用记忆失败'));
          }
        }
      };

      const openExportDialog = () => {
        exportForm.scope = 'EMPLOYEE_USER';
        exportForm.subjectId = authStore.userInfo?.userId || '';
        exportDialogVisible.value = true;
      };

      const handleExportScopeChange = (scope: MemoryScope) => {
        exportForm.subjectId = USER_OWNED_SCOPES.includes(scope) ? authStore.userInfo?.userId || '' : '';
      };

      const exportMemories = async () => {
        if (!exportForm.subjectId.trim()) {
          ElMessage.warning('请填写主体ID');
          return;
        }
        exporting.value = true;
        try {
          const count = await agentMemoryService.downloadMemoryExport(
            String(props.agentId),
            exportForm.scope,
            exportForm.subjectId.trim(),
          );
          ElMessage.success(`已导出 ${count} 条记忆`);
          exportDialogVisible.value = false;
        } catch (error) {
          if (shouldShowLocalApiError(error)) {
            ElMessage.error(extractApiErrorMessage(error, '导出记忆失败'));
          }
        } finally {
          exporting.value = false;
        }
      };

      const typeLabel = (type: AgentMemoryType) =>
        memoryTypeOptions.find(item => item.value === type)?.label || type;

      const statusLabel = (status: AgentMemoryStatus) =>
        ({ ACTIVE: '启用', PENDING_REVIEW: '待审核', DISABLED: '禁用', EXPIRED: '已过期' })[status] || status;

      const statusTag = (status: AgentMemoryStatus) => {
        if (status === 'ACTIVE') {
          return 'success';
        }
        if (status === 'PENDING_REVIEW' || status === 'DISABLED') {
          return 'warning';
        }
        return 'info';
      };

      const scopeLabel = (scope?: MemoryScope) =>
        memoryScopeOptions.find(item => item.value === scope)?.label || scope || '-';

      const sensitivityLabel = (sensitivity?: MemorySensitivity) =>
        sensitivityOptions.find(item => item.value === sensitivity)?.label || sensitivity || '-';

      const sensitivityTag = (sensitivity?: MemorySensitivity) => {
        if (sensitivity === 'HIGH') {
          return 'danger';
        }
        if (sensitivity === 'MEDIUM') {
          return 'warning';
        }
        return 'info';
      };

      const consentLabel = (consent?: MemoryConsentStatus) =>
        consentOptions.find(item => item.value === consent)?.label || consent || '-';

      const formatScore = (value?: number) =>
        value === undefined || value === null ? '-' : value.toFixed(2);

      const formatTime = (value?: string) => {
        if (!value) {
          return '-';
        }
        return new Date(value).toLocaleString();
      };

      watch([queryStatus, scopeFilter, sensitivityFilter, consentFilter], () => {
        loadMemories();
      });

      onMounted(async () => {
        await loadConfig();
        await loadMemories();
      });

      return {
        form,
        saving,
        loading,
        queryStatus,
        scopeFilter,
        sensitivityFilter,
        consentFilter,
        memories,
        memoryTypeOptions,
        memoryScopeOptions,
        sensitivityOptions,
        consentOptions,
        canExportMemory,
        canReviewMemory,
        exportDialogVisible,
        exporting,
        exportForm,
        exportSubjectPlaceholder,
        saveConfig,
        loadMemories,
        updateStatus,
        approveMemory,
        deleteMemory,
        clearMemories,
        viewMemory,
        openExportDialog,
        handleExportScopeChange,
        exportMemories,
        typeLabel,
        statusLabel,
        statusTag,
        scopeLabel,
        sensitivityLabel,
        sensitivityTag,
        consentLabel,
        formatScore,
        formatTime,
      };
    },
  });
</script>

<style scoped>
  .memory-config {
    display: flex;
    flex-direction: column;
    gap: 18px;
  }

  .memory-section {
    border: 1px solid #e5e7eb;
    border-radius: 8px;
    background: #ffffff;
    padding: 20px;
  }

  .section-head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 18px;
  }

  .section-head.compact {
    margin-bottom: 8px;
  }

  .section-head h2 {
    margin: 0;
    font-size: 18px;
    line-height: 1.4;
    color: #111827;
  }

  .section-head p,
  .setting-row span {
    display: block;
    margin-top: 4px;
    color: #6b7280;
    font-size: 13px;
    line-height: 1.5;
  }

  .settings-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }

  .setting-row {
    min-height: 74px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    border: 1px solid #edf0f5;
    border-radius: 6px;
    padding: 14px;
    background: #fbfcfe;
  }

  .setting-row strong,
  .type-grid h3 {
    color: #111827;
    font-size: 14px;
  }

  .type-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 20px;
    margin-top: 18px;
  }

  .type-grid h3 {
    margin: 0 0 10px;
  }

  .param-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(150px, 1fr));
    gap: 16px;
  }

  .table-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .memory-table {
    width: 100%;
  }

  .export-tip {
    margin: 0 0 12px;
    color: #6b7280;
    font-size: 13px;
    line-height: 1.5;
  }

  @media (max-width: 960px) {
    .settings-grid,
    .type-grid,
    .param-grid {
      grid-template-columns: 1fr;
    }

    .section-head {
      flex-direction: column;
    }

    .table-actions {
      flex-wrap: wrap;
    }
  }
</style>
