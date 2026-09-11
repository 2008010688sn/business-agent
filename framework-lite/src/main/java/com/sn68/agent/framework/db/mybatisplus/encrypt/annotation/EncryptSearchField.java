package com.sn68.agent.framework.db.mybatisplus.encrypt.annotation;

import com.sn68.agent.framework.db.mybatisplus.encrypt.utils.FieldEncryptKeyHelper;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 加密字段搜索支持注解
 * <p>
 * 用于标记查询条件（DTO/Request）中的字段，支持对加密字段进行模糊搜索和精确匹配。
 * 该注解主要解决加密字段无法直接进行数据库查询的问题，通过预处理查询条件来实现搜索功能。
 * </p>
 *
 * <h3>注解参数说明</h3>
 * <ul>
 *     <li><strong>relatedField</strong>：关联的实体字段名，默认为空字符串。指定与当前搜索字段对应的加密字段名称</li>
 *     <li><strong>prefixLength</strong>：前缀匹配长度，默认为 3。用于模糊搜索时指定匹配前几位字符</li>
 *     <li><strong>suffixLength</strong>：后缀匹配长度，默认为 4。用于模糊搜索时指定匹配后几位字符</li>
 * </ul>
 *
 * <h3>使用场景</h3>
 * <p>主要用于以下业务场景：</p>
 * <ul>
 *     <li>根据身份证号码搜索用户（支持部分匹配）</li>
 *     <li>根据手机号码搜索客户（支持前缀或后缀匹配）</li>
 *     <li>根据银行卡号搜索账户信息</li>
 *     <li>其他加密敏感信息的搜索需求</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 *
 * <h4>基本用法</h4>
 * <pre>{@code
 * @Data
 * @EncryptEntity
 * public class Employee extends SuperEntity<String> {
 *     @EncryptField
 *     @TableField(typeHandler = FieldDecryptTypeHandler.class)
 *     private String idCard;
 *
 *     @EncryptField
 *     @TableField(typeHandler = FieldDecryptTypeHandler.class)
 *     private String phoneNumber;
 *
 *     @EncryptSearchField(relatedField = "idCard")
 *     private String idCardSearch;  // 身份证号搜索条件
 *     
 *     @EncryptSearchField(relatedField = "phoneNumber")
 *     private String phoneSearch;   // 手机号搜索条件
 * }
 * }</pre>
 *
 * <h3>注意事项</h3>
 * <ul>
 *     <li>必须与 {@link EncryptEntity} 和 {@link EncryptField} 配合使用</li>
 *     <li>{@code relatedField} 必须对应实体类中真实存在的加密字段</li>
 *     <li>模糊搜索的性能相对较低，建议合理设置匹配长度</li>
 *     <li>前缀和后缀长度不能超过原始数据的实际长度</li>
 * </ul>
 *
 * <h3>最佳实践</h3>
 * <ul>
 *     <li>为不同类型的敏感数据设计合适的搜索策略</li>
 *     <li>身份证号建议使用前4后4的匹配模式</li>
 *     <li>手机号建议使用前3后4的匹配模式</li>
 *     <li>银行卡号建议使用后4位匹配模式</li>
 * </ul>
 *
 * @author 钱丁君-chandler
 * @since 2025/12/30
 * @see EncryptEntity
 * @see EncryptField
 * @see FieldEncryptKeyHelper
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptSearchField {
    /**
     * 关联需要加密的字段名，自动填充搜索字段的数据
     * @return 字段名
     */
    String relatedField() default "";
    int prefixLength() default 3;
    int suffixLength() default 4;
}
