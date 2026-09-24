package io.matedata.shared.interfaces;
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status,String code,String message) {super(message);this.status=status;this.code=code;}
}
