export type SkillPublicationCode =
  | 'DRAFT_ONLY'
  | 'PUBLISHED'
  | 'PUBLISHED_WITH_DRAFT'
  | 'RETIRED'
  | 'NO_VERSION'
  | 'INVALID';

export interface SkillPublicationInput {
  status?: string;
  publishedVersionId?: string;
  latestDraftVersionId?: string;
}

export interface SkillPublicationState {
  code: SkillPublicationCode;
  statusLabel: string;
  versionLabel: string;
  pendingLabel?: string;
  publishable: boolean;
  /** 「发布」按钮 tooltip：可发布时说明会发布什么，不可发布时说明具体原因和下一步 */
  publishHint: string;
}

export interface PublishedSkillVersionOption {
  id: string;
  versionNo: number;
  skillName?: string;
  executionMode?: string;
}

export interface SkillBindingVersionInput {
  bound: boolean;
  pinnedSkillVersionId?: string;
  persistedPinnedSkillVersionId?: string;
  publishedVersionId?: string;
}

export const resolveSkillPublicationState = (input: SkillPublicationInput): SkillPublicationState => {
  if (input.status === 'RETIRED') {
    return {
      code: 'RETIRED',
      statusLabel: '已退役',
      versionLabel: '已退役',
      publishable: false,
      publishHint: '已退役的 Skill 不能再发布'
    };
  }
  const hasPublished = Boolean(input.publishedVersionId);
  const hasDraft = Boolean(input.latestDraftVersionId);
  // 主档存在但一个版本都没有（例如版本数据被清理），此时既不能发布也不能绑定，需要引导用户重新存草稿
  if (!hasPublished && !hasDraft) {
    return {
      code: 'NO_VERSION',
      statusLabel: '无可用版本',
      versionLabel: '无版本',
      pendingLabel: '需先保存草稿',
      publishable: false,
      publishHint: '当前无草稿，请先「编辑」并保存草稿后再发布'
    };
  }
  if (input.status === 'DRAFT' && !hasPublished && hasDraft) {
    return {
      code: 'DRAFT_ONLY',
      statusLabel: '仅草稿',
      versionLabel: '草稿',
      publishable: true,
      publishHint: '发布当前草稿'
    };
  }
  if (input.status === 'PUBLISHED' && hasPublished && hasDraft) {
    return {
      code: 'PUBLISHED_WITH_DRAFT',
      statusLabel: '发布版可用',
      versionLabel: '发布版 + 草稿',
      pendingLabel: '有未发布修改',
      publishable: true,
      publishHint: '发布当前草稿'
    };
  }
  if (input.status === 'PUBLISHED' && hasPublished) {
    return {
      code: 'PUBLISHED',
      statusLabel: '发布版可用',
      versionLabel: '发布版',
      publishable: false,
      publishHint: '没有待发布的草稿，请先「编辑」并保存草稿'
    };
  }
  return {
    code: 'INVALID',
    statusLabel: '版本状态不一致',
    versionLabel: '状态异常',
    pendingLabel: '请刷新后重试',
    publishable: false,
    publishHint: '主档状态与版本指针不一致，请刷新列表；若仍然异常请联系管理员'
  };
};

export const publishedVersionOptionLabel = (version: PublishedSkillVersionOption, fallbackName: string) =>
  `v${version.versionNo} · ${version.skillName || fallbackName} · ${version.executionMode || '-'}`;

export const resolveSkillBindingVersionState = (input: SkillBindingVersionInput) => {
  if (!input.bound) return '';
  if (!input.pinnedSkillVersionId) return '版本未选择';
  if (!input.persistedPinnedSkillVersionId) return '待切换';
  if (input.persistedPinnedSkillVersionId && input.pinnedSkillVersionId !== input.persistedPinnedSkillVersionId) {
    return '待切换';
  }
  if (input.publishedVersionId && input.pinnedSkillVersionId !== input.publishedVersionId) {
    return '当前使用历史发布版';
  }
  return '当前生效';
};
