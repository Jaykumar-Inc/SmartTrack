package io.smarttrack.backend.common;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler({
    IllegalArgumentException.class,
    java.time.DateTimeException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception e, HttpServletRequest r) {
    if (e instanceof MethodArgumentNotValidException validation) {
      String detail =
          validation.getBindingResult().getFieldErrors().stream()
              .map(
                  field ->
                      field.getField().equals("password")
                          ? "Password must contain 12 to 72 characters (at most 72 UTF-8 bytes)"
                          : field.getField().equals("email")
                              ? "Enter a valid email address"
                              : field.getField() + ": " + field.getDefaultMessage())
              .distinct()
              .sorted()
              .collect(java.util.stream.Collectors.joining(". "));
      return error(400, "Invalid request", detail, r);
    }
    return error(
        400,
        "Invalid request",
        e instanceof IllegalArgumentException
            ? e.getMessage()
            : "Body does not match the API contract",
        r);
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e, HttpServletRequest r) {
    return error(e.getStatusCode().value(), "Request failed", e.getReason(), r);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e, HttpServletRequest r) {
    return error(409, "Conflict", "Record already exists or references an unavailable resource", r);
  }

  private ResponseEntity<?> error(int code, String title, String detail, HttpServletRequest r) {
    return ResponseEntity.status(code)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            Map.of(
                "type",
                "about:blank",
                "title",
                title,
                "status",
                code,
                "detail",
                detail == null ? title : detail,
                "instance",
                r.getRequestURI(),
                "timestamp",
                Instant.now().toString()));
  }
}
