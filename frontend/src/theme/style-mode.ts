export const themeStyleKeys = ['classic', 'minimal', 'enterprise'] as const;

export type ThemeStyleKey = (typeof themeStyleKeys)[number];

const themeStyleClassPrefix = 'theme-style';

export const themeStyleClassNames = themeStyleKeys.map(key => getThemeStyleClassName(key));

export function getThemeStyleClassName(style: ThemeStyleKey) {
  return `${themeStyleClassPrefix}-${style}`;
}

type ThemeStylePreset = Pick<App.Theme.ThemeSetting, 'themeColor' | 'tokens'> & {
  header: Pick<App.Theme.ThemeSetting['header'], 'height'>;
  tab: Pick<App.Theme.ThemeSetting['tab'], 'height'>;
  sider: Pick<App.Theme.ThemeSetting['sider'], 'width'>;
};

const themeStylePresets: Record<ThemeStyleKey, ThemeStylePreset> = {
  classic: {
    themeColor: '#00AE42',
    header: {
      height: 56
    },
    tab: {
      height: 44
    },
    sider: {
      width: 220
    },
    tokens: {
      light: {
        colors: {
          container: 'rgb(255, 255, 255)',
          layout: 'rgb(247, 250, 252)',
          inverted: 'rgb(0, 20, 40)',
          'base-text': 'rgb(31, 31, 31)'
        },
        boxShadow: {
          header: '0 1px 2px rgb(0, 21, 41, 0.08)',
          sider: '2px 0 8px 0 rgb(29, 35, 41, 0.05)',
          tab: '0 1px 2px rgb(0, 21, 41, 0.08)'
        }
      },
      dark: {
        colors: {
          container: 'rgb(28, 28, 28)',
          layout: 'rgb(18, 18, 18)',
          'base-text': 'rgb(224, 224, 224)'
        }
      }
    }
  },
  minimal: {
    themeColor: '#22c55e',
    header: {
      height: 48
    },
    tab: {
      height: 40
    },
    sider: {
      width: 240
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
  },
  enterprise: {
    themeColor: '#1890ff',
    header: {
      height: 48
    },
    tab: {
      height: 42
    },
    sider: {
      width: 240
    },
    tokens: {
      light: {
        colors: {
          container: 'rgb(255, 255, 255)',
          layout: 'rgb(240, 242, 245)',
          inverted: 'rgb(0, 21, 41)',
          'base-text': 'rgb(26, 26, 26)'
        },
        boxShadow: {
          header: '0 1px 4px rgb(0, 21, 41, 0.08)',
          sider: '0 2px 8px rgb(0, 21, 41, 0.06)',
          tab: 'none'
        }
      },
      dark: {
        colors: {
          container: 'rgb(28, 30, 38)',
          layout: 'rgb(18, 20, 26)',
          'base-text': 'rgb(235, 235, 245)'
        }
      }
    }
  }
};

function cloneThemeTokens(tokens: App.Theme.ThemeSetting['tokens']): App.Theme.ThemeSetting['tokens'] {
  return {
    light: {
      colors: {
        ...tokens.light.colors
      },
      boxShadow: {
        ...tokens.light.boxShadow
      }
    },
    dark: tokens.dark
      ? {
          colors: tokens.dark.colors ? { ...tokens.dark.colors } : undefined,
          boxShadow: tokens.dark.boxShadow ? { ...tokens.dark.boxShadow } : undefined
        }
      : undefined
  };
}

function normalizeThemeColor(themeColor: string) {
  return themeColor.toLowerCase();
}

export function isThemeStyleDefaultColor(themeColor: string) {
  const normalizedColor = themeColor.toLowerCase();

  return Object.values(themeStylePresets).some(preset => preset.themeColor.toLowerCase() === normalizedColor);
}

export function isOtherThemeStyleDefaultColor(themeColor: string, style: ThemeStyleKey) {
  const normalizedColor = normalizeThemeColor(themeColor);
  const currentStyleDefaultColor = normalizeThemeColor(themeStylePresets[style].themeColor);

  return normalizedColor !== currentStyleDefaultColor && isThemeStyleDefaultColor(themeColor);
}

export function getThemeStylePreset(style: ThemeStyleKey): ThemeStylePreset {
  const preset = themeStylePresets[style];

  return {
    themeColor: preset.themeColor,
    header: {
      ...preset.header
    },
    tab: {
      ...preset.tab
    },
    sider: {
      ...preset.sider
    },
    tokens: cloneThemeTokens(preset.tokens)
  };
}

export function applyThemeStylePreset(settings: App.Theme.ThemeSetting, style: ThemeStyleKey) {
  const preset = getThemeStylePreset(style);
  const shouldUseStyleDefaultThemeColor = isThemeStyleDefaultColor(settings.themeColor);

  settings.themeStyle = style;
  if (shouldUseStyleDefaultThemeColor) {
    settings.themeColor = preset.themeColor;
  }
  settings.header.height = preset.header.height;
  settings.tab.height = preset.tab.height;
  settings.sider.width = preset.sider.width;
  settings.tokens = preset.tokens;
}
