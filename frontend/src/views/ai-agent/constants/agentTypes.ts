export const AGENT_TYPE = {
  DATA_ANALYSIS: 'commonagent',
  KNOWLEDGE_BASE: 'knowledge_base',
  CUSTOMER_SERVICE: 'customer_service',
  ORCHESTRATOR: 'orchestrator',
  LEGACY_DATA_ANALYSIS: 'data_analysis',
} as const;

export const AGENT_TYPE_OPTIONS = [
  { label: '数据分析 Agent', value: AGENT_TYPE.DATA_ANALYSIS },
  { label: '知识库 Agent', value: AGENT_TYPE.KNOWLEDGE_BASE },
  { label: '客服 Agent', value: AGENT_TYPE.CUSTOMER_SERVICE },
  { label: '编排 Agent', value: AGENT_TYPE.ORCHESTRATOR },
];

export const normalizeAgentType = (agentType?: string) => {
  if (!agentType) {
    return AGENT_TYPE.DATA_ANALYSIS;
  }

  const normalizedType = agentType.trim().toLowerCase().replaceAll('-', '_');
  return normalizedType === AGENT_TYPE.LEGACY_DATA_ANALYSIS ? AGENT_TYPE.DATA_ANALYSIS : normalizedType;
};

export const formatAgentType = (agentType?: string) => {
  const normalizedType = normalizeAgentType(agentType);
  return AGENT_TYPE_OPTIONS.find(option => option.value === normalizedType)?.label || agentType || '-';
};
