package ai.genesisbrands.controller;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import ai.genesisbrands.security.ClientAuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandPageControllerTest {

    @Mock private ClientAuthHelper clientAuthHelper;
    @Mock private EngagementRepository engagementRepo;
    @Mock private HttpServletRequest req;

    private BrandPageController controller;

    @BeforeEach
    void setUp() {
        controller = new BrandPageController(clientAuthHelper, engagementRepo);
    }

    private ClientUser clientUser(String id) {
        ClientUser u = new ClientUser();
        u.setId(id);
        return u;
    }

    private Engagement engagement(String ownerId) {
        Engagement e = new Engagement();
        e.setId("eng-1");
        e.setClientUserId(ownerId);
        return e;
    }

    @Test
    void signedOutVisitorSignsInAndComesBack() {
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.empty());

        assertThat(controller.yourBrand("eng-1", req)).isEqualTo("redirect:/login?next=%2Fyour-brand%2Feng-1");
        verifyNoInteractions(engagementRepo);
    }

    @Test
    void claimsAnUnownedEngagementAndOpensTheDashboard() {
        Engagement e = engagement(null);
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("u1")));
        when(engagementRepo.findById("eng-1")).thenReturn(Optional.of(e));

        assertThat(controller.yourBrand("eng-1", req)).isEqualTo("redirect:/live/dashboard");
        assertThat(e.getClientUserId()).isEqualTo("u1");
        verify(engagementRepo).save(e);
    }

    @Test
    void neverTakesAnEngagementOwnedBySomeoneElse() {
        Engagement e = engagement("other");
        when(clientAuthHelper.resolve(req)).thenReturn(Optional.of(clientUser("u1")));
        when(engagementRepo.findById("eng-1")).thenReturn(Optional.of(e));

        assertThat(controller.yourBrand("eng-1", req)).isEqualTo("redirect:/live/dashboard");
        assertThat(e.getClientUserId()).isEqualTo("other");
        verify(engagementRepo, never()).save(any());
    }
}
