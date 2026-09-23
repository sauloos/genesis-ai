package ai.genesisbrands.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/dashboard")
    public String dashboardPage() {
        return "forward:/dashboard.html";
    }

    @GetMapping("/dashboard/console")
    public String consolePage() { return "forward:/console.html"; }
    @GetMapping("/console")
    public String consoleRedirect() { return "redirect:/dashboard/console"; }

    @GetMapping("/dashboard/training")
    public String trainingPage() { return "forward:/training.html"; }
    @GetMapping("/training")
    public String trainingRedirect() { return "redirect:/dashboard/training"; }

    @GetMapping("/dashboard/questionnaires")
    public String questionnairesPage() { return "forward:/questionnaires.html"; }
    @GetMapping("/questionnaires")
    public String questionnairesRedirect() { return "redirect:/dashboard/questionnaires"; }

    @GetMapping("/dashboard/knowledge")
    public String knowledgePage() { return "forward:/knowledge.html"; }
    @GetMapping("/knowledge")
    public String knowledgeRedirect() { return "redirect:/dashboard/knowledge"; }

    @GetMapping("/dashboard/themes")
    public String themesPage() { return "forward:/themes.html"; }
    @GetMapping("/themes")
    public String themesRedirect() { return "redirect:/dashboard/themes"; }

    @GetMapping("/dashboard/agents")
    public String agentsPage() { return "forward:/agents.html"; }
    @GetMapping("/agents")
    public String agentsRedirect() { return "redirect:/dashboard/agents"; }

    @GetMapping("/login")
    public String loginPage() {
        return "forward:/login.html";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "forward:/register.html";
    }
}
