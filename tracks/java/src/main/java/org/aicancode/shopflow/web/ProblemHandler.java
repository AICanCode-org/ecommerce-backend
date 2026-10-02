package org.aicancode.shopflow.web;

import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to application/problem+json. A 401 also carries WWW-Authenticate: Bearer (RFC 6750). */
@RestControllerAdvice
public class ProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return problem(e);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        return problem(ApiException.validation("request body must be a JSON object"));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> noRoute(Exception e) {
        return problem(ApiException.notFound("route"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return problem(new ApiException(405, "http-error", "Method Not Allowed", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.error("unhandled error", e);
        return problem(new ApiException(500, "internal-error", "Internal server error", null));
    }

    static ResponseEntity<Map<String, Object>> problem(ApiException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if ("unauthorized".equals(e.slug())) {
            response.header("WWW-Authenticate", "Bearer");
        }
        return response.body(e.toProblem());
    }
}
