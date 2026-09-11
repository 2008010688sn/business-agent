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
  <BaseLayout class="im-connector-shell">
    <main class="im-connector-page">
      <ElCard class="im-toolbar-card">
        <header class="page-toolbar">
          <div>
            <h1>IM 对话连接器</h1>
            <p>钉钉 / 企微机器人入口、Agent 绑定与 IAM 用户映射</p>
          </div>
          <div class="toolbar-actions">
            <ElButton :icon="Refresh" :loading="loading.connectors" @click="reloadAll">刷新</ElButton>
            <ElButton type="primary" :icon="Plus" @click="openConnectorDialog()">新建连接器</ElButton>
          </div>
        </header>
      </ElCard>
      <section class="provider-config-panel">
        <div class="provider-config-grid">
          <div
            v-for="item in providerOptions"
            :key="item.value"
            class="provider-config-card"
            :class="{ uninitialized: !providerConfigByProvider(item.value) }"
          >
            <div class="provider-card-main">
              <strong>{{ item.label }}</strong>
              <ElTag size="small" effect="plain" :type="providerConfigByProvider(item.value) ? 'success' : 'warning'">
                {{
                  providerConfigByProvider(item.value)
                    ? statusLabel(providerConfigByProvider(item.value)?.status)
                    : '未初始化'
                }}
              </ElTag>
            </div>
            <div class="provider-card-meta">
              <span>安装会话 {{ providerConfigValue(item.value, 'setupSessionTtlMinutes') || '-' }} 分钟</span>
              <span>发送超时 {{ providerConfigValue(item.value, 'platformSendTimeoutMs') || '-' }} ms</span>
              <span>Stream {{ providerStreamStatus(item.value) }}</span>
            </div>
            <ElButton
              class="provider-config-action"
              size="small"
              type="primary"
              plain
              @click="openProviderDialog(item.value)"
            >
              配置
            </ElButton>
          </div>
        </div>
      </section>

      <section class="im-layout">
        <aside class="connector-list">
          <div class="panel-title">
            <span class="font-size-16px">连接器</span>
            <ElTag size="small" type="info">{{ connectors.length }}</ElTag>
          </div>
          <ElEmpty v-if="!connectors.length && !loading.connectors" description="暂无连接器" />
          <div
            v-for="connector in connectors"
            :key="connector.connectorCode"
            class="connector-item"
            :class="{ active: connector.connectorCode === selectedConnectorCode }"
          >
            <div
              class="connector-main"
              role="button"
              tabindex="0"
              @click="selectConnector(connector.connectorCode)"
              @keydown.enter.prevent="selectConnector(connector.connectorCode)"
              @keydown.space.prevent="selectConnector(connector.connectorCode)"
            >
              <span class="connector-name">{{ connector.connectorName || connector.connectorCode }}</span>
              <span class="connector-meta">
                <ElTag size="small">{{ providerLabel(connector.provider) }}</ElTag>
                <ElTag size="small" effect="plain" :type="connector.status === 'enabled' ? 'success' : 'info'">
                  {{ statusLabel(connector.status) }}
                </ElTag>
                <ElTag
                  v-if="connector.provider === 'DINGTALK'"
                  size="small"
                  effect="plain"
                  :type="streamStatusTagType(connector)"
                >
                  {{ streamStatusLabel(connector) }}
                </ElTag>
              </span>
              <span class="connector-code">{{ connector.connectorCode }}</span>
            </div>
            <span class="connector-actions">
              <ElButton type="primary" text :icon="Edit" @click.stop="openConnectorDialog(connector)"></ElButton>
              <ElButton
                type="danger"
                text
                :icon="Delete"
                :disabled="!connector.id"
                @click.stop="deleteConnector(connector)"
              ></ElButton>
            </span>
          </div>
        </aside>

        <section class="connector-detail">
          <ElEmpty v-if="!selectedConnector" description="请选择连接器" />
          <template v-else>
            <div class="detail-header">
              <div>
                <div class="font-size-16px font-bold">
                  {{ selectedConnector.connectorName || selectedConnector.connectorCode }}
                </div>
                <div class="detail-meta">
                  <ElTag>{{ providerLabel(selectedConnector.provider) }}</ElTag>
                  <ElTag effect="plain">{{ selectedConnector.authType || '-' }}</ElTag>
                  <ElTag :type="selectedConnector.status === 'enabled' ? 'success' : 'info'">
                    {{ statusLabel(selectedConnector.status) }}
                  </ElTag>
                </div>
              </div>
              <div class="toolbar-actions">
                <ElButton :icon="Connection" :loading="loading.testing" @click="testConnector">测试连接</ElButton>
                <ElButton :icon="Edit" @click="openConnectorDialog(selectedConnector)">编辑</ElButton>
                <ElButton :icon="Delete" type="danger" plain @click="deleteConnector(selectedConnector)">删除</ElButton>
              </div>
            </div>

            <ElTabs v-model="activeTab" class="detail-tabs">
              <ElTabPane label="基础配置" name="basic" class="basic-tab-pane">
                <div class="basic-grid">
                  <div class="kv">
                    <span>连接器编码</span>
                    <strong>{{ selectedConnector.connectorCode }}</strong>
                  </div>
                  <div class="kv">
                    <span>默认 Agent</span>
                    <strong>{{ agentName(selectedConnector.defaultAgentId) }}</strong>
                  </div>
                  <div class="kv">
                    <span>单聊</span>
                    <strong>{{ selectedConnector.directEnabled === false ? '关闭' : '开启' }}</strong>
                  </div>
                  <div class="kv">
                    <span>群聊</span>
                    <strong>{{ selectedConnector.groupEnabled ? '开启' : '关闭' }}</strong>
                  </div>
                  <div v-if="selectedConnector.provider === 'DINGTALK'" class="kv">
                    <span>钉钉 Stream</span>
                    <strong>{{ streamStatusLabel(selectedConnector) }}</strong>
                  </div>
                  <div v-if="selectedConnector.provider === 'DINGTALK' && selectedConnector.streamLastError" class="kv">
                    <span>Stream 说明</span>
                    <strong>{{ selectedConnector.streamLastError }}</strong>
                  </div>
                </div>
                <JsonObjectEditor :model-value="selectedConnector.config || {}" :rows="12" readonly />
              </ElTabPane>

              <ElTabPane label="单聊 / 群聊绑定" name="bindings" class="table-tab-pane">
                <TableHeader
                  title="会话绑定"
                  :count="bindings.length"
                  @create="openBindingDialog()"
                  @reload="loadBindings"
                />
                <div class="im-table-fill">
                  <ElTable v-loading="loading.bindings" :data="bindings" height="100%" border>
                    <ElTableColumn label="类型" width="92">
                      <template #default="{ row }">
                        <ElTag :type="row.conversationType === 'GROUP' ? 'warning' : 'primary'" size="small">
                          {{ conversationTypeLabel(row.conversationType) }}
                        </ElTag>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn prop="conversationName" label="名称" min-width="150" show-overflow-tooltip />
                    <ElTableColumn
                      prop="externalConversationId"
                      label="外部会话 ID"
                      min-width="190"
                      show-overflow-tooltip
                    />
                    <ElTableColumn label="Agent" min-width="170" show-overflow-tooltip>
                      <template #default="{ row }">{{ agentName(row.agentId) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="数字员工" min-width="170" show-overflow-tooltip>
                      <template #default="{ row }">{{ employeeName(row.digitalEmployeeId) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="触发策略" width="150">
                      <template #default="{ row }">{{ triggerPolicyLabel(row.triggerPolicy) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="唤醒词" min-width="160" show-overflow-tooltip>
                      <template #default="{ row }">{{ (row.wakeWords || []).join('、') || '-' }}</template>
                    </ElTableColumn>
                    <ElTableColumn prop="status" label="状态" width="88">
                      <template #default="{ row }">{{ statusLabel(row.status) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="操作" width="140" fixed="right">
                      <template #default="{ row }">
                        <ElButton link type="primary" @click="openBindingDialog(row)">编辑</ElButton>
                        <ElButton link type="danger" @click="deleteBinding(row)">删除</ElButton>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </div>
              </ElTabPane>

              <ElTabPane label="用户绑定" name="users" class="table-tab-pane">
                <div class="table-header">
                  <div class="table-title">
                    <strong>钉钉用户绑定</strong>
                    <span>{{ visibleIdentities.length }} 条</span>
                  </div>
                  <div class="toolbar-actions">
                    <ElButton :icon="Refresh" @click="loadIdentities">刷新</ElButton>
                    <ElButton @click="openIdentityDialog()">手工绑定</ElButton>
                    <ElButton type="primary" :icon="Plus" @click="openBindSessionDialog">扫码绑定</ElButton>
                  </div>
                </div>
                <div class="im-table-fill">
                  <ElTable v-loading="loading.identities" :data="visibleIdentities" height="100%" border>
                    <ElTableColumn prop="externalUserId" label="钉钉用户 ID" min-width="170" show-overflow-tooltip />
                    <ElTableColumn prop="unionId" label="钉钉统一用户 ID" min-width="160" show-overflow-tooltip />
                    <ElTableColumn prop="contact" label="手机号 / 邮箱" min-width="150" show-overflow-tooltip />
                    <ElTableColumn label="系统用户" min-width="170" show-overflow-tooltip>
                      <template #default="{ row }">{{ identityUserLabel(row) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="系统账号" min-width="140" show-overflow-tooltip>
                      <template #default="{ row }">{{ row.username || '-' }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="绑定来源" width="120">
                      <template #default="{ row }">{{ bindSourceLabel(row.bindSource) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="状态" width="88">
                      <template #default="{ row }">{{ statusLabel(row.bindStatus) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="操作" width="140" fixed="right">
                      <template #default="{ row }">
                        <ElButton link type="primary" @click="openIdentityDialog(row)">编辑</ElButton>
                        <ElButton link type="danger" @click="deleteIdentity(row)">删除</ElButton>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </div>
              </ElTabPane>

              <ElTabPane label="消息日志" name="messages" class="table-tab-pane">
                <div class="log-filters">
                  <ElSelect v-model="messageFilters.conversationType" clearable placeholder="会话类型">
                    <ElOption
                      v-for="item in conversationTypeOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                  <ElSelect v-model="messageFilters.direction" clearable placeholder="方向">
                    <ElOption label="入站" value="INBOUND" />
                    <ElOption label="出站" value="OUTBOUND" />
                  </ElSelect>
                  <ElSelect v-model="messageFilters.status" clearable placeholder="状态">
                    <ElOption
                      v-for="item in messageStatusOptions"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                  <ElInput v-model="messageFilters.externalUserId" clearable placeholder="钉钉用户 ID" />
                  <ElInput v-model="messageFilters.runtimeRequestId" clearable placeholder="runtimeRequestId" />
                  <ElInput v-model="messageFilters.keyword" clearable placeholder="关键词" />
                  <ElButton type="primary" :icon="Search" @click="searchMessages">查询</ElButton>
                </div>

                <div class="im-table-fill">
                  <ElTable v-loading="loading.messages" :data="messages" height="100%" border>
                    <ElTableColumn label="时间" min-width="170" show-overflow-tooltip>
                      <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="方向" width="82">
                      <template #default="{ row }">
                        <ElTag :type="row.direction === 'OUTBOUND' ? 'success' : 'primary'" size="small">
                          {{ directionLabel(row.direction) }}
                        </ElTag>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn label="状态" width="112">
                      <template #default="{ row }">
                        <ElTag :type="messageStatusType(row.status)" size="small">
                          {{ messageStatusLabel(row.status) }}
                        </ElTag>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn prop="externalUserId" label="钉钉用户" min-width="150" show-overflow-tooltip />
                    <ElTableColumn label="会话" min-width="180" show-overflow-tooltip>
                      <template #default="{ row }">
                        {{ conversationTypeLabel(row.conversationType) }} / {{ row.externalConversationId || '-' }}
                      </template>
                    </ElTableColumn>
                    <ElTableColumn label="内容" min-width="260" show-overflow-tooltip>
                      <template #default="{ row }">{{ row.content || row.responseContent || '-' }}</template>
                    </ElTableColumn>
                    <ElTableColumn label="系统用户" min-width="140" show-overflow-tooltip>
                      <template #default="{ row }">{{ messageUserLabel(row) }}</template>
                    </ElTableColumn>
                    <ElTableColumn prop="sessionId" label="Session" min-width="120" show-overflow-tooltip />
                    <ElTableColumn prop="runtimeRequestId" label="Request" min-width="220" show-overflow-tooltip />
                    <ElTableColumn prop="errorCode" label="错误码" width="100" show-overflow-tooltip />
                    <ElTableColumn prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
                    <ElTableColumn label="操作" width="110" fixed="right">
                      <template #default="{ row }">
                        <ElButton
                          v-if="row.externalUserId && row.direction !== 'OUTBOUND'"
                          link
                          type="primary"
                          @click="openIdentityDialogFromMessage(row)"
                        >
                          绑定用户
                        </ElButton>
                        <span v-else>-</span>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </div>

                <div class="pagination-row">
                  <ElPagination
                    v-model:current-page="messageFilters.current"
                    v-model:page-size="messageFilters.size"
                    background
                    layout="total, sizes, prev, pager, next, jumper"
                    :page-sizes="[10, 20, 50, 100]"
                    :total="messageTotal"
                    @size-change="handleMessageSizeChange"
                    @current-change="loadMessages"
                  />
                </div>
              </ElTabPane>

              <ElTabPane label="连接测试" name="test" class="basic-tab-pane">
                <ElForm class="test-form" label-width="120px">
                  <ElFormItem label="钉钉用户 ID">
                    <ElInput v-model="testForm.externalUserId" clearable />
                  </ElFormItem>
                  <ElFormItem label="手机号 / 邮箱">
                    <ElInput v-model="testForm.contact" clearable />
                  </ElFormItem>
                  <ElFormItem label="测试文本">
                    <ElInput v-model="testForm.text" type="textarea" :rows="4" />
                  </ElFormItem>
                  <ElFormItem>
                    <ElButton type="primary" :icon="Connection" :loading="loading.testing" @click="testConnector">
                      测试连接
                    </ElButton>
                  </ElFormItem>
                </ElForm>
                <div v-if="testResult" class="test-result">
                  <ElTag :type="testResult.success ? 'success' : 'danger'">
                    {{ testResult.success ? '可用' : '失败' }}
                  </ElTag>
                  <span>{{ testResult.message || testResult.platformCode || '-' }}</span>
                </div>
              </ElTabPane>
            </ElTabs>
          </template>
        </section>
      </section>

      <ElDialog v-model="connectorDialog.visible" :title="connectorDialogTitle" width="min(880px, 92vw)">
        <ElForm class="connector-form" label-position="top">
          <section class="form-section">
            <h3>基础配置</h3>
            <div class="connector-form-grid">
              <ElFormItem label="连接器编码">
                <ElInput v-model="connectorForm.connectorCode" :disabled="connectorDialog.mode === 'edit'" />
              </ElFormItem>
              <ElFormItem label="连接器名称">
                <ElInput v-model="connectorForm.connectorName" />
              </ElFormItem>
              <ElFormItem label="平台">
                <ElSelect v-model="connectorForm.provider" @change="handleConnectorProviderChange">
                  <ElOption v-for="item in providerOptions" :key="item.value" :label="item.label" :value="item.value" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="默认 Agent">
                <ElSelect
                  v-model="connectorForm.defaultAgentId"
                  clearable
                  filterable
                  placeholder="选择 Agent"
                  :loading="loading.agents"
                  @visible-change="handleAgentOptionsVisible"
                >
                  <ElOption
                    v-for="item in agentOptions"
                    :key="String(item.id)"
                    :label="agentLabel(item)"
                    :value="String(item.id)"
                  />
                </ElSelect>
              </ElFormItem>
            </div>
            <div class="connector-options-grid">
              <ElFormItem label="单聊启用"><ElSwitch v-model="connectorForm.directEnabled" /></ElFormItem>
              <ElFormItem label="群聊启用"><ElSwitch v-model="connectorForm.groupEnabled" /></ElFormItem>
              <ElFormItem label="状态">
                <ElSelect v-model="connectorForm.status">
                  <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="展示顺序">
                <ElInputNumber v-model="connectorForm.displayOrder" :min="0" />
              </ElFormItem>
            </div>
          </section>

          <section class="form-section">
            <h3>接入凭据</h3>
            <p class="field-tip">
              在钉钉开放平台创建企业内部应用后填写 Client ID / Secret。钉钉不会通过扫码下发 AppSecret。
              <a :href="dingTalkConsoleUrl" target="_blank" rel="noreferrer">打开钉钉开放平台</a>
            </p>
            <div class="connector-form-grid">
              <ElFormItem label="接入模式">
                <ElSelect v-model="connectorForm.config.mode" @change="markConnectorConfigTouched">
                  <ElOption label="Stream" value="STREAM" />
                  <ElOption label="HTTP Callback" value="HTTP_CALLBACK" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="Client ID">
                <ElInput v-model="connectorForm.config.clientId" @input="markConnectorConfigTouched" />
              </ElFormItem>
              <ElFormItem label="Client Secret">
                <ElInput
                  v-model="connectorForm.config.clientSecret"
                  show-password
                  autocomplete="new-password"
                  @input="markConnectorConfigTouched"
                />
              </ElFormItem>
              <ElFormItem label="签名密钥">
                <ElInput
                  v-model="connectorForm.config.callbackSecret"
                  show-password
                  autocomplete="new-password"
                  @input="markConnectorConfigTouched"
                />
              </ElFormItem>
              <ElFormItem label="认证方式">
                <ElInput v-model="connectorForm.authType" />
              </ElFormItem>
              <ElFormItem label="凭据引用">
                <ElInput v-model="connectorForm.credentialRef" clearable />
              </ElFormItem>
            </div>
          </section>

          <ElCollapse>
            <ElCollapseItem title="高级连接器配置" name="advanced">
              <div class="connector-options-grid">
                <ElFormItem label="Stream 自动启动">
                  <ElSwitch v-model="connectorForm.config.streamAutoStart" @change="markConnectorConfigTouched" />
                </ElFormItem>
                <ElFormItem label="异步 Webhook 回复">
                  <ElSwitch v-model="connectorForm.config.webhookReplyEnabled" @change="markConnectorConfigTouched" />
                </ElFormItem>
                <ElFormItem label="用户解析">
                  <ElSwitch v-model="connectorForm.config.userResolveEnabled" @change="markConnectorConfigTouched" />
                </ElFormItem>
                <ElFormItem label="思考中文案">
                  <ElSwitch
                    v-model="connectorForm.config.thinkingMessageEnabled"
                    @change="markConnectorConfigTouched"
                  />
                </ElFormItem>
              </div>
              <div class="connector-form-grid">
                <ElFormItem label="思考中文案覆盖">
                  <ElInput
                    v-model="connectorForm.config.thinkingMessageText"
                    clearable
                    @input="markConnectorConfigTouched"
                  />
                </ElFormItem>
                <ElFormItem label="回复 Webhook">
                  <ElInput v-model="connectorForm.config.replyWebhook" clearable @input="markConnectorConfigTouched" />
                </ElFormItem>
                <ElFormItem label="回复域名白名单">
                  <ElSelect
                    v-model="connectorForm.config.replyWebhookAllowedHosts"
                    multiple
                    allow-create
                    filterable
                    default-first-option
                    placeholder="输入域名后回车，不包含协议和路径"
                    @change="markConnectorConfigTouched"
                  />
                  <div class="field-tip">
                    仅填写纯域名，例如 oapi.dingtalk.com；不包含协议、端口、路径、Token、IP 或通配符。
                  </div>
                </ElFormItem>
              </div>
              <ElFormItem class="connector-config-field" label="扩展配置">
                <JsonObjectEditor
                  :model-value="connectorForm.config"
                  :rows="8"
                  @update:model-value="updateConnectorConfig"
                  @validity-change="connectorConfigValid = $event"
                />
              </ElFormItem>
            </ElCollapseItem>
          </ElCollapse>
        </ElForm>
        <template #footer>
          <ElButton @click="connectorDialog.visible = false">取消</ElButton>
          <ElButton type="primary" :disabled="!connectorConfigValid" @click="saveConnector">保存</ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="providerDialog.visible" :title="providerDialogTitle" width="min(920px, 94vw)">
        <ElForm class="provider-form" label-position="top">
          <section class="form-section">
            <h3>基础参数</h3>
            <div class="provider-form-grid">
              <ElFormItem label="平台">
                <ElInput :model-value="providerLabel(providerConfigForm.provider)" disabled />
              </ElFormItem>
              <ElFormItem label="状态">
                <ElSelect v-model="providerConfigForm.status">
                  <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="开发者控制台地址">
                <ElInput v-model="providerConfigForm.config.developerConsoleUrl" />
              </ElFormItem>
              <ElFormItem label="安装会话 TTL（分钟）">
                <ElInputNumber v-model="providerConfigForm.config.setupSessionTtlMinutes" :min="1" :max="1440" />
              </ElFormItem>
              <ElFormItem label="凭据校验超时（ms）">
                <ElInputNumber
                  v-model="providerConfigForm.config.credentialValidationTimeoutMs"
                  :min="1000"
                  :step="500"
                />
              </ElFormItem>
              <ElFormItem label="发送超时（ms）">
                <ElInputNumber v-model="providerConfigForm.config.platformSendTimeoutMs" :min="1000" :step="500" />
              </ElFormItem>
            </div>
          </section>

          <section class="form-section">
            <h3>业务配置</h3>
            <div class="provider-form-grid">
              <ElFormItem label="默认单聊触发策略">
                <ElSelect v-model="providerConfigForm.config.defaultSingleTriggerPolicy">
                  <ElOption
                    v-for="item in triggerPolicyOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="默认群聊触发策略">
                <ElSelect v-model="providerConfigForm.config.defaultGroupTriggerPolicy">
                  <ElOption
                    v-for="item in triggerPolicyOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="自动按联系方式绑定">
                <ElSwitch v-model="providerConfigForm.config.autoBindByContact" />
              </ElFormItem>
              <ElFormItem label="思考中文案开关">
                <ElSwitch v-model="providerConfigForm.config.thinkingMessageEnabled" />
              </ElFormItem>
              <ElFormItem label="委托客户端 ID">
                <ElInput v-model="providerConfigForm.config.delegatedClientId" />
              </ElFormItem>
              <ElFormItem label="委托设备标识">
                <ElInput v-model="providerConfigForm.config.delegatedDevice" />
              </ElFormItem>
              <ElFormItem label="委托 Token TTL（秒）">
                <ElInputNumber v-model="providerConfigForm.config.delegatedTokenTtlSeconds" :min="60" :step="60" />
              </ElFormItem>
              <ElFormItem label="IAM 调用超时（ms）">
                <ElInputNumber v-model="providerConfigForm.config.iamTimeoutMs" :min="1000" :step="500" />
              </ElFormItem>
            </div>
            <div class="provider-message-grid">
              <ElFormItem label="思考中文案">
                <ElInput v-model="providerConfigForm.config.thinkingMessageText" />
              </ElFormItem>
              <ElFormItem label="未绑定用户提示">
                <ElInput v-model="providerConfigForm.config.unboundUserMessage" />
              </ElFormItem>
              <ElFormItem label="连接器禁用提示">
                <ElInput v-model="providerConfigForm.config.disabledConnectorMessage" />
              </ElFormItem>
              <ElFormItem label="不支持消息类型提示">
                <ElInput v-model="providerConfigForm.config.unsupportedMessageTypeMessage" />
              </ElFormItem>
              <ElFormItem label="Agent 执行失败提示">
                <ElInput v-model="providerConfigForm.config.agentInvokeFailedMessage" />
              </ElFormItem>
              <ElFormItem label="分析超时提示">
                <ElInput v-model="providerConfigForm.config.agentInvokeTimeoutMessage" />
              </ElFormItem>
            </div>
          </section>

          <ElCollapse>
            <ElCollapseItem title="高级配置" name="advanced">
              <div class="provider-form-grid">
                <ElFormItem label="Stream Worker">
                  <ElSwitch v-model="providerConfigForm.config.streamWorkerEnabled" />
                  <div class="field-tip">
                    本实例是否抢钉钉 Stream。同一 Client ID 全环境只能开一个 worker；本地调试时请关掉
                    DEV，否则两边会抢连接，对话会飘。
                  </div>
                </ElFormItem>
                <ElFormItem label="用户解析">
                  <ElSwitch v-model="providerConfigForm.config.userResolveEnabled" />
                </ElFormItem>
                <ElFormItem label="Stream 连接超时（ms）">
                  <ElInputNumber v-model="providerConfigForm.config.streamConnectTimeoutMs" :min="1000" :step="500" />
                </ElFormItem>
                <ElFormItem label="Stream 重连间隔（ms）">
                  <ElInputNumber v-model="providerConfigForm.config.streamReconnectDelayMs" :min="1000" :step="500" />
                </ElFormItem>
                <ElFormItem label="Stream lease TTL（秒）">
                  <ElInputNumber v-model="providerConfigForm.config.streamLeaseTtlSeconds" :min="10" :step="5" />
                </ElFormItem>
                <ElFormItem label="Stream lease renew（秒）">
                  <ElInputNumber v-model="providerConfigForm.config.streamLeaseRenewSeconds" :min="5" :step="5" />
                </ElFormItem>
                <ElFormItem label="用户解析超时（ms）">
                  <ElInputNumber v-model="providerConfigForm.config.userResolveTimeoutMs" :min="1000" :step="500" />
                </ElFormItem>
                <ElFormItem label="显示顺序">
                  <ElInputNumber v-model="providerConfigForm.displayOrder" :min="0" />
                </ElFormItem>
              </div>
              <div class="provider-message-grid">
                <ElFormItem label="钉钉 accessToken 地址">
                  <ElInput v-model="providerConfigForm.config.tokenUrl" />
                </ElFormItem>
                <ElFormItem label="钉钉用户信息地址模板">
                  <ElInput v-model="providerConfigForm.config.userInfoUrlTemplate" />
                </ElFormItem>
              </div>
            </ElCollapseItem>
          </ElCollapse>
        </ElForm>
        <template #footer>
          <ElButton @click="providerDialog.visible = false">取消</ElButton>
          <ElButton type="primary" :loading="loading.providerConfigs" @click="saveProviderConfig">保存</ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="bindingDialog.visible" :title="bindingDialogTitle" width="760px">
        <ElForm label-width="130px">
          <ElFormItem label="会话类型">
            <ElSelect v-model="bindingForm.conversationType" @change="syncBindingDefaultTrigger">
              <ElOption
                v-for="item in conversationTypeOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="外部会话 ID"><ElInput v-model="bindingForm.externalConversationId" /></ElFormItem>
          <ElFormItem label="会话名称"><ElInput v-model="bindingForm.conversationName" clearable /></ElFormItem>
          <ElFormItem label="绑定 Agent">
            <ElSelect
              v-model="bindingForm.agentId"
              clearable
              filterable
              placeholder="选择 Agent"
              :loading="loading.agents"
              @visible-change="handleAgentOptionsVisible"
            >
              <ElOption
                v-for="item in agentOptions"
                :key="String(item.id)"
                :label="agentLabel(item)"
                :value="String(item.id)"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="绑定数字员工">
            <EmployeeOptionSelect
              v-model="bindingForm.digitalEmployeeId"
              placeholder="可不指定数字员工"
              width="100%"
            />
            <div class="field-tip">可空。指定后会话归属该数字员工；当前回调仍按绑定 Agent 执行。</div>
          </ElFormItem>
          <ElFormItem label="触发策略">
            <ElSelect v-model="bindingForm.triggerPolicy">
              <ElOption
                v-for="item in triggerPolicyOptions"
                :key="item.value"
                :label="item.label"
                :value="item.value"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="唤醒词">
            <ElInput v-model="wakeWordsText" type="textarea" :rows="3" placeholder="多个词可用逗号、空格或换行分隔" />
          </ElFormItem>
          <ElFormItem label="会话隔离">
            <ElSelect v-model="bindingForm.sessionScope">
              <ElOption label="按用户隔离" value="PER_USER" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="bindingForm.status">
              <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="展示顺序"><ElInputNumber v-model="bindingForm.displayOrder" :min="0" /></ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="bindingDialog.visible = false">取消</ElButton>
          <ElButton type="primary" @click="saveBinding">保存</ElButton>
        </template>
      </ElDialog>

      <ElDialog v-model="identityDialog.visible" :title="identityDialogTitle" width="720px">
        <ElForm label-width="130px">
          <ElFormItem label="钉钉用户 ID">
            <ElInput v-model="identityForm.externalUserId" placeholder="从消息日志复制，例如 manager4081" />
          </ElFormItem>
          <ElFormItem label="钉钉统一用户 ID">
            <ElInput v-model="identityForm.unionId" clearable placeholder="可选；拿不到可以留空" />
            <div class="field-tip">
              同一个钉钉用户在企业应用间的统一标识，用于辅助识别；当前绑定主要依赖“钉钉用户 ID”。
            </div>
          </ElFormItem>
          <ElFormItem label="手机号 / 邮箱">
            <ElInput v-model="identityForm.contact" clearable placeholder="可选；用于联系人自动匹配" />
          </ElFormItem>
          <ElFormItem label="系统用户">
            <ElSelect
              v-model="identityForm.userId"
              clearable
              filterable
              :loading="loading.iamUsers"
              placeholder="请选择系统用户"
              @visible-change="handleIamUserOptionsVisible"
              @change="handleIdentityUserChange"
            >
              <ElOption
                v-for="item in iamUserOptions"
                :key="iamUserValue(item)"
                :label="iamUserLabel(item)"
                :value="iamUserValue(item)"
              >
                <div class="iam-user-option">
                  <span>{{ iamUserLabel(item) }}</span>
                  <small>{{ iamUserMeta(item) }}</small>
                </div>
              </ElOption>
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="系统账号">
            <ElInput v-model="identityForm.username" disabled placeholder="选择系统用户后自动带出" />
          </ElFormItem>
          <ElFormItem label="系统昵称">
            <ElInput v-model="identityForm.nickName" disabled placeholder="选择系统用户后自动带出" />
          </ElFormItem>
          <ElFormItem label="绑定来源">
            <ElSelect v-model="identityForm.bindSource">
              <ElOption v-for="item in bindSourceOptions" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="identityForm.bindStatus">
              <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="identityDialog.visible = false">取消</ElButton>
          <ElButton type="primary" @click="saveIdentity">保存</ElButton>
        </template>
      </ElDialog>

      <ElDialog
        v-model="bindSessionDialog.visible"
        title="扫码绑定当前登录账号"
        width="480px"
        @closed="stopBindSessionPoll"
      >
        <p class="field-tip">
          用当前登录的系统账号生成一次性短码。打开本连接器对应的钉钉机器人，把短码原样发出即可，无需填写钉钉用户
          ID。
        </p>
        <div v-loading="loading.bindSession" class="bind-session-panel">
          <div id="im-bind-qr-canvas" ref="bindQrRef" class="bind-qr"></div>
          <div v-if="bindSession.code" class="bind-code-row">
            <strong>{{ bindSession.code }}</strong>
            <ElButton size="small" @click="copyBindCode">复制</ElButton>
          </div>
          <div class="bind-session-meta">
            <span>状态 {{ bindSessionStatusLabel }}</span>
            <span v-if="bindCountdown > 0">剩余 {{ bindCountdown }} 秒</span>
            <span v-if="bindSession.externalUserId">钉钉用户 {{ bindSession.externalUserId }}</span>
          </div>
        </div>
        <template #footer>
          <ElButton @click="bindSessionDialog.visible = false">关闭</ElButton>
          <ElButton
            v-if="bindSession.status === 'EXPIRED' || (bindSession.status === 'PENDING' && bindCountdown === 0)"
            @click="openBindSessionDialog"
          >
            重新生成
          </ElButton>
        </template>
      </ElDialog>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import QRCode from 'qrcodejs2-fix';
import { Connection, Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import { ElButton, ElMessage, ElMessageBox } from 'element-plus';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import EmployeeOptionSelect from '@/views/ai-agent/components/employee-option-select.vue';
import AgentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployeeOption } from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import imConnectorService from '@/views/ai-agent/services/im';
import type {
  IamBasicUser,
  ImConnector,
  ImConversationBinding,
  ImMessage,
  ImMessagePageQuery,
  ImProviderConfig,
  ImTestRequest,
  ImUserBindSession,
  ImUserIdentity
} from '@/views/ai-agent/services/im';
import type { NotificationAdapterResult } from '@/views/ai-agent/services/notification';

defineOptions({ name: 'DataAgentImConnector' });

type DialogMode = 'create' | 'edit';

interface ProviderConfigForm {
  id?: string;
  provider: string;
  config: Record<string, any>;
  status: string;
  displayOrder: number;
}

const TableHeader = defineComponent({
  name: 'TableHeader',
  props: {
    title: { type: String, required: true },
    count: { type: Number, default: 0 }
  },
  emits: ['create', 'reload'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'table-header' }, [
        h('div', { class: 'table-title' }, [h('strong', props.title), h('span', `${props.count} 条`)]),
        h('div', { class: 'toolbar-actions' }, [
          h(ElButton, { icon: Refresh, onClick: () => emit('reload') }, () => '刷新'),
          h(ElButton, { type: 'primary', icon: Plus, onClick: () => emit('create') }, () => '新增')
        ])
      ]);
  }
});

const providerOptions = [
  { label: '钉钉', value: 'DINGTALK' },
  { label: '企业微信', value: 'WECOM' }
];

const statusOptions = [
  { label: '启用', value: 'enabled' },
  { label: '禁用', value: 'disabled' }
];

const conversationTypeOptions = [
  { label: '单聊', value: 'SINGLE' },
  { label: '群聊', value: 'GROUP' }
];

const triggerPolicyOptions = [
  { label: '直接触发', value: 'ALWAYS' },
  { label: '@机器人', value: 'MENTION' },
  { label: '唤醒词', value: 'WAKE_WORD' },
  { label: '@机器人或唤醒词', value: 'MENTION_OR_WAKE_WORD' }
];

const bindSourceOptions = [
  { label: '手动绑定', value: 'MANUAL' },
  { label: '联系人自动绑定', value: 'CONTACT_AUTO' },
  { label: '扫码绑定', value: 'QR_PAIR' }
];

const messageStatusOptions = [
  { label: '已接收', value: 'RECEIVED' },
  { label: '跳过', value: 'SKIPPED' },
  { label: '授权失败', value: 'AUTH_FAILED' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '待发送', value: 'PENDING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' }
];

const connectors = ref<ImConnector[]>([]);
const providerConfigs = ref<ImProviderConfig[]>([]);
const bindings = ref<ImConversationBinding[]>([]);
const identities = ref<ImUserIdentity[]>([]);
const messages = ref<ImMessage[]>([]);
const agentOptions = ref<Agent[]>([]);
const employeeOptions = ref<DigitalEmployeeOption[]>([]);
const iamUserOptions = ref<IamBasicUser[]>([]);
const messageTotal = ref(0);
const selectedConnectorCode = ref('');
const activeTab = ref('basic');
const loadedTabs = reactive({
  bindings: false,
  users: false,
  messages: false
});
const agentsLoaded = ref(false);
const employeesLoaded = ref(false);
const iamUsersLoaded = ref(false);
const providerConfigsLoaded = ref(false);
const wakeWordsText = ref('');
const testResult = ref<NotificationAdapterResult | null>(null);
const connectorConfigTouched = ref(false);
const connectorConfigValid = ref(true);

const selectedConnector = computed(() =>
  connectors.value.find(item => item.connectorCode === selectedConnectorCode.value)
);

const providerConfigMap = computed(
  () => new Map(providerConfigs.value.map(item => [String(item.provider || '').toUpperCase(), item]))
);

const loading = reactive({
  connectors: false,
  bindings: false,
  identities: false,
  messages: false,
  agents: false,
  employees: false,
  iamUsers: false,
  testing: false,
  providerConfigs: false,
  bindSession: false
});

const providerDialog = reactive({ visible: false });
const connectorDialog = reactive({ visible: false, mode: 'create' as DialogMode });
const bindingDialog = reactive({ visible: false, mode: 'create' as DialogMode });
const identityDialog = reactive({ visible: false, mode: 'create' as DialogMode });
const bindSessionDialog = reactive({ visible: false });
const bindSession = reactive<ImUserBindSession>({});
const bindQrRef = ref<HTMLElement | null>(null);
const bindCountdown = ref(0);
let bindPollTimer: ReturnType<typeof setInterval> | null = null;
let bindCountdownTimer: ReturnType<typeof setInterval> | null = null;

const connectorForm = reactive<ImConnector>(emptyConnector());
const providerConfigForm = reactive<ProviderConfigForm>(emptyProviderConfig('DINGTALK'));
const bindingForm = reactive<ImConversationBinding>(emptyBinding());
const identityForm = reactive<ImUserIdentity>(emptyIdentity());
const testForm = reactive<ImTestRequest>({
  externalUserId: '',
  contact: '',
  text: 'ping'
});
const messageFilters = reactive<ImMessagePageQuery>({
  current: 1,
  size: 20,
  conversationType: '',
  externalUserId: '',
  runtimeRequestId: '',
  direction: '',
  status: '',
  keyword: ''
});

const visibleIdentities = computed(() => {
  const connector = selectedConnector.value;
  if (!connector) return [];
  return identities.value.filter(item => {
    const providerMatched = !item.provider || item.provider === connector.provider;
    const connectorMatched = !item.connectorCode || item.connectorCode === connector.connectorCode;
    return providerMatched && connectorMatched;
  });
});
const connectorDialogTitle = computed(() => (connectorDialog.mode === 'create' ? '新建 IM 连接器' : '编辑 IM 连接器'));
const providerDialogTitle = computed(() => `${providerLabel(providerConfigForm.provider)} 平台配置`);
const bindingDialogTitle = computed(() => (bindingDialog.mode === 'create' ? '新增会话绑定' : '编辑会话绑定'));
const identityDialogTitle = computed(() => (identityDialog.mode === 'create' ? '新增用户绑定' : '编辑用户绑定'));

const dingTalkConsoleUrl = computed(
  () =>
    String(providerConfigValue('DINGTALK', 'developerConsoleUrl') || 'https://open-dev.dingtalk.com')
);

const bindSessionStatusLabel = computed(() => {
  const labels: Record<string, string> = {
    PENDING: '等待钉钉发送短码',
    CONSUMED: '绑定成功',
    EXPIRED: '已过期'
  };
  return labels[bindSession.status || ''] || bindSession.status || '-';
});

onMounted(loadConnectors);
onUnmounted(stopBindSessionPoll);

watch(activeTab, tab => {
  ensureActiveTabLoaded(tab);
});

async function reloadAll() {
  await loadConnectors();
  await refreshActiveTab();
}

async function loadProviderConfigs(force = false) {
  if (providerConfigsLoaded.value && !force) {
    return;
  }
  loading.providerConfigs = true;
  try {
    providerConfigs.value = await imConnectorService.listProviderConfigs();
    providerConfigsLoaded.value = true;
  } catch (error) {
    providerConfigs.value = [];
    showError(error, '加载 IM 平台配置失败');
  } finally {
    loading.providerConfigs = false;
  }
}

async function loadAgents(force = false) {
  if (agentsLoaded.value && !force) {
    return;
  }
  loading.agents = true;
  try {
    agentOptions.value = await AgentService.list();
    agentsLoaded.value = true;
  } catch (error) {
    agentOptions.value = [];
    showError(error, '加载 Agent 列表失败');
  } finally {
    loading.agents = false;
  }
}

async function loadEmployees(force = false) {
  if (employeesLoaded.value && !force) {
    return;
  }
  loading.employees = true;
  try {
    employeeOptions.value = await digitalEmployeeService.listOptions();
    employeesLoaded.value = true;
  } catch (error) {
    employeeOptions.value = [];
    showError(error, '加载数字员工列表失败');
  } finally {
    loading.employees = false;
  }
}

async function loadIamUsers(force = false) {
  if (iamUsersLoaded.value && !force) {
    return;
  }
  loading.iamUsers = true;
  try {
    iamUserOptions.value = await imConnectorService.listIamUsers();
    iamUsersLoaded.value = true;
  } catch (error) {
    iamUserOptions.value = [];
    showError(error, '加载系统用户列表失败');
  } finally {
    loading.iamUsers = false;
  }
}

async function loadConnectors() {
  loading.connectors = true;
  try {
    connectors.value = await imConnectorService.listConnectors();
    if (!connectors.value.some(item => item.connectorCode === selectedConnectorCode.value)) {
      selectedConnectorCode.value = connectors.value[0]?.connectorCode || '';
    }
  } catch (error) {
    connectors.value = [];
    selectedConnectorCode.value = '';
    showError(error, '加载 IM 连接器失败');
  } finally {
    loading.connectors = false;
  }
}

async function loadBindings() {
  const connector = selectedConnector.value;
  if (!connector?.connectorCode) {
    bindings.value = [];
    loadedTabs.bindings = true;
    return;
  }
  loading.bindings = true;
  try {
    await loadEmployees();
    bindings.value = await imConnectorService.listConversationBindings({
      provider: connector.provider,
      connectorCode: connector.connectorCode
    });
    loadedTabs.bindings = true;
  } catch (error) {
    bindings.value = [];
    showError(error, '加载会话绑定失败');
  } finally {
    loading.bindings = false;
  }
}

async function loadIdentities() {
  loading.identities = true;
  try {
    identities.value = await imConnectorService.listUserIdentities();
    loadedTabs.users = true;
  } catch (error) {
    identities.value = [];
    showError(error, '加载用户绑定失败');
  } finally {
    loading.identities = false;
  }
}

async function loadMessages() {
  const connector = selectedConnector.value;
  if (!connector?.connectorCode) {
    messages.value = [];
    messageTotal.value = 0;
    loadedTabs.messages = true;
    return;
  }
  loading.messages = true;
  try {
    const page = await imConnectorService.pageMessages(
      compact({
        ...messageFilters,
        provider: connector.provider,
        connectorCode: connector.connectorCode
      }) as ImMessagePageQuery
    );
    messages.value = page.data;
    messageTotal.value = page.total;
    loadedTabs.messages = true;
  } catch (error) {
    messages.value = [];
    messageTotal.value = 0;
    showError(error, '加载消息日志失败');
  } finally {
    loading.messages = false;
  }
}

async function selectConnector(connectorCode?: string) {
  selectedConnectorCode.value = connectorCode || '';
  messageFilters.current = 1;
  testResult.value = null;
  markConnectorScopedTabsStale();
  await refreshActiveTab();
}

function markConnectorScopedTabsStale() {
  bindings.value = [];
  identities.value = [];
  messages.value = [];
  messageTotal.value = 0;
  loadedTabs.bindings = false;
  loadedTabs.users = false;
  loadedTabs.messages = false;
}

async function refreshActiveTab(tab = activeTab.value) {
  if (tab === 'bindings') await loadBindings();
  if (tab === 'users') await loadIdentities();
  if (tab === 'messages') await loadMessages();
}

async function ensureActiveTabLoaded(tab = activeTab.value) {
  if (tab === 'bindings' && !loadedTabs.bindings) await loadBindings();
  if (tab === 'users' && !loadedTabs.users) await loadIdentities();
  if (tab === 'messages' && !loadedTabs.messages) await loadMessages();
}

function handleAgentOptionsVisible(visible: boolean) {
  if (visible) {
    loadAgents();
  }
}

function handleIamUserOptionsVisible(visible: boolean) {
  if (visible) {
    loadIamUsers();
  }
}

function providerConfigByProvider(provider?: string) {
  return providerConfigMap.value.get(String(provider || '').toUpperCase());
}

function providerConfigValue(provider: string, key: string) {
  return providerConfigByProvider(provider)?.config?.[key];
}

function providerStreamStatus(provider: string) {
  const config = providerConfigByProvider(provider);
  if (!config) {
    return '-';
  }
  return config.config?.streamWorkerEnabled === false ? '关闭' : '开启';
}

async function openProviderDialog(provider: string) {
  await loadProviderConfigs();
  const existing = providerConfigByProvider(provider);
  assign(providerConfigForm, existing || emptyProviderConfig(provider));
  providerConfigForm.provider = provider.toUpperCase();
  providerConfigForm.config = {
    ...defaultProviderConfig(provider),
    ...(existing?.config || {})
  };
  providerDialog.visible = true;
}

async function saveProviderConfig() {
  const provider = providerConfigForm.provider;
  if (!provider) {
    return;
  }
  loading.providerConfigs = true;
  try {
    await imConnectorService.saveProviderConfig(provider, {
      id: providerConfigForm.id,
      provider,
      status: providerConfigForm.status || 'enabled',
      displayOrder: providerConfigForm.displayOrder,
      config: compact(providerConfigForm.config)
    });
    providerDialog.visible = false;
    ElMessage.success('IM 平台配置已保存');
    await loadProviderConfigs(true);
  } catch (error) {
    showError(error, '保存 IM 平台配置失败');
  } finally {
    loading.providerConfigs = false;
  }
}

async function openConnectorDialog(row?: ImConnector) {
  await loadAgents();
  connectorDialog.mode = row?.id ? 'edit' : 'create';
  connectorConfigTouched.value = false;
  connectorConfigValid.value = true;
  assign(connectorForm, row || emptyConnector());
  connectorForm.config = {
    ...defaultConnectorConfig(connectorForm.provider || 'DINGTALK'),
    ...(connectorForm.config || {})
  };
  connectorDialog.visible = true;
}

async function saveConnector() {
  if (!connectorConfigValid.value) {
    ElMessage.warning('请先修正扩展配置中的 JSON 格式错误');
    return;
  }
  if (connectorForm.config.mode === 'HTTP_CALLBACK' && !String(connectorForm.config.callbackSecret || '').trim()) {
    ElMessage.warning('HTTP Callback 必须配置签名密钥');
    return;
  }
  const allowedHosts = normalizeReplyWebhookAllowedHosts(connectorForm.config.replyWebhookAllowedHosts);
  if (Object.hasOwn(connectorForm.config, 'replyWebhookAllowedHosts')) {
    const invalidHost = allowedHosts.find(host => !isPureReplyWebhookHost(host));
    if (invalidHost) {
      ElMessage.warning(`回复域名白名单无效：${invalidHost}`);
      return;
    }
    connectorForm.config = {
      ...connectorForm.config,
      replyWebhookAllowedHosts: allowedHosts
    };
  }
  if (
    connectorForm.config.webhookReplyEnabled &&
    (!Array.isArray(connectorForm.config.replyWebhookAllowedHosts) ||
      connectorForm.config.replyWebhookAllowedHosts.length === 0)
  ) {
    ElMessage.warning('启用异步 Webhook 回复时必须配置回复域名白名单');
    return;
  }
  const payload: ImConnector = {
    ...connectorForm,
    defaultAgentId: connectorForm.defaultAgentId || undefined
  };
  const request =
    connectorDialog.mode === 'edit' && !connectorConfigTouched.value
      ? (({ config: _config, ...rest }) => rest as ImConnector)(payload)
      : payload;
  if (connectorForm.id) {
    await imConnectorService.updateConnector(connectorForm.id, request);
  } else {
    await imConnectorService.createConnector(request);
  }
  connectorDialog.visible = false;
  ElMessage.success('连接器已保存');
  await loadConnectors();
  markConnectorScopedTabsStale();
  await refreshActiveTab();
}

async function deleteConnector(row: ImConnector) {
  if (!row.id) return;
  try {
    await confirmDelete(`确认删除连接器 ${row.connectorName || row.connectorCode}？`);
  } catch {
    return;
  }
  try {
    await imConnectorService.deleteConnector(row.id);
  } catch (error) {
    showError(error, '删除连接器失败');
    return;
  }
  ElMessage.success('连接器已删除');
  await loadConnectors();
  markConnectorScopedTabsStale();
  await refreshActiveTab();
}

async function testConnector() {
  if (!selectedConnector.value?.connectorCode) return;
  loading.testing = true;
  try {
    testResult.value = await imConnectorService.testConnector(
      selectedConnector.value.connectorCode,
      compact(testForm) as ImTestRequest
    );
    if (testResult.value.success) {
      ElMessage.success(testResult.value.message || '连接器可用');
    } else {
      ElMessage.warning(testResult.value.message || '连接器测试未通过');
    }
  } catch (error) {
    testResult.value = null;
    showError(error, '测试连接失败');
  } finally {
    loading.testing = false;
  }
}

async function openBindingDialog(row?: ImConversationBinding) {
  await Promise.all([loadAgents(), loadEmployees()]);
  const connector = selectedConnector.value;
  bindingDialog.mode = row?.id ? 'edit' : 'create';
  assign(bindingForm, row || emptyBinding());
  bindingForm.provider = connector?.provider || bindingForm.provider;
  bindingForm.connectorCode = connector?.connectorCode || bindingForm.connectorCode;
  bindingForm.agentId = row?.agentId ?? connector?.defaultAgentId ?? '';
  wakeWordsText.value = (bindingForm.wakeWords || []).join('\n');
  bindingDialog.visible = true;
}

async function saveBinding() {
  if (!bindingForm.agentId) {
    ElMessage.warning('请选择绑定 Agent');
    return;
  }
  const payload = {
    ...bindingForm,
    agentId: bindingForm.agentId || undefined,
    // 数字员工可为空；空串会导致后端 Long 反序列化失败，统一转 undefined
    digitalEmployeeId: bindingForm.digitalEmployeeId || undefined,
    wakeWords: parseWakeWords(wakeWordsText.value)
  };
  if (bindingForm.id) {
    await imConnectorService.updateConversationBinding(bindingForm.id, payload);
  } else {
    await imConnectorService.createConversationBinding(payload);
  }
  bindingDialog.visible = false;
  ElMessage.success('会话绑定已保存');
  await loadBindings();
}

async function deleteBinding(row: ImConversationBinding) {
  if (!row.id) return;
  try {
    await confirmDelete(`确认删除会话绑定 ${row.conversationName || row.externalConversationId}？`);
  } catch {
    return;
  }
  try {
    await imConnectorService.deleteConversationBinding(row.id);
  } catch (error) {
    showError(error, '删除会话绑定失败');
    return;
  }
  ElMessage.success('会话绑定已删除');
  await loadBindings();
}

async function openBindSessionDialog() {
  const connector = selectedConnector.value;
  if (!connector?.connectorCode) {
    ElMessage.warning('请先选择连接器');
    return;
  }
  stopBindSessionPoll();
  Object.assign(bindSession, {
    id: undefined,
    code: '',
    qrContent: '',
    status: '',
    expiresAt: '',
    externalUserId: ''
  });
  bindCountdown.value = 0;
  bindSessionDialog.visible = true;
  loading.bindSession = true;
  try {
    const created = await imConnectorService.createBindSession(connector.connectorCode);
    Object.assign(bindSession, created);
    await nextTick();
    renderBindQr(created.qrContent || created.code || '');
    startBindSessionPoll();
  } catch (error) {
    showError(error, '创建扫码绑定失败');
    bindSessionDialog.visible = false;
  } finally {
    loading.bindSession = false;
  }
}

function renderBindQr(text: string) {
  const el = bindQrRef.value || document.getElementById('im-bind-qr-canvas');
  if (!el || !text) {
    return;
  }
  el.innerHTML = '';
  // eslint-disable-next-line no-new
  new QRCode(el, { width: 180, height: 180, text });
}

function startBindSessionPoll() {
  stopBindSessionPoll();
  refreshBindCountdown();
  bindCountdownTimer = setInterval(refreshBindCountdown, 1000);
  bindPollTimer = setInterval(async () => {
    if (!bindSession.id) {
      return;
    }
    try {
      const latest = await imConnectorService.getBindSessionStatus(bindSession.id);
      Object.assign(bindSession, latest);
      if (latest.status === 'CONSUMED') {
        stopBindSessionPoll();
        ElMessage.success(`已绑定钉钉用户 ${latest.externalUserId || ''}`);
        await loadIdentities();
      }
      if (latest.status === 'EXPIRED') {
        stopBindSessionPoll();
        ElMessage.warning('绑定码已过期，请重新生成');
      }
    } catch {
      // 轮询失败不打断操作，下一轮继续。
    }
  }, 2000);
}

function refreshBindCountdown() {
  if (!bindSession.expiresAt) {
    bindCountdown.value = 0;
    return;
  }
  const remain = Math.floor((new Date(bindSession.expiresAt).getTime() - Date.now()) / 1000);
  bindCountdown.value = remain > 0 ? remain : 0;
}

function stopBindSessionPoll() {
  if (bindPollTimer) {
    clearInterval(bindPollTimer);
    bindPollTimer = null;
  }
  if (bindCountdownTimer) {
    clearInterval(bindCountdownTimer);
    bindCountdownTimer = null;
  }
}

async function copyBindCode() {
  if (!bindSession.code) {
    return;
  }
  try {
    await navigator.clipboard.writeText(bindSession.code);
    ElMessage.success('短码已复制');
  } catch {
    ElMessage.warning('复制失败，请手动选择短码');
  }
}

async function openIdentityDialog(row?: ImUserIdentity) {
  await loadIamUsers();
  const connector = selectedConnector.value;
  identityDialog.mode = row?.id ? 'edit' : 'create';
  assign(identityForm, row || emptyIdentity());
  identityForm.provider = connector?.provider || identityForm.provider;
  identityForm.connectorCode = connector?.connectorCode || identityForm.connectorCode;
  if (identityForm.userId) {
    fillIdentityUserInfo(identityForm.userId, false);
  }
  identityDialog.visible = true;
}

async function openIdentityDialogFromMessage(row: ImMessage) {
  if (!loadedTabs.users) {
    await loadIdentities();
  }
  const matched = visibleIdentities.value.find(item => item.externalUserId === row.externalUserId);
  if (matched) {
    await openIdentityDialog(matched);
    return;
  }
  await openIdentityDialog({
    ...emptyIdentity(),
    provider: row.provider || selectedConnector.value?.provider || 'DINGTALK',
    connectorCode: row.connectorCode || selectedConnectorCode.value,
    externalUserId: row.externalUserId || '',
    userId: row.userId || ''
  });
}

async function saveIdentity() {
  if (!identityForm.externalUserId) {
    ElMessage.warning('请填写钉钉用户 ID');
    return;
  }
  if (!identityForm.userId) {
    ElMessage.warning('请选择系统用户');
    return;
  }
  if (identityForm.id) {
    await imConnectorService.updateUserIdentity(identityForm.id, identityForm);
  } else {
    await imConnectorService.createUserIdentity(identityForm);
  }
  identityDialog.visible = false;
  ElMessage.success('用户绑定已保存');
  if (activeTab.value === 'users') {
    await loadIdentities();
  } else {
    loadedTabs.users = false;
  }
}

function handleIdentityUserChange(value?: string | number) {
  fillIdentityUserInfo(value, true);
}

function fillIdentityUserInfo(value?: string | number, clearWhenMissing = true) {
  const user = findIamUser(value);
  if (!user) {
    if (clearWhenMissing) {
      identityForm.username = '';
      identityForm.nickName = '';
    }
    return;
  }
  identityForm.userId = iamUserValue(user);
  identityForm.username = user.username || '';
  identityForm.nickName = user.nickName || user.username || '';
  if (!identityForm.contact) {
    identityForm.contact = iamUserContact(user);
  }
}

async function deleteIdentity(row: ImUserIdentity) {
  if (!row.id) return;
  try {
    await confirmDelete(`确认删除外部用户 ${row.externalUserId} 的绑定？`);
  } catch {
    return;
  }
  try {
    await imConnectorService.deleteUserIdentity(row.id);
  } catch (error) {
    showError(error, '删除用户绑定失败');
    return;
  }
  ElMessage.success('用户绑定已删除');
  await loadIdentities();
}

async function searchMessages() {
  messageFilters.current = 1;
  await loadMessages();
}

async function handleMessageSizeChange() {
  messageFilters.current = 1;
  await loadMessages();
}

function syncBindingDefaultTrigger() {
  bindingForm.triggerPolicy = bindingForm.conversationType === 'GROUP' ? 'MENTION' : 'ALWAYS';
}

function updateConnectorConfig(value: Record<string, unknown> | unknown[] | string) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  connectorConfigTouched.value = true;
  connectorForm.config = value;
}

function handleConnectorProviderChange() {
  connectorConfigTouched.value = true;
  connectorForm.config = {
    ...defaultConnectorConfig(connectorForm.provider || 'DINGTALK'),
    ...(connectorForm.config || {})
  };
}

function markConnectorConfigTouched() {
  connectorConfigTouched.value = true;
}

function defaultConnectorConfig(provider: string): Record<string, any> {
  const isDingTalk = provider.toUpperCase() === 'DINGTALK';
  return {
    mode: isDingTalk ? 'STREAM' : 'HTTP_CALLBACK',
    streamAutoStart: isDingTalk,
    webhookReplyEnabled: true,
    replyWebhookAllowedHosts: isDingTalk ? ['oapi.dingtalk.com', 'api.dingtalk.com'] : ['qyapi.weixin.qq.com'],
    userResolveEnabled: isDingTalk,
    thinkingMessageEnabled: true,
    thinkingMessageText: ''
  };
}

function normalizeReplyWebhookAllowedHosts(value: unknown): string[] {
  const values = Array.isArray(value) ? value : typeof value === 'string' ? value.split(',') : [];
  return [
    ...new Set(
      values
        .map(item =>
          String(item || '')
            .trim()
            .toLowerCase()
        )
        .filter(Boolean)
    )
  ];
}

function isPureReplyWebhookHost(value: string): boolean {
  if (
    !value ||
    value === 'localhost' ||
    value.includes('://') ||
    value.includes('/') ||
    value.includes('@') ||
    value.includes(':') ||
    value.includes('*') ||
    isIpLiteral(value)
  ) {
    return false;
  }
  return /^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(value);
}

function isIpLiteral(value: string): boolean {
  if (value.includes(':')) return true;
  const parts = value.split('.');
  return parts.length === 4 && parts.every(part => /^\d+$/.test(part));
}

function emptyProviderConfig(provider: string): ProviderConfigForm {
  return {
    provider: provider.toUpperCase(),
    config: defaultProviderConfig(provider),
    status: 'enabled',
    displayOrder: provider.toUpperCase() === 'DINGTALK' ? 10 : 20
  };
}

function defaultProviderConfig(provider: string): Record<string, any> {
  const isDingTalk = provider.toUpperCase() === 'DINGTALK';
  return {
    developerConsoleUrl: isDingTalk ? 'https://open-dev.dingtalk.com' : 'https://work.weixin.qq.com/wework_admin/frame',
    setupSessionTtlMinutes: 30,
    credentialValidationTimeoutMs: 5000,
    streamConnectTimeoutMs: 5000,
    streamReconnectDelayMs: 5000,
    streamWorkerEnabled: isDingTalk,
    streamLeaseTtlSeconds: 90,
    streamLeaseRenewSeconds: 30,
    userResolveEnabled: isDingTalk,
    userResolveTimeoutMs: 5000,
    tokenUrl: isDingTalk ? 'https://api.dingtalk.com/v1.0/oauth2/accessToken' : '',
    userInfoUrlTemplate: isDingTalk ? 'https://api.dingtalk.com/v1.0/contact/users/{userId}' : '',
    platformSendTimeoutMs: 5000,
    iamTimeoutMs: 5000,
    delegatedTokenTtlSeconds: 900,
    delegatedClientId: 'pc-web',
    delegatedDevice: 'delegated-agent',
    autoBindByContact: false,
    defaultSingleTriggerPolicy: 'ALWAYS',
    defaultGroupTriggerPolicy: 'MENTION',
    unboundUserMessage: '未识别到您的系统账号，请先联系管理员完成 IM 用户绑定。',
    disabledConnectorMessage: '当前 IM 对话连接器未启用。',
    unsupportedMessageTypeMessage: '暂时只支持文本消息。',
    thinkingMessageEnabled: true,
    thinkingMessageText: '正在思考中，请耐心等候...',
    agentInvokeFailedMessage: 'Agent 执行失败，请稍后再试。',
    agentInvokeTimeoutMessage: '本次分析超时，请缩小查询范围或补充筛选条件后重试；如果已触发后台任务，请稍后查看结果。'
  };
}

function emptyConnector(): ImConnector {
  return {
    connectorCode: '',
    connectorName: '',
    provider: 'DINGTALK',
    authType: 'ROBOT',
    config: defaultConnectorConfig('DINGTALK'),
    credentialRef: '',
    defaultAgentId: '',
    directEnabled: true,
    groupEnabled: false,
    status: 'enabled',
    displayOrder: 0
  };
}

function emptyBinding(): ImConversationBinding {
  return {
    provider: selectedConnector.value?.provider || 'DINGTALK',
    connectorCode: selectedConnectorCode.value,
    conversationType: 'SINGLE',
    externalConversationId: '',
    conversationName: '',
    agentId: selectedConnector.value?.defaultAgentId || '',
    digitalEmployeeId: '',
    triggerPolicy: 'ALWAYS',
    wakeWords: [],
    sessionScope: 'PER_USER',
    status: 'enabled',
    displayOrder: 0
  };
}

function emptyIdentity(): ImUserIdentity {
  return {
    provider: selectedConnector.value?.provider || 'DINGTALK',
    connectorCode: selectedConnectorCode.value,
    externalUserId: '',
    unionId: '',
    contact: '',
    userId: '',
    username: '',
    nickName: '',
    bindStatus: 'enabled',
    bindSource: 'MANUAL'
  };
}

function assign<T extends object>(target: T, source: object) {
  const record = target as Record<string, unknown>;
  Object.keys(record).forEach(key => Reflect.deleteProperty(record, key));
  Object.assign(target, JSON.parse(JSON.stringify(source || {})));
}

function compact<T extends object>(source: T) {
  return Object.entries(source).reduce<Record<string, unknown>>((result, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      result[key] = value;
    }
    return result;
  }, {});
}

function parseWakeWords(value: string) {
  return value
    .split(/[\s,，;；]+/)
    .map(item => item.trim())
    .filter(Boolean);
}

function agentLabel(agent: Agent) {
  return agent.name || `Agent ${agent.id}`;
}

function agentName(agentId?: string | null) {
  const id = String(agentId ?? '');
  if (!id) return '-';
  const agent = agentOptions.value.find(item => String(item.id ?? '') === id);
  return agent ? agentLabel(agent) : id;
}

function employeeName(employeeId?: string | null) {
  const id = String(employeeId ?? '');
  if (!id) return '-';
  const employee = employeeOptions.value.find(item => String(item.id ?? '') === id);
  if (!employee) return id;
  const name = employee.employeeName || `员工 #${id}`;
  return employee.employeeCode ? `${name}（${employee.employeeCode}）` : name;
}

function iamUserValue(user: IamBasicUser) {
  return String(user.id ?? user.userId ?? '');
}

function iamUserContact(user: IamBasicUser) {
  return user.mobile || user.phone || user.email || '';
}

function iamUserLabel(user: IamBasicUser) {
  const name = user.nickName || user.username || iamUserValue(user);
  return user.username && user.username !== name ? `${name}（${user.username}）` : name;
}

function iamUserMeta(user: IamBasicUser) {
  return [iamUserValue(user), iamUserContact(user)].filter(Boolean).join(' / ');
}

function findIamUser(value?: string | number) {
  const id = String(value ?? '');
  if (!id) return undefined;
  return iamUserOptions.value.find(item => iamUserValue(item) === id);
}

function identityUserLabel(identity: ImUserIdentity) {
  const user = findIamUser(identity.userId);
  if (user) return iamUserLabel(user);
  return identity.nickName || identity.username || identity.userId || '-';
}

function messageUserLabel(message: ImMessage) {
  const user = findIamUser(message.userId);
  if (user) return iamUserLabel(user);
  return message.userId || '-';
}

function providerLabel(value?: string) {
  return providerOptions.find(item => item.value === value)?.label || value || '-';
}

function statusLabel(value?: string) {
  const labels: Record<string, string> = {
    enabled: '启用',
    disabled: '禁用'
  };
  return labels[value || ''] || value || '-';
}

function streamStatusLabel(connector?: ImConnector) {
  if (!connector) {
    return '-';
  }
  if (connector.streamStatus === 'CONNECTED') {
    return '本机已连接';
  }
  const error = connector.streamLastError || '';
  if (error.includes('其他实例已持有')) {
    return '租约在其他实例';
  }
  if (error.includes('已被连接器')) {
    return 'Client ID 已被占用';
  }
  if (connector.streamStatus === 'FAILED') {
    return '连接失败';
  }
  return '未连接';
}

function streamStatusTagType(connector?: ImConnector) {
  if (connector?.streamStatus === 'CONNECTED') {
    return 'success';
  }
  if (connector?.streamStatus === 'FAILED' || (connector?.streamLastError || '').includes('占用')) {
    return 'danger';
  }
  if ((connector?.streamLastError || '').includes('其他实例已持有')) {
    return 'warning';
  }
  return 'info';
}

function conversationTypeLabel(value?: string) {
  const labels: Record<string, string> = {
    SINGLE: '单聊',
    GROUP: '群聊'
  };
  return labels[value || ''] || value || '-';
}

function triggerPolicyLabel(value?: string) {
  const labels: Record<string, string> = {
    ALWAYS: '直接触发',
    MENTION: '@机器人',
    WAKE_WORD: '唤醒词',
    MENTION_OR_WAKE_WORD: '@机器人或唤醒词'
  };
  return labels[value || ''] || value || '-';
}

function bindSourceLabel(value?: string) {
  const labels: Record<string, string> = {
    MANUAL: '手动绑定',
    CONTACT_AUTO: '联系人自动绑定',
    QR_PAIR: '扫码绑定'
  };
  return labels[value || ''] || value || '-';
}

function directionLabel(value?: string) {
  const labels: Record<string, string> = {
    INBOUND: '入站',
    OUTBOUND: '出站'
  };
  return labels[value || ''] || value || '-';
}

function messageStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    RECEIVED: '已接收',
    SKIPPED: '跳过',
    AUTH_FAILED: '授权失败',
    PROCESSING: '处理中',
    PENDING: '待发送',
    SUCCESS: '成功',
    FAILED: '失败'
  };
  return labels[value || ''] || value || '-';
}

function messageStatusType(value?: string) {
  if (value === 'SUCCESS' || value === 'RECEIVED') return 'success';
  if (value === 'FAILED' || value === 'AUTH_FAILED') return 'danger';
  if (value === 'PROCESSING' || value === 'PENDING') return 'warning';
  return 'info';
}

function formatDateTime(value?: string | number | Date) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString('zh-CN', { hour12: false });
}

function showError(error: unknown, fallback: string) {
  if (shouldShowLocalApiError(error)) {
    ElMessage.error(extractApiErrorMessage(error, fallback));
  }
}

async function confirmDelete(message: string) {
  await ElMessageBox.confirm(message, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  });
}
</script>

<style scoped>
.im-connector-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.im-connector-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.im-toolbar-card {
  flex: 0 0 auto;
}

.page-toolbar,
.detail-header,
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-toolbar h1,
.detail-header h2 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0;
}

.page-toolbar p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.im-layout {
  display: grid;
  grid-template-columns: minmax(260px, 300px) minmax(0, 1fr);
  gap: 8px;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.connector-list,
.connector-detail {
  overflow: hidden;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.connector-list {
  display: flex;
  overflow-y: auto;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  scrollbar-color: transparent transparent;
  scrollbar-width: thin;
}

.panel-title {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.provider-config-panel {
  flex: 0 0 auto;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  padding: 12px;
}

.provider-config-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.provider-config-card {
  position: relative;
  display: flex;
  overflow: hidden;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  min-height: 76px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 12px 86px 12px 12px;
}

.provider-card-main,
.provider-card-meta {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.provider-card-main {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.provider-card-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.provider-config-action {
  position: absolute;
  top: 12px;
  right: 12px;
}

.connector-item {
  position: relative;
  display: block;
  width: 100%;
  min-height: 82px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  padding: 12px;
  color: inherit;
  text-align: left;
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease;
}

.connector-item:hover,
.connector-item.active {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 7%, var(--el-bg-color));
  box-shadow: 0 8px 18px rgb(64 158 255 / 12%);
}

.connector-main {
  display: flex;
  min-height: 58px;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding-right: 104px;
  cursor: pointer;
}

.connector-main:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.connector-name {
  min-width: 0;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.connector-actions {
  display: flex;
  position: absolute;
  top: 10px;
  right: 12px;
  flex-shrink: 0;
  align-items: center;
  gap: 2px;
}

.connector-actions :deep(.el-button) {
  height: 22px;
  padding: 0;
}

.connector-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.connector-code,
.table-title span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.connector-detail {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  padding: 16px;
  scrollbar-color: transparent transparent;
  scrollbar-width: thin;
}

.connector-list::-webkit-scrollbar,
.connector-detail::-webkit-scrollbar {
  width: 4px;
  height: 4px;
}

.connector-list::-webkit-scrollbar-button,
.connector-detail::-webkit-scrollbar-button {
  -webkit-appearance: none;
  appearance: none;
  display: none;
  width: 0;
  height: 0;
  background: transparent;
}

.connector-list::-webkit-scrollbar-corner,
.connector-list::-webkit-scrollbar-track-piece,
.connector-list::-webkit-scrollbar-track,
.connector-detail::-webkit-scrollbar-corner,
.connector-detail::-webkit-scrollbar-track-piece,
.connector-detail::-webkit-scrollbar-track {
  background: transparent;
}

.connector-list::-webkit-scrollbar-thumb,
.connector-detail::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background-color: transparent;
}

.connector-list:hover,
.connector-detail:hover {
  scrollbar-color: rgba(144, 147, 153, 0.22) transparent;
}

.connector-list:hover::-webkit-scrollbar-thumb,
.connector-list:hover::-webkit-scrollbar-thumb:hover,
.connector-list:hover::-webkit-scrollbar-thumb:active,
.connector-detail:hover::-webkit-scrollbar-thumb,
.connector-detail:hover::-webkit-scrollbar-thumb:hover,
.connector-detail:hover::-webkit-scrollbar-thumb:active {
  background-color: rgba(144, 147, 153, 0.22);
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.detail-header {
  flex: 0 0 auto;
}

.detail-tabs {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column !important;
  margin-top: 12px;
  min-height: 0;
}

.detail-tabs :deep(.el-tabs__header) {
  order: 0;
  flex: 0 0 auto;
}

.detail-tabs :deep(.el-tabs__content) {
  order: 1;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.table-tab-pane {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.table-tab-pane .table-header,
.table-tab-pane .log-filters,
.table-tab-pane .pagination-row {
  flex: 0 0 auto;
}

.basic-tab-pane {
  overflow-y: auto;
  height: 100%;
  min-height: 0;
}

.im-table-fill {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.basic-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.kv {
  min-width: 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  padding: 10px 12px;
}

.kv span {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.kv strong {
  display: block;
  overflow: hidden;
  margin-top: 4px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-header {
  margin-bottom: 10px;
  border-bottom: 0;
  padding: 0;
}

.table-header::after {
  display: none;
}

.table-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.log-filters {
  display: grid;
  grid-template-columns: repeat(6, minmax(120px, 1fr)) auto;
  gap: 8px;
  margin-bottom: 10px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.test-form {
  max-width: 720px;
}

.test-result {
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: 720px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  padding: 12px;
}

.field-tip {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.bind-session-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  min-height: 220px;
}

.bind-qr {
  width: 180px;
  height: 180px;
}

.bind-qr img,
.bind-qr canvas {
  width: 180px;
  height: 180px;
}

.bind-code-row {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 18px;
  letter-spacing: 1px;
}

.bind-session-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.form-section {
  margin-bottom: 14px;
}

.form-section h3 {
  margin: 0 0 10px;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
}

.provider-form-grid,
.provider-message-grid,
.connector-form-grid,
.connector-options-grid {
  display: grid;
  gap: 0 16px;
}

.provider-form-grid,
.connector-form-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.provider-message-grid {
  grid-template-columns: 1fr;
}

.connector-options-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.provider-form :deep(.el-form-item),
.connector-form :deep(.el-form-item) {
  min-width: 0;
}

.provider-form :deep(.el-form-item__content),
.provider-form :deep(.el-select),
.provider-form :deep(.el-input-number),
.connector-form :deep(.el-form-item__content),
.connector-form :deep(.el-select),
.connector-form :deep(.el-input-number),
.connector-config-field :deep(.json-object-editor) {
  width: 100%;
}

.connector-config-field {
  margin-bottom: 0;
}

.iam-user-option {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.iam-user-option small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .im-layout {
    grid-template-columns: 1fr;
  }

  .connector-list {
    max-height: 260px;
  }

  .basic-grid,
  .log-filters {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 720px) {
  .connector-options-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .provider-form-grid,
  .connector-form-grid,
  .connector-options-grid {
    grid-template-columns: 1fr;
  }
}
</style>
