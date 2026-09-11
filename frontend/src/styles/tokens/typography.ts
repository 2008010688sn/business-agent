/**
 * 字体设计 Token
 * 用于统一项目中的字体使用
 */

/** 字体族 */
export const fontFamily = {
  /** 主字体 */
  sans: '"Alibaba PuHuiTi 3.0", "AlibabaPuHuiTi-3", "Alibaba PuHuiTi", "阿里巴巴普惠体 3.0", "阿里巴巴普惠体", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji"',
  /** 等宽字体 */
  mono: '"SFMono-Regular", Consolas, "Liberation Mono", Menlo, Courier, monospace',
  /** 中文优化字体 */
  chinese: '"Alibaba PuHuiTi 3.0", "AlibabaPuHuiTi-3", "Alibaba PuHuiTi", "阿里巴巴普惠体 3.0", "阿里巴巴普惠体", "PingFang SC", "Microsoft YaHei", "Hiragino Sans GB", "WenQuanYi Micro Hei", sans-serif'
} as const;

/** 字体大小 */
export const fontSize = {
  /** 12px - 辅助文本 */
  xs: '12px',
  /** 13px - 小号文本 */
  sm: '13px',
  /** 14px - 基础文本 */
  base: '14px',
  /** 15px - 中号文本 */
  md: '15px',
  /** 16px - 大号文本 */
  lg: '16px',
  /** 18px - 小标题 */
  xl: '18px',
  /** 20px - 标题 */
  '2xl': '20px',
  /** 24px - 大标题 */
  '3xl': '24px',
  /** 30px - 页面标题 */
  '4xl': '30px',
  /** 36px - 超大标题 */
  '5xl': '36px'
} as const;

/** 行高 */
export const lineHeight = {
  /** 1 - 紧凑 */
  tight: '1',
  /** 1.25 - 紧凑标题 */
  snug: '1.25',
  /** 1.375 - 标题 */
  normal: '1.375',
  /** 1.5 - 正文 */
  relaxed: '1.5',
  /** 1.625 - 宽松 */
  loose: '1.625',
  /** 2 - 双倍 */
  double: '2'
} as const;

/** 字体粗细 */
export const fontWeight = {
  /** 400 - 正常 */
  normal: '400',
  /** 500 - 中等 */
  medium: '500',
  /** 600 - 半粗 */
  semibold: '600',
  /** 700 - 粗体 */
  bold: '700'
} as const;

/** 字体样式预设 */
export const typography = {
  /** 页面标题 */
  h1: {
    fontSize: fontSize['4xl'],
    fontWeight: fontWeight.bold,
    lineHeight: lineHeight.tight,
    letterSpacing: '-0.02em'
  },
  /** 区域标题 */
  h2: {
    fontSize: fontSize['3xl'],
    fontWeight: fontWeight.bold,
    lineHeight: lineHeight.tight,
    letterSpacing: '-0.01em'
  },
  /** 卡片标题 */
  h3: {
    fontSize: fontSize['2xl'],
    fontWeight: fontWeight.semibold,
    lineHeight: lineHeight.snug
  },
  /** 小标题 */
  h4: {
    fontSize: fontSize.xl,
    fontWeight: fontWeight.semibold,
    lineHeight: lineHeight.snug
  },
  /** 正文大号 */
  bodyLarge: {
    fontSize: fontSize.lg,
    fontWeight: fontWeight.normal,
    lineHeight: lineHeight.relaxed
  },
  /** 正文 */
  body: {
    fontSize: fontSize.base,
    fontWeight: fontWeight.normal,
    lineHeight: lineHeight.relaxed
  },
  /** 正文小号 */
  bodySmall: {
    fontSize: fontSize.sm,
    fontWeight: fontWeight.normal,
    lineHeight: lineHeight.relaxed
  },
  /** 辅助文本 */
  caption: {
    fontSize: fontSize.xs,
    fontWeight: fontWeight.normal,
    lineHeight: lineHeight.relaxed
  },
  /** 按钮文本 */
  button: {
    fontSize: fontSize.base,
    fontWeight: fontWeight.medium,
    lineHeight: lineHeight.tight
  },
  /** 标签文本 */
  label: {
    fontSize: fontSize.sm,
    fontWeight: fontWeight.medium,
    lineHeight: lineHeight.normal
  }
} as const;

/** 字体 Token 集合 */
export const typographyTokens = {
  fontFamily,
  fontSize,
  lineHeight,
  fontWeight,
  presets: typography
} as const;

export type TypographyTokens = typeof typographyTokens;
