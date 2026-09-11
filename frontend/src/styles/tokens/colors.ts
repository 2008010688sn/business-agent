/**
 * 颜色设计 Token
 * 扁平化设计 - 蓝灰色调
 */

/** 品牌色 - 主色 */
export const primaryColors = {
  50: '#f0fdf4',
  100: '#dcfce7',
  200: '#bbf7d0',
  300: '#86efac',
  400: '#4ade80',
  500: '#22c55e',
  600: '#16a34a',
  700: '#15803d',
  800: '#166534',
  900: '#14532d',
  950: '#052e16'
} as const;

/** 中性色 - 蓝灰 (Slate) */
export const neutralColors = {
  0: '#ffffff',
  50: '#f8fafc',
  100: '#f1f5f9',
  200: '#e2e8f0',
  300: '#cbd5e1',
  400: '#94a3b8',
  500: '#64748b',
  600: '#475569',
  700: '#334155',
  800: '#1e293b',
  900: '#0f172a',
  1000: '#020617'
} as const;

/** 功能色 */
export const functionalColors = {
  info: {
    light: '#eff6ff',
    default: '#3b82f6',
    dark: '#2563eb'
  },
  success: {
    light: '#f0fdf4',
    default: '#22c55e',
    dark: '#16a34a'
  },
  warning: {
    light: '#fffbeb',
    default: '#f59e0b',
    dark: '#d97706'
  },
  error: {
    light: '#fef2f2',
    default: '#ef4444',
    dark: '#dc2626'
  }
} as const;

/** 背景色 */
export const backgroundColors = {
  page: '#f8fafc',
  container: '#ffffff',
  card: '#ffffff',
  hover: '#f1f5f9',
  active: '#e2e8f0',
  disabled: '#f1f5f9',
  mask: 'rgba(0, 0, 0, 0.4)',
  tooltip: 'rgba(15, 23, 42, 0.9)'
} as const;

/** 文本色 */
export const textColors = {
  primary: '#0f172a',
  secondary: '#475569',
  tertiary: '#94a3b8',
  disabled: '#cbd5e1',
  inverse: '#ffffff',
  link: '#22c55e',
  linkHover: '#16a34a'
} as const;

/** 边框色 */
export const borderColors = {
  light: '#e2e8f0',
  default: '#cbd5e1',
  dark: '#94a3b8',
  disabled: '#e2e8f0'
} as const;

/** 颜色 Token 集合 */
export const colorTokens = {
  primary: primaryColors,
  neutral: neutralColors,
  functional: functionalColors,
  background: backgroundColors,
  text: textColors,
  border: borderColors
} as const;

export type ColorTokens = typeof colorTokens;
