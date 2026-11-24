package org.smarttrack.controller;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<?> validation(MethodArgumentNotValidException e) {
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "error",
                "Invalid telemetry or settings",
                "fields",
                e.getBindingResult().getFieldErrors().stream()
                    .map(x -> x.getField() + ": " + x.getDefaultMessage())
                    .toList()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("error", ObjectsHelper.message(e.getReason())));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict() {
    return ResponseEntity.status(409)
        .body(Map.of("error", "Duplicate or conflicting data; retry the same packet"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database() {
    return ResponseEntity.status(503).body(Map.of("error", "Database unavailable; retry later"));
  }

  private static class ObjectsHelper {
    static String message(String value) {
      return value == null ? "Request failed" : value;
    }
  }
}
