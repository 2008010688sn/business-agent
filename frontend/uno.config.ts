import { defineConfig } from '@unocss/vite';
import transformerDirectives from '@unocss/transformer-directives';
import transformerVariantGroup from '@unocss/transformer-variant-group';
import presetUno from '@unocss/preset-uno';
import type { Theme } from '@unocss/preset-uno';
import { presetSoybeanAdmin } from '@sa/uno-preset';
import { themeVars } from './src/theme/vars';

export default defineConfig<Theme>({
  content: {
    pipeline: {
      exclude: ['node_modules', 'dist']
    }
  },
  theme: {
    ...themeVars,
    // 扩展断点
    breakpoints: {
      'xs': '480px',
      'sm': '640px',
      'md': '768px',
      'lg': '1024px',
      'xl': '1280px',
      '2xl': '1536px'
    },
    // 扩展字体大小
    fontSize: {
      'icon-xs': '0.875rem',
      'icon-small': '1rem',
      icon: '1.125rem',
      'icon-large': '1.5rem',
      'icon-xl': '2rem',
      'xs': '12px',
      'sm': '13px',
      'base': '14px',
      'md': '15px',
      'lg': '16px',
      'xl': '18px',
      '2xl': '20px',
      '3xl': '24px',
      '4xl': '30px',
      '5xl': '36px'
    },
    // 扩展间距
    spacing: {
      '4.5': '1.125rem',
      '13': '3.25rem',
      '15': '3.75rem',
      '18': '4.5rem',
      '22': '5.5rem',
      '26': '6.5rem',
      '30': '7.5rem'
    },
    // 扩展圆角
    borderRadius: {
      'none': '0px',
      'sm': '2px',
      'DEFAULT': '4px',
      'md': '6px',
      'lg': '8px',
      'xl': '12px',
      '2xl': '16px',
      '3xl': '24px',
      'full': '9999px'
    },
    // 扩展阴影
    boxShadow: {
      'none': 'none',
      'xs': '0 1px 2px 0 rgba(0, 0, 0, 0.05)',
      'sm': '0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px -1px rgba(0, 0, 0, 0.1)',
      'DEFAULT': '0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px -1px rgba(0, 0, 0, 0.1)',
      'md': '0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -2px rgba(0, 0, 0, 0.1)',
      'lg': '0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -4px rgba(0, 0, 0, 0.1)',
      'xl': '0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 8px 10px -6px rgba(0, 0, 0, 0.1)',
      '2xl': '0 25px 50px -12px rgba(0, 0, 0, 0.25)',
      'soft-xs': '0 1px 2px 0 rgba(0, 0, 0, 0.03)',
      'soft-sm': '0 2px 8px -2px rgba(0, 0, 0, 0.06)',
      'soft-md': '0 4px 16px -4px rgba(0, 0, 0, 0.08)',
      'soft-lg': '0 8px 24px -6px rgba(0, 0, 0, 0.1)',
      'soft-xl': '0 16px 48px -12px rgba(0, 0, 0, 0.12)',
      'inner-sm': 'inset 0 1px 2px 0 rgba(0, 0, 0, 0.06)',
      'inner-md': 'inset 0 2px 4px 0 rgba(0, 0, 0, 0.06)',
      'inner-lg': 'inset 0 4px 8px 0 rgba(0, 0, 0, 0.06)',
      'primary': '0 4px 14px 0 rgba(0, 174, 66, 0.3)',
      'info': '0 4px 14px 0 rgba(32, 128, 240, 0.3)',
      'success': '0 4px 14px 0 rgba(82, 196, 26, 0.3)',
      'warning': '0 4px 14px 0 rgba(250, 173, 20, 0.3)',
      'error': '0 4px 14px 0 rgba(245, 34, 45, 0.3)',
      header: 'var(--header-box-shadow)',
      sider: 'var(--sider-box-shadow)',
      tab: 'var(--tab-box-shadow)'
    },
  },
  shortcuts: {
    // 原有快捷方式
    'card-wrapper': 'rd-8px shadow-sm',
    
    // 极简主义2.0卡片样式
    'card': 'bg-white rd-xl shadow-xs p-6 transition-all duration-200',
    'card-hover': 'card hover:shadow-sm hover:-translate-y-0.5',
    'card-flat': 'bg-white rd-xl border border-neutral-100 p-6',
    
    // 极简主义2.0卡片
    'minimal-card': 'rd-xl bg-white border border-[rgba(0,0,0,0.04)] shadow-xs transition-all duration-200',
    'minimal-card-base': 'minimal-card p-6',
    'minimal-card-sm': 'minimal-card p-4',
    'minimal-card-lg': 'minimal-card p-8',
    'minimal-card-hover': 'hover:shadow-sm hover:border-[rgba(0,0,0,0.06)]',
    
    // 极简主义2.0导航
    'minimal-nav': 'bg-white border-b border-[rgba(0,0,0,0.04)] shadow-xs',
    'minimal-sidebar': 'bg-white border-r border-[rgba(0,0,0,0.04)]',
    'minimal-tab': 'bg-white border-b border-[rgba(0,0,0,0.04)]',
    
    // 极简主义2.0弹窗
    'minimal-modal': 'bg-white rd-2xl shadow-lg',
    'minimal-dropdown': 'bg-white rd-lg shadow-md border border-[rgba(0,0,0,0.06)]',
    'minimal-tooltip': 'bg-neutral-800 text-white rd-md',
    
    // 极简主义2.0输入框
    'minimal-input': 'rd-lg bg-white border border-[rgba(0,0,0,0.08)] px-4 py-2.5 transition-all duration-200 hover:border-[rgba(0,0,0,0.12)] focus:border-[rgba(var(--color-primary-rgb),0.5)] focus:shadow-[0_0_0_3px_rgba(var(--color-primary-rgb),0.08)] focus:outline-none',
    
    // 极简主义2.0按钮
    'minimal-btn': 'inline-flex items-center justify-center rd-lg px-5 py-2.5 font-medium bg-white border border-[rgba(0,0,0,0.08)] shadow-xs transition-all duration-200 cursor-pointer hover:bg-neutral-50 hover:shadow-sm',
    'minimal-btn-primary': 'inline-flex items-center justify-center rd-lg px-5 py-2.5 font-medium bg-primary text-white shadow-sm transition-all duration-200 cursor-pointer hover:bg-primary-600 hover:shadow-md',
    'minimal-btn-secondary': 'minimal-btn hover:bg-neutral-100',
    
    // 极简主义2.0标签
    'minimal-tag': 'inline-flex items-center rd-md bg-neutral-100 border border-[rgba(0,0,0,0.04)] px-3 py-1 text-sm',
    
    // 按钮样式
    'btn': 'inline-flex items-center justify-center rd-lg px-4 py-2 font-medium transition-all duration-200 cursor-pointer select-none',
    'btn-primary': 'btn bg-primary text-white hover:bg-primary-600 active:bg-primary-700',
    'btn-secondary': 'btn bg-neutral-100 text-neutral-700 hover:bg-neutral-200 active:bg-neutral-300',
    'btn-outline': 'btn border-2 border-primary text-primary hover:bg-primary hover:text-white',
    'btn-ghost': 'btn text-neutral-600 hover:bg-neutral-100 active:bg-neutral-200',
    'btn-text': 'btn text-primary hover:bg-primary-50 active:bg-primary-100',
    
    // 输入框样式
    'input-base': 'w-full rd-lg border border-neutral-300 px-4 py-2.5 transition-all duration-200 focus:border-primary focus:ring-2 focus:ring-primary-100 focus:outline-none',
    'input-error': 'input-base border-error focus:border-error focus:ring-error-100',
    
    // 布局工具
    'flex-center': 'flex items-center justify-center',
    'flex-between': 'flex items-center justify-between',
    'flex-col-center': 'flex flex-col items-center justify-center',
    'flex-start': 'flex items-center justify-start',
    'flex-end': 'flex items-center justify-end',
    
    // 文本工具
    'text-truncate': 'overflow-hidden text-overflow-ellipsis whitespace-nowrap',
    'text-clamp-2': 'overflow-hidden text-overflow-ellipsis display-[-webkit-box] -webkit-box-orient-vertical -webkit-line-clamp-2',
    'text-clamp-3': 'overflow-hidden text-overflow-ellipsis display-[-webkit-box] -webkit-box-orient-vertical -webkit-line-clamp-3',
    
    // 间距工具
    'section-gap': 'space-y-6',
    'card-gap': 'space-y-4',
    'inline-gap': 'space-x-4',
    
    // 状态样式
    'interactive': 'transition-all duration-200 cursor-pointer select-none',
    'disabled': 'opacity-50 cursor-not-allowed pointer-events-none',
    
    // 容器样式
    'page-container': 'p-6 bg-neutral-50 min-h-screen',
    'content-container': 'bg-white rd-xl shadow-xs p-6',
    
    // 表格样式
    'table-container': 'bg-white rd-xl shadow-xs overflow-hidden',
    'table-header': 'px-6 py-4 border-b border-neutral-100',
    'table-body': 'divide-y divide-neutral-100',
    'table-row': 'px-6 py-4 hover:bg-neutral-50 transition-colors duration-150',
    'table-cell': 'px-4 py-3',
    
    // 弹窗样式
    'modal-overlay': 'fixed inset-0 bg-black/30 flex-center z-50',
    'modal-content': 'minimal-modal max-w-lg w-full mx-4',
    'modal-header': 'px-6 py-4 border-b border-neutral-100',
    'modal-body': 'px-6 py-4',
    'modal-footer': 'px-6 py-4 border-t border-neutral-100 flex justify-end gap-3',
    
    // 极简动画
    'minimal-fade-in': 'animate-[minimal-fade-in_0.2s_ease_forwards]',
    'minimal-slide-up': 'animate-[minimal-slide-up_0.25s_ease_forwards]',
    'minimal-scale-in': 'animate-[minimal-scale-in_0.2s_ease_forwards]',
    'minimal-scale-spring': 'animate-[minimal-scale-spring_0.3s_ease_forwards]'
  },
  transformers: [transformerDirectives(), transformerVariantGroup()],
  presets: [presetUno({ dark: 'class' }), presetSoybeanAdmin()]
});
