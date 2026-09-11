<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->

<script setup lang="ts">
  import { ref, computed, nextTick } from 'vue';
  import ChartComponent from './ChartComponent.vue';
  import { formatResultCell, type ResultData } from '@/views/ai-agent/services/resultSet';
  import {
    Grid as ICON_TABLE,
    Histogram as ICON_CHART,
    CopyDocument as ICON_COPY,
  } from '@element-plus/icons-vue';
  import { ElMessage } from 'element-plus';

  const props = defineProps<{
    resultData: ResultData;
    pageSize: number;
    pageSizeOptions?: number[];
  }>();
  const emit = defineEmits<{
    (event: 'update:pageSize', value: number): void;
  }>();

  const isChartView = ref(true);
  const defaultPageSizeOptions = [5, 10, 20, 50, 100];
  const resolvedPageSizeOptions = computed(() => {
    return props.pageSizeOptions?.length ? props.pageSizeOptions : defaultPageSizeOptions;
  });
  const selectedPageSize = computed({
    get: () => props.pageSize,
    set: (value: number) => {
      emit('update:pageSize', Number(value));
    },
  });
  const effectivePageSize = computed(() => Math.max(1, Number(props.pageSize) || 20));

  // 判断是否显示图表
  const showChart = computed(() => {
    return (
      props.resultData &&
      props.resultData.displayStyle?.type &&
      props.resultData.displayStyle?.type !== 'table'
    );
  });

  // 生成表格HTML
  const generateTableHtml = (): string => {
    const resultSet = props.resultData?.resultSet || {};
    const columns = resultSet.column || [];
    const allData = resultSet.data || [];
    const total = allData.length;
    const pageSize = effectivePageSize.value;

    // 分页逻辑
    const totalPages = Math.ceil(total / pageSize);

    let tableHtml = `<div class="result-set-container"><div class="result-set-header"><div class="result-set-info"><span>查询结果 (共 ${total} 条记录)</span><div class="result-set-pagination-controls"><span class="result-set-pagination-info">第 <span class="result-set-current-page">1</span> 页，共 ${totalPages} 页</span><div class="result-set-pagination-buttons"><ElButton class="result-set-pagination-btn result-set-pagination-prev" onclick="handleResultSetPagination(this, 'prev')" disabled>上一页</ElButton><ElButton class="result-set-pagination-btn result-set-pagination-next" onclick="handleResultSetPagination(this, 'next')" ${totalPages > 1 ? '' : 'disabled'}>下一页</ElButton></div></div></div></div><div class="result-set-table-container">`;

    // 生成所有页面的表格
    for (let page = 1; page <= totalPages; page++) {
      const startIndex = (page - 1) * pageSize;
      const endIndex = Math.min(startIndex + pageSize, total);
      const currentPageData = allData.slice(startIndex, endIndex);

      tableHtml += `<div class="result-set-page ${page === 1 ? 'result-set-page-active' : ''}" data-page="${page}"><table class="result-set-table"><thead><tr>`;

      // 添加表头
      columns.forEach(column => {
        tableHtml += `<th>${escapeHtml(column)}</th>`;
      });

      tableHtml += `</tr></thead><tbody>`;

      // 添加表格数据
      if (currentPageData.length === 0) {
        tableHtml += `<tr><td colspan="${columns.length}" class="result-set-empty-cell">暂无数据</td></tr>`;
      } else {
        currentPageData.forEach(row => {
          tableHtml += `<tr>`;
          columns.forEach(column => {
            const value = formatResultCell(row[column]);
            tableHtml += `<td>${escapeHtml(value)}</td>`;
          });
          tableHtml += `</tr>`;
        });
      }

      tableHtml += `</tbody></table></div>`;
    }

    tableHtml += `</div></div>`;

    return tableHtml;
  };

  // HTML转义函数
  const escapeHtml = (text: string): string => {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
  };

  // 切换到图表视图
  const switchToChart = () => {
    isChartView.value = true;
  };

  // 切换到表格视图
  const switchToTable = () => {
    isChartView.value = false;
  };

  // 复制JSON数据到剪贴板
  const copyJsonData = () => {
    try {
      const data = props.resultData?.resultSet?.data || [];
      const jsonData = JSON.stringify(data, null, 2);
      navigator.clipboard
        .writeText(jsonData)
        .then(() => {
          ElMessage.success('数据已复制到剪贴板');
        })
        .catch(err => {
          console.error('复制失败:', err);
          ElMessage.error('复制失败');
        });
    } catch (err) {
      console.error('JSON转换失败:', err);
      ElMessage.error('复制失败');
    }
  };

  // 组件挂载时初始化
  nextTick(() => {
    // 默认显示图表
    isChartView.value = showChart.value;
  });
</script>

<template>
  <div
    v-if="resultData && resultData.resultSet && resultData.resultSet.errorMsg"
    class="result-set-error"
  >
    错误: {{ resultData.resultSet.errorMsg }}
  </div>
  <div
    v-else-if="
      !resultData ||
      !resultData.resultSet ||
      !resultData.resultSet.column ||
      resultData.resultSet.column.length === 0 ||
      !resultData.resultSet.data ||
      resultData.resultSet.data.length === 0
    "
    class="result-set-empty"
  >
    查询结果为空
  </div>
  <div v-else class="agent-response-block">
    <!-- 头部区域 -->
    <div class="agent-response-title result-set-header-bar">
      <div class="agent-response-title">
        {{ resultData.displayStyle?.title || '查询结果' }}
      </div>
      <div class="buttons-bar">
        <div class="result-set-page-size-control">
          <span>每页</span>
          <el-select v-model="selectedPageSize" size="small" style="width: 82px">
            <el-option
              v-for="option in resolvedPageSizeOptions"
              :key="option"
              :label="String(option)"
              :value="option"
            />
          </el-select>
        </div>
        <div v-if="showChart" class="chart-select-container">
          <el-tooltip effect="dark" content="图表" placement="top">
            <el-button
              class="tool-btn"
              :class="{ 'view-active': isChartView }"
              text
              @click="switchToChart"
            >
              <el-icon size="16">
                <ICON_CHART />
              </el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip effect="dark" content="表格" placement="top">
            <el-button
              class="tool-btn"
              :class="{ 'view-active': !isChartView }"
              text
              @click="switchToTable"
            >
              <el-icon size="16">
                <ICON_TABLE />
              </el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip effect="dark" content="复制JSON" placement="top">
            <el-button class="tool-btn" text @click="copyJsonData">
              <el-icon size="16">
                <ICON_COPY />
              </el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
    </div>

    <!-- 显示区域 -->
    <div class="result-show-area">
      <ChartComponent v-if="isChartView && showChart" :resultData="resultData" />
      <div v-else v-html="generateTableHtml()"></div>
    </div>
  </div>
</template>

<style scoped>
  .result-set-error {
    padding: 12px;
    background-color: #fef0f0;
    border: 1px solid #fbc4c4;
    border-radius: 4px;
    color: #f56c6c;
    margin: 8px 0;
  }

  .result-set-empty {
    padding: 12px;
    background-color: #f4f4f5;
    border: 1px solid #e9e9eb;
    border-radius: 4px;
    color: #909399;
    margin: 8px 0;
  }

  /* 头部样式 */
  .result-set-header-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
    /* margin-left: 15px; */
    margin-bottom: 12px;
    padding: 8px 0;
    border-bottom: 1px solid #ebeef5;
  }

  .buttons-bar {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    flex-wrap: wrap;
  }

  .result-set-page-size-control {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    color: #606266;
    font-size: 13px;
    white-space: nowrap;
  }

  .chart-select-container {
    display: flex;
    align-items: center;
  }

  .tool-btn {
    padding: 4px 8px;
    margin-left: 4px;
    border-radius: 4px;
  }

  .tool-btn:hover {
    background-color: #f5f7fa;
  }

  .view-active {
    background-color: #edf8ef;
    color: #167243;
  }

  /* 显示区域样式 */
  .result-show-area {
    width: 100%;
    min-height: 300px;
  }
</style>
