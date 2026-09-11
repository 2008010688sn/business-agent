package com.sn68.agent.framework.commons;

import java.util.UUID;

/**
 * @author AFZ
 */
public class IdUtil {


    public static String uuid() {
        return UUID.randomUUID().toString();
    }

    public static String uuid32() {
        String uuidStr = UUID.randomUUID().toString();
        return uuidStr.replaceAll("-", "");
    }

    public static String nextId() {
        return cn.hutool.core.util.IdUtil.getSnowflakeNextIdStr();
    }
}
