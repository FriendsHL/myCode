package com.skillforge.server.security.skill;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class SkillSecurityScanner {

    private static final int MAX_EXCERPT_CHARS = 200;

    private static final Pattern PIPE_TO_SHELL = Pattern.compile(
            "\\b(?:curl|wget)\\b[^\\n|]*\\|\\s*(?:sudo\\s+)?(?:sh|bash|zsh)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_THEN_EXEC = Pattern.compile(
            "\\b(?:curl|wget)\\b[^\\n]*(?:-o|--output-document=?)\\s+([^\\s;&|]+).*"
                    + "(?:&&|;)\\s*(?:chmod\\s+\\+x\\s+\\1\\s*(?:&&|;)\\s*)?\\1\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "\\b(?:ignore previous instructions|disregard prior instructions|new system instructions|"
                    + "ignore all previous|system instructions:)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODED_PAYLOAD = Pattern.compile(
            "(?:[A-Za-z0-9+/]{120,}={0,2}|(?:\\\\x[0-9a-fA-F]{2}){16,}|(?:\\\\u[0-9a-fA-F]{4}){12,})");
    private static final Pattern SECRET_EXFIL = Pattern.compile(
            "(?:~/(?:\\.aws/credentials|\\.ssh/id_rsa|\\.ssh/authorized_keys)|/etc/passwd|"
                    + "AWS_SECRET_ACCESS_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|api[_-]?key|token|secret|"
                    + "\\benv\\b|\\bprintenv\\b).*\\b(?:curl|wget)\\b.*(?:-d|--data|--post-data|@-)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "\\b([A-Z0-9_]*(?:SECRET|TOKEN|KEY|PASSWORD)[A-Z0-9_]*)\\s*=\\s*([^\\s'\\\"]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\brm\\s+-rf\\s+(?:/|\\$HOME|~)(?:\\s|$)",
            Pattern.CASE_INSENSITIVE);

    private final SkillSecurityScanProperties properties;

    public SkillSecurityScanner(SkillSecurityScanProperties properties) {
        this.properties = properties;
    }

    public SkillScanResult scan(Path sourceRoot) {
        if (!properties.isEnabled()) {
            return SkillScanResult.allow();
        }
        List<SkillScanFinding> findings = new ArrayList<>();
        long[] totalBytes = {0L};
        try {
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(sourceRoot) && Files.isSymbolicLink(dir)) {
                        findings.add(finding(SkillScanSeverity.MEDIUM, "SF-SCAN-SYMLINK",
                                sourceRoot, dir, 1, "Skill package contains a symlinked directory.",
                                dir.getFileName().toString()));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(file)) {
                        findings.add(finding(SkillScanSeverity.MEDIUM, "SF-SCAN-SYMLINK",
                                sourceRoot, file, 1, "Skill package contains a symlinked file.",
                                file.getFileName().toString()));
                        return FileVisitResult.CONTINUE;
                    }
                    if (!shouldScan(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    long size = Files.size(file);
                    if (size > properties.getMaxFileBytes()) {
                        findings.add(finding(SkillScanSeverity.MEDIUM, "SF-SCAN-FILE-TOO-LARGE",
                                sourceRoot, file, 1, "File exceeds the per-file security scan byte limit.",
                                file.getFileName().toString()));
                        return FileVisitResult.CONTINUE;
                    }
                    if (totalBytes[0] + size > properties.getMaxTotalBytes()) {
                        findings.add(finding(SkillScanSeverity.MEDIUM, "SF-SCAN-TOTAL-BYTES-EXCEEDED",
                                sourceRoot, file, 1, "Skill package exceeds the total security scan byte limit.",
                                file.getFileName().toString()));
                        return FileVisitResult.TERMINATE;
                    }
                    totalBytes[0] += size;
                    scanTextFile(sourceRoot, file, findings);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            findings.add(new SkillScanFinding(SkillScanSeverity.HIGH, "SF-SCAN-ERROR",
                    ".", 1, "Security scanner could not read the skill package.",
                    sanitizeExcerpt(e.getMessage())));
        }
        return toResult(findings);
    }

    public boolean allowMediumRiskByDefault() {
        return properties.isAllowMediumRiskByDefault();
    }

    private void scanTextFile(Path sourceRoot, Path file, List<SkillScanFinding> findings) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return;
        } catch (IOException e) {
            findings.add(finding(SkillScanSeverity.MEDIUM, "SF-SCAN-FILE-READ-FAILED",
                    sourceRoot, file, 1, "Security scanner could not read a text file.",
                    e.getMessage()));
            return;
        }

        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int lineNo = i + 1;
            if (PIPE_TO_SHELL.matcher(line).find() || DOWNLOAD_THEN_EXEC.matcher(line).find()) {
                findings.add(finding(SkillScanSeverity.HIGH, "SF-SCAN-SHELL-PIPE-EXEC",
                        sourceRoot, file, lineNo,
                        "Remote download is piped directly into a shell interpreter.",
                        line));
            }
            if (SECRET_EXFIL.matcher(line).find()) {
                findings.add(finding(SkillScanSeverity.HIGH, "SF-SCAN-SECRET-EXFIL",
                        sourceRoot, file, lineNo,
                        "Sensitive files or environment data appear to be sent over the network.",
                        context(lines, i)));
            }
            if (DESTRUCTIVE.matcher(line).find()) {
                findings.add(finding(SkillScanSeverity.HIGH, "SF-SCAN-DESTRUCTIVE-COMMAND",
                        sourceRoot, file, lineNo,
                        "Destructive command targets the filesystem root or home directory.",
                        line));
            }
            if (fileName.equals("skill.md") && PROMPT_INJECTION.matcher(line).find()) {
                SkillScanSeverity severity = containsSecretOrNetworkIntent(line)
                        ? SkillScanSeverity.HIGH : SkillScanSeverity.MEDIUM;
                findings.add(finding(severity, "SF-SCAN-PROMPT-INJECTION",
                        sourceRoot, file, lineNo,
                        "Skill text contains instruction-override language.",
                        line));
            }
            if (ENCODED_PAYLOAD.matcher(line).find() && hasExecutionContext(line)) {
                findings.add(finding(SkillScanSeverity.MEDIUM, "SF-SCAN-ENCODED-PAYLOAD",
                        sourceRoot, file, lineNo,
                        "Encoded payload appears near execution or network context.",
                        line));
            }
            if (SECRET_ASSIGNMENT.matcher(line).find()) {
                findings.add(finding(SkillScanSeverity.MEDIUM, "SF-SCAN-SECRET-LITERAL",
                        sourceRoot, file, lineNo,
                        "File contains a secret-like literal.",
                        line));
            }
        }
    }

    private static boolean shouldScan(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("skill.md")
                || name.equals("_meta.json")
                || name.equals("package.json")
                || name.equals("requirements.txt")
                || name.equals("pyproject.toml")
                || name.endsWith(".sh")
                || name.endsWith(".bash")
                || name.endsWith(".zsh")
                || name.endsWith(".py")
                || name.endsWith(".js")
                || name.endsWith(".ts")
                || name.endsWith(".mjs")
                || name.endsWith(".cjs");
    }

    private static SkillScanResult toResult(List<SkillScanFinding> findings) {
        if (findings.isEmpty()) {
            return SkillScanResult.allow();
        }
        SkillScanSeverity highest = findings.stream()
                .map(SkillScanFinding::severity)
                .max(Comparator.comparingInt(SkillSecurityScanner::severityRank))
                .orElse(SkillScanSeverity.LOW);
        SkillScanDecision decision = highest == SkillScanSeverity.LOW
                ? SkillScanDecision.ALLOW_WITH_WARNINGS
                : SkillScanDecision.BLOCK;
        return new SkillScanResult(decision, highest, findings);
    }

    private static int severityRank(SkillScanSeverity severity) {
        return switch (severity) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    private static boolean containsSecretOrNetworkIntent(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("secret")
                || lower.contains("credential")
                || lower.contains("token")
                || lower.contains("~/.ssh")
                || lower.contains("~/.aws")
                || lower.contains("curl")
                || lower.contains("http");
    }

    private static boolean hasExecutionContext(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("eval")
                || lower.contains("exec")
                || lower.contains("bash")
                || lower.contains(" sh")
                || lower.contains("curl")
                || lower.contains("http");
    }

    private static String context(List<String> lines, int index) {
        int start = Math.max(0, index - 1);
        int end = Math.min(lines.size() - 1, index + 1);
        return String.join(" ", lines.subList(start, end + 1));
    }

    private static SkillScanFinding finding(SkillScanSeverity severity, String ruleId,
                                            Path sourceRoot, Path file, int line,
                                            String message, String excerpt) {
        String relative = sourceRoot.relativize(file).toString();
        return new SkillScanFinding(severity, ruleId, relative, line, message, sanitizeExcerpt(excerpt));
    }

    private static String sanitizeExcerpt(String raw) {
        if (raw == null) {
            return "";
        }
        String redacted = SECRET_ASSIGNMENT.matcher(raw).replaceAll("$1=<redacted>");
        redacted = redacted.replaceAll("(?i)(api[_-]?key|token|secret|password)=([^\\s'\\\"]+)",
                "$1=<redacted>");
        redacted = redacted.replace('\n', ' ').replace('\r', ' ').trim();
        if (redacted.length() <= MAX_EXCERPT_CHARS) {
            return redacted;
        }
        return redacted.substring(0, MAX_EXCERPT_CHARS);
    }
}
