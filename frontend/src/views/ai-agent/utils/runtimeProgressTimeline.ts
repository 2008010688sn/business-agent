import type { RuntimeProgressEvent } from '@/views/ai-agent/services/graph';

export interface RuntimeProgressTimelineItem {
  key: string;
  label: string;
  status: string;
  statusLabel: string;
  durationText: string;
  depth: number;
  kind: 'group' | 'stage';
}

const TOOL_RUNNING = 'TOOL_RUNNING';
const TOOL_FINISHED = 'TOOL_FINISHED';
const FLOW_RESOLVER_RUNNING = 'FLOW_RESOLVER_RUNNING';
const FLOW_RESOLVER_FINISHED = 'FLOW_RESOLVER_FINISHED';
const FLOW_NODE_RUNNING = 'FLOW_NODE_RUNNING';
const FLOW_NODE_FINISHED = 'FLOW_NODE_FINISHED';
const TERMINAL_STAGE_CODES = new Set([
  'DONE',
  'FAILED',
  'CANCELLED',
  'COLLABORATOR_FINISHED',
  'COLLABORATOR_TIMED_OUT',
  'ORCHESTRATION_FAILED'
]);

const statusLabels: Record<string, string> = {
  running: '进行中',
  waiting: '等待用户操作',
  waiting_clarification: '等待补充信息',
  success: '完成',
  partial_success: '部分完成',
  failed: '失败',
  timed_out: '超时',
  cancelled: '取消'
};

const stageLabels: Record<string, string> = {
  AGENT_ENTERED: '进入智能体',
  MEMORY_LOADING: '读取上下文',
  MEMORY_READY: '上下文就绪',
  CLARIFY_CHECKING: '判断是否需要澄清',
  CLARIFY_DONE: '问题判断完成',
  CLARIFY_REQUIRED: '需要补充问题信息',
  CONFIG_LOADING: '加载智能体配置',
  CONFIG_READY: '智能体配置就绪',
  ROUTING: '规划/路由',
  ROUTING_DONE: '规划/路由完成',
  ORCHESTRATION_ROUTING: '识别问题并选择协作者',
  ORCHESTRATION_CLARIFICATION: '等待补充统计口径',
  ORCHESTRATION_COLLABORATING: '执行协作者',
  ORCHESTRATION_MERGING: '合并结果',
  FLOW_STARTED: '启动流程',
  FLOW_NODE_RUNNING: '执行流程节点',
  FLOW_NODE_FINISHED: '流程节点完成',
  [FLOW_RESOLVER_RUNNING]: '查询数据',
  [FLOW_RESOLVER_FINISHED]: '数据查询完成',
  FLOW_WAITING: '等待用户操作',
  FLOW_FINISHED: '流程完成',
  FLOW_FAILED: '流程失败',
  KNOWLEDGE_RETRIEVING: '检索知识',
  KNOWLEDGE_DONE: '知识检索完成',
  AGENT_INITIALIZING: '初始化智能体',
  AGENT_READY: '智能体就绪',
  THINKING: '思考中',
  [TOOL_RUNNING]: '调用工具',
  [TOOL_FINISHED]: '工具调用完成',
  MODEL_CALL: '调用模型',
  ANSWER_COMPOSING: '生成回答',
  FUSION_TRACE: '附件融合',
  LINK_RESOLVE: '链接解析'
};

const progressStagePairs = [
  {
    key: 'memory',
    label: '读取上下文',
    startCode: 'MEMORY_LOADING',
    doneCodes: ['MEMORY_READY']
  },
  {
    key: 'clarify',
    label: '判断是否需要澄清',
    startCode: 'CLARIFY_CHECKING',
    doneCodes: ['CLARIFY_DONE', 'CLARIFY_REQUIRED']
  },
  {
    key: 'config',
    label: '加载智能体配置',
    startCode: 'CONFIG_LOADING',
    doneCodes: ['CONFIG_READY']
  },
  {
    key: 'routing',
    label: '规划/路由',
    startCode: 'ROUTING',
    doneCodes: ['ROUTING_DONE']
  },
  {
    key: 'orchestration-routing',
    label: '识别问题并选择协作者',
    startCode: 'ORCHESTRATION_ROUTING',
    doneCodes: ['ORCHESTRATION_ROUTING']
  },
  {
    key: 'orchestration-collaborating',
    label: '执行协作者',
    startCode: 'ORCHESTRATION_COLLABORATING',
    doneCodes: ['ORCHESTRATION_COLLABORATING']
  },
  {
    key: 'orchestration-merging',
    label: '合并结果',
    startCode: 'ORCHESTRATION_MERGING',
    doneCodes: ['ORCHESTRATION_MERGING']
  },
  {
    key: 'knowledge',
    label: '检索知识',
    startCode: 'KNOWLEDGE_RETRIEVING',
    doneCodes: ['KNOWLEDGE_DONE']
  },
  {
    key: 'agent-init',
    label: '初始化智能体',
    startCode: 'AGENT_INITIALIZING',
    doneCodes: ['AGENT_READY']
  },
  {
    key: 'flow',
    label: '执行流程',
    startCode: 'FLOW_STARTED',
    doneCodes: ['FLOW_FINISHED', 'FLOW_WAITING', 'FLOW_FAILED']
  },
  {
    key: 'thinking',
    label: '思考中',
    startCode: 'THINKING',
    doneCodes: ['THINKING']
  },
  {
    key: 'answer-composing',
    label: '生成回答',
    startCode: 'ANSWER_COMPOSING',
    doneCodes: ['ANSWER_COMPOSING']
  }
] as const;

type ProgressStagePair = (typeof progressStagePairs)[number];

const stagePairByStartCode = new Map<string, ProgressStagePair>(progressStagePairs.map(pair => [pair.startCode, pair]));
const stagePairByDoneCode = new Map<string, ProgressStagePair>(
  progressStagePairs.flatMap(pair => pair.doneCodes.map(code => [code, pair]))
);

const isFiniteNumber = (value: number | null | undefined): value is number =>
  typeof value === 'number' && Number.isFinite(value);

const isFinishedEvent = (event: RuntimeProgressEvent) => Boolean(event.status && event.status !== 'running');

const formatDuration = (value: number) => {
  if (value < 1000) {
    return `${Math.max(0, Math.round(value))}ms`;
  }
  return `${(value / 1000).toFixed(value < 10000 ? 1 : 0)}s`;
};

const safeProgressText = (value?: string | null) => {
  const text = String(value || '')
    .replace(/[<>{}`]/g, '')
    .trim();
  return text.length > 48 ? `${text.slice(0, 48)}...` : text;
};

const eventKey = (event: RuntimeProgressEvent, index: number) =>
  [
    event.childRuntimeRequestId || event.runtimeRequestId || '',
    event.seq ?? index,
    event.stageCode,
    event.toolExecutionSeq || '',
    event.resolverId || event.nodeId || event.displayName || ''
  ].join('-');

const statusLabel = (status: string) => statusLabels[status] || status;

const findLastEvent = (events: RuntimeProgressEvent[], code: string, status?: string) => {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index];
    if (event.stageCode !== code || (status && event.status !== status)) {
      continue;
    }
    return event;
  }
  return null;
};

const stageDurationText = (
  startEvent: RuntimeProgressEvent | null,
  doneEvent: RuntimeProgressEvent | null,
  nowMs: number
) => {
  if (doneEvent) {
    if (isFiniteNumber(doneEvent.durationMs)) {
      return formatDuration(doneEvent.durationMs);
    }
    if (isFiniteNumber(doneEvent.elapsedMs) && isFiniteNumber(startEvent?.elapsedMs)) {
      return formatDuration(Math.max(0, doneEvent.elapsedMs - startEvent.elapsedMs));
    }
    return '';
  }
  if (isFiniteNumber(startEvent?.clientReceivedAtMs)) {
    return `已运行 ${formatDuration(Math.max(0, nowMs - startEvent.clientReceivedAtMs))}`;
  }
  return '';
};

const buildStageItem = (
  startEvent: RuntimeProgressEvent | null,
  doneEvent: RuntimeProgressEvent | null,
  pair: ProgressStagePair,
  nowMs: number,
  depth: number
): RuntimeProgressTimelineItem => {
  const event = doneEvent || startEvent;
  const status = event?.status || 'running';
  return {
    key: `stage-${pair.key}-${event?.childRuntimeRequestId || 'root'}`,
    label: pair.label,
    status,
    statusLabel: statusLabel(status),
    durationText: stageDurationText(startEvent, doneEvent, nowMs),
    depth,
    kind: 'stage'
  };
};

const toolExecutionKey = (event: RuntimeProgressEvent, index: number) =>
  String(event.toolExecutionSeq ?? `${event.displayName || 'tool'}-${index}`);

const findToolEvent = (events: RuntimeProgressEvent[], key: string, stageCode: string) =>
  events.find((event, index) => event.stageCode === stageCode && toolExecutionKey(event, index) === key) || null;

const buildToolItem = (
  startEvent: RuntimeProgressEvent | null,
  doneEvent: RuntimeProgressEvent | null,
  index: number,
  nowMs: number,
  depth: number
): RuntimeProgressTimelineItem => {
  const event = doneEvent || startEvent!;
  const status = event.status || 'running';
  const toolName = safeProgressText(event.displayName);
  return {
    key: `tool-${toolExecutionKey(event, index)}-${event.childRuntimeRequestId || 'root'}`,
    label: toolName ? `调用工具: ${toolName}` : '调用工具',
    status,
    statusLabel: statusLabel(status),
    durationText: stageDurationText(startEvent, doneEvent, nowMs),
    depth,
    kind: 'stage'
  };
};

const flowResolverLabel = (event: RuntimeProgressEvent) => {
  const value = safeProgressText(event.displayName || event.resolverId);
  if (value.startsWith('查询')) return value;
  const normalized = value.replace(/[_\-.]/g, '').toLowerCase();
  if (normalized.includes('customer')) return '查询客户';
  if (normalized.includes('project')) return '查询项目';
  if (normalized.includes('history')) return '查询历史单据';
  if (normalized.includes('product')) return '查询商品';
  if (normalized.includes('address')) return '查询地址';
  return value ? `查询数据: ${value}` : '查询数据';
};

const findResolverEvent = (events: RuntimeProgressEvent[], resolverId: string | undefined, stageCode: string) => {
  if (!resolverId) return null;
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index];
    if (event.stageCode === stageCode && event.resolverId === resolverId) return event;
  }
  return null;
};

const buildFlowResolverItem = (
  startEvent: RuntimeProgressEvent | null,
  doneEvent: RuntimeProgressEvent | null,
  index: number,
  nowMs: number,
  depth: number
): RuntimeProgressTimelineItem => {
  const event = doneEvent || startEvent!;
  const status = event.status || 'running';
  return {
    key: `resolver-${event.resolverId || eventKey(event, index)}-${event.childRuntimeRequestId || 'root'}`,
    label: flowResolverLabel(event),
    status,
    statusLabel: statusLabel(status),
    durationText: stageDurationText(startEvent, doneEvent, nowMs),
    depth,
    kind: 'stage'
  };
};

// 流程节点事件按 nodeId 独立成条目，逐节点展示提取/收集/查询等阶段；
// 后端携带中文 displayName 时作为节点标题，旧后端英文 node.id 回退静态“执行流程节点”
const hasChineseText = (value: string) => /[\u4E00-\u9FA5]/.test(value);

const flowNodeLabel = (event: RuntimeProgressEvent) => {
  const displayName = safeProgressText(event.displayName);
  if (displayName && hasChineseText(displayName)) return displayName;
  return '执行流程节点';
};

const findNodeEvent = (events: RuntimeProgressEvent[], nodeId: string | undefined, stageCode: string) => {
  if (!nodeId) return null;
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const event = events[index];
    if (event.stageCode === stageCode && event.nodeId === nodeId) return event;
  }
  return null;
};

const buildFlowNodeItem = (
  startEvent: RuntimeProgressEvent | null,
  doneEvent: RuntimeProgressEvent | null,
  index: number,
  nowMs: number,
  depth: number
): RuntimeProgressTimelineItem => {
  const event = doneEvent || startEvent!;
  const status = event.status || 'running';
  return {
    key: `node-${event.nodeId || eventKey(event, index)}-${event.childRuntimeRequestId || 'root'}`,
    label: flowNodeLabel(event),
    status,
    statusLabel: statusLabel(status),
    durationText: stageDurationText(startEvent, doneEvent, nowMs),
    depth,
    kind: 'stage'
  };
};

const buildSingleEventItem = (
  event: RuntimeProgressEvent,
  index: number,
  depth: number
): RuntimeProgressTimelineItem => {
  const status = event.status || 'running';
  const durationText = status !== 'running' && isFiniteNumber(event.durationMs) ? formatDuration(event.durationMs) : '';
  return {
    key: eventKey(event, index),
    label: stageLabels[event.stageCode] || event.stageCode,
    status,
    statusLabel: statusLabel(status),
    durationText,
    depth,
    kind: 'stage'
  };
};

const buildTimelineItems = (events: RuntimeProgressEvent[], nowMs: number, depth: number) => {
  const emittedStageKeys = new Set<string>();
  const emittedToolKeys = new Set<string>();
  const emittedResolverKeys = new Set<string>();
  const emittedNodeKeys = new Set<string>();

  return events.reduce<RuntimeProgressTimelineItem[]>((items, event, index) => {
    if (TERMINAL_STAGE_CODES.has(event.stageCode)) {
      return items;
    }

    if (event.stageCode === TOOL_RUNNING || event.stageCode === TOOL_FINISHED) {
      const key = toolExecutionKey(event, index);
      if (emittedToolKeys.has(key)) return items;
      emittedToolKeys.add(key);
      items.push(
        buildToolItem(
          findToolEvent(events, key, TOOL_RUNNING),
          findToolEvent(events, key, TOOL_FINISHED),
          index,
          nowMs,
          depth
        )
      );
      return items;
    }

    if (event.stageCode === FLOW_RESOLVER_RUNNING || event.stageCode === FLOW_RESOLVER_FINISHED) {
      const key = event.resolverId || eventKey(event, index);
      if (emittedResolverKeys.has(key)) return items;
      emittedResolverKeys.add(key);
      items.push(
        buildFlowResolverItem(
          findResolverEvent(events, event.resolverId, FLOW_RESOLVER_RUNNING),
          findResolverEvent(events, event.resolverId, FLOW_RESOLVER_FINISHED),
          index,
          nowMs,
          depth
        )
      );
      return items;
    }

    // 流程节点按 nodeId 独立成条目，RUNNING/FINISHED 配对后展示各节点阶段；
    // 旧事件缺失 nodeId 时用触发事件自身兜底，避免节点条目丢失
    if (event.stageCode === FLOW_NODE_RUNNING || event.stageCode === FLOW_NODE_FINISHED) {
      const key = event.nodeId || eventKey(event, index);
      if (emittedNodeKeys.has(key)) return items;
      emittedNodeKeys.add(key);
      const startEvent = findNodeEvent(events, event.nodeId, FLOW_NODE_RUNNING);
      const doneEvent = findNodeEvent(events, event.nodeId, FLOW_NODE_FINISHED);
      items.push(
        buildFlowNodeItem(
          startEvent ?? (event.stageCode === FLOW_NODE_RUNNING ? event : null),
          doneEvent ?? (event.stageCode === FLOW_NODE_FINISHED ? event : null),
          index,
          nowMs,
          depth
        )
      );
      return items;
    }

    const pair = stagePairByStartCode.get(event.stageCode) || stagePairByDoneCode.get(event.stageCode);
    if (pair) {
      if (emittedStageKeys.has(pair.key)) return items;
      const startEvent = findLastEvent(events, pair.startCode, 'running');
      const doneEvent = pair.doneCodes
        .map(code => findLastEvent(events, code))
        .find(item => item && isFinishedEvent(item));
      if (!startEvent && !doneEvent) return items;
      emittedStageKeys.add(pair.key);
      items.push(buildStageItem(startEvent, doneEvent || null, pair, nowMs, depth));
      return items;
    }

    items.push(buildSingleEventItem(event, index, depth));
    return items;
  }, []);
};

const lastMatchingEvent = (events: RuntimeProgressEvent[], predicate: (event: RuntimeProgressEvent) => boolean) => {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    if (predicate(events[index])) return events[index];
  }
  return null;
};

const rootStatus = (events: RuntimeProgressEvent[]) => {
  if (events.some(event => event.stageCode === 'ORCHESTRATION_FAILED')) return 'failed';
  if (events.some(event => event.stageCode === 'ORCHESTRATION_CLARIFICATION')) return 'waiting_clarification';
  const merged = lastMatchingEvent(
    events,
    event => event.stageCode === 'ORCHESTRATION_MERGING' && event.status !== 'running'
  );
  return merged?.status || 'running';
};

const collaboratorStatus = (events: RuntimeProgressEvent[]) => {
  const terminal = lastMatchingEvent(events, event =>
    ['COLLABORATOR_FINISHED', 'COLLABORATOR_TIMED_OUT', 'DONE', 'FAILED', 'CANCELLED'].includes(event.stageCode)
  );
  if (terminal?.stageCode === 'COLLABORATOR_TIMED_OUT') return 'timed_out';
  if (terminal?.stageCode === 'DONE') return 'success';
  if (terminal?.stageCode === 'FAILED') return 'failed';
  if (terminal?.stageCode === 'CANCELLED') return 'cancelled';
  return terminal?.status || 'running';
};

const rootDurationText = (events: RuntimeProgressEvent[], nowMs: number, terminalStatus: string) => {
  const last = events[events.length - 1];
  if (terminalStatus !== 'running' && isFiniteNumber(last?.elapsedMs)) {
    return formatDuration(last.elapsedMs);
  }
  const firstReceivedAt = events.find(event => isFiniteNumber(event.clientReceivedAtMs))?.clientReceivedAtMs;
  return isFiniteNumber(firstReceivedAt) ? `已运行 ${formatDuration(Math.max(0, nowMs - firstReceivedAt))}` : '';
};

const collaboratorDurationText = (events: RuntimeProgressEvent[], nowMs: number, terminalStatus: string) => {
  const terminal = lastMatchingEvent(events, event =>
    ['COLLABORATOR_FINISHED', 'COLLABORATOR_TIMED_OUT'].includes(event.stageCode)
  );
  if (terminalStatus !== 'running' && isFiniteNumber(terminal?.durationMs)) {
    return formatDuration(terminal.durationMs);
  }
  const first = events.find(event => isFiniteNumber(event.elapsedMs));
  const last = events[events.length - 1];
  if (terminalStatus !== 'running' && isFiniteNumber(first?.elapsedMs) && isFiniteNumber(last?.elapsedMs)) {
    return formatDuration(Math.max(0, last.elapsedMs - first.elapsedMs));
  }
  const firstReceivedAt = events.find(event => isFiniteNumber(event.clientReceivedAtMs))?.clientReceivedAtMs;
  return isFiniteNumber(firstReceivedAt) ? `已运行 ${formatDuration(Math.max(0, nowMs - firstReceivedAt))}` : '';
};

const collaboratorLabel = (events: RuntimeProgressEvent[]) => {
  const event = events.find(item => item.collaboratorRole || item.collaboratorName);
  const role = safeProgressText(event?.collaboratorRole);
  const name = safeProgressText(event?.collaboratorName);
  if (role && name && role !== name) return `${role} · ${name}`;
  return role || name || '协作者';
};

export const isRuntimeWaitingForInteraction = (events: RuntimeProgressEvent[]) => {
  const latest = events.at(-1);
  return Boolean(latest && (latest.status === 'waiting' || latest.stageCode === 'WAITING'));
};

export const buildRuntimeProgressTimeline = (
  events: RuntimeProgressEvent[],
  nowMs: number
): RuntimeProgressTimelineItem[] => {
  const orchestrationEvents = events.filter(
    event => event.childRuntimeRequestId || event.stageCode.startsWith('ORCHESTRATION_')
  );
  if (orchestrationEvents.length === 0) {
    return buildTimelineItems(events, nowMs, 0);
  }

  const parentEvents = events.filter(event => !event.childRuntimeRequestId);
  const childGroups = new Map<string, RuntimeProgressEvent[]>();
  events.forEach(event => {
    if (!event.childRuntimeRequestId) return;
    const group = childGroups.get(event.childRuntimeRequestId) || [];
    group.push(event);
    childGroups.set(event.childRuntimeRequestId, group);
  });

  const status = rootStatus(parentEvents);
  const items: RuntimeProgressTimelineItem[] = [
    {
      key: 'orchestration-root',
      label: '编排运行',
      status,
      statusLabel: statusLabel(status),
      durationText: rootDurationText(parentEvents, nowMs, status),
      depth: 0,
      kind: 'group'
    },
    ...buildTimelineItems(parentEvents, nowMs, 1)
  ];

  childGroups.forEach((childEvents, childRuntimeRequestId) => {
    const childStatus = collaboratorStatus(childEvents);
    items.push({
      key: `collaborator-${childRuntimeRequestId}`,
      label: collaboratorLabel(childEvents),
      status: childStatus,
      statusLabel: statusLabel(childStatus),
      durationText: collaboratorDurationText(childEvents, nowMs, childStatus),
      depth: 1,
      kind: 'group'
    });
    items.push(...buildTimelineItems(childEvents, nowMs, 2));
  });

  return items;
};
