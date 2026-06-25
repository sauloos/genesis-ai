package com.genesisai.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/training")
    public String trainingPage() {
        return "forward:/training.html";
    }
}
