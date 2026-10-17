package ai.genesisbrands.controller;

import ai.genesisbrands.platform.AdminNavExtension;
import org.springframework.stereotype.Component;

@Component
public class TemplatesNavExtension implements AdminNavExtension {

    @Override
    public String label() {
        return "Templates";
    }

    @Override
    public String path() {
        return "/dashboard/templates";
    }

    @Override
    public String description() {
        return "Define and version the structure of playbooks and brand books. Control what the assembly agents produce.";
    }

    @Override
    public int order() {
        return 200;
    }
}
