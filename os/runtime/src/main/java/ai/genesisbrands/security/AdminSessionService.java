package ai.genesisbrands.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Issues and validates a signed admin session cookie so the browser only needs to
 * present HTTP Basic Auth once per session instead of on every page request.
 */
@Component
public class AdminSessionService {

    static final String COOKIE_NAME = "admin_sess";
    private static final int MAX_AGE = 60 * 60 * 8; // 8 hours
    private static final String PAYLOAD = "genesis-admin";

    @Value("${genesis.basic-auth.password}")
    private String signingSecret;

    public void setSessionCookie(HttpServletResponse res) {
        Cookie c = new Cookie(COOKIE_NAME, sign(PAYLOAD));
        c.setHttpOnly(true);
        c.setSecure(true);
        c.setPath("/");
        c.setMaxAge(MAX_AGE);
        res.addCookie(c);
    }

    public boolean hasValidSession(HttpServletRequest req) {
        if (req.getCookies() == null) return false;
        String expected = sign(PAYLOAD);
        for (Cookie c : req.getCookies()) {
            if (COOKIE_NAME.equals(c.getName()) && expected.equals(c.getValue())) return true;
        }
        return false;
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC init failed", e);
        }
    }
}
