package ai.genesisbrands.standing;

import ai.genesisbrands.platform.AdminNavExtension;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The standing-agents admin page: its nav entry and its route. BasicAuthFilter protects both paths. */
@Controller
public class StandingAdminPage {

    @GetMapping("/dashboard/standing")
    public String page() {
        return "forward:/standing.html";
    }

    @Component
    static class Nav implements AdminNavExtension {

        @Override
        public String label() {
            return "Standing agents";
        }

        @Override
        public String path() {
            return "/dashboard/standing";
        }

        @Override
        public String description() {
            return "Agents clients can subscribe to: what is offered, on what terms, and every run.";
        }
    }
}
