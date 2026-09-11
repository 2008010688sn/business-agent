<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDrawer v-model="visible" title="策略详情" size="62%" destroy-on-close>
    <div v-loading="loading" class="policy-detail">
      <template v-if="detail.id">
        <ElDescriptions :column="2" border size="small" class="detail-block">
          <ElDescriptionsItem label="策略编码">{{ detail.code || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="策略名称">{{ detail.name || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="状态">
            <ElTag :type="statusTagType(detail.status)" size="small">{{ statusLabel(detail.status) }}</ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="来源模板">{{ detail.templateCode || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="策略 ID">{{ detail.id }}</ElDescriptionsItem>
          <ElDescriptionsItem label="发布版本">
            {{ detail.currentVersionNo != null ? `v${detail.currentVersionNo}` : '未发布' }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <section class="detail-block">
          <h3 class="block-title">
            当前发布版本
            <span v-if="detail.currentPolicyHash" class="hash-chip" title="点击复制策略 hash" @click="copyHash(detail.currentPolicyHash)">
              hash: {{ shortHash(detail.currentPolicyHash) }}
            </span>
          </h3>
          <pre v-if="detail.currentPolicyJson" class="policy-json">{{ formatJson(detail.currentPolicyJson) }}</pre>
          <ElEmpty v-else description="未发布，无生效版本内容" :image-size="60" />
        </section>

        <section class="detail-block">
          <h3 class="block-title">
            最新草稿版本
            <span v-if="detail.draftVersionNo != null" class="block-title-muted">v{{ detail.draftVersionNo }}</span>
          </h3>
          <pre v-if="detail.draftPolicyJson" class="policy-json">{{ formatJson(detail.draftPolicyJson) }}</pre>
          <ElEmpty v-else description="无草稿（已发布未变更）" :image-size="60" />
        </section>

        <section class="detail-block">
          <h3 class="block-title">版本列表（不含 JSON 正文，hash 可比对内容一致性）</h3>
          <ElTable :data="versions" border stripe size="small" empty-text="暂无版本">
            <ElTableColumn label="版本号" width="80">
              <template #default="{ row }">v{{ row.versionNo ?? '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="是否已发布" width="100" align="center">
              <template #default="{ row }">
                <ElTag :type="row.published ? 'success' : 'info'" size="small">
                  {{ row.published ? '已发布' : '草稿' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="策略 hash" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.policyHash" class="hash-chip" @click="copyHash(row.policyHash)">{{ row.policyHash }}</span>
                <span v-else>-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="创建时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </ElTableColumn>
          </ElTable>
        </section>
      </template>
      <ElEmpty v-else-if="!loading" description="未找到策略详情" />
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import dayjs from 'dayjs';
import authorizationService from '@/views/ai-agent/services/authorization';
import type { PolicyDetailResp, PolicyVersionResp } from '@/views/ai-agent/services/authorization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { POLICY_STATUS_LABELS, POLICY_STATUS_TAG_TYPES } from '../authorization-constants';

defineOptions({ name: 'AuthorizationPolicyDetailDrawer' });

const props = defineProps<{
  visible: boolean;
  policyId: string;
}>();

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void;
}>();

const visible = computed({
  get: () => props.visible,
  set: value => emit('update:visible', value)
});

const loading = ref(false);
const detail = ref<PolicyDetailResp>({});
const versions = ref<PolicyVersionResp[]>([]);

watch(
  () => props.visible,
  async value => {
    if (!value || !props.policyId) return;
    await loadDetail();
  }
);

async function loadDetail() {
  loading.value = true;
  try {
    const [detailResp, versionList] = await Promise.all([
      authorizationService.fetchPolicyDetail(props.policyId),
      authorizationService.listPolicyVersions(props.policyId)
    ]);
    detail.value = detailResp;
    versions.value = versionList;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '策略详情加载失败'));
  } finally {
    loading.value = false;
  }
}

const statusLabel = (status?: string) => POLICY_STATUS_LABELS[status || ''] || status || '-';
const statusTagType = (status?: string) => POLICY_STATUS_TAG_TYPES[status || ''] || 'info';
const shortHash = (hash: string) => (hash.length > 16 ? `${hash.slice(0, 16)}…` : hash);

const formatJson = (value: string): string => {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
};

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

async function copyHash(hash: string) {
  try {
    await navigator.clipboard.writeText(hash);
    ElMessage.success('策略 hash 已复制');
  } catch {
    ElMessage.warning('复制失败，请手动选择复制');
  }
}
</script>

<style scoped>
.policy-detail {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.detail-block {
  margin: 0;
}

.block-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 10px;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
}

.block-title-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.policy-json {
  max-height: 300px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.hash-chip {
  cursor: pointer;
  color: var(--el-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  font-weight: 400;
}
</style>
