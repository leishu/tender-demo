package org.example.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;


/**
 * 启动时把 classpath 下 agentscope-workspace/ 的资源释放到 .agentscope/workspace。
 *
 * <p>总是覆盖 AGENTS.md 与 subagents/ 下的声明文件，保证开发阶段修改
 * src/main/resources 后重启即可生效；运行时生成的文件（MEMORY.md、sessions/
 * 等）不受影响。</p>
 */
@Component
@Slf4j
public class WorkspaceInitializer {

    private static final String CLASSPATH_PREFIX = "agentscope-workspace/";

    @Value("${agentscope.workspace-path}")
    private String workspacePath;

    @PostConstruct
    public void init() throws IOException {
        Path target = Paths.get(workspacePath);
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:" + CLASSPATH_PREFIX + "**");

        int copied = 0;
        for (Resource resource : resources) {
            if (!resource.isReadable() || resource.getFilename() == null) {
                continue;
            }
            String relativePath = resolveRelativePath(resource);
            if (relativePath == null || !shouldOverwrite(relativePath)) {
                continue;
            }
            Path dest = target.resolve(relativePath);
            Files.createDirectories(dest.getParent());
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        log.info("[WorkspaceInitializer] 释放 {} 个资源到 {}", copied, target.toAbsolutePath());
    }

    /**
     * 从资源 URL 中截取 agentscope-workspace/ 之后的相对路径。
     */
    private String resolveRelativePath(Resource resource) throws IOException {
        String path = resource.getURL().getPath();
        int idx = path.indexOf(CLASSPATH_PREFIX);
        if (idx < 0) {
            return null;
        }
        return path.substring(idx + CLASSPATH_PREFIX.length());
    }

    /**
     * 只覆盖声明类文件，保留运行时生成的文件。
     * 目前覆盖 AGENTS.md 和 subagents/ 下的所有文件。
     */
    private boolean shouldOverwrite(String relativePath) {
        return "AGENTS.md".equals(relativePath)
                || relativePath.startsWith("subagents/");
    }
}
