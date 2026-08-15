package dev.tushar.forgestack.githublogin.internal;

import dev.tushar.forgestack.iam.SessionService;
import java.time.Duration;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfig {

    private final ForgeStackOAuth2UserService oauth2UserService;
    private final ForgeStackSessionAuthenticationFilter sessionFilter;
    private final SessionService sessions;

    @Value("${forgestack.security.cookie-secure:true}")
    private boolean cookieSecure;

    @Value("${forgestack.security.login-success-redirect:/}")
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
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .oauth2Login(oauth2 -> oauth2.userInfoEndpoint(userInfo -> userInfo.userService(oauth2UserService))
                        .successHandler(issueForgeSession()))
                .logout(logout -> logout.logoutUrl("/logout").addLogoutHandler((request, response, authentication) -> {
                    ForgeStackSessionCookie.read(request).ifPresent(sessions::revoke);
                    ForgeStackSessionCookie.clear(response, cookieSecure);
                }))
                // The API is authenticated by the ForgeStack session cookie, not by a servlet session.
                // The OAuth handshake still needs one to hold the authorization request between
                // the redirect out and the callback back, hence IF_REQUIRED rather than STATELESS.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .addFilterBefore(sessionFilter, UsernamePasswordAuthenticationFilter.class)
                // The session cookie is SameSite=Lax and the API is called from our own origin;
                // CSRF protection is reinstated with the browser client in a later phase.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));

        return http.build();
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
