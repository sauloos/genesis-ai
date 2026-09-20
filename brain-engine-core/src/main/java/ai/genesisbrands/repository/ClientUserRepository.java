package ai.genesisbrands.repository;

import ai.genesisbrands.model.ClientUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientUserRepository extends JpaRepository<ClientUser, String> {
    Optional<ClientUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
