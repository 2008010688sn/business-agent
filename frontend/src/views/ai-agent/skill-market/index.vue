<template>
  <BaseLayout class="skill-market-shell">
    <main class="skill-market-page">
      <ElCard>
        <header class="page-toolbar">
          <div>
            <h1>技能市场</h1>
            <p>发布者上架技能能力条目，平台审核通过后可安装到数字员工；撤销为终态并级联处置已安装项。</p>
          </div>
          <div class="toolbar-actions">
            <ElButton :icon="Refresh" :loading="loading" @click="loadListings">刷新列表</ElButton>
            <ElButton v-if="canManage" type="primary" @click="openEditor()">创建市场条目</ElButton>
          </div>
        </header>
      </ElCard>

      <ElCard class="skill-market-list-card">
        <section class="filter-panel">
          <ElForm :model="filters" label-width="70px">
            <ElRow :gutter="16">
              <ElCol :span="5">
                <ElFormItem label="条目名称">
                  <ElInput
                    v-model="filters.listingName"
                    clearable
                    placeholder="名称模糊匹配"
                    @keyup.enter="handleSearch"
                  />
                </ElFormItem>
              </ElCol>
              <ElCol :span="4">
                <ElFormItem label="分类">
                  <ElInput v-model="filters.category" clearable placeholder="分类" @keyup.enter="handleSearch" />
                </ElFormItem>
              </ElCol>
              <ElCol :span="4">
                <ElFormItem label="审核状态">
                  <ElSelect v-model="filters.reviewStatus" clearable placeholder="全部状态">
                    <ElOption
                      v-for="item in MARKET_LISTING_STATUS_OPTIONS"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElCol :span="4">
                <ElFormItem label="风险等级">
                  <ElSelect v-model="filters.riskLevel" clearable placeholder="全部等级">
                    <ElOption
                      v-for="item in MARKET_RISK_LEVEL_OPTIONS"
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
              <ElButton @click="resetFilters">
                <ElIcon><Refresh /></ElIcon>
                重置
              </ElButton>
            </ElRow>
          </ElForm>
        </section>

        <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="load-error-alert">
          <ElButton link type="primary" :loading="loading" @click="loadListings">重试</ElButton>
        </ElAlert>

        <div class="skill-market-table-wrap">
          <ElTable
            v-loading="loading"
            :data="listings"
            border
            stripe
            row-key="id"
            height="100%"
            :empty-text="loadError ? '加载失败，数据未获取到' : '暂无市场条目'"
          >
            <ElTableColumn prop="listingName" label="条目名称" min-width="180" show-overflow-tooltip />
            <ElTableColumn prop="category" label="分类" width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.category || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="当前版本" width="90">
              <template #default="{ row }">{{ row.currentVersionNo ? `V${row.currentVersionNo}` : '-' }}</template>
            </ElTableColumn>
            <ElTableColumn prop="publisherName" label="发布者" width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.publisherName || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="风险等级" width="90">
              <template #default="{ row }">
                <ElTag :type="riskTagType(row.riskLevel)" size="small">{{ marketRiskLevelLabel(row.riskLevel) }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="配额(次/日)" width="100">
              <template #default="{ row }">{{ row.defaultUsageQuota ?? '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="兼容版本" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ row.compatibleEngineVersion || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="审核状态" width="100">
              <template #default="{ row }">
                <ElTag :type="statusTagType(row.reviewStatus)" size="small">
                  {{ marketListingStatusLabel(row.reviewStatus) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="撤销状态" width="90">
              <template #default="{ row }">
                <ElTag v-if="row.revoked" type="danger" size="small">已撤销</ElTag>
                <span v-else>-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="280" fixed="right">
              <template #default="{ row }">
                <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
                <ElButton
                  v-if="canManage && isListingModifiable(row.reviewStatus)"
                  link
                  type="primary"
                  @click="openEditor(row)"
                >
                  编辑
                </ElButton>
                <ElButton
                  v-if="canManage && isListingModifiable(row.reviewStatus)"
                  link
                  type="success"
                  @click="openSubmitReview(row)"
                >
                  {{ row.reviewStatus === 'REJECTED' ? '重新提交' : '提交审核' }}
                </ElButton>
                <ElButton
                  v-if="canReview && row.reviewStatus === 'REVIEWING'"
                  link
                  type="warning"
                  @click="openReview(row)"
                >
                  审核
                </ElButton>
                <ElButton
                  v-if="canReview && row.reviewStatus === 'APPROVED' && !row.revoked"
                  link
                  type="danger"
                  @click="revokeListing(row)"
                >
                  撤销
                </ElButton>
                <ElButton
                  v-if="row.reviewStatus === 'APPROVED' && !row.revoked"
                  link
                  type="primary"
                  @click="goInstall(row)"
                >
                  安装到数字员工
                </ElButton>
                <ElButton
                  v-if="canManage && isListingModifiable(row.reviewStatus)"
                  link
                  type="danger"
                  @click="removeListing(row)"
                >
                  删除
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>
        <ElPagination
          v-model:current-page="pageQuery.current"
          v-model:page-size="pageQuery.size"
          class="page-pagination"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50, 100]"
          :total="pageTotal"
          @size-change="handlePageSizeChange"
          @current-change="loadListings"
        />
      </ElCard>

      <ListingEditorDrawer v-model="editorVisible" :listing="editingListing" @saved="handleSaved" />
      <ListingDetailDrawer v-model="detailVisible" :listing-id="activeListingId" />
      <SubmitReviewDialog v-model="submitVisible" :listing="activeListing" @submitted="handleSaved" />
      <ReviewDialog v-model="reviewVisible" :listing="activeListing" @reviewed="handleSaved" />
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import { haveAuth } from '@/mixins/userAuth.js';
import skillMarketService, {
  MARKET_LISTING_STATUS_OPTIONS,
  MARKET_RISK_LEVEL_OPTIONS,
  SKILL_MARKET_MANAGE_PERMISSION,
  SKILL_MARKET_REVIEW_PERMISSION,
  isListingModifiable,
  marketListingStatusLabel,
  marketRiskLevelLabel
} from '@/views/ai-agent/services/skillMarket';
import type {
  MarketListingStatus,
  MarketRiskLevel,
  SkillMarketListing,
  SkillMarketListingPageQuery
} from '@/views/ai-agent/services/skillMarket';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import ListingEditorDrawer from './listing-editor-drawer.vue';
import ListingDetailDrawer from './listing-detail-drawer.vue';
import SubmitReviewDialog from './submit-review-dialog.vue';
import ReviewDialog from './review-dialog.vue';

const router = useRouter();
const loading = ref(false);
/** 列表加载失败原因；非空时表格顶部常驻错误态，与「确实没有数据」区分开 */
const loadError = ref('');
const listings = ref<SkillMarketListing[]>([]);
const pageTotal = ref(0);
const pageQuery = reactive({ current: 1, size: 20 });
const filters = reactive<{
  listingName: string;
  category: string;
  reviewStatus: MarketListingStatus | '';
  riskLevel: MarketRiskLevel | '';
}>({ listingName: '', category: '', reviewStatus: '', riskLevel: '' });

const editorVisible = ref(false);
const detailVisible = ref(false);
const submitVisible = ref(false);
const reviewVisible = ref(false);
const editingListing = ref<SkillMarketListing | null>(null);
const activeListing = ref<SkillMarketListing | null>(null);
const activeListingId = ref('');

const canReview = computed(() => haveAuth(SKILL_MARKET_REVIEW_PERMISSION));
/** 创建/编辑/提交审核/删除后端均校验 manage 码，无权限时直接隐藏按钮 */
const canManage = computed(() => haveAuth(SKILL_MARKET_MANAGE_PERMISSION));

function statusTagType(status?: MarketListingStatus) {
  const map: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
    DRAFT: 'info',
    REVIEWING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    REVOKED: 'danger'
  };
  return map[status || ''] || 'info';
}

function riskTagType(level?: MarketRiskLevel) {
  const map: Record<string, 'success' | 'warning' | 'danger'> = {
    LOW: 'success',
    MEDIUM: 'warning',
    HIGH: 'danger'
  };
  return map[level || ''] || 'success';
}

function handleSearch() {
  pageQuery.current = 1;
  loadListings();
}

function resetFilters() {
  Object.assign(filters, { listingName: '', category: '', reviewStatus: '', riskLevel: '' });
  pageQuery.current = 1;
  loadListings();
}

function handlePageSizeChange() {
  pageQuery.current = 1;
  loadListings();
}

async function loadListings() {
  loading.value = true;
  loadError.value = '';
  try {
    const query: SkillMarketListingPageQuery = {
      current: pageQuery.current,
      size: pageQuery.size,
      listingName: filters.listingName || undefined,
      category: filters.category || undefined,
      reviewStatus: filters.reviewStatus || undefined,
      riskLevel: filters.riskLevel || undefined
    };
    const page = await skillMarketService.page(query);
    listings.value = page.data;
    pageTotal.value = page.total;
    pageQuery.current = page.pageNum || pageQuery.current;
    pageQuery.size = page.pageSize || pageQuery.size;
  } catch (error) {
    loadError.value = extractApiErrorMessage(error, '加载技能市场条目失败');
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(loadError.value);
    }
    listings.value = [];
    pageTotal.value = 0;
  } finally {
    loading.value = false;
  }
}

function openEditor(row?: SkillMarketListing) {
  editingListing.value = row || null;
  editorVisible.value = true;
}

function openDetail(row: SkillMarketListing) {
  activeListingId.value = row.id;
  detailVisible.value = true;
}

function openSubmitReview(row: SkillMarketListing) {
  activeListing.value = row;
  submitVisible.value = true;
}

function openReview(row: SkillMarketListing) {
  activeListing.value = row;
  reviewVisible.value = true;
}

async function handleSaved() {
  await loadListings();
}

async function revokeListing(row: SkillMarketListing) {
  try {
    await ElMessageBox.confirm(
      `撤销后条目「${row.listingName}」进入终态，不可再次上架，已安装到数字员工的技能将被级联处置。是否继续？`,
      '撤销市场条目（终态）',
      { type: 'warning', confirmButtonText: '确认撤销', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await skillMarketService.revoke(row.id);
    ElMessage.success('条目已撤销');
    await loadListings();
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '撤销市场条目失败'));
    }
  }
}

async function removeListing(row: SkillMarketListing) {
  try {
    await ElMessageBox.confirm(`确认删除市场条目「${row.listingName}」？仅草稿/已驳回条目可删除。`, '删除市场条目', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    await skillMarketService.remove(row.id);
    ElMessage.success('条目已删除');
    await loadListings();
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '删除市场条目失败'));
    }
  }
}

function goInstall(row: SkillMarketListing) {
  router.push({ path: '/ai-agent/digital-employees', query: { listingId: row.id } });
}

onMounted(loadListings);
</script>

<style scoped>
.skill-market-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.skill-market-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.page-toolbar,
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-toolbar {
  justify-content: space-between;
}

.page-toolbar h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
}

.page-toolbar p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.filter-panel {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-input),
.filter-panel :deep(.el-select) {
  width: 100%;
}

.load-error-alert {
  margin-bottom: 10px;
}

.skill-market-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.skill-market-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.skill-market-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.skill-market-table-wrap :deep(.el-table) {
  height: 100%;
}

.page-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 900px) {
  .page-toolbar,
  .filter-panel {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
