// @ts-nocheck
declare module 'markdown-it' {
  export interface MarkdownItRenderer {
    rules: Record<string, (...args: any[]) => string>;
  }

  export interface MarkdownItToken {
    content: string;
    info: string;
  }

  export default class MarkdownIt {
    renderer: MarkdownItRenderer;
    constructor(options?: Record<string, any>);
    use(plugin: (md: MarkdownIt, ...params: any[]) => void, ...params: any[]): this;
    render(src: string): string;
  }
}
