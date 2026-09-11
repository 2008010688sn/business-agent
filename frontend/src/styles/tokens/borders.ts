/**
 * 边框设计 Token
 * 扁平化设计 - 大圆角
 */

/** 边框宽度 */
export const borderWidth = {
  /** 0px */
  0: '0px',
  /** 1px */
  1: '1px',
  /** 2px */
  2: '2px',
  /** 4px */
  4: '4px',
  /** 8px */
  8: '8px'
} as const;

/** 边框样式 */
export const borderStyle = {
  solid: 'solid',
  dashed: 'dashed',
  dotted: 'dotted',
  double: 'double',
  none: 'none'
} as const;

/** 圆角 - 统一放大 */
export const borderRadius = {
  /** 0px - 无圆角 */
  none: '0px',
  /** 6px - 小圆角 */
  sm: '6px',
  /** 8px - 默认圆角 */
  DEFAULT: '8px',
  /** 10px - 中圆角 */
  md: '10px',
  /** 12px - 大圆角 */
  lg: '12px',
  /** 16px - 超大圆角 */
  xl: '16px',
  /** 20px - 特大圆角 */
  '2xl': '20px',
  /** 24px - 巨大圆角 */
  '3xl': '24px',
  /** 9999px - 全圆角 */
  full: '9999px'
} as const;

/** 组件圆角预设 */
export const componentBorderRadius = {
  /** 按钮圆角 */
  button: borderRadius.md,
  /** 输入框圆角 */
  input: borderRadius.md,
  /** 卡片圆角 */
  card: borderRadius.xl,
  /** 弹窗圆角 */
  dialog: borderRadius['2xl'],
  /** 头像圆角 */
  avatar: borderRadius.full,
  /** 标签圆角 */
  tag: borderRadius.lg,
  /** 徽章圆角 */
  badge: borderRadius.full,
  /** 气泡卡片圆角 */
  popover: borderRadius.lg,
  /** 工具提示圆角 */
  tooltip: borderRadius.md,
  /** 下拉菜单圆角 */
  dropdown: borderRadius.lg,
  /** 抽屉圆角 */
  drawer: {
    left: `0 ${borderRadius.xl} ${borderRadius.xl} 0`,
    right: `${borderRadius.xl} 0 0 ${borderRadius.xl}`,
    top: `0 0 ${borderRadius.xl} ${borderRadius.xl}`,
    bottom: `${borderRadius.xl} ${borderRadius.xl} 0 0`
  }
} as const;

/** 边框预设 */
export const borders = {
  /** 默认边框 */
  default: `${borderWidth[1]} ${borderStyle.solid} #e2e8f0`,
  /** 浅色边框 */
  light: `${borderWidth[1]} ${borderStyle.solid} #f1f5f9`,
  /** 深色边框 */
  dark: `${borderWidth[1]} ${borderStyle.solid} #cbd5e1`,
  /** 主色边框 */
  primary: `${borderWidth[1]} ${borderStyle.solid} #22c55e`,
  /** 虚线边框 */
  dashed: `${borderWidth[1]} ${borderStyle.dashed} #cbd5e1`,
  /** 无边框 */
  none: 'none'
} as const;

/** 边框 Token 集合 */
export const borderTokens = {
  width: borderWidth,
  style: borderStyle,
  radius: borderRadius,
  componentRadius: componentBorderRadius,
  presets: borders
} as const;

export type BorderTokens = typeof borderTokens;
