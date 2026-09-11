package com.sn68.agent.framework.db.mybatisplus.encrypt.exception;

/**
 *
 * @author 钱丁君-chandler 2025/12/15
 */
public class FieldEncryptException extends RuntimeException{
    public FieldEncryptException(String message, Throwable cause) {
        super(message, cause);
    }

    public FieldEncryptException(String message) {
        super(message);
    }
}
