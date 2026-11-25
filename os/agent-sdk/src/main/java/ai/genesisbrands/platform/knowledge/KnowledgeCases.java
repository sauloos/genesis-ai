package ai.genesisbrands.platform.knowledge;

import java.util.List;

/**
 * The agency's case library: real examples an admin has marked good or bad, each with the lesson
 * it teaches, so an agent learns from cases and not only from rules. Admins add and approve cases
 * on the Knowledge page; an agent reads only approved ones. The runtime implements this port, so
 * an agent asks for it optionally and runs without it when the app does not provide one.
 */
public interface KnowledgeCases {

    /**
     * Up to {@code limit} approved cases of one kind (e.g. "logo"), the ones whose sector and
     * tags best match {@code hint} first, then the newest. Good and bad cases are mixed.
     */
    List<Case> approved(String kind, String hint, int limit);

    /** @param verdict GOOD or BAD */
    record Case(String verdict, String title, String sector, List<String> tags,
                String analysis, String lesson) {}
}
