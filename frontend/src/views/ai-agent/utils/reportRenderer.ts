import { renderSafeMarkdown, sanitizeLegacyHtml } from '@/views/ai-agent/utils/safeMarkdown';

export type ReportSourceFormat = 'markdown' | 'html';

type EchartsModule = typeof import('echarts');
type ReportChartDisposer = () => void;

const ECHARTS_URL = 'https://mirrors.sustech.edu.cn/cdnjs/ajax/libs/echarts/5.5.0/echarts.min.js';
const REPORT_CHART_SELECTOR = '.safe-markdown-echarts';
const noop = () => {};

let echartsModulePromise: Promise<EchartsModule> | null = null;

const loadEcharts = () => {
  echartsModulePromise ||= import('echarts');
  return echartsModulePromise;
};

const escapeHtml = (value: string) => {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
};

const isPlainObject = (value: unknown): value is Record<string, unknown> => {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
};

export const renderReportMarkdownFragment = (markdown?: string | null) => {
  return renderSafeMarkdown(markdown);
};

export const extractLegacyReportMarkdown = (html?: string | null) => {
  const source = html || '';
  if (!source || typeof DOMParser === 'undefined') {
    return '';
  }
  try {
    const document = new DOMParser().parseFromString(source, 'text/html');
    const rawMarkdown = document.querySelector<HTMLElement>('#raw-markdown');
    return rawMarkdown?.textContent?.trim() || '';
  } catch {
    return '';
  }
};

export const renderReportHtmlFragment = (content?: string | null, sourceFormat: ReportSourceFormat = 'markdown') => {
  if (sourceFormat === 'markdown') {
    return renderReportMarkdownFragment(content);
  }
  const legacyMarkdown = extractLegacyReportMarkdown(content);
  if (legacyMarkdown) {
    return renderReportMarkdownFragment(legacyMarkdown);
  }
  return sanitizeLegacyHtml(content);
};

export const hydrateReportCharts = async (root: ParentNode | null): Promise<ReportChartDisposer> => {
  if (!root) {
    return noop;
  }
  const elements = Array.from(root.querySelectorAll<HTMLElement>(REPORT_CHART_SELECTOR));
  if (elements.length === 0) {
    return noop;
  }

  let echarts: EchartsModule;
  try {
    echarts = await loadEcharts();
  } catch (error) {
    console.warn('ECharts 图表库加载失败:', error);
    elements.forEach(element => {
      element.classList.add('safe-markdown-echarts-error');
      element.textContent = 'ECharts 加载失败，暂无法渲染图表。';
    });
    return noop;
  }

  const charts: Array<ReturnType<EchartsModule['init']>> = [];
  const resizeObserver =
    typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(() => {
          charts.forEach(chart => chart.resize());
        });

  elements.forEach(element => {
    if (!element.isConnected) {
      return;
    }
    const optionText = element.textContent?.trim();
    if (!optionText) {
      return;
    }
    try {
      const option = JSON.parse(optionText);
      if (!isPlainObject(option)) {
        throw new Error('ECharts option 必须是 JSON 对象');
      }
      element.textContent = '';
      const chart = echarts.init(element);
      chart.setOption(option);
      charts.push(chart);
      resizeObserver?.observe(element);
    } catch (error) {
      console.warn('ECharts 图表渲染失败:', error);
      element.classList.add('safe-markdown-echarts-error');
      element.textContent = '图表配置格式错误，暂无法渲染。';
    }
  });

  if (charts.length === 0 && !resizeObserver) {
    return noop;
  }

  return () => {
    resizeObserver?.disconnect();
    charts.forEach(chart => chart.dispose());
  };
};

export const buildReportHtmlDocument = (
  content?: string | null,
  options: { sourceFormat?: ReportSourceFormat } = {}
) => {
  const sourceFormat = options.sourceFormat || 'markdown';
  const reportContent = content || '';
  const renderedContent = reportContent
    ? renderReportHtmlFragment(reportContent, sourceFormat)
    : '<p class="report-empty">暂无报告内容</p>';
  const needsCharts = renderedContent.includes('safe-markdown-echarts');

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>分析报告</title>
${needsCharts ? `<script src="${ECHARTS_URL}"></script>` : ''}
<style>
* { box-sizing: border-box; }
body { margin: 0; padding: 20px; background: #f3f4f6; color: #24352b; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; line-height: 1.7; }
.container { max-width: 940px; margin: 0 auto; padding: 36px; background: #fff; border-radius: 10px; box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08); }
h1, h2, h3, h4, h5, h6 { color: #183627; line-height: 1.35; }
h1 { margin-top: 0; padding-bottom: 10px; border-bottom: 1px solid #e2ece4; font-size: 28px; }
h2 { margin-top: 32px; padding-left: 12px; border-left: 4px solid #167243; color: #167243; font-size: 22px; }
h3 { margin-top: 24px; font-size: 18px; }
p, ul, ol, blockquote, table, pre { margin: 12px 0; }
ul, ol { padding-left: 24px; }
blockquote { margin-left: 0; padding: 8px 12px; border-left: 3px solid #71b68d; background: #f5faf6; }
a { color: #167243; }
table { display: block; max-width: 100%; overflow-x: auto; border-collapse: collapse; border-spacing: 0; }
th, td { min-width: 96px; padding: 8px 10px; border: 1px solid #dce8df; text-align: left; vertical-align: top; }
th { background: #f3f8f4; color: #315b40; }
code { padding: 1px 5px; border-radius: 4px; background: #eef5f0; color: #275238; font-family: Consolas, monospace; font-size: 0.92em; }
pre { max-width: 100%; overflow-x: auto; padding: 12px; border: 1px solid #d9e7dc; border-radius: 8px; background: #f8fbf8; }
pre code { padding: 0; background: transparent; color: #244630; white-space: pre; }
.safe-markdown-code-header { display: none; }
.safe-markdown-echarts { width: 100%; height: 420px; min-height: 320px; margin: 18px 0; overflow: hidden; border: 1px solid #d9e7dc; border-radius: 8px; background: #fff; font-size: 0; }
.safe-markdown-echarts-error, .report-chart-error { display: flex; align-items: center; justify-content: center; min-height: 120px; padding: 16px; border: 1px dashed #ef4444; border-radius: 8px; background: #fef2f2; color: #dc2626; font-size: 13px; text-align: center; }
.report-empty { color: #64748b; }
</style>
</head>
<body>
<main class="container">${renderedContent}</main>
${needsCharts ? buildStandaloneChartScript() : ''}
</body>
</html>`;
};

const buildStandaloneChartScript = () => `<script>
(function() {
  function renderCharts() {
    var boxes = document.querySelectorAll('.safe-markdown-echarts');
    if (!boxes.length) return;
    if (typeof echarts === 'undefined') {
      boxes.forEach(function(box) {
        box.classList.add('safe-markdown-echarts-error');
        box.textContent = 'ECharts 加载失败，暂无法渲染图表。';
      });
      return;
    }
    var charts = [];
    boxes.forEach(function(box) {
      var code = (box.textContent || '').trim();
      if (!code) return;
      try {
        var option = JSON.parse(code);
        if (!option || typeof option !== 'object' || Array.isArray(option)) {
          throw new Error('ECharts option 必须是 JSON 对象');
        }
        box.textContent = '';
        var chart = echarts.init(box);
        chart.setOption(option);
        charts.push(chart);
      } catch (error) {
        box.classList.add('safe-markdown-echarts-error');
        box.textContent = '图表配置格式错误，暂无法渲染。';
      }
    });
    window.addEventListener('resize', function() {
      charts.forEach(function(chart) { chart.resize(); });
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', renderCharts);
  } else {
    renderCharts();
  }
})();
</script>`;

export const buildEscapedRawMarkdownNode = (markdown?: string | null) => {
  return `<div id="raw-markdown" style="display:none;">${escapeHtml(markdown || '')}</div>`;
};
