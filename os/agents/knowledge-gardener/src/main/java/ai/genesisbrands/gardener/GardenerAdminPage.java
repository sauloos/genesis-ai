package ai.genesisbrands.gardener;

import ai.genesisbrands.platform.AdminNavExtension;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The gardener's admin page: its nav entry and its route. BasicAuthFilter protects both paths. */
@Controller
public class GardenerAdminPage {

    @GetMapping("/dashboard/gardener")
    public String page() {
        return "forward:/gardener.html";
    }

    @Component
    static class Nav implements AdminNavExtension {

        @Override
        public String label() {
            return "Knowledge gardener";
        }

        @Override
        public String path() {
            return "/dashboard/gardener";
        }

        @Override
        public String description() {
            return "Where newly ingested Layer 1 sources contradict or extend the reasoning modules.";
        }
    }
}
