package com.skillforge.core.llm;

import com.skillforge.core.model.Message;
import com.skillforge.core.model.ReasoningEffort;
import com.skillforge.core.model.ThinkingMode;
import com.skillforge.core.model.ToolSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 请求模型，封装调用参数。
 */
public class LlmRequest {

    /**
     * 全局默认 output max_tokens。
     *
     * <p>历史值是 4096（Claude 早期默认），但实测下来主流 provider 实际可用上限均 ≥ 8K：
     * Claude Sonnet/Opus 8192（extended thinking 64K）、Qwen Max / glm-5 / DeepSeek 8192、
     * GPT-4o 16384、mimo-v2.5-pro 16384。
     *
     * <p>取 16384 作为新默认（向上兼容；Claude 真用得起更高）。Per-provider yaml override 留 V2。
     * 小模型 / 自部署 vLLM 等可能溢出场景，让 agent 配 {@code max_tokens} per-agent override
     * （已支持，{@code AgentDefinition.getMaxTokens()} 读 config 优先）。
     */
    public static final int DEFAULT_MAX_TOKENS = 16384;

    private String systemPrompt;
    private List<Message> messages = new ArrayList<>();
    private List<ToolSchema> tools = new ArrayList<>();
    private String model;
    private int maxTokens = DEFAULT_MAX_TOKENS;
    private double temperature = 0.7;
    /** Agent-level thinking-mode override. Null or AUTO = provider default. */
    private ThinkingMode thinkingMode;
    /** Reasoning-effort hint for reasoning-capable families. Null = provider default. */
    private ReasoningEffort reasoningEffort;

    public LlmRequest() {
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public List<ToolSchema> getTools() {
        return tools;
    }

    public void setTools(List<ToolSchema> tools) {
        this.tools = tools;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public ThinkingMode getThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(ThinkingMode thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    public ReasoningEffort getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(ReasoningEffort reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }
}
