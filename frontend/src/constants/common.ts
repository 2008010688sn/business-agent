import { transformRecordToOption } from '@/utils/common';

export const yesOrNoRecord: Record<CommonType.YesOrNo, App.I18n.I18nKey> = {
  Y: 'common.yesOrNo.yes',
  N: 'common.yesOrNo.no'
};

export const yesOrNoOptions = transformRecordToOption(yesOrNoRecord);

export const XX_COMPANY_ID = '';
export const XX_COMPANY_NAME = '';
export const XX_ONE_PROJECT_ID = '';
export const XX_ONE_PROJECT_NAME = '';
export const XX_TWO_PROJECT_ID = '';
export const XX_TWO_PROJECT_NAME = '';
export const TW_COMPANY_NAME = '';
export const TW_COMPANY_ID = '';
export const KLS_COMPANY_NAME = '';
export const WH_COMPANY_NAME = '';

export const passwordFormatOptions = [
  { value: 'LETTER_NUMBER_SPECIAL_CHARACTER', label: '大小写字母、数字、特殊字符' },
  { value: 'CAPITAL_LETTER_NUMBER_SPECIAL_CHARACTER', label: '大写字母、数字、特殊字符' },
  { value: 'LETTER_SPECIAL_CHARACTER', label: '大小写字母、特殊字符' },
  { value: 'LETTER_NUMBER', label: '大小写字母、数字' }
];

export const secondLoginTypeMap = {
  MFA_LOGIN: 110,
  ABNORMAL_LOGIN: 120
};
