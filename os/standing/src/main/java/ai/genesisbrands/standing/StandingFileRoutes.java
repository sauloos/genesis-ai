package ai.genesisbrands.standing;

import ai.genesisbrands.security.ApiKeyExemption;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Inbox files are loaded by plain {@code <img src>} and download links, which cannot send the
 * shared X-Api-Key. The routes still require the client's own session: the controller resolves
 * the client and serves only that client's items.
 */
@Component
public class StandingFileRoutes implements ApiKeyExemption {

    private static final Pattern FILES = Pattern.compile("^/api/client/standing/inbox/[^/]+/(attachments/\\d+|download)$");

    @Override
    public boolean exempts(String path) {
        return FILES.matcher(path).matches();
    }
}
