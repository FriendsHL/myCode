package com.skillforge.workflow.bindings;

import com.skillforge.workflow.WorkflowAgentInvoker;
import com.skillforge.workflow.WorkflowContext;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Registers the workflow host primitives + {@code args} onto a sandbox scope.
 * Registers {@code agent} / {@code parallel} / {@code pipeline} / {@code phase} /
 * {@code log} + {@code args}. {@code humanApprove} / {@code ctx} land in Sprint 2.
 */
public final class HostBindings {

    private HostBindings() {
    }

    public static void register(Context cx, Scriptable scope, WorkflowContext ctx,
                                WorkflowAgentInvoker invoker, ExecutorService subAgentExecutor) {
        define(scope, "agent", new HostAgent(ctx, invoker, subAgentExecutor));
        define(scope, "parallel", new HostParallel(ctx));
        define(scope, "pipeline", new HostPipeline(ctx));
        define(scope, "phase", new HostPhase(ctx));
        define(scope, "log", new HostLog(ctx));
        ScriptableObject.putProperty(scope, "args", nativeizeArgs(cx, scope, ctx.getArgs()));
    }

    /**
     * Builds a native JS object for {@code args}. We must NOT hand a raw Java
     * {@code Map} to {@code Context.javaToJS} — the sandbox {@code ClassShutter}
     * rejects wrapping it (the same mechanism that blocks {@code new java.io.File};
     * be-dev2 traceability footgun). So we build native {@code NativeObject} /
     * {@code NativeArray} shapes ourselves, recursing into nested maps and lists
     * (deep nativeize — Task D promotes the spike's shallow copy).
     */
    private static Scriptable nativeizeArgs(Context cx, Scriptable scope, Map<String, Object> args) {
        return (Scriptable) deepNativeize(cx, scope, args);
    }

    /**
     * Converts a Java value tree into JS-native values without ever wrapping a
     * Java object (which the sandbox ClassShutter would reject):
     * <ul>
     *   <li>{@code Map} → {@code NativeObject} (keys via {@code String.valueOf});</li>
     *   <li>{@code List} → {@code NativeArray};</li>
     *   <li>String / Number / Boolean / null → passed through verbatim;</li>
     *   <li>anything else → its {@code String.valueOf} (defensive — args should be
     *       JSON-shaped primitives/maps/lists).</li>
     * </ul>
     */
    static Object deepNativeize(Context cx, Scriptable scope, Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Scriptable obj = cx.newObject(scope);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                ScriptableObject.putProperty(obj, String.valueOf(e.getKey()),
                        deepNativeize(cx, scope, e.getValue()));
            }
            return obj;
        }
        if (value instanceof List<?> list) {
            Object[] elems = new Object[list.size()];
            for (int i = 0; i < elems.length; i++) {
                elems[i] = deepNativeize(cx, scope, list.get(i));
            }
            return cx.newArray(scope, elems);
        }
        return String.valueOf(value);
    }

    private static void define(Scriptable scope, String name, Scriptable fn) {
        ScriptableObject.putProperty(scope, name, fn);
    }
}
