import type { Agent } from '@/views/ai-agent/services/agent';

export function parseAgentId(value: unknown): string | null {
  if (value === undefined || value === null) {
    return null;
  }

  const parsed = String(value).trim();
  return parsed || null;
}

export function getAgentName(agent: Agent): string {
  return agent.name?.trim() || '未命名智能体';
}

export function getAgentInitial(agent: Agent): string {
  const name = getAgentName(agent);
  if (/^[A-Za-z0-9]/.test(name)) {
    return name.slice(0, 2).toUpperCase();
  }

  return Array.from(name).slice(0, 2).join('');
}

export function getAgentDescription(agent: Agent): string {
  return agent.description?.trim() || '暂无智能体介绍';
}

export function getAgentTagList(agent: Agent): string[] {
  if (!agent.tags) {
    return [];
  }

  return agent.tags
    .split(/[,，;；\s]+/)
    .map(tag => tag.trim())
    .filter(Boolean)
    .slice(0, 4);
}
