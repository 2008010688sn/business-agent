import DOMPurify from 'dompurify';
import hljs from 'highlight.js';
import sql from 'highlight.js/lib/languages/sql';
import python from 'highlight.js/lib/languages/python';
import json from 'highlight.js/lib/languages/json';
import { marked } from 'marked';
import type { AgentResponse } from '@/views/ai-agent/services/graph';
import { TextType } from '@/views/ai-agent/services/graph';
import type { ResultData, ResultSetData } from '@/views/ai-agent/services/resultSet';
import { formatResultCell } from '@/views/ai-agent/services/resultSet';
import { parseAgentUiFromResponses } from '@/views/ai-agent/utils/agentUi';

hljs.registerLanguage('sql', sql);
hljs.registerLanguage('python', python);
hljs.registerLanguage('json', json);

export type NodeBlockGroups = {
  thinkingBlocks: AgentResponse[][];
  finalBlocks: AgentResponse[][];
};

type NodeRendererOptions = {
  getResultSetPageSize: () => number;
  getMarkdownReportContent: () => string;
};

const getToolDisplayName = (toolName: string) => {
  const toolDisplayNames: Record<string, string> = {
    'domain_business_knowledge.search': '业务知识检索',
    'semantic_model.search': '语义模型检索',
    'sql_guard.check': 'SQL 校验'
  };
  return toolDisplayNames[toolName] || toolName;
};

const getFriendlyToolDisplayName = (toolName: string) => {
  const mapped = getToolDisplayName(toolName);
  if (mapped !== toolName) return mapped;
  const normalized = toolName.replace(/[_\-.]/g, '').toLowerCase();
  if (normalized.includes('demandcustomeroptions')) return '查询下单客户候选';
  if (normalized.includes('demandprojectoptions')) return '查询下单项目候选';
  if (normalized.includes('demandproductoptions')) return '查询下单商品候选';
  if (normalized.includes('demandcreatelatest')) return '查询最近下单记录';
  if (normalized.includes('demandcreateexecute')) return '创建需求单';
  if (normalized.endsWith('queryresource')) return '查询能力参考记录';
  if (normalized.endsWith('execute')) return '提交能力执行';
  return '工具操作';
};

const escapeHtml = (text: string): string => {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
};

const markdownToHtml = (markdown: string): string => {
  if (!markdown) return '';
  marked.setOptions({ gfm: true, breaks: true });
  const rawHtml = marked.parse(markdown) as string;
  return DOMPurify.sanitize(rawHtml);
};

const getNodeBlockTitle = (node: AgentResponse[]) => {
  const nodeName = node.length > 0 && node[0].nodeName ? node[0].nodeName : '';
  if (!nodeName) {
    return '空节点';
  }
  if (nodeName === 'planner-reasoning' || nodeName === 'AgentScopeRuntime') {
    return '最终回答';
  }
  if (nodeName === 'ReportGeneratorNode') {
    return '分析报告';
  }
  if (nodeName.startsWith('tool:')) {
    return `\u5DE5\u5177\u8C03\u7528\uFF1A${getFriendlyToolDisplayName(nodeName.slice('tool:'.length))}`;
  }
  return nodeName;
};

const isIntrinsicFinalNode = (node: AgentResponse[]) => {
  if (!node || node.length === 0) {
    return false;
  }
  return (
    Boolean(parseAgentUiFromResponses(node)) ||
    node[0].nodeName === 'AgentScopeRuntime' ||
    node[0].nodeName === 'ReportGeneratorNode' ||
    node[0].textType === TextType.RESULT_SET
  );
};

const hasDatasourceSearchResult = (node: AgentResponse[]) => {
  if (!node || node.length === 0) {
    return false;
  }
  const nodeName = node[0].nodeName || '';
  if (!nodeName.startsWith('tool:datasource.')) {
    return false;
  }
  const text = node.map(item => item.text || '').join('');
  return /"action"\s*:\s*"SEARCH"/.test(text);
};

const hasKnowledgeSearchResult = (node: AgentResponse[]) => {
  if (!node || node.length === 0) {
    return false;
  }
  const nodeName = node[0].nodeName || '';
  if (nodeName !== 'tool:domain_business_knowledge.search') {
    return false;
  }
  const text = node
    .map(item => item.text || '')
    .join('\n')
    .trim();
  if (!text) {
    return false;
  }
  return !text.split(/\r?\n/).every(line => /^Calling tool:\s*/.test(line.trim()));
};

export const splitNodeBlocks = (blocks: AgentResponse[][]): NodeBlockGroups => {
  const hasAgentRuntimeFinal = blocks.some(block => block.length > 0 && block[0].nodeName === 'AgentScopeRuntime');
  const groups: NodeBlockGroups = {
    thinkingBlocks: [],
    finalBlocks: []
  };
  let resultEvidenceSeen = false;

  for (const block of blocks) {
    if (!block || block.length === 0) {
      continue;
    }
    const nodeName = block[0].nodeName || '';
    if (isIntrinsicFinalNode(block)) {
      groups.finalBlocks.push(block);
      resultEvidenceSeen = true;
      continue;
    }
    if (nodeName === 'planner-reasoning') {
      if (!hasAgentRuntimeFinal && resultEvidenceSeen) {
        groups.finalBlocks.push(block);
      } else {
        groups.thinkingBlocks.push(block);
      }
      continue;
    }

    groups.thinkingBlocks.push(block);
    if (hasDatasourceSearchResult(block) || hasKnowledgeSearchResult(block)) {
      resultEvidenceSeen = true;
    }
  }

  return groups;
};

const isFinalOutputNode = (node: AgentResponse[]) => {
  return isIntrinsicFinalNode(node);
};

const shouldCollapseNodeBlock = (node: AgentResponse[]) => {
  return node.length > 0 && !isFinalOutputNode(node);
};

const isPlainFinalAnswerNode = (node: AgentResponse[]) => {
  if (!node || node.length === 0) {
    return false;
  }
  const nodeName = node[0].nodeName || '';
  return nodeName === 'AgentScopeRuntime' || nodeName === 'planner-reasoning';
};

const getThinkingStepTitle = (node: AgentResponse[]) => {
  const nodeName = node.length > 0 && node[0].nodeName ? node[0].nodeName : '';
  if (nodeName === 'planner-reasoning') {
    return '理解问题';
  }
  if (nodeName.startsWith('tool:')) {
    return `\u8C03\u7528\u5DE5\u5177\uFF1A${getFriendlyToolDisplayName(nodeName.slice('tool:'.length))}`;
  }
  if (nodeName === 'ReportGeneratorNode') {
    return '生成报告';
  }
  return getNodeBlockTitle(node);
};

const thinkingSpinnerSvg = `<svg class="agent-thinking-spinner" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="16" height="16" aria-hidden="true" focusable="false" style="flex: 0 0 auto; color: inherit;"><g><path d="M491.859587 550.384195c5.96084-12.824837 21.314518-18.243782 33.958723-12.824837 32.694302 14.089257 94.650908 50.938084 167.264773 143.060152 107.114482 136.376786 73.878285 278.714412-14.089257 323.149761-81.103546 41.183983-158.052567-9.392838-178.82519-52.202504-1.625684-3.793262-7.22526-4.335156-9.754101-0.361263-13.1861 22.037044-49.673664 72.613865-106.572588 72.613864-72.613865 0-137.279944-44.615982-137.279943-127.34521s59.427765-134.209208 125.358264-187.676134c56.718292-45.338508 97.902276-112.89469 119.939319-158.413829zM531.77915 473.615805c-5.96084 12.824837-21.314518 18.243782-33.958723 12.824837-32.694302-14.089257-94.650908-50.938084-167.264773-143.060152-107.114482-136.376786-73.878285-278.714412 14.089257-323.149761 81.103546-41.183983 158.052567 9.392838 178.82519 52.202504 1.625684 3.793262 7.22526 4.335156 9.754101 0.361263C546.22967 50.576821 582.717234 0 639.616158 0c72.613865 0 137.279944 44.615982 137.279944 127.345211s-59.427765 134.209208-125.358265 187.676133c-55.995766 45.519139-97.721644 112.714059-119.758687 158.594461zM550.384195 531.77915c-12.824837-5.96084-18.243782-21.314518-12.824837-33.958723 14.089257-32.694302 50.938084-94.650908 143.060152-167.264773 136.376786-107.114482 278.714412-73.878285 323.149761 14.089257 41.183983 81.103546-9.392838 158.052567-52.202504 178.82519-3.793262 1.625684-4.335156 7.22526-0.361263 9.754101 22.037044 13.1861 72.613865 49.673664 72.613864 106.572588 0 72.613865-44.615982 137.279944-127.34521 137.279943s-134.209208-59.427765-187.676134-125.358264c-45.338508-56.176398-112.533427-97.902276-158.413829-119.939319zM473.615805 491.859587c12.824837 5.96084 18.243782 21.314518 12.824837 33.958723-14.089257 32.694302-50.938084 94.650908-143.060152 167.264773-136.557418 107.114482-278.895043 74.058917-323.330393-13.908625-41.183983-81.103546 9.392838-158.052567 52.202505-178.82519 3.793262-1.625684 4.335156-7.22526 0.361263-9.754101C50.576821 477.409067 0 440.921503 0 384.022579c0-72.613865 44.615982-137.279944 127.345211-137.279944s134.209208 59.427765 187.676133 125.358265c45.519139 56.537661 112.714059 97.721644 158.594461 119.758687z" fill="currentColor"></path><animateTransform attributeName="transform" type="rotate" from="0 512 512" to="360 512 512" dur="1s" repeatCount="indefinite"></animateTransform></g></svg>`;

const generateResultSetTable = (resultSetData: ResultSetData, pageSize: number): string => {
  const columns = resultSetData.column || [];
  const allData = resultSetData.data || [];
  const total = allData.length;
  const totalPages = Math.ceil(total / pageSize);

  let tableHtml = `<div class="result-set-container"><div class="result-set-header"><div class="result-set-info"><span>查询结果 (共 ${total} 条记录)</span><div class="result-set-pagination-controls"><span class="result-set-pagination-info">第 <span class="result-set-current-page">1</span> 页，共 ${totalPages} 页</span><div class="result-set-pagination-buttons"><button class="result-set-pagination-btn result-set-pagination-prev" onclick="handleResultSetPagination(this, 'prev')" disabled>上一页</button><button class="result-set-pagination-btn result-set-pagination-next" onclick="handleResultSetPagination(this, 'next')" ${totalPages > 1 ? '' : 'disabled'}>下一页</button></div></div></div></div><div class="result-set-table-container">`;

  for (let page = 1; page <= totalPages; page += 1) {
    const startIndex = (page - 1) * pageSize;
    const endIndex = Math.min(startIndex + pageSize, total);
    const currentPageData = allData.slice(startIndex, endIndex);

    tableHtml += `<div class="result-set-page ${page === 1 ? 'result-set-page-active' : ''}" data-page="${page}"><table class="result-set-table"><thead><tr>`;
    for (const column of columns) {
      tableHtml += `<th>${escapeHtml(column)}</th>`;
    }

    tableHtml += `</tr></thead><tbody>`;
    if (currentPageData.length === 0) {
      tableHtml += `<tr><td colspan="${columns.length}" class="result-set-empty-cell">暂无数据</td></tr>`;
    } else {
      for (const row of currentPageData) {
        tableHtml += `<tr>`;
        for (const column of columns) {
          const value = formatResultCell(row[column]);
          tableHtml += `<td>${escapeHtml(value)}</td>`;
        }
        tableHtml += `</tr>`;
      }
    }

    tableHtml += `</tbody></table></div>`;
  }

  tableHtml += `</div></div>`;
  return tableHtml;
};

export const createAgentRunNodeRenderer = (options: NodeRendererOptions) => {
  const generateLongTermMemoryHint = (node: AgentResponse[]) => {
    const metadata = node?.[0]?.metadata;
    const memory = metadata?.longTermMemory;
    const count = Number(memory?.referencedCount || 0);
    if (!memory || count <= 0) {
      return '';
    }
    const hits = Array.isArray(memory.hits) ? memory.hits : [];
    const items = hits
      .map((hit: any) => {
        const type = escapeHtml(String(hit.type || ''));
        const summary = escapeHtml(String(hit.summary || ''));
        const similarity = typeof hit.similarity === 'number' ? ` · 相似度 ${hit.similarity.toFixed(2)}` : '';
        return `<li><span>${type}</span>${summary}${similarity}</li>`;
      })
      .join('');
    return `
      <details class="long-term-memory-hint">
        <summary>已参考 ${count} 条长期记忆</summary>
        <ul>${items}</ul>
      </details>
    `;
  };

  const formatNodeContent = (node: AgentResponse[]) => {
    let content = '';

    for (let idx = 0; idx < node.length; idx += 1) {
      if (node[idx].textType === TextType.HTML) {
        content += node[idx].text;
      } else if (node[idx].textType === TextType.TEXT) {
        content += node[idx].text.replace(/\n/g, '<br>');
      } else if (
        node[idx].textType === TextType.JSON ||
        node[idx].textType === TextType.PYTHON ||
        node[idx].textType === TextType.SQL
      ) {
        let pre = '';
        let p = idx;
        for (; p < node.length; p += 1) {
          if (node[p].textType !== node[idx].textType) {
            break;
          }
          pre += node[p].text;
        }
        try {
          const language = node[idx].textType.toLowerCase();
          const highlighted = hljs.highlight(pre, { language });
          content += `<pre><div style="display: flex; justify-content: space-between; align-items: center; background: #f8f9fa; padding: 8px 12px; border-bottom: none; font-family: system-ui, sans-serif; font-size: 14px;"><span style="color: #666;">${language}</span><span hidden>${pre}</span><button onclick='copyTextToClipboard(this)' style="background: #f8f9fa; border: none; padding: 4px 12px; border-radius: 12px; font-size: 13px; cursor: pointer; transition: background 0.2s;">复制</button></div><code class="hljs ${language}">${highlighted.value}</code></pre>`;
        } catch (error) {
          content += `<pre><code>${pre}</code></pre>`;
        }
        if (p < node.length) {
          idx = p - 1;
        } else {
          break;
        }
      } else if (node[idx].textType === TextType.MARK_DOWN) {
        let markdown = '';
        let p = idx;
        for (; p < node.length; p += 1) {
          if (node[p].textType !== TextType.MARK_DOWN) {
            break;
          }
          markdown += node[p].text;
        }

        const safeHtml = markdownToHtml(markdown);
        content += `<div class="markdown-report">${safeHtml}</div>`;

        if (p < node.length) {
          idx = p - 1;
        } else {
          break;
        }
      } else if (node[idx].textType === TextType.RESULT_SET) {
        try {
          const resultData: ResultData = JSON.parse(node[idx].text);
          const resultSetData = resultData.resultSet;

          if (resultSetData.errorMsg) {
            content += `<div class="result-set-error">错误: ${resultSetData.errorMsg}</div>`;
            continue;
          }

          if (
            !resultSetData.column ||
            resultSetData.column.length === 0 ||
            !resultSetData.data ||
            resultSetData.data.length === 0
          ) {
            content += `<div class="result-set-empty">查询结果为空</div>`;
            continue;
          }

          if (resultData.displayStyle?.type === 'table' || !resultData.displayStyle?.type) {
            const tableHtml = generateResultSetTable(resultSetData, options.getResultSetPageSize());
            content += tableHtml;
          }
        } catch (error: any) {
          console.error('解析结果集JSON失败:', error);
          content += `<div class="result-set-error">解析结果集数据失败: ${error.message}</div>`;
        }
      } else {
        console.warn(`不支持的 textType: ${node[idx].textType}`);
        content += node[idx].text;
      }
    }

    return content;
  };

  const generateThinkingHtml = (blocks: AgentResponse[][], expanded = false) => {
    const openAttr = expanded ? ' open' : '';
    const steps = blocks
      .map(block => {
        const title = escapeHtml(getThinkingStepTitle(block));
        const content = formatNodeContent(block);
        return `
          <li class="agent-thinking-step">
            <div class="agent-thinking-step-body">
              <div class="agent-thinking-step-title">${title}</div>
              <div class="agent-thinking-step-content">${content}</div>
            </div>
          </li>
        `;
      })
      .join('');

    return `
      <details class="agent-thinking-block agent-thinking-group" style="display: block !important; width: 100% !important;"${openAttr}>
        <summary class="agent-response-summary">
          <span class="agent-response-title-text">Thinking</span>${thinkingSpinnerSvg}
        </summary>
        <div class="agent-thinking-content">
          <ol class="agent-thinking-steps">${steps}</ol>
        </div>
      </details>
    `;
  };

  const generateNodeHtml = (node: AgentResponse[]) => {
    const content = formatNodeContent(node);
    const title = escapeHtml(getNodeBlockTitle(node));
    const memoryHint = generateLongTermMemoryHint(node);

    if (isPlainFinalAnswerNode(node)) {
      return `
        ${memoryHint}
        <div class="agent-final-answer-content" style="display: block !important; width: 100% !important;">${content}</div>
      `;
    }
    if (shouldCollapseNodeBlock(node)) {
      return `
        <details class="agent-thinking-block" style="display: block !important; width: 100% !important;">
          <summary class="agent-response-summary">
            <span class="agent-response-title-text">Thinking · ${title}</span>
          </summary>
          <div class="agent-thinking-content agent-thinking-raw-content">${content}</div>
        </details>
      `;
    }
    return `
      <div class="agent-response-block" style="display: block !important; width: 100% !important;">
        <div class="agent-response-title">${title}</div>
        <div class="agent-response-content">${memoryHint}${content}</div>
      </div>
    `;
  };

  const getMarkdownContentFromNode = (node: AgentResponse[]): string => {
    if (!node || node.length === 0) {
      return '';
    }

    const firstNode = node[0];
    if (firstNode.nodeName === 'ReportGeneratorNode' && firstNode.textType === 'MARK_DOWN') {
      return options.getMarkdownReportContent();
    }

    let markdown = '';
    for (let idx = 0; idx < node.length; idx += 1) {
      if (node[idx].textType === 'MARK_DOWN') {
        let p = idx;
        for (; p < node.length; p += 1) {
          if (node[p].textType !== 'MARK_DOWN') {
            break;
          }
          markdown += node[p].text;
        }
        if (p < node.length) {
          idx = p - 1;
        } else {
          break;
        }
      }
    }

    return markdown;
  };

  return {
    generateThinkingHtml,
    generateNodeHtml,
    getMarkdownContentFromNode
  };
};
