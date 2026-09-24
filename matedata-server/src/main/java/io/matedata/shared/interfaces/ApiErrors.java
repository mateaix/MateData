package io.matedata.shared.interfaces;

import io.matedata.shared.ApplicationException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(ApplicationException.class)
  ResponseEntity<?> known(ApplicationException e) {
    int status =
        switch (e.kind()) {
          case UNAUTHENTICATED, INVALID_CREDENTIALS -> 401;
          case FORBIDDEN -> 403;
          case NOT_FOUND -> 404;
          case CONFLICT -> 409;
          case BUSY -> 429;
          case INVALID_REQUEST -> 400;
          case CONNECTION_FAILED -> 422;
        };
    return error(status, e.kind().name(), e.getMessage());
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return error(
        400,
        "INVALID_REQUEST",
        e instanceof IllegalArgumentException ? e.getMessage() : "请求格式不正确，请检查输入");
  }

  @ExceptionHandler({
    org.springframework.web.servlet.resource.NoResourceFoundException.class,
    org.springframework.web.HttpRequestMethodNotSupportedException.class,
    org.springframework.web.HttpMediaTypeNotSupportedException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<?> framework(Exception e) {
    int status = ((org.springframework.web.ErrorResponse) e).getStatusCode().value();
    String message =
        switch (status) {
          case 404 -> "请求的资源不存在";
          case 405 -> "不支持此请求方法";
          case 415 -> "不支持此内容类型";
          default -> "请求参数不完整或无效";
        };
    return error(status, "HTTP_" + status, message);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception e) {
    String id = UUID.randomUUID().toString();
    var diagnostics = new java.util.ArrayList<String>();
    Throwable cause = e;
    for (int depth = 0; cause != null && depth < 6; depth++, cause = cause.getCause()) {
      diagnostics.add(cause.getClass().getName());
      java.util.Arrays.stream(cause.getStackTrace())
          .filter(frame -> frame.getClassName().startsWith("io.matedata."))
          .limit(3)
          .forEach(frame -> diagnostics.add(frame.toString()));
    }
    org.slf4j.LoggerFactory.getLogger(getClass()).error("Request failed {} ({})", id, diagnostics);

    return ResponseEntity.internalServerError()
        .body(Map.of("code", "INTERNAL_ERROR", "message", "操作失败，请联系管理员并提供请求编号", "requestId", id));
  }

  public static ResponseEntity<?> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message == null ? code : message,
                "requestId",
                UUID.randomUUID().toString()));
  }
}
