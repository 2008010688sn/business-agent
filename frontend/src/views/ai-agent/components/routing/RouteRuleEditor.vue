<template>
  <div class="route-rule-editor">
    <ElAlert
      v-if="isReadOnly"
      class="unsafe-rules-alert"
      type="error"
      :closable="false"
      show-icon
      title="当前路由规则格式异常，已锁定编辑以避免覆盖原配置。"
    />

    <div v-for="field in fieldOptions" :key="field.key" class="rule-field">
      <div class="rule-field-heading">
        <div>
          <strong>{{ field.label }}</strong>
          <span>{{ field.description }}</span>
        </div>
        <span class="rule-count">{{ rules[field.key].length }}/100</span>
      </div>
      <div v-if="rules[field.key].length" class="rule-tags">
        <ElTag
          v-for="(item, index) in rules[field.key]"
          :key="`${field.key}-${item}`"
          :closable="!isReadOnly"
          effect="plain"
          :type="field.type"
          @close="removeRule(field.key, index)"
        >
          {{ item }}
        </ElTag>
      </div>
      <ElInput
        v-model="drafts[field.key]"
        :maxlength="200"
        clearable
        :disabled="isReadOnly"
        :placeholder="field.placeholder"
        @keyup.enter="addRule(field.key)"
      >
        <template #append>
          <ElTooltip :content="`添加${field.label}`" placement="top">
            <ElButton :aria-label="`添加${field.label}`" :disabled="isReadOnly" @click="addRule(field.key)">
              <ElIcon><Plus /></ElIcon>
            </ElButton>
          </ElTooltip>
        </template>
      </ElInput>
    </div>

    <div v-if="showFlowAutoSelect" class="rule-field flow-auto-select">
      <div class="rule-field-heading">
        <div>
          <strong>FLOW 自动选择</strong>
          <span>高置信路由自动选择 FLOW，仍需业务确认</span>
        </div>
      </div>
      <ElSwitch
        :model-value="rules.allowFlowAutoSelect"
        :disabled="flowAutoSelectDisabled"
        active-text="启用"
        inactive-text="关闭"
        @update:model-value="setAllowFlowAutoSelect"
      />
    </div>

    <ElAlert v-if="validation.errors.length" type="error" :closable="false" show-icon>
      <ul class="rule-messages">
        <li v-for="error in validation.errors" :key="error">{{ error }}</li>
      </ul>
    </ElAlert>
    <ElAlert v-if="validation.warnings.length" type="warning" :closable="false" show-icon>
      <ul class="rule-messages">
        <li v-for="warning in validation.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </ElAlert>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import {
  cloneRouteRules,
  emptyRouteRules,
  isPatternRouteRuleField,
  normalizeRoutePattern,
  normalizeRouteText,
  parseRouteRules,
  ROUTE_RULE_FIELDS,
  type RouteRuleField,
  type RouteRules,
  type RouteRulesParseResult,
  validateRoutePattern
} from '@/views/ai-agent/utils/routeRules';

defineOptions({ name: 'RouteRuleEditor' });

type RouteRuleEditorContext = 'skill' | 'collaborator';

interface RouteRuleEditorValidation {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

const props = withDefaults(
  defineProps<{
    modelValue?: unknown;
    context?: RouteRuleEditorContext;
    skillKind?: string;
    executionMode?: string;
  }>(),
  {
    context: 'skill',
    skillKind: '',
    executionMode: ''
  }
);

const emit = defineEmits<{
  'update:modelValue': [value: RouteRules];
}>();

const fieldOptions: Array<{
  key: RouteRuleField;
  label: string;
  description: string;
  placeholder: string;
  type?: 'success' | 'warning' | 'danger' | 'info';
}> = [
  {
    key: 'exact',
    label: '精确问题',
    description: '仅匹配完整问题',
    placeholder: '输入完整问题后回车',
    type: 'success'
  },
  {
    key: 'phrases',
    label: '业务短语',
    description: '可在句中识别',
    placeholder: '输入业务词组后回车'
  },
  {
    key: 'aliases',
    label: '名称别名',
    description: '补充常用叫法',
    placeholder: '输入别名后回车',
    type: 'info'
  },
  {
    key: 'positiveExamples',
    label: '正向示例',
    description: '召回语义相近表达',
    placeholder: '输入示例问题后回车'
  },
  {
    key: 'positivePatterns',
    label: '通配正向规则',
    description: '使用 1 至 3 个 * 匹配前缀、后缀或中间变量片段',
    placeholder: '例如：*下单*',
    type: 'success'
  },
  {
    key: 'negativeExamples',
    label: '软反例',
    description: '降低候选得分',
    placeholder: '输入反例问题后回车',
    type: 'warning'
  },
  {
    key: 'hardExcludes',
    label: '硬排除',
    description: '命中后移除候选',
    placeholder: '输入禁止命中的表达后回车',
    type: 'danger'
  },
  {
    key: 'hardExcludePatterns',
    label: '通配硬排除',
    description: '使用 1 至 3 个 * 排除前缀、后缀或中间变量表达',
    placeholder: '例如：*不要*下单*',
    type: 'danger'
  }
];

const rules = reactive<RouteRules>(emptyRouteRules());
const drafts = reactive<Record<RouteRuleField, string>>(
  ROUTE_RULE_FIELDS.reduce(
    (result, field) => ({ ...result, [field]: '' }),
    {} as Record<RouteRuleField, string>
  )
);
const sourceResult = ref<RouteRulesParseResult>(parseRouteRules(emptyRouteRules()));

const isReadOnly = computed(() => sourceResult.value.status === 'invalid');
const flowAutoSelectAllowed = computed(
  () =>
    props.context === 'skill' &&
    ['ACTION', 'ORCHESTRATION'].includes(props.skillKind) &&
    props.executionMode === 'FLOW'
);
const showFlowAutoSelect = computed(
  () => props.context === 'skill' && (flowAutoSelectAllowed.value || rules.allowFlowAutoSelect)
);
const flowAutoSelectDisabled = computed(
  () => isReadOnly.value || (!flowAutoSelectAllowed.value && !rules.allowFlowAutoSelect)
);
const contextErrors = computed(() => {
  if (props.context === 'collaborator' && rules.allowFlowAutoSelect) {
    return ['协作 Agent 不支持 allowFlowAutoSelect'];
  }
  if (props.context === 'skill' && rules.allowFlowAutoSelect && !flowAutoSelectAllowed.value) {
    return ['仅 ACTION 或 ORCHESTRATION 类型的 FLOW Skill 可以开启自动选择'];
  }
  return [];
});
const validation = computed<RouteRuleEditorValidation>(() => {
  const base = isReadOnly.value ? sourceResult.value : parseRouteRules(rules);
  return {
    valid: base.status === 'valid' && contextErrors.value.length === 0,
    errors: [...base.errors, ...contextErrors.value],
    warnings: base.warnings
  };
});

const syncRules = (source: unknown) => {
  const result = parseRouteRules(source);
  sourceResult.value = result;
  ROUTE_RULE_FIELDS.forEach(field => {
    rules[field] = [...result.rules[field]];
  });
  rules.allowFlowAutoSelect = result.rules.allowFlowAutoSelect;
};

const commit = () => {
  const result = parseRouteRules(rules);
  const valid = result.status === 'valid' && contextErrors.value.length === 0;
  if (!valid) {
    return;
  }
  emit('update:modelValue', cloneRouteRules(result.rules));
};

const addRule = (field: RouteRuleField) => {
  if (isReadOnly.value) {
    return;
  }
  const value = isPatternRouteRuleField(field) ? normalizeRoutePattern(drafts[field]) : normalizeRouteText(drafts[field]);
  if (!value) {
    drafts[field] = '';
    return;
  }
  if (isPatternRouteRuleField(field)) {
    const patternError = validateRoutePattern(value);
    if (patternError) {
      ElMessage.warning(patternError);
      return;
    }
  }
  if (rules[field].includes(value)) {
    ElMessage.info('该规则已存在');
    drafts[field] = '';
    return;
  }
  if (rules[field].length >= 100) {
    ElMessage.warning('每类路由规则最多 100 条');
    return;
  }
  rules[field].push(value);
  drafts[field] = '';
  commit();
};

const removeRule = (field: RouteRuleField, index: number) => {
  if (isReadOnly.value) {
    return;
  }
  rules[field].splice(index, 1);
  commit();
};

const setAllowFlowAutoSelect = (value: boolean | string | number) => {
  if (isReadOnly.value) {
    return;
  }
  const enabled = value === true;
  if (enabled && !flowAutoSelectAllowed.value) {
    ElMessage.warning('仅 ACTION 或 ORCHESTRATION 类型的 FLOW Skill 可以开启自动选择');
    return;
  }
  if (rules.allowFlowAutoSelect === enabled) {
    return;
  }
  rules.allowFlowAutoSelect = enabled;
  commit();
};

watch(
  () => props.modelValue,
  value => syncRules(value),
  { immediate: true, deep: true }
);
</script>

<style scoped>
.route-rule-editor {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.rule-field {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

.rule-field-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 9px;
}

.rule-field-heading strong {
  display: block;
  color: var(--el-text-color-primary);
  font-size: 13px;
}

.rule-field-heading span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.rule-count {
  flex: none;
  font-variant-numeric: tabular-nums;
}

.rule-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 112px;
  margin-bottom: 8px;
  overflow: auto;
}

.rule-tags :deep(.el-tag) {
  max-width: 100%;
}

.rule-tags :deep(.el-tag__content) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flow-auto-select {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.rule-messages {
  margin: 0;
  padding-left: 18px;
}

.route-rule-editor > :deep(.el-alert) {
  grid-column: 1 / -1;
}

@media (max-width: 900px) {
  .route-rule-editor {
    grid-template-columns: 1fr;
  }
}
</style>
