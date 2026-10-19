package ai.genesisbrands.controller;

import ai.genesisbrands.platform.WidgetExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetExtensionControllerTest {

    private static WidgetExtension ext(String type, String module, int order) {
        return new WidgetExtension() {
            @Override public String widgetType() { return type; }
            @Override public String module() { return module; }
            @Override public int order() { return order; }
        };
    }

    private static WidgetExtensionController controller(WidgetExtension... beans) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        for (int i = 0; i < beans.length; i++) factory.addBean("ext" + i, beans[i]);
        return new WidgetExtensionController(factory.getBeanProvider(WidgetExtension.class));
    }

    @Test
    void listsModulesPerTypeInnermostFirst() {
        WidgetExtensionController c = controller(
            ext("login", "/widgets/login/ext/outer.js", 10),
            ext("login", "/widgets/login/ext/inner.js", 0),
            ext("payment", "/widgets/payment/ext/a.js", 0));

        assertThat(c.extensions()).isEqualTo(Map.of(
            "login", List.of("/widgets/login/ext/inner.js", "/widgets/login/ext/outer.js"),
            "payment", List.of("/widgets/payment/ext/a.js")));
    }

    @Test
    void noBeansMeansNoExtensions() {
        assertThat(controller().extensions()).isEmpty();
    }

    @Test
    void rejectsAModuleThatIsNotAnAbsoluteScriptPath() {
        assertThatThrownBy(() -> controller(ext("login", "widgets/login/ext.js", 0)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller(ext("login", "/widgets/login/ext.css", 0)))
            .isInstanceOf(IllegalStateException.class);
    }
}
