package vn.vnpt.util.exception;

import java.util.HashMap;
import java.util.Map;

public class InvalidInputException extends RuntimeException {
    private final Map<String, String> errors;

    // Constructor: 1 field
    public InvalidInputException(String field, String message) {
        this.errors = new HashMap<>();
        this.errors.put(field, message);
    }

    // Constructor: nhiều field public
    public InvalidInputException(Map<String, String> errors) {
        this.errors = errors;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
