package dev.tushar.forge.githubapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests — no Spring context. A generated keypair stands in for the real App key, so the
 * whole signing path is exercised without any GitHub credentials.
 */
class GithubAppJwtServiceTest {

    private static KeyPair keyPair;
    private static GithubAppJwtService service;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        service = new GithubAppJwtService(new GithubAppProperties("123456", toPkcs8Pem(keyPair), "forge", null, null));
    }

    private static String toPkcs8Pem(KeyPair keyPair) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keyPair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    @DisplayName("the App JWT is signed with RS256 and verifies against the App key")
    void jwtIsSignedAndVerifiable() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.mintAppJwt());

        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic())))
                .isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("123456");
    }

    @Test
    @DisplayName("the JWT lifetime stays inside GitHub's 10-minute ceiling")
    void jwtLifetimeIsWithinGithubLimit() throws Exception {
        var claims = SignedJWT.parse(service.mintAppJwt()).getJWTClaimsSet();

        Duration lifetime = Duration.between(
                claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant());

        // GitHub rejects anything over 10 minutes outright, which would look like an auth bug.
        assertThat(lifetime).isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("issued-at is backdated so a fast clock does not trip GitHub's validation")
    void issuedAtIsBackdated() throws Exception {
        var claims = SignedJWT.parse(service.mintAppJwt()).getJWTClaimsSet();

        assertThat(claims.getIssueTime().toInstant()).isBefore(Instant.now());
    }

    @Test
    @DisplayName("a PKCS#1 key fails with the command that converts it")
    void pkcs1KeyGivesActionableError() {
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----";

        // GitHub hands out PKCS#1 by default and the JDK cannot read it. The failure has to name
        // the fix, or it costs somebody an afternoon.
        assertThatThrownBy(() -> GithubAppJwtService.parsePrivateKey(pkcs1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PKCS#1")
                .hasMessageContaining("openssl pkcs8 -topk8");
    }

    @Test
    @DisplayName("an unconfigured App refuses to mint rather than failing later against GitHub")
    void unconfiguredAppFailsFast() {
        var unconfigured = new GithubAppJwtService(new GithubAppProperties(null, null, null, null, null));

        assertThatThrownBy(unconfigured::mintAppJwt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }
}
