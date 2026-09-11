/**
 * 剪贴板和下载相关工具函数
 */

/** 复制文本到剪贴板 */
export function copyToClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(text);
  }
  // 兼容旧浏览器
  return new Promise((resolve, reject) => {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.cssText = 'position:fixed;left:-9999px;top:-9999px;';
    document.body.appendChild(textarea);
    textarea.select();
    try {
      // eslint-disable-next-line @typescript-eslint/no-deprecated
      document.execCommand('copy');
      resolve();
    } catch {
      reject();
    } finally {
      document.body.removeChild(textarea);
    }
  });
}

/** 从剪贴板读取文本 */
export async function readFromClipboard(): Promise<string> {
  if (navigator.clipboard?.readText) {
    try {
      return await navigator.clipboard.readText();
    } catch {
      return '';
    }
  }
  return '';
}

/** 粘贴文本到可编辑元素 */
export function pasteToElement(el: HTMLElement, text: string) {
  if (!text) return;
  const tagName = el.tagName.toLowerCase();
  if (tagName === 'input' || tagName === 'textarea') {
    const input = el as HTMLInputElement | HTMLTextAreaElement;
    const start = input.selectionStart || 0;
    const end = input.selectionEnd || 0;
    const value = input.value;
    input.value = value.slice(0, start) + text + value.slice(end);
    input.selectionStart = input.selectionEnd = start + text.length;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  } else if (el.isContentEditable) {
    // eslint-disable-next-line @typescript-eslint/no-deprecated
    document.execCommand('insertText', false, text);
  }
}

/** 检查元素是否可编辑 */
export function isEditable(el: HTMLElement | null): boolean {
  if (!el) return false;
  const tagName = el.tagName.toLowerCase();
  return tagName === 'input' || tagName === 'textarea' || el.isContentEditable;
}

/** 检查元素是否是图片 */
export function isImage(el: HTMLElement | null): boolean {
  return el?.tagName.toLowerCase() === 'img';
}

/** 获取图片源地址 */
export function getImageSrc(el: HTMLElement): string {
  return el.tagName.toLowerCase() === 'img' ? (el as HTMLImageElement).src || '' : '';
}
