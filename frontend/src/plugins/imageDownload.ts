/**
 * ElImage 图片预览下载功能插件
 * 通过 MutationObserver 监听预览器打开，动态添加下载按钮
 */

import { $t } from '@/locales';
let observer: MutationObserver | null = null;

// 下载图片
async function downloadImage(url: string) {
  try {
    const response = await fetch(url, { mode: 'cors' });
    const blob = await response.blob();
    const blobUrl = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = blobUrl;
    // 从 URL 提取文件名
    const fileName = url.split('/').pop()?.split('?')[0] || `image_${Date.now()}.png`;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(blobUrl);
  } catch {
    // 如果 fetch 失败（跨域），直接打开新窗口
    window.open(url, '_blank');
  }
}

// 创建下载按钮
function createDownloadButton(getCurrentImageUrl: () => string): HTMLElement {
  const btn = document.createElement('span');
  btn.className = 'el-image-viewer__btn el-image-viewer__download';
  btn.innerHTML = `
    <svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg" width="15" height="15">
      <path fill="currentColor" d="M160 832h704a32 32 0 1 1 0 64H160a32 32 0 1 1 0-64zm384-253.696 236.288-236.352 45.248 45.248L508.8 704 192 387.2l45.248-45.248L480 584.704V128h64v450.304z"/>
    </svg>
  `;
  btn.style.cssText = 'cursor: pointer;';
  btn.title = $t('common.downloadImage');
  btn.addEventListener('click', () => {
    const url = getCurrentImageUrl();
    if (url) downloadImage(url);
  });
  return btn;
}

// 注入下载按钮到预览器
function injectDownloadButton(viewer: Element) {
  // 避免重复添加
  if (viewer.querySelector('.el-image-viewer__download')) return;

  const actions = viewer.querySelector('.el-image-viewer__actions__inner');
  if (!actions) return;

  // 获取当前显示图片的 URL（每次点击时实时获取）
  const getCurrentImageUrl = () => {
    // Element Plus 预览器中，当前显示的图片在 canvas 容器内
    // 需要找到当前可见的图片（通过 transform 或 display 判断）
    const imgs = viewer.querySelectorAll('.el-image-viewer__img') as NodeListOf<HTMLImageElement>;

    // 如果只有一张图片
    if (imgs.length === 1) {
      return imgs[0]?.src || '';
    }

    // 多张图片时，找到当前显示的那张（没有 display:none 且在视口中心的）
    for (const img of imgs) {
      const style = window.getComputedStyle(img);
      // 检查是否可见
      if (style.display !== 'none') {
        const rect = img.getBoundingClientRect();
        // 检查图片是否在视口中心区域（可见）
        if (rect.width > 0 && rect.height > 0) {
          return img.src;
        }
      }
    }

    // 兜底：返回第一张可见图片
    const visibleImg = viewer.querySelector(
      '.el-image-viewer__canvas .el-image-viewer__img:not([style*="display: none"])'
    ) as HTMLImageElement;
    return visibleImg?.src || '';
  };

  const downloadBtn = createDownloadButton(getCurrentImageUrl);
  actions.appendChild(downloadBtn);
}

// 启动监听
export function setupImageDownload() {
  if (observer) return;

  observer = new MutationObserver(mutations => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node instanceof Element) {
          // 检查是否是图片预览器
          const viewer = node.classList.contains('el-image-viewer__wrapper')
            ? node
            : node.querySelector('.el-image-viewer__wrapper');
          if (viewer) {
            // 延迟一下确保 DOM 完全渲染
            setTimeout(() => injectDownloadButton(viewer), 50);
          }
        }
      }
    }
  });

  observer.observe(document.body, {
    childList: true,
    subtree: true
  });
}

// 停止监听（一般不需要调用）
export function destroyImageDownload() {
  if (observer) {
    observer.disconnect();
    observer = null;
  }
}
