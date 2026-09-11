/*
 * Copyright (c) 2023 xx-cloud Authors. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sn68.agent.framework.boot.base;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.sn68.agent.framework.commons.entity.Result;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.i18n.core.I18nMessageResource;
import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Resource;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractResourceBasedMessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Levin
 * @since 2019-01-21
 */
@Slf4j
@Configuration
@ControllerAdvice
@Order(value = Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * 数据访问异常对外话术的 i18n key，缺省文案见 default-i18n/messages_*.properties。
     */
    static final String DATA_ACCESS_MESSAGE_CODE = "global.exception.data-access";

    @Resource
    private I18nMessageResource i18nMessageResource;

    @Bean
    @ConditionalOnMissingBean
    public I18nMessageResource i18nMessageResource(MessageSource messageSource) {
        if (messageSource instanceof AbstractResourceBasedMessageSource resourceBased) {
            resourceBased.addBasenames("classpath:default-i18n/messages");
        }
        return new I18nMessageResource(messageSource);
    }

    @ResponseBody
    @ExceptionHandler(value = MultipartException.class)
    public Result<ResponseEntity<Void>> handlerException(MultipartException e) {
        return Result.fail(i18nMessageResource.getMessage("global.exception.file-too-large"));
    }

    @ResponseBody
    @ExceptionHandler(value = NullPointerException.class)
    public Result<ResponseEntity<Void>> nullPointerException(NullPointerException e) {
        log.error("请求地址：http request uri => {}", currentRequestUrl());
        log.error("异常原因：", e);
        return Result.fail(e.getLocalizedMessage());
    }

    @ResponseBody
    @ExceptionHandler(value = ServletException.class)
    public Result<ResponseEntity<Void>> servletException(ServletException e) {
        log.error("servlet exception => http request uri => {},message => {}", currentRequestUrl(), e.getLocalizedMessage());
        return Result.fail(e.getLocalizedMessage());
    }

    @ResponseBody
    @ExceptionHandler(value = RedisLockException.class)
    public Result<ResponseEntity<Void>> redisLockException(RedisLockException e) {
        log.error("redis lock exception => http request uri => {},message => {}", currentRequestUrl(), e.getLocalizedMessage());
        return Result.fail(e.getLocalizedMessage());
    }

    @ResponseBody
    @ExceptionHandler(value = CheckedException.class)
    public Result<?> handlerException(CheckedException e) {
        String message = i18nMessageResource.getMessage(e.getMessage(), e.getArgs());
        log.error("check exception 堆栈异常 => ", e);
        log.error("check exception => http request uri => {},message => {}", currentRequestUrl(), message);
        return Result.fail(e.getCode(), message, e.getData());
    }

    @ResponseBody
    @ExceptionHandler(InvocationTargetException.class)
    public final Result<ResponseEntity<Void>> invocationTargetException(InvocationTargetException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(UnexpectedTypeException.class)
    @ResponseBody
    public final Result<ResponseEntity<Void>> unexpectedTypeException(UnexpectedTypeException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseBody
    public final Result<ResponseEntity<Void>> duplicateKeyException(DuplicateKeyException e) {
        log.error("[主键冲突]", e);
        return Result.fail(i18nMessageResource.getMessage("global.exception.duplicate-key"));
    }

    /**
     * 数据访问层异常统一出口（MyBatis / MyBatis-Plus / Spring JDBC / JDBC 驱动）。
     *
     * <p>这类异常的 message 会带上完整 SQL、表名列名、Mapper 类路径与驱动堆栈线索，
     * 直接回传调用方等于把库结构泄露给终端用户，因此对外一律收敛为业务话术，
     * 完整堆栈只落服务端日志供排查。不要在这里改成回显 {@code e.getMessage()}。
     *
     * @param e 数据访问相关异常
     * @return 统一业务提示
     */
    @ResponseBody
    @ExceptionHandler({DataAccessException.class, PersistenceException.class, MybatisPlusException.class,
            SQLException.class})
    public final Result<ResponseEntity<Void>> dataAccessException(Exception e) {
        log.error("[数据访问异常] - [{}] - [{}]", currentRequestUrl(), e.getClass().getName(), e);
        return Result.fail(i18nMessageResource.getMessage(DATA_ACCESS_MESSAGE_CODE));
    }

    @ResponseBody
    @ExceptionHandler(HttpMessageConversionException.class)
    public final Result<ResponseEntity<Void>> httpMessageConversionException(HttpMessageConversionException e) {
        log.error("HttpMessage Exception", e);
        return Result.fail(e.getMessage());
    }

    @ResponseBody
    @ExceptionHandler(ConversionFailedException.class)
    public final Result<ResponseEntity<Void>> conversionFailedException(ConversionFailedException e) {
        log.error("Spring Converts Exception", e);
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseBody
    public final Result<ResponseEntity<Void>> handlerValidationException(final Exception e) {
        HttpStatus httpStatus = HttpStatus.BAD_REQUEST;
        ValidationException exception = (ValidationException) e;
        if (exception.getCause() instanceof CheckedException ex1) {
            return Result.fail(httpStatus.value(), i18nMessageResource.getMessage(ex1.getMessage(), ex1.getArgs()));
        }

        return Result.fail(httpStatus.value(), getMessage(exception.getMessage()));
    }

    private String getMessage(String message) {
        if (message == null || !message.contains(":") || !message.contains("[0]")) {
            return message;
        }
        Pattern msgPattern = Pattern.compile(":\\s*([^,]+)");
        Matcher matcher = msgPattern.matcher(message);
        List<String> results = new ArrayList<>();

        while (matcher.find()) {
            results.add(matcher.group(1).trim());
        }

        return String.join(", ", results);
    }

    /**
     * 通用的接口映射异常处理方法
     */
    @ResponseBody
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@Nonnull Exception ex, Object body, @Nonnull HttpHeaders headers, @Nonnull HttpStatusCode statusCode, @Nonnull WebRequest request) {
        String uri = ((ServletWebRequest) request).getRequest().getRequestURI();
        if (ex instanceof MethodArgumentNotValidException e) {
            String message = e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
            log.warn("[参数验证错误] - [{}] - [{}]", uri, message);
            return new ResponseEntity<>(Result.fail(HttpStatus.BAD_REQUEST.value(), message), HttpStatus.OK);
        } else if (ex instanceof HttpRequestMethodNotSupportedException e) {
            final String method = e.getMethod();
            return new ResponseEntity<>(Result.fail("%s 请求方式 %s 不存在", uri, method), HttpStatus.OK);
        } else if (ex instanceof MethodArgumentTypeMismatchException exception) {
            logger.error("参数转换失败，方法：" + Objects.requireNonNull(exception.getParameter().getMethod()).getName() + "，参数：" + exception.getName()
                    + ",信息：" + exception.getLocalizedMessage());
            if (ex.getCause() instanceof ConversionFailedException &&
                    ex.getCause().getCause() instanceof IllegalArgumentException) {
                return new ResponseEntity<>(Result.fail(ex.getCause().getCause().getLocalizedMessage()), HttpStatus.OK);
            }
            return new ResponseEntity<>(Result.fail("表单填写错误"), HttpStatus.OK);
        } else if (ex instanceof HttpMessageNotReadableException e) {
            logger.error("参数转换失败" + ex.getLocalizedMessage());
            logCompressedBodyParseFailure(uri, request, e);
            if (e.getCause() instanceof InvalidFormatException invalid) {
                return new ResponseEntity<>(Result.fail("字段类型映射错误 " + invalid.getMessage()), HttpStatus.OK);
            }
        } else if (ex instanceof NoHandlerFoundException e) {
            logger.error("地址错误" + e.getLocalizedMessage());
            return new ResponseEntity<>(Result.fail("地址错误 " + e.getMessage()), HttpStatus.OK);
        }
        final String contextPath = request.getContextPath();
        if (request instanceof ServletRequest servletRequest) {
            logger.error("系统异常 - [" + servletRequest.getServletContext().getContextPath() + "]", ex);
        } else {
            logger.error("系统异常 - [" + contextPath + "]", ex);
        }
        return new ResponseEntity<>(Result.fail("系统异常：" + ex.getLocalizedMessage()), HttpStatus.OK);
    }

    /**
     * 取当前请求地址用于日志定位；脱离请求上下文（如 MQ、定时任务）时不因取不到上下文而覆盖原始异常。
     *
     * @return 请求地址，取不到时返回 unknown
     */
    private String currentRequestUrl() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
                return attributes.getRequest().getRequestURI();
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 输出压缩请求体未被解压时的诊断信息。
     *
     * @param uri     请求地址
     * @param request Web 请求
     * @param e       消息不可读异常
     */
    private void logCompressedBodyParseFailure(String uri, WebRequest request, HttpMessageNotReadableException e) {
        String message = e.getLocalizedMessage();
        if (message == null || !message.contains("code 31")) {
            return;
        }
        if (request instanceof ServletWebRequest servletWebRequest) {
            var servletRequest = servletWebRequest.getRequest();
            log.warn("检测到疑似 gzip 请求体未解压即进入 JSON 解析, uri={}, contentEncoding={}, contentType={}, contentLength={}, handler={}",
                    uri,
                    servletRequest.getHeader(HttpHeaders.CONTENT_ENCODING),
                    servletRequest.getContentType(),
                    servletRequest.getContentLengthLong(),
                    resolveHandlerName(servletRequest.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE)));
            return;
        }
        log.warn("检测到疑似 gzip 请求体未解压即进入 JSON 解析, uri={}", uri);
    }

    /**
     * 解析当前请求命中的 Controller 方法名称。
     *
     * @param handler Spring MVC 最佳匹配处理器
     * @return Controller 方法名称
     */
    private String resolveHandlerName(Object handler) {
        if (handler instanceof HandlerMethod handlerMethod) {
            return handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
        }
        return String.valueOf(handler);
    }
}
