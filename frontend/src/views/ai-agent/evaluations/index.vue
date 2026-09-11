<template>
  <BaseLayout class="eval-shell">
    <main class="eval-page">
      <ElCard>
        <section class="eval-header">
          <div>
            <h1>智能体评估</h1>
          </div>
          <div class="eval-header-actions">
            <ElButton :loading="pageLoading" @click="refreshActiveTab">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
          </div>
        </section>

        <section class="eval-config-entry">
          <div class="config-entry-title">
            <span>评估配置</span>
            <ElTag effect="light" size="small">DATA_AGENT</ElTag>
            <ElTag effect="light" size="small">DIGITAL_EMPLOYEE_CANDIDATE</ElTag>
            <ElTag effect="light" size="small">DIGITAL_EMPLOYEE_RELEASE</ElTag>
          </div>
          <div class="config-entry-actions">
            <ElTooltip :disabled="canManageEvaluation" content="需要 agent:evaluation:manage 权限" placement="top">
              <span class="tooltip-button">
                <ElButton type="primary" plain :disabled="!canManageEvaluation" @click="openBootstrapDialog">
                  <ElIcon><MagicStick /></ElIcon>
                  初始化默认配置
                </ElButton>
              </span>
            </ElTooltip>
            <ElTooltip :disabled="canRunEvaluation" content="需要 agent:evaluation:run 权限" placement="top">
              <span class="tooltip-button">
                <ElButton type="success" :disabled="!canRunEvaluation" @click="openRunDialog()">
                  <ElIcon><VideoPlay /></ElIcon>
                  运行评估
                </ElButton>
              </span>
            </ElTooltip>
          </div>
        </section>
      </ElCard>

      <ElCard class="eval-list-card mt-8px">
        <ElTabs v-model="activeTab" class="eval-tabs">
          <ElTabPane label="评估策略" name="policies">
            <div class="panel-toolbar">
              <ElForm :model="policyQuery" inline>
                <ElFormItem label="策略名称">
                  <ElInput
                    v-model="policyQuery.policyName"
                    clearable
                    placeholder="策略名称"
                    @keyup.enter="loadPolicies"
                  />
                </ElFormItem>
                <ElFormItem label="对象类型">
                  <ElSelect v-model="policyQuery.subjectType" clearable placeholder="全部" class="w-220px">
                    <ElOption
                      v-for="item in subjectTypeOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="状态">
                  <ElSelect v-model="policyQuery.status" clearable placeholder="全部" class="w-180px">
                    <ElOption
                      v-for="item in enabledStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem>
                  <ElButton type="primary" :loading="policyLoading" @click="handlePolicySearch">
                    <ElIcon><Search /></ElIcon>
                    查询
                  </ElButton>
                  <ElButton @click="resetPolicyQuery">重置</ElButton>
                </ElFormItem>
              </ElForm>
              <ElButton v-if="canManageEvaluation" type="primary" @click="openPolicyDialog()">
                <ElIcon><Plus /></ElIcon>
                新增策略
              </ElButton>
            </div>

            <div class="eval-table-wrap">
              <ElTable
                v-loading="policyLoading"
                :data="policyRows"
                border
                stripe
                row-key="id"
                height="100%"
                empty-text="暂无评估策略"
              >
                <ElTableColumn prop="policyCode" label="编码" min-width="150" show-overflow-tooltip />
                <ElTableColumn prop="policyName" label="策略名称" min-width="180" show-overflow-tooltip />
                <ElTableColumn prop="subjectType" label="对象类型" width="220" />
                <ElTableColumn label="版本" width="90" align="center">
                  <template #default="{ row }">v{{ row.versionNo || 1 }}</template>
                </ElTableColumn>
                <ElTableColumn label="默认" width="86" align="center">
                  <template #default="{ row }">
                    <ElTag :type="row.defaultFlag ? 'success' : 'info'" effect="light" size="small">
                      {{ row.defaultFlag ? '是' : '否' }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="状态" width="100">
                  <template #default="{ row }">
                    <ElTag :type="enabledStatusTag(row.status)" effect="light" size="small">
                      {{ enabledStatusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn prop="description" label="说明" min-width="220" show-overflow-tooltip />
                <ElTableColumn label="操作" width="96" fixed="right" align="center">
                  <template #default="{ row }">
                    <ElTooltip content="编辑" placement="top">
                      <ElButton v-if="canManageEvaluation" text type="primary" @click="openPolicyDialog(row)">
                        <ElIcon><Edit /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                  </template>
                </ElTableColumn>
              </ElTable>
            </div>
            <div class="eval-pagination">
              <ElPagination
                v-model:current-page="policyPage.current"
                v-model:page-size="policyPage.size"
                background
                layout="total, sizes, prev, pager, next, jumper"
                :page-sizes="[10, 20, 50, 100]"
                :total="policyPage.total"
                @size-change="handlePolicySizeChange"
                @current-change="loadPolicies"
              />
            </div>
          </ElTabPane>

          <ElTabPane label="评估对象" name="subjects">
            <div class="panel-toolbar">
              <ElForm :model="subjectQuery" inline>
                <ElFormItem label="对象名称">
                  <ElInput
                    v-model="subjectQuery.subjectName"
                    clearable
                    placeholder="对象名称"
                    @keyup.enter="loadSubjects"
                  />
                </ElFormItem>
                <ElFormItem label="对象类型">
                  <ElSelect v-model="subjectQuery.subjectType" clearable placeholder="全部" class="w-220px">
                    <ElOption
                      v-for="item in subjectTypeOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="状态">
                  <ElSelect v-model="subjectQuery.status" clearable placeholder="全部" class="w-180px">
                    <ElOption
                      v-for="item in enabledStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem>
                  <ElButton type="primary" :loading="subjectLoading" @click="handleSubjectSearch">
                    <ElIcon><Search /></ElIcon>
                    查询
                  </ElButton>
                  <ElButton @click="resetSubjectQuery">重置</ElButton>
                </ElFormItem>
              </ElForm>
              <ElButton v-if="canManageEvaluation" type="primary" @click="openSubjectDialog()">
                <ElIcon><Plus /></ElIcon>
                新增对象
              </ElButton>
            </div>

            <div class="eval-table-wrap">
              <ElTable
                v-loading="subjectLoading"
                :data="subjectRows"
                border
                stripe
                row-key="id"
                height="100%"
                empty-text="暂无评估对象"
              >
                <ElTableColumn prop="subjectName" label="对象名称" min-width="180" show-overflow-tooltip />
                <ElTableColumn prop="subjectType" label="对象类型" width="220" />
                <ElTableColumn prop="subjectId" label="业务对象 ID" min-width="150" show-overflow-tooltip />
                <ElTableColumn prop="adapterCode" label="适配器" width="140" />
                <ElTableColumn label="状态" width="100">
                  <template #default="{ row }">
                    <ElTag :type="enabledStatusTag(row.status)" effect="light" size="small">
                      {{ enabledStatusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn prop="description" label="说明" min-width="220" show-overflow-tooltip />
                <ElTableColumn label="操作" width="96" fixed="right" align="center">
                  <template #default="{ row }">
                    <ElTooltip content="编辑" placement="top">
                      <ElButton v-if="canManageEvaluation" text type="primary" @click="openSubjectDialog(row)">
                        <ElIcon><Edit /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                  </template>
                </ElTableColumn>
              </ElTable>
            </div>
            <div class="eval-pagination">
              <ElPagination
                v-model:current-page="subjectPage.current"
                v-model:page-size="subjectPage.size"
                background
                layout="total, sizes, prev, pager, next, jumper"
                :page-sizes="[10, 20, 50, 100]"
                :total="subjectPage.total"
                @size-change="handleSubjectSizeChange"
                @current-change="loadSubjects"
              />
            </div>
          </ElTabPane>

          <ElTabPane label="评估集" name="suites">
            <div class="panel-toolbar">
              <ElForm :model="suiteQuery" inline>
                <ElFormItem label="评估集">
                  <ElInput
                    v-model="suiteQuery.suiteName"
                    clearable
                    placeholder="评估集名称"
                    @keyup.enter="loadSuites"
                  />
                </ElFormItem>
                <ElFormItem label="对象">
                  <ElSelect
                    v-model="suiteQuery.subjectId"
                    clearable
                    filterable
                    placeholder="全部对象"
                    class="w-180"
                    @visible-change="handleSubjectOptionsVisible"
                  >
                    <ElOption
                      v-for="item in subjectOptions"
                      :key="String(item.id)"
                      :label="subjectLabel(item)"
                      :value="item.id || ''"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="状态">
                  <ElSelect v-model="suiteQuery.status" clearable placeholder="全部" class="w-180px">
                    <ElOption
                      v-for="item in enabledStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem>
                  <ElButton type="primary" :loading="suiteLoading" @click="handleSuiteSearch">
                    <ElIcon><Search /></ElIcon>
                    查询
                  </ElButton>
                  <ElButton @click="resetSuiteQuery">重置</ElButton>
                </ElFormItem>
              </ElForm>
              <ElButton v-if="canManageEvaluation" type="primary" @click="openSuiteDialog()">
                <ElIcon><Plus /></ElIcon>
                新增评估集
              </ElButton>
            </div>

            <div class="eval-table-wrap">
              <ElTable
                v-loading="suiteLoading"
                :data="suiteRows"
                border
                stripe
                row-key="id"
                height="100%"
                empty-text="暂无评估集"
              >
                <ElTableColumn prop="suiteName" label="评估集名称" min-width="180" show-overflow-tooltip />
                <ElTableColumn label="对象" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ resolveSubjectName(row.subjectId) }}</template>
                </ElTableColumn>
                <ElTableColumn label="策略" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ resolvePolicyName(row.policyId) }}</template>
                </ElTableColumn>
                <ElTableColumn label="状态" width="100">
                  <template #default="{ row }">
                    <ElTag :type="enabledStatusTag(row.status)" effect="light" size="small">
                      {{ enabledStatusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn prop="description" label="说明" min-width="220" show-overflow-tooltip />
                <ElTableColumn label="操作" width="144" fixed="right" align="center">
                  <template #default="{ row }">
                    <ElTooltip content="运行" placement="top">
                      <ElButton v-if="canRunEvaluation" text type="success" @click="openRunDialog(row)">
                        <ElIcon><VideoPlay /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                    <ElTooltip content="编辑" placement="top">
                      <ElButton v-if="canManageEvaluation" text type="primary" @click="openSuiteDialog(row)">
                        <ElIcon><Edit /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                  </template>
                </ElTableColumn>
              </ElTable>
            </div>
            <div class="eval-pagination">
              <ElPagination
                v-model:current-page="suitePage.current"
                v-model:page-size="suitePage.size"
                background
                layout="total, sizes, prev, pager, next, jumper"
                :page-sizes="[10, 20, 50, 100]"
                :total="suitePage.total"
                @size-change="handleSuiteSizeChange"
                @current-change="loadSuites"
              />
            </div>
          </ElTabPane>

          <ElTabPane label="用例" name="cases">
            <div class="panel-toolbar">
              <ElForm :model="caseQuery" inline>
                <ElFormItem label="评估集">
                  <ElSelect
                    v-model="caseQuery.suiteId"
                    clearable
                    filterable
                    placeholder="全部评估集"
                    class="w-180"
                    @visible-change="handleSuiteOptionsVisible"
                  >
                    <ElOption
                      v-for="item in suiteOptions"
                      :key="String(item.id)"
                      :label="suiteLabel(item)"
                      :value="item.id || ''"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="关键词">
                  <ElInput
                    v-model="caseQuery.keyword"
                    clearable
                    placeholder="用例、输入或期望输出"
                    @keyup.enter="loadCases"
                  />
                </ElFormItem>
                <ElFormItem label="状态">
                  <ElSelect v-model="caseQuery.status" clearable placeholder="全部" class="w-180px">
                    <ElOption
                      v-for="item in enabledStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem>
                  <ElButton type="primary" :loading="caseLoading" @click="handleCaseSearch">
                    <ElIcon><Search /></ElIcon>
                    查询
                  </ElButton>
                  <ElButton @click="resetCaseQuery">重置</ElButton>
                </ElFormItem>
              </ElForm>
              <ElButton v-if="canManageEvaluation" type="primary" @click="openCaseDialog()">
                <ElIcon><Plus /></ElIcon>
                新增用例
              </ElButton>
            </div>

            <div class="eval-table-wrap">
              <ElTable
                v-loading="caseLoading"
                :data="caseRows"
                border
                stripe
                row-key="id"
                height="100%"
                empty-text="暂无评估用例"
              >
                <ElTableColumn prop="caseName" label="用例名称" min-width="180" fixed="left" show-overflow-tooltip />
                <ElTableColumn label="评估集" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">{{ resolveSuiteName(row.suiteId) }}</template>
                </ElTableColumn>
                <ElTableColumn prop="userInput" label="用户输入" min-width="280" show-overflow-tooltip />
                <ElTableColumn prop="expectedOutput" label="期望输出" min-width="240" show-overflow-tooltip />
                <ElTableColumn label="状态" width="100">
                  <template #default="{ row }">
                    <ElTag :type="enabledStatusTag(row.status)" effect="light" size="small">
                      {{ enabledStatusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="操作" width="96" fixed="right" align="center">
                  <template #default="{ row }">
                    <ElTooltip content="编辑" placement="top">
                      <ElButton v-if="canManageEvaluation" text type="primary" @click="openCaseDialog(row)">
                        <ElIcon><Edit /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                  </template>
                </ElTableColumn>
              </ElTable>
            </div>
            <div class="eval-pagination">
              <ElPagination
                v-model:current-page="casePage.current"
                v-model:page-size="casePage.size"
                background
                layout="total, sizes, prev, pager, next, jumper"
                :page-sizes="[10, 20, 50, 100]"
                :total="casePage.total"
                @size-change="handleCaseSizeChange"
                @current-change="loadCases"
              />
            </div>
          </ElTabPane>

          <ElTabPane label="运行记录" name="runs">
            <div class="panel-toolbar">
              <ElForm :model="runQuery" inline>
                <ElFormItem label="评估集">
                  <ElSelect
                    v-model="runQuery.suiteId"
                    clearable
                    filterable
                    placeholder="全部评估集"
                    class="w-180"
                    @visible-change="handleSuiteOptionsVisible"
                  >
                    <ElOption
                      v-for="item in suiteOptions"
                      :key="String(item.id)"
                      :label="suiteLabel(item)"
                      :value="item.id || ''"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="对象">
                  <ElSelect
                    v-model="runQuery.subjectId"
                    clearable
                    filterable
                    placeholder="全部对象"
                    class="w-180"
                    @visible-change="handleSubjectOptionsVisible"
                  >
                    <ElOption
                      v-for="item in subjectOptions"
                      :key="String(item.id)"
                      :label="subjectLabel(item)"
                      :value="item.id || ''"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="状态">
                  <ElSelect v-model="runQuery.status" clearable placeholder="全部" class="w-180px">
                    <ElOption
                      v-for="item in runStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem>
                  <ElButton type="primary" :loading="runLoading" @click="handleRunSearch">
                    <ElIcon><Search /></ElIcon>
                    查询
                  </ElButton>
                  <ElButton @click="resetRunQuery">重置</ElButton>
                </ElFormItem>
              </ElForm>
              <ElButton v-if="canRunEvaluation" type="success" @click="openRunDialog()">
                <ElIcon><VideoPlay /></ElIcon>
                运行评估
              </ElButton>
            </div>

            <div class="eval-table-wrap">
              <ElTable
                v-loading="runLoading"
                :data="runRows"
                border
                stripe
                row-key="id"
                height="100%"
                empty-text="暂无运行记录"
              >
                <ElTableColumn label="运行 ID" width="120" fixed="left">
                  <template #default="{ row }">{{ row.id || '-' }}</template>
                </ElTableColumn>
                <ElTableColumn label="评估集" min-width="170" show-overflow-tooltip>
                  <template #default="{ row }">{{ resolveSuiteName(row.suiteId) }}</template>
                </ElTableColumn>
                <ElTableColumn label="对象" min-width="170" show-overflow-tooltip>
                  <template #default="{ row }">{{ resolveSubjectName(row.subjectId) }}</template>
                </ElTableColumn>
                <ElTableColumn label="策略" min-width="170" show-overflow-tooltip>
                  <template #default="{ row }">{{ resolvePolicyName(row.policyId) }}</template>
                </ElTableColumn>
                <ElTableColumn label="状态" width="108">
                  <template #default="{ row }">
                    <ElTag :type="runStatusTag(row.status)" effect="light" size="small">
                      {{ runStatusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="执行意图" width="156">
                  <template #default="{ row }">
                    <ElTooltip
                      :disabled="row.executionIntent !== EXECUTION_INTENT_DRY_RUN"
                      :content="DRY_RUN_DESCRIPTION"
                      placement="top"
                    >
                      <ElTag :type="executionIntentTagType(row.executionIntent)" effect="light" size="small">
                        {{ executionIntentLabel(row.executionIntent || EXECUTION_INTENT_LIVE) }}
                      </ElTag>
                    </ElTooltip>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="DRY_RUN 违规（写/隔离）" width="170" align="right">
                  <template #default="{ row }">
                    <span
                      v-if="row.executionIntent === EXECUTION_INTENT_DRY_RUN"
                      :class="{ 'violation-danger': runHasViolations(row) }"
                    >
                      写 {{ row.writeViolationCount ?? 0 }} / 隔离 {{ row.isolationViolationCount ?? 0 }}
                    </span>
                    <span v-else>-</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="进度" width="150" align="right">
                  <template #default="{ row }">
                    {{ formatNumber(row.successCount) }}/{{ formatNumber(row.totalCount) }}
                  </template>
                </ElTableColumn>
                <ElTableColumn label="均分" width="96" align="right">
                  <template #default="{ row }">{{ formatScore(row.averageScore) }}</template>
                </ElTableColumn>
                <ElTableColumn label="开始时间" width="170">
                  <template #default="{ row }">{{ formatDateTime(row.startedAt || row.createTime) }}</template>
                </ElTableColumn>
                <ElTableColumn prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
                <ElTableColumn label="操作" width="132" fixed="right" align="center">
                  <template #default="{ row }">
                    <ElTooltip content="结果" placement="top">
                      <ElButton text type="primary" @click="goResults(row)">
                        <ElIcon><Search /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                    <ElTooltip content="取消" placement="top">
                      <ElButton
                        v-if="canRunEvaluation && ['queued', 'running'].includes(row.status || '')"
                        text
                        type="danger"
                        @click="cancelRun(row)"
                      >
                        取消
                      </ElButton>
                    </ElTooltip>
                  </template>
                </ElTableColumn>
              </ElTable>
            </div>
            <div class="eval-pagination">
              <ElPagination
                v-model:current-page="runPage.current"
                v-model:page-size="runPage.size"
                background
                layout="total, sizes, prev, pager, next, jumper"
                :page-sizes="[10, 20, 50, 100]"
                :total="runPage.total"
                @size-change="handleRunSizeChange"
                @current-change="loadRuns"
              />
            </div>
          </ElTabPane>
        </ElTabs>
      </ElCard>
      <ElDialog
        v-model="policyDialogVisible"
        :title="editingPolicyId ? '编辑策略' : '新增策略'"
        width="760px"
        destroy-on-close
      >
        <ElForm ref="policyFormRef" :model="policyForm" label-width="120px" class="dialog-grid">
          <ElFormItem label="策略编码" prop="policyCode" required>
            <ElInput v-model="policyForm.policyCode" clearable />
          </ElFormItem>
          <ElFormItem label="策略名称" prop="policyName" required>
            <ElInput v-model="policyForm.policyName" clearable />
          </ElFormItem>
          <ElFormItem label="对象类型" prop="subjectType" required>
            <ElSelect v-model="policyForm.subjectType" @change="handlePolicySubjectTypeChange">
              <ElOption
                v-for="item in subjectTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="policyForm.status">
              <ElOption
                v-for="item in enabledStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="默认策略">
            <ElSwitch v-model="policyForm.defaultFlag" />
          </ElFormItem>
          <ElFormItem label="说明">
            <ElInput v-model="policyForm.description" clearable />
          </ElFormItem>
          <ElFormItem label="权重 JSON" prop="scoreWeightsJson" class="dialog-wide">
            <JsonObjectEditor
              :model-value="policyForm.scoreWeightsJson"
              output-type="string"
              :rows="5"
              @update:model-value="policyForm.scoreWeightsJson = String($event)"
              @validity-change="policyJsonValidity.scoreWeights = $event"
            />
          </ElFormItem>
          <ElFormItem label="评分器 JSON" prop="graderConfigJson" class="dialog-wide">
            <JsonObjectEditor
              :model-value="policyForm.graderConfigJson"
              output-type="string"
              :rows="5"
              @update:model-value="policyForm.graderConfigJson = String($event)"
              @validity-change="policyJsonValidity.graderConfig = $event"
            />
          </ElFormItem>
          <ElFormItem label="效率预算 JSON" prop="efficiencyBudgetJson" class="dialog-wide">
            <JsonObjectEditor
              :model-value="policyForm.efficiencyBudgetJson"
              output-type="string"
              :rows="5"
              @update:model-value="policyForm.efficiencyBudgetJson = String($event)"
              @validity-change="policyJsonValidity.efficiencyBudget = $event"
            />
          </ElFormItem>
          <ElFormItem label="门禁规则 JSON" prop="gateRuleJson" class="dialog-wide">
            <JsonObjectEditor
              :model-value="policyForm.gateRuleJson"
              output-type="string"
              :rows="5"
              @update:model-value="policyForm.gateRuleJson = String($event)"
              @validity-change="policyJsonValidity.gateRule = $event"
            />
          </ElFormItem>
          <ElFormItem label="硬失败 JSON" prop="hardFailRuleJson" class="dialog-wide">
            <JsonObjectEditor
              :model-value="policyForm.hardFailRuleJson"
              output-type="string"
              :rows="5"
              @update:model-value="policyForm.hardFailRuleJson = String($event)"
              @validity-change="policyJsonValidity.hardFailRule = $event"
            />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="policyDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="policySubmitting" :disabled="!policyJsonValid" @click="submitPolicy">
            保存
          </ElButton>
        </template>
      </ElDialog>

      <ElDialog
        v-model="subjectDialogVisible"
        :title="editingSubjectId ? '编辑对象' : '新增对象'"
        width="640px"
        destroy-on-close
      >
        <ElForm ref="subjectFormRef" :model="subjectForm" label-width="120px">
          <ElFormItem label="对象类型" prop="subjectType" required>
            <ElSelect v-model="subjectForm.subjectType" @change="handleSubjectTypeChange">
              <ElOption
                v-for="item in subjectTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-if="isDataAgentSubject(subjectForm.subjectType)" label="选择智能体">
            <ElSelect
              v-model="subjectAgentId"
              clearable
              filterable
              placeholder="选择已发布智能体自动填充"
              @visible-change="handleAgentOptionsVisible"
              @change="handleSubjectAgentChange"
            >
              <ElOption
                v-for="item in publishedAgentOptions"
                :key="String(item.id)"
                :label="agentLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem
            v-if="isEmployeeReleaseSubject(subjectForm.subjectType) || isEmployeeCandidateSubject(subjectForm.subjectType)"
            label="选择数字员工"
          >
            <EmployeeOptionSelect
              v-model="subjectEmployeeId"
              :placeholder="
                isEmployeeCandidateSubject(subjectForm.subjectType)
                  ? '选择数字员工，业务 ID 填员工 ID'
                  : '先选数字员工，再选已发布版本'
              "
              width="100%"
              @change="handleSubjectEmployeeChange"
            />
          </ElFormItem>
          <ElFormItem v-if="isEmployeeReleaseSubject(subjectForm.subjectType)" label="已发布版本">
            <ElSelect
              v-model="subjectForm.subjectId"
              clearable
              filterable
              placeholder="选择 PUBLISHED 发布版本"
              :disabled="!subjectEmployeeId"
              :loading="employeeReleaseLoading"
              @change="handleSubjectReleaseChange"
            >
              <ElOption
                v-for="item in employeeReleaseOptions"
                :key="String(item.id)"
                :label="employeeReleaseLabel(item)"
                :value="String(item.id || '')"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="对象名称" prop="subjectName" required>
            <ElInput v-model="subjectForm.subjectName" clearable />
          </ElFormItem>
          <ElFormItem label="业务对象 ID" prop="subjectId" required>
            <ElInput v-model="subjectForm.subjectId" clearable />
            <div v-if="isEmployeeReleaseSubject(subjectForm.subjectType)" class="field-hint">
              填数字员工已发布版本 ID（digital_employee_release.id），不是员工 ID。
            </div>
            <div v-else-if="isEmployeeCandidateSubject(subjectForm.subjectType)" class="field-hint">
              填数字员工 ID。进化门禁和 SANDBOX 真跑用这个类型，不要用发布版本。
            </div>
          </ElFormItem>
          <ElFormItem label="适配器" prop="adapterCode" required>
            <ElInput
              v-model="subjectForm.adapterCode"
              clearable
              :disabled="
                isEmployeeReleaseSubject(subjectForm.subjectType) || isEmployeeCandidateSubject(subjectForm.subjectType)
              "
            />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="subjectForm.status">
              <ElOption
                v-for="item in enabledStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="说明">
            <ElInput v-model="subjectForm.description" type="textarea" :rows="3" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="subjectDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="subjectSubmitting" @click="submitSubject">保存</ElButton>
        </template>
      </ElDialog>

      <ElDialog
        v-model="suiteDialogVisible"
        :title="editingSuiteId ? '编辑评估集' : '新增评估集'"
        width="620px"
        destroy-on-close
      >
        <ElForm ref="suiteFormRef" :model="suiteForm" label-width="120px">
          <ElFormItem label="评估集名称" prop="suiteName" required>
            <ElInput v-model="suiteForm.suiteName" clearable />
          </ElFormItem>
          <ElFormItem label="评估对象" prop="subjectId" required>
            <ElSelect
              v-model="suiteForm.subjectId"
              clearable
              filterable
              placeholder="选择评估对象"
              @visible-change="handleSubjectOptionsVisible"
            >
              <ElOption
                v-for="item in subjectOptions"
                :key="String(item.id)"
                :label="subjectLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="评估策略" prop="policyId" required>
            <ElSelect
              v-model="suiteForm.policyId"
              clearable
              filterable
              placeholder="选择评估策略"
              @visible-change="handlePolicyOptionsVisible"
            >
              <ElOption
                v-for="item in policyOptions"
                :key="String(item.id)"
                :label="policyLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="suiteForm.status">
              <ElOption
                v-for="item in enabledStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="说明">
            <ElInput v-model="suiteForm.description" type="textarea" :rows="3" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="suiteDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="suiteSubmitting" @click="submitSuite">保存</ElButton>
        </template>
      </ElDialog>

      <ElDialog
        v-model="caseDialogVisible"
        :title="editingCaseId ? '编辑用例' : '新增用例'"
        width="760px"
        destroy-on-close
      >
        <ElForm ref="caseFormRef" :model="caseForm" label-width="130px">
          <ElFormItem label="评估集" prop="suiteId" required>
            <ElSelect
              v-model="caseForm.suiteId"
              clearable
              filterable
              placeholder="选择评估集"
              @visible-change="handleSuiteOptionsVisible"
            >
              <ElOption
                v-for="item in suiteOptions"
                :key="String(item.id)"
                :label="suiteLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="用例名称" prop="caseName" required>
            <ElInput v-model="caseForm.caseName" clearable />
          </ElFormItem>
          <ElFormItem label="用户输入" prop="userInput" required>
            <ElInput v-model="caseForm.userInput" type="textarea" :rows="4" />
          </ElFormItem>
          <ElFormItem label="期望输出">
            <ElInput v-model="caseForm.expectedOutput" type="textarea" :rows="3" />
          </ElFormItem>
          <ElFormItem label="安全约束 JSON" prop="safetyConstraintsJson">
            <JsonObjectEditor
              :model-value="caseForm.safetyConstraintsJson"
              output-type="string"
              :rows="5"
              @update:model-value="caseForm.safetyConstraintsJson = String($event)"
              @validity-change="caseJsonValidity.safetyConstraints = $event"
            />
          </ElFormItem>
          <ElFormItem label="效率预算 JSON" prop="efficiencyBudgetJson">
            <JsonObjectEditor
              :model-value="caseForm.efficiencyBudgetJson"
              output-type="string"
              :rows="5"
              @update:model-value="caseForm.efficiencyBudgetJson = String($event)"
              @validity-change="caseJsonValidity.efficiencyBudget = $event"
            />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="caseForm.status">
              <ElOption
                v-for="item in enabledStatusOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="caseDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="caseSubmitting" :disabled="!caseJsonValid" @click="submitCase">
            保存
          </ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="runDialogVisible" title="运行评估" width="560px" destroy-on-close>
        <ElForm :model="runForm" label-width="120px">
          <ElFormItem label="评估集" required>
            <ElSelect
              v-model="runForm.suiteId"
              clearable
              filterable
              placeholder="选择评估集"
              @visible-change="handleSuiteOptionsVisible"
              @change="handleRunSuiteChange"
            >
              <ElOption
                v-for="item in suiteOptions"
                :key="String(item.id)"
                :label="suiteLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="指定对象">
            <ElSelect
              v-model="runForm.subjectId"
              clearable
              filterable
              placeholder="默认使用评估集对象"
              @visible-change="handleSubjectOptionsVisible"
            >
              <ElOption
                v-for="item in subjectOptions"
                :key="String(item.id)"
                :label="subjectLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="指定策略">
            <ElSelect
              v-model="runForm.policyId"
              clearable
              filterable
              placeholder="默认使用评估集策略"
              @visible-change="handlePolicyOptionsVisible"
            >
              <ElOption
                v-for="item in policyOptions"
                :key="String(item.id)"
                :label="policyLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="执行意图">
            <ElSelect v-model="runForm.executionIntent">
              <ElOption
                v-for="item in EXECUTION_INTENT_OPTIONS"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="评估模式">
            <ElSelect v-model="runForm.evalMode">
              <ElOption
                v-for="item in evalModeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
            <div class="field-hint">
              缺省回放历史。数字员工候选真跑请选 INVOKE，并确保 SANDBOX 已激活。
            </div>
          </ElFormItem>
        </ElForm>
        <ElAlert
          v-if="runForm.executionIntent === EXECUTION_INTENT_DRY_RUN"
          type="warning"
          :closable="false"
          show-icon
          :title="DRY_RUN_DESCRIPTION"
        />
        <ElAlert
          v-if="runForm.evalMode === EVAL_MODE_INVOKE"
          type="info"
          :closable="false"
          show-icon
          title="INVOKE 会按评估对象真跑模型。数字员工钉 SANDBOX，没有沙箱指针会失败。"
          class="mt-8px"
        />
        <template #footer>
          <ElButton @click="runDialogVisible = false">取消</ElButton>
          <ElButton type="success" :loading="runSubmitting" @click="submitRun">开始运行</ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="bootstrapDialogVisible" title="初始化默认配置" width="720px" destroy-on-close>
        <ElForm ref="bootstrapFormRef" :model="bootstrapForm" label-width="140px">
          <ElFormItem label="初始化主体" required>
            <ElRadioGroup v-model="bootstrapOwnerKind">
              <ElRadioButton label="DATA_AGENT" value="DATA_AGENT">DataAgent</ElRadioButton>
              <ElRadioButton label="DIGITAL_EMPLOYEE" value="DIGITAL_EMPLOYEE">数字员工</ElRadioButton>
            </ElRadioGroup>
          </ElFormItem>
          <ElFormItem v-if="bootstrapOwnerKind === 'DATA_AGENT'" label="已发布 Agent" prop="agentId" required>
            <ElSelect
              v-model="bootstrapAgentId"
              clearable
              filterable
              placeholder="选择已发布 DataAgent"
              @visible-change="handleAgentOptionsVisible"
              @change="handleBootstrapAgentChange"
            >
              <ElOption
                v-for="item in publishedAgentOptions"
                :key="String(item.id)"
                :label="agentLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-else label="数字员工" prop="employeeId" required>
            <EmployeeOptionSelect
              v-model="bootstrapEmployeeId"
              placeholder="选择要初始化评估配置的数字员工"
              width="100%"
            />
            <div class="field-hint">会创建候选对象、默认策略/评估集/冒烟用例；SANDBOX 无指针时用生产 Release 补齐。</div>
          </ElFormItem>
          <ElFormItem label="立即运行">
            <ElSwitch v-model="bootstrapForm.runNow" :disabled="!canBootstrapRunNow" />
            <div v-if="!canBootstrapRunNow" class="field-hint">需要 agent:evaluation:run 权限</div>
            <div v-else-if="bootstrapOwnerKind === 'DIGITAL_EMPLOYEE'" class="field-hint">
              数字员工冒烟强制 DRY_RUN + INVOKE，不会改生产对话。
            </div>
          </ElFormItem>
          <ElFormItem label="冒烟问题">
            <ElInput v-model="bootstrapForm.caseUserInput" type="textarea" :rows="3" />
          </ElFormItem>
          <ElFormItem label="期望输出">
            <ElInput v-model="bootstrapForm.expectedOutput" type="textarea" :rows="3" placeholder="可为空" />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="bootstrapDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="bootstrapSubmitting" @click="submitBootstrap">初始化</ElButton>
        </template>
      </ElDialog>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance } from 'element-plus';
import { Edit, MagicStick, Plus, Refresh, Search, VideoPlay } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { haveAuth } from '@/mixins/userAuth.js';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import EmployeeOptionSelect from '@/views/ai-agent/components/employee-option-select.vue';
import AgentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type { EmployeeReleaseOption } from '@/views/ai-agent/services/agentTask';
import evaluationService from '@/views/ai-agent/services/evaluation';
import type {
  EvalBootstrapItemStatus,
  EvalBootstrapRequest,
  EvalBootstrapResult,
  EvalCase,
  EvalCaseRequest,
  EvalId,
  EvalPolicy,
  EvalPolicyRequest,
  EvalRun,
  EvalRunCreateRequest,
  EvalSubject,
  EvalSubjectRequest,
  EvalSuite,
  EvalSuiteRequest
} from '@/views/ai-agent/services/evaluation';
import {
  DRY_RUN_DESCRIPTION,
  EXECUTION_INTENT_DRY_RUN,
  EXECUTION_INTENT_LIVE,
  EXECUTION_INTENT_OPTIONS,
  executionIntentLabel,
  executionIntentTagType
} from '@/views/ai-agent/constants/execution-intent';

defineOptions({ name: 'AgentEvaluationsPage' });

const router = useRouter();

const DEFAULT_SUBJECT_TYPE = 'DATA_AGENT';
const DEFAULT_ADAPTER_CODE = 'DATA_AGENT';
const EMPLOYEE_RELEASE_SUBJECT_TYPE = 'DIGITAL_EMPLOYEE_RELEASE';
const EMPLOYEE_CANDIDATE_SUBJECT_TYPE = 'DIGITAL_EMPLOYEE_CANDIDATE';
const DEFAULT_SCORE_WEIGHTS_JSON = '{"quality":45,"safety":25,"efficiency":20,"stability":10}';
const DEFAULT_EFFICIENCY_BUDGET_JSON = '{"maxDurationMs":60000}';
const EVAL_MODE_REPLAY = 'REPLAY';
const EVAL_MODE_INVOKE = 'INVOKE';
const subjectTypeOptions = [
  { label: 'DataAgent', value: DEFAULT_SUBJECT_TYPE },
  { label: '数字员工候选（真跑）', value: EMPLOYEE_CANDIDATE_SUBJECT_TYPE },
  { label: '数字员工发布版本（回放）', value: EMPLOYEE_RELEASE_SUBJECT_TYPE }
];
const evalModeOptions = [
  { label: '回放历史（REPLAY）', value: EVAL_MODE_REPLAY },
  { label: '沙箱真跑（INVOKE）', value: EVAL_MODE_INVOKE }
];
const EVALUATION_MANAGE_PERMISSION = 'agent:evaluation:manage';
const EVALUATION_RUN_PERMISSION = 'agent:evaluation:run';

const enabledStatusOptions = [
  { label: '启用', value: 'enabled' },
  { label: '停用', value: 'disabled' }
];
const runStatusOptions = [
  { label: '排队中', value: 'queued' },
  { label: '运行中', value: 'running' },
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' },
  { label: '超时', value: 'timeout' },
  { label: '已取消', value: 'cancelled' }
];

const canManageEvaluation = computed(() => haveAuth(EVALUATION_MANAGE_PERMISSION));
const canRunEvaluation = computed(() => haveAuth(EVALUATION_RUN_PERMISSION));
const canBootstrapRunNow = computed(() => canRunEvaluation.value);

const activeTab = ref('policies');
const policyLoading = ref(false);
const subjectLoading = ref(false);
const suiteLoading = ref(false);
const caseLoading = ref(false);
const runLoading = ref(false);
const policySubmitting = ref(false);
const subjectSubmitting = ref(false);
const suiteSubmitting = ref(false);
const caseSubmitting = ref(false);
const runSubmitting = ref(false);
const bootstrapSubmitting = ref(false);
const policyDialogVisible = ref(false);
const subjectDialogVisible = ref(false);
const suiteDialogVisible = ref(false);
const caseDialogVisible = ref(false);
const runDialogVisible = ref(false);
const bootstrapDialogVisible = ref(false);
const editingPolicyId = ref<EvalId | null>(null);
const editingSubjectId = ref<EvalId | null>(null);
const editingSuiteId = ref<EvalId | null>(null);
const editingCaseId = ref<EvalId | null>(null);
const subjectAgentId = ref<EvalId | ''>('');
const subjectEmployeeId = ref('');
const employeeReleaseOptions = ref<EmployeeReleaseOption[]>([]);
const employeeReleaseLoading = ref(false);
const bootstrapAgentId = ref<EvalId | ''>('');
const bootstrapEmployeeId = ref('');
const bootstrapOwnerKind = ref<'DATA_AGENT' | 'DIGITAL_EMPLOYEE'>('DATA_AGENT');

const pageLoading = computed(
  () => policyLoading.value || subjectLoading.value || suiteLoading.value || caseLoading.value || runLoading.value
);

const policyPage = reactive({ current: 1, size: 10, total: 0 });
const subjectPage = reactive({ current: 1, size: 10, total: 0 });
const suitePage = reactive({ current: 1, size: 10, total: 0 });
const casePage = reactive({ current: 1, size: 10, total: 0 });
const runPage = reactive({ current: 1, size: 10, total: 0 });

const policyRows = ref<EvalPolicy[]>([]);
const subjectRows = ref<EvalSubject[]>([]);
const suiteRows = ref<EvalSuite[]>([]);
const caseRows = ref<EvalCase[]>([]);
const runRows = ref<EvalRun[]>([]);
const policyOptions = ref<EvalPolicy[]>([]);
const subjectOptions = ref<EvalSubject[]>([]);
const suiteOptions = ref<EvalSuite[]>([]);
const publishedAgentOptions = ref<Agent[]>([]);
const optionsLoaded = reactive({
  policies: false,
  subjects: false,
  suites: false,
  agents: false
});
const loadedTabs = reactive({
  policies: false,
  subjects: false,
  suites: false,
  cases: false,
  runs: false
});

const policyQuery = reactive({ policyName: '', subjectType: DEFAULT_SUBJECT_TYPE, status: '' });
const subjectQuery = reactive({ subjectName: '', subjectType: DEFAULT_SUBJECT_TYPE, status: '' });
const suiteQuery = reactive({ suiteName: '', subjectId: '' as EvalId | '', status: '' });
const caseQuery = reactive({ suiteId: '' as EvalId | '', keyword: '', status: '' });
const runQuery = reactive({ suiteId: '' as EvalId | '', subjectId: '' as EvalId | '', status: '' });

const policyFormRef = ref<FormInstance | null>(null);
const subjectFormRef = ref<FormInstance | null>(null);
const suiteFormRef = ref<FormInstance | null>(null);
const caseFormRef = ref<FormInstance | null>(null);
const bootstrapFormRef = ref<FormInstance | null>(null);

const policyForm = reactive<EvalPolicyRequest>(defaultPolicyForm());
const subjectForm = reactive<EvalSubjectRequest>(defaultSubjectForm());
const suiteForm = reactive<EvalSuiteRequest>(defaultSuiteForm());
const caseForm = reactive<EvalCaseRequest>(defaultCaseForm());
const runForm = reactive<EvalRunCreateRequest>(defaultRunForm());
const bootstrapForm = reactive<EvalBootstrapRequest>(defaultBootstrapForm());
const policyJsonValidity = reactive({
  scoreWeights: true,
  graderConfig: true,
  efficiencyBudget: true,
  gateRule: true,
  hardFailRule: true
});
const caseJsonValidity = reactive({ safetyConstraints: true, efficiencyBudget: true });
const policyJsonValid = computed(() => Object.values(policyJsonValidity).every(Boolean));
const caseJsonValid = computed(() => Object.values(caseJsonValidity).every(Boolean));

function resetJsonValidity(target: Record<string, boolean>) {
  Object.keys(target).forEach(key => {
    target[key] = true;
  });
}

function defaultPolicyForm(): EvalPolicyRequest {
  return {
    policyCode: 'DATA_AGENT_DEFAULT',
    policyName: 'DataAgent 默认评估策略',
    status: 'enabled',
    defaultFlag: true,
    subjectType: DEFAULT_SUBJECT_TYPE,
    scoreWeightsJson: DEFAULT_SCORE_WEIGHTS_JSON,
    graderConfigJson: '{}',
    efficiencyBudgetJson: DEFAULT_EFFICIENCY_BUDGET_JSON,
    gateRuleJson: '{}',
    hardFailRuleJson: '{}',
    description: ''
  };
}

function defaultSubjectForm(): EvalSubjectRequest {
  return {
    subjectName: '',
    subjectType: DEFAULT_SUBJECT_TYPE,
    subjectId: '',
    adapterCode: DEFAULT_ADAPTER_CODE,
    status: 'enabled',
    description: ''
  };
}

function defaultSuiteForm(): EvalSuiteRequest {
  return { suiteName: '', subjectId: '', policyId: '', status: 'enabled', description: '' };
}

function defaultCaseForm(): EvalCaseRequest {
  return {
    suiteId: '',
    caseName: '',
    userInput: '',
    expectedOutput: '',
    safetyConstraintsJson: '{}',
    efficiencyBudgetJson: '{}',
    tagsJson: '[]',
    traceSnapshotJson: '{}',
    status: 'enabled'
  };
}

function defaultRunForm(): EvalRunCreateRequest {
  return {
    suiteId: '',
    subjectId: '',
    policyId: '',
    executionIntent: EXECUTION_INTENT_LIVE,
    evalMode: EVAL_MODE_REPLAY
  };
}

/** DRY_RUN 运行是否记录到硬门禁违规（写副作用 / 权限或租户隔离）。 */
function runHasViolations(run: EvalRun) {
  return (run.writeViolationCount ?? 0) > 0 || (run.isolationViolationCount ?? 0) > 0;
}

function defaultBootstrapForm(): EvalBootstrapRequest {
  return {
    agentId: '',
    runNow: canRunEvaluation.value,
    caseUserInput: '请用一句话说明你能提供哪些能力',
    expectedOutput: ''
  };
}

async function loadPolicyOptions(force = false) {
  if (optionsLoaded.policies && !force) {
    return;
  }
  const policies = await evaluationService.queryPoliciesPage({
    current: 1,
    size: 200,
    status: 'enabled'
  });
  policyOptions.value = policies.data;
  optionsLoaded.policies = true;
}

async function loadSubjectOptions(force = false) {
  if (optionsLoaded.subjects && !force) {
    return;
  }
  const subjects = await evaluationService.querySubjectsPage({
    current: 1,
    size: 200,
    status: 'enabled'
  });
  subjectOptions.value = subjects.data;
  optionsLoaded.subjects = true;
}

async function loadSuiteOptions(force = false) {
  if (optionsLoaded.suites && !force) {
    return;
  }
  const suites = await evaluationService.querySuitesPage({ current: 1, size: 200, status: 'enabled' });
  suiteOptions.value = suites.data;
  optionsLoaded.suites = true;
}

async function loadAgentOptions(force = false) {
  if (optionsLoaded.agents && !force) {
    return;
  }
  const agents = await AgentService.list('published');
  publishedAgentOptions.value = agents;
  optionsLoaded.agents = true;
}

function handlePolicyOptionsVisible(visible: boolean) {
  if (visible) {
    loadPolicyOptions();
  }
}

function handleSubjectOptionsVisible(visible: boolean) {
  if (visible) {
    loadSubjectOptions();
  }
}

function handleSuiteOptionsVisible(visible: boolean) {
  if (visible) {
    loadSuiteOptions();
  }
}

function handleAgentOptionsVisible(visible: boolean) {
  if (visible) {
    loadAgentOptions();
  }
}

async function loadPolicies() {
  policyLoading.value = true;
  try {
    const response = await evaluationService.queryPoliciesPage({
      ...policyQuery,
      current: policyPage.current,
      size: policyPage.size
    });
    policyRows.value = response.data;
    policyPage.total = response.total;
    loadedTabs.policies = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估策略查询失败');
  } finally {
    policyLoading.value = false;
  }
}

async function loadSubjects() {
  subjectLoading.value = true;
  try {
    const response = await evaluationService.querySubjectsPage({
      ...subjectQuery,
      current: subjectPage.current,
      size: subjectPage.size
    });
    subjectRows.value = response.data;
    subjectPage.total = response.total;
    loadedTabs.subjects = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估对象查询失败');
  } finally {
    subjectLoading.value = false;
  }
}

async function loadSuites() {
  suiteLoading.value = true;
  try {
    const response = await evaluationService.querySuitesPage({
      ...suiteQuery,
      current: suitePage.current,
      size: suitePage.size
    });
    suiteRows.value = response.data;
    suitePage.total = response.total;
    loadedTabs.suites = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估集查询失败');
  } finally {
    suiteLoading.value = false;
  }
}

async function loadCases() {
  caseLoading.value = true;
  try {
    const response = await evaluationService.queryCasesPage({
      ...caseQuery,
      current: casePage.current,
      size: casePage.size
    });
    caseRows.value = response.data;
    casePage.total = response.total;
    loadedTabs.cases = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估用例查询失败');
  } finally {
    caseLoading.value = false;
  }
}

async function loadRuns() {
  runLoading.value = true;
  try {
    const response = await evaluationService.queryRunsPage({
      ...runQuery,
      current: runPage.current,
      size: runPage.size
    });
    runRows.value = response.data;
    runPage.total = response.total;
    loadedTabs.runs = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估运行查询失败');
  } finally {
    runLoading.value = false;
  }
}

async function refreshActiveTab() {
  if (activeTab.value === 'policies') await loadPolicies();
  if (activeTab.value === 'subjects') await loadSubjects();
  if (activeTab.value === 'suites') await loadSuites();
  if (activeTab.value === 'cases') await loadCases();
  if (activeTab.value === 'runs') await loadRuns();
}

async function ensureActiveTabLoaded() {
  if (loadedTabs[activeTab.value as keyof typeof loadedTabs]) {
    return;
  }
  await refreshActiveTab();
}

function handlePolicySearch() {
  policyPage.current = 1;
  loadPolicies();
}

function handleSubjectSearch() {
  subjectPage.current = 1;
  loadSubjects();
}

function handleSuiteSearch() {
  suitePage.current = 1;
  loadSuites();
}

function handleCaseSearch() {
  casePage.current = 1;
  loadCases();
}

function handleRunSearch() {
  runPage.current = 1;
  loadRuns();
}

function resetPolicyQuery() {
  Object.assign(policyQuery, { policyName: '', subjectType: DEFAULT_SUBJECT_TYPE, status: '' });
  handlePolicySearch();
}

function resetSubjectQuery() {
  Object.assign(subjectQuery, { subjectName: '', subjectType: DEFAULT_SUBJECT_TYPE, status: '' });
  handleSubjectSearch();
}

function resetSuiteQuery() {
  Object.assign(suiteQuery, { suiteName: '', subjectId: '', status: '' });
  handleSuiteSearch();
}

function resetCaseQuery() {
  Object.assign(caseQuery, { suiteId: '', keyword: '', status: '' });
  handleCaseSearch();
}

function resetRunQuery() {
  Object.assign(runQuery, { suiteId: '', subjectId: '', status: '' });
  handleRunSearch();
}

function handlePolicySizeChange() {
  policyPage.current = 1;
  loadPolicies();
}

function handleSubjectSizeChange() {
  subjectPage.current = 1;
  loadSubjects();
}

function handleSuiteSizeChange() {
  suitePage.current = 1;
  loadSuites();
}

function handleCaseSizeChange() {
  casePage.current = 1;
  loadCases();
}

function handleRunSizeChange() {
  runPage.current = 1;
  loadRuns();
}

function switchTab(tabName: string) {
  if (activeTab.value === tabName) {
    return;
  }
  activeTab.value = tabName;
}

async function openPolicyDialog(row?: EvalPolicy) {
  editingPolicyId.value = row?.id ?? null;
  resetJsonValidity(policyJsonValidity);
  Object.assign(policyForm, row ? normalizePolicyForm(row) : defaultPolicyForm());
  policyDialogVisible.value = true;
}

async function openSubjectDialog(row?: EvalSubject) {
  await loadAgentOptions();
  editingSubjectId.value = row?.id ?? null;
  Object.assign(subjectForm, row ? normalizeSubjectForm(row) : defaultSubjectForm());
  subjectAgentId.value = isDataAgentSubject(subjectForm.subjectType)
    ? resolveAgentIdBySubjectId(subjectForm.subjectId)
    : '';
  subjectEmployeeId.value = isEmployeeCandidateSubject(subjectForm.subjectType)
    ? subjectForm.subjectId || ''
    : '';
  employeeReleaseOptions.value = [];
  if (isEmployeeReleaseSubject(subjectForm.subjectType)) {
    subjectForm.adapterCode = EMPLOYEE_RELEASE_SUBJECT_TYPE;
  }
  if (isEmployeeCandidateSubject(subjectForm.subjectType)) {
    subjectForm.adapterCode = EMPLOYEE_CANDIDATE_SUBJECT_TYPE;
  }
  subjectDialogVisible.value = true;
}

async function openSuiteDialog(row?: EvalSuite) {
  await Promise.all([loadSubjectOptions(), loadPolicyOptions()]);
  editingSuiteId.value = row?.id ?? null;
  Object.assign(suiteForm, row ? normalizeSuiteForm(row) : defaultSuiteForm());
  suiteDialogVisible.value = true;
}

async function openCaseDialog(row?: EvalCase) {
  await loadSuiteOptions();
  editingCaseId.value = row?.id ?? null;
  resetJsonValidity(caseJsonValidity);
  Object.assign(caseForm, row ? normalizeCaseForm(row) : defaultCaseForm());
  caseDialogVisible.value = true;
}

async function openRunDialog(row?: EvalSuite) {
  await Promise.all([loadSuiteOptions(), loadSubjectOptions(), loadPolicyOptions()]);
  Object.assign(runForm, defaultRunForm(), {
    suiteId: row?.id || '',
    subjectId: '',
    policyId: ''
  });
  handleRunSuiteChange();
  runDialogVisible.value = true;
}

async function openBootstrapDialog() {
  if (!canManageEvaluation.value) {
    ElMessage.warning('需要 agent:evaluation:manage 权限');
    return;
  }
  await loadAgentOptions();
  Object.assign(bootstrapForm, defaultBootstrapForm(), { runNow: canRunEvaluation.value });
  bootstrapAgentId.value = '';
  bootstrapEmployeeId.value = '';
  bootstrapOwnerKind.value = 'DATA_AGENT';
  bootstrapDialogVisible.value = true;
}

function normalizePolicyForm(row: EvalPolicy): EvalPolicyRequest {
  return {
    policyCode: row.policyCode || '',
    policyName: row.policyName || '',
    status: row.status || 'enabled',
    defaultFlag: row.defaultFlag === true,
    subjectType: row.subjectType || DEFAULT_SUBJECT_TYPE,
    scoreWeightsJson: row.scoreWeightsJson || '{}',
    graderConfigJson: row.graderConfigJson || '{}',
    efficiencyBudgetJson: row.efficiencyBudgetJson || '{}',
    gateRuleJson: row.gateRuleJson || '{}',
    hardFailRuleJson: row.hardFailRuleJson || '{}',
    description: row.description || ''
  };
}

function normalizeSubjectForm(row: EvalSubject): EvalSubjectRequest {
  return {
    subjectName: row.subjectName || '',
    subjectType: row.subjectType || DEFAULT_SUBJECT_TYPE,
    subjectId: row.subjectId || '',
    adapterCode: row.adapterCode || DEFAULT_ADAPTER_CODE,
    status: row.status || 'enabled',
    description: row.description || ''
  };
}

function normalizeSuiteForm(row: EvalSuite): EvalSuiteRequest {
  return {
    suiteName: row.suiteName || '',
    subjectId: row.subjectId || '',
    policyId: row.policyId || '',
    status: row.status || 'enabled',
    description: row.description || ''
  };
}

function normalizeCaseForm(row: EvalCase): EvalCaseRequest {
  return {
    suiteId: row.suiteId || '',
    caseName: row.caseName || '',
    userInput: row.userInput || '',
    expectedOutput: row.expectedOutput || '',
    safetyConstraintsJson: row.safetyConstraintsJson || '{}',
    efficiencyBudgetJson: row.efficiencyBudgetJson || '{}',
    tagsJson: row.tagsJson || '[]',
    traceSnapshotJson: row.traceSnapshotJson || '{}',
    status: row.status || 'enabled'
  };
}

async function submitPolicy() {
  if (!policyJsonValid.value) {
    ElMessage.warning('请先修正评估策略中的 JSON 格式错误');
    return;
  }
  const error = validatePolicy();
  if (error) {
    ElMessage.warning(error.message);
    focusFormField(policyFormRef.value, error.field);
    return;
  }
  policySubmitting.value = true;
  try {
    if (editingPolicyId.value) {
      await evaluationService.updatePolicy(editingPolicyId.value, policyForm);
      ElMessage.success('评估策略已更新');
    } else {
      await evaluationService.createPolicy(policyForm);
      ElMessage.success('评估策略已创建');
    }
    policyDialogVisible.value = false;
    optionsLoaded.policies = false;
    await loadPolicies();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估策略保存失败');
  } finally {
    policySubmitting.value = false;
  }
}

async function submitSubject() {
  const error = validateSubject();
  if (error) {
    ElMessage.warning(error.message);
    focusFormField(subjectFormRef.value, error.field);
    return;
  }
  subjectSubmitting.value = true;
  try {
    if (editingSubjectId.value) {
      await evaluationService.updateSubject(editingSubjectId.value, subjectForm);
      ElMessage.success('评估对象已更新');
    } else {
      await evaluationService.createSubject(subjectForm);
      ElMessage.success('评估对象已创建');
    }
    subjectDialogVisible.value = false;
    optionsLoaded.subjects = false;
    loadedTabs.suites = false;
    loadedTabs.runs = false;
    await loadSubjects();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估对象保存失败');
  } finally {
    subjectSubmitting.value = false;
  }
}

async function submitSuite() {
  const error = validateSuite();
  if (error) {
    ElMessage.warning(error.message);
    focusFormField(suiteFormRef.value, error.field);
    return;
  }
  suiteSubmitting.value = true;
  try {
    if (editingSuiteId.value) {
      await evaluationService.updateSuite(editingSuiteId.value, suiteForm);
      ElMessage.success('评估集已更新');
    } else {
      await evaluationService.createSuite(suiteForm);
      ElMessage.success('评估集已创建');
    }
    suiteDialogVisible.value = false;
    optionsLoaded.suites = false;
    loadedTabs.cases = false;
    loadedTabs.runs = false;
    await loadSuites();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估集保存失败');
  } finally {
    suiteSubmitting.value = false;
  }
}

async function submitCase() {
  if (!caseJsonValid.value) {
    ElMessage.warning('请先修正评估用例中的 JSON 格式错误');
    return;
  }
  const error = validateCase();
  if (error) {
    ElMessage.warning(error.message);
    focusFormField(caseFormRef.value, error.field);
    return;
  }
  caseSubmitting.value = true;
  try {
    if (editingCaseId.value) {
      await evaluationService.updateCase(editingCaseId.value, caseForm);
      ElMessage.success('评估用例已更新');
    } else {
      await evaluationService.createCase(caseForm);
      ElMessage.success('评估用例已创建');
    }
    caseDialogVisible.value = false;
    await loadCases();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估用例保存失败');
  } finally {
    caseSubmitting.value = false;
  }
}

async function submitRun() {
  if (!runForm.suiteId) {
    ElMessage.warning('请选择评估集');
    return;
  }
  runSubmitting.value = true;
  try {
    const run = await evaluationService.createRun(runForm);
    ElMessage.success('评估运行已创建');
    runDialogVisible.value = false;
    activeTab.value = 'runs';
    await loadRuns();
    if (run.id) {
      goResults(run);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估运行创建失败');
  } finally {
    runSubmitting.value = false;
  }
}

async function submitBootstrap() {
  const error = validateBootstrap();
  if (error) {
    ElMessage.warning(error.message);
    focusFormField(bootstrapFormRef.value, error.field);
    return;
  }
  bootstrapSubmitting.value = true;
  try {
    const result = await evaluationService.bootstrapDefaults(
      bootstrapOwnerKind.value === 'DIGITAL_EMPLOYEE'
        ? {
            employeeId: bootstrapEmployeeId.value,
            runNow: bootstrapForm.runNow,
            caseUserInput: bootstrapForm.caseUserInput,
            expectedOutput: bootstrapForm.expectedOutput
          }
        : {
            agentId: bootstrapAgentId.value,
            runNow: bootstrapForm.runNow,
            caseUserInput: bootstrapForm.caseUserInput,
            expectedOutput: bootstrapForm.expectedOutput
          }
    );
    bootstrapDialogVisible.value = false;
    optionsLoaded.policies = false;
    optionsLoaded.subjects = false;
    optionsLoaded.suites = false;
    optionsLoaded.agents = false;
    loadedTabs.policies = false;
    loadedTabs.subjects = false;
    loadedTabs.suites = false;
    loadedTabs.cases = false;
    loadedTabs.runs = false;
    await refreshActiveTab();
    const summary = formatBootstrapResult(result);
    ElMessage.success(result?.runId ? `${summary}，已发起冒烟运行` : summary);
    if (result?.runId) {
      goResults({
        id: result.runId,
        suiteId: result.suiteId
      } as EvalRun);
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '初始化默认配置失败');
  } finally {
    bootstrapSubmitting.value = false;
  }
}

async function cancelRun(row: EvalRun) {
  if (!row.id) {
    return;
  }
  try {
    await ElMessageBox.confirm('确定取消当前评估运行吗？已完成的用例结果不会删除。', '取消评估运行', {
      type: 'warning'
    });
    await evaluationService.cancelRun(row.id);
    ElMessage.success('已提交取消');
    await loadRuns();
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error instanceof Error ? error.message : '取消评估运行失败');
    }
  }
}

function validatePolicy() {
  if (!trimmed(policyForm.policyCode)) return validationError('policyCode', '请填写策略编码');
  if (!trimmed(policyForm.policyName)) return validationError('policyName', '请填写策略名称');
  if (!trimmed(policyForm.subjectType)) return validationError('subjectType', '请填写对象类型');
  return validateJsonFields([
    { field: 'scoreWeightsJson', label: '权重 JSON', value: policyForm.scoreWeightsJson },
    { field: 'graderConfigJson', label: '评分器 JSON', value: policyForm.graderConfigJson },
    { field: 'efficiencyBudgetJson', label: '效率预算 JSON', value: policyForm.efficiencyBudgetJson },
    { field: 'gateRuleJson', label: '门禁规则 JSON', value: policyForm.gateRuleJson },
    { field: 'hardFailRuleJson', label: '硬失败 JSON', value: policyForm.hardFailRuleJson }
  ]);
}

function validateSubject() {
  if (!trimmed(subjectForm.subjectName)) return validationError('subjectName', '请填写对象名称');
  if (!trimmed(subjectForm.subjectType)) return validationError('subjectType', '请填写对象类型');
  if (!trimmed(subjectForm.subjectId)) return validationError('subjectId', '请填写业务对象 ID');
  if (
    (isDataAgentSubject(subjectForm.subjectType) ||
      isEmployeeReleaseSubject(subjectForm.subjectType) ||
      isEmployeeCandidateSubject(subjectForm.subjectType)) &&
    !/^\d+$/.test(trimmed(subjectForm.subjectId))
  ) {
    return validationError(
      'subjectId',
      isEmployeeReleaseSubject(subjectForm.subjectType)
        ? 'DIGITAL_EMPLOYEE_RELEASE 的业务对象 ID 必须是数字员工发布版本 ID'
        : isEmployeeCandidateSubject(subjectForm.subjectType)
          ? 'DIGITAL_EMPLOYEE_CANDIDATE 的业务对象 ID 必须是数字员工 ID'
          : 'DATA_AGENT 对象的业务对象 ID 必须是数字'
    );
  }
  if (!trimmed(subjectForm.adapterCode)) return validationError('adapterCode', '请填写适配器编码');
  return null;
}

function validateSuite() {
  if (!trimmed(suiteForm.suiteName)) return validationError('suiteName', '请填写评估集名称');
  if (!suiteForm.subjectId) return validationError('subjectId', '请选择评估对象');
  if (!suiteForm.policyId) return validationError('policyId', '请选择评估策略');
  return null;
}

function validateCase() {
  if (!caseForm.suiteId) return validationError('suiteId', '请选择评估集');
  if (!trimmed(caseForm.caseName)) return validationError('caseName', '请填写用例名称');
  if (!trimmed(caseForm.userInput)) return validationError('userInput', '请填写用户输入');
  return validateJsonFields([
    { field: 'safetyConstraintsJson', label: '安全约束 JSON', value: caseForm.safetyConstraintsJson },
    { field: 'efficiencyBudgetJson', label: '效率预算 JSON', value: caseForm.efficiencyBudgetJson }
  ]);
}

function validateBootstrap() {
  if (bootstrapOwnerKind.value === 'DIGITAL_EMPLOYEE') {
    if (!bootstrapEmployeeId.value) {
      return validationError('employeeId', '请选择数字员工');
    }
    return null;
  }
  if (!bootstrapAgentId.value) {
    return validationError('agentId', '请选择已发布 Agent');
  }
  return null;
}

function validateJsonFields(fields: Array<{ field: string; label: string; value?: string }>) {
  for (const item of fields) {
    if (!trimmed(item.value)) {
      continue;
    }
    try {
      JSON.parse(item.value as string);
    } catch {
      return validationError(item.field, `${item.label} 不是合法 JSON`);
    }
  }
  return null;
}

function handleRunSuiteChange() {
  const suite = suiteOptions.value.find(item => sameId(item.id, runForm.suiteId));
  if (!suite) {
    return;
  }
  runForm.subjectId = '';
  runForm.policyId = '';
  const subject = subjectOptions.value.find(item => sameId(item.id, suite.subjectId));
  if (isEmployeeCandidateSubject(subject?.subjectType)) {
    runForm.evalMode = EVAL_MODE_INVOKE;
    runForm.executionIntent = EXECUTION_INTENT_DRY_RUN;
    return;
  }
  runForm.evalMode = EVAL_MODE_REPLAY;
  runForm.executionIntent = EXECUTION_INTENT_LIVE;
}

function goResults(row: EvalRun) {
  router.push({
    path: '/ai-agent/evaluation-results',
    query: {
      runId: row.id ? String(row.id) : undefined,
      suiteId: row.suiteId ? String(row.suiteId) : undefined
    }
  });
}

function agentLabel(item: Agent) {
  return `${item.name || item.id || '-'}（${item.id || '-'}）`;
}

function handleSubjectAgentChange(agentId?: EvalId | '') {
  const agent = findPublishedAgent(agentId);
  if (!agent) {
    return;
  }
  subjectForm.subjectName = agent.name || subjectForm.subjectName;
  subjectForm.subjectType = DEFAULT_SUBJECT_TYPE;
  subjectForm.subjectId = agent.id ? String(agent.id) : '';
  subjectForm.adapterCode = DEFAULT_ADAPTER_CODE;
}

function handlePolicySubjectTypeChange(subjectType?: string) {
  if (editingPolicyId.value) {
    return;
  }
  if (isEmployeeCandidateSubject(subjectType)) {
    policyForm.policyCode = 'DIGITAL_EMPLOYEE_DEFAULT';
    policyForm.policyName = '数字员工默认评估策略';
    policyForm.scoreWeightsJson = DEFAULT_SCORE_WEIGHTS_JSON;
    policyForm.efficiencyBudgetJson = DEFAULT_EFFICIENCY_BUDGET_JSON;
    policyForm.graderConfigJson = '{}';
    policyForm.gateRuleJson = '{}';
    policyForm.hardFailRuleJson = '{}';
    return;
  }
  if (isDataAgentSubject(subjectType)) {
    Object.assign(policyForm, defaultPolicyForm(), { subjectType: DEFAULT_SUBJECT_TYPE });
  }
}

async function handleSubjectTypeChange(subjectType?: string) {
  subjectAgentId.value = '';
  subjectEmployeeId.value = '';
  employeeReleaseOptions.value = [];
  subjectForm.subjectId = '';
  if (isEmployeeReleaseSubject(subjectType)) {
    subjectForm.adapterCode = EMPLOYEE_RELEASE_SUBJECT_TYPE;
    return;
  }
  if (isEmployeeCandidateSubject(subjectType)) {
    subjectForm.adapterCode = EMPLOYEE_CANDIDATE_SUBJECT_TYPE;
    return;
  }
  if (isDataAgentSubject(subjectType)) {
    subjectForm.adapterCode = DEFAULT_ADAPTER_CODE;
  }
}

async function handleSubjectEmployeeChange(employeeId?: string) {
  subjectEmployeeId.value = employeeId || '';
  employeeReleaseOptions.value = [];
  if (isEmployeeCandidateSubject(subjectForm.subjectType)) {
    subjectForm.subjectId = employeeId || '';
    subjectForm.adapterCode = EMPLOYEE_CANDIDATE_SUBJECT_TYPE;
    if (employeeId && !trimmed(subjectForm.subjectName)) {
      subjectForm.subjectName = `数字员工 ${employeeId} 评估对象`;
    }
    return;
  }
  subjectForm.subjectId = '';
  if (!subjectEmployeeId.value) {
    return;
  }
  employeeReleaseLoading.value = true;
  try {
    employeeReleaseOptions.value = await agentTaskService.fetchEmployeeReleaseOptions(subjectEmployeeId.value);
  } catch (error) {
    employeeReleaseOptions.value = [];
    ElMessage.error(error instanceof Error ? error.message : '数字员工发布版本加载失败');
  } finally {
    employeeReleaseLoading.value = false;
  }
}

function handleSubjectReleaseChange(releaseId?: string) {
  const release = employeeReleaseOptions.value.find(item => sameId(item.id, releaseId));
  subjectForm.subjectId = releaseId || '';
  subjectForm.adapterCode = EMPLOYEE_RELEASE_SUBJECT_TYPE;
  if (!release) {
    return;
  }
  const versionLabel = typeof release.releaseNo === 'number' ? `v${release.releaseNo}` : String(release.id || '');
  if (!trimmed(subjectForm.subjectName)) {
    subjectForm.subjectName = `数字员工发布 ${versionLabel}`;
  }
}

function employeeReleaseLabel(item: EmployeeReleaseOption) {
  const version = typeof item.releaseNo === 'number' ? `v${item.releaseNo}` : item.id || '-';
  return `${version}（${item.id || '-'}）`;
}

function handleBootstrapAgentChange(agentId?: EvalId | '') {
  bootstrapAgentId.value = agentId || '';
  bootstrapForm.agentId = agentId || '';
}

function findPublishedAgent(agentId?: EvalId | '') {
  return publishedAgentOptions.value.find(item => sameId(item.id, agentId));
}

function resolveAgentIdBySubjectId(subjectId?: string) {
  const agent = publishedAgentOptions.value.find(item => sameId(item.id, subjectId));
  return agent?.id ?? '';
}

function isDataAgentSubject(subjectType?: string) {
  return trimmed(subjectType).toUpperCase() === DEFAULT_SUBJECT_TYPE;
}

function isEmployeeReleaseSubject(subjectType?: string) {
  return trimmed(subjectType).toUpperCase() === EMPLOYEE_RELEASE_SUBJECT_TYPE;
}

function isEmployeeCandidateSubject(subjectType?: string) {
  return trimmed(subjectType).toUpperCase() === EMPLOYEE_CANDIDATE_SUBJECT_TYPE;
}

function validationError(field: string, message: string) {
  return { field, message };
}

function focusFormField(formRef: FormInstance | null, field: string) {
  nextTick(() => {
    formRef?.scrollToField(field);
  });
}

function formatBootstrapResult(result?: EvalBootstrapResult) {
  if (!result) {
    return '初始化完成';
  }
  const items = [
    formatBootstrapItem('策略', result.policy),
    formatBootstrapItem('对象', result.subject),
    formatBootstrapItem('评估集', result.suite),
    formatBootstrapItem('用例', result.evalCase)
  ].filter(Boolean);
  return `初始化完成：${items.join('，') || '无变化'}`;
}

function formatBootstrapItem(label: string, status?: EvalBootstrapItemStatus) {
  if (!status) {
    return '';
  }
  return `${label}${status.status === 'created' ? '已创建' : '已复用'}`;
}

function subjectLabel(item: EvalSubject) {
  return `${item.subjectName || item.subjectId || '-'}（${item.id || '-'}）`;
}

function policyLabel(item: EvalPolicy) {
  return `${item.policyName || item.policyCode || '-'}（v${item.versionNo || 1}）`;
}

function suiteLabel(item: EvalSuite) {
  return `${item.suiteName || '-'}（${item.id || '-'}）`;
}

function resolveSubjectName(id?: EvalId) {
  const subject =
    subjectOptions.value.find(item => sameId(item.id, id)) || subjectRows.value.find(item => sameId(item.id, id));
  return subject ? subjectLabel(subject) : id ? String(id) : '-';
}

function resolvePolicyName(id?: EvalId) {
  const policy =
    policyOptions.value.find(item => sameId(item.id, id)) || policyRows.value.find(item => sameId(item.id, id));
  return policy ? policyLabel(policy) : id ? String(id) : '-';
}

function resolveSuiteName(id?: EvalId) {
  const suite =
    suiteOptions.value.find(item => sameId(item.id, id)) || suiteRows.value.find(item => sameId(item.id, id));
  return suite ? suite.suiteName || String(id) : id ? String(id) : '-';
}

function sameId(left?: EvalId | '', right?: EvalId | '') {
  return String(left || '') === String(right || '');
}

function trimmed(value?: string) {
  return String(value || '').trim();
}

function enabledStatusLabel(value?: string) {
  const labels: Record<string, string> = { enabled: '启用', disabled: '停用' };
  return labels[value || ''] || value || '-';
}

function enabledStatusTag(value?: string) {
  if (value === 'enabled') return 'success';
  if (value === 'disabled') return 'info';
  return undefined;
}

function runStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    queued: '排队中',
    running: '运行中',
    success: '成功',
    failed: '失败',
    timeout: '超时',
    cancelled: '已取消'
  };
  return labels[value || ''] || value || '-';
}

function runStatusTag(value?: string) {
  if (value === 'queued') return 'info';
  if (value === 'success') return 'success';
  if (value === 'failed') return 'danger';
  if (value === 'timeout') return 'danger';
  if (value === 'running') return 'warning';
  if (value === 'cancelled') return 'info';
  return undefined;
}

function formatNumber(value?: number | string | null) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? new Intl.NumberFormat('zh-CN').format(numeric) : '0';
}

function formatScore(value?: number | string | null) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? numeric.toFixed(2) : '-';
}

function formatDateTime(value?: string) {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
}

onMounted(() => {
  loadPolicies();
});

watch(activeTab, async () => {
  await ensureActiveTabLoaded();
});
</script>

<style scoped>
.eval-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.eval-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.eval-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.eval-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: 0;
}

.eval-config-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.config-entry-title,
.config-entry-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.config-entry-title {
  font-size: 15px;
}

.config-entry-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.tooltip-button {
  display: inline-flex;
}

.eval-header-actions,
.panel-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.panel-toolbar {
  margin-bottom: 12px;
}

.eval-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.eval-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.eval-tabs {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column-reverse;
  min-height: 0;
}

.eval-tabs :deep(.el-tabs__header) {
  flex: 0 0 auto;
}

.eval-tabs :deep(.el-tabs__content) {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.eval-tabs :deep(.el-tab-pane) {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.eval-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.eval-table-wrap :deep(.el-table) {
  height: 100%;
}

.panel-toolbar :deep(.el-form-item) {
  margin-bottom: 8px;
}

.w-120 {
  width: 120px;
}

.w-180 {
  width: 180px;
}

.eval-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.dialog-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.dialog-wide {
  grid-column: 1 / -1;
}

.dialog-grid :deep(.el-select),
:deep(.el-dialog .el-select) {
  width: 100%;
}

.field-hint {
  margin-top: 4px;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.4;
}

.violation-danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

@media (max-width: 920px) {
  .eval-header,
  .eval-config-entry,
  .panel-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .config-entry-actions {
    justify-content: flex-start;
  }

  .dialog-grid {
    grid-template-columns: 1fr;
  }

  .w-120,
  .w-180 {
    width: 100%;
  }
}
</style>
