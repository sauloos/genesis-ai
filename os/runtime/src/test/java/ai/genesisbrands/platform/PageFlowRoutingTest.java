package ai.genesisbrands.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageFlowRoutingTest {

    @Test
    void routeKey_nullPrefixUsesDefault() {
        assertThat(PageFlowRouting.routeKey(null, "hello")).isEqualTo("live/hello");
    }

    @Test
    void routeKey_emptyPrefixIsRootMounted() {
        assertThat(PageFlowRouting.routeKey("", "hello")).isEqualTo("hello");
    }

    @Test
    void routeKey_emptyPrefixAndEmptySlugIsSiteRoot() {
        assertThat(PageFlowRouting.routeKey("", "")).isEqualTo("");
    }

    @Test
    void routeKey_customPrefix() {
        assertThat(PageFlowRouting.routeKey("preview", "hello")).isEqualTo("preview/hello");
    }

    @Test
    void fullPath_rootResolvesToSlash() {
        assertThat(PageFlowRouting.fullPath("", "")).isEqualTo("/");
    }

    @Test
    void fullPath_defaultPrefix() {
        assertThat(PageFlowRouting.fullPath(null, "hello")).isEqualTo("/live/hello");
    }

    @Test
    void fullPath_rootMountedSlug() {
        assertThat(PageFlowRouting.fullPath("", "hello")).isEqualTo("/hello");
    }

    @Test
    void normalizeSegment_trimsLowercasesAndStripsSlashes() {
        assertThat(PageFlowRouting.normalizeSegment("  /Hello/ ")).isEqualTo("hello");
    }

    @Test
    void normalizeSegment_nullBecomesEmpty() {
        assertThat(PageFlowRouting.normalizeSegment(null)).isEqualTo("");
    }

    @Test
    void validate_reservedExplicitPrefixThrows() {
        assertThatThrownBy(() -> PageFlowRouting.validate("dashboard", "hello"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_reservedSlugWhenRootMountedThrows() {
        assertThatThrownBy(() -> PageFlowRouting.validate("", "dashboard"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_liveIsNotReserved() {
        PageFlowRouting.validate("live", "hello");
    }

    @Test
    void validate_nullPrefixNeverThrows() {
        PageFlowRouting.validate(null, "dashboard");
    }

    @Test
    void validate_nonRootMountedSlugIgnoresReservedList() {
        PageFlowRouting.validate("preview", "dashboard");
    }
}
