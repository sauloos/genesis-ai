package ai.genesisbrands.service;

import ai.genesisbrands.model.ClientSession;
import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.repository.ClientSessionRepository;
import ai.genesisbrands.repository.ClientUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientAuthService {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final int SESSION_DAYS = 30;

    private final ClientUserRepository userRepo;
    private final ClientSessionRepository sessionRepo;

    @Transactional
    public ClientUser register(String email, String name, String password) {
        if (userRepo.existsByEmail(email.toLowerCase())) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }
        ClientUser user = new ClientUser();
        user.setId(UUID.randomUUID().toString());
        user.setEmail(email.toLowerCase());
        user.setName(name);
        user.setPasswordHash(ENCODER.encode(password));
        return userRepo.save(user);
    }

    @Transactional
    public Optional<ClientUser> login(String email, String password) {
        return userRepo.findByEmail(email.toLowerCase())
            .filter(u -> ENCODER.matches(password, u.getPasswordHash()));
    }

    @Transactional
    public String createSession(String clientUserId) {
        ClientSession session = new ClientSession();
        session.setToken(UUID.randomUUID().toString());
        session.setClientUserId(clientUserId);
        session.setExpiresAt(Instant.now().plus(SESSION_DAYS, ChronoUnit.DAYS));
        sessionRepo.save(session);
        return session.getToken();
    }

    public Optional<ClientUser> validateSession(String token) {
        if (token == null) return Optional.empty();
        return sessionRepo.findByTokenAndExpiresAtAfter(token, Instant.now())
            .flatMap(s -> userRepo.findById(s.getClientUserId()));
    }

    @Transactional
    public void invalidateSession(String token) {
        sessionRepo.deleteById(token);
    }
}
