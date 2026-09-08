package nextvisit.api.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
    }

    /** 엔진의 검증 예외. 항목·주차가 메시지에 들어 있으므로 그대로 노출한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("VALIDATION", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> beanValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .findFirst().orElse("validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("VALIDATION", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError("VALIDATION", "요청 본문을 읽을 수 없습니다"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(DataIntegrityViolationException ignored) {
        log.warn("DATA_INTEGRITY_CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError("CONFLICT", "이미 저장된 기록입니다. 새로고침 후 다시 시도해 주세요"));
    }

    /** 매핑되는 곳이 없는 경로. 흔한 404 트래픽이므로 debug로만 남기고 catch-all에 삼켜지기 전에 잡는다. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noHandler(NoResourceFoundException ignored) {
        log.debug("ROUTE_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError("NOT_FOUND", "요청한 경로를 찾을 수 없습니다"));
    }

    /** 있는 경로에 지원하지 않는 메서드. 흔한 405 트래픽이므로 debug로만 남긴다. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ignored) {
        log.debug("HTTP_METHOD_NOT_ALLOWED");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(new ApiError("METHOD_NOT_ALLOWED", "이 경로에서 지원하지 않는 방식입니다"));
    }

    /** 마지막 방어선. 예외 메시지를 그대로 내보내지 않는다 — 내부 구현이 새어나갈 수 있다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ignored) {
        log.error("UNEXPECTED_FAILURE");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("INTERNAL", "요청을 처리하지 못했습니다"));
    }
}
