<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="evolution-tab">
    <ElAlert
      class="mb-16"
      type="info"
      :closable="false"
      title="数字员工进化怎么走"
      description="这里只初始化评估尺子并跳到自优化。不会自动改生产人设。审批通过先动 SANDBOX，生产要再确认。"
    />

    <section class="evolution-grid">
      <article class="evolution-card">
        <span class="card-label">生产 Release</span>
        <strong>{{ productionRelease }}</strong>
        <span class="card-hint">对话走这个冻结快照</span>
      </article>
      <article class="evolution-card">
        <span class="card-label">沙箱 Release</span>
        <strong>{{ sandboxRelease }}</strong>
        <span class="card-hint">评估真跑和候选离线评估钉这里</span>
      </article>
    </section>

    <ElAlert
      v-if="missingProduction"
      class="mb-16"
      type="warning"
      :closable="false"
      show-icon
      title="还没有生产发布"
      description="请先到「发布」Tab 发布当前配置并激活生产，再初始化评估。"
    />
    <ElAlert
      v-else-if="missingSandbox"
      class="mb-16"
      type="warning"
      :closable="false"
      show-icon
      title="沙箱还没有激活指针"
      description="初始化评估时会用当前生产 Release 补齐 SANDBOX，不会改生产对话。"
    />

    <section class="evolution-section">
      <div class="section-head">
        <div>
          <h3>评估默认配置</h3>
          <p>一键写入策略 / 候选对象 / 评估集 / 冒烟用例，不用手填 JSON。</p>
        </div>
        <ElTooltip :disabled="canManageEvaluation" content="需要 agent:evaluation:manage 权限" placement="top">
          <span>
            <ElButton
              type="primary"
              :loading="bootstrapping"
              :disabled="!canManageEvaluation || missingProduction"
              @click="bootstrap"
            >
              初始化评估配置
            </ElButton>
          </span>
        </ElTooltip>
      </div>
      <ElForm label-width="108px" class="run-now-row">
        <ElFormItem label="立即冒烟">
          <ElSwitch v-model="runNow" :disabled="!canRunEvaluation" />
          <span class="inline-hint">数字员工强制 DRY_RUN + INVOKE</span>
        </ElFormItem>
      </ElForm>
      <ElDescriptions :column="2" border size="small">
        <ElDescriptionsItem label="策略">{{ config.policyLabel }}</ElDescriptionsItem>
        <ElDescriptionsItem label="评估对象">{{ config.subjectLabel }}</ElDescriptionsItem>
        <ElDescriptionsItem label="评估集">{{ config.suiteLabel }}</ElDescriptionsItem>
        <ElDescriptionsItem label="冒烟运行">{{ config.runLabel }}</ElDescriptionsItem>
      </ElDescriptions>
    </section>

    <section class="evolution-actions">
      <ElButton @click="goEvaluations">打开智能体评估</ElButton>
      <ElButton type="primary" plain @click="goOptimizations">打开智能体自优化</ElButton>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { haveAuth } from '@/mixins/userAuth.js';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee, DigitalEmployeeDeployment } from '@/views/ai-agent/services/digitalEmployee';
import evaluationService from '@/views/ai-agent/services/evaluation';
import type { EvalSuite } from '@/views/ai-agent/services/evaluation';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

defineOptions({ name: 'EmployeeEvolutionTab' });

const props = defineProps<{
  employee: DigitalEmployee;
}>();

const EVALUATION_MANAGE_PERMISSION = 'agent:evaluation:manage';
const EVALUATION_RUN_PERMISSION = 'agent:evaluation:run';
const EMPLOYEE_CANDIDATE_SUBJECT_TYPE = 'DIGITAL_EMPLOYEE_CANDIDATE';

const router = useRouter();
const deployments = ref<DigitalEmployeeDeployment[]>([]);
const bootstrapping = ref(false);
const runNow = ref(false);
const lastRunId = ref('');
const config = reactive({
  policyLabel: '未初始化',
  subjectLabel: '未初始化',
  suiteLabel: '未初始化',
  runLabel: '-'
});

const canManageEvaluation = computed(() => haveAuth(EVALUATION_MANAGE_PERMISSION));
const canRunEvaluation = computed(() => haveAuth(EVALUATION_RUN_PERMISSION));

const production = computed(() => deployments.value.find(item => item.environment === 'PRODUCTION'));
const sandbox = computed(() => deployments.value.find(item => item.environment === 'SANDBOX'));
const productionRelease = computed(() => production.value?.activeReleaseId || '未激活');
const sandboxRelease = computed(() => sandbox.value?.activeReleaseId || '未激活');
const missingProduction = computed(() => !production.value?.activeReleaseId);
const missingSandbox = computed(() => !missingProduction.value && !sandbox.value?.activeReleaseId);

onMounted(() => {
  runNow.value = canRunEvaluation.value;
  load();
});

watch(
  () => props.employee.id,
  () => load()
);

async function load() {
  const employeeId = props.employee.id;
  if (!employeeId) {
    return;
  }
  try {
    deployments.value = await digitalEmployeeService.listDeployments(employeeId);
  } catch (error) {
    deployments.value = [];
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '部署查询失败'));
    }
  }
  await loadEvalConfig(employeeId);
}

async function loadEvalConfig(employeeId: string) {
  try {
    const subjects = await evaluationService.querySubjectsPage({
      current: 1,
      size: 1,
      subjectType: EMPLOYEE_CANDIDATE_SUBJECT_TYPE,
      subjectId: employeeId,
      status: 'enabled'
    });
    const subject = subjects.data?.[0];
    if (!subject?.id) {
      config.policyLabel = '未初始化';
      config.subjectLabel = '未初始化';
      config.suiteLabel = '未初始化';
      return;
    }
    config.subjectLabel = `${subject.subjectName || '-'}（${subject.id}）`;
    const suites = await evaluationService.querySuitesPage({
      current: 1,
      size: 1,
      subjectId: subject.id,
      status: 'enabled'
    });
    const suite = suites.data?.[0] as EvalSuite | undefined;
    config.suiteLabel = suite?.id ? `${suite.suiteName || '-'}（${suite.id}）` : '未初始化';
    config.policyLabel = suite?.policyId ? String(suite.policyId) : '未初始化';
  } catch {
    config.policyLabel = '加载失败';
    config.subjectLabel = '加载失败';
    config.suiteLabel = '加载失败';
  }
}

async function bootstrap() {
  const employeeId = props.employee.id;
  if (!employeeId) {
    return;
  }
  if (!canManageEvaluation.value) {
    ElMessage.warning('需要 agent:evaluation:manage 权限');
    return;
  }
  bootstrapping.value = true;
  try {
    const result = await evaluationService.bootstrapDefaults({
      employeeId,
      runNow: runNow.value && canRunEvaluation.value,
      caseUserInput: '请用一句话说明你能提供哪些能力',
      expectedOutput: ''
    });
    lastRunId.value = result.runId ? String(result.runId) : '';
    config.runLabel = lastRunId.value ? `已发起 ${lastRunId.value}` : '-';
    ElMessage.success(result.runId ? '评估配置已就绪，并已发起冒烟运行' : '评估配置已就绪');
    await load();
    if (result.runId) {
      router.push({
        path: '/ai-agent/evaluation-results',
        query: { runId: String(result.runId), suiteId: result.suiteId ? String(result.suiteId) : undefined }
      });
    }
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '初始化评估配置失败'));
  } finally {
    bootstrapping.value = false;
  }
}

function goEvaluations() {
  router.push({ path: '/ai-agent/evaluations' });
}

function goOptimizations() {
  router.push({ path: '/ai-agent/optimizations' });
}
</script>

<style scoped>
.evolution-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 8px;
}

.evolution-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.evolution-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-blank);
}

.card-label,
.card-hint,
.inline-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.evolution-card strong {
  font-size: 16px;
  word-break: break-all;
}

.evolution-section,
.evolution-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.section-head h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.section-head p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.run-now-row {
  margin-bottom: 0;
}

.evolution-actions {
  flex-direction: row;
}

.mb-16 {
  margin-bottom: 16px;
}

.inline-hint {
  margin-left: 8px;
}
</style>
