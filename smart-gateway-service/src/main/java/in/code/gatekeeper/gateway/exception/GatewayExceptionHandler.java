package in.code.gatekeeper.gateway.exception;

import in.code.gatekeeper.gateway.filter.TrafficEventFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Gateway errors as ProblemDetail, the same error format api-management uses. X-Request-Id is already on the response.
 * The reason code is left on the request for the traffic event.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ProblemDetail> handle(GatewayException e, HttpServletRequest request) {
        request.setAttribute(TrafficEventFilter.REJECT_REASON, e.getReason());
        return ResponseEntity.status(e.getStatus())
                .headers(e.getHeaders())
                .body(ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage()));
    }
}
