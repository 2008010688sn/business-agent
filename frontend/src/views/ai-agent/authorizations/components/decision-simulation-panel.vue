<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="decision-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="card-header">
          <span>决策模拟</span>
          <span class="card-header-sub">
            inline/模板方式不读库不依赖运行时接线；连库模拟按 owner + 环境读取绑定的已发布策略版本求值，无绑定按 MISSING_POLICY 拒绝
          </span>
        </div>
      </template>

      <ElRadioGroup v-model="mode" class="mode-switch">
        <ElRadioButton value="inline">inline / 模板模拟</ElRadioButton>
        <ElRadioButton value="db">连库模拟（按绑定关系）</ElRadioButton>
      </ElRadioGroup>

      <ElDivider />

      <ElForm label-width="100px" class="simulate-form">
        <!-- 连库模拟：绑定定位参数 -->
        <template v-if="mode === 'db'">
          <ElFormItem label="绑定主体">
            <ElSelect v-model="dbForm.ownerType" style="width: 160px">
              <ElOption v-for="item in OWNER_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
            <ElSelect
              v-if="dbForm.ownerType === 'DATA_AGENT'"
              v-model="dbForm.ownerId"
              filterable
              clearable
              placeholder="选择数据智能体"
              :loading="agentOptionsLoading"
              style="width: 240px; margin-left: 8px"
            >
              <ElOption v-for="item in agentOptions" :key="String(item.id)" :label="item.name || String(item.id)" :value="String(item.id)" />
            </ElSelect>
            <EmployeeOptionSelect v-else v-model="dbForm.ownerId" placeholder="选择数字员工" width="240px" />
          </ElFormItem>
          <ElFormItem label="生效环境">
            <ElSelect v-model="dbForm.environment" style="width: 160px">
              <ElOption v-for="item in ENVIRONMENT_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
        </template>

        <!-- inline 模拟：策略来源二选一 -->
        <template v-if="mode === 'inline'">
          <ElFormItem label="策略来源">
            <ElRadioGroup v-model="inlineForm.source" @change="handleSourceChange">
              <ElRadio value="templateCode">模板</ElRadio>
              <ElRadio value="policyJson">inline 策略 JSON</ElRadio>
            </ElRadioGroup>
          </ElFormItem>
          <ElFormItem v-if="inlineForm.source === 'templateCode'" label="模板编码">
            <ElSelect v-model="inlineForm.templateCode" filterable placeholder="选择预设模板" style="width: 320px">
              <ElOption v-for="item in templates" :key="item.code" :label="item.code" :value="item.code" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-else label="策略 JSON">
            <ElInput
              v-model="inlineForm.policyJson"
              type="textarea"
              :rows="10"
              placeholder="粘贴策略 JSON（后端 PolicyValidator 严格校验：未知字段拒绝、schemaVersion=1）"
              spellcheck="false"
              class="policy-json-input"
            />
          </ElFormItem>
        </template>

        <!-- 求值请求参数（两种模式共用） -->
        <ElFormItem label="主体类别">
          <ElSelect v-model="requestForm.subjectKind" style="width: 160px">
            <ElOption v-for="item in SUBJECT_KIND_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="能力码">
          <ElInput v-model="requestForm.capabilityCode" placeholder="如 text2sql / knowledge-search" style="width: 320px" />
        </ElFormItem>
        <ElFormItem label="动作">
          <ElSelect v-model="requestForm.action" style="width: 200px">
            <ElOption v-for="item in ACTION_OPTIONS" :key="item.value" :label="`${item.label}（${item.value}）`" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="能力版本">
          <ElInput v-model="requestForm.capabilityVersion" placeholder="可空" style="width: 200px" />
        </ElFormItem>
        <ElFormItem label="IAM 可用">
          <ElSwitch v-model="requestForm.iamAvailable" />
        </ElFormItem>

        <ElFormItem>
          <ElButton v-if="canQuery" type="primary" :loading="submitting" @click="handleSimulate">执行模拟</ElButton>
        </ElFormItem>
      </ElForm>

      <!-- 模拟结果 -->
      <template v-if="result">
        <ElDivider content-position="left">决策结果</ElDivider>
        <div class="result-box" :class="result.allowed ? 'result-allowed' : 'result-denied'">
          <div class="result-verdict">
            <span class="verdict-badge">{{ result.allowed ? 'ALLOW' : 'DENY' }}</span>
            <span class="verdict-reason">
              {{ result.reasonCode || '-' }}
              <span v-if="reasonCodeText" class="verdict-reason-text">{{ reasonCodeText }}</span>
            </span>
          </div>
          <ElDescriptions :column="2" border size="small" class="result-desc">
            <ElDescriptionsItem label="义务（obligations）">
              {{ obligationsText }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="脱敏字段（maskFields）">
              {{ result.maskFields?.length ? result.maskFields.join(', ') : '-' }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="策略哈希（policyHash）">
              <span v-if="result.policyHash" class="hash-chip" @click="copyHash(result.policyHash)">{{ result.policyHash }}</span>
              <span v-else>-（策略缺失）</span>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="决策时间（evaluatedAt）">{{ formatDateTime(result.evaluatedAt) }}</ElDescriptionsItem>
          </ElDescriptions>
        </div>
      </template>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import dayjs from 'dayjs';
import { haveAuth } from '@/mixins/userAuth.js';
import agentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import EmployeeOptionSelect from '@/views/ai-agent/components/employee-option-select.vue';
import authorizationService from '@/views/ai-agent/services/authorization';
import type {
  Action,
  BindingOwnerType,
  DecisionResp,
  Environment,
  SubjectKind,
  TemplateResp
} from '@/views/ai-agent/services/authorization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  ACTION_OPTIONS,
  AUTHORIZATION_QUERY_PERMISSION,
  ENVIRONMENT_OPTIONS,
  OWNER_TYPE_OPTIONS,
  REASON_CODE_LABELS,
  SUBJECT_KIND_OPTIONS
} from '../authorization-constants';

defineOptions({ name: 'AuthorizationDecisionSimulationPanel' });

const canQuery = computed(() => haveAuth(AUTHORIZATION_QUERY_PERMISSION));

const mode = ref<'inline' | 'db'>('inline');

const templates = ref<TemplateResp[]>([]);
const agentOptions = ref<Agent[]>([]);
const agentOptionsLoading = ref(false);

const inlineForm = reactive<{ source: 'templateCode' | 'policyJson'; templateCode: string; policyJson: string }>({
  source: 'templateCode',
  templateCode: '',
  policyJson: ''
});

const dbForm = reactive<{ ownerType: BindingOwnerType; ownerId: string; environment: Environment }>({
  ownerType: 'DATA_AGENT',
  ownerId: '',
  environment: 'SANDBOX'
});

const requestForm = reactive<{
  subjectKind: SubjectKind;
  capabilityCode: string;
  action: Action;
  capabilityVersion: string;
  iamAvailable: boolean;
}>({
  subjectKind: 'CALLER',
  capabilityCode: '',
  action: 'EXECUTE',
  capabilityVersion: '',
  iamAvailable: true
});

const submitting = ref(false);
const result = ref<DecisionResp | null>(null);

const reasonCodeText = computed(() => {
  const code = result.value?.reasonCode;
  return code ? REASON_CODE_LABELS[code] || '' : '';
});

const obligationsText = computed(() => {
  const obligations = result.value?.obligations;
  if (!obligations || obligations.length === 0) return '-';
  return obligations.map(item => item?.type || JSON.stringify(item)).join(', ');
});

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

function handleSourceChange() {
  // 切换策略来源时清理另一侧，避免 policyJson 与 templateCode 同时提供（后端二选一强校验）
  if (inlineForm.source === 'templateCode') {
    inlineForm.policyJson = '';
  } else {
    inlineForm.templateCode = '';
  }
}

async function loadOptions() {
  try {
    templates.value = await authorizationService.listTemplates();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '授权模板加载失败'));
  }
  agentOptionsLoading.value = true;
  try {
    agentOptions.value = await agentService.list();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '智能体列表加载失败'));
  } finally {
    agentOptionsLoading.value = false;
  }
}

async function handleSimulate() {
  if (!requestForm.capabilityCode) {
    ElMessage.warning('请输入能力码');
    return;
  }
  if (mode.value === 'db' && !dbForm.ownerId) {
    ElMessage.warning('请先选择/输入绑定主体');
    return;
  }
  if (mode.value === 'inline') {
    const hasPolicyJson = Boolean(inlineForm.policyJson.trim());
    const hasTemplate = Boolean(inlineForm.templateCode);
    if (hasPolicyJson === hasTemplate) {
      ElMessage.warning('policyJson 与 templateCode 必须提供且仅提供其一');
      return;
    }
    if (hasPolicyJson) {
      try {
        JSON.parse(inlineForm.policyJson);
      } catch {
        ElMessage.warning('策略 JSON 不是合法的 JSON 格式');
        return;
      }
    }
  }

  submitting.value = true;
  try {
    const baseRequest = {
      subjectKind: requestForm.subjectKind,
      capabilityCode: requestForm.capabilityCode,
      action: requestForm.action,
      capabilityVersion: requestForm.capabilityVersion || undefined,
      iamAvailable: requestForm.iamAvailable
    };
    if (mode.value === 'db') {
      result.value = await authorizationService.simulateDecisionFromDb({
        ...dbForm,
        ...baseRequest
      });
    } else {
      result.value = await authorizationService.simulateDecision({
        policyJson: inlineForm.source === 'policyJson' ? inlineForm.policyJson : undefined,
        templateCode: inlineForm.source === 'templateCode' ? inlineForm.templateCode : undefined,
        ...baseRequest
      });
    }
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '决策模拟失败'));
  } finally {
    submitting.value = false;
  }
}

async function copyHash(hash: string) {
  try {
    await navigator.clipboard.writeText(hash);
    ElMessage.success('策略 hash 已复制');
  } catch {
    ElMessage.warning('复制失败，请手动选择复制');
  }
}

onMounted(() => {
  if (!canQuery.value) return;
  loadOptions();
});
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
}

.card-header-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.mode-switch {
  margin-bottom: 4px;
}

.simulate-form {
  max-width: 720px;
}

.policy-json-input :deep(textarea) {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.result-box {
  padding: 14px 16px;
  border-radius: 8px;
}

.result-allowed {
  border: 1px solid var(--el-color-success-light-7);
  background: var(--el-color-success-light-9);
}

.result-denied {
  border: 1px solid var(--el-color-danger-light-7);
  background: var(--el-color-danger-light-9);
}

.result-verdict {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.verdict-badge {
  padding: 4px 14px;
  border-radius: 6px;
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 1px;
}

.result-allowed .verdict-badge {
  background: var(--el-color-success);
}

.result-denied .verdict-badge {
  background: var(--el-color-danger);
}

.verdict-reason {
  color: var(--el-text-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 14px;
  font-weight: 600;
}

.verdict-reason-text {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-family: inherit;
  font-size: 12px;
  font-weight: 400;
}

.result-desc {
  background: var(--el-fill-color-blank);
}

.hash-chip {
  cursor: pointer;
  color: var(--el-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
</style>
