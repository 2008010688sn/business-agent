<template>
  <section
    class="agent-ui-message"
    :class="{ 'is-invalid': invalid, 'is-disabled': disabled && !invalid }"
    :aria-busy="disabled"
    :aria-disabled="disabled || invalid"
    @click="handleCardClick"
  >
    <header class="agent-ui-header">
      <div class="agent-ui-title">
        <ElIcon><component :is="statusIcon" /></ElIcon>
        <span>业务流程</span>
      </div>
      <div class="agent-ui-meta">
        <span v-if="durationText">{{ durationText }}</span>
      </div>
    </header>

    <div v-if="!invalid && flowSteps.length" class="flow-steps">
      <button type="button" class="flow-steps-toggle" @click.stop="stepsExpanded = !stepsExpanded">
        已执行 {{ flowSteps.length }} 步
        <ElIcon><component :is="stepsExpanded ? ArrowUp : ArrowDown" /></ElIcon>
      </button>
      <ol v-show="stepsExpanded" class="flow-steps-list">
        <li v-for="(step, index) in flowSteps" :key="`${step.kind}-${index}`">
          <span class="flow-step-kind">{{ stepKindLabel(step.kind) }}</span>
          <span class="flow-step-label">{{ step.label }}</span>
          <span class="flow-step-status">{{ stepStatusLabel(step.status) }}</span>
          <span v-if="isFiniteNumber(step.durationMs)" class="flow-step-duration">
            {{ formatStepDuration(step.durationMs) }}
          </span>
        </li>
      </ol>
    </div>

    <ElAlert v-if="invalid" type="warning" :closable="false" title="流程卡片已失效，请重新发起" />

    <SafeMarkdownContent v-else-if="contentFormat === 'markdown'" :content="ui.content?.text || ''" />
    <p v-else class="agent-ui-text">{{ ui.content?.text }}</p>

    <p v-if="!invalid && !options.length && !showForm && payloadValues.textSearchEnabled" class="flow-text-search-hint">
      可直接输入名称或编码搜索
    </p>

    <ElAlert v-if="validationErrors.length" type="error" :closable="false">
      <ul class="validation-errors">
        <li v-for="error in validationErrors" :key="error">{{ error }}</li>
      </ul>
    </ElAlert>

    <div v-if="!invalid && showDraftReview" class="flow-draft">
      <section v-for="section in draftSections" :key="section.title" class="flow-draft-section">
        <h4>{{ section.title }}</h4>
        <dl class="flow-draft-fields">
          <template v-for="field in section.fields" :key="field.path">
            <dt>{{ field.label }}</dt>
            <dd>
              <span>{{ field.value || '未填写' }}</span>
              <span v-if="field.source === 'HISTORY'" class="flow-source-tag">历史参考</span>
            </dd>
          </template>
        </dl>
      </section>
      <section v-if="draftProducts.length" class="flow-draft-section">
        <h4>商品明细</h4>
        <div class="flow-draft-list">
          <div v-for="(product, index) in draftProducts" :key="String(product.productId || product.productNo || index)" class="flow-draft-item">
            <strong>{{ product.productName || product.productNo || `商品 ${index + 1}` }}</strong>
            <span>编码：{{ product.productNo || '未填写' }}</span>
            <span>数量：{{ product.num ?? '未填写' }}</span>
            <span v-if="sourceFor(`/input/productList/${index}`) === 'HISTORY'" class="flow-source-tag">历史参考</span>
          </div>
        </div>
      </section>
      <section v-if="draftAddresses.length" class="flow-draft-section">
        <h4>提货与收货信息</h4>
        <div class="flow-draft-list">
          <div v-for="(address, index) in draftAddresses" :key="String(address.siteId || address.siteCode || index)" class="flow-draft-item">
            <strong>{{ address.type === '0' || address.type === 'FROM' ? '提货网点' : '收货网点' }}：{{ address.siteName || '未填写' }}</strong>
            <span>{{ address.fullAddress || address.address || '地址未填写' }}</span>
            <span v-if="address.contact || address.tel">联系人：{{ address.contact || '未填写' }} {{ address.tel || '' }}</span>
            <span v-if="sourceFor(`/input/addressList/${index}`) === 'HISTORY'" class="flow-source-tag">历史参考</span>
          </div>
        </div>
      </section>
      <p v-if="actionType === 'REVIEW'" class="flow-review-hint">需要调整时，直接在下方对话框说明一个或多个修改。</p>
    </div>

    <ElForm v-if="!invalid && showForm" label-position="top" class="flow-form" @submit.prevent="submitForm">
      <div class="flow-form-grid">
        <ElFormItem v-for="field in formFields" :key="field.name" :label="field.label" :required="field.required">
          <ElSelect v-if="field.enumValues.length" v-model="formValues[field.name]" class="flow-control">
            <ElOption
              v-for="option in field.enumValues"
              :key="String(option)"
              :label="String(option)"
              :value="option"
            />
          </ElSelect>
          <ElSwitch v-else-if="field.type === 'boolean'" v-model="formValues[field.name]" />
          <ElInputNumber
            v-else-if="field.type === 'number' || field.type === 'integer'"
            :model-value="getNumberValue(field.name)"
            class="flow-control"
            controls-position="right"
            @update:model-value="setNumberValue(field.name, $event)"
          />
          <ElInput
            v-else-if="field.type === 'object' || field.type === 'array'"
            v-model="formTextValues[field.name]"
            type="textarea"
            :rows="4"
            :placeholder="field.type === 'array' ? '[]' : '{}'"
          />
          <ElInput
            v-else
            :model-value="getTextValue(field.name)"
            class="flow-control"
            @update:model-value="setTextValue(field.name, $event)"
          />
        </ElFormItem>
      </div>
      <div class="flow-actions">
        <ElButton type="primary" native-type="submit" :disabled="disabled">提交</ElButton>
        <ElButton v-if="cancelAction" type="danger" :disabled="disabled" @click="trigger(cancelAction)">
          {{ cancelAction.label || '取消本次下单' }}
        </ElButton>
      </div>
    </ElForm>

    <div v-else-if="!invalid && options.length" class="flow-options">
      <button
        v-for="(option, index) in options"
        :key="`${String(option.value)}-${index}`"
        type="button"
        class="flow-option"
        :disabled="disabled"
        @click="selectOption(option)"
      >
        <span class="flow-option-label">{{ option.label || option.value }}</span>
        <span v-if="option.summary" class="flow-option-summary">{{ option.summary }}</span>
      </button>
    </div>

    <ElTable v-if="!showDraftReview && tableRows.length" :data="tableRows" size="small" class="flow-result-table">
      <ElTableColumn
        v-for="column in tableColumns"
        :key="column"
        :prop="column"
        :label="column"
        show-overflow-tooltip
      />
    </ElTable>
    <ElDescriptions v-else-if="!showDraftReview && resultEntries.length" :column="1" border size="small">
      <ElDescriptionsItem v-for="entry in resultEntries" :key="entry[0]" :label="entry[0]">
        {{ formatValue(entry[1]) }}
      </ElDescriptionsItem>
    </ElDescriptions>

    <div v-if="!invalid && visibleActions.length && !showForm && !options.length" class="flow-actions">
      <ElButton
        v-for="action in visibleActions"
        :key="action.actionId"
        :type="buttonType(action)"
        :disabled="disabled"
        @click="trigger(action)"
      >
        {{ action.label || action.type }}
      </ElButton>
    </div>
    <div v-else-if="!invalid && skipAction && options.length" class="flow-actions">
      <ElButton :disabled="disabled" @click="trigger(skipAction)">{{ skipAction.label || '跳过' }}</ElButton>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { ArrowDown, ArrowUp, CircleCheck, Clock, Warning } from '@element-plus/icons-vue';
import SafeMarkdownContent from '@/views/ai-agent/components/run/SafeMarkdownContent.vue';
import {
  emptySlotFields,
  hasEmptySlotFilter,
  toAgentUiActionRequest,
  type AgentUiAction,
  type AgentUiActionRequest,
  type AgentUiMessage,
  type AgentUiOption
} from '@/views/ai-agent/utils/agentUi';

defineOptions({ name: 'AgentMessageRenderer' });
const props = defineProps<{ ui: AgentUiMessage; disabled?: boolean; invalid?: boolean }>();
const emit = defineEmits<{ action: [request: AgentUiActionRequest] }>();

type JsonSchema = {
  type?: string;
  title?: string;
  description?: string;
  required?: string[];
  properties?: Record<string, JsonSchema & { enum?: unknown[] }>;
};

type FormScalar = string | number | boolean;
type FormField = {
  name: string;
  label: string;
  type: string;
  required: boolean;
  enumValues: FormScalar[];
};
type DraftRecord = Record<string, unknown>;
type DraftField = { path: string; label: string; value: string; source?: string };

const formValues = reactive<Record<string, FormScalar | undefined>>({});
const formTextValues = reactive<Record<string, string>>({});
const contentFormat = computed(() => props.ui.content?.format || 'markdown');
const payloadValues = computed(() => props.ui.payload?.values || {});
const options = computed(() => props.ui.payload?.options || []);
const actionType = computed(() => String(props.ui.payload?.action || '').toUpperCase());
const schema = computed(() => (payloadValues.value.schema || {}) as JsonSchema);
const showForm = computed(() => ['COLLECT', 'VALIDATION'].includes(actionType.value) && formFields.value.length > 0);
const validationErrors = computed(() => {
  const errors = payloadValues.value.errors;
  return Array.isArray(errors) ? errors.map(String) : [];
});
const isFormScalar = (value: unknown): value is FormScalar =>
  typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean';
const emptySlots = computed(() => emptySlotFields(props.ui));
const formFields = computed<FormField[]>(() => {
  const required = new Set(schema.value.required || []);
  const requiredPaths = Array.isArray(payloadValues.value.requiredPaths)
    ? payloadValues.value.requiredPaths.map(path => String(path).split('/').filter(Boolean).at(-1))
    : [];
  requiredPaths.filter(Boolean).forEach(path => required.add(String(path)));
  const fields = Object.entries(schema.value.properties || {}).map(([name, definition]) => ({
    name,
    label: definition.title || definition.description || name,
    type: definition.type || 'string',
    required: required.has(name),
    enumValues: Array.isArray(definition.enum) ? definition.enum.filter(isFormScalar) : []
  }));
  if (!hasEmptySlotFilter(props.ui)) return fields;
  const slotByName = new Map(emptySlots.value.filter(slot => slot.name).map(slot => [slot.name, slot]));
  return fields
    .filter(field => slotByName.has(field.name))
    .map(field => ({
      ...field,
      required: true,
      label: slotByName.get(field.name)?.prompt || field.label
    }));
});
const visibleActions = computed(() =>
  (props.ui.actions || []).filter(action => {
    if (['SELECT', 'SKIP'].includes(action.type)) return false;
    return !(showForm.value && action.type === 'SUBMIT');
  })
);
const selectAction = computed(() => (props.ui.actions || []).find(action => action.type === 'SELECT'));
const submitAction = computed(() => (props.ui.actions || []).find(action => action.type === 'SUBMIT'));
const skipAction = computed(() => (props.ui.actions || []).find(action => action.type === 'SKIP'));
const cancelAction = computed(() => (props.ui.actions || []).find(action => action.type === 'CANCEL'));
const isFiniteNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);
const formatStepDuration = (durationMs?: number | null) => {
  if (!isFiniteNumber(durationMs)) return '';
  return durationMs < 1000 ? `${Math.round(durationMs)}ms` : `${(durationMs / 1000).toFixed(1)}s`;
};
const durationText = computed(() => formatStepDuration(props.ui.timing?.durationMs));
const flowSteps = computed(() => (props.ui.steps || []).filter(step => step && step.label));
const stepsExpanded = ref(false);
const STEP_KIND_LABELS: Record<string, string> = {
  tool: '查询工具',
  node: '流程节点',
  state: '流程状态'
};
const STEP_STATUS_LABELS: Record<string, string> = {
  running: '进行中',
  success: '完成',
  failed: '失败',
  waiting: '等待用户操作',
  cancelled: '已取消'
};
const stepKindLabel = (kind?: string) => (kind ? STEP_KIND_LABELS[kind] || '' : '');
const stepStatusLabel = (status?: string) => (status ? STEP_STATUS_LABELS[status] || status : '');
const statusIcon = computed(() =>
  props.invalid ? Warning : props.ui.timing?.stageCode === 'FLOW_DONE' ? CircleCheck : actionType.value === 'UNKNOWN' ? Warning : Clock
);
const resultData = computed(() => payloadValues.value.data);
const draft = computed<DraftRecord>(() => {
  const value = resultData.value || payloadValues.value.currentValues;
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as DraftRecord) : {};
});
const showDraftReview = computed(() => ['REVIEW', 'CONFIRM'].includes(actionType.value) && Object.keys(draft.value).length > 0);
const fieldMeta = computed<Record<string, unknown>>(() => {
  const value = payloadValues.value.fieldMeta;
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
});
const sourceFor = (path: string) => {
  const value = fieldMeta.value[path];
  return value && typeof value === 'object' ? String((value as Record<string, unknown>).source || '') : '';
};
const draftField = (path: string, label: string, value: unknown): DraftField => ({
  path,
  label,
  value: value === null || value === undefined ? '' : String(value),
  source: sourceFor(path)
});
const draftSections = computed(() => [
  {
    title: '客户与项目',
    fields: [
      draftField('/input/companyName', '客户', draft.value.companyName),
      draftField('/input/oneProjectName', '一级项目', draft.value.oneProjectName),
      draftField('/input/twoProjectName', '二级项目', draft.value.twoProjectName)
    ]
  },
  {
    title: '需求信息',
    fields: [
      draftField('/input/type', '需求类型', draft.value.type),
      draftField('/input/businessType', '业务类型', draft.value.businessTypeName || draft.value.businessType),
      draftField('/input/arrivalTime', '到货时间', draft.value.arrivalTime),
      draftField('/input/industryName', '行业', draft.value.industryName),
      draftField('/input/contractCode', '合同', draft.value.contractCode),
      draftField('/input/remark', '备注', draft.value.remark)
    ]
  }
]);
const draftProducts = computed<DraftRecord[]>(() =>
  Array.isArray(draft.value.productList) ? (draft.value.productList as DraftRecord[]) : []
);
const draftAddresses = computed<DraftRecord[]>(() =>
  Array.isArray(draft.value.addressList) ? (draft.value.addressList as DraftRecord[]) : []
);
const tableRows = computed<Record<string, unknown>[]>(() => {
  const value = resultData.value;
  if (Array.isArray(value)) return value.filter(item => item && typeof item === 'object') as Record<string, unknown>[];
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of ['rows', 'items', 'data']) {
      if (Array.isArray(record[key])) {
        return (record[key] as unknown[]).filter(item => item && typeof item === 'object') as Record<string, unknown>[];
      }
    }
  }
  return [];
});
const tableColumns = computed(() => Array.from(new Set(tableRows.value.flatMap(row => Object.keys(row)))));
const resultEntries = computed<[string, unknown][]>(() => {
  const value = resultData.value;
  if (!value || Array.isArray(value) || typeof value !== 'object' || tableRows.value.length) return [];
  return Object.entries(value as Record<string, unknown>);
});

watch(
  () => [props.ui.source?.flowInstanceId, payloadValues.value.currentValues] as const,
  () => {
    Object.keys(formValues).forEach(key => Reflect.deleteProperty(formValues, key));
    Object.keys(formTextValues).forEach(key => Reflect.deleteProperty(formTextValues, key));
    const current = payloadValues.value.currentValues;
    const values =
      current && typeof current === 'object' && !Array.isArray(current) ? (current as Record<string, unknown>) : {};
    formFields.value.forEach(field => {
      const value = values[field.name];
      if (field.type === 'array' || field.type === 'object') {
        formTextValues[field.name] = value === null || value === undefined ? '' : JSON.stringify(value, null, 2);
      } else if (field.type === 'boolean') {
        formValues[field.name] = Boolean(value);
      } else if (field.type === 'number' || field.type === 'integer') {
        formValues[field.name] = typeof value === 'number' ? value : undefined;
      } else {
        formValues[field.name] = isFormScalar(value) ? value : undefined;
      }
    });
  },
  { immediate: true, deep: true }
);

const trigger = (action: AgentUiAction) => {
  if (props.invalid || props.disabled) return;
  emit('action', toAgentUiActionRequest(props.ui, action));
};
const buttonType = (action: AgentUiAction) => {
  if (['CONFIRM', 'SUBMIT', 'KEEP_FLOW'].includes(action.type)) return 'primary';
  if (['CANCEL', 'CONFIRM_CANCEL'].includes(action.type)) return 'danger';
  return undefined;
};
const getNumberValue = (fieldName: string) => {
  const value = formValues[fieldName];
  return typeof value === 'number' ? value : undefined;
};
const setNumberValue = (fieldName: string, value: number | undefined) => {
  formValues[fieldName] = value;
};
const getTextValue = (fieldName: string) => {
  const value = formValues[fieldName];
  return typeof value === 'string' || typeof value === 'number' ? value : '';
};
const setTextValue = (fieldName: string, value: string | number) => {
  formValues[fieldName] = value;
};
const selectOption = (option: AgentUiOption) => {
  if (props.invalid || props.disabled) return;
  if (selectAction.value) emit('action', toAgentUiActionRequest(props.ui, selectAction.value, option));
};
const handleCardClick = () => {
  if (props.invalid) {
    ElMessage.warning('流程卡片已失效，请重新发起');
  }
};
const submitForm = () => {
  if (!submitAction.value || props.invalid || props.disabled) return;
  const payload: Record<string, unknown> = { ...formValues };
  try {
    formFields.value.forEach(field => {
      if (field.type === 'array' || field.type === 'object') {
        const text = formTextValues[field.name]?.trim();
        payload[field.name] = text ? JSON.parse(text) : field.type === 'array' ? [] : {};
      }
    });
  } catch {
    ElMessage.error('JSON 格式不正确');
    return;
  }
  const request = toAgentUiActionRequest(props.ui, submitAction.value);
  request.flowAction.payload = payload;
  emit('action', request);
};
const formatValue = (value: unknown) =>
  value && typeof value === 'object'
    ? JSON.stringify(value)
    : value === null || value === undefined
      ? ''
      : String(value);
</script>

<style scoped>
.agent-ui-message {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: min(100%, 760px);
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  box-shadow: 0 1px 2px rgb(0 0 0 / 4%);
}
.agent-ui-message.is-disabled {
  opacity: 0.78;
}
.agent-ui-message.is-invalid {
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
  cursor: not-allowed;
}
.agent-ui-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.agent-ui-title,
.agent-ui-meta {
  display: flex;
  align-items: center;
  gap: 7px;
}
.agent-ui-title {
  color: var(--el-text-color-primary);
  font-weight: 650;
}
.agent-ui-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.agent-ui-text {
  margin: 0;
  line-height: 1.7;
}
.validation-errors {
  margin: 0;
  padding-left: 18px;
}
.flow-form {
  width: 100%;
}
.flow-draft {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.flow-draft-section {
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.flow-draft-section h4 {
  margin: 0 0 8px;
  color: var(--el-text-color-primary);
  font-size: 14px;
}
.flow-draft-fields {
  display: grid;
  grid-template-columns: minmax(88px, 120px) minmax(0, 1fr);
  gap: 6px 12px;
  margin: 0;
}
.flow-draft-fields dt {
  color: var(--el-text-color-secondary);
}
.flow-draft-fields dd {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
  margin: 0;
  overflow-wrap: anywhere;
}
.flow-draft-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  gap: 8px;
}
.flow-draft-item {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-extra-light);
  overflow-wrap: anywhere;
}
.flow-source-tag {
  width: fit-content;
  padding: 1px 6px;
  color: var(--el-color-warning-dark-2);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 4px;
  background: var(--el-color-warning-light-9);
  font-size: 12px;
}
.flow-review-hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.flow-text-search-hint {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.flow-form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr));
  gap: 0 12px;
}
.flow-control {
  width: 100%;
}
.flow-options {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr));
  gap: 8px;
}
.flow-option {
  display: flex;
  align-items: flex-start;
  flex-direction: column;
  gap: 4px;
  min-height: 58px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-blank);
  text-align: left;
  cursor: pointer;
}
.flow-option:hover:not(:disabled) {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.flow-option:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 1px;
}
.flow-option:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.flow-option-label {
  font-weight: 600;
}
.flow-option-summary {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
.flow-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.flow-result-table {
  width: 100%;
}
.flow-steps {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.flow-steps-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  align-self: flex-start;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-color-primary);
  font-size: 12px;
  cursor: pointer;
}
.flow-steps-toggle:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 1px;
}
.flow-steps-list {
  margin: 0;
  padding-left: 18px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.flow-steps-list li {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
}
</style>
