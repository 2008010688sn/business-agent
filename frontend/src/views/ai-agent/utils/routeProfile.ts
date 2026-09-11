import type {
  RouteProfile,
  RouteProfileBuildStatus,
  RouteProfileConstraints,
  RouteProfileDefaults,
  RouteProfileInput
} from '@/views/ai-agent/services/routing';

const EDITABLE_STATUSES = new Set(['DRAFT', 'READY', 'FAILED']);

const hasSemanticRecall = (profile: RouteProfile): boolean =>
  profile.semanticRecallEnabled ?? profile.semanticAutoSelectEnabled;

export const createRouteProfileInput = (defaults: RouteProfileDefaults, profileName: string): RouteProfileInput => {
  const semanticAutoSelectEnabled = Boolean(defaults.semanticAutoSelectEnabled);
  const configuredRecall = defaults.semanticRecallEnabled;
  const semanticRecallEnabled =
    (configuredRecall === undefined ? true : Boolean(configuredRecall)) || semanticAutoSelectEnabled;
  return {
    ...defaults,
    profileName,
    routeModelConfigId: '',
    embeddingModelConfigId: '',
    semanticRecallEnabled,
    semanticAutoSelectEnabled
  };
};

export const normalizeRouteProfileInput = (profile: RouteProfile): RouteProfileInput => {
  const semanticAutoSelectEnabled = Boolean(profile.semanticAutoSelectEnabled);
  const configuredRecall = profile.semanticRecallEnabled;
  const semanticRecallEnabled =
    (configuredRecall === undefined ? semanticAutoSelectEnabled : Boolean(configuredRecall)) ||
    semanticAutoSelectEnabled;
  return {
    profileName: profile.profileName,
    routeModelConfigId: profile.routeModelConfigId ?? '',
    embeddingModelConfigId: profile.embeddingModelConfigId ?? '',
    lexicalAutoSelectEnabled: profile.lexicalAutoSelectEnabled,
    semanticRecallEnabled,
    semanticAutoSelectEnabled,
    modelDisambiguationEnabled: profile.modelDisambiguationEnabled,
    lexicalMinScore: profile.lexicalMinScore,
    lexicalMinGap: profile.lexicalMinGap,
    vectorRecallThreshold: profile.vectorRecallThreshold,
    vectorAutoSelectThreshold: profile.vectorAutoSelectThreshold,
    vectorMinGap: profile.vectorMinGap,
    modelConfidenceThreshold: profile.modelConfidenceThreshold
  };
};

export const routeProfileValidationMessage = (
  input: RouteProfileInput,
  constraints: RouteProfileConstraints
): string | null => {
  if (!input.profileName.trim() || input.profileName.trim().length > constraints.profileNameMaxLength) {
    return '请填写 Profile 名称并选择已启用能力所需的模型';
  }
  if (input.semanticAutoSelectEnabled && !input.semanticRecallEnabled) {
    return '语义自动选择开启时必须同时开启语义召回';
  }
  if (
    (constraints.routeModelRequiredWhenDisambiguationEnabled &&
      input.modelDisambiguationEnabled &&
      !input.routeModelConfigId) ||
    (constraints.embeddingModelRequiredWhenSemanticEnabled &&
      input.semanticRecallEnabled &&
      !input.embeddingModelConfigId)
  ) {
    return '请填写 Profile 名称并选择已启用能力所需的模型';
  }
  if (
    constraints.vectorRecallThresholdMustNotExceedAutoSelectThreshold &&
    input.vectorRecallThreshold > input.vectorAutoSelectThreshold
  ) {
    return '向量召回阈值不能高于自动选择阈值';
  }
  return null;
};

export const isRouteProfileEditable = (profile?: RouteProfile | null): boolean =>
  !profile || EDITABLE_STATUSES.has(profile.status);

export const canProbeRouteProfile = (profile?: RouteProfile | null): boolean =>
  Boolean(profile?.id) && isRouteProfileEditable(profile);

export const canRebuildRouteProfile = (profile?: RouteProfile | null): boolean =>
  canProbeRouteProfile(profile) &&
  Boolean(profile && hasSemanticRecall(profile)) &&
  profile?.embeddingCapability?.state === 'SUPPORTED' &&
  profile.buildStatus !== 'RUNNING';

export const canActivateRouteProfile = (
  profile?: RouteProfile | null,
  buildStatus?: RouteProfileBuildStatus | null
): boolean => {
  if (!profile || profile.status !== 'READY') {
    return false;
  }
  const status = buildStatus?.buildStatus ?? profile.buildStatus;
  if (!hasSemanticRecall(profile)) {
    return status === 'SKIPPED' || status === 'READY';
  }
  if (profile.embeddingCapability?.state !== 'SUPPORTED') return false;
  const total = buildStatus?.total ?? profile.buildTotal ?? 0;
  const ready = buildStatus?.ready ?? profile.buildReady ?? 0;
  const failed = buildStatus?.failed ?? profile.buildFailed ?? 0;
  return status === 'READY' && ready === total && failed === 0;
};
