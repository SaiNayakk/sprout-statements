package app.sprout.statements.web;

import app.sprout.statements.domain.ApiException;
import app.sprout.statements.domain.ErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure into RFC 9457 problem details with the contract's stable codes. */
@RestControllerAdvice
public class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        var body = problem(e.code().status(), e.code().title(), e.code().name(), e.getMessage());
        body.putAll(e.extra());
        var res = ResponseEntity.status(e.code().status()).contentType(PROBLEM);
        if (e.retryAfterSeconds() != null) {
            body.put("retryAfterSeconds", e.retryAfterSeconds());
            res.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return res.body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted().reduce((a, b) -> a + "; " + b).orElse("Check the request body.");
        return validation(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return validation("The request body is missing, isn't valid JSON, or has fields this API doesn't accept.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e) {
        return validation("Send the body as application/json.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e) {
        return validation("The header '" + e.getHeaderName() + "' is required.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException e) {
        return validation("The query parameter '" + e.getParameterName() + "' is required.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> mismatch(MethodArgumentTypeMismatchException e) {
        return validation("The parameter '" + e.getName() + "' has the wrong type.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(PROBLEM)
                .body(problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", ErrorCode.VALIDATION_FAILED.name(), e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(PROBLEM)
                .body(problem(HttpStatus.NOT_FOUND, "Not found", ErrorCode.VALIDATION_FAILED.name(), "There's nothing at this address."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unexpected failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(PROBLEM)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong", "INTERNAL",
                        "Try again in a moment. If it keeps happening, quote the request id."));
    }

    private ResponseEntity<Map<String, Object>> validation(String detail) {
        ErrorCode c = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(c.status()).contentType(PROBLEM).body(problem(c.status(), c.title(), c.name(), detail));
    }

    static Map<String, Object> problem(HttpStatus status, String title, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://sainayakk.github.io/sprout-platform/errors/#" + code.toLowerCase());
        body.put("title", title);
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }
}
