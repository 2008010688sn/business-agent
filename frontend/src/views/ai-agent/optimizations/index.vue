<template>
  <BaseLayout class="opt-shell">
    <main class="opt-page">
      <ElCard>
        <section class="opt-header">
          <div>
            <h1>智能体自优化</h1>
          </div>
          <div class="opt-header-actions">
            <ElButton :loading="loading" @click="loadExperiments">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
            <ElButton v-if="canManageOptimization" type="primary" @click="openExperimentDialog">
              <ElIcon><Plus /></ElIcon>
              新建实验
            </ElButton>
            <ElButton v-if="canRollbackOptimization" @click="openRollbackDialog">
              <ElIcon><Connection /></ElIcon>
              回滚记录
            </ElButton>
          </div>
        </section>
      </ElCard>
      <ElCard class="opt-list-card mt-8px">
        <section class="filter-panel">
          <ElForm :model="query" label-width="80px">
            <ElRow :gutter="24">
              <ElCol :span="6">
                <ElFormItem label="实验名称">
                  <ElInput
                    v-model="query.experimentName"
                    clearable
                    placeholder="实验名称"
                    @keyup.enter="handleSearch"
                  />
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="评估对象">
                  <ElInput
                    v-model="query.subjectId"
                    clearable
                    placeholder="评估对象主键（数字，可空）"
                    @keyup.enter="handleSearch"
                  />
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="状态">
                  <ElSelect v-model="query.status" clearable placeholder="全部">
                    <ElOption
                      v-for="item in experimentStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElButton type="primary" :loading="loading" @click="handleSearch">
                <ElIcon><Search /></ElIcon>
                查询
              </ElButton>
              <ElButton @click="handleReset">
                <ElIcon><Refresh /></ElIcon>
                重置
              </ElButton>
            </ElRow>
          </ElForm>
        </section>

        <div class="opt-table-wrap">
          <ElTable
            v-loading="loading"
            :data="experimentRows"
            border
            stripe
            row-key="id"
            height="100%"
            empty-text="暂无优化实验"
          >
            <ElTableColumn label="实验" min-width="240" fixed="left" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="cell-strong">{{ row.experimentName || '-' }}</div>
                <span class="cell-muted">ID: {{ row.id || '-' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="基线运行" width="140">
              <template #default="{ row }">{{ row.baselineRunId || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="对象" width="130">
              <template #default="{ row }">{{ row.subjectId || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="132">
              <template #default="{ row }">
                <ElTag :type="statusTag(row.status)" effect="light" size="small">
                  {{ experimentStatusLabel(row.status) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="基线指标" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">{{ metricSummary(row.baselineMetricsJson) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="description" label="说明" min-width="220" show-overflow-tooltip />
            <ElTableColumn label="创建时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="220" fixed="right" align="center">
              <template #default="{ row }">
                <ElTooltip content="详情" placement="top">
                  <ElButton text type="primary" @click="openDetail(row)">
                    <ElIcon><View /></ElIcon>
                  </ElButton>
                </ElTooltip>
                <ElTooltip content="诊断" placement="top">
                  <ElButton v-if="canManageOptimization" text type="success" @click="diagnose(row)">
                    <ElIcon><Check /></ElIcon>
                  </ElButton>
                </ElTooltip>
                <ElTooltip content="新增候选" placement="top">
                  <ElButton v-if="canManageOptimization" text type="warning" @click="openCandidateDialog(row)">
                    <ElIcon><Plus /></ElIcon>
                  </ElButton>
                </ElTooltip>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>

        <div class="opt-pagination">
          <ElPagination
            v-model:current-page="page.current"
            v-model:page-size="page.size"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="page.total"
            @size-change="handleSizeChange"
            @current-change="loadExperiments"
          />
        </div>
      </ElCard>

      <ElDrawer v-model="detailVisible" title="优化实验详情" size="72%" destroy-on-close>
        <div v-loading="detailLoading" class="detail-body">
          <ElDescriptions :column="3" border>
            <ElDescriptionsItem label="实验 ID">{{ selectedExperiment?.id || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="基线运行">{{ selectedExperiment?.baselineRunId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="状态">
              {{ experimentStatusLabel(selectedExperiment?.status) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="评估对象">{{ selectedExperiment?.subjectId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="创建时间">
              {{ formatDateTime(selectedExperiment?.createTime) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="更新时间">
              {{ formatDateTime(selectedExperiment?.lastModifyTime) }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <section v-if="canUseLoop" class="detail-section">
            <LoopStatusPanel
              ref="loopPanelRef"
              :experiment-id="selectedExperiment?.id || ''"
            />
          </section>

          <section class="detail-section">
            <h2>诊断结果</h2>
            <pre>{{ jsonText(selectedExperiment?.diagnosisJson || selectedExperiment?.baselineMetricsJson) }}</pre>
          </section>

          <section class="detail-section">
            <div class="section-title-row">
              <h2>候选版本</h2>
              <ElButton
                v-if="canManageOptimization"
                type="primary"
                link
                @click="selectedExperiment && openCandidateDialog(selectedExperiment)"
              >
                <ElIcon><Plus /></ElIcon>
                新增候选
              </ElButton>
            </div>
            <ElTable :data="detail.candidates || []" border stripe row-key="id" empty-text="暂无候选版本">
              <ElTableColumn label="候选" min-width="200" fixed="left" show-overflow-tooltip>
                <template #default="{ row }">
                  <div class="cell-strong">{{ row.candidateName || '-' }}</div>
                  <span class="cell-muted">{{ row.targetType || '-' }} / {{ row.targetId || '-' }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="状态" width="130">
                <template #default="{ row }">
                  <ElTag :type="statusTag(row.status)" effect="light" size="small">
                    {{ candidateStatusLabel(row.status) }}
                  </ElTag>
                </template>
              </ElTableColumn>
              <ElTableColumn label="沙箱运行" width="130">
                <template #default="{ row }">{{ row.sandboxRunId || '-' }}</template>
              </ElTableColumn>
              <ElTableColumn label="门禁" min-width="240">
                <template #default="{ row }">
                  <ElTag :type="gatePassed(row) ? 'success' : 'warning'" effect="light" size="small">
                    {{ gatePassed(row) ? '通过' : '未通过' }}
                  </ElTag>
                  <span class="cell-muted ml-8">{{ latestEvalText(row) }}</span>
                  <div v-if="latestEvalViolationText(row)" class="cell-danger">
                    {{ latestEvalViolationText(row) }}
                  </div>
                </template>
              </ElTableColumn>
              <ElTableColumn label="哈希" min-width="220" show-overflow-tooltip>
                <template #default="{ row }">
                  <div>前：{{ row.beforeHash || '-' }}</div>
                  <span class="cell-muted">后：{{ row.afterHash || '-' }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="260" fixed="right" align="center">
                <template #default="{ row }">
                  <ElTooltip content="对比与门禁" placement="top">
                    <ElButton text type="primary" @click="openCompare(row)">
                      <ElIcon><View /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="发起离线评估（服务端强制 DRY_RUN）" placement="top">
                    <ElButton v-if="canUseLoop" text type="primary" @click="openOfflineEvalDialog(row)">
                      <ElIcon><VideoPlay /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="绑定沙箱评估" placement="top">
                    <ElButton v-if="canManageOptimization" text type="success" @click="openEvalDialog(row)">
                      <ElIcon><Check /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="发布审批" placement="top">
                    <ElButton v-if="canReleaseOptimization" text type="warning" @click="openReleaseDialog(row)">
                      <ElIcon><Edit /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                </template>
              </ElTableColumn>
            </ElTable>
          </section>
        </div>
      </ElDrawer>

      <ElDialog v-model="experimentDialogVisible" title="新建优化实验" width="560px" destroy-on-close>
        <ElForm :model="experimentForm" label-width="110px">
          <ElFormItem label="实验名称" required>
            <ElInput v-model="experimentForm.experimentName" clearable />
          </ElFormItem>
          <ElFormItem label="基线运行" required>
            <ElSelect
              v-model="experimentForm.baselineRunId"
              filterable
              clearable
              placeholder="选择一条已结束的评估运行"
              :loading="runOptionsLoading"
              @visible-change="handleRunOptionsVisible"
            >
              <ElOption
                v-for="item in runOptions"
                :key="String(item.id)"
                :label="runLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
            <div class="field-hint">评估对象自动取该次评估运行绑定的对象，不必手填名称或员工 ID。</div>
          </ElFormItem>
          <ElFormItem label="说明">
            <ElInput v-model="experimentForm.description" type="textarea" :rows="3" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="experimentDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="submitting" @click="submitExperiment">保存</ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="candidateDialogVisible" title="新增优化候选" width="720px" destroy-on-close>
        <ElForm :model="candidateForm" label-width="118px">
          <ElFormItem label="候选名称" required>
            <ElInput v-model="candidateForm.candidateName" clearable />
          </ElFormItem>
          <ElFormItem label="目标类型" required>
            <ElSelect v-model="candidateForm.targetType" filterable>
              <ElOption v-for="item in targetTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="目标 ID" required>
            <ElInput v-model="candidateForm.targetId" clearable />
          </ElFormItem>
          <ElFormItem label="配置版本">
            <ElInput v-model="candidateForm.targetVersion" clearable />
          </ElFormItem>
          <ElFormItem label="变更前哈希" required>
            <ElInput v-model="candidateForm.beforeHash" clearable />
          </ElFormItem>
          <ElFormItem label="变更后哈希" required>
            <ElInput v-model="candidateForm.afterHash" clearable />
          </ElFormItem>
          <ElFormItem label="补丁 JSON" required>
            <JsonObjectEditor
              v-model="candidateForm.patchJson"
              output-type="string"
              :rows="5"
              @validity-change="jsonValidity.candidatePatch = $event"
            />
          </ElFormItem>
          <ElFormItem label="回滚 JSON">
            <JsonObjectEditor
              v-model="candidateForm.rollbackPatchJson"
              output-type="string"
              :rows="3"
              @validity-change="jsonValidity.candidateRollback = $event"
            />
          </ElFormItem>
          <ElFormItem label="变更原因">
            <ElInput v-model="candidateForm.changeReason" type="textarea" :rows="2" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="candidateDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="submitting" :disabled="!candidateJsonValid" @click="submitCandidate">
            保存
          </ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="evalDialogVisible" title="候选沙箱评估" width="520px" destroy-on-close>
        <ElForm :model="evalForm" label-width="110px">
          <ElFormItem label="候选版本">
            <ElInput :model-value="activeCandidate?.candidateName || '-'" disabled />
          </ElFormItem>
          <ElFormItem label="沙箱运行" required>
            <ElSelect
              v-model="evalForm.sandboxRunId"
              filterable
              clearable
              placeholder="选择沙箱运行"
              :loading="runOptionsLoading"
              @visible-change="handleRunOptionsVisible"
            >
              <ElOption
                v-for="item in runOptions"
                :key="String(item.id)"
                :label="runLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="evalDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="submitting" @click="submitCandidateEval">评估</ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="releaseDialogVisible" title="发布审批记录" width="560px" destroy-on-close>
        <ElForm :model="releaseForm" label-width="128px">
          <ElFormItem label="候选版本">
            <ElInput :model-value="activeCandidate?.candidateName || '-'" disabled />
          </ElFormItem>
          <ElFormItem label="当前配置哈希" required>
            <ElInput v-model="releaseForm.currentHash" clearable />
          </ElFormItem>
          <ElFormItem label="审批原因">
            <ElInput v-model="releaseForm.reason" type="textarea" :rows="3" />
          </ElFormItem>
          <ElFormItem label="审批 JSON">
            <JsonObjectEditor
              v-model="releaseForm.approvalJson"
              output-type="string"
              :rows="3"
              @validity-change="jsonValidity.releaseApproval = $event"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="releaseDialogVisible = false">取消</ElButton>
          <ElButton
            type="primary"
            :loading="submitting"
            :disabled="!jsonValidity.releaseApproval"
            @click="submitRelease"
          >
            记录审批
          </ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="rollbackDialogVisible" title="回滚记录" width="520px" destroy-on-close>
        <ElForm :model="rollbackForm" label-width="110px">
          <ElFormItem label="发布记录 ID" required>
            <ElInput v-model="rollbackForm.releaseId" clearable />
          </ElFormItem>
          <ElFormItem label="回滚原因">
            <ElInput v-model="rollbackForm.reason" type="textarea" :rows="3" />
          </ElFormItem>
          <ElFormItem label="审批 JSON">
            <JsonObjectEditor
              v-model="rollbackForm.approvalJson"
              output-type="string"
              :rows="3"
              @validity-change="jsonValidity.rollbackApproval = $event"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="rollbackDialogVisible = false">取消</ElButton>
          <ElButton
            type="primary"
            :loading="submitting"
            :disabled="!jsonValidity.rollbackApproval"
            @click="submitRollback"
          >
            记录回滚
          </ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="compareVisible" title="候选对比与门禁" width="860px" destroy-on-close>
        <div v-loading="compareLoading" class="compare-body">
          <CandidateGatePanel :compare="compareResult" />
        </div>
      </ElDialog>

      <OfflineEvalDialog
        v-model:visible="offlineEvalVisible"
        :candidate="activeCandidate"
        @started="handleOfflineEvalStarted"
      />
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Check, Connection, Edit, Plus, Refresh, Search, VideoPlay, View } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import evaluationService from '@/views/ai-agent/services/evaluation';
import type { EvalRun } from '@/views/ai-agent/services/evaluation';
import optimizationService from '@/views/ai-agent/services/optimization';
import type {
  OptCandidate,
  OptCandidateCompare,
  OptCandidateEval,
  OptCandidateEvalRequest,
  OptCandidateRequest,
  OptExperiment,
  OptExperimentDetail,
  OptExperimentQueryRequest,
  OptExperimentRequest,
  OptId,
  OptReleaseApprovalRequest
} from '@/views/ai-agent/services/optimization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { haveAuth } from '@/mixins/userAuth.js';
import CandidateGatePanel from './components/candidate-gate-panel.vue';
import LoopStatusPanel from './components/loop-status-panel.vue';
import OfflineEvalDialog from './components/offline-eval-dialog.vue';

defineOptions({ name: 'AgentOptimizationsPage' });

const OPTIMIZATION_MANAGE_PERMISSION = 'agent:optimization:manage';
const OPTIMIZATION_RELEASE_PERMISSION = 'agent:optimization:release';
const OPTIMIZATION_ROLLBACK_PERMISSION = 'agent:optimization:rollback';
/** 自进化 LOOP 端点权限码（后端 @SaCheckPermission，含离线评估发起与 LOOP 状态查询）。 */
const OPTIMIZATION_LOOP_PERMISSION = 'ai-agent:optimization:loop';

const canManageOptimization = computed(() => haveAuth(OPTIMIZATION_MANAGE_PERMISSION));
const canReleaseOptimization = computed(() => haveAuth(OPTIMIZATION_RELEASE_PERMISSION));
const canRollbackOptimization = computed(() => haveAuth(OPTIMIZATION_ROLLBACK_PERMISSION));
const canUseLoop = computed(() => haveAuth(OPTIMIZATION_LOOP_PERMISSION));

const experimentStatusOptions = [
  { label: '草稿', value: 'draft' },
  { label: '已诊断', value: 'diagnosed' },
  { label: '已有候选', value: 'candidate_created' },
  { label: '已评估', value: 'evaluated' }
];
/** 与后端 AgentOptimizationService.ALLOWED_TARGET_TYPES 对齐：权限/凭据/工具代码等禁止项后端会显式拒绝。 */
const targetTypeOptions = [
  { label: '提示词', value: 'PROMPT' },
  { label: '路由示例', value: 'ROUTE_EXAMPLE' },
  { label: '技能描述', value: 'SKILL_DESCRIPTION' },
  { label: '澄清问题', value: 'CLARIFY_QUESTION' },
  { label: '记忆召回策略', value: 'MEMORY_RECALL_POLICY' },
  { label: '模型路由', value: 'MODEL_ROUTE' },
  { label: 'Flow 抽取样例', value: 'FLOW_EXTRACTION_SAMPLE' }
];

const query = reactive<OptExperimentQueryRequest>({ experimentName: '', subjectId: '', status: '' });
const page = reactive({ current: 1, size: 20, total: 0 });
const experimentRows = ref<OptExperiment[]>([]);
const runOptions = ref<EvalRun[]>([]);
const runOptionsLoaded = ref(false);
const runOptionsLoading = ref(false);
const loading = ref(false);
const detailLoading = ref(false);
const detailRequestSeq = ref(0);
const submitting = ref(false);
const detailVisible = ref(false);
const experimentDialogVisible = ref(false);
const candidateDialogVisible = ref(false);
const evalDialogVisible = ref(false);
const releaseDialogVisible = ref(false);
const rollbackDialogVisible = ref(false);
const compareVisible = ref(false);
const compareLoading = ref(false);
const offlineEvalVisible = ref(false);
const loopPanelRef = ref<InstanceType<typeof LoopStatusPanel> | null>(null);
const selectedExperiment = ref<OptExperiment | null>(null);
const activeCandidate = ref<OptCandidate | null>(null);
const compareResult = ref<OptCandidateCompare | null>(null);
const detail = reactive<OptExperimentDetail>({ candidates: [], latestCandidateEvaluations: [] });
const experimentForm = reactive<OptExperimentRequest>(defaultExperimentForm());
const candidateForm = reactive<OptCandidateRequest>(defaultCandidateForm());
const evalForm = reactive<OptCandidateEvalRequest>({ sandboxRunId: '' });
const releaseForm = reactive<OptReleaseApprovalRequest>({ currentHash: '', reason: '', approvalJson: '{}' });
const rollbackForm = reactive({ releaseId: '', reason: '', approvalJson: '{}' });
const jsonValidity = reactive({
  candidatePatch: true,
  candidateRollback: true,
  releaseApproval: true,
  rollbackApproval: true
});
const candidateJsonValid = computed(() => jsonValidity.candidatePatch && jsonValidity.candidateRollback);

function defaultExperimentForm(): OptExperimentRequest {
  return { experimentName: '', baselineRunId: '', subjectId: '', description: '' };
}

function defaultCandidateForm(): OptCandidateRequest {
  return {
    candidateName: '',
    targetType: 'PROMPT',
    targetId: '',
    patchSchemaVersion: 'v1',
    targetVersion: '',
    beforeHash: '',
    afterHash: '',
    changeReason: '',
    expectedImpact: '',
    patchJson: '{}',
    beforeSnapshotJson: '{}',
    rollbackPatchJson: '{}',
    riskLevel: 'medium',
    generatedBy: 'manual'
  };
}

async function loadRunOptions(force = false) {
  if (runOptionsLoaded.value && !force) {
    return;
  }
  runOptionsLoading.value = true;
  try {
    const response = await evaluationService.queryRunsPage({ current: 1, size: 100 });
    runOptions.value = response.data;
    runOptionsLoaded.value = true;
  } catch {
    ElMessage.warning('评估运行列表加载失败，可手动输入 ID 后操作');
  } finally {
    runOptionsLoading.value = false;
  }
}

function numericIdOrEmpty(value?: string) {
  const text = trimmed(value);
  return text && /^\d+$/.test(text) ? text : '';
}

async function loadExperiments() {
  if (trimmed(query.subjectId) && !numericIdOrEmpty(query.subjectId)) {
    ElMessage.warning('评估对象请填评估对象主键（纯数字），可留空');
    return;
  }
  loading.value = true;
  try {
    const response = await optimizationService.queryExperimentsPage({
      ...query,
      subjectId: numericIdOrEmpty(query.subjectId),
      current: page.current,
      size: page.size
    });
    experimentRows.value = response.data;
    page.total = response.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '优化实验查询失败');
  } finally {
    loading.value = false;
  }
}

async function loadDetail(id: OptId) {
  detailLoading.value = true;
  const requestSeq = detailRequestSeq.value + 1;
  detailRequestSeq.value = requestSeq;
  try {
    const response = await optimizationService.getExperimentDetail(id);
    if (requestSeq !== detailRequestSeq.value) {
      return;
    }
    selectedExperiment.value = response.experiment || selectedExperiment.value;
    detail.candidates = response.candidates || [];
    detail.latestCandidateEvaluations = response.latestCandidateEvaluations || [];
  } catch (error) {
    if (requestSeq === detailRequestSeq.value) {
      ElMessage.error(error instanceof Error ? error.message : '优化实验详情查询失败');
    }
  } finally {
    if (requestSeq === detailRequestSeq.value) {
      detailLoading.value = false;
    }
  }
}

function handleSearch() {
  page.current = 1;
  loadExperiments();
}

function handleReset() {
  Object.assign(query, { experimentName: '', subjectId: '', status: '' });
  handleSearch();
}

function handleSizeChange() {
  page.current = 1;
  loadExperiments();
}

function handleRunOptionsVisible(visible: boolean) {
  if (visible) {
    loadRunOptions();
  }
}

async function openExperimentDialog() {
  await loadRunOptions();
  Object.assign(experimentForm, defaultExperimentForm());
  experimentDialogVisible.value = true;
}

function openDetail(row: OptExperiment) {
  selectedExperiment.value = row;
  detailVisible.value = true;
  if (row.id) {
    loadDetail(row.id);
  }
}

function openCandidateDialog(row: OptExperiment) {
  selectedExperiment.value = row;
  jsonValidity.candidatePatch = true;
  jsonValidity.candidateRollback = true;
  Object.assign(candidateForm, defaultCandidateForm());
  candidateDialogVisible.value = true;
}

async function openEvalDialog(row: OptCandidate) {
  await loadRunOptions();
  activeCandidate.value = row;
  Object.assign(evalForm, { sandboxRunId: row.sandboxRunId || '' });
  evalDialogVisible.value = true;
}

function openOfflineEvalDialog(row: OptCandidate) {
  activeCandidate.value = row;
  offlineEvalVisible.value = true;
}

/** 离线评估运行已发起（异步执行）：刷新候选状态与 LOOP 面板，运行结束后再做门禁判定。 */
async function handleOfflineEvalStarted() {
  if (selectedExperiment.value?.id) {
    await loadDetail(selectedExperiment.value.id);
  }
  loopPanelRef.value?.reload();
}

function openReleaseDialog(row: OptCandidate) {
  activeCandidate.value = row;
  jsonValidity.releaseApproval = true;
  Object.assign(releaseForm, { currentHash: row.beforeHash || '', reason: '', approvalJson: '{}' });
  releaseDialogVisible.value = true;
}

function openRollbackDialog() {
  jsonValidity.rollbackApproval = true;
  Object.assign(rollbackForm, { releaseId: '', reason: '', approvalJson: '{}' });
  rollbackDialogVisible.value = true;
}

async function submitExperiment() {
  if (!trimmed(experimentForm.experimentName) || !experimentForm.baselineRunId) {
    ElMessage.warning('请填写实验名称并选择基线运行');
    return;
  }
  submitting.value = true;
  try {
    await optimizationService.createExperiment({
      experimentName: experimentForm.experimentName,
      baselineRunId: experimentForm.baselineRunId,
      description: experimentForm.description
    });
    ElMessage.success('优化实验已创建');
    experimentDialogVisible.value = false;
    await loadExperiments();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '优化实验创建失败');
  } finally {
    submitting.value = false;
  }
}

async function diagnose(row: OptExperiment) {
  if (!row.id) return;
  submitting.value = true;
  try {
    const updated = await optimizationService.diagnoseExperiment(row.id);
    ElMessage.success('诊断已生成');
    await loadExperiments();
    if (selectedExperiment.value?.id && sameId(selectedExperiment.value.id, updated.id)) {
      await loadDetail(selectedExperiment.value.id);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '诊断生成失败');
  } finally {
    submitting.value = false;
  }
}

async function submitCandidate() {
  if (!selectedExperiment.value?.id) return;
  if (!candidateJsonValid.value) {
    ElMessage.warning('请先修正候选配置中的 JSON 格式错误');
    return;
  }
  const error = validateCandidate();
  if (error) {
    ElMessage.warning(error);
    return;
  }
  submitting.value = true;
  try {
    await optimizationService.createCandidate(selectedExperiment.value.id, candidateForm);
    ElMessage.success('候选版本已保存');
    candidateDialogVisible.value = false;
    await Promise.all([loadExperiments(), loadDetail(selectedExperiment.value.id)]);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '候选版本保存失败');
  } finally {
    submitting.value = false;
  }
}

async function submitCandidateEval() {
  if (!activeCandidate.value?.id || !evalForm.sandboxRunId) {
    ElMessage.warning('请选择沙箱评估运行');
    return;
  }
  submitting.value = true;
  try {
    await optimizationService.evaluateCandidate(activeCandidate.value.id, evalForm);
    ElMessage.success('候选评估已记录');
    evalDialogVisible.value = false;
    if (selectedExperiment.value?.id) {
      await loadDetail(selectedExperiment.value.id);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '候选评估失败');
  } finally {
    submitting.value = false;
  }
}

async function openCompare(row: OptCandidate) {
  if (!row.id) return;
  compareResult.value = null;
  compareVisible.value = true;
  compareLoading.value = true;
  try {
    compareResult.value = await optimizationService.getCandidateCompare(row.id);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '候选对比查询失败');
  } finally {
    compareLoading.value = false;
  }
}

async function submitRelease() {
  if (!jsonValidity.releaseApproval) {
    ElMessage.warning('请先修正审批配置中的 JSON 格式错误');
    return;
  }
  if (!activeCandidate.value?.id || !trimmed(releaseForm.currentHash)) {
    ElMessage.warning('请填写当前配置哈希');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '审批通过后会把候选覆盖写入智能体活体配置并立即发布（DataAgent.publish）。请确认评估门禁已通过。',
      '记录发布审批',
      { type: 'warning' }
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    const release = await optimizationService.createReleaseApproval(activeCandidate.value.id, releaseForm);
    releaseDialogVisible.value = false;
    if (selectedExperiment.value?.id) {
      await loadDetail(selectedExperiment.value.id);
    }
    loopPanelRef.value?.reload();
    ElMessage.success(`审批已通过，候选已写入活体并发布：${release.id || '-'}`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布审批记录失败');
  } finally {
    submitting.value = false;
  }
}

async function submitRollback() {
  if (!jsonValidity.rollbackApproval) {
    ElMessage.warning('请先修正审批配置中的 JSON 格式错误');
    return;
  }
  if (!rollbackForm.releaseId) {
    ElMessage.warning('请填写发布记录 ID');
    return;
  }
  submitting.value = true;
  try {
    await optimizationService.recordRollback(rollbackForm.releaseId, {
      reason: rollbackForm.reason,
      approvalJson: rollbackForm.approvalJson
    });
    ElMessage.success('回滚记录已写入');
    rollbackDialogVisible.value = false;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '回滚记录失败');
  } finally {
    submitting.value = false;
  }
}

function validateCandidate() {
  if (!trimmed(candidateForm.candidateName)) return '请填写候选名称';
  if (!trimmed(candidateForm.targetType)) return '请选择目标类型';
  if (!trimmed(candidateForm.targetId)) return '请填写目标 ID';
  if (!trimmed(candidateForm.beforeHash)) return '请填写变更前哈希';
  if (!trimmed(candidateForm.afterHash)) return '请填写变更后哈希';
  return validateJsonFields([
    ['补丁 JSON', candidateForm.patchJson],
    ['回滚 JSON', candidateForm.rollbackPatchJson],
    ['变更前快照 JSON', candidateForm.beforeSnapshotJson]
  ]);
}

function validateJsonFields(fields: Array<[string, string | undefined]>) {
  for (const [label, value] of fields) {
    if (!trimmed(value)) continue;
    try {
      JSON.parse(value as string);
    } catch {
      return `${label} 不是合法 JSON`;
    }
  }
  return '';
}

function latestEval(candidate: OptCandidate): OptCandidateEval | undefined {
  return detail.latestCandidateEvaluations?.find(item => sameId(item.candidateId, candidate.id));
}

function latestEvalText(candidate: OptCandidate) {
  const evalRow = latestEval(candidate);
  if (!evalRow) return '未评估';
  return `分差 ${formatSigned(evalRow.scoreDelta)} / 通过率 ${formatPercent(evalRow.passRateDelta)}`;
}

/** 硬门禁违规摘要（写副作用 / 权限或租户隔离），有违规才展示，作为审批依据醒目提示。 */
function latestEvalViolationText(candidate: OptCandidate) {
  const evalRow = latestEval(candidate);
  if (!evalRow) return '';
  const writeViolations = evalRow.writeViolationCount ?? 0;
  const isolationViolations = evalRow.isolationViolationCount ?? 0;
  if (writeViolations === 0 && isolationViolations === 0) return '';
  return `硬门禁违规：写副作用 ${writeViolations} 次 / 权限隔离 ${isolationViolations} 次（要求为 0）`;
}

function gatePassed(candidate: OptCandidate) {
  const parsed = parseJson(candidate.gateResultJson);
  return parsed?.passed === true || latestEval(candidate)?.passed === true;
}

function runLabel(item: EvalRun) {
  return `Run ${item.id || '-'} / ${runStatusLabel(item.status)} / ${formatDateTime(item.createTime)}`;
}

function metricSummary(json?: string) {
  const values = parseJson(json);
  if (!values) return '-';
  const score = formatScore(values.averageScore as number | string | null | undefined);
  const passRate = formatPercent(values.passRate as number | string | null | undefined);
  const p90 = values.p90DurationMs ? `${values.p90DurationMs}ms` : '-';
  return `均分 ${score}，通过率 ${passRate}，p90 ${p90}`;
}

function experimentStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    draft: '草稿',
    diagnosed: '已诊断',
    candidate_created: '已有候选',
    evaluated: '已评估'
  };
  return labels[value || ''] || value || '-';
}

function candidateStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    draft: '草稿',
    offline_evaluating: '离线评估中',
    evaluated: '已评估',
    gate_blocked: '门禁未过',
    release_approved: '已审批（草稿已建）'
  };
  return labels[value || ''] || value || '-';
}

function runStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    queued: '排队中',
    running: '运行中',
    success: '成功',
    failed: '失败',
    timeout: '超时',
    cancelled: '已取消'
  };
  return labels[value || ''] || value || '-';
}

function statusTag(value?: string) {
  if (value === 'evaluated' || value === 'success' || value === 'release_approved') return 'success';
  if (value === 'gate_blocked' || value === 'failed' || value === 'timeout') return 'danger';
  if (value === 'running' || value === 'candidate_created' || value === 'offline_evaluating') return 'warning';
  return 'info';
}

function sameId(left?: OptId | '', right?: OptId | '') {
  return String(left || '') === String(right || '');
}

function formatScore(value?: number | string | null) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? numeric.toFixed(2) : '-';
}

function formatPercent(value?: number | string | null) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? `${numeric.toFixed(2)}%` : '-';
}

function formatSigned(value?: number | string | null) {
  const numeric = Number(value || 0);
  if (!Number.isFinite(numeric)) return '-';
  return `${numeric >= 0 ? '+' : ''}${numeric.toFixed(2)}`;
}

function formatDateTime(value?: string) {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
}

function jsonText(value: unknown) {
  if (value === undefined || value === null || value === '') return '-';
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return JSON.stringify(value, null, 2);
}

function parseJson(value?: string): Record<string, unknown> | null {
  if (!trimmed(value)) return null;
  try {
    return JSON.parse(value as string);
  } catch {
    return null;
  }
}

function trimmed(value?: string) {
  return String(value || '').trim();
}

onMounted(loadExperiments);
</script>

<style scoped>
.opt-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.opt-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.opt-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.opt-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: 0;
}

.opt-header-actions,
.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.opt-panel {
  padding: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 22px rgb(15 23 42 / 5%);
}

.filter-panel {
  margin-bottom: 12px;
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

.opt-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.opt-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.opt-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.opt-table-wrap :deep(.el-table) {
  height: 100%;
}

.cell-strong {
  font-weight: 600;
  color: #111827;
}

.cell-muted {
  color: #64748b;
}

.cell-danger {
  margin-top: 2px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.compare-body {
  min-height: 120px;
}

.ml-8 {
  margin-left: 8px;
}

.opt-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.detail-body {
  display: grid;
  gap: 18px;
}

.detail-section {
  display: grid;
  gap: 10px;
}

.detail-section h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0;
}

.field-hint {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

pre {
  overflow: auto;
  max-height: 340px;
  margin: 0;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  color: #111827;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

@media (max-width: 920px) {
  .opt-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .opt-header-actions {
    flex-wrap: wrap;
  }

  .filter-panel :deep(.el-input),
  .filter-panel :deep(.el-select) {
    width: 100%;
  }
}
</style>
