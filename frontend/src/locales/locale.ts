import zhCN from './langs/zh-cn';
import enUS from './langs/en-us';
import jaJP from './langs/ja-jp';

const locales: Record<App.I18n.LangType, App.I18n.Schema> = {
  'zh-CN': zhCN as App.I18n.Schema,
  'en-US': enUS as App.I18n.Schema,
  'ja-JP': jaJP as App.I18n.Schema
};

export default locales;
