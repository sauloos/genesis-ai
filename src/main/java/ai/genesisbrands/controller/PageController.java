package ai.genesisbrands.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/console")
    public String consolePage() {
        return "forward:/console.html";
    }

    @GetMapping("/training")
    public String trainingPage() {
        return "forward:/training.html";
    }

    @GetMapping("/playground")
    public String playgroundPage() {
        return "forward:/playground.html";
    }

    @GetMapping("/questionnaires")
    public String questionnairesPage() {
        return "forward:/questionnaires.html";
    }

    @GetMapping("/questionnaire-run")
    public String questionnaireRunPage() {
        return "forward:/questionnaire-run.html";
    }
}
