package me.taromati.almah.core.exception;

import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.core.response.ResponseCode;
import me.taromati.almah.core.response.RootResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = Exception.class)
    public RootResponse<Void> exceptionHandler(Exception ex) {
        log.error("Exception", ex);
        return RootResponse.fail(ex);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(value = RuntimeException.class)
    public RootResponse<Void> runtimeExceptionHandler(RuntimeException ex) {
        log.error("RuntimeException", ex);
        return RootResponse.fail(ex);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = ApiException.class)
    public RootResponse<Void> apiExceptionHandler(ApiException ex) {
        log.error("ApiException", ex);
        return RootResponse.fail(ex);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public RootResponse<Void> apiExceptionHandler(MethodArgumentNotValidException ex) {
        log.error("MethodArgumentNotValidException", ex);
        return RootResponse.fail(ResponseCode.INVALID_PARAMETER);
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler(NoResourceFoundException.class)
    public RootResponse<Void> notFoundHandler(NoResourceFoundException ex) {
        return RootResponse.fail(ResponseCode.NOT_FOUND);
    }
}
