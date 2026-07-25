package com.threeam.global.exception.handler;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.global.exception.custom.RetryAfterException;
import com.threeam.global.exception.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("BusinessException: {} - {}", errorCode.getCode(), errorCode.getMessage());
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    // 쿨다운 거절은 남은 초를 함께 내린다. BusinessException 핸들러보다 구체 타입이라 이쪽이 먼저 잡는다.
    @ExceptionHandler(RetryAfterException.class)
    public ResponseEntity<ErrorResponse> handleRetryAfterException(RetryAfterException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("쿨다운 거절: {} - {}초 남음", errorCode.getCode(), e.getRetryAfterSeconds());
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, e.getRetryAfterSeconds()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e) {
        log.warn("Validation failed: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT_VALUE, e.getBindingResult()));
    }

    // 비동기 HTTP 대기 초과 — 뒤에서 도는 작업(분석 등)은 계속 진행돼 저장까지 될 수 있으므로
    // "서버 오류"가 아니라 새로고침 안내로 응답한다.
    @ExceptionHandler(org.springframework.web.context.request.async.AsyncRequestTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleAsyncTimeout(
            org.springframework.web.context.request.async.AsyncRequestTimeoutException e) {
        log.warn("비동기 응답 대기 초과 — 백그라운드 작업은 계속 진행 중일 수 있음");
        return ResponseEntity.status(ErrorCode.ASYNC_REQUEST_TIMEOUT.getStatus())
                .body(ErrorResponse.of(ErrorCode.ASYNC_REQUEST_TIMEOUT));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unexpected exception", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
