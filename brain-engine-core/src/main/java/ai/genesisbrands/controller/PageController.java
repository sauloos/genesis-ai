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

    @GetMapping("/dashboard/playground")
    public String playgroundPage() { return "forward:/playground.html"; }
    @GetMapping("/playground")
    public String playgroundRedirect() { return "redirect:/dashboard/playground"; }

    @GetMapping("/dashboard/questionnaires")
    public String questionnairesPage() { return "forward:/questionnaires.html"; }
    @GetMapping("/questionnaires")
    public String questionnairesRedirect() { return "redirect:/dashboard/questionnaires"; }

    @GetMapping("/dashboard/templates")
    public String templatesPage() { return "forward:/templates.html"; }
    @GetMapping("/templates")
    public String templatesRedirect() { return "redirect:/dashboard/templates"; }

    @GetMapping("/dashboard/knowledge")
    public String knowledgePage() { return "forward:/knowledge.html"; }
    @GetMapping("/knowledge")
    public String knowledgeRedirect() { return "redirect:/dashboard/knowledge"; }

    @GetMapping("/dashboard/themes")
    public String themesPage() { return "forward:/themes.html"; }
    @GetMapping("/themes")
    public String themesRedirect() { return "redirect:/dashboard/themes"; }

    @GetMapping("/questionnaire-run")
    public String questionnaireRunPage() {
        return "forward:/questionnaire-run.html";
    }

    @GetMapping("/discover")
    public String discoverPage() {
        return "forward:/questionnaire-run.html";
    }

    @GetMapping("/your-brand/{id}")
    public String yourBrandPage() {
        return "forward:/your-brand.html";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "forward:/login.html";
    }

    @GetMapping("/register")
    public String registerPage() {
        return "forward:/register.html";
    }
}
