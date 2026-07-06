package vn.vnpt.util.exception;

import vn.vnpt.util.common.constant.ErrorCodeEnum;

public class GreenException extends RuntimeException {
    private final ErrorCodeEnum errorCodeEnum;
    private final Object data;

    public GreenException(ErrorCodeEnum errorCodeEnum, String message) {
        this(errorCodeEnum, message, null);
    }

    public GreenException(ErrorCodeEnum errorCodeEnum, String message, Object data) {
        super(message);
        this.errorCodeEnum = errorCodeEnum;
        this.data = data;
    }

    public ErrorCodeEnum getErrorCode() {
        return this.errorCodeEnum;
    }

    public Object getData() {
        return this.data;
    }
}
