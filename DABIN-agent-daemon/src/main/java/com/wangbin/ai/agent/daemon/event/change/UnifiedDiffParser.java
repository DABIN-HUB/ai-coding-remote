package com.wangbin.ai.agent.daemon.event.change;

import com.wangbin.ai.agent.contract.enums.FileChangeType;
import com.wangbin.ai.agent.daemon.config.AgentCodexProperties;
import com.wangbin.ai.agent.daemon.exception.AgentCapabilityException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Parses unified diff text for review metadata only. It never applies patches
 * or invokes Git, and all outgoing patch text is already redacted/truncated.
 */
@Component
public class UnifiedDiffParser {

    private static final String DIFF_GIT_PREFIX = "diff --git ";
    private static final String OLD_FILE_PREFIX = "--- ";
    private static final String NEW_FILE_PREFIX = "+++ ";
    private static final String RENAME_FROM_PREFIX = "rename from ";
    private static final String RENAME_TO_PREFIX = "rename to ";
    private static final String DEV_NULL = "/dev/null";
    private static final String REDACTED_PATCH = "# redacted sensitive file diff\n";
    private static final String DIFF_TRUNCATED_MARKER = "\n# diff truncated\n";
    private static final String PATCH_TRUNCATED_MARKER = "\n# patch truncated\n";
    private static final String FILES_TRUNCATED_MARKER = "\n# files truncated\n";
    private static final String SHA_256 = "SHA-256";

    private final WorkspaceRelativePathNormalizer pathNormalizer;
    private final SensitivePathPolicy sensitivePathPolicy;
    private final AgentCodexProperties properties;

    public UnifiedDiffParser(WorkspaceRelativePathNormalizer pathNormalizer,
                             SensitivePathPolicy sensitivePathPolicy,
                             AgentCodexProperties properties) {
        this.pathNormalizer = pathNormalizer;
        this.sensitivePathPolicy = sensitivePathPolicy;
        this.properties = properties;
    }

    public SanitizedDiff parse(Path workspace, String diff) {
        if (diff == null || diff.isBlank()) {
            return new SanitizedDiff("", sha256(""), false, false, 0, 0, 0, List.of());
        }
        List<String> blocks = splitBlocks(cleanControls(diff));
        List<UnifiedDiffFile> files = new ArrayList<>();
        StringBuilder visibleDiff = new StringBuilder();
        boolean filesTruncated = false;
        for (String block : blocks) {
            ParsedPatch parsedPatch = parseBlock(workspace, block);
            if (parsedPatch == null) {
                continue;
            }
            if (files.size() >= properties.getChangeSetMaxFiles()) {
                filesTruncated = true;
                continue;
            }
            files.add(parsedPatch.file());
            visibleDiff.append(parsedPatch.visiblePatch());
        }
        if (filesTruncated) {
            visibleDiff.append(FILES_TRUNCATED_MARKER);
        }
        String fullVisibleDiff = visibleDiff.toString();
        String diffSha256 = sha256(fullVisibleDiff);
        TruncatedText truncatedDiff = truncate(fullVisibleDiff, properties.getDiffSnapshotMaxChars(),
                DIFF_TRUNCATED_MARKER);
        int additions = files.stream().mapToInt(UnifiedDiffFile::additions).sum();
        int deletions = files.stream().mapToInt(UnifiedDiffFile::deletions).sum();
        return new SanitizedDiff(truncatedDiff.text(), diffSha256, truncatedDiff.truncated(), filesTruncated,
                files.size(), additions, deletions, files);
    }

    private List<String> splitBlocks(String diff) {
        String normalized = diff.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith(DIFF_GIT_PREFIX) && !current.isEmpty()) {
                blocks.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private ParsedPatch parseBlock(Path workspace, String block) {
        String oldPath = null;
        String newPath = null;
        String renameFrom = null;
        String renameTo = null;
        int additions = 0;
        int deletions = 0;
        boolean binary = false;
        String[] lines = block.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith(DIFF_GIT_PREFIX)) {
                String[] paths = line.substring(DIFF_GIT_PREFIX.length()).trim().split("\\s+", 2);
                if (paths.length > 0) {
                    oldPath = cleanDiffPath(paths[0]);
                }
                if (paths.length > 1) {
                    newPath = cleanDiffPath(paths[1]);
                }
            } else if (line.startsWith(RENAME_FROM_PREFIX)) {
                renameFrom = cleanDiffPath(line.substring(RENAME_FROM_PREFIX.length()));
            } else if (line.startsWith(RENAME_TO_PREFIX)) {
                renameTo = cleanDiffPath(line.substring(RENAME_TO_PREFIX.length()));
            } else if (line.startsWith(OLD_FILE_PREFIX)) {
                oldPath = cleanDiffPath(line.substring(OLD_FILE_PREFIX.length()));
            } else if (line.startsWith(NEW_FILE_PREFIX)) {
                newPath = cleanDiffPath(line.substring(NEW_FILE_PREFIX.length()));
            } else if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                binary = true;
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        if (renameFrom != null) {
            oldPath = renameFrom;
        }
        if (renameTo != null) {
            newPath = renameTo;
        }
        String rawPath = DEV_NULL.equals(newPath) || newPath == null ? oldPath : newPath;
        if (rawPath == null || DEV_NULL.equals(rawPath)) {
            return null;
        }
        String path = normalizeSafely(workspace, rawPath);
        if (path == null) {
            return null;
        }
        String normalizedOldPath = normalizeOldPath(workspace, oldPath, path);
        FileChangeType changeType = changeType(oldPath, newPath, normalizedOldPath, path);
        boolean redacted = sensitivePathPolicy.isSensitive(path) || sensitivePathPolicy.isSensitive(normalizedOldPath);
        String visiblePatch = redacted ? redactedPatch(path, normalizedOldPath) : block;
        String patchSha256 = sha256(visiblePatch);
        TruncatedText truncatedPatch = truncate(visiblePatch, properties.getFilePatchMaxChars(),
                PATCH_TRUNCATED_MARKER);
        UnifiedDiffFile file = new UnifiedDiffFile(path, normalizedOldPath, changeType, additions, deletions, binary,
                truncatedPatch.text(), patchSha256, truncatedPatch.truncated(), redacted);
        return new ParsedPatch(file, visiblePatch);
    }

    private String normalizeOldPath(Path workspace, String oldPath, String path) {
        if (oldPath == null || DEV_NULL.equals(oldPath)) {
            return null;
        }
        String normalized = normalizeSafely(workspace, oldPath);
        return path.equals(normalized) ? null : normalized;
    }

    private String normalizeSafely(Path workspace, String path) {
        try {
            return pathNormalizer.normalize(workspace, path);
        } catch (AgentCapabilityException ex) {
            return null;
        }
    }

    private FileChangeType changeType(String oldPath, String newPath, String normalizedOldPath, String path) {
        if (DEV_NULL.equals(oldPath)) {
            return FileChangeType.ADDED;
        }
        if (DEV_NULL.equals(newPath)) {
            return FileChangeType.DELETED;
        }
        if (normalizedOldPath != null && !normalizedOldPath.equals(path)) {
            return FileChangeType.RENAMED;
        }
        return FileChangeType.MODIFIED;
    }

    private String redactedPatch(String path, String oldPath) {
        String left = oldPath == null ? path : oldPath;
        return DIFF_GIT_PREFIX + "a/" + left + " b/" + path + '\n' + REDACTED_PATCH;
    }

    private String cleanDiffPath(String path) {
        if (path == null) {
            return null;
        }
        String cleaned = path.trim();
        int tab = cleaned.indexOf('\t');
        if (tab >= 0) {
            cleaned = cleaned.substring(0, tab);
        }
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.startsWith("a/") || cleaned.startsWith("b/")) {
            cleaned = cleaned.substring(2);
        }
        return cleaned;
    }

    private String cleanControls(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c >= ' ') {
                cleaned.append(c);
            } else {
                cleaned.append('?');
            }
        }
        return cleaned.toString();
    }

    private TruncatedText truncate(String value, int maxChars, String marker) {
        if (value == null || value.length() <= maxChars) {
            return new TruncatedText(value, false);
        }
        int end = Math.max(0, maxChars - marker.length());
        return new TruncatedText(value.substring(0, end) + marker, true);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record ParsedPatch(UnifiedDiffFile file, String visiblePatch) {
    }

    private record TruncatedText(String text, boolean truncated) {
    }
}
