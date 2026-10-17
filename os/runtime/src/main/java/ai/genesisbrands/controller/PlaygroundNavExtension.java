package ai.genesisbrands.controller;

import ai.genesisbrands.platform.AdminNavExtension;
import org.springframework.stereotype.Component;

@Component
public class PlaygroundNavExtension implements AdminNavExtension {

    @Override
    public String label() {
        return "Playground";
    }

    @Override
    public String path() {
        return "/dashboard/playground";
    }

    @Override
    public String description() {
        return "Test specialist agents with custom prompts and brand context. Experiment before running an engagement.";
    }
}
