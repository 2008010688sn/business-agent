export type ChatAttachmentKind = 'image' | 'pdf' | 'docx' | 'xlsx' | 'csv' | 'txt' | 'md' | 'log' | 'audio';

export const MAX_CHAT_IMAGE_COUNT = 3;
export const MAX_CHAT_DOCUMENT_COUNT = 5;

export const IMAGE_FILE_ACCEPT = 'image/png,image/jpeg,image/webp';
export const DOCUMENT_FILE_ACCEPT = [
  '.pdf',
  '.docx',
  '.xlsx',
  '.xls',
  '.csv',
  '.txt',
  '.md',
  '.log',
  '.wav',
  '.mp3',
  '.m4a',
  '.ogg',
  'application/pdf',
  'text/csv',
  'text/plain',
  'text/markdown',
  'audio/wav',
  'audio/mpeg',
  'audio/mp4',
  'audio/ogg'
].join(',');

export const resolveChatAttachmentType = (file: { name?: string; type?: string }): ChatAttachmentKind | null => {
  const mime = String(file.type || '')
    .split(';', 1)[0]
    .trim()
    .toLowerCase();
  const name = String(file.name || '')
    .trim()
    .toLowerCase();
  if (mime.startsWith('image/')) {
    return 'image';
  }
  if (mime.includes('pdf') || name.endsWith('.pdf')) {
    return 'pdf';
  }
  if (name.endsWith('.docx') || mime.includes('wordprocessingml')) {
    return 'docx';
  }
  if (
    name.endsWith('.xlsx') ||
    name.endsWith('.xls') ||
    mime.includes('spreadsheet') ||
    mime === 'application/vnd.ms-excel'
  ) {
    return 'xlsx';
  }
  if (name.endsWith('.csv') || mime.includes('csv')) {
    return 'csv';
  }
  if (name.endsWith('.md') || mime.includes('markdown')) {
    return 'md';
  }
  if (name.endsWith('.log')) {
    return 'log';
  }
  if (name.endsWith('.txt') || mime === 'text/plain') {
    return 'txt';
  }
  if (mime.startsWith('audio/') || mime === 'video/webm' || /\.(wav|mp3|m4a|ogg|webm)$/.test(name)) {
    return 'audio';
  }
  return null;
};

export const isImageAttachmentType = (type?: string | null) => type === 'image';

export const isDocumentAttachmentType = (type?: string | null) => {
  return (
    type === 'pdf' ||
    type === 'docx' ||
    type === 'xlsx' ||
    type === 'csv' ||
    type === 'txt' ||
    type === 'md' ||
    type === 'log'
  );
};

export const isKnowledgeEligibleAttachmentType = (type?: string | null) => isDocumentAttachmentType(type);
