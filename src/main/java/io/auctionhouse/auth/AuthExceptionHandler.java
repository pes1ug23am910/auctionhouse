package io.auctionhouse.auth;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses=AuthController.class)
public class AuthExceptionHandler {
    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String,String>> failed(AuthFailure failure) {
        return ResponseEntity.status(401).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(Map.of("code", failure.code(), "message", failure.getMessage()));
    }
}
