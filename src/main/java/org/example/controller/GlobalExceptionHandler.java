package org.example.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.client.EmbeddingException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

/**
 * 全局异常处理：把业务异常映射成合适的 HTTP 状态码与统一错误体。
 *
 * <p>原先参数错误（{@link IllegalArgumentException}）和外链服务故障都会变成 500，
 * 前端只能显示“服务器错误”，也无法区分该重试还是该改参数。这里统一为：</p>
 * <ul>
 *   <li>参数/请求体问题 → 400；</li>
 *   <li>显式抛出的 {@link ResponseStatusException} → 保留原状态码（如 404/502）；</li>
 *   <li>向量化服务不可用 → 503；</li>
 *   <li>数据库访问失败与未知异常 → 500，且不把内部细节回传给客户端。</li>
 * </ul>
 *
 * <p>错误体同时提供 {@code message} 与 {@code status}，兼容前端
 * {@code extractMessage} 读取的 message/detail 字段。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 统一错误体 */
    public record ApiError(String message, int status) {
    }

    /** 显式状态异常：保留调用方指定的状态码（如目录不存在 404、重试后仍失败 502）。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String message = ex.getReason() != null ? ex.getReason() : defaultMessage(status);
        if (status.is5xxServerError()) {
            log.error("请求处理失败: status={}, reason={}", status.value(), message, ex);
        } else {
            log.warn("请求被拒绝: status={}, reason={}", status.value(), message);
        }
        return ResponseEntity.status(status).body(new ApiError(message, status.value()));
    }

    /** 请求体解析失败（JSON 格式错误、字段类型不匹配等）。 */
    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ApiError> handleInput(ServerWebInputException ex) {
        String detail = ex.getReason() != null ? ex.getReason() : "格式不正确";
        log.warn("请求参数或请求体无法解析: {}", detail);
        // 既可能是请求体 JSON 解析失败，也可能是 query 参数类型不匹配，统一表述避免误导
        return badRequest("请求参数或请求体格式不正确：" + detail);
    }

    /** 业务参数校验失败。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "请求参数不合法";
        log.warn("请求参数不合法: {}", message);
        return badRequest(message);
    }

    /** 向量化服务不可用：属于下游依赖故障，返回 503 便于客户端重试。 */
    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<ApiError> handleEmbedding(EmbeddingException ex) {
        log.error("向量化服务调用失败", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("向量化服务暂不可用，请稍后重试", HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    /** 数据库访问异常：细节只记日志，不回传客户端。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(DataAccessException ex) {
        log.error("数据库访问失败", ex);
        return internalError();
    }

    /** 兜底：记录完整堆栈，返回通用错误信息。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("未预期的异常", ex);
        return internalError();
    }

    private static ResponseEntity<ApiError> badRequest(String message) {
        return ResponseEntity.badRequest()
                .body(new ApiError(message, HttpStatus.BAD_REQUEST.value()));
    }

    private static ResponseEntity<ApiError> internalError() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("服务器内部错误，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    private static String defaultMessage(HttpStatusCode status) {
        return status.is4xxClientError() ? "请求不合法" : "服务器处理失败";
    }
}
