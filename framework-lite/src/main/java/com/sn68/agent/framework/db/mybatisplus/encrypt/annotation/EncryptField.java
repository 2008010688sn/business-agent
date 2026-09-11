package com.sn68.agent.framework.db.mybatisplus.encrypt.annotation;



import com.sn68.agent.framework.db.mybatisplus.encrypt.utils.FieldEncryptKeyHelper;
import com.sn68.agent.framework.db.mybatisplus.encrypt.handler.type.FieldDecryptTypeHandler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段加密标记注解
 * <p>
 * 用于标记实体类中需要进行加密存储的字段。必须配合 {@link EncryptEntity} 注解使用。
 * 支持多种加密类型和掩码显示功能，适用于各种敏感信息的安全存储。
 * </p>
 *
 * <h4>基本用法</h4>
 * <pre>{@code
 * @Data
 * @TableName(value = "user", autoResultMap = true)
 * @EncryptEntity
 * public class User extends SuperEntity<String> {
 *     @EncryptField
 *     @TableField(typeHandler = FieldDecryptTypeHandler.class)
 *     private String idCard;
 *
 * }
 * }</pre>
 *
 * <h3>注意事项</h3>
 * <ul>
 *     <li>必须配合 {@link EncryptEntity} 注解使用</li>
 *     <li>确保数据库字段长度足够存储加密后的数据（建议至少为原长度的2倍）</li>
 *     <li>修改加密配置后，历史数据需要进行数据迁移</li>
 * </ul>
 *
 * @author 钱丁君-chandler
 * @since 2025/12/15
 * @see EncryptEntity
 * @see FieldDecryptTypeHandler
 * @see FieldEncryptKeyHelper
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptField {
}
