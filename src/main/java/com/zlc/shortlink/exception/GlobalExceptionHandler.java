package com.zlc.shortlink.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * 全局异常处理：把未捕获异常统一转成 {"error": "..."} 的 JSON。
 * 否则会被 Spring 默认错误页接管，返回 HTML 或带堆栈的 JSON，前端解析不了，也泄露内部细节。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数不合法：调用方的问题，返回 400 */
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(Exception e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return Map.of("error", e.getMessage() == null ? "参数不合法" : e.getMessage());
    }

    /** 缺少必填参数，例如 POST /api/links 没带 url，返回 400 而不是 500 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return Map.of("error", "缺少必填参数：" + e.getParameterName());
    }

    /**
     * 唯一索引冲突，例如短码并发写入撞车。
     * 这类异常有明确的业务含义，单独处理并提示可重试。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一索引冲突: {}", e.getMessage());
        return Map.of("error", "短码已被占用，请重试");
    }

    /** 兜底：其他未预料到的异常，返回 500 但不暴露堆栈 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleOther(Exception e) {
        // 完整堆栈只写日志，不返回给调用方
        log.error("未处理的异常", e);
        return Map.of("error", "服务器内部错误，请稍后重试");
    }
}
