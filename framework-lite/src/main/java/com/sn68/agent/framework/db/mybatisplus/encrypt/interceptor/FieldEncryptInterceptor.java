package com.sn68.agent.framework.db.mybatisplus.encrypt.interceptor;

import cn.hutool.core.annotation.AnnotationUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.google.common.collect.Maps;
import com.sn68.agent.framework.db.mybatisplus.encrypt.utils.FieldEncryptKeyHelper;
import com.sn68.agent.framework.db.mybatisplus.encrypt.annotation.EncryptEntity;
import com.sn68.agent.framework.db.mybatisplus.encrypt.annotation.EncryptField;
import com.sn68.agent.framework.db.mybatisplus.encrypt.annotation.EncryptSearchField;
import com.sn68.agent.framework.db.mybatisplus.encrypt.exception.FieldEncryptException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 数据加密拦截器
 *
 * @author 钱丁君-chandler 2026/1/12
 */
@Slf4j
@RequiredArgsConstructor
public class FieldEncryptInterceptor implements InnerInterceptor {
    private final FieldEncryptKeyHelper fieldEncryptKeyHelper;

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        if (ObjectUtil.isNull(parameter)) {
            return;
        }
        if (parameter instanceof Map) {
            ((Map<?, ?>) parameter).values().forEach(this::encryptObj);
        } else {
            encryptObj(parameter);
        }

    }

    private void encryptObj(Object parameter) throws FieldEncryptException {
        if (parameter instanceof Collection) {
            ((Collection<?>) parameter).forEach(this::encryptSingle);
        } else {
            encryptSingle(parameter);
        }
    }

    private void encryptSingle(Object parameter) throws FieldEncryptException {
        if (ObjectUtil.isNull(parameter)) {
            return;
        }
        if (AnnotationUtil.hasAnnotation(parameter.getClass(), EncryptEntity.class)) {
            //如果存在就加密
            encrypt(parameter);
        }
    }

    private void encrypt(Object parameterObject) throws FieldEncryptException {
        try {
            // 1. 获取所有需要加密的字段
            Field[] secretFields = ReflectUtil.getFields(parameterObject.getClass(), f -> f.isAnnotationPresent(EncryptField.class));
            if (CollUtil.isEmpty(Arrays.asList(secretFields))) {
                return;
            }

            // 2. 使用 Guava 快速构建搜索映射表 (relatedField -> Field)
            Field[] searchFields = ReflectUtil.getFields(parameterObject.getClass(), f -> f.isAnnotationPresent(EncryptSearchField.class));
            Map<String, Field> searchFieldMap = Maps.uniqueIndex(CollUtil.newArrayList(searchFields),
                    f -> {
                        assert f != null;
                        return f.getAnnotation(EncryptSearchField.class).relatedField();
                    });

            for (Field secretField : secretFields) {
                String plaintext = (String) ReflectUtil.getFieldValue(parameterObject, secretField);
                if (StrUtil.isEmpty(plaintext) || fieldEncryptKeyHelper.isEncrypted(plaintext)) {
                    continue;
                }

                // 执行加密并回填
                String ciphertext = fieldEncryptKeyHelper.encrypt(plaintext);
                ReflectUtil.setFieldValue(parameterObject, secretField, ciphertext);

                // 处理关联的模糊搜索字段 (掩码处理)
                Field searchField = searchFieldMap.get(secretField.getName());
                if (searchField == null) {
                    continue;
                }
                ReflectUtil.setFieldValue(parameterObject, searchField, mask(plaintext, searchField));
            }
        } catch (Exception e) {
            log.error("Field encryption failed", e);
            throw new FieldEncryptException("encrypt failure", e);
        }
    }

    private String mask(String plaintext, Field field) {
        EncryptSearchField sf = field.getAnnotation(EncryptSearchField.class);
        // 使用 Hutool 的 StrUtil 进行更优雅的长度判断
        if (StrUtil.isBlank(plaintext) || plaintext.length() <= (sf.prefixLength() + sf.suffixLength())) {
            return plaintext;
        }
        // 使用 Hutool 的 hide 功能或手动截取
        return StrUtil.hide(plaintext, sf.prefixLength(), plaintext.length() - sf.suffixLength());
    }
}
