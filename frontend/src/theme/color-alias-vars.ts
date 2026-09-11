const paletteNumbers = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950] as const;

const semanticColorKeys = ['primary', 'info', 'success', 'warning', 'error'] as const;

type SemanticColorKey = (typeof semanticColorKeys)[number];

type ColorTokens = Partial<Record<string, string>>;

function getRgbTuple(color: string) {
  const hex = color.replace('#', '');
  const normalizedHex =
    hex.length === 3
      ? hex
          .split('')
          .map(value => value + value)
          .join('')
      : hex;

  const r = Number.parseInt(normalizedHex.slice(0, 2), 16);
  const g = Number.parseInt(normalizedHex.slice(2, 4), 16);
  const b = Number.parseInt(normalizedHex.slice(4, 6), 16);

  return `${r}, ${g}, ${b}`;
}

function pushPaletteVars(styles: string[], key: SemanticColorKey, colors: ColorTokens) {
  paletteNumbers.forEach(number => {
    const color = colors[`${key}-${number}`];

    if (color) {
      styles.push(`--color-${key}-${number}: ${color}`);
    }
  });
}

function pushSemanticVars(styles: string[], key: SemanticColorKey, colors: ColorTokens) {
  const color = colors[key] || colors[`${key}-500`];

  if (!color) return;

  styles.push(`--color-${key}: ${color}`);
  styles.push(`--color-${key}-rgb: ${getRgbTuple(color)}`);
  styles.push(`--shadow-${key}: 0 4px 14px 0 rgba(${getRgbTuple(color)}, 0.15)`);

  const lightColor = colors[`${key}-50`];
  const darkColor = colors[`${key}-600`];

  if (lightColor) {
    styles.push(`--color-${key}-light: ${lightColor}`);
  }

  if (darkColor) {
    styles.push(`--color-${key}-dark: ${darkColor}`);
  }
}

export function createThemeColorAliasVars(colors: ColorTokens) {
  const styles: string[] = [];

  semanticColorKeys.forEach(key => {
    pushPaletteVars(styles, key, colors);
    pushSemanticVars(styles, key, colors);
  });

  const primary = colors.primary || colors['primary-500'];
  const primaryHover = colors['primary-600'];

  if (primary) {
    styles.push(`--text-link: ${primary}`);
  }

  if (primaryHover) {
    styles.push(`--text-link-hover: ${primaryHover}`);
  }

  return styles.join(';');
}
