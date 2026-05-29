package com.skillforge.workflow.bindings;

import com.skillforge.workflow.WorkflowContext;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Undefined;

/**
 * The {@code phase(title)} host binding. Sprint-1 spike: records the phase title
 * into the {@link WorkflowContext} (Task C wires WS broadcast via
 * {@code WorkflowWsBroadcaster}). Returns {@code undefined}.
 */
public final class HostPhase extends BaseFunction {

    private final transient WorkflowContext ctx;

    public HostPhase(WorkflowContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String getFunctionName() {
        return "phase";
    }

    @Override
    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
        if (args.length > 0 && args[0] != null) {
            ctx.recordPhase(Context.toString(args[0]));
        }
        return Undefined.instance;
    }
}
