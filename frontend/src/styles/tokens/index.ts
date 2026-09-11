/**
 * 设计 Token 集合
 * 统一管理项目的视觉规范
 */

export * from './colors';
export * from './spacing';
export * from './typography';
export * from './shadows';
export * from './borders';
export * from './transitions';

import { colorTokens } from './colors';
import { spacingTokens } from './spacing';
import { typographyTokens } from './typography';
import { shadowTokens } from './shadows';
import { borderTokens } from './borders';
import { transitionTokens } from './transitions';

/** 完整的设计 Token 对象 */
export const designTokens = {
  colors: colorTokens,
  spacing: spacingTokens,
  typography: typographyTokens,
  shadows: shadowTokens,
  borders: borderTokens,
  transitions: transitionTokens
} as const;

export type DesignTokens = typeof designTokens;
