package com.exchange.global.error;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.exchange.domain.audit.service.AuditService;
import com.exchange.global.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 예외를 공통 응답 형식으로 바꾸고, 실패한 변경 요청을 감사 로그에 남긴다. */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

	private final AuditService auditService;

	/** 업무 규칙 위반. */
	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ApiResponse<Void>> handle(BusinessException e, HttpServletRequest request) {
		return respond(e.getErrorCode(), request);
	}

	/** 형식이 잘못된 요청. 입력값을 응답에 되돌려 주지 않는다. */
	@ExceptionHandler({ MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
			ServletRequestBindingException.class, HttpMediaTypeNotSupportedException.class })
	ResponseEntity<ApiResponse<Void>> handleInvalid(Exception e, HttpServletRequest request) {
		return respond(ErrorCode.INVALID_REQUEST, request);
	}

	/** 없는 경로. */
	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiResponse<Void>> handleNotFound(HttpServletRequest request) {
		return respond(ErrorCode.NOT_FOUND, request);
	}

	/** 지원하지 않는 메서드. */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiResponse<Void>> handleMethod(HttpServletRequest request) {
		return respond(ErrorCode.METHOD_NOT_ALLOWED, request);
	}

	/** 예상하지 못한 오류. 원인은 로그에만 남긴다. */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
		// 인가 실패는 보안 필터가 처리하도록 다시 던진다.
		if (e instanceof org.springframework.security.access.AccessDeniedException denied) {
			throw denied;
		}
		log.error("처리하지 못한 예외", e);
		return respond(ErrorCode.INTERNAL_ERROR, request);
	}

	/** 실패를 감사 로그에 남기고 공통 형식으로 응답한다. */
	private ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, HttpServletRequest request) {
		if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
			auditService.recordFailure(request.getMethod() + " " + request.getRequestURI(), code.name());
		}
		return ResponseEntity.status(code.getStatus()).body(ApiResponse.fail(code));
	}
}
