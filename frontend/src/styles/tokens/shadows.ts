/**
 * 阴影设计 Token
 * 扁平化设计 - 极简阴影
 */

/** 基础阴影 */
export const shadows = {
  /** 无阴影 */
  none: 'none',
  /** 超小阴影 */
  xs: '0 1px 2px 0 rgba(0, 0, 0, 0.03)',
  /** 小阴影 */
  sm: '0 1px 3px 0 rgba(0, 0, 0, 0.04), 0 1px 2px -1px rgba(0, 0, 0, 0.04)',
  /** 中阴影 */
  md: '0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -2px rgba(0, 0, 0, 0.05)',
  /** 大阴影 */
  lg: '0 10px 15px -3px rgba(0, 0, 0, 0.06), 0 4px 6px -4px rgba(0, 0, 0, 0.06)',
  /** 超大阴影 */
  xl: '0 20px 25px -5px rgba(0, 0, 0, 0.06), 0 8px 10px -6px rgba(0, 0, 0, 0.06)',
  /** 双倍大阴影 */
  '2xl': '0 25px 50px -12px rgba(0, 0, 0, 0.15)'
} as const;

/** 柔和阴影 */
export const softShadows = {
  /** 超小柔和阴影 */
  xs: '0 1px 2px 0 rgba(0, 0, 0, 0.02)',
  /** 小柔和阴影 */
  sm: '0 2px 8px -2px rgba(0, 0, 0, 0.04)',
  /** 中柔和阴影 */
  md: '0 4px 16px -4px rgba(0, 0, 0, 0.06)',
  /** 大柔和阴影 */
  lg: '0 8px 24px -6px rgba(0, 0, 0, 0.06)',
  /** 超大柔和阴影 */
  xl: '0 16px 48px -12px rgba(0, 0, 0, 0.08)'
} as const;

/** 内阴影 */
export const innerShadows = {
  /** 小内阴影 */
  sm: 'inset 0 1px 2px 0 rgba(0, 0, 0, 0.04)',
  /** 中内阴影 */
  md: 'inset 0 2px 4px 0 rgba(0, 0, 0, 0.04)',
  /** 大内阴影 */
  lg: 'inset 0 4px 8px 0 rgba(0, 0, 0, 0.04)'
} as const;

/** 彩色阴影 - 极淡 */
export const coloredShadows = {
  /** 主色阴影 */
  primary: '0 4px 14px 0 rgba(34, 197, 94, 0.15)',
  /** 信息色阴影 */
  info: '0 4px 14px 0 rgba(59, 130, 246, 0.15)',
  /** 成功色阴影 */
  success: '0 4px 14px 0 rgba(34, 197, 94, 0.15)',
  /** 警告色阴影 */
  warning: '0 4px 14px 0 rgba(245, 158, 11, 0.15)',
  /** 错误色阴影 */
  error: '0 4px 14px 0 rgba(239, 68, 68, 0.15)'
} as const;

/** 组件阴影预设 */
export const componentShadows = {
  /** 卡片阴影 - 默认无阴影，用边框 */
  card: {
    default: 'none',
    hover: softShadows.sm
  },
  /** 下拉菜单阴影 */
  dropdown: shadows.md,
  /** 弹窗阴影 */
  dialog: shadows.xl,
  /** 抽屉阴影 */
  drawer: shadows.lg,
  /** 气泡卡片阴影 */
  popover: shadows.md,
  /** 工具提示阴影 */
  tooltip: shadows.sm,
  /** 浮动按钮阴影 */
  fab: shadows.md,
  /** 导航栏阴影 - 无阴影，用边框 */
  navbar: 'none',
  /** 侧边栏阴影 - 无阴影，用边框 */
  sidebar: 'none'
} as const;

/** 阴影 Token 集合 */
export const shadowTokens = {
  base: shadows,
  soft: softShadows,
  inner: innerShadows,
  colored: coloredShadows,
  component: componentShadows
} as const;

export type ShadowTokens = typeof shadowTokens;
