package vn.vnpt.util.exception;

public class TenantResolvingException extends RuntimeException {
  public TenantResolvingException(Throwable throwable, String message) {
    super(message, throwable);
  }
}
