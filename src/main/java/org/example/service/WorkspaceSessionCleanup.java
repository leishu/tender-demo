package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 清理 AgentScope workspace 下的会话记录：
 * {@code .agentscope/workspace/<userId>/agents/<agentId>/sessions/}。
 *
 * <p>为什么需要它：{@code TranscriptMiddleware} 的注册条件只有 {@code !disableTranscript}，
 * 与 memory 无关。项目调用了 {@code disableMemoryHooks()}，内置的
 * {@code MemoryMaintenanceMiddleware#pruneOldSessions} 不会注册，因此
 * {@code sessions/*.jsonl} 与 {@code sessions/*.log.jsonl} 会一直增长。
 * 即使关闭 memory，这里仍然需要清理。</p>
 *
 * <p>清理策略：</p>
 * <ul>
 *   <li>按“会话组”计数，而不是按文件计数。同一 sessionId 的
 *       {@code <sessionId>.jsonl} 与 {@code <sessionId>.log.jsonl} 视为一组，一起删除；</li>
 *   <li>组的时间取组内文件的最新 {@code lastModified}；</li>
 *   <li>超过 {@code retain-days} 的组删除；剩余组数超过
 *       {@code max-sessions-per-agent} 时，从最旧的开始删；</li>
 *   <li>最近 {@code active-protection-minutes} 内修改过的组一律跳过，避免删掉正在写入的会话；</li>
 *   <li>不修改 {@code sessions.json}。框架把它当作 best-effort 索引，内置清理同样不更新它，
 *       而且它由 {@code WorkspaceManager.updateSessionIndex} 用进程内锁读改写，外部改写会竞争。</li>
 * </ul>
 *
 * <p>可选：{@code cleanup-events=true} 时，删除会话组的同时删除
 * {@code <userId>/default/<agentId>/<sessionId>/}（{@code FilesystemTranscriptStore} 写入的
 * {@code events/*.jsonl}）。默认关闭，因为它不在 {@code sessions/} 目录内。</p>
 */
@Component
@Slf4j
public class WorkspaceSessionCleanup {

    private static final String SESSIONS_DIR = "sessions";
    private static final String AGENTS_DIR = "agents";
    private static final String DEFAULT_DIR = "default";
    private static final String SESSION_INDEX = "sessions.json";
    private static final String TRANSCRIPT_SUFFIX = ".jsonl";
    private static final String LOG_SUFFIX = ".log.jsonl";

    /** 从 workspace 根开始最多下探 4 层：<userId>/agents/<agentId>/sessions */
    private static final int MAX_WALK_DEPTH = 4;

    private final String workspacePath;
    private final boolean enabled;
    private final int retainDays;
    private final int maxSessionsPerAgent;
    private final int activeProtectionMinutes;
    private final boolean cleanupEvents;

    public WorkspaceSessionCleanup(
            @Value("${agentscope.workspace-path}") String workspacePath,
            @Value("${agentscope.workspace-cleanup.enabled:true}") boolean enabled,
            @Value("${agentscope.workspace-cleanup.retain-days:30}") int retainDays,
            @Value("${agentscope.workspace-cleanup.max-sessions-per-agent:200}") int maxSessionsPerAgent,
            @Value("${agentscope.workspace-cleanup.active-protection-minutes:10}") int activeProtectionMinutes,
            @Value("${agentscope.workspace-cleanup.cleanup-events:false}") boolean cleanupEvents) {
        this.workspacePath = workspacePath;
        this.enabled = enabled;
        this.retainDays = retainDays;
        this.maxSessionsPerAgent = maxSessionsPerAgent;
        this.activeProtectionMinutes = activeProtectionMinutes;
        this.cleanupEvents = cleanupEvents;
    }

    /** 启动后执行一次，清掉上次运行遗留的过期会话。 */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOnStartup() {
        runOnce();
    }

    /** 定时清理；cron 可通过 {@code agentscope.workspace-cleanup.cron} 覆盖。 */
    @Scheduled(cron = "${agentscope.workspace-cleanup.cron:0 30 3 * * *}")
    public void scheduledCleanup() {
        runOnce();
    }

    /**
     * 执行一次清理。任何异常都只记录日志，不向调用方抛出，避免定时任务被终止。
     *
     * @return 本次清理的统计结果
     */
    public CleanupResult runOnce() {
        try {
            return doRunOnce();
        } catch (Exception e) {
            log.error("[WorkspaceSessionCleanup] 清理失败，本次跳过", e);
            return CleanupResult.EMPTY;
        }
    }

    private CleanupResult doRunOnce() {
        if (!enabled) {
            log.debug("[WorkspaceSessionCleanup] 已关闭，跳过");
            return CleanupResult.EMPTY;
        }
        Path root = Paths.get(workspacePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("[WorkspaceSessionCleanup] workspace 目录不存在，跳过: {}", root);
            return CleanupResult.EMPTY;
        }

        long now = System.currentTimeMillis();
        boolean timeEnabled = retainDays > 0;
        boolean countEnabled = maxSessionsPerAgent > 0;
        if (!timeEnabled && !countEnabled) {
            log.debug("[WorkspaceSessionCleanup] 时间与数量限制均为 0，跳过");
            return CleanupResult.EMPTY;
        }
        long expireBefore = now - Duration.ofDays(retainDays).toMillis();
        long protectAfter = now - Duration.ofMinutes(Math.max(activeProtectionMinutes, 0)).toMillis();

        int scannedDirs = 0;
        int scannedGroups = 0;
        int deletedGroups = 0;
        int deletedFiles = 0;
        int failedDeletions = 0;
        long freedBytes = 0L;

        for (Path sessionsDir : findSessionDirs(root)) {
            scannedDirs++;
            List<SessionGroup> groups;
            try {
                groups = groupBySession(sessionsDir);
            } catch (IOException e) {
                log.warn("[WorkspaceSessionCleanup] 读取会话目录失败: {}", sessionsDir, e);
                continue;
            }
            if (groups.isEmpty()) {
                continue;
            }
            scannedGroups += groups.size();
            groups.sort(Comparator.comparingLong(SessionGroup::lastModified)
                    .thenComparing(SessionGroup::sessionId));

            int remaining = groups.size();
            for (SessionGroup group : groups) {
                // 组按时间从旧到新排序，遇到活跃组即可停止：后面的只会更新
                if (group.lastModified() >= protectAfter) {
                    break;
                }
                boolean expired = timeEnabled && group.lastModified() < expireBefore;
                boolean overflow = countEnabled && remaining > maxSessionsPerAgent;
                if (!expired && !overflow) {
                    break;
                }
                DeleteOutcome outcome = deleteGroup(sessionsDir, group);
                deletedFiles += outcome.deletedFiles();
                failedDeletions += outcome.failedFiles();
                freedBytes += outcome.freedBytes();
                if (outcome.completelyDeleted()) {
                    deletedGroups++;
                    remaining--;
                    if (cleanupEvents) {
                        deleteEventsDir(sessionsDir, group.sessionId());
                    }
                }
            }

            if (countEnabled && remaining > maxSessionsPerAgent) {
                log.info("[WorkspaceSessionCleanup] {} 仍有 {} 个会话，超过上限 {}，"
                                + "但其余会话处于活跃保护期（{} 分钟）内，留待下次清理",
                        sessionsDir, remaining, maxSessionsPerAgent, activeProtectionMinutes);
            }
        }

        CleanupResult result = new CleanupResult(
                scannedDirs, scannedGroups, deletedGroups, deletedFiles, failedDeletions, freedBytes);
        if (deletedGroups > 0 || failedDeletions > 0) {
            log.info("[WorkspaceSessionCleanup] 清理完成: {}", result);
        } else {
            log.debug("[WorkspaceSessionCleanup] 无需清理: {}", result);
        }
        return result;
    }

    /** 找出所有 {@code <workspace>/<userId>/agents/<agentId>/sessions} 目录。 */
    private List<Path> findSessionDirs(Path root) {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_WALK_DEPTH)) {
            stream.filter(Files::isDirectory)
                    .filter(path -> SESSIONS_DIR.equals(path.getFileName().toString()))
                    // <userId>/agents/<agentId>/sessions -> 祖父目录必须是 agents
                    .filter(path -> path.getParent() != null
                            && path.getParent().getParent() != null
                            && AGENTS_DIR.equals(path.getParent().getParent().getFileName().toString()))
                    .forEach(dirs::add);
        } catch (IOException e) {
            log.warn("[WorkspaceSessionCleanup] 遍历 workspace 失败: {}", root, e);
        }
        return dirs;
    }

    /**
     * 把目录下的 {@code *.jsonl} 按 sessionId 分组。
     * {@code sessions.json} 不匹配 {@code .jsonl}，天然被排除。
     */
    private List<SessionGroup> groupBySession(Path sessionsDir) throws IOException {
        Map<String, List<Path>> grouped = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(TRANSCRIPT_SUFFIX))
                    .forEach(path -> grouped
                            .computeIfAbsent(baseSessionId(path.getFileName().toString()),
                                    key -> new ArrayList<>())
                            .add(path));
        }
        return new ArrayList<>(grouped.entrySet().stream()
                .map(entry -> new SessionGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList());
    }

    /**
     * 去掉文件名里的 transcript 后缀，得到会话 ID。
     * 先判断更长的 {@code .log.jsonl}，否则 {@code <id>.log.jsonl} 会被截成 {@code <id>.log}。
     */
    private static String baseSessionId(String fileName) {
        if (fileName.endsWith(LOG_SUFFIX)) {
            return fileName.substring(0, fileName.length() - LOG_SUFFIX.length());
        }
        return fileName.substring(0, fileName.length() - TRANSCRIPT_SUFFIX.length());
    }

    private DeleteOutcome deleteGroup(Path sessionsDir, SessionGroup group) {
        int deleted = 0;
        int failed = 0;
        long freed = 0L;
        for (Path file : group.files()) {
            long size = sizeOf(file);
            try {
                if (Files.deleteIfExists(file)) {
                    deleted++;
                    freed += size;
                }
            } catch (IOException e) {
                failed++;
                log.warn("[WorkspaceSessionCleanup] 删除会话文件失败: {}", file, e);
            }
        }
        if (deleted > 0) {
            log.debug("[WorkspaceSessionCleanup] 删除会话 {}: {} 个文件, {} 字节",
                    group.sessionId(), deleted, freed);
        }
        return new DeleteOutcome(deleted, failed, freed);
    }

    /**
     * 删除同一会话在 {@code default/<agentId>/<sessionId>/} 下的 events 目录。
     * 失败只记日志，不影响 transcript 的清理结果。
     */
    private void deleteEventsDir(Path sessionsDir, String sessionId) {
        // <userId>/agents/<agentId>/sessions -> <userId>/default/<agentId>/<sessionId>
        Path agentDir = sessionsDir.getParent();
        if (agentDir == null || agentDir.getParent() == null
                || agentDir.getParent().getParent() == null) {
            return;
        }
        // <userId>/agents/<agentId>/sessions -> <userId>
        Path userDir = agentDir.getParent().getParent();
        Path eventsSessionDir = userDir.resolve(DEFAULT_DIR)
                .resolve(agentDir.getFileName())
                .resolve(sessionId);
        if (!Files.isDirectory(eventsSessionDir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(eventsSessionDir)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            log.debug("[WorkspaceSessionCleanup] 删除 events 目录: {}", eventsSessionDir);
        } catch (IOException e) {
            log.warn("[WorkspaceSessionCleanup] 删除 events 目录失败: {}", eventsSessionDir, e);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 同一 sessionId 的一组文件；时间取组内最新修改时间。 */
    private record SessionGroup(String sessionId, List<Path> files, long lastModified) {
        SessionGroup(String sessionId, List<Path> files) {
            this(sessionId, files, files.stream()
                    .mapToLong(WorkspaceSessionCleanup::lastModified)
                    .max()
                    .orElse(0L));
        }
    }

    private record DeleteOutcome(int deletedFiles, int failedFiles, long freedBytes) {
        boolean completelyDeleted() {
            return failedFiles == 0 && deletedFiles > 0;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 一次清理的统计结果。 */
    public record CleanupResult(
            int scannedDirectories,
            int scannedSessions,
            int deletedSessions,
            int deletedFiles,
            int failedDeletions,
            long freedBytes) {

        static final CleanupResult EMPTY = new CleanupResult(0, 0, 0, 0, 0, 0L);
    }
}
