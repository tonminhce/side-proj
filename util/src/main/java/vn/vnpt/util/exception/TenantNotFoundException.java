package vn.vnpt.util.exception;

public class TenantNotFoundException extends RuntimeException {
  public TenantNotFoundException(String message) {
    super(message);
  }
}
