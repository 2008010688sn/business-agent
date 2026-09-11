export const ROUTE_RULE_FIELDS = [
  'exact',
  'phrases',
  'aliases',
  'positiveExamples',
  'positivePatterns',
  'negativeExamples',
  'hardExcludes',
  'hardExcludePatterns'
] as const;

export const ROUTE_RULE_BOOLEAN_FIELD = 'allowFlowAutoSelect' as const;

export const ROUTE_RULE_RESPONSE_FIELDS = [...ROUTE_RULE_FIELDS, ROUTE_RULE_BOOLEAN_FIELD] as const;

export const LITERAL_ROUTE_RULE_FIELDS = [
  'exact',
  'phrases',
  'aliases',
  'positiveExamples',
  'negativeExamples',
  'hardExcludes'
] as const;

export const PATTERN_ROUTE_RULE_FIELDS = ['positivePatterns', 'hardExcludePatterns'] as const;

export type RouteRuleField = (typeof ROUTE_RULE_FIELDS)[number];

export type LiteralRouteRuleField = (typeof LITERAL_ROUTE_RULE_FIELDS)[number];

export type PatternRouteRuleField = (typeof PATTERN_ROUTE_RULE_FIELDS)[number];

export interface RouteRules {
  exact: string[];
  phrases: string[];
  aliases: string[];
  positiveExamples: string[];
  positivePatterns: string[];
  negativeExamples: string[];
  hardExcludes: string[];
  hardExcludePatterns: string[];
  allowFlowAutoSelect: boolean;
}

export type RouteRulesPayload = RouteRules | Record<string, unknown>;

export interface ValidRouteRulesParseResult {
  status: 'valid';
  valid: true;
  raw: unknown;
  rules: RouteRules;
  errors: [];
  warnings: string[];
}

export interface InvalidRouteRulesParseResult {
  status: 'invalid';
  valid: false;
  raw: unknown;
  rules: RouteRules;
  errors: string[];
  warnings: string[];
}

export type RouteRulesParseResult = ValidRouteRulesParseResult | InvalidRouteRulesParseResult;

export type RouteRulesValidationResult = RouteRulesParseResult;

const MAX_ITEMS_PER_FIELD = 100;
const MAX_ITEM_LENGTH = 200;
const MAX_SERIALIZED_BYTES = 32 * 1024;
const POSITIVE_FIELDS: RouteRuleField[] = [
  'exact',
  'phrases',
  'aliases',
  'positiveExamples',
  'positivePatterns'
];
const SEMANTIC_SEPARATORS = new Set(['.', ':', '/']);
const LETTER_OR_DIGIT = /[\p{L}\p{N}]/u;
const DIGIT = /\p{N}/u;
const patternRouteRuleFieldSet = new Set<RouteRuleField>(PATTERN_ROUTE_RULE_FIELDS);

export const emptyRouteRules = (): RouteRules => ({
  exact: [],
  phrases: [],
  aliases: [],
  positiveExamples: [],
  positivePatterns: [],
  negativeExamples: [],
  hardExcludes: [],
  hardExcludePatterns: [],
  allowFlowAutoSelect: false
});

export const cloneRouteRules = (rules: RouteRules): RouteRules => ({
  exact: [...rules.exact],
  phrases: [...rules.phrases],
  aliases: [...rules.aliases],
  positiveExamples: [...rules.positiveExamples],
  positivePatterns: [...rules.positivePatterns],
  negativeExamples: [...rules.negativeExamples],
  hardExcludes: [...rules.hardExcludes],
  hardExcludePatterns: [...rules.hardExcludePatterns],
  allowFlowAutoSelect: rules.allowFlowAutoSelect
});

export const isPatternRouteRuleField = (field: RouteRuleField): field is PatternRouteRuleField =>
  patternRouteRuleFieldSet.has(field);

export const normalizeRouteText = (value: unknown): string => {
  if (typeof value !== 'string' || !value.trim()) {
    return '';
  }
  const chars = Array.from(value.normalize('NFKC').toLowerCase());
  const normalized = chars.map((char, index) => {
    if (LETTER_OR_DIGIT.test(char) || char === '-' || char === '_') {
      return char;
    }
    if (
      SEMANTIC_SEPARATORS.has(char) &&
      DIGIT.test(chars[index - 1] || '') &&
      DIGIT.test(chars[index + 1] || '')
    ) {
      return char;
    }
    return ' ';
  });
  return normalized.join('').replace(/\s+/g, ' ').trim();
};

export const normalizeRoutePattern = (value: unknown): string => {
  if (typeof value !== 'string' || !value.trim()) {
    return '';
  }
  return value
    .normalize('NFKC')
    .toLowerCase()
    .split('*')
    .map(segment => normalizeRouteText(segment))
    .join('*');
};

export const validateRoutePattern = (value: string): string | undefined => {
  const wildcardCount = Array.from(value).filter(char => char === '*').length;
  if (wildcardCount < 1 || wildcardCount > 3) {
    return '通配规则必须包含 1 至 3 个 *';
  }
  if (value.trim() === '*' || value.includes('**')) {
    return '通配规则不能为单独的 *，也不能包含 **';
  }
  return undefined;
};

const normalizeField = (value: unknown, field: RouteRuleField, errors: string[]): string[] => {
  if (value === undefined) {
    return [];
  }
  if (!Array.isArray(value)) {
    errors.push(`${field} 必须是字符串数组`);
    return [];
  }

  const normalized = new Set<string>();
  for (const item of value) {
    if (typeof item !== 'string') {
      errors.push(`${field} 只能包含字符串`);
      continue;
    }
    const text = isPatternRouteRuleField(field) ? normalizeRoutePattern(item) : normalizeRouteText(item);
    if (!text) {
      continue;
    }
    if (text.length > MAX_ITEM_LENGTH) {
      errors.push(`${field} 的单条内容不能超过 ${MAX_ITEM_LENGTH} 字`);
      continue;
    }
    if (isPatternRouteRuleField(field)) {
      const patternError = validateRoutePattern(text);
      if (patternError) {
        errors.push(`${field} ${patternError}`);
        continue;
      }
    }
    normalized.add(text);
  }
  if (normalized.size > MAX_ITEMS_PER_FIELD) {
    errors.push(`${field} 不能超过 ${MAX_ITEMS_PER_FIELD} 条`);
  }
  return Array.from(normalized).slice(0, MAX_ITEMS_PER_FIELD);
};

const isRouteRulesRecord = (source: unknown): source is Record<string, unknown> =>
  typeof source === 'object' && source !== null && !Array.isArray(source);

const invalidResult = (
  raw: unknown,
  rules: RouteRules,
  errors: string[],
  warnings: string[]
): InvalidRouteRulesParseResult => ({
  status: 'invalid',
  valid: false,
  raw,
  rules,
  errors,
  warnings
});

const validResult = (raw: unknown, rules: RouteRules, warnings: string[]): ValidRouteRulesParseResult => ({
  status: 'valid',
  valid: true,
  raw,
  rules,
  errors: [],
  warnings
});

export const parseRouteRules = (source: unknown): RouteRulesParseResult => {
  if (source === undefined) {
    return validResult(source, emptyRouteRules(), []);
  }
  if (!isRouteRulesRecord(source)) {
    return invalidResult(source, emptyRouteRules(), ['路由规则必须是对象'], []);
  }

  const errors: string[] = [];
  const warnings: string[] = [];
  const unknownFields = Object.keys(source)
    .filter(key => !ROUTE_RULE_RESPONSE_FIELDS.includes(key as (typeof ROUTE_RULE_RESPONSE_FIELDS)[number]))
    .sort();
  if (unknownFields.length > 0) {
    errors.push(`路由规则包含未知字段: ${unknownFields.join(', ')}`);
  }

  const rules = ROUTE_RULE_FIELDS.reduce<RouteRules>((result, field) => {
    result[field] = normalizeField(source[field], field, errors);
    return result;
  }, emptyRouteRules());
  const autoSelect = source[ROUTE_RULE_BOOLEAN_FIELD];
  if (autoSelect === undefined) {
    rules.allowFlowAutoSelect = false;
  } else if (typeof autoSelect === 'boolean') {
    rules.allowFlowAutoSelect = autoSelect;
  } else {
    errors.push(`${ROUTE_RULE_BOOLEAN_FIELD} 必须是布尔值`);
  }

  const hardExclusions = new Set(rules.hardExcludes);
  for (const field of POSITIVE_FIELDS) {
    for (const value of rules[field]) {
      if (hardExclusions.has(value)) {
        errors.push(`${field} 与硬排除冲突: ${value}`);
      }
    }
  }

  const positiveValues = new Set(POSITIVE_FIELDS.flatMap(field => rules[field]));
  const softConflicts = rules.negativeExamples.filter(value => positiveValues.has(value));
  if (softConflicts.length > 0) {
    warnings.push(`软反例与正向信号重复: ${softConflicts.join(', ')}`);
  }

  if (new TextEncoder().encode(JSON.stringify(rules)).byteLength > MAX_SERIALIZED_BYTES) {
    errors.push('路由规则总大小不能超过 32KB');
  }
  return errors.length ? invalidResult(source, rules, errors, warnings) : validResult(source, rules, warnings);
};

export const validateRouteRules = parseRouteRules;

export const normalizeRouteRules = (source: unknown): RouteRules => {
  const result = parseRouteRules(source);
  if (result.status === 'invalid') {
    throw new Error(result.errors[0]);
  }
  return result.rules;
};

export const routeRulesPayloadForSave = (
  result: RouteRulesParseResult,
  dirty: boolean
): RouteRulesPayload => {
  if (result.status === 'invalid') {
    throw new Error(result.errors[0]);
  }
  if (dirty) {
    return cloneRouteRules(result.rules);
  }
  if (isRouteRulesRecord(result.raw)) {
    return result.raw;
  }
  return LITERAL_ROUTE_RULE_FIELDS.reduce<Record<string, unknown>>((payload, field) => {
    payload[field] = [...result.rules[field]];
    return payload;
  }, {});
};
