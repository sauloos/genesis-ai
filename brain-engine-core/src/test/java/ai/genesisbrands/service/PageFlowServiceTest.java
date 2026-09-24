package ai.genesisbrands.service;

import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageFlowServiceTest {

    @Mock private PageFlowRepository pageFlowRepo;
    @Mock private PageRepository pageRepo;
    @Mock private PageWidgetRepository pageWidgetRepo;
    @Mock private PageTransitionService pageTransitionService;

    private PageFlowService service;

    @BeforeEach
    void setUp() {
        service = new PageFlowService(pageFlowRepo, pageRepo, pageWidgetRepo, pageTransitionService);
    }

    private PageFlow flow(String id, String slug, String rootPrefix, boolean live) {
        PageFlow f = new PageFlow();
        f.setId(id);
        f.setName(id);
        f.setSlug(slug);
        f.setRootPrefix(rootPrefix);
        f.setLive(live);
        return f;
    }

    @Test
    void create_neverRejectsAReservedLookingSlugSinceItIsAlwaysNamespacedByTheDefaultPrefix() {
        when(pageFlowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PageFlow created = service.create("Dashboard", "dashboard");

        assertThat(created.getSlug()).isEqualTo("dashboard");
        assertThat(created.getRootPrefix()).isNull();
    }

    @Test
    void create_savesWithDefaultRootPrefix() {
        when(pageFlowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PageFlow created = service.create("Hello", "hello");

        assertThat(created.getSlug()).isEqualTo("hello");
        assertThat(created.getRootPrefix()).isNull();
    }

    @Test
    void rename_rejectsReservedRootMountedSlug() {
        PageFlow existing = flow("f1", "hello", "", false);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rename("f1", "Dashboard", "dashboard"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setRootPrefix_rejectsReservedSegment() {
        PageFlow existing = flow("f1", "hello", null, false);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.setRootPrefix("f1", "dashboard"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(pageFlowRepo, never()).save(any());
    }

    @Test
    void setRootPrefix_blankResetsToDefault() {
        PageFlow existing = flow("f1", "hello", "preview", false);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(existing));
        when(pageFlowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PageFlow updated = service.setRootPrefix("f1", "  ");

        assertThat(updated.getRootPrefix()).isNull();
    }

    @Test
    void setRootPrefix_slashBecomesRootMount() {
        PageFlow existing = flow("f1", "hello", null, false);
        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(existing));
        when(pageFlowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PageFlow updated = service.setRootPrefix("f1", "");

        assertThat(updated.getRootPrefix()).isNull();
    }

    @Test
    void setLive_deactivatesOnlyTheFlowWithTheSameResolvedRoute() {
        PageFlow target = flow("f1", "hello", null, false);
        PageFlow colliding = flow("f2", "hello", null, true);
        PageFlow sameSlugDifferentPrefix = flow("f3", "hello", "preview", true);

        when(pageFlowRepo.findById("f1")).thenReturn(Optional.of(target));
        when(pageFlowRepo.findAllByLiveTrue()).thenReturn(List.of(colliding, sameSlugDifferentPrefix));
        when(pageFlowRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PageFlow result = service.setLive("f1");

        assertThat(result.isLive()).isTrue();
        assertThat(colliding.isLive()).isFalse();
        assertThat(sameSlugDifferentPrefix.isLive()).isTrue();
    }
}
