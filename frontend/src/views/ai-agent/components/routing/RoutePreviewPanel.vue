<template>
  <section class="route-preview-panel">
    <div class="preview-input-row">
      <ElInput
        v-model="query"
        type="textarea"
        :rows="3"
        maxlength="2000"
        show-word-limit
        placeholder="输入要验证的用户问题"
        @keydown.ctrl.enter="runPreview"
      />
      <ElButton type="primary" :loading="loading" @click="runPreview">
        <ElIcon><Search /></ElIcon>
        路由预览
      </ElButton>
    </div>

    <ElEmpty v-if="!result" description="暂无路由预览结果" :image-size="64" />
    <template v-else>
      <div class="preview-summary">
        <ElTag :type="decisionTagType(result.decision)">{{
          result.decision
        }}</ElTag>
        <span>原因 {{ result.reasonCode || "-" }}</span>
        <span>Profile {{ result.profileId }}</span>
        <span>{{ result.modelInvoked ? "已调用模型" : "未调用模型" }}</span>
        <ElTag v-if="result.degradeMode" type="warning" effect="plain">{{
          result.degradeMode
        }}</ElTag>
      </div>
      <ElTable
        :data="result.candidates"
        border
        stripe
        empty-text="没有相关候选"
      >
        <ElTableColumn prop="rank" label="#" width="56" />
        <ElTableColumn
          prop="name"
          label="候选"
          min-width="170"
          show-overflow-tooltip
        />
        <ElTableColumn prop="targetType" label="类型" width="110" />
        <ElTableColumn prop="riskLevel" label="风险" width="110" />
        <ElTableColumn prop="lexicalScore" label="词法" width="80" />
        <ElTableColumn label="向量" width="90">
          <template #default="{ row }">{{
            formatScore(row.vectorScore)
          }}</template>
        </ElTableColumn>
        <ElTableColumn label="命中信号" min-width="220">
          <template #default="{ row }">
            <div class="signal-list">
              <ElTag
                v-for="signal in row.matchedSignals"
                :key="signal"
                size="small"
                effect="plain"
              >
                {{ signal }}
              </ElTag>
            </div>
          </template>
        </ElTableColumn>
      </ElTable>
    </template>
  </section>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { Search } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";
import routingService, {
  type RouteDecisionType,
  type RoutePreviewResponse,
  type RouteProfileId,
  type RouteTargetType,
} from "@/views/ai-agent/services/routing";
import { extractApiErrorMessage } from "@/views/ai-agent/services/common";

defineOptions({ name: "RoutePreviewPanel" });

const props = defineProps<{
  agentId: string;
  profileId?: RouteProfileId;
  targetTypes?: RouteTargetType[];
}>();

const query = ref("");
const loading = ref(false);
const result = ref<RoutePreviewResponse | null>(null);

const runPreview = async () => {
  const value = query.value.trim();
  if (!value) {
    ElMessage.warning("请输入要验证的问题");
    return;
  }
  loading.value = true;
  try {
    result.value = await routingService.preview(props.agentId, {
      query: value,
      profileId: props.profileId,
      targetTypes: props.targetTypes,
    });
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, "路由预览失败"));
  } finally {
    loading.value = false;
  }
};

const formatScore = (value?: number) =>
  typeof value === "number" ? value.toFixed(4) : "-";
const decisionTagType = (decision: RouteDecisionType) => {
  if (decision === "SELECT" || decision === "MULTI_SELECT") return "success";
  if (decision === "CLARIFY" || decision === "CONFIRM_REQUIRED") return "warning";
  if (decision === "ROUTE_UNAVAILABLE") return "danger";
  return "info";
};
</script>

<style scoped>
.route-preview-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.preview-input-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
}
.preview-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.signal-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
@media (max-width: 720px) {
  .preview-input-row {
    grid-template-columns: 1fr;
  }
}
</style>
