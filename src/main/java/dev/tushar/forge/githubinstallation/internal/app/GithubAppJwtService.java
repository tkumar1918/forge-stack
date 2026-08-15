package dev.tushar.forge.githubinstallation.internal.app;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.stereotype.Service;

/**
 * Mints the short-lived JWT that authenticates Forge <em>as the App itself</em>.
 *
 * <p>This token can only read App metadata and exchange itself for installation tokens. It cannot
 * touch repository content — that requires an installation token from
 * {@link InstallationTokenService}.
 */
@Service
public class GithubAppJwtService {

    /**
     * GitHub rejects App JWTs with a lifetime over 10 minutes. Nine gives room for clock skew on
     * both ends without living near the limit.
     */
    private static final Duration LIFETIME = Duration.ofMinutes(9);

    /**
     * GitHub validates {@code iat}, so a fast clock produces "issued in the future" rejections
     * that look like authentication bugs. Backdating absorbs modest skew.
     */
    private static final Duration ISSUED_AT_BACKDATE = Duration.ofSeconds(30);

    private final GithubAppProperties properties;
    private final RSAPrivateKey privateKey;

    public GithubAppJwtService(GithubAppProperties properties) {
        this.properties = properties;
        this.privateKey = properties.isConfigured() ? parsePrivateKey(properties.privateKeyPem()) : null;
    }

    public String mintAppJwt() {
        if (privateKey == null) {
            throw new IllegalStateException(
                    "GitHub App is not configured: set forge.github.app.app-id and forge.github.app.private-key-pem");
        }
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.appId())
                .issueTime(Date.from(now.minus(ISSUED_AT_BACKDATE)))
                .expirationTime(Date.from(now.plus(LIFETIME)))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner(privateKey));
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign GitHub App JWT", e);
        }
        return jwt.serialize();
    }

    static RSAPrivateKey parsePrivateKey(String pem) {
        String normalized = pem.trim();

        // GitHub hands out PKCS#1 ("BEGIN RSA PRIVATE KEY") by default, which the JDK cannot read
        // without BouncyCastle. Rather than pull in a crypto provider for one parsing case, fail
        // with the exact command that fixes it.
        if (normalized.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException(
                    """
                    The GitHub App private key is in PKCS#1 format, which the JDK cannot read.
                    Convert it once:
                      openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt \\
                        -in github-app.private-key.pem -out github-app.pkcs8.pem
                    """);
        }

        String base64 = normalized
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

        try {
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse GitHub App private key as PKCS#8 PEM", e);
        }
    }
}
