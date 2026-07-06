package vn.vnpt.util.jasperreports;

public class JasperUtilsException extends RuntimeException{
    JasperUtilsException(String message) {
        super(message);
    }

    JasperUtilsException(Exception ex) {
        super(ex);
    }
}

