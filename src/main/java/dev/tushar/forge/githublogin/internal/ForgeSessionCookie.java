package dev.tushar.forge.githublogin.internal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/** Reads and writes the Forge session cookie. */
public final class ForgeSessionCookie {

    public static final String NAME = "forge_session";

    private ForgeSessionCookie() {}

    static void write(HttpServletResponse response, String token, Duration maxAge, boolean secure) {
        Cookie cookie = new Cookie(NAME, token);
        // HttpOnly: the token must be unreachable from JavaScript, so an XSS bug cannot lift it.
        cookie.setHttpOnly(true);
        // Secure everywhere except plain-HTTP local development.
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge((int) maxAge.toSeconds());
        // Lax rather than Strict: the OAuth callback is a top-level cross-site redirect back from
        // GitHub, and Strict would withhold the cookie on exactly that navigation.
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    static void clear(HttpServletResponse response, boolean secure) {
        Cookie cookie = new Cookie(NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }
}
