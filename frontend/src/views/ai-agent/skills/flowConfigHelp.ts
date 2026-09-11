import type { ConfigHelp } from '@/views/ai-agent/components/common/configHelp';

const json = (value: unknown) => JSON.stringify(value, null, 2);

export const flowConfigHelp: Record<string, ConfigHelp> = {
  flowDefinition: {
    title: 'FLOW 定义',
    description: '定义后端确定性引擎执行的节点、顺序和分支；模型不能自由跳转。',
    fields: [
      { name: 'schemaVersion', description: '新建流程使用 skill-flow/v2，旧 v1 仍可导入。' },
      { name: 'startNode', description: '必须存在的起始节点 ID。' },
      { name: 'nodes', description: '节点 ID 必须唯一，至少包含 end 节点。' }
    ],
    minimalExample: json({ schemaVersion: 'skill-flow/v2', startNode: 'end', nodes: [{ id: 'end', type: 'end' }] }),
    commonErrors: ['next 或分支指向不存在的节点。', 'execute 没有前置 confirm。']
  },
  variablesSchema: {
    title: '变量结构',
    description: '声明 FLOW 可读写的变量和字段类型；时间语义通过字段上的 x-temporal 声明。',
    fields: [
      { name: 'properties', description: '字段定义。' },
      { name: 'required', description: '必填字段列表。' },
      { name: 'x-temporal', description: '日期、时区和落点语义。' }
    ],
    minimalExample: json({ type: 'object', properties: { quantity: { type: 'integer' } }, required: ['quantity'] }),
    commonErrors: ['节点引用未声明路径。', '顶层和 FLOW 内 variablesSchema 不一致。']
  },
  extract: {
    title: 'extract：抽取字段',
    description: '允许模型从用户消息中抽取指定字段，流程跳转和写入仍由后端控制。',
    fields: [
      { name: 'schema', description: '本节点允许模型返回的 JSON Schema。' },
      { name: 'outputPath', description: '抽取结果写入位置，使用 JSON Pointer，例如 /input。' },
      { name: 'requiredPaths', description: '抽取后必须存在的完整 JSON Pointer。' },
      { name: 'extraction', description: '模型调用、Schema 范围和失败处理策略。' }
    ],
    minimalExample: json({
      outputPath: '/input',
      schema: { type: 'object', properties: { customerName: { type: 'string' } } },
      requiredPaths: ['/input/customerName'],
      extraction: { modelPolicy: 'ALWAYS', schemaMode: 'FULL', failurePolicy: 'WAIT_RETRY' }
    }),
    completeExample: json({
      outputPath: '/input',
      schema: {
        type: 'object',
        properties: { customerName: { type: 'string' }, quantity: { type: 'integer' } }
      },
      requiredPaths: ['/input/customerName'],
      extraction: {
        modelPolicy: 'IF_UNRESOLVED',
        schemaMode: 'CONFIGURED_PATHS',
        paths: ['/input/customerName', '/input/quantity'],
        failurePolicy: 'WAIT_RETRY'
      }
    }),
    commonErrors: ['把未声明在变量 Schema 中的路径写入 extraction.paths。', '依赖模型输出决定下一节点。']
  },
  collect: {
    title: 'collect：收集用户输入',
    description: '当必填字段缺失时暂停流程并询问用户；Web 使用表单，IM 使用文本 fallback。',
    fields: [
      { name: 'requiredPaths/requiredAnyPaths', description: '必须收集的完整 JSON Pointer。' },
      { name: 'schema', description: '允许接收和抽取的字段结构。' },
      { name: 'prompt', description: '没有动态字段提示时使用的提问文案。' },
      { name: 'collectionPresentation', description: '按缺失字段生成 Web/IM 可读提示。' },
      { name: 'uiActions', description: 'v2 等待节点动作；IM 收集节点仍直接发送待补充内容。' }
    ],
    minimalExample: json({
      requiredPaths: ['/input/arrivalTime'],
      schema: { type: 'object', properties: { arrivalTime: { type: 'string' } } },
      prompt: '请提供到达时间',
      uiActions: [{ actionId: 'submit-input', type: 'SUBMIT', label: '提交' }]
    }),
    completeExample: json({
      requiredPaths: ['/input/arrivalTime'],
      schema: { type: 'object', properties: { arrivalTime: { type: 'string' } } },
      prompt: '请提供到达时间',
      collectionPresentation: {
        mode: 'MISSING_ONLY',
        fieldPrompts: { '/input/arrivalTime': '请提供到达时间，例如 2026-07-20 09:00' }
      },
      extraction: { modelPolicy: 'IF_UNRESOLVED', schemaMode: 'UNRESOLVED_REQUIRED', failurePolicy: 'WAIT_RETRY' },
      uiActions: [{ actionId: 'submit-input', type: 'SUBMIT', label: '提交' }]
    }),
    commonErrors: ['只配置 Web 按钮，没有文本输入方式。', '把必填路径写成不存在的字段。']
  },
  resolve: {
    title: 'resolve：查询候选项',
    description: '使用已绑定的 READ Tool 查询候选数据，不能引用未发布或未绑定的工具版本。',
    fields: [
      { name: 'resourceVersionId', description: '固定的 Tool 发布版本 ID。' },
      { name: 'argumentMappings', description: 'Tool 入参名到 FLOW JSON Pointer 的映射。' },
      { name: 'outputPath', description: 'Tool 返回值写入的 JSON Pointer。' },
      { name: 'dependsOn/requiresAll/requiresAny', description: 'Resolver 依赖和执行前置条件。' }
    ],
    minimalExample: json({
      resourceVersionId: '101',
      outputPath: '/resolved/customers',
      argumentMappings: { keyword: '/input/customerName' }
    }),
    commonErrors: ['引用 WRITE Tool。', '节点中的 Tool 版本没有同时绑定到当前 Skill。']
  },
  select: {
    title: 'select：选择候选项',
    description: '将用户的序号、名称、编码转换成候选项中的唯一值。',
    fields: [
      { name: 'optionsPath', description: '候选列表所在的 JSON Pointer。' },
      { name: 'targetPath', description: '选中值写入路径。' },
      { name: 'labelPath/valuePath', description: '候选项中的显示字段和值字段 JSON Pointer。' },
      { name: 'textSearch', description: '未精确命中时调用哪个 READ Resolver 搜索。' },
      { name: 'allowSkip/skipCommands', description: '是否允许跳过及 IM 跳过文本。' }
    ],
    minimalExample: json({
      optionsPath: '/resolved/customers',
      targetPath: '/selection/customer',
      labelPath: '/name',
      valuePath: '/id',
      textSearch: {
        enabled: true,
        resolverNode: 'resolve-customers',
        argumentName: 'keyword',
        matchPaths: ['/name', '/code'],
        notFoundText: '未找到客户，请换一个名称或编码。'
      },
      uiActions: [{ actionId: 'select-customer', type: 'SELECT', label: '选择' }]
    }),
    commonErrors: ['没有 textSearch 却要求 IM 通过名称选择。', '候选路径不是数组。']
  },
  review: {
    title: 'review：展示并接收修改',
    description: '向用户展示摘要并接收修改内容；模型只能抽取修改字段。',
    fields: [
      { name: 'schema/extraction', description: '限定模型从用户修改文本中可抽取的字段。' },
      { name: 'prompt', description: 'Web/IM 共用的核对提示。' },
      { name: 'refreshNext/refreshRoutes', description: '修改后重新查询或校验的确定性路由。' },
      { name: 'uiActions', description: 'SUBMIT、CLEAR_REFERENCE 或 CANCEL 动作。' }
    ],
    minimalExample: json({
      prompt: '请核对当前信息，需要调整时可直接说明。',
      schema: { type: 'object', properties: { quantity: { type: 'integer' } } },
      refreshNext: 'validate-input',
      extraction: { modelPolicy: 'ALWAYS', schemaMode: 'FULL', failurePolicy: 'WAIT_RETRY' },
      uiActions: [{ actionId: 'review-submit', type: 'SUBMIT', label: '信息无误' }]
    }),
    commonErrors: ['允许修改不可变或未声明字段。', 'IM 没有直接文本修改路径。']
  },
  merge: {
    title: 'merge：合并变量',
    description: '按确定性规则把源对象合并到目标路径。',
    fields: [
      { name: 'sourcePath', description: '源对象路径。' },
      { name: 'targetPath', description: '目标对象路径。' },
      { name: 'strategy', description: 'FILL_MISSING、OVERWRITE_PRESENT 或 REPLACE。' }
    ],
    minimalExample: json({ sourcePath: '/resolved/draft', targetPath: '/input', strategy: 'FILL_MISSING' })
  },
  validate: {
    title: 'validate：校验字段',
    description: '由后端按变量 Schema 和业务规则校验，失败进入明确的失败节点。',
    fields: [
      { name: 'targetPath', description: '被校验的数据路径。' },
      { name: 'schema', description: '局部校验 Schema。' },
      { name: 'invalidNext', description: '校验失败时跳转的节点；不配置则等待用户修正。' }
    ],
    minimalExample: json({
      targetPath: '/input',
      invalidNext: 'collect-input',
      schema: { type: 'object', properties: { quantity: { type: 'integer' } }, required: ['quantity'] }
    })
  },
  switch: {
    title: 'switch：条件分支',
    description: '后端按条件匹配分支，不由模型自由选择目标节点。',
    fields: [
      { name: 'branches', description: '在节点抽屉的“分支配置”中维护，不写入 config。' },
      {
        name: 'condition.op',
        description: '支持 eq、ne、empty、notEmpty、gt、gte、lt、lte、in、contains、all、any、not。'
      }
    ],
    minimalExample: json({}),
    commonErrors: ['把 branches 写进节点 config。', '条件字段使用 operator 而不是后端读取的 op。']
  },
  confirm: {
    title: 'confirm：确认后继续',
    description: 'WRITE Tool 前必须经过确认；Web 和 IM 都需要确认与取消文本路径。',
    fields: [
      { name: 'summary/summaryPath', description: '确认文案和摘要数据路径。' },
      { name: 'editNode', description: '用户选择修改时返回的节点。' },
      { name: 'uiActions', description: 'CONFIRM、EDIT 或 CANCEL；这些动作的 label 可作为 IM 文本命令。' }
    ],
    minimalExample: json({
      summaryPath: '/input',
      summary: '请确认是否提交。',
      editNode: 'review-input',
      uiActions: [
        { actionId: 'confirm-submit', type: 'CONFIRM', label: '确认提交', value: true },
        { actionId: 'edit-flow', type: 'EDIT', label: '返回修改' }
      ]
    }),
    commonErrors: ['execute 前没有 confirm。', '只有按钮没有 IM 文本确认命令。']
  },
  execute: {
    title: 'execute：执行工具',
    description: '后端确认状态后执行固定版本的 WRITE Tool，并按 successCondition 判断成功。',
    fields: [
      { name: 'resourceVersionId', description: '固定的 WRITE + FLOW_ONLY Tool 版本。' },
      { name: 'argumentMappings', description: 'Tool 入参名到 FLOW JSON Pointer 的确定性映射。' },
      { name: 'outputPath', description: 'Tool 返回值写入的 JSON Pointer。' },
      { name: 'successCondition', description: '后端判断成功的结果路径和条件。' }
    ],
    minimalExample: json({
      resourceVersionId: '202',
      argumentMappings: { quantity: '/input/quantity' },
      outputPath: '/result',
      successCondition: { op: 'eq', path: '/result/success', value: true }
    }),
    commonErrors: ['没有确认节点。', 'WRITE Tool 未配置幂等或成功条件。']
  },
  present: {
    title: 'present：展示结果',
    description: '将结果按 Web 卡片或 IM 可读文本渲染，不改变流程变量。',
    fields: [
      { name: 'dataPath', description: '展示数据路径。' },
      { name: 'text', description: '支持 {{path}} 占位符的展示文案。' },
      { name: 'waitForAction/action', description: '是否展示后继续等待，以及等待动作名。' }
    ],
    minimalExample: json({ dataPath: '/result', format: 'markdown', text: '处理完成：{{result.code}}' })
  },
  handoff: {
    title: 'handoff：转交人工',
    description: '暂停当前自动流程并返回明确的人工办理提示。',
    fields: [{ name: 'text', description: '交接给用户的可读提示。' }],
    minimalExample: json({ text: '请转人工客服继续处理。' })
  },
  end: {
    title: 'end：正常结束',
    description: '结束 FLOW 并返回最终结果。',
    fields: [{ name: 'text', description: '可选结束文案。' }],
    minimalExample: json({ text: '已完成处理' })
  },
  error: {
    title: 'error：错误处理',
    description: '把可恢复或不可恢复错误映射到明确的提示和结束路径。',
    fields: [
      { name: 'text', description: '用户可读的错误提示。' },
      { name: 'errorCode', description: '可追踪的稳定错误码。' }
    ],
    minimalExample: json({ text: '处理失败，请稍后重试。', errorCode: 'FLOW_BUSINESS_ERROR' })
  },
  literalMappings: {
    title: 'literalMappings：固定值映射',
    description: '把用户文本别名映射为 Schema 字段的固定规范值，不执行表达式。',
    fields: [
      { name: '<field>', description: '必须是当前节点 schema.properties 中的字段名。' },
      { name: '<canonicalValue>', description: '规范值，对应值必须是非空别名数组。' }
    ],
    minimalExample: json({ businessType: { '2': ['长期租赁', '年租'], '3': ['临时租赁'] } }),
    commonErrors: ['字段未声明在同一节点的 schema.properties。', '同一别名映射到多个规范值。']
  },
  extraction: {
    title: 'extraction：受限抽取策略',
    description: '限制模型只能抽取哪些路径和格式。',
    fields: [
      { name: 'modelPolicy', description: 'ALWAYS 或 IF_UNRESOLVED。' },
      { name: 'schemaMode', description: 'FULL、CONFIGURED_PATHS 或 UNRESOLVED_REQUIRED。' },
      { name: 'paths', description: 'CONFIGURED_PATHS 使用的完整 JSON Pointer 数组。' },
      { name: 'failurePolicy', description: 'WAIT_RETRY；仅 extract 节点可使用 CONTINUE。' }
    ],
    minimalExample: json({
      modelPolicy: 'IF_UNRESOLVED',
      schemaMode: 'UNRESOLVED_REQUIRED',
      failurePolicy: 'WAIT_RETRY'
    })
  },
  textSearch: {
    title: 'textSearch：文本选择',
    description: '让 IM 用户通过序号、名称或编码选择候选项。',
    fields: [
      { name: 'enabled', description: '是否开启文本搜索。' },
      { name: 'resolverNode', description: '未命中当前候选时重新查询的 resolve 节点 ID。' },
      { name: 'argumentName', description: '搜索关键词覆盖的 Tool 入参名。' },
      { name: 'matchPaths', description: '候选 rawData 中允许精确匹配的 JSON Pointer。' },
      { name: 'notFoundText', description: '重新查询仍无结果时的 IM 提示。' }
    ],
    minimalExample: json({
      enabled: true,
      resolverNode: 'resolve-customer',
      argumentName: 'keyword',
      matchPaths: ['/companyName', '/companyCode'],
      notFoundText: '未找到客户，请换一个名称或编码。'
    })
  },
  collectionPresentation: {
    title: 'collectionPresentation：收集展示',
    description: '统一生成 Web 和 IM 都能理解的字段标签、示例和顺序。',
    fields: [
      { name: 'mode', description: '当前仅支持 MISSING_ONLY。' },
      { name: 'fieldPrompts', description: 'requiredPaths 中每个路径对应的完整提问文本。' }
    ],
    minimalExample: json({
      mode: 'MISSING_ONLY',
      fieldPrompts: { '/input/arrivalTime': '请提供到达时间，例如 2026-07-20 09:00' }
    })
  },
  uiActions: {
    title: 'uiActions：交互动作',
    description:
      '定义 Web 按钮和受支持的 IM 文本动作；运行时不读取 command 或 text。收集内容和候选选择仍按节点文本规则处理。',
    fields: [
      { name: 'actionId', description: '节点内唯一且非空的稳定动作 ID。' },
      { name: 'type', description: '动作类型，且必须与节点类型匹配。' },
      { name: 'label', description: 'Web 展示文案；CANCEL、SKIP、REVIEW、CONFIRM 等动作也按该文本精确匹配 IM。' },
      { name: 'value/payload', description: '可选动作值和扩展载荷；确认动作通常设置 value=true。' }
    ],
    minimalExample: json([
      { actionId: 'confirm-submit', type: 'CONFIRM', label: '确认提交', value: true },
      { actionId: 'edit-flow', type: 'EDIT', label: '返回修改' }
    ]),
    commonErrors: [
      '使用运行时不读取的 command 或 text 字段。',
      '同一节点 actionId 重复。',
      '动作类型与节点类型不匹配。'
    ]
  },
  successCondition: {
    title: 'successCondition：成功条件',
    description: '后端按工具返回值确定成功或失败，模型不能覆盖。',
    fields: [
      { name: 'path', description: '工具结果路径。' },
      { name: 'op', description: '受限条件操作符，例如 eq、notEmpty、all。' },
      { name: 'value', description: '需要比较的期望值。' }
    ],
    minimalExample: json({ op: 'eq', path: '/result/success', value: true })
  },
  flowRuntimeConfig: {
    title: 'FLOW 运行参数',
    description: '限制 Resolver 并发、波次、扇出数量和模型抽取预算。null 表示保留原值，空对象表示明确清空。',
    fields: [
      { name: 'maxParallelResolvers', description: '并行 Resolver 数量，后端限制为 1–16。' },
      { name: 'maxResolverWaves', description: 'Resolver 依赖波次数，后端限制为 1–32。' },
      { name: 'maxFanOutItems', description: '批量 Resolver 最大条目数，后端限制为 1–100。' },
      { name: 'extraction.maxTokens/timeoutMs', description: '模型字段抽取预算，仍受平台上限约束。' }
    ],
    minimalExample: json({
      maxParallelResolvers: 4,
      maxResolverWaves: 8,
      maxFanOutItems: 50,
      extraction: { maxTokens: 512, timeoutMs: 8000 }
    })
  },
  flowPolicyConfig: {
    title: 'FLOW 业务策略',
    description: '控制用户修改输入后的依赖字段失效和集合条目合并。等待、取消、跳过和确认属于各节点 config。',
    fields: [
      { name: 'inputInvalidationRules', description: '输入变化时清理依赖字段或集合字段。' },
      { name: 'collectionMergePolicies', description: '按路径配置集合匹配字段和身份字段清理规则。' }
    ],
    minimalExample: json({
      inputInvalidationRules: [{ whenAny: ['/input/companyName'], clearUnlessProvided: ['/input/companyId'] }],
      collectionMergePolicies: [
        {
          path: '/input/products',
          matchFields: ['productId', 'productNo', 'productName'],
          clearIdWhenChanged: ['productNo', 'productName'],
          idFields: ['productId']
        }
      ]
    })
  },
  'x-temporal': {
    title: 'x-temporal：时间字段语义',
    description: '在变量 Schema 中声明日期、时区和落点语义；运行时策略属于 Agent。',
    fields: [
      { name: 'kind', description: '日期或日期时间。' },
      { name: 'targetType', description: '目标时间类型。' },
      { name: 'dateLanding', description: '只有日期时的时间落点。' }
    ],
    minimalExample: json({ kind: 'DATE_OR_DATE_TIME', targetType: 'INSTANT', dateLanding: 'START_OF_DAY' })
  }
};

export const getFlowConfigHelp = (key: string) => flowConfigHelp[key];
