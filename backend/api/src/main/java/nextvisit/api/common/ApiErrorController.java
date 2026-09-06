package nextvisit.api.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Boot 기본 whitelabel 에러 페이지 대신, 다른 모든 응답과 같은 {code, message} 모양을 낸다. */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object attr = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = attr instanceof Integer code ? HttpStatus.resolve(code) : null;
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ApiError body = status == HttpStatus.NOT_FOUND
            ? new ApiError("NOT_FOUND", "요청한 경로를 찾을 수 없습니다")
            : new ApiError("INTERNAL", "요청을 처리하지 못했습니다");
        return ResponseEntity.status(status).body(body);
    }
}
