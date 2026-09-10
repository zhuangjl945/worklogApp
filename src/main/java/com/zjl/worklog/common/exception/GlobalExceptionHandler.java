package com.zjl.worklog.common.exception;

import com.zjl.worklog.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：msg 是写给人看的中文提示，原样返回 */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        return ApiResponse.fail(e.getCode(), e.getMsg());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getAllErrors().isEmpty()
                ? "参数校验失败"
                : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ApiResponse.fail(40001, msg);
    }

    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getAllErrors().isEmpty()
                ? "参数校验失败"
                : e.getBindingResult().getAllErrors().get(0).getDefaultMessage();
        return ApiResponse.fail(40001, msg);
    }

    /**
     * 方法级参数校验失败。
     *
     * <p>不再把 e.getMessage() 回出去：那句话形如 getValue.xxx must not be null，
     * 带的是后端方法名和字段名，对使用者没意义，对探测者有价值。真实原因进日志。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<Void> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        log.warn("参数校验失败 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ApiResponse.fail(40001, "请求参数不合法");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return ApiResponse.fail(40001, "请求体格式错误");
    }

    /**
     * 路径没有对应的接口或静态资源：这是 404，不是系统异常。
     *
     * <p>以前它一路掉进下面的兜底，被包成 code=50000 的「系统异常」，
     * 前端与监控看到的是服务出错，实际只是地址写错了。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("资源不存在 {} {}", request.getMethod(), request.getRequestURI());
        return ApiResponse.fail(40404, "资源不存在");
    }

    /**
     * 兜底：完整堆栈只进日志，响应体一律给一句通用文案。
     *
     * <p>原来是「系统异常: 」+ e.getMessage() 直接回给客户端，SQL 片段、类名、文件路径
     * 都可能从这条路上漏出去；更要命的是这个 handler 自己一行日志都不记，
     * 真出故障时现场既没留住、又被告诉了不该知道的人。两个问题一起修掉。
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("未处理异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return ApiResponse.fail(50000, "系统繁忙，请稍后再试");
    }
}
