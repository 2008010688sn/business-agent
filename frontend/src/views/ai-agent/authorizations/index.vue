<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <BaseLayout class="authorization-shell">
    <main class="authorization-page">
      <ElCard class="page-header-card">
        <section class="page-header">
          <div>
            <h1>权限中心</h1>
            <p class="page-subtitle">
              策略（PAP）管理、绑定与授权、PDP 决策模拟与灰度 ENFORCE 门禁：策略发布不可变（CAS）、绑定乐观锁改绑、影子差异归零后才可逐租户放行。
            </p>
          </div>
        </section>
      </ElCard>

      <ElCard class="page-body-card mt-8px">
        <ElTabs v-model="activeTab" class="authorization-tabs">
          <ElTabPane label="策略管理" name="policy">
            <AuthorizationPolicyPanel />
          </ElTabPane>
          <ElTabPane label="绑定管理" name="binding" lazy>
            <AuthorizationBindingPanel />
          </ElTabPane>
          <ElTabPane label="授权管理" name="grant" lazy>
            <AuthorizationGrantPanel />
          </ElTabPane>
          <ElTabPane label="决策模拟" name="simulation" lazy>
            <AuthorizationDecisionSimulationPanel />
          </ElTabPane>
          <ElTabPane label="灰度视图" name="shadow" lazy>
            <AuthorizationShadowGatePanel />
          </ElTabPane>
        </ElTabs>
      </ElCard>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import AuthorizationPolicyPanel from './components/policy-panel.vue';
import AuthorizationBindingPanel from './components/binding-panel.vue';
import AuthorizationGrantPanel from './components/grant-panel.vue';
import AuthorizationDecisionSimulationPanel from './components/decision-simulation-panel.vue';
import AuthorizationShadowGatePanel from './components/shadow-gate-panel.vue';

defineOptions({ name: 'AgentAuthorizationsPage' });

const route = useRoute();
const activeTab = ref('policy');

onMounted(() => {
  const tab = String(Array.isArray(route.query.tab) ? (route.query.tab[0] ?? '') : (route.query.tab ?? '')).trim();
  const ownerType = String(
    Array.isArray(route.query.ownerType) ? (route.query.ownerType[0] ?? '') : (route.query.ownerType ?? '')
  ).trim();
  if (tab === 'binding' || tab === 'grant' || tab === 'policy' || tab === 'simulation' || tab === 'shadow') {
    activeTab.value = tab;
  } else if (ownerType) {
    activeTab.value = 'binding';
  }
});
</script>

<style scoped>
.authorization-shell,
.authorization-page {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.mt-8px {
  margin-top: 8px;
}

.page-header-card {
  flex: 0 0 auto;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-header > div {
  min-width: 0;
}

.page-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
}

.page-subtitle {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  overflow-wrap: anywhere;
}

.page-body-card :deep(.el-card__body) {
  overflow: visible;
}

.authorization-tabs {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.authorization-tabs :deep(.el-tabs__header) {
  flex: 0 0 auto;
  margin-bottom: 12px;
}

.authorization-tabs :deep(.el-tabs__content),
.authorization-tabs :deep(.el-tab-pane) {
  overflow: visible;
  height: auto;
}

.authorization-tabs :deep(.card-header) {
  min-width: 0;
  flex-wrap: wrap;
}

.authorization-tabs :deep(.card-header-sub) {
  min-width: 0;
  overflow-wrap: anywhere;
}

@media (max-width: 920px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
