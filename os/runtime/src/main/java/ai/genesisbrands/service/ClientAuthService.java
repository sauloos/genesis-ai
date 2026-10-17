package ai.genesisbrands.service;

import ai.genesisbrands.model.ClientSession;
import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.repository.ClientSessionRepository;
import ai.genesisbrands.repository.ClientUserRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientAuthService {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final int SESSION_DAYS = 30;

    private final ClientUserRepository userRepo;
    private final ClientSessionRepository sessionRepo;

    @Value("${genesis.google-oauth.client-id:}")
    private String googleClientId;

    private GoogleIdTokenVerifier googleVerifier;

    @PostConstruct
    void initGoogleVerifier() {
        if (googleClientId == null || googleClientId.isBlank()) {
            return;
        }
        googleVerifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
            .setAudience(Collections.singletonList(googleClientId))
            .build();
    }

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
            .filter(u -> u.getPasswordHash() != null && ENCODER.matches(password, u.getPasswordHash()));
    }

    /** Verifies a Google Sign-In ID token via the official library's real signature
     *  verification against Google's published keys (not the tokeninfo HTTP shortcut),
     *  then resolves the account by googleSub, falling back to email (linking an
     *  existing password account) or creating a new one. */
    @Transactional
    public ClientUser loginWithGoogle(String idToken) {
        if (googleVerifier == null) {
            throw new IllegalStateException("Google sign-in is not configured.");
        }
        GoogleIdToken token;
        try {
            token = googleVerifier.verify(idToken);
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalArgumentException("Could not verify Google ID token.", e);
        }
        if (token == null) {
            throw new IllegalArgumentException("Invalid Google ID token.");
        }
        GoogleIdToken.Payload payload = token.getPayload();
        String sub = payload.getSubject();
        String email = payload.getEmail();
        String name = (String) payload.get("name");

        ClientUser user = userRepo.findByGoogleSub(sub)
            .or(() -> userRepo.findByEmail(email.toLowerCase()))
            .orElseGet(() -> {
                ClientUser u = new ClientUser();
                u.setId(UUID.randomUUID().toString());
                u.setEmail(email.toLowerCase());
                u.setName(name);
                return u;
            });
        user.setGoogleSub(sub);
        return userRepo.save(user);
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
