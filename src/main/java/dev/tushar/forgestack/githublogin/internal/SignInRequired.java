package dev.tushar.forgestack.githublogin.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

/**
 * What an unauthenticated request is told, in whichever dialect it speaks.
 *
 * <p>A person who typed a URL is sent to GitHub to sign in. A program is told 401 and left to decide
 * what to do about it. The same answer in two forms, because the wrong form of it is useless to both:
 * a redirect reaches {@code fetch} as an opaque cross-origin failure with no status worth reading,
 * and a 401 reaches a browser's address bar as a blank page.
 *
 * <p><strong>Why this is not simply "{@code /api/**} returns 401".</strong> {@code /api/session} is
 * where a successful login lands, so it is a URL people navigate to and bookmark. Deciding by path
 * would mean an expired session on that page produced a bare 401 instead of re-authenticating and
 * coming back — which is behaviour {@code known-gaps.md} §1.5 records as correct, having already been
 * mistaken for a bug once. The distinction that matters is not which resource is being asked for, it
 * is who is asking.
 *
 * <p>No {@code WWW-Authenticate} header, deliberately, though RFC 7235 asks for one. No registered
 * scheme describes "present a session cookie", and {@code Basic} is actively harmful here — it makes
 * browsers raise a credentials dialog for an account system that does not accept passwords.
 */
class SignInRequired implements AuthenticationEntryPoint {

    /**
     * Straight to GitHub, skipping the provider chooser.
     *
     * <p>Reproduces what {@code oauth2Login} installs by default with a single registration.
     * Configuring an entry point replaces that default outright, so the browser half has to be
     * written out rather than inherited — and if a second provider is ever added, this is the line
     * that has to change with it.
     */
    private final AuthenticationEntryPoint toGithub =
            new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/github");

    @Override
    public void commence(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AuthenticationException failure)
            throws IOException, jakarta.servlet.ServletException {

        if (isBrowserNavigation(request)) {
            toGithub.commence(request, response, failure);
            return;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // The sign-in URL is in the body because a client that gets a 401 needs somewhere to send the
        // person next, and hard-coding our OAuth path into every caller is how it goes stale.
        response.getWriter()
                .write(
                        """
                        {"error":"unauthenticated",\
                        "message":"Sign in with GitHub to use this API.",\
                        "signIn":"/oauth2/authorization/github"}""");
    }

    /**
     * Whether this is a person moving between pages, rather than code calling an API.
     *
     * <p>{@code Sec-Fetch-Mode} is the reliable signal and browsers send it on every request:
     * {@code navigate} for following a link or typing a URL, {@code cors} or {@code same-origin} for
     * {@code fetch} and {@code XMLHttpRequest}. Trusting it means a {@code fetch} that happens to ask
     * for HTML still gets a 401, which asking {@code Accept} alone would get wrong.
     *
     * <p>The {@code Accept} fallback covers clients that send no {@code Sec-Fetch-Mode} at all —
     * older browsers, and every command-line tool. {@code curl} lands on 401, which is the answer it
     * wants: a redirect to GitHub's login page is not something a terminal can do anything with, and
     * it has been misread as broken authentication before.
     */
    private static boolean isBrowserNavigation(HttpServletRequest request) {
        String fetchMode = request.getHeader("Sec-Fetch-Mode");
        if (fetchMode != null) {
            return "navigate".equalsIgnoreCase(fetchMode);
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }
}
