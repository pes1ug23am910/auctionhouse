package io.auctionhouse.web;

import io.auctionhouse.auction.AuctionException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(AuctionException.class)
    ResponseEntity<Map<String,String>> auction(AuctionException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code",error.code(),"message",error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<Map<String,String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","Check the request fields and required headers"));
    }
}
