package com.sn68.agent.framework.db.mybatisplus.handler.type;

import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * List<String> 的类型处理器
 * 用于逗号分隔的字符串与 List<String> 互转
 * 
 * 注意：不使用 @MappedTypes 自动注册，避免与其他 List TypeHandler 冲突
 * 需要在 MyBatis 配置中指定默认 TypeHandler，或在字段上显式指定
 * 
 * @author Levin
 */
public class StringToListTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, StrUtil.join(",", parameter));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        final String context = rs.getString(columnName);
        return StrUtil.split(context, ",");
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        final String context = rs.getString(columnIndex);
        return StrUtil.split(context, ",");
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        final String context = cs.getString(columnIndex);
        return StrUtil.split(context, ",");
    }
}
