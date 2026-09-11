<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->
<template>
  <BaseLayout class="diagnostics-shell">
    <main class="diagnostics-page">
      <section class="diagnostics-header">
        <div>
          <h1>会话排障</h1>
          <p>按问答轮次查看思考过程、数据来源与调用链路</p>
        </div>
        <div class="diagnostics-stats">
          <div>
            <span>轮次</span>
            <strong>{{ pageTotal }}</strong>
          </div>
          <div>
            <span>用户</span>
            <strong>{{ userSummaryStats.userCount }}</strong>
          </div>
          <div>
            <span>失败</span>
            <strong>{{ userSummaryStats.failedTurnCount }}</strong>
          </div>
          <div>
            <span>平均耗时</span>
            <strong>{{ formatDuration(userSummaryStats.averageDurationMs) }}</strong>
          </div>
        </div>
      </section>

      <section class="diagnostics-filters">
        <ElForm :model="query" label-width="80px">
          <ElRow :gutter="24">
            <ElCol :span="6">
              <ElFormItem label="关键词">
                <ElInput
                  v-model="query.keyword"
                  clearable
                  placeholder="问题 / 回答 / 用户 / 请求"
                  :prefix-icon="Search"
                />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="智能体">
                <ElSelect v-model="query.agentId" clearable filterable placeholder="选择智能体" :loading="agentLoading">
                  <ElOption
                    v-for="item in agentOptions"
                    :key="String(item.id)"
                    :label="agentLabel(item)"
                    :value="item.id || ''"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="用户">
                <ElSelect v-model="query.userId" clearable filterable placeholder="选择用户" :loading="userLoading">
                  <ElOption
                    v-for="item in userOptions"
                    :key="String(resolveUserId(item))"
                    :label="userLabel(item)"
                    :value="resolveUserId(item)"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="会话ID">
                <ElInput v-model="query.sessionId" clearable placeholder="会话ID" />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="请求ID">
                <ElInput v-model="query.runtimeRequestId" clearable placeholder="请求ID" />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="状态">
                <ElSelect v-model="query.status" clearable placeholder="全部">
                  <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="数据来源">
                <ElSelect v-model="query.hasDatasource" clearable placeholder="全部">
                  <ElOption label="有来源" :value="true" />
                  <ElOption label="无来源" :value="false" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="SQL">
                <ElSelect v-model="query.hasSql" clearable placeholder="全部">
                  <ElOption label="有 SQL" :value="true" />
                  <ElOption label="无 SQL" :value="false" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="工具调用">
                <ElSelect v-model="query.hasToolCall" clearable placeholder="全部">
                  <ElOption label="有调用" :value="true" />
                  <ElOption label="无调用" :value="false" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="时间范围">
                <ElDatePicker
                  v-model="timeRange"
                  type="datetimerange"
                  start-placeholder="开始时间"
                  end-placeholder="结束时间"
                  value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
                  clearable
                />
              </ElFormItem>
            </ElCol>
            <ElButton type="primary" :loading="loading" @click="handleSearch">
              <ElIcon><Search /></ElIcon>
              查询
            </ElButton>
            <ElButton @click="handleReset">
              <ElIcon><Refresh /></ElIcon>
              重置
            </ElButton>
          </ElRow>
        </ElForm>
      </section>

      <section class="diagnostics-table-panel">
        <div class="diagnostics-table-toolbar">
          <div>
            <strong>问答轮次</strong>
            <span>{{ pageTotal }} 条</span>
          </div>
          <ElButton :loading="loading" @click="loadTurns">
            <ElIcon><Refresh /></ElIcon>
            刷新
          </ElButton>
        </div>

        <div class="diagnostics-table-wrap">
          <ElTable
            v-loading="loading"
            :data="turns"
            row-key="id"
            border
            stripe
            height="100%"
            empty-text="暂无轮次记录"
            @row-dblclick="openDetail"
          >
            <ElTableColumn label="时间" width="168" fixed="left">
              <template #default="{ row }">
                <div class="cell-strong">{{ formatDateTime(row.startedAt || row.createTime) }}</div>
                <span class="cell-muted">{{ formatDuration(row.durationMs) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="104">
              <template #default="{ row }">
                <ElTag :type="statusTagType(row.status)" effect="light" size="small">
                  {{ statusLabel(row.status) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="用户" width="160" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ turnUserLabel(row) }}</div>
                <span v-if="row.userId" class="cell-muted">ID: {{ row.userId }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="智能体" width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ turnAgentLabel(row) }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="会话 / 请求" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="mono-line">S: {{ row.sessionId || '-' }}</div>
                <div class="mono-line">R: {{ row.runtimeRequestId || '-' }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="question" label="问题" min-width="260" show-overflow-tooltip />
            <ElTableColumn label="回答" min-width="280">
              <template #default="{ row }">
                <ElTooltip
                  placement="top"
                  popper-class="diagnostics-answer-tooltip"
                  :disabled="!answerOverflowMap[answerKey(row)]"
                >
                  <template #content>
                    <div class="diagnostics-answer-tooltip-content">{{ answerText(row) }}</div>
                  </template>
                  <span
                    class="diagnostics-answer-cell"
                    @mouseenter="updateAnswerOverflow(row, $event)"
                    @focus="updateAnswerOverflow(row, $event)"
                  >
                    {{ answerText(row) }}
                  </span>
                </ElTooltip>
              </template>
            </ElTableColumn>
            <ElTableColumn label="诊断" width="190">
              <template #default="{ row }">
                <div class="diagnostics-tags">
                  <ElTag v-if="row.hasDatasource" type="success" size="small" effect="plain">来源</ElTag>
                  <ElTag v-if="row.hasSql" type="primary" size="small" effect="plain">SQL</ElTag>
                  <ElTag v-if="Number(row.toolCount || 0) > 0" type="warning" size="small" effect="plain">
                    工具 {{ row.toolCount }}
                  </ElTag>
                  <ElTag v-if="Number(row.toolFailCount || 0) > 0" type="danger" size="small" effect="plain">
                    失败 {{ row.toolFailCount }}
                  </ElTag>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="96" fixed="right" align="center">
              <template #default="{ row }">
                <ElTooltip content="查看详情" placement="top">
                  <ElButton type="primary" text aria-label="查看详情" @click="openDetail(row)">
                    <ElIcon><View /></ElIcon>
                  </ElButton>
                </ElTooltip>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>

        <div class="diagnostics-pagination">
          <ElPagination
            v-model:current-page="query.current"
            v-model:page-size="query.size"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="pageTotal"
            @size-change="handleSizeChange"
            @current-change="loadTurns"
          />
        </div>
      </section>

      <ElDrawer v-model="detailVisible" size="72%" class="diagnostics-detail-drawer" destroy-on-close>
        <template #header>
          <div class="drawer-title">
            <span>轮次详情</span>
            <small>{{ selectedTurn?.runtimeRequestId || selectedDetail?.turn?.runtimeRequestId || '-' }}</small>
          </div>
        </template>

        <ElSkeleton v-if="detailLoading" animated :rows="12" />
        <ElAlert v-else-if="detailError" :title="detailError" type="warning" :closable="false" show-icon />
        <div v-else-if="selectedDetail" class="diagnostics-detail">
          <section class="detail-section qa-section">
            <div class="detail-section-title">
              <ElIcon><Tickets /></ElIcon>
              <span>问答</span>
            </div>
            <div class="qa-block question">
              <span>Q</span>
              <p>{{ selectedDetail.turn?.question || selectedTurn?.question || '-' }}</p>
            </div>
            <div class="qa-block answer">
              <span>A</span>
              <p>
                {{ selectedDetail.turn?.answer || selectedTurn?.answer || selectedDetail.turn?.errorMessage || '-' }}
              </p>
            </div>
            <div class="detail-meta-grid">
              <div>
                <label>会话</label>
                <strong>{{ selectedDetail.turn?.sessionId || selectedDetail.session?.id || '-' }}</strong>
              </div>
              <div>
                <label>Agent</label>
                <strong>{{ detailAgentLabel }}</strong>
              </div>
              <div>
                <label>用户</label>
                <strong>{{ detailUserLabel }}</strong>
              </div>
              <div>
                <label>耗时</label>
                <strong>{{ formatDuration(selectedDetail.turn?.durationMs) }}</strong>
              </div>
            </div>
          </section>

          <ElTabs v-model="activeDetailTab" class="diagnostics-tabs">
            <ElTabPane label="思考过程" name="thinking">
              <ElAlert
                v-if="selectedDetail.canViewThinking === false"
                title="无思考查看权限"
                type="warning"
                :closable="false"
                show-icon
              />
              <div v-else-if="thinkingMessages.length" class="detail-grid">
                <section v-for="message in thinkingMessages" :key="messageKey(message)" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><Operation /></ElIcon>
                    <span>可审计思考快照</span>
                    <small>{{ formatDateTime(message.createTime) }}</small>
                  </div>
                  <div class="thinking-html" v-html="sanitizeHtml(message.content || '')"></div>
                </section>
                <section v-if="clarifyEntries.length || selectedExplain?.warnings?.length" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><DocumentIcon /></ElIcon>
                    <span>过程摘要</span>
                  </div>
                  <ElDescriptions :column="1" border size="small">
                    <ElDescriptionsItem v-for="item in clarifyEntries" :key="item.key" :label="item.label">
                      {{ item.value }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem v-if="selectedExplain?.warnings?.length" label="Warnings">
                      {{ selectedExplain.warnings.join('；') }}
                    </ElDescriptionsItem>
                  </ElDescriptions>
                </section>
              </div>
              <ElEmpty v-else description="暂无思考数据" />
            </ElTabPane>

            <ElTabPane label="数据来源" name="answer-source">
              <ElAlert
                v-if="selectedDetail.canViewAnswerSource === false"
                title="无数据来源查看权限"
                type="warning"
                :closable="false"
                show-icon
              />
              <div v-else-if="selectedExplain" class="detail-grid">
                <section class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><DataAnalysis /></ElIcon>
                    <span>来源与 SQL</span>
                  </div>
                  <ElDescriptions :column="2" border size="small">
                    <ElDescriptionsItem label="数据源">{{ selectedExplain.datasource || '-' }}</ElDescriptionsItem>
                    <ElDescriptionsItem label="更新时间">
                      {{ formatTraceTime(selectedExplain.updatedAt) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="使用表">
                      <div class="tag-list">
                        <ElTag
                          v-for="table in selectedExplain.usedTables || []"
                          :key="table"
                          size="small"
                          effect="plain"
                        >
                          {{ table }}
                        </ElTag>
                        <span v-if="!selectedExplain.usedTables?.length" class="cell-muted">-</span>
                      </div>
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="使用字段">
                      <div class="tag-list">
                        <ElTag
                          v-for="column in selectedExplain.usedColumns || []"
                          :key="column"
                          size="small"
                          effect="plain"
                        >
                          {{ column }}
                        </ElTag>
                        <span v-if="!selectedExplain.usedColumns?.length" class="cell-muted">-</span>
                      </div>
                    </ElDescriptionsItem>
                  </ElDescriptions>
                  <pre v-if="selectedExplain.sql" class="code-block language-sql">{{ selectedExplain.sql }}</pre>
                  <ElEmpty v-else class="compact-empty" description="暂无 SQL" />
                </section>

                <section v-if="selectedExplain.decisionReason || selectedExplain.resultScope" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><Operation /></ElIcon>
                    <span>执行解释</span>
                  </div>
                  <ElDescriptions :column="1" border size="small">
                    <ElDescriptionsItem v-if="selectedExplain.decisionReason" label="决策原因">
                      {{ selectedExplain.decisionReason }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem v-if="selectedExplain.resultScope" label="结果范围">
                      {{ selectedExplain.resultScope }}
                    </ElDescriptionsItem>
                  </ElDescriptions>
                </section>

                <section v-if="selectedExplain.semanticHits?.length" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><DocumentIcon /></ElIcon>
                    <span>语义命中</span>
                  </div>
                  <ElTable :data="selectedExplain.semanticHits" border size="small" max-height="260">
                    <ElTableColumn prop="tableName" label="表" min-width="130" show-overflow-tooltip />
                    <ElTableColumn prop="columnName" label="字段" min-width="130" show-overflow-tooltip />
                    <ElTableColumn prop="businessName" label="业务名" min-width="160" show-overflow-tooltip />
                    <ElTableColumn prop="matchedBy" label="匹配方式" width="110" />
                    <ElTableColumn prop="score" label="得分" width="88" />
                    <ElTableColumn prop="relationHint" label="关系提示" min-width="180" show-overflow-tooltip />
                  </ElTable>
                </section>

                <section v-if="selectedExplain.knowledgeHits?.length" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><Files /></ElIcon>
                    <span>知识命中</span>
                  </div>
                  <ElTable :data="selectedExplain.knowledgeHits" border size="small" max-height="260">
                    <ElTableColumn prop="title" label="标题" min-width="180" show-overflow-tooltip />
                    <ElTableColumn prop="vectorType" label="类型" width="120" />
                    <ElTableColumn prop="source" label="来源" min-width="150" show-overflow-tooltip />
                    <ElTableColumn prop="snippet" label="片段" min-width="260" show-overflow-tooltip />
                  </ElTable>
                </section>

                <section v-if="relationEvidence.length" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><LinkIcon /></ElIcon>
                    <span>关系证据</span>
                  </div>
                  <ElCollapse>
                    <ElCollapseItem
                      v-for="(item, index) in relationEvidence"
                      :key="`relation-${index}`"
                      :title="`证据 ${index + 1}`"
                    >
                      <pre class="code-block">{{ formatJson(item) }}</pre>
                    </ElCollapseItem>
                  </ElCollapse>
                </section>
              </div>
              <ElEmpty v-else description="暂无数据来源" />
            </ElTabPane>

            <ElTabPane label="调用链路" name="call-chain">
              <ElAlert
                v-if="selectedDetail.canViewCallChain === false"
                title="无调用链路查看权限"
                type="warning"
                :closable="false"
                show-icon
              />
              <div v-else-if="selectedCallChain || detailToolSteps.length" class="detail-grid">
                <section v-if="detailToolSteps.length" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><Share /></ElIcon>
                    <span>工具步骤</span>
                  </div>
                  <ElTable :data="detailToolSteps" border stripe size="small" max-height="300">
                    <ElTableColumn label="#" width="64">
                      <template #default="{ row, $index }">{{ row.sequenceNo || $index + 1 }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="工具" min-width="180" show-overflow-tooltip>
                      <template #default="{ row }">
                        <div class="cell-strong">{{ row.title || row.toolName || '-' }}</div>
                        <span class="cell-muted">{{ row.stepType || '-' }}</span>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn label="状态" width="104">
                      <template #default="{ row }">
                        <ElTag :type="toolStatusType(row.status)" size="small" effect="light">
                          {{ row.status || 'unknown' }}
                        </ElTag>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn label="耗时" width="104">
                      <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
                    </ElTableColumn>
                    <ElTableColumn prop="summary" label="摘要" min-width="220" show-overflow-tooltip />
                    <ElTableColumn prop="inputSummary" label="输入" min-width="220" show-overflow-tooltip />
                    <ElTableColumn prop="outputSummary" label="输出" min-width="220" show-overflow-tooltip />
                    <ElTableColumn prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
                  </ElTable>
                </section>

                <section v-if="selectedCallChain?.orchestration?.run" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><Connection /></ElIcon>
                    <span>编排运行</span>
                  </div>
                  <ElDescriptions :column="3" border size="small">
                    <ElDescriptionsItem label="Run ID">
                      {{ selectedCallChain.orchestration.run.id || '-' }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="状态">
                      {{ selectedCallChain.orchestration.run.status || '-' }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="总耗时">
                      {{ formatDuration(selectedCallChain.orchestration.run.totalMs) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="路由耗时">
                      {{ formatDuration(selectedCallChain.orchestration.run.routeMs) }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="协作者">
                      {{ selectedCallChain.orchestration.run.collaboratorCount ?? '-' }}
                    </ElDescriptionsItem>
                    <ElDescriptionsItem label="错误">
                      {{ selectedCallChain.orchestration.run.errorMessage || '-' }}
                    </ElDescriptionsItem>
                  </ElDescriptions>
                </section>

                <section v-if="selectedCallChain?.orchestration?.steps?.length" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><Share /></ElIcon>
                    <span>协作步骤</span>
                  </div>
                  <ElTable :data="selectedCallChain.orchestration.steps" border size="small" max-height="280">
                    <ElTableColumn prop="stepNo" label="#" width="64" />
                    <ElTableColumn prop="collaboratorAgentId" label="协作者" width="120" />
                    <ElTableColumn prop="task" label="任务" min-width="220" show-overflow-tooltip />
                    <ElTableColumn prop="reason" label="原因" min-width="220" show-overflow-tooltip />
                    <ElTableColumn prop="status" label="状态" width="104" />
                    <ElTableColumn label="耗时" width="104">
                      <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
                    </ElTableColumn>
                    <ElTableColumn prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
                  </ElTable>
                </section>

                <section v-if="flattenedSpans.length" class="detail-section">
                  <div class="detail-section-title">
                    <ElIcon><Histogram /></ElIcon>
                    <span>Trace Span</span>
                  </div>
                  <ElTable :data="flattenedSpans" border size="small" max-height="320">
                    <ElTableColumn label="Span" min-width="260" show-overflow-tooltip>
                      <template #default="{ row }">
                        <div class="trace-span-name" :style="{ paddingLeft: `${row.depth * 16}px` }">
                          {{ row.name }}
                        </div>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn prop="kind" label="Kind" width="108" />
                    <ElTableColumn prop="status" label="状态" width="108" />
                    <ElTableColumn label="耗时" width="104">
                      <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="属性" min-width="300" show-overflow-tooltip>
                      <template #default="{ row }">{{ formatAttributes(row.attributes) }}</template>
                    </ElTableColumn>
                  </ElTable>
                </section>
              </div>
              <ElEmpty v-else description="暂无调用链路" />
            </ElTabPane>

            <ElTabPane label="会话消息" name="messages">
              <div v-if="selectedMessages.length" class="message-timeline">
                <div
                  v-for="message in selectedMessages"
                  :key="message.id || `${message.role}-${message.createTime}`"
                  class="message-item"
                >
                  <ElTag :type="message.role === 'user' ? 'primary' : 'success'" size="small" effect="light">
                    {{ message.role }}
                  </ElTag>
                  <div class="message-content">
                    <p>{{ message.content || '-' }}</p>
                    <span>{{ formatDateTime(message.createTime) }}</span>
                  </div>
                </div>
              </div>
              <ElEmpty v-else description="暂无会话消息" />
            </ElTabPane>

            <ElTabPane label="同会话轮次" name="session-turns">
              <ElTable v-if="sessionTurns.length" :data="sessionTurns" border stripe size="small" max-height="360">
                <ElTableColumn label="时间" width="160">
                  <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
                </ElTableColumn>
                <ElTableColumn label="状态" width="104">
                  <template #default="{ row }">
                    <ElTag :type="statusTagType(row.status)" effect="light" size="small">
                      {{ statusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn prop="runtimeRequestId" label="Request" min-width="220" show-overflow-tooltip />
                <ElTableColumn prop="question" label="问题" min-width="260" show-overflow-tooltip />
                <ElTableColumn label="操作" width="88" align="center">
                  <template #default="{ row }">
                    <ElButton text type="primary" @click="openDetail(row)">详情</ElButton>
                  </template>
                </ElTableColumn>
              </ElTable>
              <ElEmpty v-else description="暂无同会话轮次" />
            </ElTabPane>
          </ElTabs>
        </div>
      </ElDrawer>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import DOMPurify from 'dompurify';
import {
  Connection,
  DataAnalysis,
  Document as DocumentIcon,
  Files,
  Histogram,
  Link as LinkIcon,
  Operation,
  Refresh,
  Search,
  Share,
  Tickets,
  View
} from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import AgentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import diagnosticsService from '@/views/ai-agent/services/diagnostics';
import type {
  BasicUser,
  DataChatTurn,
  DataChatTurnDetail,
  DataChatTurnPageQuery,
  DataChatUserSummary
} from '@/views/ai-agent/services/diagnostics';
import type { AnswerTraceExplain, AnswerTraceToolStep, ChatMessage, TraceSpan } from '@/views/ai-agent/services/chat';

defineOptions({ name: 'DataAgentDiagnostics' });

interface FlatTraceSpan extends TraceSpan {
  depth: number;
}

interface ClarifyEntry {
  key: string;
  label: string;
  value: string;
}

const defaultQuery = (): DataChatTurnPageQuery => ({
  current: 1,
  size: 20,
  agentId: '',
  userId: '',
  sessionId: '',
  runtimeRequestId: '',
  keyword: '',
  status: '',
  hasDatasource: undefined,
  hasSql: undefined,
  hasToolCall: undefined
});

const statusOptions = [
  { label: '运行中', value: 'running' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' },
  { label: '已取消', value: 'cancelled' },
  { label: '澄清', value: 'clarify' }
];

const answerText = (row: DataChatTurn) => row.answer || row.errorMessage || '-';
const answerOverflowMap = reactive<Record<string, boolean>>({});
const answerKey = (row: DataChatTurn) =>
  row.id || row.runtimeRequestId || `${row.sessionId || ''}-${row.createTime || ''}`;
const updateAnswerOverflow = (row: DataChatTurn, event: Event) => {
  const target = event.currentTarget;
  if (!(target instanceof HTMLElement)) return;
  answerOverflowMap[answerKey(row)] = target.scrollWidth > target.clientWidth + 1;
};

const query = reactive<DataChatTurnPageQuery>(defaultQuery());
const route = useRoute();
const autoOpenedRuntimeRequestId = ref('');
const timeRange = ref<[string, string] | []>([]);
const loading = ref(false);
const agentLoading = ref(false);
const userLoading = ref(false);
const turns = ref<DataChatTurn[]>([]);
const pageTotal = ref(0);
const userSummaries = ref<DataChatUserSummary[]>([]);
const agentOptions = ref<Agent[]>([]);
const userOptions = ref<BasicUser[]>([]);

const detailVisible = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const selectedTurn = ref<DataChatTurn | null>(null);
const selectedDetail = ref<DataChatTurnDetail | null>(null);
const sessionTurns = ref<DataChatTurn[]>([]);
const activeDetailTab = ref('thinking');

const selectedExplain = computed<AnswerTraceExplain | null>(() => {
  return selectedDetail.value?.answerExplain ?? selectedDetail.value?.callChain?.answerExplain ?? null;
});
const selectedCallChain = computed(() => selectedDetail.value?.callChain ?? null);
const thinkingMessages = computed<ChatMessage[]>(() => selectedDetail.value?.thinkingMessages ?? []);
const selectedMessages = computed<ChatMessage[]>(() => selectedDetail.value?.messages ?? []);
const detailToolSteps = computed<AnswerTraceToolStep[]>(() => {
  const chainSteps = selectedCallChain.value?.toolSteps ?? [];
  const explainSteps = selectedExplain.value?.toolSteps ?? [];
  return orderToolSteps(chainSteps.length > 0 ? chainSteps : explainSteps);
});
const relationEvidence = computed<Record<string, unknown>[]>(() => {
  return (selectedExplain.value?.relationEvidence ?? []) as Record<string, unknown>[];
});
const clarifyEntries = computed<ClarifyEntry[]>(() => {
  const clarify = selectedExplain.value?.clarify ?? {};
  return Object.entries(clarify)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => ({ key, label: clarifyLabel(key), value: formatUnknown(value) }));
});
const flattenedSpans = computed<FlatTraceSpan[]>(() => {
  const trace = selectedCallChain.value?.trace;
  if (!trace) return [];
  const roots = trace.rootSpans?.length ? trace.rootSpans : trace.rootSpan ? [trace.rootSpan] : [];
  return flattenTraceSpans(roots);
});
const userSummaryStats = computed(() => {
  const turnCount = userSummaries.value.reduce((sum, item) => sum + Number(item.turnCount || 0), 0);
  const failedTurnCount = userSummaries.value.reduce((sum, item) => sum + Number(item.failedTurnCount || 0), 0);
  const weightedDuration = userSummaries.value.reduce((sum, item) => {
    return sum + Number(item.averageDurationMs || 0) * Number(item.turnCount || 0);
  }, 0);
  return {
    userCount: userSummaries.value.length,
    failedTurnCount,
    averageDurationMs: turnCount > 0 ? weightedDuration / turnCount : 0
  };
});
const detailAgentLabel = computed(() => {
  const id = selectedDetail.value?.turn?.agentId || selectedDetail.value?.session?.agentId || '';
  return resolveAgentName(id);
});
const detailUserLabel = computed(() => {
  const turn = selectedDetail.value?.turn;
  return turn?.createName || turn?.createBy || resolveUserName(turn?.userId) || '-';
});

const handleSearch = () => {
  query.current = 1;
  loadTurns();
};
const handleReset = () => {
  Object.assign(query, defaultQuery());
  timeRange.value = [];
  loadTurns();
};
const handleSizeChange = () => {
  query.current = 1;
  loadTurns();
};

const applyRouteQuery = () => {
  const sessionId = routeQueryText(route.query.sessionId);
  const runtimeRequestId = routeQueryText(route.query.runtimeRequestId);
  let changed = false;

  if (sessionId && query.sessionId !== sessionId) {
    query.sessionId = sessionId;
    changed = true;
  }
  if (runtimeRequestId && query.runtimeRequestId !== runtimeRequestId) {
    query.runtimeRequestId = runtimeRequestId;
    changed = true;
  }
  if (changed) {
    query.current = 1;
  }
  return changed;
};

const loadTurns = async () => {
  loading.value = true;
  try {
    const page = await diagnosticsService.queryTurns(buildQuery());
    turns.value = page.data;
    pageTotal.value = page.total;
    await loadUserSummaries();
    await openRouteMatchedTurn();
  } catch (error) {
    ElMessage.error(extractMessage(error, '加载会话排障列表失败'));
    turns.value = [];
    pageTotal.value = 0;
  } finally {
    loading.value = false;
  }
};

const openRouteMatchedTurn = async () => {
  const sessionId = routeQueryText(route.query.sessionId);
  const runtimeRequestId = routeQueryText(route.query.runtimeRequestId);
  if (!sessionId || !runtimeRequestId || autoOpenedRuntimeRequestId.value === runtimeRequestId) {
    return;
  }
  const matchedTurn = turns.value.find(
    turn => String(turn.sessionId || '') === sessionId && turn.runtimeRequestId === runtimeRequestId
  );
  if (!matchedTurn || turns.value.length !== 1) {
    return;
  }
  autoOpenedRuntimeRequestId.value = runtimeRequestId;
  await openDetail(matchedTurn);
};
const loadFilterOptions = async () => {
  agentLoading.value = true;
  userLoading.value = true;
  try {
    const [agents, users] = await Promise.all([AgentService.list(), diagnosticsService.listUsers()]);
    agentOptions.value = agents;
    userOptions.value = users;
  } catch (error) {
    ElMessage.warning(extractMessage(error, '加载筛选下拉失败'));
  } finally {
    agentLoading.value = false;
    userLoading.value = false;
  }
};
const loadUserSummaries = async () => {
  try {
    userSummaries.value = await diagnosticsService.summarizeUsers({
      agentId: query.agentId,
      startTime: timeRange.value[0],
      endTime: timeRange.value[1]
    });
  } catch {
    userSummaries.value = [];
  }
};
const openDetail = async (turn: DataChatTurn) => {
  if (!turn.sessionId || !turn.runtimeRequestId) {
    ElMessage.warning('该记录缺少 sessionId 或 runtimeRequestId');
    return;
  }
  selectedTurn.value = turn;
  selectedDetail.value = null;
  sessionTurns.value = [];
  detailError.value = '';
  detailVisible.value = true;
  detailLoading.value = true;
  activeDetailTab.value = 'thinking';
  try {
    const [detailResult, turnsResult] = await Promise.allSettled([
      diagnosticsService.getTurnDetail(turn.sessionId, turn.runtimeRequestId),
      diagnosticsService.listSessionTurns(turn.sessionId)
    ]);
    if (detailResult.status === 'fulfilled') {
      selectedDetail.value = detailResult.value;
    } else {
      detailError.value = extractMessage(detailResult.reason, '加载轮次详情失败');
    }
    if (turnsResult.status === 'fulfilled') {
      sessionTurns.value = turnsResult.value;
    }
  } finally {
    detailLoading.value = false;
  }
};
const buildQuery = (): DataChatTurnPageQuery => ({
  ...query,
  startTime: timeRange.value[0],
  endTime: timeRange.value[1]
});

const agentLabel = (agent: Agent) => agent.name || `Agent ${agent.id}`;
const resolveAgentName = (agentId?: string) => {
  const id = String(agentId ?? '');
  const agent = agentOptions.value.find(item => String(item.id ?? '') === id);
  return agent ? agentLabel(agent) : id || '-';
};
const turnAgentLabel = (turn: DataChatTurn) => resolveAgentName(turn.agentId);
const resolveUserId = (user: BasicUser) => user.id ?? user.userId ?? '';
const userLabel = (user: BasicUser) => {
  return (
    user.name ||
    user.realName ||
    user.nickName ||
    user.username ||
    user.userName ||
    user.account ||
    user.code ||
    String(resolveUserId(user))
  );
};
const resolveUserName = (userId?: string) => {
  const id = String(userId ?? '');
  const user = userOptions.value.find(item => String(resolveUserId(item)) === id);
  return user ? userLabel(user) : id;
};
const turnUserLabel = (turn: DataChatTurn) => turn.createName || turn.createBy || resolveUserName(turn.userId) || '-';
const messageKey = (message: ChatMessage) => {
  return String(
    message.id ?? message.createTime ?? `${message.role}-${message.messageType}-${message.content?.slice(0, 24)}`
  );
};

const statusLabel = (status?: string) => {
  const normalized = (status || '').toLowerCase();
  const labels: Record<string, string> = {
    running: '运行中',
    success: '成功',
    failed: '失败',
    cancelled: '已取消',
    canceled: '已取消',
    clarify: '澄清',
    clarifying: '澄清'
  };
  return labels[normalized] || status || '-';
};
const statusTagType = (status?: string) => {
  const normalized = (status || '').toLowerCase();
  if (normalized === 'success') return 'success';
  if (normalized === 'failed') return 'danger';
  if (normalized === 'running') return 'warning';
  if (normalized === 'cancelled' || normalized === 'canceled') return 'info';
  return 'primary';
};
const toolStatusType = (status?: string) => {
  const normalized = (status || '').toLowerCase();
  if (normalized === 'success' || normalized === 'ok') return 'success';
  if (normalized === 'failed' || normalized === 'error') return 'danger';
  if (normalized === 'running') return 'warning';
  return 'info';
};
const formatDateTime = (value?: string | number | Date) => {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString('zh-CN', { hour12: false });
};
const formatTraceTime = (value?: number | string) => {
  if (!value) return '-';
  const numeric = Number(value);
  return formatDateTime(Number.isFinite(numeric) ? numeric : value);
};
const formatDuration = (value?: number) => {
  const duration = Number(value || 0);
  if (!duration) return '-';
  if (duration < 1000) return `${Math.round(duration)}ms`;
  if (duration < 60000) return `${(duration / 1000).toFixed(1)}s`;
  return `${(duration / 60000).toFixed(1)}min`;
};
const orderToolSteps = (steps: AnswerTraceToolStep[]) => {
  return [...steps].sort((left, right) => {
    const leftOrder = left.sequenceNo ?? left.startEpochMs ?? left.timestampEpochMs ?? 0;
    const rightOrder = right.sequenceNo ?? right.startEpochMs ?? right.timestampEpochMs ?? 0;
    return leftOrder - rightOrder;
  });
};
const flattenTraceSpans = (spans: TraceSpan[], depth = 0): FlatTraceSpan[] => {
  return spans.flatMap(span => [{ ...span, depth }, ...flattenTraceSpans(span.children || [], depth + 1)]);
};
const clarifyLabel = (key: string) => {
  const labels: Record<string, string> = {
    riskLevel: '风险等级',
    clarifyRequired: '需要澄清',
    missingDimensions: '缺失维度',
    followUpQuestions: '追问问题',
    suggestedAssumptions: '建议假设',
    summary: '澄清摘要',
    userMessage: '用户提示',
    shouldBlockExecution: '阻断执行',
    humanFeedbackContent: '人工反馈'
  };
  return labels[key] || key;
};
const formatUnknown = (value: unknown): string => {
  if (Array.isArray(value)) return value.map(formatUnknown).join('；');
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (value && typeof value === 'object') return formatJson(value);
  return String(value);
};
const formatJson = (value: unknown) => {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
};
const sanitizeHtml = (value: string) => DOMPurify.sanitize(value);
const formatAttributes = (attributes?: Record<string, string>) => {
  if (!attributes) return '-';
  const entries = Object.entries(attributes);
  if (!entries.length) return '-';
  return entries
    .slice(0, 8)
    .map(([key, value]) => `${key}=${value}`)
    .join(' | ');
};
const extractMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (error && typeof error === 'object') {
    const message = (error as { message?: unknown }).message;
    if (typeof message === 'string' && message) return message;
  }
  return fallback;
};
const routeQueryText = (value: unknown) => {
  if (Array.isArray(value)) {
    return value[0] ? String(value[0]) : '';
  }
  return value ? String(value) : '';
};

watch(
  () => [route.query.sessionId, route.query.runtimeRequestId],
  () => {
    if (applyRouteQuery()) {
      loadTurns();
    }
  }
);

onMounted(async () => {
  applyRouteQuery();
  await loadFilterOptions();
  await loadTurns();
});
</script>

<style scoped>
.diagnostics-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.diagnostics-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
}

.diagnostics-header,
.diagnostics-filters,
.diagnostics-table-panel,
.detail-section {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.diagnostics-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
}

.diagnostics-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: 0;
}

.diagnostics-header p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.diagnostics-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(104px, 1fr));
  gap: 10px;
}

.diagnostics-stats > div {
  min-width: 104px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: color-mix(in srgb, var(--el-color-primary) 6%, var(--el-bg-color));
}

.diagnostics-stats span {
  display: block;
  margin-bottom: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.diagnostics-stats strong {
  color: var(--el-text-color-primary);
  font-size: 18px;
  line-height: 24px;
}

.diagnostics-filters,
.diagnostics-table-panel,
.detail-section {
  padding: 12px;
}

.diagnostics-table-panel {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.diagnostics-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.diagnostics-table-wrap :deep(.el-table) {
  height: 100%;
}

.diagnostics-filter-range {
  grid-column: span 2;
}

.diagnostics-filter-actions :deep(.el-form-item__content) {
  align-items: flex-end;
  gap: 8px;
}

.cell-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.diagnostics-table-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.diagnostics-table-toolbar > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.cell-strong,
.trace-span-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.mono-line {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 18px;
}

.diagnostics-tags,
.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.diagnostics-tags {
  align-items: center;
  justify-content: center;
}

.diagnostics-answer-cell {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.diagnostics-answer-tooltip) {
  max-width: min(860px, calc(100vw - 120px));
}

:global(.diagnostics-answer-tooltip .diagnostics-answer-tooltip-content) {
  max-height: 240px;
  padding-right: 6px;
  overflow-y: auto;
  overflow-x: hidden;
  color: var(--el-bg-color);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.28) transparent;
}

:global(html.dark .diagnostics-answer-tooltip .diagnostics-answer-tooltip-content) {
  scrollbar-color: rgba(0, 0, 0, 0.25) transparent;
}

:global(.diagnostics-answer-tooltip .diagnostics-answer-tooltip-content::-webkit-scrollbar) {
  width: 6px;
  height: 6px;
}

:global(.diagnostics-answer-tooltip .diagnostics-answer-tooltip-content::-webkit-scrollbar-track) {
  background: transparent;
}

:global(.diagnostics-answer-tooltip .diagnostics-answer-tooltip-content::-webkit-scrollbar-button) {
  display: none;
  width: 0;
  height: 0;
}

:global(.diagnostics-answer-tooltip .diagnostics-answer-tooltip-content::-webkit-scrollbar-thumb) {
  border: 2px solid transparent;
  border-radius: 999px;
  background-clip: content-box;
  background-color: rgba(255, 255, 255, 0.28);
}

:global(.diagnostics-answer-tooltip .diagnostics-answer-tooltip-content::-webkit-scrollbar-thumb:hover) {
  background-color: rgba(255, 255, 255, 0.28);
}

.diagnostics-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.drawer-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.drawer-title span {
  color: var(--el-text-color-primary);
  font-size: 16px;
  font-weight: 700;
}

.drawer-title small,
.detail-section-title small {
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;
}

.diagnostics-detail,
.detail-grid,
.message-timeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.detail-section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 10px;
  color: var(--el-text-color-primary);
  font-weight: 700;
}

.qa-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.qa-block {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
}

.qa-block > span {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: var(--el-color-white);
  font-weight: 700;
}

.qa-block.question > span {
  background: var(--el-color-primary);
}

.qa-block.answer > span {
  background: var(--el-color-success);
}

.qa-block p,
.message-content p {
  margin: 0;
  color: var(--el-text-color-primary);
  line-height: 22px;
  white-space: pre-wrap;
}

.detail-meta-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.detail-meta-grid > div {
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.detail-meta-grid label {
  display: block;
  margin-bottom: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.detail-meta-grid strong {
  color: var(--el-text-color-primary);
  font-size: 13px;
  word-break: break-all;
}

.code-block {
  max-height: 320px;
  margin: 10px 0 0;
  padding: 10px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #111827;
  color: #e5e7eb;
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  line-height: 18px;
  white-space: pre-wrap;
}

.thinking-html {
  overflow: auto;
}

.thinking-html :deep(.agent-thinking-block) {
  margin: 0;
}

.compact-empty {
  --el-empty-padding: 12px 0 0;
}

.message-item {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.message-content span {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 900px) {
  .diagnostics-header {
    align-items: stretch;
    flex-direction: column;
  }

  .diagnostics-stats,
  .detail-meta-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .diagnostics-filter-range {
    grid-column: span 1;
  }
}
</style>
