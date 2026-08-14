package dev.tushar.forge.githublogin.internal;

import dev.tushar.forge.githublogin.ForgePrincipal;
import dev.tushar.forge.iam.AuthenticatedSession;
import dev.tushar.forge.iam.SessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests carrying a Forge session cookie.
 *
 * <p>Every request revalidates against the database, which is what makes revocation immediate:
 * removing someone from a workspace or revoking a session takes effect on the next request rather
 * than whenever a token would have expired.
 */
@Component
public class ForgeSessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessions;

    ForgeSessionAuthenticationFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<AuthenticatedSession> session = ForgeSessionCookie.read(request).flatMap(sessions::validate);

            session.ifPresent(active -> {
                ForgePrincipal principal =
                        new ForgePrincipal(active.userId(), active.sessionId(), active.workspaceId());
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        chain.doFilter(request, response);
    }
}
