package com.skillforge.workflow;

import com.skillforge.workflow.exception.WorkflowMetaException;
import org.mozilla.javascript.CompilerEnvirons;
import org.mozilla.javascript.Parser;
import org.mozilla.javascript.Token;
import org.mozilla.javascript.ast.ArrayLiteral;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.KeywordLiteral;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NumberLiteral;
import org.mozilla.javascript.ast.ObjectLiteral;
import org.mozilla.javascript.ast.ObjectProperty;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.UnaryExpression;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Task F: loads {@code classpath:workflows/*.workflow.js} definitions, parses
 * their {@code export const meta = {...}} block, and exposes them by name.
 *
 * <p><b>Meta parsing (FR-1.3):</b> {@code meta} MUST be a pure object literal.
 * The initializer AST is walked and any non-literal value (variable reference,
 * function call, template string, spread, computed property) causes the file to
 * be rejected with {@link WorkflowMetaException}. This lets {@code GET
 * /api/workflows} list definitions and the runtime statically validate phases
 * without executing the script.
 *
 * <p><b>Hot-reload (V1):</b> a startup scan plus an explicit {@link #reloadAll()}
 * method. A real-time {@code WatchService} is deferred — V1 only needs {@code GET
 * /api/workflows} to list definitions and {@code startRun} to look one up.
 *
 * <p><b>sourceHash:</b> SHA-256 of the original file source, recorded so Sprint-2
 * resume can refuse to replay against a changed definition (plan §3.3 #6).
 */
@Component
public class WorkflowDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionRegistry.class);
    private static final String LOCATION_PATTERN = "classpath*:workflows/*.workflow.js";

    /**
     * Atomically-swapped immutable snapshot (java W1). reloadAll() builds a fresh
     * map and replaces this reference in a single {@code volatile} write, so a
     * concurrent {@link #findByName}/{@link #listAll} never observes a
     * partially-cleared map (the old {@code clear()}+{@code putAll()} race could
     * return empty mid-reload → spurious WorkflowNotFoundException).
     */
    private volatile Map<String, WorkflowDefinition> byName = Map.of();

    @PostConstruct
    public void init() {
        try {
            reloadAll();
        } catch (RuntimeException e) {
            // A malformed workflow file must not block application startup; log and
            // continue with whatever parsed cleanly.
            log.error("WorkflowDefinitionRegistry: initial scan failed: {}", e.getMessage(), e);
        }
    }

    /** Re-scans the classpath and replaces the registry contents (V1 hot-reload). */
    public void reloadAll() {
        Map<String, WorkflowDefinition> fresh = new LinkedHashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(LOCATION_PATTERN);
        } catch (IOException e) {
            log.warn("WorkflowDefinitionRegistry: no workflow resources scanned: {}", e.getMessage());
            return;
        }
        for (Resource r : resources) {
            String fileName = r.getFilename();
            try {
                String source = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                WorkflowDefinition def = parse(fileName, source);
                if (fresh.containsKey(def.name())) {
                    log.warn("WorkflowDefinitionRegistry: duplicate workflow name '{}' in {} — keeping first",
                            def.name(), fileName);
                    continue;
                }
                fresh.put(def.name(), def);
                log.info("WorkflowDefinitionRegistry: loaded workflow '{}' from {} ({} phases)",
                        def.name(), fileName, def.phases().size());
            } catch (Exception e) {
                log.error("WorkflowDefinitionRegistry: failed to parse {}: {}", fileName, e.getMessage());
            }
        }
        // Single atomic publish: readers see either the old or the new snapshot,
        // never an intermediate empty map.
        this.byName = Map.copyOf(fresh);
    }

    public Optional<WorkflowDefinition> findByName(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<WorkflowDefinition> listAll() {
        return List.copyOf(byName.values());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Parsing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parses one workflow file. Visible for testing.
     *
     * @param fileName logical name (for error messages / AST source uri)
     * @param source   the raw file source
     */
    public WorkflowDefinition parse(String fileName, String source) {
        if (source == null || source.isBlank()) {
            throw new WorkflowMetaException(fileName + ": empty workflow source");
        }
        String sourceHash = sha256(source);

        // Rhino 1.7.14 cannot parse `export`; strip it (string/comment aware).
        String body = JsKeywordStripper.strip(source, "export");

        // Workflow bodies use a top-level `return` (illegal at Rhino script top
        // level), so wrap in a function purely for PARSING/meta extraction. AST
        // positions are mapped back to `body` by subtracting the prefix length.
        final String wrapPrefix = "function __wf(){\n";
        String wrapped = wrapPrefix + body + "\n}";

        CompilerEnvirons env = new CompilerEnvirons();
        env.setLanguageVersion(org.mozilla.javascript.Context.VERSION_ES6);
        env.setRecordingComments(false);
        AstRoot root;
        try {
            root = new Parser(env).parse(wrapped, fileName, 1);
        } catch (RuntimeException e) {
            throw new WorkflowMetaException(fileName + ": JS parse error: " + e.getMessage());
        }
        final int prefixLen = wrapPrefix.length();

        VariableDeclaration[] metaDecl = {null};
        VariableInitializer[] metaInit = {null};
        root.visit(node -> {
            if (metaInit[0] != null) {
                return false;
            }
            if (node instanceof VariableDeclaration vd) {
                for (VariableInitializer vi : vd.getVariables()) {
                    if (vi.getTarget() instanceof Name nm && "meta".equals(nm.getIdentifier())) {
                        metaDecl[0] = vd;
                        metaInit[0] = vi;
                        return false;
                    }
                }
            }
            return true;
        });

        if (metaInit[0] == null) {
            throw new WorkflowMetaException(fileName + ": missing `export const meta = {...}` declaration");
        }
        AstNode initializer = metaInit[0].getInitializer();
        if (!(initializer instanceof ObjectLiteral metaObj)) {
            throw new WorkflowMetaException(fileName + ": meta must be an object literal");
        }
        requirePureLiteral(fileName, metaObj);

        String name = readStringProp(metaObj, "name");
        String description = readStringProp(metaObj, "description");
        if (name == null || name.isBlank()) {
            throw new WorkflowMetaException(fileName + ": meta.name is required");
        }
        if (description == null) {
            description = "";
        }
        List<WorkflowDefinition.WorkflowPhase> phases = readPhases(metaObj);

        // Remove the meta declaration so WorkflowEvaluator gets a meta-free body.
        // AST positions are relative to the wrapped source; map back to `body`.
        String jsBody = removeNodeText(body, metaDecl[0], prefixLen);

        return new WorkflowDefinition(name, description, phases, jsBody, sourceHash);
    }

    /**
     * Rejects any non-pure-literal value inside the meta object (FR-1.3): only
     * string/number/boolean/null literals, nested object literals, array
     * literals, and negative-number unary expressions are allowed.
     */
    private void requirePureLiteral(String fileName, AstNode node) {
        if (node instanceof ObjectLiteral obj) {
            for (ObjectProperty prop : obj.getElements()) {
                AstNode key = prop.getLeft();
                // Computed keys ([expr]) and spreads are not plain Name/StringLiteral keys.
                if (!(key instanceof Name) && !(key instanceof StringLiteral) && !(key instanceof NumberLiteral)) {
                    throw new WorkflowMetaException(
                            fileName + ": meta has a non-literal/computed property key");
                }
                requirePureLiteral(fileName, prop.getRight());
            }
            return;
        }
        if (node instanceof ArrayLiteral arr) {
            for (AstNode el : arr.getElements()) {
                requirePureLiteral(fileName, el);
            }
            return;
        }
        if (node instanceof StringLiteral || node instanceof NumberLiteral) {
            return;
        }
        if (node instanceof KeywordLiteral kw) {
            int t = kw.getType();
            if (t == Token.TRUE || t == Token.FALSE || t == Token.NULL) {
                return;
            }
            throw new WorkflowMetaException(fileName + ": meta contains a non-literal keyword");
        }
        if (node instanceof UnaryExpression unary && unary.getOperator() == Token.NEG
                && unary.getOperand() instanceof NumberLiteral) {
            return; // negative numeric literal
        }
        throw new WorkflowMetaException(
                fileName + ": meta is not a pure literal (found "
                        + node.getClass().getSimpleName()
                        + " — variables/function calls/template strings/spreads are forbidden)");
    }

    private String readStringProp(ObjectLiteral obj, String key) {
        for (ObjectProperty prop : obj.getElements()) {
            if (key.equals(propKey(prop)) && prop.getRight() instanceof StringLiteral s) {
                return s.getValue();
            }
        }
        return null;
    }

    private List<WorkflowDefinition.WorkflowPhase> readPhases(ObjectLiteral obj) {
        List<WorkflowDefinition.WorkflowPhase> phases = new ArrayList<>();
        for (ObjectProperty prop : obj.getElements()) {
            if ("phases".equals(propKey(prop)) && prop.getRight() instanceof ArrayLiteral arr) {
                for (AstNode el : arr.getElements()) {
                    if (el instanceof ObjectLiteral phaseObj) {
                        String title = readStringProp(phaseObj, "title");
                        String detail = readStringProp(phaseObj, "detail");
                        phases.add(new WorkflowDefinition.WorkflowPhase(
                                title == null ? "" : title, detail));
                    }
                }
            }
        }
        return phases;
    }

    private static String propKey(ObjectProperty prop) {
        AstNode key = prop.getLeft();
        if (key instanceof Name n) {
            return n.getIdentifier();
        }
        if (key instanceof StringLiteral s) {
            return s.getValue();
        }
        return null;
    }

    private static String removeNodeText(String body, VariableDeclaration decl, int prefixLen) {
        if (decl == null) {
            return body;
        }
        int start = decl.getAbsolutePosition() - prefixLen;
        int end = start + decl.getLength();
        if (start < 0 || end > body.length() || start >= end) {
            return body;
        }
        return body.substring(0, start) + body.substring(end);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
