package me.taromati.almah.core.exception;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import me.taromati.almah.core.response.ResponseCode;

@Getter
@EqualsAndHashCode(callSuper = false)
public class ApiException extends RuntimeException {
    private final String code;
    private final String message;

    public ApiException(ResponseCode code) {
        super(code.getMessage());

        this.message = code.getMessage();
        this.code = code.getCode();
    }

    public ApiException(String code, String message) {
        super(message);

        this.message = message;
        this.code = code;
    }
}
