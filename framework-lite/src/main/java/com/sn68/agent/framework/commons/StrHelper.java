package com.sn68.agent.framework.commons;


import cn.hutool.core.util.StrUtil;

/**
 * 字符串帮助类
 *
 * @author Levin
 */
public class StrHelper extends StrUtil {


    public static String formatMac(String str) {
        if (StrUtil.isBlank(str)) {
            return str;
        }
        // 移除所有冒号并去空格
        str = str.trim().replace(":", "");
        if (str.length() < 12) {
            // 不足12位补0
            str = StrUtil.padPre(str, 12, '0');
        }

        // 用 Hutool 的字符串分割方式格式化 MAC 地址
        return StrUtil.format(
                "{}:{}:{}:{}:{}:{}",
                str.substring(0, 2),
                str.substring(2, 4),
                str.substring(4, 6),
                str.substring(6, 8),
                str.substring(8, 10),
                str.substring(10, 12)
        );
    }


}
