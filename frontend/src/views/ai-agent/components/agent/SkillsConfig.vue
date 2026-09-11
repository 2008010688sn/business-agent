<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div v-loading="loading" class="skill-binding-panel">
    <header class="binding-header">
      <div>
        <h2>Skill 绑定</h2>
        <p>Agent 只绑定已发布 Skill 版本；数据源、语义和业务知识都由 Skill 自己维护。</p>
      </div>
      <div class="header-actions">
        <ElButton @click="load">
          <ElIcon><Refresh /></ElIcon>
          刷新
        </ElButton>
        <ElButton type="primary" :loading="saving" @click="save">
          <ElIcon><Check /></ElIcon>
          保存绑定
        </ElButton>
      </div>
    </header>

    <div class="binding-notices">
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="数据源、表字段、语义和业务知识都已收口到 Skill，Agent 仅负责绑定多个 Skill 版本。"
      />
      <ElAlert v-if="issues.length" type="error" :closable="false" show-icon :title="issues.join('；')" />
      <ElAlert v-if="loadError" type="error" :closable="false" show-icon :title="loadError">
        <ElButton link type="primary" :loading="loading" @click="load">重试</ElButton>
      </ElAlert>
    </div>

    <ElEmpty v-if="!rows.length" class="binding-empty">
      <template #description>
        <p class="binding-empty-title">{{ emptyState.title }}</p>
        <p v-if="emptyState.hint" class="binding-empty-hint">{{ emptyState.hint }}</p>
      </template>
      <ElButton v-if="!loadError && !loading" type="primary" @click="goSkills">前往 Skill 中心</ElButton>
    </ElEmpty>
    <ElTable v-else :data="rows" border stripe row-key="skillId">
      <ElTableColumn label="绑定" width="76" align="center" class-name="binding-check-cell">
        <template #default="{ row }">
          <ElTooltip
            :disabled="row.selectable || row.bound"
            :content="row.unavailableReason || '该 Skill 当前不可绑定'"
          >
            <ElCheckbox v-model="row.bound" :disabled="!row.selectable && !row.bound" />
          </ElTooltip>
        </template>
      </ElTableColumn>
      <ElTableColumn label="启用" width="76" align="center" class-name="binding-check-cell">
        <template #default="{ row }"><ElCheckbox v-model="row.enabled" :disabled="!row.bound" /></template>
      </ElTableColumn>
      <ElTableColumn prop="skillName" label="Skill" min-width="220" show-overflow-tooltip />
      <ElTableColumn label="版本" min-width="240">
        <template #default="{ row }">
          <ElSelect v-model="row.pinnedSkillVersionId" class="full-width" :disabled="!row.bound" placeholder="选择版本">
            <ElOption
              v-for="version in row.publishedVersions"
              :key="version.id"
              :label="publishedVersionOptionLabel(version, row.skillName)"
              :value="version.id"
            />
          </ElSelect>
        </template>
      </ElTableColumn>
      <ElTableColumn label="生效状态" min-width="150">
        <template #default="{ row }">
          <span class="binding-version-state" :class="[bindingVersionState(row) === '待切换' ? 'is-pending' : '']">
            {{ bindingVersionState(row) || '-' }}
          </span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="执行模式" width="120">
        <template #default="{ row }">{{ row.executionMode || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="优先级" width="140">
        <template #default="{ row }">
          <ElInputNumber
            v-model="row.priority"
            class="full-width"
            controls-position="right"
            :disabled="!row.bound"
            :min="0"
            :max="9999"
          />
        </template>
      </ElTableColumn>
    </ElTable>

    <section class="binding-route-preview">
      <ElDivider content-position="left">Agent 路由预览</ElDivider>
      <RoutePreviewPanel :agent-id="agentId" :target-types="['SKILL']" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Check, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import skillService, {
  type AgentSkillBinding,
  type AgentSkillBindingEditorContext,
  type AgentSkillBindingOption
} from '@/views/ai-agent/services/skill';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import {
  publishedVersionOptionLabel,
  resolveSkillBindingVersionState
} from '@/views/ai-agent/utils/skillPublicationState';
import RoutePreviewPanel from '@/views/ai-agent/components/routing/RoutePreviewPanel.vue';

defineOptions({ name: 'AgentSkillsConfig' });
const props = defineProps<{ agentId: string }>();
const router = useRouter();
interface BindingRow extends AgentSkillBindingOption {
  bound: boolean;
  enabled: boolean;
  priority: number;
  pinnedSkillVersionId?: string;
  persistedPinnedSkillVersionId?: string;
}
const loading = ref(false);
const saving = ref(false);
const rows = ref<BindingRow[]>([]);
const issues = ref<string[]>([]);
/** 绑定编辑上下文加载失败原因；非空时展示可重试的错误态，与「确实没有已发布 Skill」区分开 */
const loadError = ref('');
/** 空表格的三种成因：加载失败、正在加载、确实没有已发布 Skill，文案必须能区分并指出下一步 */
const emptyState = computed(() => {
  if (loadError.value) {
    return { title: '加载失败，数据未获取到', hint: '请点击上方错误提示中的「重试」重新加载绑定数据。' };
  }
  if (loading.value) {
    return { title: '正在加载可绑定的已发布 Skill', hint: '' };
  }
  return {
    title: '暂无可绑定的已发布 Skill',
    hint: 'Agent 只能绑定已发布的 Skill 版本。请先在 Skill 中心编辑并保存草稿，再发布版本，之后回到这里即可绑定。'
  };
});

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    const context: AgentSkillBindingEditorContext = await skillService.getAgentBindingEditorContext(props.agentId);
    const bindings = new Map(context.bindings.map(item => [item.skillId, item]));
    rows.value = context.skills.map(item => {
      const binding = bindings.get(item.skillId);
      return {
        ...item,
        bound: Boolean(binding),
        enabled: binding?.enabled !== false,
        priority: binding?.priority || 0,
        pinnedSkillVersionId: binding?.pinnedSkillVersionId ?? item.publishedVersionId,
        persistedPinnedSkillVersionId: binding?.pinnedSkillVersionId
      };
    });
    issues.value = context.issues || [];
  } catch (error) {
    loadError.value = extractApiErrorMessage(error, '加载 Skill 绑定失败');
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(loadError.value);
    }
    // 清空上一次成功加载的数据，避免错误态与旧的 issues 告警同时堆叠
    rows.value = [];
    issues.value = [];
  } finally {
    loading.value = false;
  }
};

const save = async () => {
  const invalid = rows.value.find(row => row.bound && !row.pinnedSkillVersionId);
  if (invalid) {
    ElMessage.warning(`${invalid.skillName} 没有可固定的已发布版本`);
    return;
  }
  const bindings: AgentSkillBinding[] = rows.value
    .filter(row => row.bound)
    .map(row => ({
      skillId: row.skillId,
      pinnedSkillVersionId: row.pinnedSkillVersionId!,
      priority: row.priority,
      enabled: row.enabled
    }))
    .filter(item => item.skillId && item.pinnedSkillVersionId);
  saving.value = true;
  try {
    await skillService.replaceAgentBindings(props.agentId, bindings);
    ElMessage.success('Skill 绑定已保存，所选版本当前生效。');
    await load();
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '保存 Skill 绑定失败'));
    }
  } finally {
    saving.value = false;
  }
};
const bindingVersionState = (row: BindingRow) =>
  resolveSkillBindingVersionState({
    bound: row.bound,
    pinnedSkillVersionId: row.pinnedSkillVersionId,
    persistedPinnedSkillVersionId: row.persistedPinnedSkillVersionId,
    publishedVersionId: row.publishedVersionId
  });
const goSkills = () => router.push('/ai-agent/skills');
onMounted(load);
</script>

<style scoped>
.skill-binding-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.binding-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.binding-header h2 {
  margin: 0 0 5px;
  font-size: 20px;
}
.binding-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.header-actions {
  display: flex;
  gap: 8px;
}
/* 告警成组后用更紧的 8px，和面板 14px 的区块间距拉开层级，避免多条提示各占一段显得散乱 */
.binding-notices {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.binding-version-state {
  color: var(--el-color-success);
  font-size: 13px;
}
.binding-version-state.is-pending {
  color: var(--el-color-warning);
}
.full-width {
  width: 100%;
}
/* ElCheckbox 默认带 30px 右外边距，无文字时会把居中列里的勾选框整体推向左侧 */
.skill-binding-panel :deep(.binding-check-cell .el-checkbox) {
  margin-right: 0;
}
.binding-empty {
  padding: 28px 0;
}
.binding-empty-title {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 14px;
}
.binding-empty-hint {
  margin: 6px auto 0;
  max-width: 520px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.binding-route-preview {
  display: flex;
  flex-direction: column;
}
/* 分隔线默认上下各 24px，叠加面板 14px 间距后两侧一样宽，标题看不出属于下方区块；
   改成上宽下窄，让「Agent 路由预览」与预览内容成组 */
.binding-route-preview :deep(.el-divider--horizontal) {
  margin: 10px 0 12px;
}
</style>
