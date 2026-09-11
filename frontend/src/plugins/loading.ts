import { getRgb } from '@sa/color';
import { $t } from '@/locales';
import { localStg } from '@/utils/storage';
import imgLogo from '@/assets/imgs/logo.svg';
import { DARK_CLASS } from '@/constants/app';
import { toggleHtmlClass } from '@/utils/common';

export function setupLoading() {
  const themeColor = localStg.get('themeColor') || '#22c55e';
  const darkMode = localStg.get('darkMode') || false;
  const { r, g, b } = getRgb(themeColor);
  const primaryColor = `--primary-color: ${r} ${g} ${b}`;

  if (darkMode) {
    toggleHtmlClass(DARK_CLASS).add();
  }

  const loadingText = Array.from($t('common.loading'))
    .map((item, index) => {
      const text = item === ' ' ? '&nbsp;' : item;
      return `<span style="animation-delay:${index * 0.08}s">${text}</span>`;
    })
    .join('');

  const loading = `
  <style>
    .app-loading-wave span {
      display: inline-block;
      animation: app-loading-wave 1.2s ease-in-out infinite;
    }
    @keyframes app-loading-wave {
      0%, 60%, 100% { transform: translateY(0); }
      30% { transform: translateY(-5px); }
    }
  </style>
  <div class="fixed-center flex-col bg-layout px-24px" style="${primaryColor}">
    <div class="flex flex-col items-center">
      <img style="border-radius:12px" class="w-56px h-56px shadow-sm" src="${imgLogo}"/>
      <h2 class="mb-0 mt-16px text-20px font-600 text-primary">${$t('system.title')}</h2>
      <p class="app-loading-wave mb-28px mt-8px text-14px text-gray-500">${loadingText}</p>
    </div>
  </div>`;

  const app = document.getElementById('app');
  if (app) {
    app.innerHTML = loading;
  }
}
