package dev.tushar.forge.iam;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByPrimaryEmail(String primaryEmail);
}
