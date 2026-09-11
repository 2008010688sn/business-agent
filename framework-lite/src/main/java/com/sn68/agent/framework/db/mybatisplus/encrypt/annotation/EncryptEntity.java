package com.sn68.agent.framework.db.mybatisplus.encrypt.annotation;

import com.sn68.agent.framework.db.mybatisplus.encrypt.utils.FieldEncryptKeyHelper;
import com.sn68.agent.framework.db.mybatisplus.encrypt.handler.type.FieldDecryptTypeHandler;

import java.lang.annotation.*;

/**
 * 实体类加密标记注解
 * <p>
 * 用于标记实体类启用字段加密功能。当实体类被此注解标记后，
 * 该类中使用 {@link EncryptField} 注解的字段将自动进行加密解密处理。
 * </p>
 *
 * <h4>基本用法</h4>
 * <pre>{@code
 * @Data
 * @TableName(value = "user", autoResultMap = true)
 * @EncryptEntity  // 标记实体类启用加密
 * public class User extends SuperEntity<String> {
 *     
 *     @EncryptField  // 标记字段需要加密
 *     @TableField(typeHandler = FieldDecryptTypeHandler.class)
 *     private String idCard;
 *
 *     // 普通字段不会被加密
 *     private String name;
 * }
 * }</pre>
 *
 * <h3>注意事项</h3>
 * <ul>
 *     <li>必须配合 {@link EncryptField} 注解使用，单独使用无效</li>
 *     <li>仅对标记了 {@link EncryptField} 的字段进行加密，其他字段保持原样</li>
 *     <li>确保加密字段对应的数据库列有足够的长度存储加密后的数据，推荐改成128位</li>
 * </ul>
 *
 * @author 钱丁君-chandler
 * @since 2025/12/16
 * @see EncryptField
 * @see FieldDecryptTypeHandler
 * @see FieldEncryptKeyHelper
 */
@Documented
@Inherited
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptEntity {
}
