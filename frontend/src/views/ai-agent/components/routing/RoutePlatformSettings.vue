<template>
  <section v-loading="loading" class="route-platform-settings">
    <header class="settings-header">
      <div>
        <h2>智能路由</h2>
        <p>统一维护路由模型、向量模型、阈值和目标 Artifact。运行时只使用已激活 Profile。</p>
      </div>
      <div class="settings-actions">
        <ElButton @click="load">
          <ElIcon><Refresh /></ElIcon>
          刷新
        </ElButton>
        <ElButton v-if="profile?.status === 'ACTIVE'" type="primary" @click="createDraftFromCurrent">
          <ElIcon><DocumentAdd /></ElIcon>
          创建新草稿
        </ElButton>
        <ElButton
          v-if="profile?.status === 'ACTIVE' && rollbackProfile"
          :loading="activating"
          @click="rollbackActiveProfile"
        >
          <ElIcon><RefreshLeft /></ElIcon>
          回滚上一版本
        </ElButton>
      </div>
    </header>

    <ElAlert
      v-if="profile?.lastErrorCode"
      :type="profileAlertType"
      :closable="false"
      show-icon
      :title="`失败码：${profile.lastErrorCode}`"
    />

    <div class="profile-state-bar">
      <div>
        <span>Profile</span>
        <strong>{{ profile?.profileName || '尚未创建' }}</strong>
      </div>
      <ElTag :type="profileStatusType(profile?.status)">{{ profile?.status || 'DRAFT' }}</ElTag>
      <span>路由模型 {{ profile?.modelCapability?.state || 'NOT_PROBED' }}</span>
      <span>Embedding {{ profile?.embeddingCapability?.state || 'NOT_PROBED' }}</span>
      <span>Artifact {{ artifactProgress }}</span>
      <span>revision {{ profile?.revision ?? 0 }}</span>
      <span v-if="activeProfile && profile?.id !== activeProfile.id">当前生效 {{ activeProfile.profileName }}</span>
    </div>

    <ElForm v-if="configuration" label-position="top" class="settings-form">
      <div class="settings-grid">
        <ElFormItem label="Profile 名称">
          <ElInput
            v-model="form.profileName"
            :disabled="!editable"
            :maxlength="configuration.constraints.profileNameMaxLength"
          />
        </ElFormItem>
        <ElFormItem label="路由模型（CHAT）">
          <ElSelect v-model="form.routeModelConfigId" :disabled="!editable" filterable placeholder="选择对话模型">
            <ElOption
              v-for="model in chatModels"
              :key="String(model.id)"
              :label="modelLabel(model)"
              :value="String(model.id)"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="向量模型（EMBEDDING）">
          <ElSelect v-model="form.embeddingModelConfigId" :disabled="!editable" filterable placeholder="选择嵌入模型">
            <ElOption
              v-for="model in embeddingModels"
              :key="String(model.id)"
              :label="modelLabel(model)"
              :value="String(model.id)"
            />
          </ElSelect>
        </ElFormItem>
      </div>

      <div class="switch-row">
        <ElCheckbox v-model="form.lexicalAutoSelectEnabled" :disabled="!editable">词法自动选择</ElCheckbox>
        <ElCheckbox v-model="form.semanticRecallEnabled" :disabled="!editable" @change="onSemanticRecallChange">
          语义召回
        </ElCheckbox>
        <ElCheckbox v-model="form.semanticAutoSelectEnabled" :disabled="semanticAutoSelectDisabled">
          语义自动选择
        </ElCheckbox>
        <ElCheckbox v-model="form.modelDisambiguationEnabled" :disabled="!editable">模型消歧</ElCheckbox>
      </div>

      <div class="threshold-grid">
        <ElFormItem label="词法最低分">
          <ElInputNumber
            v-model="form.lexicalMinScore"
            :disabled="!editable"
            :min="configuration.constraints.lexicalThresholdMin"
            :max="configuration.constraints.lexicalThresholdMax"
          />
        </ElFormItem>
        <ElFormItem label="词法最小分差">
          <ElInputNumber
            v-model="form.lexicalMinGap"
            :disabled="!editable"
            :min="configuration.constraints.lexicalThresholdMin"
            :max="configuration.constraints.lexicalThresholdMax"
          />
        </ElFormItem>
        <ElFormItem label="向量召回阈值">
          <ElInputNumber
            v-model="form.vectorRecallThreshold"
            :disabled="!editable"
            :min="configuration.constraints.ratioMin"
            :max="configuration.constraints.ratioMax"
            :step="0.01"
            :precision="2"
          />
        </ElFormItem>
        <ElFormItem label="向量自动选择阈值">
          <ElInputNumber
            v-model="form.vectorAutoSelectThreshold"
            :disabled="!editable"
            :min="configuration.constraints.ratioMin"
            :max="configuration.constraints.ratioMax"
            :step="0.01"
            :precision="2"
          />
        </ElFormItem>
        <ElFormItem label="向量最小分差">
          <ElInputNumber
            v-model="form.vectorMinGap"
            :disabled="!editable"
            :min="configuration.constraints.ratioMin"
            :max="configuration.constraints.ratioMax"
            :step="0.01"
            :precision="2"
          />
        </ElFormItem>
        <ElFormItem label="模型置信度阈值">
          <ElInputNumber
            v-model="form.modelConfidenceThreshold"
            :disabled="!editable"
            :min="configuration.constraints.ratioMin"
            :max="configuration.constraints.ratioMax"
            :step="0.01"
            :precision="2"
          />
        </ElFormItem>
      </div>
    </ElForm>

    <ElDescriptions v-if="configuration" title="平台路由预算（只读）" :column="4" border class="budget-details">
      <ElDescriptionsItem label="在线总路由">
        {{ formatLatency(configuration.runtimeBudget.totalTimeoutMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="在线模型阶段">
        {{ formatLatency(configuration.runtimeBudget.modelTimeoutMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="在线向量阶段">
        {{ formatLatency(configuration.runtimeBudget.vectorTimeoutMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="模型最小启动余量">
        {{ formatLatency(configuration.runtimeBudget.modelMinStartMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="结束缓冲">
        {{ formatLatency(configuration.runtimeBudget.finishBufferMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="模型探测读取">
        {{ formatLatency(configuration.probeTimeouts.modelTimeoutMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="模型探测连接">
        {{ formatLatency(configuration.probeTimeouts.modelConnectTimeoutMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="Embedding 探测">
        {{ formatLatency(configuration.probeTimeouts.embeddingTimeoutMs) }}
      </ElDescriptionsItem>
    </ElDescriptions>

    <ElDescriptions v-if="profile?.id" :column="3" border class="probe-details">
      <ElDescriptionsItem label="路由模型协议能力">
        {{ profile.modelCapability?.state || 'NOT_PROBED' }} / {{ profile.modelCapability?.protocol || 'NONE' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="路由模型实际耗时">
        {{ formatLatency(profile.modelCapability?.latencyMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="在线模型预算">
        {{ formatLatency(configuration?.runtimeBudget.modelTimeoutMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="runtimeReady">{{ runtimeReadyLabel }}</ElDescriptionsItem>
      <ElDescriptionsItem label="路由模型能力码">{{ profile.modelCapability?.failureCode || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="路由模型探测时间">{{ profile.modelCapability?.checkedAt || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="Embedding 状态">
        {{ profile.embeddingCapability?.state || 'NOT_PROBED' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="Embedding 耗时">
        {{ formatLatency(profile.embeddingCapability?.latencyMs) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="Embedding 能力码">
        {{ profile.embeddingCapability?.failureCode || '-' }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="Embedding fingerprint">{{ profile.embeddingFingerprint || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="Embedding 维度">{{ profile.embeddingDimension || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="Embedding 探测时间">
        {{ profile.embeddingCapability?.checkedAt || '-' }}
      </ElDescriptionsItem>
    </ElDescriptions>

    <footer class="workflow-actions">
      <ElTooltip :disabled="editable" :content="saveDisabledReason" placement="top">
        <span class="action-wrapper">
          <ElButton type="primary" :disabled="!editable" :loading="saving" @click="saveProfile">
            <ElIcon><Check /></ElIcon>
            {{ profile?.id ? '保存草稿' : '创建草稿' }}
          </ElButton>
        </span>
      </ElTooltip>
      <ElTooltip :disabled="canProbe" :content="probeDisabledReason" placement="top">
        <span class="action-wrapper">
          <ElButton :disabled="!canProbe" :loading="probing" @click="probeProfile">
            <ElIcon><Connection /></ElIcon>
            能力探测
          </ElButton>
        </span>
      </ElTooltip>
      <ElTooltip :disabled="canRebuild" :content="rebuildDisabledReason" placement="top">
        <span class="action-wrapper">
          <ElButton :disabled="!canRebuild" :loading="rebuilding" @click="rebuildProfile">
            <ElIcon><RefreshRight /></ElIcon>
            构建全部索引
          </ElButton>
        </span>
      </ElTooltip>
      <ElTooltip :disabled="canActivate" :content="activateDisabledReason" placement="top">
        <span class="action-wrapper">
          <ElButton type="success" :disabled="!canActivate" :loading="activating" @click="activateProfile">
            <ElIcon><CircleCheck /></ElIcon>
            激活 Profile
          </ElButton>
        </span>
      </ElTooltip>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import {
  Check,
  CircleCheck,
  Connection,
  DocumentAdd,
  Refresh,
  RefreshLeft,
  RefreshRight
} from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import modelConfigService, { normalizeModelType, type ModelConfig } from '@/views/ai-agent/services/modelConfig';
import routingService, {
  type RouteProfile,
  type RouteProfileBuildStatus,
  type RouteProfileConfiguration,
  type RouteProfileDefaults,
  type RouteProfileInput
} from '@/views/ai-agent/services/routing';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  canActivateRouteProfile,
  canProbeRouteProfile,
  canRebuildRouteProfile,
  createRouteProfileInput,
  isRouteProfileEditable,
  normalizeRouteProfileInput,
  routeProfileValidationMessage
} from '@/views/ai-agent/utils/routeProfile';

defineOptions({ name: 'RoutePlatformSettings' });

const emptyForm = (): RouteProfileInput => ({
  profileName: '',
  routeModelConfigId: '',
  embeddingModelConfigId: '',
  lexicalAutoSelectEnabled: false,
  semanticRecallEnabled: false,
  semanticAutoSelectEnabled: false,
  modelDisambiguationEnabled: false,
  lexicalMinScore: 0,
  lexicalMinGap: 0,
  vectorRecallThreshold: 0,
  vectorAutoSelectThreshold: 0,
  vectorMinGap: 0,
  modelConfidenceThreshold: 0
});

const defaultForm = (defaults: RouteProfileDefaults): RouteProfileInput =>
  createRouteProfileInput(defaults, `route-${new Date().toISOString().slice(0, 10)}`);

const loading = ref(false);
const saving = ref(false);
const probing = ref(false);
const rebuilding = ref(false);
const activating = ref(false);
const models = ref<ModelConfig[]>([]);
const profile = ref<RouteProfile | null>(null);
const activeProfile = ref<RouteProfile | null>(null);
const rollbackProfile = ref<RouteProfile | null>(null);
const configuration = ref<RouteProfileConfiguration | null>(null);
const buildStatus = ref<RouteProfileBuildStatus | null>(null);
const form = reactive<RouteProfileInput>(emptyForm());
const POLL_INTERVAL_MS = 1500;
const POLL_MAX_DURATION_MS = 10 * 60 * 1000;
let buildPollTimer: ReturnType<typeof setTimeout> | null = null;
let buildPollProfileId: string | null = null;
let buildPollGeneration = 0;
let buildPollRequestGeneration: number | null = null;
let buildPollAbortController: AbortController | null = null;
let buildPollStartedAt = 0;
let loadGeneration = 0;
let componentMounted = false;

const chatModels = computed(() => models.value.filter(model => normalizeModelType(model.modelType) === 'CHAT'));
const embeddingModels = computed(() =>
  models.value.filter(model => normalizeModelType(model.modelType) === 'EMBEDDING')
);
const editable = computed(() => Boolean(configuration.value) && isRouteProfileEditable(profile.value));
const semanticAutoSelectDisabled = computed(() => !editable.value || !form.semanticRecallEnabled);
const canProbe = computed(() => canProbeRouteProfile(profile.value));
const canRebuild = computed(() => canRebuildRouteProfile(profile.value));
const canActivate = computed(() => canActivateRouteProfile(profile.value, buildStatus.value));
const profileAlertType = computed(() =>
  profile.value?.lastErrorCode && profile.value.lastErrorCode === profile.value.modelCapability?.failureCode
    ? 'warning'
    : 'error'
);
const runtimeReadyLabel = computed(() => {
  if (!profile.value?.modelDisambiguationEnabled) return '未启用';
  if (profile.value.modelCapability?.state === 'NOT_PROBED') return '未探测';
  return profile.value.modelCapability?.runtimeReady ? '满足' : '不满足';
});
const saveDisabledReason = computed(() => {
  if (!configuration.value) return '平台配置尚未加载';
  return profile.value?.status === 'ACTIVE'
    ? '已激活 Profile 不可直接修改，请先创建新草稿'
    : `当前状态 ${profile.value?.status || 'UNKNOWN'} 不可保存`;
});
const probeDisabledReason = computed(() => (!profile.value?.id ? '请先保存草稿' : '当前 Profile 状态不可探测'));
const rebuildDisabledReason = computed(() => {
  if (!profile.value?.id) return '请先保存草稿';
  if (!form.semanticRecallEnabled) {
    return '未启用语义召回，无需构建索引';
  }
  if (profile.value.embeddingCapability?.state !== 'SUPPORTED') {
    return '请先完成 Embedding 能力探测';
  }
  if (profile.value.buildStatus === 'RUNNING') return '索引正在构建';
  return '当前 Profile 状态不可构建索引';
});
const activateDisabledReason = computed(() => {
  if (!profile.value?.id) return '请先保存草稿并完成能力探测';
  if (profile.value.status !== 'READY') return 'Profile 尚未就绪';
  if (form.semanticRecallEnabled && profile.value.embeddingCapability?.state !== 'SUPPORTED') {
    return 'Embedding 能力尚未就绪';
  }
  return '索引尚未构建完成';
});
const artifactProgress = computed(() => {
  const status = buildStatus.value?.buildStatus ?? profile.value?.buildStatus;
  if (status === 'SKIPPED') return 'SKIPPED';
  const total = buildStatus.value?.total ?? profile.value?.buildTotal ?? 0;
  const ready = buildStatus.value?.ready ?? profile.value?.buildReady ?? 0;
  const failed = buildStatus.value?.failed ?? profile.value?.buildFailed ?? 0;
  return `${status || 'NOT_BUILT'} ${ready}/${total}${failed ? `，失败 ${failed}` : ''}`;
});

const assignForm = (value?: RouteProfile | null) => {
  if (!configuration.value) return;
  Object.assign(form, value ? normalizeRouteProfileInput(value) : defaultForm(configuration.value.defaults));
};

const onSemanticRecallChange = (enabled: boolean | string | number) => {
  form.semanticRecallEnabled = enabled === true;
  if (!form.semanticRecallEnabled) form.semanticAutoSelectEnabled = false;
};

const load = async () => {
  const requestGeneration = ++loadGeneration;
  stopBuildPolling();
  loading.value = true;
  try {
    const [modelList, current] = await Promise.all([modelConfigService.list(), routingService.currentProfile()]);
    if (!componentMounted || requestGeneration !== loadGeneration) return;
    models.value = modelList;
    if (!current.configuration) {
      throw new Error('Routing configuration metadata is unavailable');
    }
    configuration.value = current.configuration;
    activeProfile.value = current.active || null;
    rollbackProfile.value = current.rollback || null;
    profile.value = current.working || current.active || null;
    buildStatus.value = null;
    assignForm(profile.value);
    rebuilding.value = false;
    if (profile.value?.buildStatus === 'RUNNING') {
      startBuildPolling(profile.value.id);
    }
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '加载智能路由配置失败'));
  } finally {
    loading.value = false;
  }
};

const validateForm = () => {
  if (!configuration.value) return false;
  const message = routeProfileValidationMessage(form, configuration.value.constraints);
  if (!message) return true;
  ElMessage.warning(message);
  return false;
};

const saveProfile = async () => {
  if (!editable.value) return;
  if (!validateForm()) return;
  saving.value = true;
  try {
    profile.value = profile.value?.id
      ? await routingService.modifyProfile(profile.value.id, {
          ...form,
          revision: profile.value.revision
        })
      : await routingService.createProfile({ ...form });
    buildStatus.value = null;
    assignForm(profile.value);
    ElMessage.success('路由 Profile 草稿已保存');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '保存路由 Profile 失败'));
  } finally {
    saving.value = false;
  }
};

const createDraftFromCurrent = () => {
  if (!configuration.value) return;
  stopBuildPolling();
  const currentValues = { ...form };
  profile.value = null;
  buildStatus.value = null;
  Object.assign(form, {
    ...defaultForm(configuration.value.defaults),
    ...currentValues,
    profileName: `${currentValues.profileName}-next`
  });
};

const probeProfile = async () => {
  if (!profile.value?.id) return;
  probing.value = true;
  try {
    profile.value = await routingService.probeProfile(profile.value.id);
    assignForm(profile.value);
    const modelDegraded =
      profile.value.modelDisambiguationEnabled &&
      (!profile.value.modelCapability ||
        profile.value.modelCapability.state !== 'SUPPORTED' ||
        !profile.value.modelCapability.runtimeReady);
    const embeddingBlocked = form.semanticRecallEnabled && profile.value.embeddingCapability?.state !== 'SUPPORTED';
    if (embeddingBlocked) {
      ElMessage.error('能力探测已完成，Embedding 能力不可用，无法构建索引');
    } else if (modelDegraded) {
      ElMessage.warning('能力探测已完成，路由模型不满足在线预算，将降级为规则路由');
    } else {
      ElMessage.success('能力探测已完成');
    }
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '模型能力探测失败'));
  } finally {
    probing.value = false;
  }
};

const stopBuildPolling = () => {
  if (buildPollTimer) clearTimeout(buildPollTimer);
  buildPollAbortController?.abort();
  buildPollTimer = null;
  buildPollAbortController = null;
  buildPollRequestGeneration = null;
  buildPollProfileId = null;
  buildPollGeneration += 1;
  buildPollStartedAt = 0;
  rebuilding.value = false;
};

const applyBuildStatus = (profileId: string, status: RouteProfileBuildStatus) => {
  if (!profile.value?.id || profile.value.id !== profileId) return;
  buildStatus.value = status;
  profile.value = {
    ...profile.value,
    status: status.status,
    buildStatus: status.buildStatus,
    buildTotal: status.total,
    buildReady: status.ready,
    buildFailed: status.failed,
    lastErrorCode: status.errorCode,
    revision: status.revision
  };
};

const buildFailureMessage = (errorCode?: string) => {
  if (errorCode === 'ROUTE_ARTIFACT_SOURCE_INVALID') {
    return '存在未发布、版本不匹配或租户不一致的 Skill 绑定，请修复后重新构建索引';
  }
  if (errorCode === 'ROUTE_ARTIFACT_BUILD_TIMEOUT') return '索引构建超时，请检查数据源后重新构建';
  return `索引构建失败${errorCode ? `（${errorCode}）` : ''}`;
};

const scheduleBuildPoll = (generation: number, profileId: string, delayMs: number) => {
  if (!componentMounted || generation !== buildPollGeneration || buildPollProfileId !== profileId || buildPollTimer) {
    return;
  }
  buildPollTimer = setTimeout(() => {
    buildPollTimer = null;
    pollBuildStatus(generation, profileId).catch(() => undefined);
  }, delayMs);
};

const pollBuildStatus = async (generation: number, profileId: string) => {
  if (!componentMounted || generation !== buildPollGeneration || buildPollProfileId !== profileId) return;
  if (buildPollRequestGeneration !== null) return;
  if (Date.now() - buildPollStartedAt >= POLL_MAX_DURATION_MS) {
    stopBuildPolling();
    ElMessage.error('状态查询超时，请刷新');
    return;
  }

  const requestController = new AbortController();
  buildPollRequestGeneration = generation;
  buildPollAbortController = requestController;
  let requestTimeoutTimer: ReturnType<typeof setTimeout> | null = null;
  let continuePolling = false;
  try {
    const remainingMs = Math.max(1, POLL_MAX_DURATION_MS - (Date.now() - buildPollStartedAt));
    const clientTimeout = new Promise<never>((_, reject) => {
      requestTimeoutTimer = setTimeout(() => reject(new Error('ROUTE_BUILD_POLL_TIMEOUT')), remainingMs);
    });
    const status = await Promise.race([
      routingService.getBuildStatus(profileId, requestController.signal),
      clientTimeout
    ]);
    if (!componentMounted || generation !== buildPollGeneration || buildPollProfileId !== profileId) return;
    applyBuildStatus(profileId, status);
    if (status.buildStatus !== 'RUNNING') {
      stopBuildPolling();
      if (status.buildStatus === 'FAILED') ElMessage.error(buildFailureMessage(status.errorCode));
      return;
    }
    if (Date.now() - buildPollStartedAt >= POLL_MAX_DURATION_MS) {
      stopBuildPolling();
      ElMessage.error('状态查询超时，请刷新');
      return;
    }
    continuePolling = true;
  } catch (error) {
    if (componentMounted && generation === buildPollGeneration && buildPollProfileId === profileId) {
      stopBuildPolling();
      if (error instanceof Error && error.message === 'ROUTE_BUILD_POLL_TIMEOUT') {
        ElMessage.error('状态查询超时，请刷新');
      } else {
        ElMessage.error(extractApiErrorMessage(error, '查询 Artifact 构建状态失败'));
      }
    }
  } finally {
    if (requestTimeoutTimer) clearTimeout(requestTimeoutTimer);
    if (buildPollAbortController === requestController) buildPollAbortController = null;
    if (buildPollRequestGeneration === generation) buildPollRequestGeneration = null;
    if (continuePolling && componentMounted && generation === buildPollGeneration && buildPollProfileId === profileId) {
      scheduleBuildPoll(generation, profileId, POLL_INTERVAL_MS);
    }
  }
};

const startBuildPolling = (profileId: string) => {
  stopBuildPolling();
  buildPollProfileId = profileId;
  buildPollStartedAt = Date.now();
  rebuilding.value = true;
  const generation = buildPollGeneration;
  pollBuildStatus(generation, profileId).catch(() => undefined);
};

const rebuildProfile = async () => {
  if (!profile.value?.id) return;
  const profileId = profile.value.id;
  stopBuildPolling();
  const requestGeneration = buildPollGeneration;
  rebuilding.value = true;
  try {
    const status = await routingService.rebuildProfile(profileId);
    if (requestGeneration !== buildPollGeneration || profile.value?.id !== profileId) return;
    applyBuildStatus(profileId, status);
    if (status.buildStatus === 'RUNNING') startBuildPolling(profileId);
    else {
      rebuilding.value = false;
      if (status.buildStatus === 'FAILED') ElMessage.error(buildFailureMessage(status.errorCode));
    }
  } catch (error) {
    if (requestGeneration !== buildPollGeneration || profile.value?.id !== profileId) return;
    rebuilding.value = false;
    ElMessage.error(extractApiErrorMessage(error, '启动 Artifact 构建失败'));
  }
};

const activateProfile = async () => {
  if (!profile.value?.id || !canActivate.value) return;
  await ElMessageBox.confirm('激活后，所有新请求将使用该 Profile。确认继续？', '激活路由 Profile', { type: 'warning' });
  activating.value = true;
  try {
    await routingService.activateProfile(profile.value.id);
    await load();
    ElMessage.success('路由 Profile 已激活');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '激活路由 Profile 失败'));
  } finally {
    activating.value = false;
  }
};

const rollbackActiveProfile = async () => {
  if (!rollbackProfile.value?.id) return;
  await ElMessageBox.confirm(
    `确认回滚到 ${rollbackProfile.value.profileName}？系统会重新校验模型和全部 Artifact。`,
    '回滚路由 Profile',
    { type: 'warning' }
  );
  activating.value = true;
  try {
    await routingService.activateProfile(rollbackProfile.value.id);
    await load();
    ElMessage.success('路由 Profile 已回滚');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '回滚路由 Profile 失败'));
  } finally {
    activating.value = false;
  }
};

const modelLabel = (model: ModelConfig) => `${model.provider} / ${model.modelName}`;
const formatLatency = (latency?: number | null) => (latency === null || latency === undefined ? '-' : `${latency} ms`);
const profileStatusType = (status?: string) => {
  if (status === 'ACTIVE' || status === 'READY') return 'success';
  if (status === 'FAILED') return 'danger';
  if (status === 'BUILDING') return 'warning';
  return 'info';
};

const initialize = async () => {
  componentMounted = true;
  await load();
};

onMounted(() => initialize().catch(() => undefined));
onBeforeUnmount(() => {
  componentMounted = false;
  stopBuildPolling();
});
</script>

<style scoped>
.route-platform-settings {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
  padding: 2px;
}
.settings-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.settings-header h2 {
  margin: 0 0 4px;
  font-size: 18px;
}
.settings-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.settings-actions,
.workflow-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.action-wrapper {
  display: inline-flex;
}
.profile-state-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 18px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.profile-state-bar > div {
  display: flex;
  align-items: center;
  gap: 8px;
}
.profile-state-bar strong {
  color: var(--el-text-color-primary);
}
.settings-form {
  padding: 14px 14px 2px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.settings-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.threshold-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(120px, 1fr));
  gap: 12px;
}
.switch-row {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  margin-bottom: 14px;
}
.settings-form :deep(.el-form-item) {
  margin-bottom: 12px;
}
.settings-form :deep(.el-select),
.settings-form :deep(.el-input-number) {
  width: 100%;
}
.probe-details {
  margin-top: 0;
}
@media (max-width: 1100px) {
  .threshold-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 760px) {
  .settings-header {
    flex-direction: column;
  }
  .settings-grid,
  .threshold-grid {
    grid-template-columns: 1fr;
  }
}
</style>
