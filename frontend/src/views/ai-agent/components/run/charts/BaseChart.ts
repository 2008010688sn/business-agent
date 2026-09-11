/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

export interface ChartAxis {
  name: string;
  value: string;
  type?: 'x' | 'y' | 'series';
}

export interface ChartData {
  [key: string]: any;
}

export type ChartTypes = 'table' | 'bar' | 'column' | 'line' | 'pie';

// 基础颜色面板，作为扩展颜色数组的前6个色值
export const COLOR_PANEL = ['#167243', '#7b9f42', '#2f7d60', '#c7a64a', '#8f6b38', '#5ca977'];

// 扩展颜色数组，包含基础颜色面板、ECharts默认颜色和额外颜色
export const EXTENDED_COLORS = [
  // 基础颜色面板作为前6个色值
  ...COLOR_PANEL,
  // ECharts默认颜色（不包括与COLOR_PANEL重复的部分）
  '#1f6844',
  '#91cc75',
  '#fac858',
  '#ee6666',
  '#86b07a',
  '#3ba272',
  '#fc8452',
  '#6f9338',
  '#b18c42',
  // 额外补充颜色
  '#4f8b58',
  '#fdd845',
  '#22ed7c',
  '#365644',
  '#9fca9f',
  '#f9e264',
  '#f47a75',
  '#009db2',
];

// 生成随机颜色的函数
export const generateRandomColor = (): string => {
  return `#${Math.floor(Math.random() * 16777215)
    .toString(16)
    .padStart(6, '0')}`;
};

// 生成指定长度的唯一颜色数组
export const generateUniqueColors = (count: number): string[] => {
  const colors: string[] = [];
  const usedColors = new Set<string>();

  for (let i = 0; i < count; i++) {
    let color: string;

    if (i < EXTENDED_COLORS.length) {
      // 使用预定义颜色
      color = EXTENDED_COLORS[i];
      // 检查预定义颜色是否已被使用（防止EXTENDED_COLORS数组中可能存在的重复）
      if (usedColors.has(color)) {
        // 如果已被使用，生成随机颜色
        do {
          color = generateRandomColor();
        } while (usedColors.has(color));
      }
    } else {
      // 生成随机颜色并确保唯一
      do {
        color = generateRandomColor();
      } while (usedColors.has(color));
    }

    colors.push(color);
    usedColors.add(color);
  }

  return colors;
};

export abstract class BaseChart {
  id: string;
  _name: string = 'base-chart';
  axis: Array<ChartAxis> = [];
  data: Array<ChartData> = [];

  constructor(id: string, name: string) {
    this.id = id;
    this._name = name;
  }

  init(axis: Array<ChartAxis>, data: Array<ChartData>): void {
    this.axis = axis;
    this.data = data;
  }

  abstract render(): void;

  abstract destroy(): void;

  abstract resize(): void;
}
