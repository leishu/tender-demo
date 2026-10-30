package org.example.prompts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Component
public class CatalogPromptProvider {
    private final String template;

    public CatalogPromptProvider(@Value("classpath:prompts/catalog-generation.md") Resource resource) throws IOException {
        this.template = Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8);
    }

    public String render(Long tenderRequirementId) {
        return template.replace("{{id}}", String.valueOf(tenderRequirementId));
    }
}