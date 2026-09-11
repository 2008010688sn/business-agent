/** Default theme settings - 扁平化设计 */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'light',
  themeStyle: 'minimal',
  grayscale: false,
  colourWeakness: false,
  recommendColor: false,
  themeColor: '#22c55e',
  otherColor: {
    info: '#3b82f6',
    success: '#22c55e',
    warning: '#f59e0b',
    error: '#ef4444'
  },
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close',
  layout: {
    mode: 'vertical',
    scrollMode: 'content',
    reverseHorizontalMix: false
  },
  page: {
    animate: true,
    animateMode: 'fade-slide'
  },
  header: {
    height: 64,
    breadcrumb: {
      visible: true,
      showIcon: true
    },
    multilingual: {
      visible: true
    }
  },
  tab: {
    visible: true,
    cache: true,
    height: 40,
    mode: 'chrome'
  },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 240,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200
  },
  footer: {
    visible: true,
    fixed: false,
    height: 48,
    right: true
  },
  watermark: {
    visible: true,
    text: ''
  },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 255, 255)',
        layout: 'rgb(248, 250, 252)',
        inverted: 'rgb(15, 23, 42)',
        'base-text': 'rgb(15, 23, 42)'
      },
      boxShadow: {
        header: 'none',
        sider: 'none',
        tab: 'none'
      }
    },
    dark: {
      colors: {
        container: 'rgb(30, 41, 59)',
        layout: 'rgb(15, 23, 42)',
        'base-text': 'rgb(241, 245, 249)'
      }
    }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {};
