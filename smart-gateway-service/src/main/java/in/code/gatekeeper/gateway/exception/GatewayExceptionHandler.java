package in.code.gatekeeper.gateway.exception;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Gateway errors as ProblemDetail, the same error format api-management uses. X-Request-Id is already on the response.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ProblemDetail> handle(GatewayException e) {
        return ResponseEntity.status(e.getStatus())
                .headers(e.getHeaders())
                .body(ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage()));
    }
}
