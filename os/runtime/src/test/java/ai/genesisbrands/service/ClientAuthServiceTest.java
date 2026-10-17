package ai.genesisbrands.service;

import ai.genesisbrands.model.ClientSession;
import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.repository.ClientSessionRepository;
import ai.genesisbrands.repository.ClientUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientAuthServiceTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Mock private ClientUserRepository userRepo;
    @Mock private ClientSessionRepository sessionRepo;

    private ClientAuthService service;

    @BeforeEach
    void setUp() {
        service = new ClientAuthService(userRepo, sessionRepo);
    }

    private ClientUser user(String id, String email, String passwordHash) {
        ClientUser u = new ClientUser();
        u.setId(id);
        u.setEmail(email);
        u.setPasswordHash(passwordHash);
        return u;
    }

    @Test
    void register_newEmail_createsUserWithHashedPassword() {
        when(userRepo.existsByEmail("ada@example.com")).thenReturn(false);
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClientUser u = service.register("Ada@Example.com", "Ada", "s3cret");

        assertThat(u.getEmail()).isEqualTo("ada@example.com");
        assertThat(u.getPasswordHash()).isNotEqualTo("s3cret");
        assertThat(ENCODER.matches("s3cret", u.getPasswordHash())).isTrue();
    }

    @Test
    void register_duplicateEmail_throws() {
        when(userRepo.existsByEmail("ada@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register("ada@example.com", "Ada", "s3cret"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void login_correctPassword_returnsUser() {
        ClientUser u = user("u1", "ada@example.com", ENCODER.encode("s3cret"));
        when(userRepo.findByEmail("ada@example.com")).thenReturn(Optional.of(u));

        Optional<ClientUser> result = service.login("ada@example.com", "s3cret");

        assertThat(result).contains(u);
    }

    @Test
    void login_wrongPassword_returnsEmpty() {
        ClientUser u = user("u1", "ada@example.com", ENCODER.encode("s3cret"));
        when(userRepo.findByEmail("ada@example.com")).thenReturn(Optional.of(u));

        assertThat(service.login("ada@example.com", "wrong")).isEmpty();
    }

    @Test
    void login_googleOnlyAccountWithNullPasswordHash_returnsEmpty_doesNotThrow() {
        ClientUser u = user("u1", "ada@example.com", null);
        when(userRepo.findByEmail("ada@example.com")).thenReturn(Optional.of(u));

        assertThat(service.login("ada@example.com", "anything")).isEmpty();
    }

    @Test
    void login_unknownEmail_returnsEmpty() {
        when(userRepo.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThat(service.login("ghost@example.com", "anything")).isEmpty();
    }

    @Test
    void loginWithGoogle_whenNotConfigured_throwsIllegalState() {
        assertThatThrownBy(() -> service.loginWithGoogle("some-id-token"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createSession_savesSessionAndReturnsToken() {
        when(sessionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String token = service.createSession("u1");

        assertThat(token).isNotBlank();
        org.mockito.ArgumentCaptor<ClientSession> captor = org.mockito.ArgumentCaptor.forClass(ClientSession.class);
        org.mockito.Mockito.verify(sessionRepo).save(captor.capture());
        assertThat(captor.getValue().getClientUserId()).isEqualTo("u1");
        assertThat(captor.getValue().getToken()).isEqualTo(token);
        assertThat(captor.getValue().getExpiresAt()).isAfter(Instant.now().plusSeconds(3600 * 24 * 29));
    }

    @Test
    void validateSession_nullToken_returnsEmpty() {
        assertThat(service.validateSession(null)).isEmpty();
    }

    @Test
    void validateSession_validToken_resolvesUser() {
        ClientSession s = new ClientSession();
        s.setToken("tok");
        s.setClientUserId("u1");
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.of(s));
        ClientUser u = user("u1", "ada@example.com", "hash");
        when(userRepo.findById("u1")).thenReturn(Optional.of(u));

        assertThat(service.validateSession("tok")).contains(u);
    }

    @Test
    void validateSession_expiredOrMissingToken_returnsEmpty() {
        when(sessionRepo.findByTokenAndExpiresAtAfter(anyString(), any())).thenReturn(Optional.empty());

        assertThat(service.validateSession("tok")).isEmpty();
    }

    @Test
    void invalidateSession_deletesByToken() {
        service.invalidateSession("tok");

        org.mockito.Mockito.verify(sessionRepo).deleteById("tok");
    }
}
