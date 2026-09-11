package com.sn68.agent.framework.db.mybatisplus.encrypt.handler.type;

import cn.hutool.extra.spring.SpringUtil;
import com.sn68.agent.framework.db.mybatisplus.encrypt.utils.FieldEncryptKeyHelper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * 数据库字段加密解密处理器
 * <p>
 * 该 TypeHandler 用于在数据库存储和读取时自动对敏感字段进行加密和解密处理。
 * 支持在 MyBatis-Plus 实体类和 MyBatis XML 映射文件中使用。
 * </p>
 *
 * <h3>使用方式</h3>
 *
 * <h4>方式一：在实体类字段上使用（推荐用于 MyBatis-Plus）</h4>
 * <pre>{@code
 * @Data
 * @TableName(value = "user", autoResultMap = true)
 * @EncryptEntity // 标记实体类启用加密
 * public class User extends SuperEntity<String> {
 *
 *     @EncryptField // 标记字段需要加密
 *     @TableField(typeHandler = EncryptTypeHandler.class)  // 指定 TypeHandler
 *     private String idCard;
 *
 *     @EncryptField
 *     @TableField(typeHandler = EncryptTypeHandler.class)
 *     private String phoneNumber;
 * }
 * }</pre>
 *
 * <h4>方式二：在 MyBatis XML 映射中使用</h4>
 * <pre>{@code
 * <!-- 在 resultMap 中指定 TypeHandler -->
 * <resultMap id="UserResultMap" type="com.example.dto.UserResp">
 *     <result property="idCard" column="id_card"
 *             typeHandler="com.sn68.agent.framework.db.mybatisplus.encrypt.typehandler.EncryptTypeHandler"/>
 *     <result property="phoneNumber" column="phone_number"
 *             typeHandler="com.sn68.agent.framework.db.mybatisplus.encrypt.typehandler.EncryptTypeHandler"/>
 * </resultMap>
 *
 * <select id="selectUser" resultMap="UserResultMap">
 *     SELECT id, name, id_card, phone_number FROM user WHERE id = #{id}
 * </select>
 * }</pre>
 *
 * <h4>方式三：在查询参数中使用</h4>
 * <pre>{@code
 * <!-- 在参数映射中指定 TypeHandler -->
 * <select id="findByIdCard" resultType="User">
 *     SELECT * FROM user
 *     WHERE id_card = #{idCard,typeHandler=com.sn68.agent.framework.db.mybatisplus.encrypt.typehandler.EncryptTypeHandler}
 * </select>
 * }</pre>
 *
 *
 * <h3>注意事项</h3>
 * <ul>
 *     <li>确保 {@link FieldEncryptKeyHelper} Bean 已正确配置并注册到 Spring 容器中</li>
 *     <li>加密字段建议使用 VARCHAR 类型，长度至少为原数据长度的 2 倍</li>
 *     <li>在 XML 映射中使用时，必须通过 resultMap 指定 TypeHandler，不能使用 resultType</li>
 *     <li>该 TypeHandler 仅处理 String 类型的字段</li>
 *     <li>如果数据库中存储的是明文，首次使用时需要进行数据迁移</li>
 *     <li>使用 {@code @TableName(autoResultMap = true)} 确保 MyBatis-Plus 自动生成 ResultMap</li>
 * </ul>
 *
 * @author 钱丁君-chandler 2023/9/5 16:07
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class FieldDecryptTypeHandler extends BaseTypeHandler<String> {
    private final FieldEncryptKeyHelper fieldEncryptKeyHelper;

    public FieldDecryptTypeHandler() {
        this.fieldEncryptKeyHelper = SpringUtil.getBean("fieldEncryptKeyHelper", FieldEncryptKeyHelper.class);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return Objects.isNull(fieldEncryptKeyHelper) ? value : fieldEncryptKeyHelper.decrypt(value);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return Objects.isNull(fieldEncryptKeyHelper) ? value : fieldEncryptKeyHelper.decrypt(value);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return Objects.isNull(fieldEncryptKeyHelper) ? value : fieldEncryptKeyHelper.decrypt(value);
    }
}
