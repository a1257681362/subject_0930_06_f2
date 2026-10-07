package com.evops.common;

import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.evops.exception.RouteValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(RouteValidationException.class)
    public ApiResponse<com.evops.entity.RoutePublishCheck> handleRouteValidation(RouteValidationException ex) {
        // 校验记录已落库可追溯，随响应返回禁飞区冲突坐标与原因
        return ApiResponse.fail(ex.getMessage(), ex.getCheck());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public ApiResponse<Void> handleBind(BindException ex) {
        FieldError error = ex.getBindingResult().getFieldError();
        String message = error != null ? error.getDefaultMessage() : "参数校验失败";
        return ApiResponse.fail(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleNotReadable(HttpMessageNotReadableException ex) {
        return ApiResponse.fail("请求参数格式错误");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        return ApiResponse.fail("系统处理失败");
    }
}
