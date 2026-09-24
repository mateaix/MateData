package io.matedata.identity.interfaces;

import io.matedata.identity.application.IdentityService.User;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import jakarta.servlet.http.HttpServletRequest;

public final class Access {
  private Access() {}

  public static User user(HttpServletRequest request) {
    var session = request.getSession(false);
    var user = session == null ? null : (User) session.getAttribute("user");
    if (user == null) throw new ApplicationException(Kind.UNAUTHENTICATED, "请先登录");
    return user;
  }

  public static User admin(HttpServletRequest request) {
    var u = user(request);
    if (!u.role().equals("ADMIN")) throw new ApplicationException(Kind.FORBIDDEN, "需要管理员权限");
    return u;
  }

  public static User analyst(HttpServletRequest request) {
    var u = user(request);
    if (u.role().equals("VIEWER")) throw new ApplicationException(Kind.FORBIDDEN, "当前角色没有查询权限");
    return u;
  }
}
