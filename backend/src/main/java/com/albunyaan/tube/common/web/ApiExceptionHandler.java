package com.albunyaan.tube.common.web;

import com.albunyaan.tube.common.TraceContext;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private final MessageSource messageSource;

    public ApiExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatus(ResponseStatusException ex) {
        var statusCode = ex.getStatusCode();
        var status = resolveStatus(statusCode);
        var body = errorResponse(status, ex.getReason(), List.of());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGeneric(Exception ex) {
        var status = HttpStatus.INTERNAL_SERVER_ERROR;
        var message = messageSource.getMessage("error.internal", null, status.getReasonPhrase(), currentLocale());
        var body = errorResponse(status, message, List.of());
        return ResponseEntity.status(status).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        org.springframework.http.HttpStatusCode status,
        WebRequest request
    ) {
        var locale = currentLocale();
        var resolvedStatus = resolveStatus(status);
        var message = messageSource.getMessage("error.validation", null, "Validation failed", locale);
        var details = ex
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .map(this::formatFieldError)
            .collect(Collectors.toList());
        var body = errorResponse(resolvedStatus, message, details);
        return ResponseEntity.status(resolvedStatus).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception ex,
        @Nullable Object body,
        HttpHeaders headers,
        org.springframework.http.HttpStatusCode status,
        WebRequest request
    ) {
        var resolvedStatus = resolveStatus(status);
        if (body instanceof ProblemDetail problemDetail) {
            var errorBody = errorResponse(resolvedStatus, problemDetail.getDetail(), List.of());
            return ResponseEntity.status(resolvedStatus).headers(headers).body(errorBody);
        }
        var message = ex.getMessage() != null ? ex.getMessage() : resolvedStatus.getReasonPhrase();
        var errorBody = errorResponse(resolvedStatus, message, List.of());
        return ResponseEntity.status(resolvedStatus).headers(headers).body(errorBody);
    }

    private ErrorResponse errorResponse(HttpStatus status, String message, List<String> details) {
        var traceId = TraceContext.get();
        return new ErrorResponse(status.name(), message, details, OffsetDateTime.now(), traceId);
    }

    private String formatFieldError(FieldError error) {
        return "%s: %s".formatted(error.getField(), error.getDefaultMessage());
    }

    private Locale currentLocale() {
        var locale = LocaleContextHolder.getLocale();
        return locale != null ? locale : Locale.ENGLISH;
    }

    private HttpStatus resolveStatus(org.springframework.http.HttpStatusCode statusCode) {
        if (statusCode instanceof HttpStatus httpStatus) {
            return httpStatus;
        }
        var resolved = HttpStatus.resolve(statusCode.value());
        if (resolved != null) {
            return resolved;
        }
        return HttpStatus.valueOf(statusCode.value());
    }
}
