package io.matedata.shared.interfaces;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> known(ApiException e) {return error(e.status,e.code,e.getMessage());}
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) {return error(400,"INVALID_REQUEST",e instanceof IllegalArgumentException ? e.getMessage() : "请求格式不正确，请检查输入");}
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception e) {
        String id=UUID.randomUUID().toString(); org.slf4j.LoggerFactory.getLogger(getClass()).error("Request failed {} ({})",id,e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","操作失败，请联系管理员并提供请求编号","requestId",id));
    }
    public static ResponseEntity<?> error(int status,String code,String message) {return ResponseEntity.status(status).body(Map.of("code",code,"message",message==null?code:message,"requestId",UUID.randomUUID().toString()));}
}
