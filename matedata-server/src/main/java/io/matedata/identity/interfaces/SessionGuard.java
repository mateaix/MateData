package io.matedata.identity.interfaces;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionGuard extends OncePerRequestFilter {
  private static final int MAX_BODY_BYTES = 256 * 1024;

  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("X-Frame-Options", "DENY");
    res.setHeader("Referrer-Policy", "same-origin");
    // Match the container's decoded, normalized route, including stripped matrix parameters.
    String route = req.getServletPath();
    if (route.startsWith("/api/")) {
      res.setHeader("Cache-Control", "no-store");
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())
          && !"1".equals(req.getHeader("X-MateData-Request"))) {
        fail(res, 403, "CSRF_REJECTED", "请求校验失败");
        return;
      }
      if (!route.equals("/api/v1/auth/login")) {
        var s = req.getSession(false);
        if (s == null || s.getAttribute("user") == null) {
          fail(res, 401, "UNAUTHENTICATED", "请先登录");
          return;
        }
      }
      if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())) {
        if (req.getContentLengthLong() > MAX_BODY_BYTES) {
          fail(res, 413, "PAYLOAD_TOO_LARGE", "请求内容不能超过 256 KiB");
          return;
        }
        // Bound the bytes actually received as well: chunked requests have no declared size.
        byte[] body = req.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
          fail(res, 413, "PAYLOAD_TOO_LARGE", "请求内容不能超过 256 KiB");
          return;
        }
        req = new BoundedBodyRequest(req, body);
      }
    }
    chain.doFilter(req, res);
  }

  private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
    private final ServletInputStream input;

    BoundedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      var bytes = new java.io.ByteArrayInputStream(body);
      input =
          new ServletInputStream() {
            @Override
            public int read() {
              return bytes.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
              return bytes.read(buffer, offset, length);
            }

            @Override
            public boolean isFinished() {
              return bytes.available() == 0;
            }

            @Override
            public boolean isReady() {
              return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
              throw new IllegalStateException("Only synchronous request parsing is supported");
            }
          };
    }

    @Override
    public ServletInputStream getInputStream() {
      return input;
    }

    @Override
    public java.io.BufferedReader getReader() {
      return new java.io.BufferedReader(
          new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
    }
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
