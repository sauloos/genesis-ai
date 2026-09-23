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
}
