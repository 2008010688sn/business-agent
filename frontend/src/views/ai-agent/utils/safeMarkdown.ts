import DOMPurify from 'dompurify';
import { marked } from 'marked';

const SAFE_LINK_PROTOCOLS = new Set(['http:', 'https:', 'mailto:']);
const SAFE_URI_REGEXP = /^(?:(?:https?|mailto):)/i;

const MARKDOWN_ALLOWED_TAGS = [
  'a',
  'blockquote',
  'br',
  'button',
  'code',
  'del',
  'div',
  'em',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'hr',
  'li',
  'ol',
  'p',
  'pre',
  'span',
  'strong',
  'table',
  'tbody',
  'td',
  'th',
  'thead',
  'tr',
  'ul'
];

const LEGACY_HTML_ALLOWED_TAGS = [...MARKDOWN_ALLOWED_TAGS.filter(tag => tag !== 'button'), 'details', 'summary'];
const SAFE_HTML_ATTRIBUTES = ['aria-label', 'class', 'colspan', 'href', 'rel', 'rowspan', 'target', 'title', 'type'];

const sanitizeOptions = (allowedTags: string[]) => ({
  ALLOWED_TAGS: allowedTags,
  ALLOWED_ATTR: SAFE_HTML_ATTRIBUTES,
  ALLOW_DATA_ATTR: false,
  ALLOW_ARIA_ATTR: true,
  ALLOWED_URI_REGEXP: SAFE_URI_REGEXP
});

const escapeHtml = (value: string) => {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
};

const normalizeCodeLanguage = (infoString?: string) => {
  const language = (infoString || '').trim().split(/\s+/)[0] || '';
  return /^[a-z0-9_-]{1,32}$/i.test(language) ? language.toLowerCase() : '';
};

const parseSafeEchartsOption = (code: string) => {
  try {
    const option = JSON.parse(code.replace(/^\uFEFF/, '').trim());
    if (!option || typeof option !== 'object' || Array.isArray(option)) {
      return null;
    }
    return option as Record<string, unknown>;
  } catch {
    return null;
  }
};

const extractJsonObjects = (code: string) => {
  const options: Record<string, unknown>[] = [];
  let depth = 0;
  let start = -1;
  let inString = false;
  let escaped = false;
  for (let index = 0; index < code.length; index += 1) {
    const character = code[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === '\\') {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }
    if (character === '"') {
      inString = true;
      continue;
    }
    if (character === '{') {
      if (depth === 0) {
        start = index;
      }
      depth += 1;
      continue;
    }
    if (character === '}' && depth > 0) {
      depth -= 1;
      if (depth === 0 && start >= 0) {
        const option = parseSafeEchartsOption(code.slice(start, index + 1));
        if (option) {
          options.push(option);
        }
        start = -1;
      }
    }
  }
  return options;
};

const parseEchartsOptions = (code: string) => {
  const single = parseSafeEchartsOption(code);
  if (single) {
    return [single];
  }
  return extractJsonObjects(code);
};

const renderEchartsPlaceholders = (code: string) => {
  const options = parseEchartsOptions(code);
  if (options.length === 0) {
    return '<div class="safe-markdown-echarts-error" aria-label="分析报告图表">图表配置格式错误，暂无法渲染。</div>';
  }
  return options
    .map(
      option =>
        `<div class="safe-markdown-echarts" aria-label="分析报告图表">${escapeHtml(JSON.stringify(option))}</div>`
    )
    .join('');
};

const renderCodeBlock = (code: string, language: string) => {
  const languageLabel = language || 'text';
  const languageClass = language ? ` language-${language}` : '';
  return `<div class="safe-markdown-code-block"><div class="safe-markdown-code-header"><span>${languageLabel}</span><button type="button" class="safe-markdown-copy-button" aria-label="复制代码">复制</button></div><pre><code class="${languageClass.trim()}">${escapeHtml(code)}</code></pre></div>`;
};

const TABLE_ROW_PATTERN = /^\s*\|.+\|\s*$/;
const TABLE_DELIMITER_PATTERN = /^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)+\|?\s*$/;

const normalizeMarkdownTables = (markdown: string) => {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
  const normalized: string[] = [];
  let inTable = false;
  lines.forEach((line, index) => {
    const startsTable = TABLE_ROW_PATTERN.test(line) && TABLE_DELIMITER_PATTERN.test(lines[index + 1] || '');
    const isTableRow = TABLE_ROW_PATTERN.test(line);
    if (startsTable && normalized.length > 0 && normalized[normalized.length - 1].trim()) {
      normalized.push('');
    } else if (inTable && !isTableRow && line.trim()) {
      normalized.push('');
    }
    normalized.push(line);
    inTable = startsTable || (inTable && isTableRow);
  });
  return normalized.join('\n');
};

const hasControlCharacter = (value: string) => {
  return Array.from(value).some(character => {
    const code = character.charCodeAt(0);
    return code <= 31 || code === 127;
  });
};

export const isSafeMarkdownUrl = (value: unknown): value is string => {
  if (typeof value !== 'string') {
    return false;
  }
  const url = value.trim();
  if (!url || hasControlCharacter(url)) {
    return false;
  }
  try {
    return SAFE_LINK_PROTOCOLS.has(new URL(url).protocol.toLowerCase());
  } catch {
    return false;
  }
};

const normalizeSafeLinks = (html: string) => {
  if (typeof DOMParser === 'undefined') {
    return html;
  }
  const document = new DOMParser().parseFromString(html, 'text/html');
  document.querySelectorAll('a').forEach(anchor => {
    const href = anchor.getAttribute('href');
    if (!isSafeMarkdownUrl(href)) {
      anchor.replaceWith(document.createTextNode(anchor.textContent || ''));
      return;
    }
    anchor.setAttribute('target', '_blank');
    anchor.setAttribute('rel', 'noopener noreferrer');
  });
  return document.body.innerHTML;
};

const sanitizeHtml = (html: string, allowedTags: string[]) => {
  return normalizeSafeLinks(DOMPurify.sanitize(html, sanitizeOptions(allowedTags)));
};

const markdownRenderer = new marked.Renderer();

markdownRenderer.html = () => '';
markdownRenderer.image = () => '';
markdownRenderer.link = (href, title, text) => {
  if (!isSafeMarkdownUrl(href)) {
    return text;
  }
  const titleAttribute = title ? ` title="${escapeHtml(title)}"` : '';
  return `<a href="${escapeHtml(href)}"${titleAttribute} target="_blank" rel="noopener noreferrer">${text}</a>`;
};
markdownRenderer.code = (code, infoString) => {
  const language = normalizeCodeLanguage(infoString);
  if (language === 'echarts') {
    return renderEchartsPlaceholders(typeof code === 'string' ? code : String(code ?? ''));
  }
  return renderCodeBlock(code, language);
};
markdownRenderer.table = (header, body) => {
  return `<div class="safe-markdown-table-wrapper"><table><thead>${header}</thead><tbody>${body}</tbody></table></div>`;
};

export const hasMarkdownTable = (value?: string | null) => {
  if (!value) {
    return false;
  }
  try {
    return marked.lexer(normalizeMarkdownTables(value), { gfm: true }).some(token => token.type === 'table');
  } catch {
    return false;
  }
};

export const renderSafeMarkdown = (value?: string | null) => {
  const markdown = value || '';
  if (!markdown) {
    return '';
  }
  try {
    const html = marked.parse(normalizeMarkdownTables(markdown), {
      breaks: true,
      gfm: true,
      renderer: markdownRenderer
    }) as string;
    return sanitizeHtml(html, MARKDOWN_ALLOWED_TAGS);
  } catch (error) {
    console.warn('Markdown 渲染失败，已按纯文本显示:', error);
    return sanitizeHtml(`<p>${escapeHtml(markdown)}</p>`, MARKDOWN_ALLOWED_TAGS);
  }
};

export const sanitizeLegacyHtml = (value?: string | null) => {
  return sanitizeHtml(value || '', LEGACY_HTML_ALLOWED_TAGS);
};
