import { CHAT_MESSAGE_TYPES, type ChatMessage } from '@/views/ai-agent/services/chat';

export interface MessageExplainMetadata {
  contentFormat?: MessageContentFormat;
  runtimeRequestId?: string;
  explainAvailable?: boolean;
  nodeName?: string;
  attachments?: MessageAttachment[];
  messageType?: string;
  [key: string]: unknown;
}

export type MessageContentFormat = 'plain' | 'markdown' | 'legacy-html';

export interface MessageDisplayContent {
  content: string;
  contentFormat: MessageContentFormat;
}

export interface MessageAttachment {
  type?: string;
  storageKey?: string;
  url?: string;
  previewUrl?: string;
  contentType?: string;
  fileName?: string;
  size?: number;
}

export const getMessageRole = (message: ChatMessage) => message.role?.toLowerCase() || '';

const getMessageType = (message: ChatMessage) => message.messageType?.toLowerCase() || '';

export const isTextMessage = (message: ChatMessage) => getMessageType(message) === CHAT_MESSAGE_TYPES.TEXT;

export const isTextualMessage = (message: ChatMessage) => {
  const messageType = getMessageType(message);
  return messageType === CHAT_MESSAGE_TYPES.TEXT || messageType === CHAT_MESSAGE_TYPES.MARKDOWN;
};

const isMessageContentFormat = (value: unknown): value is MessageContentFormat => {
  return value === 'plain' || value === 'markdown' || value === 'legacy-html';
};

export const getMessageRenderKey = (message: ChatMessage, index: number) => {
  if (message.id !== undefined && message.id !== null) {
    return String(message.id);
  }
  const createdAt = message.createTime ? String(message.createTime) : '';
  return `${message.sessionId}-${index}-${createdAt}`;
};

export const writeTextToClipboard = async (text: string) => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', 'readonly');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.select();
  document.execCommand('copy');
  document.body.removeChild(textarea);
};

export const parseMessageMetadata = (message: ChatMessage): MessageExplainMetadata | null => {
  if (!message.metadata) {
    return null;
  }
  try {
    const parsed = JSON.parse(message.metadata) as MessageExplainMetadata;
    return parsed && typeof parsed === 'object' ? parsed : null;
  } catch (error) {
    console.warn('解析消息 metadata 失败:', error);
    return null;
  }
};

export const getMessageContentFormat = (message: ChatMessage): MessageContentFormat => {
  if (getMessageRole(message) !== 'assistant') {
    return 'plain';
  }
  const messageType = getMessageType(message);
  if (messageType === CHAT_MESSAGE_TYPES.MARKDOWN) {
    return 'markdown';
  }
  if (messageType === 'html') {
    return 'legacy-html';
  }
  const format = parseMessageMetadata(message)?.contentFormat;
  if (isMessageContentFormat(format)) {
    return format;
  }
  return 'plain';
};

const LEGACY_FINAL_ANSWER_SELECTOR = '.agent-final-answer-content';
const LEGACY_BLOCK_ELEMENTS = new Set([
  'ARTICLE',
  'ASIDE',
  'BLOCKQUOTE',
  'DIV',
  'FOOTER',
  'H1',
  'H2',
  'H3',
  'H4',
  'H5',
  'H6',
  'HEADER',
  'LI',
  'MAIN',
  'OL',
  'P',
  'PRE',
  'SECTION',
  'TABLE',
  'TR',
  'UL'
]);

const normalizeLegacyText = (value: string) => {
  return value
    .replace(/\u00A0/g, ' ')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
};

const extractLegacyElementText = (element: Element) => {
  let content = '';
  const appendLineBreak = () => {
    if (content && !content.endsWith('\n')) {
      content += '\n';
    }
  };
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      content += node.textContent || '';
      return;
    }
    if (node.nodeType !== Node.ELEMENT_NODE) {
      return;
    }
    const childElement = node as Element;
    const tagName = childElement.tagName;
    if (tagName === 'BR') {
      appendLineBreak();
      return;
    }
    const isBlock = LEGACY_BLOCK_ELEMENTS.has(tagName);
    if (isBlock) {
      appendLineBreak();
    }
    if (tagName === 'LI') {
      content += '- ';
    }
    Array.from(childElement.childNodes).forEach(walk);
    if (isBlock) {
      appendLineBreak();
    }
  };
  Array.from(element.childNodes).forEach(walk);
  return normalizeLegacyText(content);
};

export const extractLegacyFinalAnswerMarkdown = (content?: string | null): string | null => {
  if (!content || typeof DOMParser === 'undefined') {
    return null;
  }
  const document = new DOMParser().parseFromString(content, 'text/html');
  const finalAnswer = document.querySelector(LEGACY_FINAL_ANSWER_SELECTOR);
  return finalAnswer ? extractLegacyElementText(finalAnswer) : null;
};

export const resolveMessageDisplayContent = (message: ChatMessage): MessageDisplayContent => {
  const contentFormat = getMessageContentFormat(message);
  if (contentFormat !== 'legacy-html') {
    return {
      content: message.content || '',
      contentFormat
    };
  }
  const extractedContent = extractLegacyFinalAnswerMarkdown(message.content);
  if (extractedContent !== null) {
    return {
      content: extractedContent,
      contentFormat: 'markdown'
    };
  }
  return {
    content: message.content || '',
    contentFormat
  };
};

export const getMessageAttachments = (message: ChatMessage): MessageAttachment[] => {
  const metadata = parseMessageMetadata(message);
  return (metadata?.attachments || []).filter(attachment => Boolean(attachment));
};

export const getMessageImageAttachments = (message: ChatMessage): MessageAttachment[] => {
  return getMessageAttachments(message).filter(attachment => attachment?.type === 'image' && Boolean(attachment.url));
};

export const getMessageFileAttachments = (message: ChatMessage): MessageAttachment[] => {
  return getMessageAttachments(message).filter(attachment => attachment?.type && attachment.type !== 'image');
};
