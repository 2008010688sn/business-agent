<template>
  <BaseLayout class="skill-center-shell">
    <main class="skill-center-page">
      <ElCard>
        <header class="page-toolbar">
          <div>
            <h1>技能中心</h1>
            <p>统一维护路由语义、执行模式、确定性 FLOW 和固定工具版本。</p>
          </div>
          <div class="toolbar-actions">
            <ElButton @click="helpVisible = true">
              <ElIcon><InfoFilled /></ElIcon>
              结构说明
            </ElButton>
            <input
              ref="importInputRef"
              class="hidden-file-input"
              type="file"
              accept="application/json,.json"
              @change="importSkill"
            />
            <ElButton @click="importInputRef?.click()">
              <ElIcon><Upload /></ElIcon>
              导入
            </ElButton>
            <ElButton type="primary" @click="openCreate">
              <ElIcon><Plus /></ElIcon>
              新建 Skill
            </ElButton>
          </div>
        </header>
      </ElCard>

      <ElCard class="skill-center-list-card">
        <section class="filter-panel">
          <ElForm :model="query" label-width="70px" @submit.prevent="loadPage">
            <ElRow :gutter="24">
              <ElCol :span="6">
                <ElFormItem label="搜索">
                  <ElInput v-model="query.keyword" clearable placeholder="名称或编码" @keyup.enter="loadPage" />
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="模式">
                  <ElSelect v-model="query.executionMode" clearable placeholder="全部">
                    <ElOption
                      v-for="mode in executionModeOptions"
                      :key="mode.value"
                      :label="mode.label"
                      :value="mode.value"
                    />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="状态">
                  <ElSelect v-model="query.status" clearable placeholder="全部">
                    <ElOption label="仅草稿" value="DRAFT" />
                    <ElOption label="发布版可用" value="PUBLISHED" />
                    <ElOption label="已退役" value="RETIRED" />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElButton type="primary" @click="loadPage">查询</ElButton>
              <ElButton @click="resetQuery">重置</ElButton>
            </ElRow>
          </ElForm>
        </section>

        <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="load-error-alert">
          <ElButton link type="primary" :loading="loading" @click="loadPage">重试</ElButton>
        </ElAlert>

        <div class="skill-center-table-wrap">
          <ElTable
            v-loading="loading"
            :data="rows"
            stripe
            border
            row-key="id"
            height="100%"
            :empty-text="loadError ? '加载失败，数据未获取到' : '暂无 Skill'"
          >
            <ElTableColumn label="Skill" min-width="230">
              <template #default="{ row }">
                <div class="primary-text">{{ row.skillName }}</div>
                <div class="code-text">{{ row.skillCode }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="description" label="说明" min-width="280" show-overflow-tooltip />
            <ElTableColumn label="模式" width="118">
              <template #default="{ row }">
                <ElTag :type="modeTag(row.executionMode)" effect="plain">
                  {{ executionModeLabel(row.executionMode) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="范围" width="104">
              <template #default="{ row }">{{ row.tenantId === '1' ? '平台默认（租户 1）' : '租户' }}</template>
            </ElTableColumn>
            <ElTableColumn label="版本" width="138">
              <template #default="{ row }">{{ publicationState(row).versionLabel }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="188">
              <template #default="{ row }">
                <ElTag :type="publicationTag(publicationState(row).code)">
                  {{ publicationState(row).statusLabel }}
                </ElTag>
                <ElTag v-if="publicationState(row).pendingLabel" type="warning" class="status-pending-tag">
                  {{ publicationState(row).pendingLabel }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <ElButton link type="primary" @click="openEdit(row)">编辑</ElButton>
                  <ElButton link @click="validateSkill(row.skillCode)">校验</ElButton>
                  <ElTooltip
                    :content="row.latestDraftVersionId ? '测试草稿' : '修改后生成草稿才能测试'"
                    placement="top"
                  >
                    <ElButton link :disabled="!row.latestDraftVersionId" @click="openTest(row)">测试</ElButton>
                  </ElTooltip>
                  <ElButton link @click="exportSkill(row.skillCode)">导出</ElButton>
                  <ElTooltip content="克隆已发布版本" placement="top">
                    <ElButton link :disabled="!row.publishedVersionId" @click="openClone(row)">克隆</ElButton>
                  </ElTooltip>
                  <ElTooltip :content="publicationState(row).publishHint" placement="top">
                    <ElButton
                      link
                      type="success"
                      :disabled="!publicationState(row).publishable"
                      @click="publishSkill(row)"
                    >
                      发布
                    </ElButton>
                  </ElTooltip>
                  <ElButton link type="danger" @click="deleteSkill(row)">删除</ElButton>
                </div>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>
        <ElPagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          class="page-pagination"
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          :page-sizes="[10, 20, 50]"
          @change="loadPage"
        />
      </ElCard>
    </main>

    <ElDrawer v-model="helpVisible" title="Skill 结构与使用说明" size="min(620px, 94vw)" append-to-body>
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="Skill 主档">保存稳定编码、名称、作用域、执行模式和版本指针。</ElDescriptionsItem>
        <ElDescriptionsItem label="草稿版本">保存运行说明、路由规则、模式配置、流程定义和变量结构。</ElDescriptionsItem>
        <ElDescriptionsItem label="发布版本">
          发布后内容不可变；新草稿不会自动改变已有 Skill 运行行为。
        </ElDescriptionsItem>
        <ElDescriptionsItem label="工具引用">
          固定到具体工具版本；ReAct 使用只读模型工具，FLOW 按节点调用工具。
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Skill 绑定">
          固定到具体已发布 Skill 版本，需要在技能详情中显式调整。
        </ElDescriptionsItem>
      </ElDescriptions>
      <ElAlert
        class="help-alert"
        type="info"
        :closable="false"
        title="使用顺序：创建草稿 → 配置路由和模式 → 绑定工具 → 校验或试运行 → 发布 → 绑定到 Skill。"
      />
    </ElDrawer>

    <ElDialog v-model="createTemplateVisible" title="新建 Skill" width="min(620px, 92vw)" append-to-body>
      <ElRadioGroup v-model="createTemplate" class="create-template-list">
        <ElRadio label="KNOWLEDGE" border>
          <span class="template-name">空白 KNOWLEDGE</span>
          <span class="template-description">用于知识检索和问答。</span>
        </ElRadio>
        <ElRadio label="REACT" border>
          <span class="template-name">空白 REACT</span>
          <span class="template-description">由模型在已绑定 READ Tool 范围内选择工具。</span>
        </ElRadio>
        <ElRadio label="FLOW_COLLECT" border>
          <span class="template-name">信息采集 FLOW</span>
          <span class="template-description">包含字段收集、文本提交和结束节点。</span>
        </ElRadio>
        <ElRadio label="FLOW_CONFIRM" border>
          <span class="template-name">确认后执行 FLOW</span>
          <span class="template-description">包含收集、确认、WRITE Tool、结果展示和结束节点。</span>
        </ElRadio>
        <ElRadio label="CLONE" border>
          <span class="template-name">克隆已发布 Skill</span>
          <span class="template-description">复制不可变发布版本并创建新草稿。</span>
        </ElRadio>
      </ElRadioGroup>
      <ElSelect
        v-if="createTemplate === 'CLONE'"
        v-model="cloneTemplateCode"
        class="full-width template-source"
        filterable
        placeholder="选择已发布 Skill"
      >
        <ElOption
          v-for="skill in publishedSkillOptions"
          :key="skill.skillCode"
          :label="`${skill.skillName}（${skill.skillCode}）`"
          :value="skill.skillCode"
        />
      </ElSelect>
      <template #footer>
        <ElButton @click="createTemplateVisible = false">取消</ElButton>
        <ElButton type="primary" @click="continueCreateSkill">继续</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="cloneVisible" title="克隆已发布 Skill" width="min(520px, 92vw)" append-to-body>
      <ElForm :model="cloneForm" label-position="top">
        <ElFormItem label="新 Skill 编码" required>
          <ElInput v-model="cloneForm.skillCode" placeholder="只能使用小写字母、数字和连字符" />
        </ElFormItem>
        <ElFormItem label="新 Skill 名称" required>
          <ElInput v-model="cloneForm.skillName" />
        </ElFormItem>
      </ElForm>
      <ElAlert
        :closable="false"
        type="info"
        title="克隆只复制已发布版本内容，原版本保持不可变；新 Skill 会以草稿状态创建。"
      />
      <template #footer>
        <ElButton @click="cloneVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="cloneLoading" @click="cloneSkill">创建草稿</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="editorVisible"
      :title="editingCode ? `编辑 Skill · ${editingCode}` : '新建 Skill'"
      width="min(1080px, 94vw)"
      top="5vh"
      destroy-on-close
    >
      <ElTabs v-model="editorTab" class="editor-tabs">
        <ElTabPane label="基础信息" name="basic">
          <ElAlert
            v-if="editingCode"
            class="draft-isolation-alert"
            type="info"
            :closable="false"
            title="当前编辑未发布草稿；已绑定智能体仍使用各自固定的发布版本。"
          />
          <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" class="form-grid">
            <ElFormItem label="Skill 编码" prop="skillCode">
              <ElInput v-model="form.skillCode" :disabled="Boolean(editingCode)" placeholder="例如 demand-create" />
            </ElFormItem>
            <ElFormItem label="名称" prop="skillName">
              <ElInput v-model="form.skillName" />
            </ElFormItem>
            <ElFormItem label="Skill 类型" prop="skillKind">
              <ElSegmented v-model="form.skillKind" :options="skillKindOptions" @change="handleSkillKindChange" />
            </ElFormItem>
            <ElFormItem label="执行模式" prop="executionMode">
              <ElSegmented v-model="form.executionMode" :options="executionModeOptionsForKind(form.skillKind)" />
            </ElFormItem>
            <ElFormItem label="分类">
              <ElInput v-model="form.category" />
            </ElFormItem>
            <ElFormItem label="排序">
              <ElInputNumber v-model="form.displayOrder" :min="0" :max="9999" controls-position="right" />
            </ElFormItem>
            <ElFormItem label="功能说明" class="span-2">
              <ElInput
                v-model="form.description"
                type="textarea"
                :rows="3"
                placeholder="说明这个 Skill 可以解决什么问题"
              />
            </ElFormItem>
            <ElFormItem label="Skill 运行说明（Markdown）" class="span-2">
              <ElInput
                v-model="form.skillMarkdown"
                type="textarea"
                :rows="8"
                placeholder="使用中文说明适用场景、执行约束和期望结果"
              />
              <ElAlert
                class="skill-markdown-alert"
                type="info"
                :closable="false"
                title="用于每次执行都必须生效的职责边界、固定规则和输出要求；大量按问题匹配的术语与背景请配置为业务知识。"
              />
            </ElFormItem>
          </ElForm>
        </ElTabPane>
        <ElTabPane label="路由规则" name="routing">
          <RouteRuleEditor
            :model-value="skillRouteRules"
            context="skill"
            :skill-kind="form.skillKind"
            :execution-mode="form.executionMode"
            @update:model-value="updateSkillRouteRules"
          />
        </ElTabPane>
        <ElTabPane label="模式配置" name="mode">
          <div v-if="form.executionMode === 'KNOWLEDGE'" class="editor-section">
            <h3>知识检索配置</h3>
            <ElAlert type="info" :closable="false" title="当前运行链路主要读取 topK，取值范围为 1–5。" />
            <JsonObjectEditor
              v-model="form.knowledgeConfig"
              :rows="16"
              placeholder='{"topK":5}'
              @validity-change="skillJsonValidity.knowledgeConfig = $event"
            />
          </div>
          <div v-else-if="form.executionMode === 'REACT'" class="editor-section">
            <h3>ReAct 配置</h3>
            <ElAlert
              type="info"
              :closable="false"
              title="Skill 预算只能收紧平台和 Agent 上限；留空继承当前有效限制。"
            />
            <div class="react-budget-grid">
              <ElFormItem label="最大循环轮数">
                <ElInputNumber
                  :model-value="reactBudgetValue('maxIterations')"
                  :min="1"
                  :max="100"
                  controls-position="right"
                  @update:model-value="setReactBudgetValue('maxIterations', $event)"
                />
              </ElFormItem>
              <ElFormItem label="最大模型调用次数">
                <ElInputNumber
                  :model-value="reactBudgetValue('maxModelCalls')"
                  :min="1"
                  :max="100"
                  controls-position="right"
                  @update:model-value="setReactBudgetValue('maxModelCalls', $event)"
                />
              </ElFormItem>
              <ElFormItem label="最大工具调用次数">
                <ElInputNumber
                  :model-value="reactBudgetValue('maxToolCalls')"
                  :min="1"
                  :max="200"
                  controls-position="right"
                  @update:model-value="setReactBudgetValue('maxToolCalls', $event)"
                />
              </ElFormItem>
              <ElFormItem label="最大 Prompt Token">
                <ElInputNumber
                  :model-value="reactBudgetValue('maxPromptTokens')"
                  :min="1"
                  :max="1000000"
                  :step="1000"
                  controls-position="right"
                  @update:model-value="setReactBudgetValue('maxPromptTokens', $event)"
                />
              </ElFormItem>
            </div>
          </div>
          <div v-if="form.skillKind === 'QUERY'" class="editor-section">
            <h3>数据源与权限配置</h3>
            <ElAlert type="info" :closable="false" title="在资源配置中维护数据源和语义模型；发布版本会固定资源快照。" />
            <ElDivider content-position="left">运行参数</ElDivider>
            <JsonObjectEditor
              v-model="form.runtimeConfig"
              :rows="12"
              placeholder='{"maxRows":200,"queryTimeoutMs":30000}'
              @validity-change="skillJsonValidity.runtimeConfig = $event"
            />
          </div>
          <div v-else-if="form.executionMode === 'FLOW'" class="flow-editor">
            <FlowSchemaEditor v-model="form.flowDefinition" />
            <div class="flow-toolbar">
              <div>
                <h3>确定性流程步骤</h3>
                <span>节点顺序由“下一步”和“分支”配置决定，运行时不执行脚本或 Java 类。</span>
              </div>
              <ElButton @click="addFlowNode">
                <ElIcon><Plus /></ElIcon>
                新增步骤
              </ElButton>
            </div>
            <ElTable :data="flowNodes" border stripe row-key="id">
              <ElTableColumn prop="id" label="节点 ID" min-width="180" />
              <ElTableColumn label="类型" width="140">
                <template #default="{ row }">{{ nodeTypeLabel(row.type) }}</template>
              </ElTableColumn>
              <ElTableColumn prop="next" label="下一步" min-width="160" />
              <ElTableColumn label="配置" min-width="260">
                <template #default="{ row }">
                  <span class="config-summary">{{ summarizeConfig(row.config) }}</span>
                </template>
              </ElTableColumn>
              <ElTableColumn label="操作" width="112">
                <template #default="{ row, $index }">
                  <ElButton link type="primary" @click="editFlowNode(row, $index)">编辑</ElButton>
                  <ElButton link type="danger" @click="removeFlowNode($index)">删除</ElButton>
                </template>
              </ElTableColumn>
            </ElTable>
            <ElDivider content-position="left">完整流程定义（JSON）</ElDivider>
            <JsonObjectEditor
              v-model="form.flowDefinition"
              :rows="14"
              :help="getFlowConfigHelp('flowDefinition')"
              @validity-change="skillJsonValidity.flowDefinition = $event"
            />
          </div>
          <ElAlert v-else type="info" :closable="false" title="默认运行模式继续使用当前 Skill 的既有执行链路。" />
        </ElTabPane>
        <ElTabPane v-if="form.executionMode === 'FLOW'" label="变量结构" name="schema">
          <ElAlert
            v-if="variablesSchemaConflict"
            class="schema-conflict-alert"
            type="error"
            :closable="false"
            show-icon
            title="流程定义中的 variablesSchema 与顶层变量结构不一致，请在下方重新确认后再保存。"
          />
          <JsonObjectEditor
            v-model="flowVariablesSchema"
            :rows="18"
            :help="getFlowConfigHelp('variablesSchema')"
            placeholder='{"type":"object","properties":{}}'
            @validity-change="skillJsonValidity.variablesSchema = $event"
          />
        </ElTabPane>
        <ElTabPane v-if="form.executionMode === 'FLOW'" label="FLOW 运行参数" name="runtime">
          <ElAlert
            type="info"
            :closable="false"
            title="运行参数由后端 FLOW 引擎读取；留空会使用系统默认值，保存空对象表示明确清空。"
          />
          <ElFormItem label="需要管理端审批">
            <ElSwitch
              :model-value="Boolean(form.runtimeConfig && form.runtimeConfig.requireManagerApproval)"
              @update:model-value="setRequireManagerApproval"
            />
            <span class="form-item-tip">默认关闭。打开后确认提交会等待有审批权限的人通过，再自动写入。</span>
          </ElFormItem>
          <JsonObjectEditor
            v-model="form.flowRuntimeConfig"
            :rows="16"
            :help="getFlowConfigHelp('flowRuntimeConfig')"
            placeholder='{"maxParallelResolvers":4,"maxResolverWaves":8,"maxFanOutItems":50,"extraction":{"maxTokens":512,"timeoutMs":8000}}'
            @validity-change="skillJsonValidity.flowRuntimeConfig = $event"
          />
        </ElTabPane>
        <ElTabPane v-if="form.executionMode === 'FLOW'" label="FLOW 业务策略" name="policy">
          <ElAlert
            type="info"
            :closable="false"
            title="业务策略控制输入变更后的依赖失效与集合合并，不由模型自由修改。"
          />
          <JsonObjectEditor
            v-model="form.flowPolicyConfig"
            :rows="16"
            :help="getFlowConfigHelp('flowPolicyConfig')"
            placeholder='{"inputInvalidationRules":[],"collectionMergePolicies":[]}'
            @validity-change="skillJsonValidity.flowPolicyConfig = $event"
          />
        </ElTabPane>
        <ElTabPane v-if="form.executionMode === 'REACT' || form.executionMode === 'FLOW'" label="工具版本" name="tools">
          <div class="tool-ref-toolbar">
            <span>只允许选择已发布的工具版本；Skill 发布后引用固定到具体版本。</span>
            <div class="tool-ref-actions">
              <ElButton :loading="toolContextLoading" @click="refreshToolContext">刷新工具选项</ElButton>
              <ElButton :disabled="!toolRefsLoaded" @click="addToolRef">
                <ElIcon><Plus /></ElIcon>
                新增引用
              </ElButton>
            </div>
          </div>
          <ElAlert v-if="toolContextError" type="error" :closable="false" :title="toolContextError">
            <ElButton link type="primary" @click="refreshToolContext">重试</ElButton>
          </ElAlert>
          <ElTable :data="toolRefs" border stripe>
            <ElTableColumn label="工具版本" min-width="320">
              <template #default="{ row }">
                <ElSelect v-model="row.resourceVersionId" filterable class="full-width" @change="syncToolRef(row)">
                  <ElOption
                    v-for="option in toolVersionOptions"
                    :key="option.resourceVersionId"
                    :label="toolOptionLabel(option)"
                    :value="option.resourceVersionId"
                    :disabled="!option.selectable"
                  />
                </ElSelect>
              </template>
            </ElTableColumn>
            <ElTableColumn label="用途" min-width="220">
              <template #default="{ row }"><ElInput v-model="row.usage" @input="markToolRefsDirty" /></template>
            </ElTableColumn>
            <ElTableColumn label="排序" width="120">
              <template #default="{ row }">
                <ElInputNumber
                  v-model="row.displayOrder"
                  :min="0"
                  controls-position="right"
                  @change="markToolRefsDirty"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="80">
              <template #default="{ $index }">
                <ElButton link type="danger" @click="removeToolRef($index)">删除</ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </ElTabPane>
        <ElTabPane v-if="hasSkillResources" label="资源配置" name="resources" :disabled="!hasPersistedSkill">
          <ElAlert
            v-if="!hasPersistedSkill"
            type="info"
            :closable="false"
            title="先保存 Skill，获得真实 Skill ID 后再配置资源。"
          />
          <ElTabs v-else v-model="resourceTab" class="resource-tabs" @tab-change="activateResourceTab">
            <ElTabPane v-if="supportsQueryResources" label="数据源" name="datasources">
              <SkillDataSourceConfig
                v-if="loadedResourceTabs.has('datasources')"
                v-show="resourceTab === 'datasources'"
                :skill-id="editingSkillId"
                :skill-kind="form.skillKind"
              />
            </ElTabPane>
            <ElTabPane v-if="supportsQueryResources" label="语义模型" name="semantic-models">
              <SkillSemanticsConfig
                v-if="loadedResourceTabs.has('semantic-models')"
                v-show="resourceTab === 'semantic-models'"
                :skill-id="editingSkillId"
              />
            </ElTabPane>
            <ElTabPane v-if="supportsBusinessKnowledgeResources" label="业务知识" name="business-knowledge">
              <ElAlert class="business-knowledge-alert" type="info" :closable="false" :title="businessKnowledgeHelp" />
              <SkillBusinessKnowledgeConfig
                v-if="loadedResourceTabs.has('business-knowledge')"
                v-show="resourceTab === 'business-knowledge'"
                :skill-id="editingSkillId"
              />
            </ElTabPane>
            <ElTabPane v-if="supportsKnowledgeBaseResources" label="知识库" name="knowledge-base">
              <SkillKnowledgeConfig
                v-if="loadedResourceTabs.has('knowledge-base')"
                v-show="resourceTab === 'knowledge-base'"
                :skill-id="editingSkillId"
              />
            </ElTabPane>
          </ElTabs>
        </ElTabPane>
        <ElTabPane label="最终内容预览" name="preview">
          <div class="preview-toolbar">
            <ElRadioGroup v-model="previewVersion" :disabled="!editingCode" size="small">
              <ElRadioButton label="draft">当前草稿</ElRadioButton>
              <ElRadioButton label="published">已发布版本</ElRadioButton>
            </ElRadioGroup>
            <ElButton :loading="previewLoading" :disabled="!editingCode" @click="loadPreview">刷新预览</ElButton>
          </div>
          <ElAlert
            v-if="!editingCode"
            type="info"
            :closable="false"
            title="新建 Skill 尚未保存，当前显示编辑器中的本地草稿；保存后可查看后端有效运行配置。"
          />
          <ElAlert v-if="previewError" type="error" :closable="false" :title="previewError" />
          <template v-if="effectivePreview">
            <ElDescriptions class="preview-summary" :column="4" border>
              <ElDescriptionsItem label="版本">{{ effectivePreview.versionNo || '未保存' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="状态">{{ effectivePreview.status }}</ElDescriptionsItem>
              <ElDescriptionsItem label="模式">
                {{ executionModeLabel(effectivePreview.executionMode) }}
              </ElDescriptionsItem>
              <ElDescriptionsItem label="节点数">{{ effectivePreview.nodes.length }}</ElDescriptionsItem>
            </ElDescriptions>
            <ElAlert
              v-if="effectivePreview.errors.length"
              class="preview-alert"
              type="error"
              :closable="false"
              title="校验错误"
            >
              <ul>
                <li v-for="error in effectivePreview.errors" :key="error">{{ error }}</li>
              </ul>
            </ElAlert>
            <ElAlert
              v-if="effectivePreview.warnings.length"
              class="preview-alert"
              type="warning"
              :closable="false"
              title="运行警告 / IM 兼容提示"
            >
              <ul>
                <li v-for="warning in effectivePreview.warnings" :key="warning">{{ warning }}</li>
              </ul>
            </ElAlert>
            <ElTabs class="preview-tabs">
              <ElTabPane label="SKILL.md" name="markdown">
                <ElAlert class="preview-markdown-effect" type="info" :closable="false" :title="markdownEffectLabel" />
                <ElRow :gutter="16">
                  <ElCol :span="12">
                    <SafeMarkdownContent :content="effectivePreview.skillMarkdown || '暂无 Markdown'" />
                  </ElCol>
                  <ElCol :span="12">
                    <pre class="preview-source">{{ effectivePreview.skillMarkdown || '' }}</pre>
                  </ElCol>
                </ElRow>
              </ElTabPane>
              <ElTabPane label="Skill Package" name="package">
                <JsonObjectEditor :model-value="previewPackage" :rows="22" readonly />
              </ElTabPane>
              <ElTabPane label="FLOW 执行图" name="flow">
                <ElTable :data="effectivePreview.nodes" border stripe>
                  <ElTableColumn prop="nodeId" label="节点" min-width="140" />
                  <ElTableColumn prop="nodeType" label="类型" width="110" />
                  <ElTableColumn label="Tool 版本" min-width="180">
                    <template #default="{ row }">{{ previewNodeToolLabel(row) }}</template>
                  </ElTableColumn>
                  <ElTableColumn prop="next" label="下一步" min-width="120" />
                  <ElTableColumn label="分支" min-width="140">
                    <template #default="{ row }">{{ row.branches.join('、') || '-' }}</template>
                  </ElTableColumn>
                  <ElTableColumn label="模型参与" width="100">
                    <template #default="{ row }">{{ row.modelParticipates ? '是' : '否' }}</template>
                  </ElTableColumn>
                  <ElTableColumn label="写入/确认" width="120">
                    <template #default="{ row }">
                      {{ row.writes ? '写入' : row.requiresConfirmation ? '确认' : '只读' }}
                    </template>
                  </ElTableColumn>
                  <ElTableColumn label="失败路径" min-width="120">
                    <template #default="{ row }">{{ row.failureNext || '-' }}</template>
                  </ElTableColumn>
                </ElTable>
              </ElTabPane>
              <ElTabPane label="运行时有效配置" name="runtime">
                <ElDescriptions :column="1" border>
                  <ElDescriptionsItem label="FLOW 运行参数">
                    <pre class="preview-json">{{ JSON.stringify(effectivePreview.flowRuntimeConfig, null, 2) }}</pre>
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="FLOW 业务策略">
                    <pre class="preview-json">{{ JSON.stringify(effectivePreview.flowPolicyConfig, null, 2) }}</pre>
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="模型参与节点">
                    <span>
                      {{
                        effectivePreview.modelParticipation
                          .filter(item => item.participates)
                          .map(item => item.nodeId)
                          .join('、') || '无'
                      }}
                    </span>
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="后端确定性节点">
                    {{
                      effectivePreview.modelParticipation
                        .filter(item => !item.participates)
                        .map(item => item.nodeId)
                        .join('、') || '无'
                    }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="IM 兼容状态">
                    {{
                      effectivePreview.warnings.some(item => item.startsWith('IM 兼容：'))
                        ? '存在待处理提示'
                        : '通过当前文本交互检查'
                    }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="Skill 绑定版本">
                    {{
                      effectivePreview.status === 'DRAFT'
                        ? '当前草稿不会影响已固定的发布版本'
                        : `Skill 可固定绑定当前 v${effectivePreview.versionNo || '-'} 发布版本`
                    }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="发布资源">
                    数据源 {{ effectivePreview.resourceSummary.datasourceCount }}，语义模型
                    {{ effectivePreview.resourceSummary.semanticModelCount }}，业务知识
                    {{ effectivePreview.resourceSummary.businessKnowledgeCount }}，知识库
                    {{ effectivePreview.resourceSummary.skillKnowledgeCount }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="模型使用阶段">
                    {{ effectivePreview.resourceSummary.modelStages.join('、') || '无模型前置召回' }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem label="资源快照状态">
                    {{ resourceSnapshotStatusLabel(effectivePreview.resourceSummary.snapshotStatus) }}
                  </ElDescriptionsItem>
                  <ElDescriptionsItem v-if="effectivePreview.executionMode === 'REACT'" label="有效 ReAct 预算">
                    {{ formatReactBudget(effectivePreview.effectiveReactBudget) }}
                  </ElDescriptionsItem>
                </ElDescriptions>
                <ElTable class="preview-tool-table" :data="effectivePreview.tools" border stripe>
                  <ElTableColumn prop="resourceKey" label="Tool" min-width="180" />
                  <ElTableColumn label="版本" width="80">
                    <template #default="{ row }">v{{ row.versionNo || '-' }}</template>
                  </ElTableColumn>
                  <ElTableColumn label="访问" width="120">
                    <template #default="{ row }">{{ row.accessMode || '-' }}/{{ row.exposureMode || '-' }}</template>
                  </ElTableColumn>
                  <ElTableColumn prop="permissionCode" label="权限编码" min-width="150" />
                  <ElTableColumn label="写入" width="70">
                    <template #default="{ row }">{{ row.write ? '是' : '否' }}</template>
                  </ElTableColumn>
                  <ElTableColumn label="确认/幂等" width="110">
                    <template #default="{ row }">
                      {{ row.confirmRequired ? '确认' : '无' }}/{{ row.idempotencyRequired ? '幂等' : '无' }}
                    </template>
                  </ElTableColumn>
                </ElTable>
              </ElTabPane>
            </ElTabs>
          </template>
        </ElTabPane>
      </ElTabs>
      <template #footer>
        <ElButton @click="editorVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="saving"
          :disabled="!activeSkillJsonValid || !skillRouteRulesValid"
          @click="saveSkill"
        >
          保存草稿
        </ElButton>
      </template>
    </ElDialog>

    <ElDrawer v-model="nodeDrawerVisible" title="FLOW 节点" size="520px" append-to-body destroy-on-close>
      <FlowNodeEditor
        :model-value="nodeDraft"
        :node-types="nodeTypes"
        :node-type-labels="nodeTypeLabels"
        :disabled-id="nodeEditIndex >= 0"
        @update:model-value="Object.assign(nodeDraft, $event)"
        @validity-change="handleNodeValidity"
      />
      <FlowActionEditor v-model="nodeActions" :node-type="String(nodeDraft.type || '')" />
      <template #footer>
        <ElButton type="primary" :disabled="!nodeJsonValid" @click="commitFlowNode">保存节点</ElButton>
      </template>
    </ElDrawer>

    <ElDialog
      v-model="testVisible"
      :title="`Skill 测试 · ${testingSkill?.skillCode || ''}`"
      width="min(920px, 94vw)"
      destroy-on-close
    >
      <ElForm label-position="top">
        <ElFormItem label="用户消息">
          <ElInput v-model="routeTestQuery" placeholder="输入要验证的路由消息" @keyup.enter="runRouteTest" />
        </ElFormItem>
        <ElButton type="primary" :loading="routeTesting" @click="runRouteTest">测试路由</ElButton>
      </ElForm>
      <ElDescriptions v-if="routeTestResult" class="test-result-block" :column="2" border>
        <ElDescriptionsItem label="结果">
          <ElTag :type="routeTestResult.matched ? 'success' : 'info'">
            {{ routeTestResult.matched ? '命中' : '未命中' }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="原因码">{{ routeTestResult.reasonCode }}</ElDescriptionsItem>
        <ElDescriptionsItem label="词法得分">{{ routeTestResult.lexicalScore }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Skill">{{ routeTestResult.skillCode }}</ElDescriptionsItem>
        <ElDescriptionsItem label="精确命中">{{ routeTestResult.exact ? '是' : '否' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="已硬排除">{{ routeTestResult.excluded ? '是' : '否' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="命中信号" :span="2">
          {{ routeTestResult.matchedSignals?.join('、') || '-' }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <template v-if="testingSkill?.executionMode === 'FLOW'">
        <ElDivider content-position="left">FLOW dry-run</ElDivider>
        <ElButton :loading="flowTesting" @click="runFlowTest">生成执行预览</ElButton>
        <ElAlert
          v-if="flowTestResult && !flowTestResult.valid"
          class="test-result-block"
          type="error"
          :closable="false"
          :title="flowTestResult.errors.join('；')"
        />
        <ElTable v-if="flowTestResult" class="test-result-block" :data="flowTestResult.nodes" border stripe>
          <ElTableColumn prop="nodeId" label="节点" min-width="150" />
          <ElTableColumn prop="nodeType" label="类型" width="110" />
          <ElTableColumn label="dry-run 行为" width="150">
            <template #default="{ row }">
              <ElTag
                :type="row.behavior === 'BLOCKED_WRITE' ? 'danger' : row.behavior === 'READ_TOOL' ? 'success' : 'info'"
                effect="plain"
              >
                {{ row.behavior }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="next" label="下一步" min-width="140" />
          <ElTableColumn label="分支" min-width="180">
            <template #default="{ row }">{{ row.branches.join(', ') || '-' }}</template>
          </ElTableColumn>
        </ElTable>
      </template>
    </ElDialog>

    <ElDialog v-model="validationVisible" title="Skill 校验结果" width="min(820px, 94vw)" append-to-body>
      <ElAlert
        :type="validationResult?.valid ? 'success' : 'error'"
        :closable="false"
        :title="validationResult?.valid ? '校验通过' : '存在阻止发布的问题'"
      />
      <ElTable class="validation-table" :data="validationResult?.issues || []" border stripe>
        <ElTableColumn prop="severity" label="级别" width="90" />
        <ElTableColumn prop="nodeId" label="节点" width="140" />
        <ElTableColumn prop="path" label="配置位置" min-width="180" />
        <ElTableColumn prop="message" label="问题" min-width="300" />
        <ElTableColumn label="操作" width="80">
          <template #default="{ row }">
            <ElButton link type="primary" @click="openValidationIssue(row)">定位</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElAlert
        v-if="validationResult?.warnings.length"
        class="validation-warning"
        type="warning"
        :closable="false"
        :title="validationResult.warnings.join('；')"
      />
    </ElDialog>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, toRaw, watch } from 'vue';
import { InfoFilled, Plus, Upload } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { isEqual } from 'lodash-es';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import RouteRuleEditor from '@/views/ai-agent/components/routing/RouteRuleEditor.vue';
import {
  emptyRouteRules,
  parseRouteRules,
  routeRulesPayloadForSave,
  type RouteRules
} from '@/views/ai-agent/utils/routeRules';
import { getFlowConfigHelp } from '@/views/ai-agent/skills/flowConfigHelp';
import SafeMarkdownContent from '@/views/ai-agent/components/run/SafeMarkdownContent.vue';
import FlowActionEditor from '@/views/ai-agent/skills/FlowActionEditor.vue';
import FlowNodeEditor from '@/views/ai-agent/skills/FlowNodeEditor.vue';
import FlowSchemaEditor from '@/views/ai-agent/skills/FlowSchemaEditor.vue';
import SkillDataSourceConfig from '@/views/ai-agent/components/agent/DataSourceConfig.vue';
import SkillSemanticsConfig from '@/views/ai-agent/components/agent/SemanticsConfig.vue';
import SkillBusinessKnowledgeConfig from '@/views/ai-agent/components/agent/BusinessKnowledgeConfig.vue';
import SkillKnowledgeConfig from '@/views/ai-agent/components/agent/SkillKnowledgeConfig.vue';
import skillService, {
  type SkillCatalogItem,
  type SkillExecutionMode,
  type FlowValidationIssue,
  type SkillFlowTestResult,
  type SkillKind,
  type SkillPackageBundle,
  type SkillRouteTestResult,
  type SkillSavePayload,
  type SkillPreview,
  type SkillToolRefSave,
  type SkillValidationResult,
  type ToolVersionOption
} from '@/views/ai-agent/services/skill';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import { resolveSkillPublicationState, type SkillPublicationCode } from '@/views/ai-agent/utils/skillPublicationState';

defineOptions({ name: 'SkillCenter' });

const executionModeOptions = [
  { label: '知识问答', value: 'KNOWLEDGE' },
  { label: '确定性查询', value: 'DETERMINISTIC' },
  { label: '受限模型工具调用', value: 'REACT' },
  { label: '确定性流程', value: 'FLOW' }
];
const skillKindOptions = [
  { label: '数据查询', value: 'QUERY' },
  { label: '知识问答', value: 'QA' },
  { label: '业务动作', value: 'ACTION' },
  { label: '多步骤编排', value: 'ORCHESTRATION' }
];
const allowedExecutionModes: Record<SkillKind, SkillExecutionMode[]> = {
  QUERY: ['DETERMINISTIC', 'REACT'],
  QA: ['KNOWLEDGE'],
  ACTION: ['FLOW'],
  ORCHESTRATION: ['FLOW']
};
const executionModeOptionsForKind = (skillKind: SkillKind | string) => {
  const modes = allowedExecutionModes[skillKind as SkillKind] || [];
  return executionModeOptions.filter(option => modes.includes(option.value as SkillExecutionMode));
};
const nodeTypes = [
  'extract',
  'collect',
  'resolve',
  'select',
  'review',
  'merge',
  'validate',
  'switch',
  'confirm',
  'execute',
  'present',
  'handoff',
  'end',
  'error'
];
const nodeTypeLabels: Record<string, string> = {
  extract: '提取信息',
  collect: '收集信息',
  resolve: '查询候选',
  select: '选择候选',
  review: '用户修改',
  merge: '合并数据',
  validate: '校验数据',
  switch: '条件分支',
  confirm: '用户确认',
  execute: '执行工具',
  present: '展示结果',
  handoff: '转人工',
  end: '结束',
  error: '错误处理'
};
const executionModeLabel = (value: string) => executionModeOptions.find(item => item.value === value)?.label || value;
const nodeTypeLabel = (value: string) => nodeTypeLabels[value] || value;
const query = reactive({ current: 1, size: 20, keyword: '', status: '', executionMode: '' });
const rows = ref<SkillCatalogItem[]>([]);
const total = ref(0);
const loading = ref(false);
/** 列表加载失败原因；非空时表格顶部常驻错误态，与「确实没有数据」区分开 */
const loadError = ref('');
const saving = ref(false);
const editorVisible = ref(false);
const cloneVisible = ref(false);
const cloneLoading = ref(false);
const cloneSource = ref<SkillCatalogItem | null>(null);
const cloneForm = reactive({ skillCode: '', skillName: '' });
type CreateTemplate = 'KNOWLEDGE' | 'REACT' | 'FLOW_COLLECT' | 'FLOW_CONFIRM' | 'CLONE';
const createTemplateVisible = ref(false);
const createTemplate = ref<CreateTemplate>('KNOWLEDGE');
const cloneTemplateCode = ref('');
const publishedSkillOptions = computed(() => rows.value.filter(row => Boolean(row.publishedVersionId)));
const helpVisible = ref(false);
const editorTab = ref('basic');
const editingCode = ref('');
const editingSkillId = ref('');
type ResourceTab = 'datasources' | 'semantic-models' | 'business-knowledge' | 'knowledge-base';
const resourceTab = ref<ResourceTab>('datasources');
const loadedResourceTabs = ref<Set<ResourceTab>>(new Set());
const formRef = ref<FormInstance>();
type EditableToolRef = SkillToolRefSave & { resourceKey?: string };
const toolRefs = ref<EditableToolRef[]>([]);
const toolVersionOptions = ref<ToolVersionOption[]>([]);
const toolRefsLoaded = ref(false);
const toolRefsDirty = ref(false);
const toolContextLoading = ref(false);
const toolContextError = ref('');
let editorRequestId = 0;
let toolContextRequestId = 0;
const nodeDrawerVisible = ref(false);
const nodeEditIndex = ref(-1);
const skillJsonValidity = reactive({
  routeRules: true,
  knowledgeConfig: true,
  reactConfig: true,
  flowDefinition: true,
  variablesSchema: true,
  flowRuntimeConfig: true,
  flowPolicyConfig: true,
  runtimeConfig: true
});
const nodeJsonValidity = reactive({ config: true, branches: true });
const nodeJsonValid = computed(() => Object.values(nodeJsonValidity).every(Boolean));
const importInputRef = ref<HTMLInputElement>();
const previewVersion = ref<'draft' | 'published'>('draft');
const previewLoading = ref(false);
const previewError = ref('');
const previewData = ref<SkillPreview | null>(null);
const testVisible = ref(false);
const testingSkill = ref<SkillCatalogItem | null>(null);
const routeTestQuery = ref('');
const routeTesting = ref(false);
const routeTestResult = ref<SkillRouteTestResult | null>(null);
const flowTesting = ref(false);
const flowTestResult = ref<SkillFlowTestResult | null>(null);
const validationVisible = ref(false);
const validationResult = ref<SkillValidationResult | null>(null);
const validationSkill = ref<SkillCatalogItem | null>(null);
const nodeDraft = reactive({
  id: '',
  type: 'collect',
  next: '',
  config: {} as Record<string, unknown>,
  branches: [] as unknown[]
});
const nodeOriginal = ref<Record<string, any>>({});
const nodeActions = computed<Array<Record<string, unknown>>>({
  get: () =>
    (Array.isArray(nodeDraft.config.uiActions) ? nodeDraft.config.uiActions : []) as Array<Record<string, unknown>>,
  set: value => {
    nodeDraft.config = { ...nodeDraft.config, uiActions: value };
  }
});
const handleNodeValidity = (field: 'config' | 'branches', value: boolean) => {
  nodeJsonValidity[field] = value;
};

const emptyForm = (): SkillSavePayload => ({
  skillCode: '',
  skillName: '',
  description: '',
  category: '',
  scope: 'TENANT',
  skillKind: 'QA',
  executionMode: 'KNOWLEDGE',
  displayOrder: 0,
  skillMarkdown: '',
  routeRules: emptyRouteRules(),
  knowledgeConfig: {},
  reactConfig: {},
  runtimeConfig: {},
  flowDefinition: {
    schemaVersion: 'skill-flow/v2',
    startNode: '',
    interruptPolicy: 'ASK',
    variablesSchema: {},
    nodes: []
  },
  variablesSchema: {},
  flowRuntimeConfig: {},
  flowPolicyConfig: {},
  resourceRequirement: {},
  inputSchema: { type: 'object', properties: {} },
  outputSchema: { type: 'object', properties: {} },
  toolRefs: null
});
const loadedVariablesSchemaConflict = ref(false);
const form = reactive<SkillSavePayload>(emptyForm());
const skillRouteRules = ref<unknown>(emptyRouteRules());
const skillRouteRulesDirty = ref(false);
const skillRouteRulesParseResult = computed(() => parseRouteRules(skillRouteRules.value));
const skillRouteRulesValid = computed(() => {
  const parsed = skillRouteRulesParseResult.value;
  if (parsed.status === 'invalid') {
    return false;
  }
  return (
    !parsed.rules.allowFlowAutoSelect ||
    (['ACTION', 'ORCHESTRATION'].includes(form.skillKind) && form.executionMode === 'FLOW')
  );
});
const updateSkillRouteRules = (rules: RouteRules) => {
  skillRouteRules.value = rules;
  form.routeRules = rules;
  skillRouteRulesDirty.value = true;
};
type ReactBudgetKey = 'maxIterations' | 'maxModelCalls' | 'maxToolCalls' | 'maxPromptTokens';
const reactBudgetValue = (key: ReactBudgetKey): number | undefined => {
  const value = form.reactConfig?.[key];
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
};
const setRequireManagerApproval = (value: boolean) => {
  form.runtimeConfig = {
    ...(form.runtimeConfig || {}),
    requireManagerApproval: value
  };
};
const setReactBudgetValue = (key: ReactBudgetKey, value: number | null | undefined) => {
  const next = { ...(form.reactConfig || {}) };
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) next[key] = Math.trunc(value);
  form.reactConfig =
    typeof value === 'number' && Number.isFinite(value) && value > 0
      ? next
      : Object.fromEntries(Object.entries(next).filter(([entryKey]) => entryKey !== key));
  skillJsonValidity.reactConfig = true;
};
const hasPersistedSkill = computed(() => Boolean(editingSkillId.value));
const supportsQueryResources = computed(
  () => form.skillKind === 'QUERY' && ['DETERMINISTIC', 'REACT'].includes(form.executionMode)
);
const supportsBusinessKnowledgeResources = computed(
  () =>
    supportsQueryResources.value ||
    (['ACTION', 'ORCHESTRATION'].includes(form.skillKind) && form.executionMode === 'FLOW')
);
const supportsKnowledgeBaseResources = computed(() => form.skillKind === 'QA' && form.executionMode === 'KNOWLEDGE');
const hasSkillResources = computed(
  () => supportsQueryResources.value || supportsBusinessKnowledgeResources.value || supportsKnowledgeBaseResources.value
);
const availableResourceTabs = computed<ResourceTab[]>(() => {
  const tabs: ResourceTab[] = [];
  if (supportsQueryResources.value) {
    tabs.push('datasources', 'semantic-models', 'business-knowledge');
  }
  if (!supportsQueryResources.value && supportsBusinessKnowledgeResources.value) {
    tabs.push('business-knowledge');
  }
  if (supportsKnowledgeBaseResources.value) {
    tabs.push('knowledge-base');
  }
  return tabs;
});
const ensureResourceTab = () => {
  const tabs = availableResourceTabs.value;
  if (tabs.length > 0 && !tabs.includes(resourceTab.value)) {
    resourceTab.value = tabs[0];
  }
};
const activateResourceTab = (tab: string | number = resourceTab.value) => {
  if (!hasPersistedSkill.value) {
    return;
  }
  const selectedTab = String(tab) as ResourceTab;
  if (!availableResourceTabs.value.includes(selectedTab)) {
    return;
  }
  resourceTab.value = selectedTab;
  if (!loadedResourceTabs.value.has(selectedTab)) {
    loadedResourceTabs.value = new Set([...loadedResourceTabs.value, selectedTab]);
  }
};
const isLegalSkillMatrix = () => {
  const modes = allowedExecutionModes[form.skillKind as SkillKind] || [];
  return modes.includes(form.executionMode as SkillExecutionMode);
};
const handleSkillKindChange = () => {
  const modes = allowedExecutionModes[form.skillKind as SkillKind] || [];
  if (!modes.includes(form.executionMode as SkillExecutionMode)) {
    form.executionMode = modes[0] || 'KNOWLEDGE';
  }
  ensureResourceTab();
};
const nodeUsesModel = (node: any) =>
  ['extract', 'collect', 'review', 'validate'].includes(String(node?.type || '').toLowerCase()) ||
  (String(node?.type || '').toLowerCase() === 'resolve' && Boolean(node?.config?.forEach));
const businessKnowledgeHelp = computed(() =>
  form.executionMode === 'FLOW'
    ? '业务知识不绑定数据源，只在模型字段抽取前按需召回；不会控制 FLOW 节点跳转、权限、确认、幂等或写操作策略。'
    : '业务知识不绑定数据源，适合大量可检索术语、同义词和业务背景；配置并发布后会在模型调用前自动按需召回。'
);
const localPreview = computed<SkillPreview>(() => {
  const definition = (form.flowDefinition || {}) as Record<string, any>;
  const nodes = Array.isArray(definition.nodes) ? definition.nodes : [];
  return {
    skillCode: form.skillCode,
    skillName: form.skillName,
    executionMode: String(form.executionMode),
    status: 'UNSAVED',
    skillMarkdown: form.skillMarkdown,
    routeRules: skillRouteRulesParseResult.value.rules,
    manifest: {
      schemaVersion: 'skill-package/v1',
      skillCode: form.skillCode,
      name: form.skillName,
      executionMode: form.executionMode,
      routeRules: form.routeRules || {},
      variablesSchema: form.variablesSchema || {},
      flowRuntimeConfig: form.flowRuntimeConfig || {},
      flowPolicyConfig: form.flowPolicyConfig || {},
      runtimeConfig: form.runtimeConfig || {}
    },
    flowDefinition: definition,
    flowRuntimeConfig: form.flowRuntimeConfig || {},
    flowPolicyConfig: form.flowPolicyConfig || {},
    tools: [],
    nodes: nodes.map((node: any) => ({
      nodeId: node.id || '',
      nodeType: node.type || '',
      resourceVersionId: node.config?.resourceVersionId,
      next: node.next,
      branches: Array.isArray(node.branches)
        ? node.branches.map((branch: any) => branch.next || branch.target).filter(Boolean)
        : [],
      modelParticipates: nodeUsesModel(node),
      writes: node.type === 'execute',
      requiresConfirmation: node.type === 'confirm' || node.type === 'execute',
      failureNext: node.config?.invalidNext || node.config?.emptyNext
    })),
    modelParticipation: nodes.map((node: any) => ({
      nodeId: node.id || '',
      participates: nodeUsesModel(node),
      reason: nodeUsesModel(node) ? '模型按配置路径参与有限抽取' : '节点由后端确定性执行'
    })),
    errors: [],
    warnings: [],
    resourceSummary: {
      datasourceCount: 0,
      semanticModelCount: 0,
      businessKnowledgeCount: 0,
      skillKnowledgeCount: 0,
      modelStages:
        form.executionMode === 'FLOW'
          ? ['FLOW模型字段抽取前（仅模型节点）']
          : form.executionMode === 'DETERMINISTIC'
            ? ['SQL规划前']
            : form.executionMode === 'REACT'
              ? ['ReAct启动前']
              : ['回答模型前'],
      snapshotStatus: 'UNSAVED'
    },
    effectiveReactBudget: {
      maxIterations: reactBudgetValue('maxIterations') || 5,
      maxModelCalls: reactBudgetValue('maxModelCalls') || 5,
      maxToolCalls: reactBudgetValue('maxToolCalls') || 6,
      maxPromptTokens: reactBudgetValue('maxPromptTokens') || 30000
    }
  };
});
const effectivePreview = computed(() => previewData.value || localPreview.value);
const previewPackage = computed<Record<string, unknown>>(() => ({
  'manifest.yaml': effectivePreview.value.manifest,
  'SKILL.md': effectivePreview.value.skillMarkdown || '',
  'flow.yaml': effectivePreview.value.flowDefinition,
  'tool-refs.json': effectivePreview.value.tools
}));
const variablesSchemaConflict = computed(() => {
  if (form.executionMode !== 'FLOW') return false;
  const flowDefinition = form.flowDefinition;
  const currentConflict = Boolean(
    flowDefinition &&
      Object.hasOwn(flowDefinition, 'variablesSchema') &&
      !isEqual(flowDefinition.variablesSchema, form.variablesSchema || {})
  );
  return loadedVariablesSchemaConflict.value || currentConflict;
});
const flowVariablesSchema = computed<Record<string, unknown>>({
  get: () => (form.flowDefinition?.variablesSchema as Record<string, unknown>) || form.variablesSchema || {},
  set: value => {
    form.flowDefinition = { ...(form.flowDefinition || {}), variablesSchema: value };
    form.variablesSchema = value;
    loadedVariablesSchemaConflict.value = false;
  }
});
const activeSkillJsonValid = computed(() => {
  if (!isLegalSkillMatrix()) return false;
  if (!skillRouteRulesValid.value) return false;
  if (form.executionMode === 'KNOWLEDGE' && !skillJsonValidity.knowledgeConfig) return false;
  if (form.executionMode === 'REACT' && !skillJsonValidity.reactConfig) return false;
  if (form.skillKind === 'QUERY' && !skillJsonValidity.runtimeConfig) return false;
  if (form.executionMode === 'FLOW') {
    return (
      skillJsonValidity.flowDefinition &&
      skillJsonValidity.variablesSchema &&
      skillJsonValidity.flowRuntimeConfig &&
      skillJsonValidity.flowPolicyConfig &&
      !variablesSchemaConflict.value
    );
  }
  return true;
});
const rules: FormRules = {
  skillCode: [{ required: true, message: '请输入 Skill 编码', trigger: 'blur' }],
  skillName: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  skillKind: [{ required: true }],
  executionMode: [{ required: true }]
};

const flowNodes = computed<any[]>(() =>
  Array.isArray(form.flowDefinition?.nodes) ? (form.flowDefinition.nodes as any[]) : []
);
const modeTag = (mode: string) =>
  mode === 'FLOW' ? 'warning' : mode === 'REACT' ? 'success' : mode === 'DETERMINISTIC' ? 'primary' : undefined;
const publicationState = (skill: SkillCatalogItem) => resolveSkillPublicationState(skill);
const publicationTag = (code: SkillPublicationCode) => {
  if (code === 'PUBLISHED' || code === 'PUBLISHED_WITH_DRAFT') return 'success';
  if (code === 'RETIRED') return 'info';
  if (code === 'INVALID') return 'danger';
  return 'warning';
};
const previewNodeToolLabel = (node: SkillPreview['nodes'][number]) => {
  if (!node.resourceVersionId) return '-';
  const tool = effectivePreview.value.tools.find(item => item.resourceVersionId === node.resourceVersionId);
  return tool ? `${tool.resourceKey} v${tool.versionNo || '-'}` : `版本 ID ${node.resourceVersionId}`;
};
const markdownEffectLabel = computed(() => {
  const mode = effectivePreview.value.executionMode;
  if (mode === 'KNOWLEDGE') return 'SKILL.md 会作为知识问答的 Skill 指令参与模型提示。';
  if (mode === 'FLOW') return 'SKILL.md 用于版本说明和 Package 交付，不控制 FLOW 节点跳转、权限或 Tool 执行。';
  return 'SKILL.md 会在 Skill 路由命中后作为运行指令注入执行上下文。';
});
const resourceSnapshotStatusLabels: Record<string, string> = {
  PUBLISHED_SNAPSHOT: '已固定到发布版本',
  DRAFT_RESOURCES: '按当前草稿资源解析',
  UNSAVED: '尚未保存'
};
const resourceSnapshotStatusLabel = (value: string) => resourceSnapshotStatusLabels[value] || value;
const formatReactBudget = (budget: Record<string, number>) =>
  `循环 ${budget.maxIterations ?? '-'} / 模型 ${budget.maxModelCalls ?? '-'} / 工具 ${budget.maxToolCalls ?? '-'} / Prompt Token ${budget.maxPromptTokens ?? '-'}`;
const summarizeConfig = (config: unknown) => {
  const text = JSON.stringify(config || {});
  return text.length > 80 ? `${text.slice(0, 80)}...` : text;
};

const loadPage = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    const page = await skillService.page(query);
    rows.value = page.data;
    total.value = page.total;
  } catch (error) {
    loadError.value = extractApiErrorMessage(error, '加载 Skill 失败');
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(loadError.value);
    }
    rows.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
};
const resetQuery = () => {
  Object.assign(query, { current: 1, keyword: '', status: '', executionMode: '' });
  loadPage();
};
const toolAccessLabel = (value: string) => (value === 'READ' ? '只读' : value === 'WRITE' ? '写入' : value);
const toolExposureLabel = (value: string) =>
  value === 'MODEL' ? '模型可调用' : value === 'FLOW_ONLY' ? '仅流程调用' : value;
const toolOptionLabel = (option: ToolVersionOption) => {
  const unavailable = option.unavailableReason ? `（${option.unavailableReason}）` : '';
  return `${option.resourceName || option.resourceKey} · v${option.versionNo} · ${toolAccessLabel(option.accessMode)}/${toolExposureLabel(option.exposureMode)}${unavailable}`;
};
const loadToolContext = async (force = false) => {
  if (!['REACT', 'FLOW'].includes(form.executionMode)) return;
  if (toolRefsLoaded.value && !force) return;
  const requestId = ++toolContextRequestId;
  toolContextLoading.value = true;
  toolContextError.value = '';
  try {
    const context = await skillService.toolEditorContext({
      skillCode: editingCode.value || undefined,
      executionMode: form.executionMode
    });
    if (requestId !== toolContextRequestId) return;
    const optionMap = new Map(context.options.map(option => [option.resourceVersionId, option]));
    toolVersionOptions.value = context.options;
    toolRefs.value = context.refs.map(ref => ({
      ...ref,
      resourceKey: optionMap.get(ref.resourceVersionId)?.resourceKey
    }));
    toolRefsLoaded.value = true;
    toolRefsDirty.value = false;
  } catch (error) {
    if (requestId === toolContextRequestId) toolContextError.value = extractApiErrorMessage(error, '加载工具选项失败');
  } finally {
    if (requestId === toolContextRequestId) toolContextLoading.value = false;
  }
};
const refreshToolContext = async () => {
  if (toolRefsDirty.value) {
    try {
      await ElMessageBox.confirm('刷新会覆盖当前未保存的工具引用修改，是否继续？', '刷新工具选项', { type: 'warning' });
    } catch {
      return;
    }
  }
  await loadToolContext(true);
};
const loadPreview = async () => {
  if (!editingCode.value) {
    previewData.value = null;
    previewError.value = '';
    return;
  }
  previewLoading.value = true;
  previewError.value = '';
  try {
    previewData.value = await skillService.preview(editingCode.value, previewVersion.value);
  } catch (error) {
    previewData.value = null;
    previewError.value = extractApiErrorMessage(error, '加载 Skill 最终预览失败');
  } finally {
    previewLoading.value = false;
  }
};

const resetForm = () => {
  toolContextRequestId += 1;
  loadedVariablesSchemaConflict.value = false;
  Object.assign(form, emptyForm());
  skillRouteRules.value = form.routeRules || emptyRouteRules();
  skillRouteRulesDirty.value = false;
  toolRefs.value = [];
  toolVersionOptions.value = [];
  toolRefsLoaded.value = false;
  toolRefsDirty.value = false;
  toolContextError.value = '';
  toolContextLoading.value = false;
  previewData.value = null;
  previewError.value = '';
  previewVersion.value = 'draft';
  editorTab.value = 'basic';
  resourceTab.value = 'datasources';
  loadedResourceTabs.value = new Set();
  Object.keys(skillJsonValidity).forEach(key => {
    skillJsonValidity[key as keyof typeof skillJsonValidity] = true;
  });
};
const openCreate = () => {
  createTemplate.value = 'KNOWLEDGE';
  cloneTemplateCode.value = '';
  createTemplateVisible.value = true;
};
const flowTemplate = (template: 'FLOW_COLLECT' | 'FLOW_CONFIRM') => {
  const inputSchema = {
    type: 'object',
    properties: { content: { type: 'string', title: '待处理内容' } }
  };
  const nodes: Array<Record<string, unknown>> = [
    {
      id: 'collect-input',
      type: 'collect',
      next: template === 'FLOW_CONFIRM' ? 'confirm-input' : 'end',
      config: {
        requiredPaths: ['/input/content'],
        schema: inputSchema,
        prompt: '请提供待处理内容。',
        collectionPresentation: {
          mode: 'MISSING_ONLY',
          fieldPrompts: { '/input/content': '请提供待处理内容。' }
        },
        extraction: {
          modelPolicy: 'IF_UNRESOLVED',
          schemaMode: 'UNRESOLVED_REQUIRED',
          failurePolicy: 'WAIT_RETRY'
        },
        uiActions: [{ actionId: 'submit-input', type: 'SUBMIT', label: '提交' }]
      }
    }
  ];
  if (template === 'FLOW_CONFIRM') {
    nodes.push(
      {
        id: 'confirm-input',
        type: 'confirm',
        next: 'execute-tool',
        config: {
          summaryPath: '/input',
          summary: '请确认是否执行。',
          editNode: 'collect-input',
          uiActions: [
            { actionId: 'confirm-submit', type: 'CONFIRM', label: '确认执行', value: true },
            { actionId: 'edit-flow', type: 'EDIT', label: '返回修改' }
          ]
        }
      },
      {
        id: 'execute-tool',
        type: 'execute',
        next: 'present-result',
        config: {
          resourceVersionId: '',
          argumentMappings: { content: '/input/content' },
          outputPath: '/result',
          successCondition: { op: 'notEmpty', path: '/result' }
        }
      },
      {
        id: 'present-result',
        type: 'present',
        next: 'end',
        config: { dataPath: '/result', text: '处理完成。' }
      }
    );
  }
  nodes.push({ id: 'end', type: 'end', config: { text: '流程已完成。' } });
  return {
    schemaVersion: 'skill-flow/v2',
    startNode: 'collect-input',
    interruptPolicy: 'ASK',
    variablesSchema: {
      type: 'object',
      properties: { input: inputSchema }
    },
    nodes
  };
};
const continueCreateSkill = () => {
  if (createTemplate.value === 'CLONE') {
    const source = publishedSkillOptions.value.find(row => row.skillCode === cloneTemplateCode.value);
    if (!source) {
      ElMessage.warning('请选择要克隆的已发布 Skill');
      return;
    }
    createTemplateVisible.value = false;
    openClone(source);
    return;
  }
  editorRequestId += 1;
  editingCode.value = '';
  editingSkillId.value = '';
  resetForm();
  if (createTemplate.value === 'REACT') {
    form.skillKind = 'QUERY';
    form.executionMode = 'REACT';
  }
  if (createTemplate.value === 'FLOW_COLLECT' || createTemplate.value === 'FLOW_CONFIRM') {
    const definition = flowTemplate(createTemplate.value);
    form.skillKind = createTemplate.value === 'FLOW_CONFIRM' ? 'ACTION' : 'ORCHESTRATION';
    form.executionMode = 'FLOW';
    form.flowDefinition = definition;
    form.variablesSchema = definition.variablesSchema;
  }
  createTemplateVisible.value = false;
  editorVisible.value = true;
};
const openEdit = async (row: SkillCatalogItem) => {
  const requestId = ++editorRequestId;
  editingCode.value = row.skillCode;
  editingSkillId.value = row.id;
  resetForm();
  editorVisible.value = true;
  try {
    const detail = await skillService.detail(row.skillCode);
    if (requestId !== editorRequestId) return;
    const selectedVersion = detail.version;
    const flowDefinition = selectedVersion?.flowDefinition || {};
    const nestedVariablesPresent = Object.hasOwn(flowDefinition, 'variablesSchema');
    const nestedVariables = (flowDefinition.variablesSchema || {}) as Record<string, unknown>;
    const topVariables = selectedVersion?.variablesSchema || {};
    loadedVariablesSchemaConflict.value = nestedVariablesPresent && !isEqual(nestedVariables, topVariables);
    const effectiveVariables = nestedVariablesPresent ? nestedVariables : topVariables;
    const routeRules = selectedVersion?.routeRules === undefined ? emptyRouteRules() : selectedVersion.routeRules;
    const parsedRouteRules = parseRouteRules(routeRules);
    skillRouteRules.value = routeRules;
    skillRouteRulesDirty.value = false;
    Object.assign(form, {
      skillCode: detail.skillCode,
      skillName: selectedVersion?.skillName ?? detail.skillName,
      description: selectedVersion?.description ?? detail.description ?? '',
      category: selectedVersion?.category ?? detail.category ?? '',
      scope: 'TENANT',
      skillKind: selectedVersion?.skillKind ?? detail.skillKind,
      executionMode: selectedVersion?.executionMode ?? detail.executionMode,
      displayOrder: selectedVersion?.displayOrder ?? detail.displayOrder ?? 0,
      skillMarkdown: selectedVersion?.skillMarkdown || '',
      routeRules: parsedRouteRules.rules,
      knowledgeConfig: selectedVersion?.knowledgeConfig || {},
      reactConfig: selectedVersion?.reactConfig || {},
      runtimeConfig: selectedVersion?.runtimeConfig || {},
      flowDefinition: { ...flowDefinition, variablesSchema: effectiveVariables },
      variablesSchema: effectiveVariables,
      flowRuntimeConfig: selectedVersion?.flowRuntimeConfig || {},
      flowPolicyConfig: selectedVersion?.flowPolicyConfig || {},
      resourceRequirement: selectedVersion?.resourceRequirement || {},
      inputSchema: selectedVersion?.inputSchema || {},
      outputSchema: selectedVersion?.outputSchema || {},
      toolRefs: null
    });
    previewVersion.value = detail.latestDraftVersionId ? 'draft' : 'published';
  } catch (error) {
    if (requestId === editorRequestId) editorVisible.value = false;
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, '加载 Skill 详情失败'));
  }
};

watch(editorTab, tab => {
  if (tab === 'tools') loadToolContext();
  if (tab === 'preview') loadPreview();
  if (tab === 'resources') {
    ensureResourceTab();
    activateResourceTab();
  }
});
watch(resourceTab, tab => {
  if (editorTab.value === 'resources') {
    activateResourceTab(tab);
  }
});
watch(
  () => form.skillKind,
  () => {
    ensureResourceTab();
    if (editorTab.value === 'resources') {
      activateResourceTab();
    }
  }
);
watch(previewVersion, () => {
  if (editorTab.value === 'preview') loadPreview();
});
watch(
  () => form.executionMode,
  (current, previous) => {
    if (current === previous) return;
    if (!toolRefsLoaded.value && !toolRefsDirty.value) return;
    toolContextRequestId += 1;
    toolRefs.value = [];
    toolVersionOptions.value = [];
    toolRefsLoaded.value = false;
    toolRefsDirty.value = false;
    toolContextError.value = '';
    ElMessage.info('执行模式已变化，请重新确认工具引用');
    if (editorTab.value === 'tools' && ['REACT', 'FLOW'].includes(form.executionMode)) loadToolContext();
  }
);

const saveSkill = async () => {
  if (!isLegalSkillMatrix()) {
    editorTab.value = 'basic';
    ElMessage.warning('Skill 类型和执行模式必须使用支持的组合');
    return;
  }
  if (variablesSchemaConflict.value) {
    editorTab.value = 'schema';
    ElMessage.warning('FLOW 变量结构存在冲突，请重新确认后再保存');
    return;
  }
  if (!skillRouteRulesValid.value) {
    editorTab.value = 'routing';
    ElMessage.warning('当前路由规则格式或 FLOW 自动选择条件无效，无法保存以避免覆盖原配置');
    return;
  }
  if (!activeSkillJsonValid.value) {
    ElMessage.warning('请先修正当前 Skill 配置中的 JSON 格式错误');
    return;
  }
  if (!(await formRef.value?.validate().catch(() => false))) {
    editorTab.value = 'basic';
    return;
  }
  saving.value = true;
  try {
    const flowDefinition = form.flowDefinition || {};
    const variablesSchema =
      (flowDefinition.variablesSchema as Record<string, unknown> | undefined) || form.variablesSchema || {};
    form.flowDefinition = { ...flowDefinition, variablesSchema };
    form.variablesSchema = variablesSchema;
    const payload: SkillSavePayload = {
      ...form,
      routeRules: routeRulesPayloadForSave(skillRouteRulesParseResult.value, skillRouteRulesDirty.value),
      toolRefs:
        toolRefsLoaded.value && toolRefsDirty.value && ['REACT', 'FLOW'].includes(form.executionMode)
          ? toolRefs.value.map(ref => ({
              resourceVersionId: ref.resourceVersionId,
              usage: ref.usage,
              displayOrder: ref.displayOrder
            }))
          : null
    };
    const code = editingCode.value;
    const savedSkill = await (code ? skillService.modify(code, payload) : skillService.create(payload));
    ElMessage.success('草稿已保存，尚未发布；不会影响已绑定智能体。');
    await loadPage();
    if (code) {
      editorVisible.value = false;
      return;
    }

    editingCode.value = savedSkill.skillCode;
    editingSkillId.value = savedSkill.id;
    ensureResourceTab();
    if (hasSkillResources.value) {
      editorTab.value = 'resources';
      activateResourceTab();
    }
  } catch (error) {
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, '保存 Skill 失败'));
  } finally {
    saving.value = false;
  }
};
const validateSkill = async (code: string) => {
  try {
    const result = await skillService.validate(code);
    validationResult.value = {
      ...result,
      issues: result.issues?.length
        ? result.issues
        : result.errors.map(message => ({ code: 'SKILL_INVALID', severity: 'ERROR', message }))
    };
    validationSkill.value = rows.value.find(row => row.skillCode === code) || null;
    validationVisible.value = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, '校验失败'));
  }
};
const openValidationIssue = async (issue: FlowValidationIssue) => {
  if (!validationSkill.value) return;
  validationVisible.value = false;
  await openEdit(validationSkill.value);
  if (!editorVisible.value) return;
  if (issue.path?.includes('flowRuntimeConfig')) editorTab.value = 'runtime';
  else if (issue.path?.includes('flowPolicyConfig')) editorTab.value = 'policy';
  else if (issue.path?.includes('variablesSchema')) editorTab.value = 'schema';
  else if (issue.path?.includes('routeRules')) editorTab.value = 'routing';
  else editorTab.value = 'mode';
  if (issue.nodeId) {
    const index = flowNodes.value.findIndex(node => node.id === issue.nodeId);
    if (index >= 0) editFlowNode(flowNodes.value[index], index);
  }
};
const publishSkill = async (skill: SkillCatalogItem) => {
  const state = publicationState(skill);
  if (!state.publishable) {
    ElMessage.warning(state.publishHint);
    return;
  }
  try {
    await ElMessageBox.confirm(
      '新版本发布后，已绑定智能体不会自动切换；请在绑定页手动选择新版本。确认发布？',
      '发布 Skill',
      { type: 'warning' }
    );
    await skillService.publish(skill.skillCode);
    ElMessage.success('新版本已发布；已绑定智能体不会自动切换，请在绑定页手动选择新版本。');
    await loadPage();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && shouldShowLocalApiError(error))
      ElMessage.error(extractApiErrorMessage(error, '发布失败'));
  }
};
const deleteSkill = async (row: SkillCatalogItem) => {
  try {
    await ElMessageBox.confirm(`确认删除 ${row.skillName}？`, '删除 Skill', { type: 'warning' });
    await skillService.delete(row.skillCode);
    ElMessage.success('Skill 已删除');
    await loadPage();
  } catch (error) {
    if (error !== 'cancel' && error !== 'close' && shouldShowLocalApiError(error))
      ElMessage.error(extractApiErrorMessage(error, '删除失败'));
  }
};
const openClone = (row: SkillCatalogItem) => {
  cloneSource.value = row;
  Object.assign(cloneForm, { skillCode: `${row.skillCode}-copy`, skillName: `${row.skillName} 副本` });
  cloneVisible.value = true;
};
const cloneSkill = async () => {
  if (!cloneSource.value || !cloneForm.skillCode.trim() || !cloneForm.skillName.trim()) {
    ElMessage.warning('请填写新 Skill 编码和名称');
    return;
  }
  cloneLoading.value = true;
  try {
    await skillService.clonePublished(cloneSource.value.skillCode, {
      skillCode: cloneForm.skillCode.trim(),
      skillName: cloneForm.skillName.trim()
    });
    ElMessage.success('已克隆为新的 Skill 草稿');
    cloneVisible.value = false;
    await loadPage();
  } catch (error) {
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, '克隆 Skill 失败'));
  } finally {
    cloneLoading.value = false;
  }
};

const openTest = (row: SkillCatalogItem) => {
  testingSkill.value = row;
  routeTestQuery.value = '';
  routeTestResult.value = null;
  flowTestResult.value = null;
  testVisible.value = true;
};
const runRouteTest = async () => {
  if (!testingSkill.value || !routeTestQuery.value.trim()) {
    ElMessage.warning('请输入要测试的用户消息');
    return;
  }
  routeTesting.value = true;
  try {
    routeTestResult.value = await skillService.testRoute(testingSkill.value.skillCode, routeTestQuery.value.trim());
  } catch (error) {
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, '路由测试失败'));
  } finally {
    routeTesting.value = false;
  }
};
const runFlowTest = async () => {
  if (!testingSkill.value) return;
  flowTesting.value = true;
  try {
    flowTestResult.value = await skillService.testFlow(testingSkill.value.skillCode);
  } catch (error) {
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, 'FLOW dry-run 失败'));
  } finally {
    flowTesting.value = false;
  }
};
const exportSkill = async (skillCode: string) => {
  try {
    const bundle = await skillService.exportBundle(skillCode);
    const blob = new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${skillCode}.skill.json`;
    link.click();
    URL.revokeObjectURL(url);
  } catch (error) {
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, '导出 Skill 失败'));
  }
};
const importSkill = async (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  try {
    const parsed = JSON.parse(await file.text()) as SkillPackageBundle;
    if (!parsed || typeof parsed !== 'object' || !parsed['manifest.yaml']) throw new Error('缺少 manifest.yaml');
    await skillService.importBundle(parsed);
    ElMessage.success('Skill 已导入为草稿');
    await loadPage();
  } catch (error) {
    if (shouldShowLocalApiError(error))
      ElMessage.error(extractApiErrorMessage(error, error instanceof Error ? error.message : '导入 Skill 失败'));
  } finally {
    input.value = '';
  }
};

const addToolRef = async () => {
  await loadToolContext();
  if (!toolRefsLoaded.value) return;
  toolRefs.value.push({
    resourceVersionId: '',
    usage: '',
    displayOrder: toolRefs.value.length * 10
  });
  toolRefsDirty.value = true;
};
const removeToolRef = (index: number) => {
  toolRefs.value.splice(index, 1);
  toolRefsDirty.value = true;
};
const markToolRefsDirty = () => {
  toolRefsDirty.value = true;
};
const syncToolRef = (row: EditableToolRef) => {
  row.resourceKey = toolVersionOptions.value.find(
    item => item.resourceVersionId === row.resourceVersionId
  )?.resourceKey;
  toolRefsDirty.value = true;
};
const setFlowNodes = (nodes: any[]) => {
  form.flowDefinition = { ...(form.flowDefinition || {}), nodes };
};
const addFlowNode = () => editFlowNode({ id: '', type: 'collect', next: '', config: {} }, -1);
const cloneJson = <T,>(value: T): T => JSON.parse(JSON.stringify(toRaw(value)));
const editFlowNode = (node: any, index: number) => {
  try {
    nodeEditIndex.value = index;
    nodeJsonValidity.config = true;
    nodeJsonValidity.branches = true;
    nodeOriginal.value = cloneJson(node || {});
    Object.assign(nodeDraft, {
      id: node.id || '',
      type: node.type || 'collect',
      next: node.next || '',
      config: cloneJson(node.config || {}),
      branches: cloneJson(node.branches || [])
    });
    nodeDrawerVisible.value = true;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '打开 FLOW 节点失败'));
  }
};
const commitFlowNode = () => {
  if (!nodeJsonValid.value) {
    ElMessage.warning('请先修正节点配置中的 JSON 格式错误');
    return;
  }
  const id = nodeDraft.id.trim();
  if (!id) {
    ElMessage.warning('节点 ID 不能为空');
    return;
  }
  if (!nodeTypes.includes(nodeDraft.type)) {
    ElMessage.warning('节点类型不受支持');
    return;
  }
  const nodes = [...flowNodes.value];
  if (nodeEditIndex.value < 0 && nodes.some(item => item.id === id)) {
    ElMessage.warning(`节点 ID 已存在：${id}`);
    return;
  }
  try {
    const branches = cloneJson(nodeDraft.branches || []);
    const unknownFields = cloneJson(nodeOriginal.value || {});
    delete unknownFields.id;
    delete unknownFields.type;
    delete unknownFields.next;
    delete unknownFields.config;
    delete unknownFields.branches;
    const node = {
      ...unknownFields,
      id,
      type: nodeDraft.type,
      ...(nodeDraft.next ? { next: nodeDraft.next.trim() } : {}),
      config: cloneJson(nodeDraft.config),
      ...(branches.length ? { branches } : {})
    };
    if (nodeEditIndex.value < 0) nodes.push(node);
    else nodes.splice(nodeEditIndex.value, 1, node);
    setFlowNodes(nodes);
    if (!form.flowDefinition?.startNode) form.flowDefinition = { ...form.flowDefinition, startNode: id };
    nodeDrawerVisible.value = false;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '保存 FLOW 节点失败'));
  }
};
const flowReferencesTo = (targetId: string, nodes: any[]) => {
  const references: string[] = [];
  if (form.flowDefinition?.startNode === targetId) references.push('流程起始节点');
  nodes.forEach(node => {
    if (!node || node.id === targetId) return;
    if (node.next === targetId) references.push(`节点 ${node.id}.next`);
    (Array.isArray(node.branches) ? node.branches : []).forEach((branch: any, index: number) => {
      if (branch?.next === targetId) references.push(`节点 ${node.id}.branches[${index}].next`);
    });
    const config = node.config || {};
    if (config.emptyNext === targetId) references.push(`节点 ${node.id}.config.emptyNext`);
    if (config.invalidNext === targetId) references.push(`节点 ${node.id}.config.invalidNext`);
    if (config.resultQuery?.next === targetId) references.push(`节点 ${node.id}.config.resultQuery.next`);
  });
  return references;
};
const removeFlowNode = async (index: number) => {
  const target = flowNodes.value[index];
  if (!target?.id) return;
  const references = flowReferencesTo(target.id, flowNodes.value);
  if (references.length) {
    ElMessage.warning(`节点 ${target.id} 仍被引用：${references.join('、')}`);
    return;
  }
  try {
    await ElMessageBox.confirm(`确认从当前草稿删除节点“${target.id}”吗？`, '删除 FLOW 节点', { type: 'warning' });
  } catch {
    return;
  }
  const nodes = [...flowNodes.value];
  nodes.splice(index, 1);
  setFlowNodes(nodes);
  ElMessage.success('节点已从当前草稿移除，保存草稿后生效');
};

onMounted(loadPage);
</script>

<style scoped>
.skill-center-shell,
.skill-center-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}
.skill-center-page {
  gap: 8px;
}

.page-toolbar,
.toolbar-actions,
.flow-toolbar,
.tool-ref-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-toolbar,
.flow-toolbar,
.tool-ref-toolbar {
  justify-content: space-between;
}

.page-toolbar h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: 0;
}

.page-toolbar p,
.flow-toolbar span,
.tool-ref-toolbar span {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.tool-ref-actions {
  display: flex;
  gap: 8px;
}
.help-alert {
  margin-top: 16px;
}
.skill-markdown-alert,
.business-knowledge-alert {
  width: 100%;
  margin-top: 8px;
}
.draft-isolation-alert {
  margin-bottom: 12px;
}
.status-pending-tag {
  margin-left: 6px;
}
.react-budget-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
  margin-top: 12px;
}
.react-budget-grid :deep(.el-input-number) {
  width: 100%;
}
.create-template-list {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
  width: 100%;
}
.create-template-list :deep(.el-radio) {
  width: 100%;
  height: auto;
  min-height: 52px;
  margin-right: 0;
  padding: 10px 12px;
}
.create-template-list :deep(.el-radio__label) {
  display: flex;
  min-width: 0;
  flex-direction: column;
  white-space: normal;
}
.template-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
}
.template-description {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.template-source {
  margin-top: 12px;
}
.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.preview-summary,
.preview-alert,
.preview-tool-table {
  margin-top: 12px;
}
.preview-alert ul {
  margin: 0;
  padding-left: 18px;
}
.preview-source,
.preview-json {
  max-height: 360px;
  margin: 0;
  overflow: auto;
  padding: 10px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.preview-tabs {
  margin-top: 12px;
}
.preview-markdown-effect {
  margin-bottom: 12px;
}
.validation-table,
.validation-warning {
  margin-top: 12px;
}
.hidden-file-input {
  display: none;
}

.filter-panel {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-input),
.filter-panel :deep(.el-select) {
  width: 100%;
}

.filter-panel :deep(.el-form-item__content) {
  gap: 8px;
  flex-wrap: nowrap;
}

.load-error-alert {
  margin-bottom: 10px;
}

.skill-center-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.skill-center-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.skill-center-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.skill-center-table-wrap :deep(.el-table) {
  height: 100%;
}

.primary-text {
  font-weight: 600;
}
.code-text,
.config-summary {
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 文字按钮之间只保留 flex gap，避免叠加 Element Plus 默认的按钮间距把操作列撑出滚动条 */
.row-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.page-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.span-2 {
  grid-column: 1/-1;
}
.editor-tabs {
  min-height: 560px;
}
.editor-section h3,
.flow-toolbar h3 {
  margin: 0 0 8px;
  font-size: 16px;
}
.flow-editor {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.tool-ref-toolbar {
  align-items: center;
  margin-bottom: 12px;
}
.full-width {
  width: 100%;
}
.drawer-json {
  width: 100%;
}
.test-result-block {
  margin-top: 14px;
}
@media (max-width: 820px) {
  .page-toolbar,
  .flow-toolbar,
  .tool-ref-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .react-budget-grid {
    grid-template-columns: 1fr;
  }
  .span-2 {
    grid-column: auto;
  }
}
</style>
