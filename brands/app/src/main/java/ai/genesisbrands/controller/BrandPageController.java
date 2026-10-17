package ai.genesisbrands.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class BrandPageController {

    @GetMapping("/dashboard/templates")
    public String templatesPage() { return "forward:/templates.html"; }
    @GetMapping("/templates")
    public String templatesRedirect() { return "redirect:/dashboard/templates"; }

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
}
