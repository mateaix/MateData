package io.matedata.identity.interfaces;
import io.matedata.identity.application.IdentityService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final IdentityService service;
    public AuthController(IdentityService service){this.service=service;}
    public record Login(String username,String password){}
    @PostMapping("/login") public IdentityService.User login(@RequestBody Login body,HttpServletRequest request){
        var user=service.login(body.username(),body.password());var old=request.getSession(false);if(old!=null)old.invalidate();request.getSession(true).setAttribute("user",user);return user;
    }
    @GetMapping("/me") public IdentityService.User me(HttpServletRequest request){return Access.user(request);}
    @PostMapping("/logout") public Map<String,Boolean> logout(HttpServletRequest request){request.getSession().invalidate();return Map.of("success",true);}
}
