package com.skillforge.workflow.sandbox;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Removes dangerous global bindings from a freshly-created sandbox scope
 * (plan §4). Belt-and-suspenders on top of {@link org.mozilla.javascript.ClassShutter}
 * (returns {@code false} for every class) and {@code initSafeStandardObjects()}
 * (omits {@code Packages}/{@code java} and the Java-bridge globals).
 *
 * <p>Sprint-1 spike scope: covers §4 vectors #1-#8 + #13 reachable via global
 * bindings, plus the §4 #5/#7 {@code Function}-constructor escape. {@code while(true)}
 * (#9) / deep recursion (#10) are handled by the instruction observer +
 * stack-depth cap in {@link L1SandboxFactory}.
 */
public final class ScopeScrubber {

    /**
     * Global bindings deleted from the sandbox scope. Java-bridge entry points
     * (#1-#4, #8), dynamic-eval (#5), and Rhino-shell-only globals (#13).
     * {@code initSafeStandardObjects()} already omits most of these in embedded
     * mode; deletion is defensive in case a host injects them.
     */
    private static final String[] DANGEROUS_BINDINGS = {
            "Packages", "java", "javax", "org", "com", "edu", "net",
            "JavaAdapter", "JavaImporter", "importClass", "importPackage",
            "getClass",
            "eval",
            "load", "loadClass", "readFile", "readUrl", "spawn", "runCommand",
            "quit", "print", "defineClass",
    };

    /**
     * §4 #5/#7: neutralise the {@code Function} constructor reachable both via the
     * global binding ({@code new Function("...")}) and via the prototype chain
     * ({@code this.constructor.constructor("...")} / {@code (function(){}).constructor("...")}).
     * Runs as a privileged JS bootstrap so the replacement is a real JS throwing
     * function (clean {@code TypeError}); doing this via Java {@code putProperty}
     * on the function prototype instead corrupts interpreter state (NPE).
     *
     * <p>Critically this does NOT break function literals or arrow functions
     * (compiled at parse time, not via the constructor) — verified in the spike.
     */
    private static final String FUNCTION_CONSTRUCTOR_BOOTSTRAP =
            "(function(){"
          + "  var blocked = function(){ throw new TypeError('Function constructor is disabled in workflow sandbox'); };"
          + "  Object.getPrototypeOf(function(){}).constructor = blocked;"
          + "  this.Function = blocked;"
          + "})();";

    private ScopeScrubber() {
    }

    /**
     * Scrubs the scope in place. Deletes the dangerous global bindings then runs
     * the privileged Function-constructor neutralisation bootstrap.
     */
    public static void scrub(Context cx, Scriptable scope) {
        for (String name : DANGEROUS_BINDINGS) {
            ScriptableObject.deleteProperty(scope, name);
        }
        cx.evaluateString(scope, FUNCTION_CONSTRUCTOR_BOOTSTRAP,
                "workflow-sandbox-bootstrap", 1, null);
    }
}
