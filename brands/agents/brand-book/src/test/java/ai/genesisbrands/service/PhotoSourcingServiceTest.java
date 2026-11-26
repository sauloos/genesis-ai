package ai.genesisbrands.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PhotoSourcingServiceTest {

    private static PhotoSourcingService.Candidate photo(String id, boolean portrait) {
        return new PhotoSourcingService.Candidate(id, "https://img/" + id, "", portrait ? 800 : 1200,
            portrait ? 1200 : 800, "A. Photographer", "");
    }

    @Test
    void neverUsesAPhotoTwiceOrOneASiblingDirectionHas() {
        List<String> terms = List.of("roaster at work", "green beans");
        Map<String, List<PhotoSourcingService.Candidate>> pools = Map.of(
            "roaster at work", List.of(photo("a", false), photo("b", false), photo("taken", true)),
            "green beans", List.of(photo("a", false), photo("c", false)));
        var picks = PhotoSourcingService.assign(List.of("imagery-1", "imagery-2", "imagery-3", "imagery-4"),
            terms, pools, Set.of("taken"));

        List<String> ids = picks.values().stream().map(PhotoSourcingService.Candidate::id).toList();
        assertThat(ids).doesNotContain("taken").doesNotHaveDuplicates();
        assertThat(new HashSet<>(ids)).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(picks).doesNotContainKey("imagery-4");
    }

    @Test
    void fullPageSlotsPreferPortrait() {
        var picks = PhotoSourcingService.assign(List.of("cover", "imagery-1"), List.of("bench"),
            Map.of("bench", new ArrayList<>(List.of(photo("wide", false), photo("tall", true)))), Set.of());
        assertThat(picks.get("cover").id()).isEqualTo("tall");
        assertThat(picks.get("imagery-1").id()).isEqualTo("wide");
    }

    @Test
    void aFullPageSlotTakesALandscapeWhenNoPortraitIsLeft() {
        var picks = PhotoSourcingService.assign(List.of("cover"), List.of("bench"),
            Map.of("bench", List.of(photo("wide", false))), Set.of());
        assertThat(picks.get("cover").id()).isEqualTo("wide");
    }

    @Test
    void skipsPhotosOfOtherCompaniesBranding() {
        assertThat(PhotoSourcingService.showsBranding("brown coffee bag with printed label")).isTrue();
        assertThat(PhotoSourcingService.showsBranding("neon sign above a café counter")).isTrue();
        assertThat(PhotoSourcingService.showsBranding("Coffee packaging on a shelf")).isTrue();
        assertThat(PhotoSourcingService.showsBranding("roasted coffee beans cooling in a tray")).isFalse();
        assertThat(PhotoSourcingService.showsBranding("person holding a design notebook")).isFalse();
    }

    @Test
    void searchesOnlyKeywordsThatDoNotAskForBranding() {
        assertThat(PhotoSourcingService.searchTerms(List.of(
            "coffee bag with visible SCA score label", " roasted beans cooling tray ", "roasted beans cooling tray",
            "delivery parcel on doorstep", "hands at the roasting bench", "")))
            .containsExactly("roasted beans cooling tray", "hands at the roasting bench");
        assertThat(PhotoSourcingService.searchTerms(null)).isEmpty();
    }
}
