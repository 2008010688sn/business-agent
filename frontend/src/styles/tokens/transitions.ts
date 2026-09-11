/**
 * 过渡动画设计 Token
 * 扁平化设计 - 更流畅的交互动效
 */

/** 过渡时长 */
export const duration = {
  /** 100ms - 即时反馈 */
  instant: '100ms',
  /** 150ms - 快速 */
  fast: '150ms',
  /** 100ms - 较快 */
  faster: '100ms',
  /** 150ms - 正常快速 */
  normal: '150ms',
  /** 200ms - 正常 */
  DEFAULT: '200ms',
  /** 250ms - 正常慢速 */
  slower: '250ms',
  /** 300ms - 慢速 */
  slow: '300ms',
  /** 500ms - 较慢 */
  verySlow: '500ms',
  /** 700ms - 超慢 */
  ultraSlow: '700ms'
} as const;

/** 缓动函数 - 扁平化设计专用 */
export const easing = {
  /** 线性 */
  linear: 'linear',
  /** 缓入 */
  easeIn: 'cubic-bezier(0.4, 0, 1, 1)',
  /** 缓出 */
  easeOut: 'cubic-bezier(0, 0, 0.2, 1)',
  /** 缓入缓出 */
  easeInOut: 'cubic-bezier(0.4, 0, 0.2, 1)',
  /** 弹性效果 - 更柔和 */
  bounce: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
  /** 平滑效果 */
  smooth: 'cubic-bezier(0.4, 0, 0.2, 1)',
  /** 四次缓出 - 更自然 */
  outQuart: 'cubic-bezier(0.25, 1, 0.5, 1)',
  /** 四次缓入缓出 */
  inOutQuart: 'cubic-bezier(0.76, 0, 0.24, 1)',
  /** 弹性缓动 */
  spring: 'cubic-bezier(0.34, 1.56, 0.64, 1)'
} as const;

/** 过渡属性 */
export const property = {
  /** 所有属性 */
  all: 'all',
  /** 颜色相关 */
  colors: 'background-color, border-color, color, fill, stroke',
  /** 透明度 */
  opacity: 'opacity',
  /** 阴影 */
  shadow: 'box-shadow',
  /** 变换 */
  transform: 'transform',
  /** 位置 */
  position: 'left, right, top, bottom',
  /** 尺寸 */
  size: 'width, height',
  /** 边距 */
  spacing: 'margin, padding'
} as const;

/** 预设过渡效果 */
export const presets = {
  /** 默认过渡 - 用于大多数交互 */
  default: `all ${duration.DEFAULT} ${easing.easeInOut}`,
  /** 快速过渡 - 用于即时反馈 */
  fast: `all ${duration.fast} ${easing.easeInOut}`,
  /** 平滑过渡 - 用于平滑动画 */
  smooth: `all ${duration.slow} ${easing.smooth}`,
  /** 颜色过渡 - 用于颜色变化 */
  colors: `${property.colors} ${duration.DEFAULT} ${easing.easeInOut}`,
  /** 透明度过渡 - 用于显示/隐藏 */
  opacity: `${property.opacity} ${duration.DEFAULT} ${easing.easeInOut}`,
  /** 阴影过渡 - 用于悬浮效果 */
  shadow: `${property.shadow} ${duration.DEFAULT} ${easing.easeInOut}`,
  /** 变换过渡 - 用于缩放/旋转 */
  transform: `${property.transform} ${duration.DEFAULT} ${easing.easeInOut}`,
  /** 弹性过渡 - 用于强调效果 */
  bounce: `all ${duration.slow} ${easing.bounce}`,
  /** 扁平化按钮过渡 */
  flatButton: `all ${duration.fast} ${easing.easeInOut}`,
  /** 扁平化卡片过渡 */
  flatCard: `all ${duration.DEFAULT} ${easing.easeInOut}`
} as const;

/** 组件过渡预设 */
export const componentTransitions = {
  /** 按钮过渡 - 扁平化 */
  button: `all ${duration.fast} ${easing.easeInOut}`,
  /** 输入框过渡 - 扁平化 */
  input: `all ${duration.fast} ${easing.easeInOut}`,
  /** 卡片过渡 - 扁平化 */
  card: `all ${duration.DEFAULT} ${easing.easeInOut}`,
  /** 弹窗过渡 */
  dialog: `all ${duration.slow} ${easing.easeInOut}`,
  /** 下拉菜单过渡 */
  dropdown: `all ${duration.DEFAULT} ${easing.easeOut}`,
  /** 标签页过渡 */
  tabs: `all ${duration.DEFAULT} ${easing.easeInOut}`,
  /** 折叠面板过渡 */
  collapse: `all ${duration.slow} ${easing.easeInOut}`,
  /** 消息提示过渡 */
  message: `all ${duration.DEFAULT} ${easing.easeOut}`,
  /** 抽屉过渡 */
  drawer: `all ${duration.slow} ${easing.easeInOut}`,
  /** 菜单过渡 - 扁平化 */
  menu: `all ${duration.fast} ${easing.easeInOut}`
} as const;

/** 过渡动画 Token 集合 */
export const transitionTokens = {
  duration,
  easing,
  property,
  presets,
  component: componentTransitions
} as const;

export type TransitionTokens = typeof transitionTokens;
