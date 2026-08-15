package dev.tushar.forgestack.iam.internal.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByPrimaryEmail(String primaryEmail);
}
