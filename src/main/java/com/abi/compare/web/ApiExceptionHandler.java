package com.abi.compare.web;

import com.abi.compare.storage.DecisionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DecisionConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(DecisionConflictException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "DECISION_CONFLICT");
        body.put("message", e.getMessage());
        body.put("currentVersion", e.getCurrentVersion());
        body.put("conflicts", e.getConflicts());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
