package ai.genesisbrands.previews;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.security.ClientAuthHelper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreviewControllerTest {

    private final DocumentPreviewService service = mock(DocumentPreviewService.class);
    private final ClientAuthHelper auth = mock(ClientAuthHelper.class);
    private final PreviewController controller = new PreviewController(service, auth);
    private final HttpServletRequest req = mock(HttpServletRequest.class);

    @Test
    void everyRouteNeedsTheClientsOwnSession() {
        when(auth.resolve(any())).thenReturn(Optional.empty());   // a connector key or no cookie resolves to nobody
        for (Runnable call : new Runnable[]{
            () -> controller.mine(req),
            () -> controller.manifest("e1", "EVOLVED", req),
            () -> controller.page("tok", req)}) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
        verifyNoInteractions(service);
    }

    @Test
    void pageResponsesAreNeverCachedOrSniffed() {
        ClientUser u = new ClientUser();
        u.setId("u1");
        u.setEmail("saulo@gmail.com");
        when(auth.resolve(any())).thenReturn(Optional.of(u));
        when(service.page("tok", "u1", "sa***@gmail.com", null, null)).thenReturn(new DocumentPreviewService.Rendition(new byte[]{1}));

        var res = controller.page("tok", req);

        assertThat(res.getHeaders().getCacheControl()).contains("no-store").contains("private");
        assertThat(res.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeaders().getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(res.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
    }
}
