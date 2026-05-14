package com.skillforge.server.exception;

/**
 * MULTIMODAL-MVP defense-in-depth: thrown when a turn carries multimodal content
 * blocks but the resolved effective model (from
 * {@code session.runtimeModelOverride} / {@code agent.modelId}) is not in any
 * LLM provider's {@code visionModels} allowlist (see
 * {@link com.skillforge.server.config.LlmProperties#supportsVision}).
 *
 * <p>The FE upload-button gate + BE upload-endpoint gate ({@code
 * ChatController.requireVisionCapableModel}) block the common path. This
 * exception is the runtime guard for the race-condition path (agent.modelId
 * changed between upload and send) and replayed / stale-FE requests. Surfaces
 * as runtimeError + WS sessionStatus("error") so the FE can map the wire CODE
 * to a "switch model" hint.</p>
 *
 * <p>Error code (stable wire identifier): {@code MULTIMODAL_MODEL_NO_VISION_CAPABILITY}.</p>
 */
public class MultimodalNoVisionException extends RuntimeException {

    public static final String CODE = "MULTIMODAL_MODEL_NO_VISION_CAPABILITY";

    private final String modelId;

    public MultimodalNoVisionException(String modelId) {
        super(CODE + ": model `" + modelId + "` does not support vision input");
        this.modelId = modelId;
    }

    public String getModelId() {
        return modelId;
    }
}
