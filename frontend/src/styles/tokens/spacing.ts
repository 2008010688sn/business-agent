/**
 * 间距设计 Token
 * 用于统一项目中的间距使用
 */

/** 基础间距单位 (4px) */
export const BASE_UNIT = 4;

/** 间距比例 */
export const spacing = {
  /** 0px */
  0: '0px',
  /** 2px */
  0.5: '2px',
  /** 4px */
  1: '4px',
  /** 6px */
  1.5: '6px',
  /** 8px */
  2: '8px',
  /** 10px */
  2.5: '10px',
  /** 12px */
  3: '12px',
  /** 14px */
  3.5: '14px',
  /** 16px */
  4: '16px',
  /** 20px */
  5: '20px',
  /** 24px */
  6: '24px',
  /** 28px */
  7: '28px',
  /** 32px */
  8: '32px',
  /** 36px */
  9: '36px',
  /** 40px */
  10: '40px',
  /** 44px */
  11: '44px',
  /** 48px */
  12: '48px',
  /** 56px */
  14: '56px',
  /** 64px */
  16: '64px',
  /** 80px */
  20: '80px',
  /** 96px */
  24: '96px',
  /** 112px */
  28: '112px',
  /** 128px */
  32: '128px'
} as const;

/** 组件间距预设 */
export const componentSpacing = {
  /** 按钮内边距 */
  button: {
    small: { x: '12px', y: '6px' },
    medium: { x: '16px', y: '8px' },
    large: { x: '20px', y: '10px' }
  },
  /** 输入框内边距 */
  input: {
    small: { x: '8px', y: '4px' },
    medium: { x: '12px', y: '6px' },
    large: { x: '16px', y: '8px' }
  },
  /** 卡片内边距 */
  card: {
    small: '12px',
    medium: '16px',
    large: '24px'
  },
  /** 弹窗内边距 */
  dialog: {
    header: '20px 24px',
    body: '24px',
    footer: '16px 24px'
  },
  /** 表格单元格内边距 */
  table: {
    cell: '12px 16px',
    header: '16px'
  }
} as const;

/** 间距 Token 集合 */
export const spacingTokens = {
  base: BASE_UNIT,
  scale: spacing,
  component: componentSpacing
} as const;

export type SpacingTokens = typeof spacingTokens;
