package com.sn68.agent.framework.boot.log.handler;

import com.sn68.agent.framework.boot.log.AccessLogInfo;

/**
 * 日志处理基类
 *
 * @author Levin
 */
public abstract class AbstractLogHandler {

    /**
     * 执行日志打印
     *
     * @param info info
     * @return 日志结果
     */
    public abstract AccessLogInfo handler(AccessLogInfo info);

}
