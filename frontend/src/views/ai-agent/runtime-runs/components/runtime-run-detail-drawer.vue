<template>
  <ElDrawer
    v-model="visible"
    title="运行时间线详情"
    size="720px"
    destroy-on-close
    class="runtime-run-detail-drawer"
    @closed="handleClosed"
  >
    <div v-loading="loading" class="drawer-body">
      <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="drawer-alert" />
      <template v-else-if="run">
        <section class="drawer-section">
          <header class="drawer-section-header">
            <div class="drawer-run-title">
              <ElTag :type="runStateTagType(run.state)" effect="light">{{ runStateLabel(run.state) }}</ElTag>
              <ElTag v-if="isLiveAttached" type="primary" effect="plain" size="small">实时同步中</ElTag>
              <span class="drawer-run-id">运行ID: {{ run.id }}</span>
            </div>
            <div class="drawer-actions">
              <ElButton size="small" :loading="loading" @click="reload">刷新</ElButton>
              <ElButton
                v-if="isActiveRuntimeRunState(run.state)"
                size="small"
                type="danger"
                plain
                :disabled="!canCancel"
                :loading="cancelling"
                @click="handleCancel"
              >
                取消运行
              </ElButton>
              <ElButton
                v-if="isResumableRuntimeRunState(run.state)"
                size="small"
                type="primary"
                :disabled="!canResume"
                :loading="resuming"
                @click="handleResume"
              >
                恢复运行
              </ElButton>
            </div>
          </header>

          <ElAlert
            v-if="run.state === 'WAITING_APPROVAL'"
            type="warning"
            :closable="false"
            show-icon
            class="drawer-alert"
            :title="
              canQueryApproval
                ? '存在待处理审批，运行已暂停，请在下方「审批记录」中处理后恢复执行。'
                : '存在待处理审批，运行已暂停；当前账号没有 ai-agent:approval:query 权限，无法查看审批记录。'
            "
          />

          <ElDescriptions :column="2" border size="small" class="drawer-descriptions">
            <ElDescriptionsItem label="问题 / 任务" :span="2">{{ run.query || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="运行模式">{{ runModeLabel(run.runMode) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="触发来源">{{ run.triggerSource || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="执行主体">
              {{ runOwnerTypeLabel(run.ownerType) }}{{ run.ownerId ? ` · ${run.ownerId}` : '' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="所属数字员工">{{ run.digitalEmployeeId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="Release">{{ run.releaseId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="发起人">{{ run.initiatorUserName || run.initiatorUserId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="执行 Principal">
              {{ run.subjectKind || '-' }}{{ run.executionPrincipalId ? ` · ${run.executionPrincipalId}` : '' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="开始时间">{{ formatRunDateTime(run.startedAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="结束时间">{{ formatRunDateTime(run.finishedAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="耗时">
              {{ formatRunDuration(run.startedAt, run.finishedAt, nowMs) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="截止时间">{{ formatRunDateTime(run.deadlineAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="业务凭证（请求ID）">{{ run.clientRequestId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="会话ID">{{ run.threadId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem v-if="run.errorCode || run.errorMessage" label="错误" :span="2">
              <span class="drawer-error-text">{{ [run.errorCode, run.errorMessage].filter(Boolean).join(' - ') }}</span>
            </ElDescriptionsItem>
          </ElDescriptions>
        </section>

        <section v-if="canQueryApproval" class="drawer-section">
          <RuntimeApprovalList
            ref="approvalListRef"
            :run-id="run.id"
            :can-review="canReviewApproval"
            @decided="handleApprovalDecided"
          />
        </section>

        <section v-if="detail?.finalAnswer" class="drawer-section">
          <header class="drawer-section-header">
            <strong>最终回答</strong>
          </header>
          <div class="drawer-final-answer">{{ detail.finalAnswer }}</div>
        </section>

        <section class="drawer-section">
          <header class="drawer-section-header">
            <strong>步骤时间线</strong>
            <span class="drawer-section-count">{{ stepItems.length }} 步</span>
          </header>
          <ElEmpty
            v-if="stepItems.length === 0"
            description="暂无步骤（对话/直连运行可能不产生步骤）"
            :image-size="72"
          />
          <div v-else class="runtime-progress-list">
            <div
              v-for="item in stepItems"
              :key="item.key"
              class="runtime-progress-item"
              :class="`is-${item.status}`"
              :style="{ paddingLeft: `${item.depth * 18}px` }"
            >
              <span class="runtime-progress-dot" aria-hidden="true"></span>
              <span class="runtime-progress-label">{{ item.label }}</span>
              <span class="runtime-progress-status">{{ item.statusLabel }}</span>
              <span v-if="item.durationText" class="runtime-progress-duration">{{ item.durationText }}</span>
            </div>
          </div>
          <div v-for="step in failedSteps" :key="`step-error-${step.stepKey}`" class="drawer-step-error">
            <span>{{ step.stepName || step.stepKey }}：</span>
            <span class="drawer-error-text">{{ [step.errorCode, step.errorMessage].filter(Boolean).join(' - ') }}</span>
          </div>
        </section>

        <section class="drawer-section">
          <header class="drawer-section-header">
            <strong>事件时间线</strong>
            <span class="drawer-section-count">{{ eventRows.length }} 条</span>
          </header>
          <ElEmpty v-if="eventRows.length === 0" description="暂无事件" :image-size="72" />
          <div v-else class="event-timeline">
            <div v-for="row in eventRows" :key="row.key" class="event-row" :class="`tone-${row.tone}`">
              <span class="event-seq">#{{ row.seq }}</span>
              <div class="event-main">
                <div class="event-head">
                  <span class="event-label">{{ row.label }}</span>
                  <span v-if="row.stepKey" class="event-step">步骤: {{ row.stepKey }}</span>
                  <span class="event-time">{{ row.timeText }}</span>
                </div>
                <div v-if="row.summary" class="event-summary">{{ row.summary }}</div>
              </div>
            </div>
          </div>
        </section>
      </template>
      <ElEmpty v-else-if="!loading" description="暂无运行详情" />
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import runtimeRunService, {
  isActiveRuntimeRunState,
  isResumableRuntimeRunState,
  type RuntimeEventResp,
  type RuntimeRunAttachment,
  type RuntimeRunDetailResp,
  type RuntimeRunId
} from '@/views/ai-agent/services/runtimeRun';
import {
  buildRuntimeEventRows,
  buildRuntimeStepTimeline,
  formatRunDateTime,
  formatRunDuration,
  runModeLabel,
  runOwnerTypeLabel,
  runStateLabel,
  runStateTagType
} from '../runtime-run-display';
import RuntimeApprovalList from './runtime-approval-list.vue';

defineOptions({ name: 'RuntimeRunDetailDrawer' });

defineProps<{
  canCancel: boolean;
  canResume: boolean;
  /** 对应权限码 ai-agent:approval:query */
  canQueryApproval: boolean;
  /** 对应权限码 ai-agent:approval:review */
  canReviewApproval: boolean;
}>();

const emit = defineEmits<{
  changed: [];
}>();

/** 事件时间线单次加载上限，超长运行按最近事件截断展示 */
const EVENT_LOAD_LIMIT = 500;

const visible = ref(false);
const loading = ref(false);
const loadError = ref('');
const cancelling = ref(false);
const resuming = ref(false);
const detail = ref<RuntimeRunDetailResp | null>(null);
const events = ref<RuntimeEventResp[]>([]);
const nowMs = ref(Date.now());
const isLiveAttached = ref(false);
const currentRunId = ref<RuntimeRunId | null>(null);
const approvalListRef = ref<InstanceType<typeof RuntimeApprovalList> | null>(null);
let attachment: RuntimeRunAttachment | null = null;
let tickTimer: ReturnType<typeof setInterval> | null = null;

const run = computed(() => detail.value?.run ?? null);
const stepItems = computed(() => buildRuntimeStepTimeline(detail.value?.steps, nowMs.value));
const failedSteps = computed(() =>
  (detail.value?.steps ?? []).filter(step => Boolean(step.errorCode || step.errorMessage))
);
const eventRows = computed(() => buildRuntimeEventRows(events.value));

const stopTick = () => {
  if (tickTimer !== null) {
    clearInterval(tickTimer);
    tickTimer = null;
  }
};

const startTick = () => {
  stopTick();
  tickTimer = setInterval(() => {
    nowMs.value = Date.now();
  }, 1000);
};

const detachLive = () => {
  attachment?.close();
  attachment = null;
  isLiveAttached.value = false;
};

const maxEventSeq = () => events.value.reduce((max, event) => Math.max(max, event.seq ?? 0), 0);

const appendEvent = (event: RuntimeEventResp) => {
  // attach 已按 seq 去重，这里防御 REST 回放与 SSE 交叠
  if ((event.seq ?? 0) <= 0 || events.value.some(item => item.seq === event.seq)) {
    return;
  }
  events.value = [...events.value, event];
};

const attachLive = (runId: RuntimeRunId) => {
  detachLive();
  isLiveAttached.value = true;
  attachment = runtimeRunService.attachRunEvents({
    runId,
    afterSeq: maxEventSeq(),
    onEvent: appendEvent,
    onTerminal: () => {
      isLiveAttached.value = false;
      attachment = null;
      // 终态后回读详情，取最终状态与步骤结果（loadDetail 内部自处理失败，不会 reject）
      loadDetail(runId, { attach: false });
      emit('changed');
    },
    onError: error => {
      isLiveAttached.value = false;
      attachment = null;
      console.error('运行事件流连接失败, runId=%s:', runId, error);
      ElMessage.error('运行事件流连接中断，可点击刷新重试');
    }
  });
};

async function loadDetail(runId: RuntimeRunId, options: { attach?: boolean } = {}) {
  loading.value = true;
  loadError.value = '';
  try {
    const [detailResp, eventList] = await Promise.all([
      runtimeRunService.detail(runId),
      runtimeRunService.events(runId, 0, EVENT_LOAD_LIMIT)
    ]);
    if (currentRunId.value !== runId) {
      return;
    }
    detail.value = detailResp;
    events.value = eventList;
    nowMs.value = Date.now();
    const shouldAttach = options.attach ?? true;
    if (shouldAttach && isActiveRuntimeRunState(detailResp.run?.state)) {
      attachLive(runId);
    } else if (!isActiveRuntimeRunState(detailResp.run?.state)) {
      detachLive();
    }
  } catch (error) {
    if (currentRunId.value === runId) {
      loadError.value = '运行详情加载失败，请稍后重试';
      console.error('加载运行详情失败, runId=%s:', runId, error);
    }
  } finally {
    if (currentRunId.value === runId) {
      loading.value = false;
    }
  }
}

const open = async (runId: RuntimeRunId) => {
  visible.value = true;
  currentRunId.value = runId;
  detail.value = null;
  events.value = [];
  loadError.value = '';
  startTick();
  await loadDetail(runId);
};

const reload = async () => {
  if (currentRunId.value) {
    approvalListRef.value?.reload();
    await loadDetail(currentRunId.value);
  }
};

/** 审批决定成功：回读运行详情（等待审批可能已恢复/终止）并通知列表刷新 */
const handleApprovalDecided = async () => {
  if (currentRunId.value) {
    await loadDetail(currentRunId.value);
  }
  emit('changed');
};

const handleCancel = async () => {
  const runId = currentRunId.value;
  if (!runId) {
    return;
  }
  try {
    await ElMessageBox.confirm('取消后运行将走状态机终止，是否继续？', '取消运行', { type: 'warning' });
  } catch {
    return;
  }
  cancelling.value = true;
  try {
    await runtimeRunService.cancel(runId, '管理页手动取消');
    ElMessage.success('已请求取消');
    await loadDetail(runId);
    emit('changed');
  } catch (error) {
    console.error('取消运行失败, runId=%s:', runId, error);
    ElMessage.error('取消运行失败，请稍后重试');
  } finally {
    cancelling.value = false;
  }
};

const handleResume = async () => {
  const runId = currentRunId.value;
  if (!runId) {
    return;
  }
  resuming.value = true;
  try {
    await runtimeRunService.resume(runId);
    ElMessage.success('已恢复运行');
    await loadDetail(runId);
    emit('changed');
  } catch (error) {
    console.error('恢复运行失败, runId=%s:', runId, error);
    ElMessage.error('恢复运行失败，请稍后重试');
  } finally {
    resuming.value = false;
  }
};

const handleClosed = () => {
  detachLive();
  stopTick();
  currentRunId.value = null;
  detail.value = null;
  events.value = [];
  loadError.value = '';
};

onBeforeUnmount(() => {
  detachLive();
  stopTick();
});

defineExpose({ open });
</script>

<style scoped>
.drawer-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 200px;
}

.drawer-alert {
  margin-bottom: 4px;
}

.drawer-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.drawer-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.drawer-section-header strong {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.drawer-section-count {
  padding: 2px 10px;
  border-radius: 999px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  font-size: 12px;
  font-weight: 600;
}

.drawer-run-title {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.drawer-run-id {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
}

.drawer-error-text {
  color: var(--el-color-danger);
  word-break: break-all;
}

.drawer-final-answer {
  max-height: 240px;
  padding: 10px 12px;
  overflow: auto;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 复用运行进度时间线（StreamingResponsePanel / runtimeProgressTimeline）的条目渲染模式 */
.runtime-progress-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.runtime-progress-item {
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.runtime-progress-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-primary) 12%, transparent);
}

.runtime-progress-item.is-success .runtime-progress-dot {
  background: var(--el-color-success);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-success) 14%, transparent);
}

.runtime-progress-item.is-failed .runtime-progress-dot {
  background: var(--el-color-danger);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-danger) 14%, transparent);
}

.runtime-progress-item.is-cancelled .runtime-progress-dot,
.runtime-progress-item.is-skipped .runtime-progress-dot {
  background: var(--el-color-info);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-info) 14%, transparent);
}

.runtime-progress-item.is-waiting .runtime-progress-dot,
.runtime-progress-item.is-pending .runtime-progress-dot,
.runtime-progress-item.is-ready .runtime-progress-dot {
  background: var(--el-color-warning);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-warning) 14%, transparent);
}

.runtime-progress-label {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-progress-status,
.runtime-progress-duration {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.drawer-step-error {
  padding: 6px 8px;
  background: var(--el-color-danger-light-9);
  border-radius: 6px;
  font-size: 12px;
}

.event-timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 360px;
  overflow: auto;
}

.event-row {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.event-row.tone-approval {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}

.event-row.tone-interruption {
  border-color: var(--el-color-warning-light-7);
  background: var(--el-fill-color-light);
}

.event-row.tone-success {
  border-color: var(--el-color-success-light-7);
  background: var(--el-color-success-light-9);
}

.event-row.tone-danger {
  border-color: var(--el-color-danger-light-7);
  background: var(--el-color-danger-light-9);
}

.event-seq {
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  padding-top: 2px;
}

.event-main {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.event-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.event-label {
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
}

.event-step {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
}

.event-time {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.event-summary {
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.6;
  word-break: break-word;
}
</style>
