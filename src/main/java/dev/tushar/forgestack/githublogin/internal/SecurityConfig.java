package dev.tushar.forgestack.githublogin.internal;

import dev.tushar.forgestack.githublogin.ForgeStackPrincipal;
import dev.tushar.forgestack.iam.SessionService;
import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

@Configuration
class SecurityConfig {

    private final ForgeStackOAuth2UserService oauth2UserService;
    private final ForgeStackSessionAuthenticationFilter sessionFilter;
    private final SessionService sessions;

    @Value("${forgestack.security.cookie-secure:true}")
    private boolean cookieSecure;

    /**
     * Where a successful login lands.
     *
     * <p>Defaulted to {@code /api/session} rather than {@code /}, because nothing serves {@code /}
     * until a frontend exists — so every successful login rendered Spring's Whitelabel 404 and was
     * indistinguishable from a broken one. It was reported as a failure three times before anyone
     * realised it was the success path.
     *
     * <p>Landing on the session resource also answers the question a user actually has at that
     * moment: it worked, and <em>which GitHub account am I signed in as</em> — which decides whether
     * the install flow will accept their installation at all.
     */
    @Value("${forgestack.security.login-success-redirect:/api/session}")
    private String loginSuccessRedirect;

    SecurityConfig(
            ForgeStackOAuth2UserService oauth2UserService,
            ForgeStackSessionAuthenticationFilter sessionFilter,
            SessionService sessions) {
        this.oauth2UserService = oauth2UserService;
        this.sessionFilter = sessionFilter;
        this.sessions = sessions;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(
                                "/actuator/health/**", "/login/**", "/oauth2/**", "/error")
                        .permitAll()
                        // GitHub signs webhook deliveries with an HMAC, which is a stronger
                        // check than a session cookie and must not require one.
                        .requestMatchers("/api/webhooks/**")
                        .permitAll()
                        .requestMatchers("/api/**")
                        // Not .authenticated(). That predicate was satisfied by an
                        // OAuth2AuthenticationToken left in the servlet session, which let a request
                        // through to a controller declaring ForgeStackPrincipal and reading null.
                        // These endpoints need a ForgeStack principal specifically, so ask for it —
                        // once, here, rather than seven times in the controllers.
                        .access(forgeStackPrincipalRequired())
                        .anyRequest()
                        .permitAll())
                .oauth2Login(oauth2 -> oauth2.userInfoEndpoint(userInfo -> userInfo.userService(oauth2UserService))
                        .authorizedClientRepository(new DiscardedGithubUserTokens())
                        .successHandler(issueForgeSession()))
                .logout(logout -> logout.logoutUrl("/logout").addLogoutHandler((request, response, authentication) -> {
                    ForgeStackSessionCookie.read(request).ifPresent(sessions::revoke);
                    ForgeStackSessionCookie.clear(response, cookieSecure);
                }))
                // The API is authenticated by the ForgeStack session cookie, not by a servlet session.
                //
                // That sentence was here before the code made it true. oauth2Login's default
                // repository persisted the OAuth2AuthenticationToken into the servlet session,
                // SecurityContextHolderFilter restored it on the next request, and every /api/**
                // call then reached a controller with the wrong principal type. Storing nothing is
                // what makes the claim structural: there is no context to restore, so no request can
                // arrive pre-authenticated by anything other than the cookie.
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                // The handshake still needs a servlet session, but only to hold the authorization
                // request between the redirect out and the callback back — a different attribute,
                // owned by HttpSessionOAuth2AuthorizationRequestRepository and untouched by the line
                // above. Hence IF_REQUIRED rather than STATELESS.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // Without this, an unauthenticated API call is answered with a 302 to github.com.
                // A browser follows it and signs in; fetch() follows it too, fails on CORS, and
                // reports something that mentions neither authentication nor this application.
                // See SignInRequired for why the choice is made per caller rather than per path.
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new SignInRequired()))
                // Nothing is saved for later, so nothing needs a session to save it in.
                //
                // ExceptionTranslationFilter stashes the current request before sending anyone to
                // log in, and stashing it creates a servlet session — so every unauthenticated API
                // call was answered with a Set-Cookie, and anything scanning or polling the API
                // while signed out accumulated sessions on the server. Nothing ever read them back:
                // login lands on a fixed URL (loginSuccessRedirect), so the saved request is
                // discarded. The handshake's own state lives in a different attribute, written when
                // the login actually starts.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
                // The session cookie is SameSite=Lax and the API is called from our own origin;
                // CSRF protection is reinstated with the browser client in a later phase.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));

        return http.build();
    }

    /**
     * Admits a request only if the ForgeStack session filter authenticated it.
     *
     * <p>The invariant every controller under {@code /api/**} assumes: <em>authenticated implies
     * {@link ForgeStackPrincipal}</em>. It held by accident until an {@code OAuth2AuthenticationToken}
     * satisfied {@code authenticated()} without being one, and
     * {@code AuthenticationPrincipalArgumentResolver} answered a type mismatch with null rather than
     * an exception — turning a configuration mistake into a 500 four frames away.
     *
     * <p>An anonymous caller still yields a false decision, so {@code ExceptionTranslationFilter}
     * still redirects to login rather than returning 403; only a wrong <em>kind</em> of
     * authentication is refused outright.
     */
    private static AuthorizationManager<RequestAuthorizationContext> forgeStackPrincipalRequired() {
        return (authentication, context) -> {
            Authentication current = authentication.get();
            return new AuthorizationDecision(current != null && current.getPrincipal() instanceof ForgeStackPrincipal);
        };
    }

    /**
     * Exchanges a completed GitHub login for a ForgeStack session cookie.
     *
     * <p>From here on the browser authenticates with an opaque ForgeStack token; the GitHub OAuth
     * token is already gone.
     */
    private SimpleUrlAuthenticationSuccessHandler issueForgeSession() {
        return new SimpleUrlAuthenticationSuccessHandler(loginSuccessRedirect) {
            @Override
            public void onAuthenticationSuccess(
                    jakarta.servlet.http.@NonNull HttpServletRequest request,
                    jakarta.servlet.http.@NonNull HttpServletResponse response,
                    org.springframework.security.core.@NonNull Authentication authentication)
                    throws java.io.IOException, jakarta.servlet.ServletException {

                if (authentication.getPrincipal() instanceof OAuth2User user) {
                    String forgeUserId = String.valueOf(user.getAttributes().get(ForgeStackOAuth2UserService.FORGE_USER_ID));
                    var issued = sessions.issue(
                            java.util.UUID.fromString(forgeUserId), request.getHeader("User-Agent"));
                    ForgeStackSessionCookie.write(
                            response, issued.token(), Duration.between(java.time.Instant.now(), issued.expiresAt()), cookieSecure);
                }

                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
    }
}
