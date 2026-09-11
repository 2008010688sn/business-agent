<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div v-if="querySnapshots.length || chartCandidates.length || analysisReport" class="structured">
    <section v-for="(snapshot, index) in querySnapshots" :key="`table-${index}`" class="block">
      <header>{{ snapshot.title || `查询结果 ${index + 1}` }}</header>
      <p v-if="snapshot.summary" class="summary">{{ snapshot.summary }}</p>
      <ElTable :data="snapshot.rows" border stripe size="small" max-height="280" empty-text="无行">
        <ElTableColumn
          v-for="column in snapshot.columns"
          :key="column"
          :prop="column"
          :label="column"
          min-width="96"
          show-overflow-tooltip
        />
      </ElTable>
    </section>

    <section v-for="(chart, index) in chartCandidates" :key="`chart-${index}`" class="block">
      <header>{{ chart.title || `图表 ${index + 1}` }}</header>
      <p class="summary">{{ chartTypeLabel(chart.chartType) }} · {{ chart.note || '由工具结果生成，非模型散文反解析' }}</p>
      <div v-if="chart.bars.length" class="bars">
        <div v-for="bar in chart.bars" :key="bar.label" class="bar-row">
          <span class="bar-label" :title="bar.label">{{ bar.label }}</span>
          <div class="bar-track">
            <div class="bar-fill" :style="{ width: `${bar.percent}%` }" />
          </div>
          <span class="bar-value">{{ bar.value }}</span>
        </div>
      </div>
      <ElEmpty v-else description="无可视化数据点" :image-size="48" />
    </section>

    <section v-if="analysisReport" class="block">
      <header>分析报告</header>
      <pre class="answer">{{ analysisReport }}</pre>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';

defineOptions({ name: 'RuntimeStructuredArtifacts' });

interface StructuredArtifact {
  schemaVersion?: string;
  data?: string | null;
}

const QUERY_RESULT_SCHEMA = 'query-result/v1';
const CHART_CANDIDATE_SCHEMA = 'chart-candidate/v1';
const ANALYSIS_REPORT_SCHEMA = 'analysis-report/v1';

const props = defineProps<{
  artifacts?: StructuredArtifact[] | null;
}>();

interface QuerySnapshot {
  title?: string;
  summary?: string;
  columns: string[];
  rows: Record<string, unknown>[];
}

interface ChartView {
  title?: string;
  chartType?: string;
  note?: string;
  bars: Array<{ label: string; value: string; percent: number }>;
}

const parseJson = (data?: string | null): unknown => {
  if (!data) return null;
  try {
    return JSON.parse(data);
  } catch {
    return null;
  }
};

const asRecord = (value: unknown): Record<string, unknown> | null => {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
};

const querySnapshots = computed<QuerySnapshot[]>(() => {
  const items: QuerySnapshot[] = [];
  for (const artifact of props.artifacts || []) {
    if (artifact.schemaVersion !== QUERY_RESULT_SCHEMA) continue;
    const root = asRecord(parseJson(artifact.data || null));
    const snapshots = Array.isArray(root?.snapshots) ? root.snapshots : [];
    for (const snapshot of snapshots) {
      const record = asRecord(snapshot);
      if (!record) continue;
      const columns = Array.isArray(record.columns)
        ? record.columns.map(item => String(item)).filter(Boolean)
        : [];
      const rows = Array.isArray(record.rows)
        ? record.rows
            .map(item => asRecord(item))
            .filter((item): item is Record<string, unknown> => item != null)
        : [];
      if (!columns.length || !rows.length) continue;
      items.push({
        title: record.title ? String(record.title) : undefined,
        summary: record.summary ? String(record.summary) : undefined,
        columns,
        rows
      });
    }
  }
  return items;
});

const chartCandidates = computed<ChartView[]>(() => {
  const items: ChartView[] = [];
  for (const artifact of props.artifacts || []) {
    if (artifact.schemaVersion !== CHART_CANDIDATE_SCHEMA) continue;
    const root = asRecord(parseJson(artifact.data || null));
    const candidates = Array.isArray(root?.candidates) ? root.candidates : [];
    for (const candidate of candidates) {
      const record = asRecord(candidate);
      if (!record) continue;
      const metric = String(record.metricName || (Array.isArray(record.metricNames) ? record.metricNames[0] : '') || '');
      const dimension = String(record.dimensionName || 'name');
      const data = Array.isArray(record.data) ? record.data : [];
      const points = data
        .map(item => asRecord(item))
        .filter((item): item is Record<string, unknown> => item != null)
        .map(item => {
          const label = String(item[dimension] ?? item.name ?? item.label ?? '');
          const raw = item[metric] ?? item.value;
          const numeric = typeof raw === 'number' ? raw : Number(raw);
          return { label: label || '-', value: raw == null ? '-' : String(raw), numeric: Number.isFinite(numeric) ? numeric : 0 };
        });
      const max = Math.max(...points.map(item => item.numeric), 1);
      items.push({
        title: record.title ? String(record.title) : undefined,
        chartType: record.chartType ? String(record.chartType) : undefined,
        note: record.note ? String(record.note) : undefined,
        bars: points.map(item => ({
          label: item.label,
          value: item.value,
          percent: Math.max(4, Math.round((item.numeric / max) * 100))
        }))
      });
    }
  }
  return items;
});

const analysisReport = computed(() => {
  for (const artifact of props.artifacts || []) {
    if (artifact.schemaVersion !== ANALYSIS_REPORT_SCHEMA) continue;
    const root = asRecord(parseJson(artifact.data || null));
    const markdown = root?.markdown;
    if (typeof markdown === 'string' && markdown.trim()) {
      return markdown;
    }
  }
  return '';
});

const chartTypeLabel = (type?: string) => {
  if (type === 'line') return '趋势图';
  if (type === 'pie') return '占比图';
  if (type === 'bar-multi') return '对比柱图';
  if (type === 'bar') return '柱状图';
  return type || '图表';
};
</script>

<style scoped>
.block {
  margin-top: 16px;
}

.block header {
  margin-bottom: 8px;
  font-weight: 600;
}

.summary {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.answer {
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  max-height: 280px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
}

.bars {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bar-row {
  display: grid;
  grid-template-columns: 96px 1fr 64px;
  gap: 8px;
  align-items: center;
}

.bar-label,
.bar-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.bar-value {
  text-align: right;
}

.bar-track {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--el-fill-color);
}

.bar-fill {
  height: 100%;
  border-radius: 999px;
  background: var(--el-color-primary);
}
</style>
