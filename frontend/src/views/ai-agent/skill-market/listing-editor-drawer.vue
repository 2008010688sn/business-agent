<template>
  <ElDrawer
    :model-value="modelValue"
    :title="isEdit ? '编辑市场条目' : '创建市场条目'"
    size="600px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @open="handleOpen"
  >
    <ElAlert
      v-if="isEdit"
      class="mb-12px"
      type="info"
      show-icon
      :closable="false"
      title="仅草稿/已驳回状态的条目可修改，提交审核后内容将快照为不可变版本。"
    />
    <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
      <ElFormItem label="条目名称" prop="listingName">
        <ElInput v-model="form.listingName" maxlength="128" show-word-limit placeholder="对外展示的能力条目名称" />
      </ElFormItem>
      <ElFormItem label="条目说明" prop="description">
        <ElInput v-model="form.description" type="textarea" :rows="3" placeholder="说明该能力的用途与适用场景" />
      </ElFormItem>
      <div class="grid-two">
        <ElFormItem label="分类" prop="category">
          <ElInput v-model="form.category" maxlength="128" placeholder="如：数据分析 / 工单处理" />
        </ElFormItem>
        <ElFormItem label="风险等级" prop="riskLevel">
          <ElSelect v-model="form.riskLevel" class="full-width">
            <ElOption
              v-for="item in MARKET_RISK_LEVEL_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
      </div>
      <ElFormItem label="输入输出 Schema 摘要（JSON）" prop="ioSchemaSummary">
        <ElInput
          v-model="form.ioSchemaSummary"
          type="textarea"
          :rows="5"
          placeholder='如 {"input":{...},"output":{...}}，用于市场展示的 IO 契约摘要'
        />
      </ElFormItem>
      <div class="grid-two">
        <ElFormItem label="权限范围说明" prop="permissionScope">
          <ElInput
            v-model="form.permissionScope"
            type="textarea"
            :rows="2"
            maxlength="512"
            placeholder="该能力运行所需的权限范围"
          />
        </ElFormItem>
        <ElFormItem label="数据范围说明" prop="dataScope">
          <ElInput
            v-model="form.dataScope"
            type="textarea"
            :rows="2"
            maxlength="512"
            placeholder="该能力可触达的数据范围"
          />
        </ElFormItem>
      </div>
      <ElFormItem label="依赖资源（JSON）" prop="dependentResources">
        <ElInput
          v-model="form.dependentResources"
          type="textarea"
          :rows="4"
          placeholder='如 [{"type":"datasource","key":"..."}]，声明依赖的数据源/模型/工具等资源'
        />
      </ElFormItem>
      <div class="grid-two">
        <ElFormItem label="使用配额默认值（次/日）" prop="defaultUsageQuota">
          <ElInputNumber v-model="form.defaultUsageQuota" class="full-width" :min="0" :max="1000000" :step="10" />
        </ElFormItem>
        <ElFormItem label="兼容引擎版本" prop="compatibleEngineVersion">
          <ElInput v-model="form.compatibleEngineVersion" maxlength="64" placeholder="如 >=1.2.0" />
        </ElFormItem>
      </div>
    </ElForm>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" :loading="saving" @click="save">保存</ElButton>
    </template>
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import skillMarketService, { MARKET_RISK_LEVEL_OPTIONS } from '@/views/ai-agent/services/skillMarket';
import type { SkillMarketListing, SkillMarketListingPayload } from '@/views/ai-agent/services/skillMarket';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

const props = defineProps<{
  modelValue: boolean;
  listing: SkillMarketListing | null;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'saved'): void;
}>();

const formRef = ref<FormInstance>();
const saving = ref(false);
const form = reactive<SkillMarketListingPayload>(emptyPayload());

const isEdit = computed(() => Boolean(props.listing?.id));

const jsonValidator = (_rule: unknown, value: string | undefined, callback: (error?: Error) => void) => {
  if (!value || !value.trim()) {
    callback();
    return;
  }
  try {
    JSON.parse(value);
    callback();
  } catch {
    callback(new Error('请输入合法的 JSON 文本'));
  }
};

const rules: FormRules = {
  listingName: [
    { required: true, message: '请输入条目名称', trigger: 'blur' },
    { max: 128, message: '条目名称不能超过128字符', trigger: 'blur' }
  ],
  ioSchemaSummary: [{ validator: jsonValidator, trigger: 'blur' }],
  dependentResources: [{ validator: jsonValidator, trigger: 'blur' }]
};

function emptyPayload(): SkillMarketListingPayload {
  return {
    listingName: '',
    description: '',
    category: '',
    ioSchemaSummary: '',
    permissionScope: '',
    dataScope: '',
    riskLevel: 'LOW',
    dependentResources: '',
    defaultUsageQuota: undefined,
    compatibleEngineVersion: ''
  };
}

function handleOpen() {
  const source = props.listing;
  Object.assign(form, emptyPayload(), {
    listingName: source?.listingName || '',
    description: source?.description || '',
    category: source?.category || '',
    ioSchemaSummary: source?.ioSchemaSummary || '',
    permissionScope: source?.permissionScope || '',
    dataScope: source?.dataScope || '',
    riskLevel: source?.riskLevel || 'LOW',
    dependentResources: source?.dependentResources || '',
    defaultUsageQuota: source?.defaultUsageQuota,
    compatibleEngineVersion: source?.compatibleEngineVersion || ''
  });
  formRef.value?.clearValidate();
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  saving.value = true;
  try {
    if (isEdit.value && props.listing) {
      await skillMarketService.modify(props.listing.id, { ...form });
      ElMessage.success('市场条目已更新');
    } else {
      await skillMarketService.create({ ...form });
      ElMessage.success('市场条目已创建（草稿）');
    }
    emit('update:modelValue', false);
    emit('saved');
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, isEdit.value ? '更新市场条目失败' : '创建市场条目失败'));
    }
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.grid-two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.full-width {
  width: 100%;
}

.mb-12px {
  margin-bottom: 12px;
}

@media (max-width: 900px) {
  .grid-two {
    grid-template-columns: 1fr;
  }
}
</style>
