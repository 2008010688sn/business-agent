<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="shadow-gate-panel">
    <!-- 影子差异报告 -->
    <ElCard shadow="never" class="mb-8px">
      <template #header>
        <div class="card-header">
          <span>影子差异报告</span>
          <span class="card-header-sub">
            灰度视图（影子差异，不是生产审计台账）。生产决策审计请用上方 decisionId 查询。
          </span>
          <div class="card-header-actions">
            <ElButton v-if="canQuery" :loading="reportLoading" size="small" @click="loadReport">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
            <ElButton v-if="canQuery" size="small" :loading="rebuilding" @click="handleRebuild">
              <ElIcon><RefreshRight /></ElIcon>
              手动重建
            </ElButton>
          </div>
        </div>
      </template>

      <template v-if="report">
        <ElDescriptions :column="4" border size="small" class="report-desc">
          <ElDescriptionsItem label="聚合窗口">
            {{ formatDateTime(report.windowFrom) }} ~ {{ formatDateTime(report.windowTo) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="报告生成时刻">{{ formatDateTime(report.generatedAt) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="门禁阈值">
            {{ formatRate(report.enforceGateThreshold) }}（{{ ratePercent(report.enforceGateThreshold) }}）
          </ElDescriptionsItem>
          <ElDescriptionsItem label="窗口事件总数">{{ report.totalEvents ?? 0 }}</ElDescriptionsItem>
        </ElDescriptions>

        <section class="report-section">
          <h3 class="block-title">租户级差异汇总（按差异率降序）</h3>
          <ElTable :data="report.tenants || []" border stripe size="small" empty-text="窗口内无影子事件">
            <ElTableColumn prop="tenantId" label="租户 ID" width="120" fixed="left" />
            <ElTableColumn prop="total" label="事件总数" width="90" align="right" />
            <ElTableColumn prop="comparable" label="可比对数" width="90" align="right" />
            <ElTableColumn prop="matched" label="一致（MATCHED）" width="120" align="right" />
            <ElTableColumn label="不一致（MISMATCHED）" width="130" align="right">
              <template #default="{ row }">
                <span :class="{ 'mismatch-strong': (row.mismatched ?? 0) > 0 }">{{ row.mismatched ?? 0 }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="originalOnly" label="现网缺失（ORIGINAL_ONLY）" width="160" align="right" />
            <ElTableColumn label="差异率" width="120" align="right">
              <template #default="{ row }">{{ formatRate(row.mismatchRate) }}</template>
            </ElTableColumn>
            <ElTableColumn label="ENFORCE 门禁" width="110" align="center" fixed="right">
              <template #default="{ row }">
                <ElTag :type="row.enforceGatePassed ? 'success' : 'danger'" size="small">
                  {{ row.enforceGatePassed ? '通过' : '未通过' }}
                </ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
        </section>

        <section class="report-section">
          <h3 class="block-title">
            MISMATCHED 差异样本（按 decisionId 审计定位，限量按时间倒序）
          </h3>
          <ElForm inline class="audit-lookup">
            <ElFormItem label="审计 decisionId">
              <ElInput v-model="auditDecisionId" placeholder="输入 decisionId 查询审计" style="width: 280px" />
            </ElFormItem>
            <ElButton v-if="canQuery" :loading="auditLoading" @click="lookupAudit()">查询审计</ElButton>
          </ElForm>
          <ElTable :data="report.mismatchSamples || []" border stripe size="small" empty-text="无判定不一致样本">
            <ElTableColumn prop="decisionId" label="decisionId（审计定位键）" min-width="220" show-overflow-tooltip fixed="left">
              <template #default="{ row }">
                <ElButton v-if="row.decisionId" link type="primary" @click="lookupAudit(row.decisionId)">
                  {{ row.decisionId }}
                </ElButton>
                <span v-else>-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="tenantId" label="租户" width="100" />
            <ElTableColumn label="policyHash" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.policyHash" class="hash-chip" @click="copyText(row.policyHash)">{{ row.policyHash }}</span>
                <span v-else>-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="PDP 原因码" width="200">
              <template #default="{ row }">
                <div>{{ row.reasonCode || '-' }}</div>
                <span v-if="row.reasonCode" class="cell-muted">{{ REASON_CODE_LABELS[row.reasonCode] || '' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="comparisonStatus" label="比对状态" width="120" align="center">
              <template #default="{ row }">
                <ElTag :type="COMPARISON_STATUS_TAG_TYPES[row.comparisonStatus || ''] || 'info'" size="small">
                  {{ COMPARISON_STATUS_LABELS[row.comparisonStatus || ''] || row.comparisonStatus }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="比对时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.comparisonTimestamp) }}</template>
            </ElTableColumn>
          </ElTable>
        </section>

        <section v-if="report.taskObservation" class="report-section">
          <h3 class="block-title">任务侧观测（SKIPPED 台账与受理延迟）</h3>
          <ElDescriptions :column="5" border size="small" class="report-desc">
            <ElDescriptionsItem label="SKIPPED 总数">{{ report.taskObservation.skippedTotal ?? 0 }}</ElDescriptionsItem>
            <ElDescriptionsItem label="槽位冲突">{{ report.taskObservation.slotConflictTotal ?? 0 }}</ElDescriptionsItem>
            <ElDescriptionsItem label="其他跳过">{{ report.taskObservation.otherSkipTotal ?? 0 }}</ElDescriptionsItem>
            <ElDescriptionsItem label="受理延迟 P95（秒）">{{ report.taskObservation.workItemCreateP95Seconds ?? 0 }}</ElDescriptionsItem>
            <ElDescriptionsItem label="延迟样本数">{{ report.taskObservation.workItemSampleTotal ?? 0 }}</ElDescriptionsItem>
          </ElDescriptions>
        </section>
      </template>
      <ElEmpty v-else-if="!reportLoading" :description="reportMissingText">
        <ElButton v-if="canQuery && reportMissing" type="primary" size="small" :loading="rebuilding" @click="handleRebuild">
          立即手动重建
        </ElButton>
      </ElEmpty>
    </ElCard>

    <!-- ENFORCE 门禁 -->
    <ElCard shadow="never">
      <template #header>
        <div class="card-header">
          <span>ENFORCE 门禁（灰度 checklist）</span>
          <span class="card-header-sub">放行登记 fail-closed：评估不通过后端直接拒绝；进程重启后须重建报告并重新登记</span>
          <div class="card-header-actions">
            <ElButton v-if="canQuery" :loading="gateLoading" size="small" @click="loadOverview">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
          </div>
        </div>
      </template>

      <template v-if="overview">
        <ElAlert
          :type="overview.rolloutIntegrityPassed ? 'success' : 'error'"
          :closable="false"
          class="gate-alert"
        >
          <template #title>
            <span v-if="overview.rolloutIntegrityPassed">checklist 通过：无静默回退且无未验证 ENFORCE 租户</span>
            <span v-else>checklist 未通过：{{ overview.integrityActionRequired || '存在静默回退或未验证租户，按整改项逐条处理' }}</span>
          </template>
        </ElAlert>

        <ElDescriptions :column="4" border size="small" class="report-desc">
          <ElDescriptionsItem label="门禁启用">
            <ElTag :type="overview.gateEnabled ? 'success' : 'danger'" size="small">
              {{ overview.gateEnabled ? '已启用' : '未启用' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="可比样本下限">{{ overview.minComparable ?? 0 }}</ElDescriptionsItem>
          <ElDescriptionsItem label="影子报告就绪">
            <ElTag :type="overview.reportPresent ? 'success' : 'warning'" size="small">
              {{ overview.reportPresent ? '就绪' : '缺失' }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="报告生成时刻">{{ formatDateTime(overview.reportGeneratedAt) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="ENFORCE 白名单" :span="2">
            {{ joinList(overview.enforceTenantIds) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="本进程已放行登记" :span="2">
            {{ joinList(overview.admittedTenantIds) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="未验证 ENFORCE 租户" :span="2">
            <span :class="{ 'mismatch-strong': (overview.unverifiedEnforcedTenantIds?.length ?? 0) > 0 }">
              {{ joinList(overview.unverifiedEnforcedTenantIds) }}
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="静默回退租户（禁止发生）" :span="2">
            <span :class="{ 'mismatch-strong': (overview.silentRollbackTenantIds?.length ?? 0) > 0 }">
              {{ joinList(overview.silentRollbackTenantIds) }}
            </span>
          </ElDescriptionsItem>
          <ElDescriptionsItem v-if="(overview.decisionCounts?.length ?? 0) > 0" label="结论分布" :span="4">
            <ElTag
              v-for="item in overview.decisionCounts"
              :key="item.decision"
              :type="ENFORCE_GATE_DECISION_TAG_TYPES[item.decision || ''] || 'info'"
              size="small"
              class="decision-count-tag"
            >
              {{ ENFORCE_GATE_DECISION_LABELS[item.decision || ''] || item.decision }} × {{ item.count ?? 0 }}
            </ElTag>
          </ElDescriptionsItem>
        </ElDescriptions>

        <section class="report-section">
          <h3 class="block-title">白名单逐租户门禁评估明细</h3>
          <ElTable :data="overview.tenantEvaluations || []" border stripe size="small" empty-text="白名单为空或无评估数据">
            <ElTableColumn prop="tenantId" label="租户 ID" width="120" fixed="left" />
            <ElTableColumn label="结论" width="200">
              <template #default="{ row }">
                <ElTag :type="ENFORCE_GATE_DECISION_TAG_TYPES[row.decision || ''] || 'info'" size="small">
                  {{ ENFORCE_GATE_DECISION_LABELS[row.decision || ''] || row.decision }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="comparable" label="可比样本" width="90" align="right" />
            <ElTableColumn prop="matched" label="一致" width="80" align="right" />
            <ElTableColumn label="不一致" width="80" align="right">
              <template #default="{ row }">
                <span :class="{ 'mismatch-strong': (row.mismatched ?? 0) > 0 }">{{ row.mismatched ?? 0 }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="originalOnly" label="现网缺失" width="90" align="right" />
            <ElTableColumn label="差异率" width="110" align="right">
              <template #default="{ row }">{{ formatRate(row.mismatchRate) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="reason" label="结论说明" min-width="260" show-overflow-tooltip />
            <ElTableColumn label="操作" width="110" fixed="right" align="center">
              <template #default="{ row }">
                <ElButton
                  v-if="canManage && row.allowed && !isAdmitted(row.tenantId)"
                  link
                  type="success"
                  @click="handleAdmit(row)"
                >
                  放行登记
                </ElButton>
                <ElTag v-else-if="isAdmitted(row.tenantId)" type="success" size="small" effect="plain">已登记</ElTag>
              </template>
            </ElTableColumn>
          </ElTable>
        </section>
      </template>
      <ElEmpty v-else-if="!gateLoading" description="暂无门禁 checklist 数据" />

      <!-- 单租户准入评估 -->
      <ElDivider content-position="left">单租户准入评估（只读，不做放行登记）</ElDivider>
      <ElForm label-width="80px" inline>
        <ElFormItem label="租户 ID">
          <ElInput v-model="admissionTenantId" placeholder="与 enforceTenantIds 同口径" style="width: 240px" />
        </ElFormItem>
        <ElButton v-if="canQuery" :loading="admissionLoading" @click="handleEvaluateAdmission">
          <ElIcon><Search /></ElIcon>
          评估
        </ElButton>
      </ElForm>

      <template v-if="admissionResult">
        <ElDescriptions :column="3" border size="small" class="report-desc">
          <ElDescriptionsItem label="结论">
            <ElTag :type="ENFORCE_GATE_DECISION_TAG_TYPES[admissionResult.decision || ''] || 'info'" size="small">
              {{ ENFORCE_GATE_DECISION_LABELS[admissionResult.decision || ''] || admissionResult.decision }}
            </ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="可比样本">{{ admissionResult.comparable ?? 0 }}</ElDescriptionsItem>
          <ElDescriptionsItem label="差异率">{{ formatRate(admissionResult.mismatchRate) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="报告阈值">{{ formatRate(admissionResult.enforceGateThreshold) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="评估窗口" :span="2">
            {{ formatDateTime(admissionResult.reportWindowFrom) }} ~ {{ formatDateTime(admissionResult.reportWindowTo) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="结论说明" :span="3">{{ admissionResult.reason || '-' }}</ElDescriptionsItem>
        </ElDescriptions>
        <ElButton
          v-if="canManage && admissionResult.allowed"
          type="success"
          :loading="admitting"
          class="admit-btn"
          @click="handleAdmit(admissionResult)"
        >
          登记该租户放行
        </ElButton>
      </template>
    </ElCard>

    <ElDrawer v-model="auditDrawerVisible" title="授权决策审计" size="520px">
      <ElDescriptions v-if="auditDetail" :column="1" border size="small">
        <ElDescriptionsItem label="decisionId">{{ auditDetail.decisionId || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="runId">{{ auditDetail.runId || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="subjectKind">{{ auditDetail.subjectKind || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="reasonCode">{{ auditDetail.reasonCode || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="policyHash">{{ auditDetail.policyHash || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="occurredAt">{{ formatDateTime(auditDetail.occurredAt) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="detailedDecisionLog">
          <pre class="audit-log">{{ auditDetail.detailedDecisionLog || '-' }}</pre>
        </ElDescriptionsItem>
      </ElDescriptions>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, RefreshRight, Search } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { haveAuth } from '@/mixins/userAuth.js';
import authorizationShadowReportService from '@/views/ai-agent/services/authorizationShadowReport';
import type { ShadowDiffReport } from '@/views/ai-agent/services/authorizationShadowReport';
import authorizationService from '@/views/ai-agent/services/authorization';
import type { DecisionAuditResp } from '@/views/ai-agent/services/authorization';
import enforceGateService from '@/views/ai-agent/services/enforceGate';
import type { EnforceGateEvaluation, EnforceGateOverview } from '@/views/ai-agent/services/enforceGate';
import { extractApiErrorMessage, getResponseStatus } from '@/views/ai-agent/services/common';
import {
  AUTHORIZATION_MANAGE_PERMISSION,
  AUTHORIZATION_QUERY_PERMISSION,
  COMPARISON_STATUS_LABELS,
  COMPARISON_STATUS_TAG_TYPES,
  ENFORCE_GATE_DECISION_LABELS,
  ENFORCE_GATE_DECISION_TAG_TYPES,
  REASON_CODE_LABELS
} from '../authorization-constants';

defineOptions({ name: 'AuthorizationShadowGatePanel' });

const canQuery = computed(() => haveAuth(AUTHORIZATION_QUERY_PERMISSION));
const canManage = computed(() => haveAuth(AUTHORIZATION_MANAGE_PERMISSION));

// ---------- 影子差异报告 ----------
const report = ref<ShadowDiffReport | null>(null);
const reportLoading = ref(false);
const reportMissing = ref(false);
const rebuilding = ref(false);

const reportMissingText = computed(() =>
  reportMissing.value
    ? '尚未生成授权影子差异报告：请等待观测作业（agentAuthorizationShadowReportJob）首轮执行，或先手动重建'
    : '暂无影子差异报告数据'
);

async function loadReport() {
  reportLoading.value = true;
  reportMissing.value = false;
  try {
    report.value = await authorizationShadowReportService.fetchLatestReport();
  } catch (error) {
    report.value = null;
    if (getResponseStatus(error) === 404) {
      reportMissing.value = true;
    } else {
      ElMessage.error(extractApiErrorMessage(error, '影子差异报告查询失败'));
    }
  } finally {
    reportLoading.value = false;
  }
}

async function handleRebuild() {
  try {
    await ElMessageBox.confirm(
      '按当前配置窗口（默认近 24h）立即重算差异报告并覆盖最新？幂等只读聚合，供 ENFORCE 门禁评估前人工取数。',
      '手动重建确认',
      { type: 'warning', confirmButtonText: '重建', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  rebuilding.value = true;
  try {
    await authorizationShadowReportService.rebuildReport();
    ElMessage.success('影子差异报告重建完成');
    await loadReport();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '影子差异报告重建失败'));
  } finally {
    rebuilding.value = false;
  }
}

// ---------- ENFORCE 门禁 ----------
const overview = ref<EnforceGateOverview | null>(null);
const gateLoading = ref(false);

const admissionTenantId = ref('');
const admissionLoading = ref(false);
const admissionResult = ref<EnforceGateEvaluation | null>(null);
const admitting = ref(false);

async function loadOverview() {
  gateLoading.value = true;
  try {
    overview.value = await enforceGateService.fetchOverview();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, 'ENFORCE 门禁 checklist 查询失败'));
  } finally {
    gateLoading.value = false;
  }
}

const isAdmitted = (tenantId?: string) => Boolean(tenantId && (overview.value?.admittedTenantIds || []).includes(tenantId));

async function handleEvaluateAdmission() {
  const tenantId = admissionTenantId.value.trim();
  if (!tenantId) {
    ElMessage.warning('请输入租户 ID');
    return;
  }
  admissionLoading.value = true;
  try {
    admissionResult.value = await enforceGateService.evaluateAdmission(tenantId);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '准入评估失败'));
  } finally {
    admissionLoading.value = false;
  }
}

/** 放行登记：fail-closed，需二次确认（后端评估不通过会直接抛业务异常拒绝）。 */
async function handleAdmit(evaluation: EnforceGateEvaluation) {
  const tenantId = evaluation.tenantId || admissionTenantId.value.trim();
  if (!tenantId) return;
  try {
    await ElMessageBox.confirm(
      `确认登记租户「${tenantId}」ENFORCE 放行？登记后写入进程级登记簿供切换保护比对；进程重启后须重建影子差异报告并重新登记。`,
      '放行登记确认（fail-closed）',
      { type: 'warning', confirmButtonText: '确认放行登记', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' }
    );
  } catch {
    return;
  }
  admitting.value = true;
  try {
    const result = await enforceGateService.admitTenant(tenantId);
    ElMessage.success(`租户 ${tenantId} 放行登记成功`);
    if (admissionResult.value?.tenantId === tenantId) {
      admissionResult.value = result;
    }
    await loadOverview();
  } catch (error) {
    // fail-closed：后端评估不通过直接拒绝，展示明确失败原因
    ElMessage.error(extractApiErrorMessage(error, '放行登记失败（fail-closed 拒绝）'));
  } finally {
    admitting.value = false;
  }
}

const auditDecisionId = ref('');
const auditLoading = ref(false);
const auditDrawerVisible = ref(false);
const auditDetail = ref<DecisionAuditResp | null>(null);

async function lookupAudit(decisionId?: string) {
  const id = String(decisionId || auditDecisionId.value || '').trim();
  if (!id) {
    ElMessage.warning('请输入 decisionId');
    return;
  }
  auditDecisionId.value = id;
  auditLoading.value = true;
  try {
    auditDetail.value = await authorizationService.getDecision(id);
    auditDrawerVisible.value = true;
  } catch (error) {
    auditDetail.value = null;
    ElMessage.error(extractApiErrorMessage(error, '授权决策审计查询失败'));
  } finally {
    auditLoading.value = false;
  }
}

// ---------- 展示辅助 ----------
const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

/** 差异率/阈值展示：0.001 → "0.001（0.1%）" */
const formatRate = (value?: number) => (value ?? 0).toFixed(4);
const ratePercent = (value?: number) => `${((value ?? 0) * 100).toFixed(2)}%`;

const joinList = (list?: string[]) => (list && list.length > 0 ? list.join('、') : '-');

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success('已复制');
  } catch {
    ElMessage.warning('复制失败，请手动选择复制');
  }
}

onMounted(() => {
  if (!canQuery.value) return;
  loadReport();
  loadOverview();
});
</script>

<style scoped>
.mb-8px {
  margin-bottom: 8px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
}

.card-header-sub {
  flex: 1;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.card-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.report-desc {
  margin-bottom: 4px;
}

.report-section {
  margin-top: 16px;
}

.block-title {
  margin: 0 0 10px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.gate-alert {
  margin-bottom: 14px;
}

.decision-count-tag {
  margin-right: 8px;
}

.admit-btn {
  margin-top: 12px;
}

.mismatch-strong {
  color: var(--el-color-danger);
  font-weight: 600;
}

.audit-lookup {
  margin-bottom: 10px;
}

.audit-log {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
}

.cell-muted {
  color: #64748b;
  font-size: 12px;
}

.hash-chip {
  cursor: pointer;
  color: var(--el-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
</style>
