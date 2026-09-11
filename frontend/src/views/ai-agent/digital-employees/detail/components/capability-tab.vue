<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 -->

<template>
  <div class="capability-tab">
    <ElAlert
      class="mb-16"
      type="info"
      :closable="false"
      show-icon
      title="能力来自技能市场"
      description="这里只能安装市场中已审核通过的当前版本。能力修改保存在员工草稿，发布当前配置后才会进入运行时。"
    />

    <section class="market-section">
      <div class="section-heading">
        <div>
          <h3>可安装能力</h3>
          <p>技能中心负责生产版本，技能市场负责审核与租户分发。</p>
        </div>
        <ElButton :loading="marketLoading" @click="loadMarket">
          <ElIcon><Refresh /></ElIcon>
          刷新市场
        </ElButton>
      </div>

      <ElForm inline class="market-filters" @submit.prevent>
        <ElFormItem label="名称">
          <ElInput v-model="marketQuery.listingName" clearable placeholder="搜索市场能力" @keyup.enter="searchMarket" />
        </ElFormItem>
        <ElFormItem label="分类">
          <ElInput v-model="marketQuery.category" clearable placeholder="分类" @keyup.enter="searchMarket" />
        </ElFormItem>
        <ElFormItem label="风险">
          <ElSelect v-model="marketQuery.riskLevel" clearable placeholder="全部" @change="searchMarket">
            <ElOption label="低风险" value="LOW" />
            <ElOption label="中风险" value="MEDIUM" />
            <ElOption label="高风险" value="HIGH" />
          </ElSelect>
        </ElFormItem>
        <ElButton type="primary" :loading="marketLoading" @click="searchMarket">查询</ElButton>
      </ElForm>

      <ElEmpty v-if="!canQueryMarket" description="暂无技能市场管理权限" />
      <ElEmpty v-else-if="!marketLoading && !marketRows.length" description="暂无可安装的市场能力" />
      <ElTable
        v-else
        v-loading="marketLoading"
        :data="marketRows"
        border
        stripe
        row-key="id"
        :row-class-name="marketRowClassName"
        empty-text="暂无可安装能力"
      >
        <ElTableColumn label="能力" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-strong">{{ row.listingName || '-' }}</div>
            <div class="cell-muted">{{ row.category || '未分类' }} · v{{ row.currentVersionNo ?? '-' }}</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发布方" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.publisherName || '平台' }}</template>
        </ElTableColumn>
        <ElTableColumn label="风险" width="100">
          <template #default="{ row }">
            <ElTag :type="riskTagType(row.riskLevel)" effect="light" size="small">{{ riskLabel(row.riskLevel) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="120" align="center">
          <template #default="{ row }">
            <ElButton
              v-if="canManage && row.currentVersionNo != null"
              type="primary"
              link
              :loading="installingId === String(row.id)"
              @click="install(row)"
            >
              {{ installedSkillIds.has(String(row.currentSkillVersionId || '')) ? '重新启用' : '安装' }}
            </ElButton>
            <ElTooltip v-else-if="!canManage" content="需要数字员工管理权限" placement="top">
              <span class="muted-action">只读</span>
            </ElTooltip>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElPagination
        v-if="canQueryMarket && marketPage.total > marketPage.size"
        v-model:current-page="marketPage.current"
        v-model:page-size="marketPage.size"
        class="market-pagination"
        background
        layout="total, prev, pager, next"
        :total="marketPage.total"
        @size-change="handleMarketSizeChange"
        @current-change="loadMarket"
      />
    </section>

    <section class="installed-section">
      <div class="section-heading">
        <div>
          <h3>已安装能力</h3>
          <p>历史通用绑定继续保留；没有对应市场当前版本时按历史来源展示。</p>
        </div>
        <ElButton :loading="loading" @click="loadCapabilities">
          <ElIcon><Refresh /></ElIcon>
          刷新绑定
        </ElButton>
      </div>

      <ElTable v-loading="loading" :data="capabilityRows" border stripe row-key="id" empty-text="暂无能力绑定">
        <ElTableColumn label="能力版本" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-strong">{{ capabilityLabel(row.skillVersionId) }}</div>
            <div v-if="isHistorical(row.skillVersionId)" class="cell-muted">历史绑定</div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120" align="center">
          <template #default="{ row }">
            <ElSwitch
              v-if="canEditCapabilities && row.id"
              v-model="row.enabled"
              inline-prompt
              active-text="启用"
              inactive-text="停用"
              :loading="updatingCapabilityId === String(row.id)"
              @change="value => updateEnabled(row, Boolean(value))"
            />
            <ElTag v-else :type="row.enabled ? 'success' : 'info'" effect="light" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="110" align="center">
          <template #default="{ row }">
            <ElButton v-if="canEditCapabilities && row.id" link type="danger" @click="unbind(row)">解绑</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { haveAuth } from '@/mixins/userAuth.js';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee, EmployeeCapability } from '@/views/ai-agent/services/digitalEmployee';
import skillMarketService, { SKILL_MARKET_MANAGE_PERMISSION } from '@/views/ai-agent/services/skillMarket';
import type { MarketRiskLevel, SkillMarketListing } from '@/views/ai-agent/services/skillMarket';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { EMPLOYEE_MANAGE_PERMISSION } from '../../employee-support';

defineOptions({ name: 'EmployeeCapabilityTab' });

const props = defineProps<{ employee: DigitalEmployee; canManage: boolean; focusListingId?: string }>();
const emit = defineEmits<{ changed: []; }>();
const canQueryMarket = computed(() => haveAuth(SKILL_MARKET_MANAGE_PERMISSION));
const canManage = computed(() => props.canManage && haveAuth(EMPLOYEE_MANAGE_PERMISSION));
const canEditCapabilities = computed(() => canManage.value && props.employee.status !== 'ARCHIVED');
const capabilityRows = ref<EmployeeCapability[]>([]);
const marketRows = ref<SkillMarketListing[]>([]);
const loading = ref(false);
const marketLoading = ref(false);
const installingId = ref('');
const updatingCapabilityId = ref('');
const marketPage = reactive({ current: 1, size: 8, total: 0 });
const marketQuery = reactive<{ listingName: string; category: string; riskLevel?: MarketRiskLevel }>({ listingName: '', category: '', riskLevel: undefined });
const marketSkillMap = computed(
  () => new Map(marketRows.value.filter(item => item.currentSkillVersionId).map(item => [String(item.currentSkillVersionId), item]))
);
const installedSkillIds = computed(() => new Set(capabilityRows.value.map(row => String(row.skillVersionId || ''))));
const riskLabel = (risk?: MarketRiskLevel) =>
  ({ LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' } as Record<string, string>)[risk || ''] || risk || '-';
const riskTagType = (risk?: MarketRiskLevel) => (risk === 'HIGH' ? 'danger' : risk === 'MEDIUM' ? 'warning' : 'success');
const capabilityLabel = (skillVersionId?: string) => {
  if (!skillVersionId) return '-';
  const listing = marketSkillMap.value.get(String(skillVersionId));
  return listing ? `${listing.listingName}（市场 v${listing.currentVersionNo ?? '-'}）` : `SkillVersion #${skillVersionId}`;
};
const isHistorical = (skillVersionId?: string) => Boolean(skillVersionId && !marketSkillMap.value.has(String(skillVersionId)));

function marketRowClassName({ row }: { row: SkillMarketListing }) {
  return props.focusListingId && String(row.id) === String(props.focusListingId) ? 'market-focus-row' : '';
}

async function scrollToFocusedListing() {
  if (!props.focusListingId) return;
  await nextTick();
  document.querySelector('.market-focus-row')?.scrollIntoView({ block: 'center', behavior: 'smooth' });
}

async function loadCapabilities() {
  if (!props.employee.id) return;
  loading.value = true;
  try { capabilityRows.value = await digitalEmployeeService.listCapabilities(props.employee.id); }
  catch (error) { ElMessage.error(extractApiErrorMessage(error, '能力清单查询失败')); }
  finally { loading.value = false; }
}

async function loadMarket() {
  if (!canQueryMarket.value) return;
  marketLoading.value = true;
  try {
    const result = await skillMarketService.availableForEmployee({ ...marketQuery, current: marketPage.current, size: marketPage.size });
    marketRows.value = result.data;
    marketPage.total = result.total;
    await scrollToFocusedListing();
  }
  catch (error) { marketRows.value = []; marketPage.total = 0; ElMessage.error(extractApiErrorMessage(error, '技能市场加载失败')); }
  finally { marketLoading.value = false; }
}

function searchMarket() {
  marketPage.current = 1;
  loadMarket().catch(() => undefined);
}

function handleMarketSizeChange(size: number) {
  marketPage.size = size;
  marketPage.current = 1;
  loadMarket().catch(() => undefined);
}

async function install(row: SkillMarketListing) {
  if (!props.employee.id || !row.id || row.currentVersionNo === null || row.currentVersionNo === undefined || !canEditCapabilities.value) return;
  installingId.value = String(row.id);
  try {
    await digitalEmployeeService.installMarketSkill(props.employee.id, String(row.id), row.currentVersionNo);
    ElMessage.success('市场能力已安装到员工草稿，发布后生效');
    await Promise.all([loadCapabilities(), loadMarket()]);
    emit('changed');
  }
  catch (error) { ElMessage.error(extractApiErrorMessage(error, '市场能力安装失败，可能是版本已更新，请刷新后重试')); await loadMarket(); }
  finally { installingId.value = ''; }
}

async function updateEnabled(row: EmployeeCapability, enabled: boolean) {
  if (!props.employee.id || !row.id || !canEditCapabilities.value) return;
  const previous = !enabled;
  updatingCapabilityId.value = String(row.id);
  try {
    await digitalEmployeeService.updateCapabilityEnabled(props.employee.id, row.id, enabled);
    ElMessage.success(enabled ? '能力已启用' : '能力已停用');
    emit('changed');
  } catch (error) {
    row.enabled = previous;
    ElMessage.error(extractApiErrorMessage(error, '能力状态更新失败，请刷新后重试'));
  } finally {
    updatingCapabilityId.value = '';
  }
}

async function unbind(row: EmployeeCapability) {
  if (!props.employee.id || !row.id || !canEditCapabilities.value) return;
  try {
    await ElMessageBox.confirm('解绑后该能力不会进入后续发布快照，已发布快照不受影响。确认解绑？', '解绑确认', { type: 'warning', confirmButtonText: '解绑', cancelButtonText: '取消' });
    await digitalEmployeeService.unbindCapability(props.employee.id, row.id);
    ElMessage.success('能力已解绑');
    await loadCapabilities();
    emit('changed');
  }
  catch (error) { if (error === 'cancel' || error === 'close') return; ElMessage.error(extractApiErrorMessage(error, '能力解绑失败')); }
}

watch(() => [props.employee.id, props.focusListingId], () => {
  loadCapabilities().catch(() => undefined);
  loadMarket().catch(() => undefined);
});
onMounted(() => {
  loadCapabilities().catch(() => undefined);
  loadMarket().catch(() => undefined);
});
</script>

<style scoped>
.capability-tab { min-height: 100%; }
.mb-16 { margin-bottom: 16px; }
.market-section, .installed-section { padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-bg-color); }
.installed-section { margin-top: 16px; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.section-heading h3 { margin: 0; color: var(--el-text-color-primary); font-size: 16px; }
.section-heading p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 12px; }
.market-filters { margin-bottom: 4px; }
.market-filters :deep(.el-form-item) { margin-bottom: 12px; }
.market-pagination { justify-content: flex-end; margin-top: 14px; }
.cell-strong { color: var(--el-text-color-primary); font-weight: 600; }
.cell-muted, .muted-action { color: var(--el-text-color-secondary); font-size: 12px; }
:deep(.market-focus-row td) { background: var(--el-color-primary-light-9) !important; }
@media (max-width: 720px) { .section-heading { flex-direction: column; } .market-filters { display: block; } .market-filters :deep(.el-form-item) { display: block; margin-right: 0; } .market-filters :deep(.el-input), .market-filters :deep(.el-select) { width: 100%; } }
</style>
