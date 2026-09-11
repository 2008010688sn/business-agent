const urlLangMap: Record<string, App.I18n.LangType> = {
  'zh-cn': 'zh-CN',
  'zh_cn': 'zh-CN',
  zh: 'zh-CN',
  cn: 'zh-CN',
  'en-us': 'en-US',
  en_us: 'en-US',
  en: 'en-US',
  'ja-jp': 'ja-JP',
  ja_jp: 'ja-JP',
  ja: 'ja-JP',
  jp: 'ja-JP'
};

type UrlLangValue = string | string[] | null | undefined;

export function normalizeUrlLang(lang: UrlLangValue): App.I18n.LangType | null {
  const rawLang = Array.isArray(lang) ? lang[0] : lang;

  if (!rawLang) return null;

  return urlLangMap[rawLang.trim().toLowerCase()] ?? null;
}

export function getUrlLang(search = window.location.search): App.I18n.LangType | null {
  return normalizeUrlLang(new URLSearchParams(search).get('lang'));
}

export function getInitialLocale(storedLang?: App.I18n.LangType | null, search = window.location.search) {
  return getUrlLang(search) || storedLang || 'zh-CN';
}
