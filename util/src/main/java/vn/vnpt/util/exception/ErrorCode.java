package vn.vnpt.util.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    SUCCESS(200),
    UNKNOWN_ERROR(400),
    NOT_FOUND(9001),
    EXIST(9002),
    API(9003),
    BREAK(9004),
    IMPORT(9005),
    CONSTRAINT(1000),
    INTERNAL_SERVER_ERROR(500);

    private int value;
    ErrorCode(int value) {
        this.value = value;
    }
}
