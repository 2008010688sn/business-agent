<template>
  <ElDrawer
    :model-value="modelValue"
    title="市场条目详情"
    size="680px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @open="loadDetail"
  >
    <div v-loading="loading" class="detail-body">
      <template v-if="detail">
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem label="条目名称" :span="2">{{ detail.listing.listingName }}</ElDescriptionsItem>
          <ElDescriptionsItem label="分类">{{ detail.listing.category || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="当前版本">
            {{ detail.listing.currentVersionNo ? `V${detail.listing.currentVersionNo}` : '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="发布者">{{ detail.listing.publisherName || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="发布租户">{{ detail.listing.publisherTenantId || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="审核状态">
            <ElTag size="small">{{ marketListingStatusLabel(detail.listing.reviewStatus) }}</ElTag>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="风险等级">{{ marketRiskLevelLabel(detail.listing.riskLevel) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="使用配额（次/日）">
            {{ detail.listing.defaultUsageQuota ?? '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="兼容引擎版本">
            {{ detail.listing.compatibleEngineVersion || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="撤销状态">
            <ElTag v-if="detail.listing.revoked" type="danger" size="small">已撤销</ElTag>
            <span v-else>未撤销</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="最后修改时间">{{ formatTime(detail.listing.lastModifyTime) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="条目说明" :span="2">{{ detail.listing.description || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="权限范围" :span="2">
            {{ detail.listing.permissionScope || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="数据范围" :span="2">{{ detail.listing.dataScope || '-' }}</ElDescriptionsItem>
        </ElDescriptions>

        <section class="json-section">
          <h3>输入输出 Schema（只读）</h3>
          <pre class="json-viewer">{{ prettyJson(detail.listing.ioSchemaSummary) }}</pre>
        </section>

        <section class="json-section">
          <h3>依赖资源（只读）</h3>
          <pre class="json-viewer">{{ prettyJson(detail.listing.dependentResources) }}</pre>
        </section>

        <section class="history-section">
          <h3>版本历史</h3>
          <ElTable :data="detail.versions" size="small" border>
            <ElTableColumn label="版本" width="70">
              <template #default="{ row }">V{{ row.versionNo }}</template>
            </ElTableColumn>
            <ElTableColumn prop="skillVersionId" label="引用 Skill 版本" min-width="140" show-overflow-tooltip />
            <ElTableColumn prop="contentHash" label="内容哈希" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.contentHash || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn prop="changeNote" label="变更说明" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.changeNote || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="创建" width="170">
              <template #default="{ row }">{{ row.createName || '-' }} {{ formatTime(row.createTime) }}</template>
            </ElTableColumn>
          </ElTable>
        </section>

        <section class="history-section">
          <h3>审核记录</h3>
          <ElTable :data="detail.reviews" size="small" border>
            <ElTableColumn prop="reviewerName" label="审核人" width="110">
              <template #default="{ row }">{{ row.reviewerName || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="结论" width="80">
              <template #default="{ row }">
                <ElTag :type="row.conclusion === 'APPROVED' ? 'success' : 'danger'" size="small">
                  {{ row.conclusion === 'APPROVED' ? '通过' : '驳回' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="opinion" label="审核意见" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.opinion || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="审核时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </ElTableColumn>
          </ElTable>
        </section>
      </template>
      <ElEmpty v-else-if="!loading" description="未能加载条目详情" />
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage } from 'element-plus';
import skillMarketService, {
  marketListingStatusLabel,
  marketRiskLevelLabel
} from '@/views/ai-agent/services/skillMarket';
import type { SkillMarketListingDetail } from '@/views/ai-agent/services/skillMarket';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

const props = defineProps<{
  modelValue: boolean;
  listingId: string;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
}>();

const loading = ref(false);
const detail = ref<SkillMarketListingDetail | null>(null);

async function loadDetail() {
  if (!props.listingId) {
    detail.value = null;
    return;
  }
  loading.value = true;
  detail.value = null;
  try {
    detail.value = await skillMarketService.detail(props.listingId);
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载市场条目详情失败'));
    }
  } finally {
    loading.value = false;
  }
}

function prettyJson(text?: string): string {
  if (!text || !text.trim()) {
    return '-';
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

function formatTime(value?: string): string {
  if (!value) {
    return '-';
  }
  return new Date(value).toLocaleString();
}
</script>

<style scoped>
.detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 120px;
}

.json-section h3,
.history-section h3 {
  margin: 0 0 8px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.json-viewer {
  max-height: 220px;
  overflow: auto;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
