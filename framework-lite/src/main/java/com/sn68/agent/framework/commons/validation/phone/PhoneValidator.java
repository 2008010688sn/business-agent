package com.sn68.agent.framework.commons.validation.phone;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 *
 * @author 钱丁君-chandler 2026/2/28
 */
public class PhoneValidator implements ConstraintValidator<Phone, Object> {
    private static final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) return true;

        String phoneStr = value.toString();
        try {
            // 如果号码自带 + 前缀，libphonenumber 会自动识别国家
            Phonenumber.PhoneNumber number = phoneUtil.parse(phoneStr.startsWith("+") ? phoneStr : "+86" + phoneStr, null);
            return phoneUtil.isValidNumber(number);
        } catch (NumberParseException e) {
            return false;
        }
    }

}
