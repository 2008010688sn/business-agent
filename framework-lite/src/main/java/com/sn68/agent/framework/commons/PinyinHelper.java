package com.sn68.agent.framework.commons;

import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.pinyin.PinyinUtil;

/**
 * @author Levin
 */
public class PinyinHelper extends PinyinUtil {

    /**
     * 中文首字母
     *
     * @param str str
     * @return 转换成大写返回中文首字母
     */
    public static String firstUpperCase(String str) {
        var letter = getFirstLetter(str, "");
        if (StrUtil.isBlank(letter)) {
            return null;
        }
        return letter.toUpperCase();
    }


}
