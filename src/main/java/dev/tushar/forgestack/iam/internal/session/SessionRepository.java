package dev.tushar.forgestack.iam.internal.session;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByTokenHash(byte[] tokenHash);

    List<Session> findByUserIdAndRevokedAtIsNull(UUID userId);
}
