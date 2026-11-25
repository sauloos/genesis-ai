package ai.genesisbrands.agent.logo;

import ai.genesisbrands.platform.knowledge.KnowledgeCases;

import java.util.List;

/** Renders the agency's approved logo cases as a prompt block for the concept stage and its critic. */
final class LogoCases {

    private LogoCases() {}

    static String render(List<KnowledgeCases.Case> cases) {
        if (cases == null || cases.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## Cases from the agency's library\n\n"
            + "Real logos the agency has judged. Learn the lesson of each, never copy a GOOD case's "
            + "form, and never repeat what a BAD case did.\n");
        for (KnowledgeCases.Case c : cases) {
            sb.append("\n- [").append(c.verdict()).append("] ")
                .append(c.title() == null ? "Untitled case" : c.title());
            if (c.sector() != null) sb.append(" (").append(c.sector()).append(")");
            sb.append(": ");
            if (c.analysis() != null) sb.append(c.analysis().strip()).append(' ');
            if (c.lesson() != null) sb.append("Lesson: ").append(c.lesson().strip());
        }
        return sb.append('\n').toString();
    }
}
