package ai.genesisbrands.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code context: on_demand} opt-out decides whether a Layer 1 module is injected into
 * every RAG prompt and every Consultant turn, so the default must stay "always" — a module
 * that silently stopped being context would quietly change every agent's behaviour.
 */
class Layer1ServiceTest {

    @Test
    @DisplayName("a module with no context key stays in the always-on context block")
    void defaultsToAlways() {
        assertThat(Layer1Service.isOnDemand("""
                module: copy-rubric
                version: 1.0
                description: Criteria for evaluating copy.
                """)).isFalse();
    }

    @Test
    @DisplayName("a top-level context: on_demand opts the module out")
    void honoursOnDemand() {
        assertThat(Layer1Service.isOnDemand("""
                module: purpose-playbook-signals
                version: 1.0
                context: on_demand
                description: The signal catalogue.
                """)).isTrue();
    }

    @Test
    @DisplayName("an explicit context: always is kept in context")
    void honoursExplicitAlways() {
        assertThat(Layer1Service.isOnDemand("""
                module: archetypes
                context: always
                """)).isFalse();
    }

    @Test
    @DisplayName("a trailing comment on the context key is tolerated")
    void toleratesTrailingComment() {
        assertThat(Layer1Service.isOnDemand(
                "module: x\ncontext: on_demand  # 40KB, only the purpose-playbook agent needs it\n"))
                .isTrue();
    }

    @Test
    @DisplayName("an indented or nested context key is not the module-level declaration")
    void ignoresNestedContextKeys() {
        assertThat(Layer1Service.isOnDemand("""
                module: x
                sections:
                  - key: setup
                    context: on_demand
                """)).isFalse();

        assertThat(Layer1Service.isOnDemand("""
                module: x
                description: >
                  The context: on_demand flag is described here in prose.
                """)).isFalse();
    }

    @Test
    @DisplayName("the first top-level context key wins and nothing after it is read")
    void firstDeclarationWins() {
        assertThat(Layer1Service.isOnDemand("module: x\ncontext: always\ncontext: on_demand\n"))
                .isFalse();
    }
}
