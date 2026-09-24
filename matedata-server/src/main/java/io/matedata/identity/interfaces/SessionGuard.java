package io.matedata.identity.interfaces;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionGuard extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader("Referrer-Policy", "same-origin");
    if (req.getRequestURI().startsWith("/api/")) {
      res.setHeader("Cache-Control", "no-store");
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
          && !"1".equals(req.getHeader("X-MateData-Request"))) {
        fail(res, 403, "CSRF_REJECTED", "请求校验失败");
        return;
      }
      if (!req.getRequestURI().equals("/api/v1/auth/login")) {
        var s = req.getSession(false);
        if (s == null || s.getAttribute("user") == null) {
          fail(res, 401, "UNAUTHENTICATED", "请先登录");
          return;
        }
      }
    }
    chain.doFilter(req, res);
  }

  private void fail(HttpServletResponse res, int code, String reason, String message)
      throws IOException {
    res.setStatus(code);
    res.setContentType("application/json;charset=UTF-8");
    res.getWriter()
        .write(
            "{\"code\":\""
                + reason
                + "\",\"message\":\""
                + message
                + "\",\"requestId\":\""
                + java.util.UUID.randomUUID()
                + "\"}");
  }
}
