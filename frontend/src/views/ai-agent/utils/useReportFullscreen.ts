import { ref } from 'vue';

export type ReportFullscreenFormat = 'markdown' | 'html';
export type ReportFullscreenSourceFormat = 'markdown' | 'html';

export const useReportFullscreen = () => {
  const showReportFullscreen = ref(false);
  const fullscreenReportContent = ref('');
  const fullscreenReportFormat = ref<ReportFullscreenFormat>('markdown');
  const fullscreenReportSourceFormat = ref<ReportFullscreenSourceFormat>('markdown');

  const openReportFullscreen = (
    content: string,
    format: ReportFullscreenFormat = 'markdown',
    sourceFormat: ReportFullscreenSourceFormat = format
  ) => {
    fullscreenReportContent.value = content;
    fullscreenReportFormat.value = format;
    fullscreenReportSourceFormat.value = sourceFormat;
    showReportFullscreen.value = true;
  };

  const closeReportFullscreen = () => {
    showReportFullscreen.value = false;
    fullscreenReportContent.value = '';
    fullscreenReportFormat.value = 'markdown';
    fullscreenReportSourceFormat.value = 'markdown';
  };

  return {
    showReportFullscreen,
    fullscreenReportContent,
    fullscreenReportFormat,
    fullscreenReportSourceFormat,
    openReportFullscreen,
    closeReportFullscreen
  };
};
