package com.skillforge.core.skill.view;

import com.skillforge.core.model.AgentDefinition;

/**
 * 解析当前 session 的 {@link SessionSkillView}。
 * <p>每个 user turn 入口算一次（plan r2 §5 计算时机）；ChatService 在调用
 * {@code agentLoopEngine.run} 之前把结果注入到 LoopContext。
 * <p>Server 层提供默认实现 {@code DefaultSessionSkillResolver}。core 模块只持有接口，
 * 避免依赖 server 的 SkillRegistry / AgentEntity 细节。
 */
public interface SessionSkillResolver {

    /**
     * @param agentDef 当前 session 绑定的 agent 定义（含 skillIds）
     * @return 不为 null 的 view（无授权时返回 {@link SessionSkillView#EMPTY}）
     */
    SessionSkillView resolveFor(AgentDefinition agentDef);

    /**
     * SKILL-CANARY-ROLLOUT V2 Phase 1.2 — session-aware resolve. Implementations
     * may use {@code sessionId} to apply per-session decisions (e.g. canary
     * rollout allocation). Default delegates to the legacy {@link #resolveFor(AgentDefinition)}
     * so existing callers without a session context keep working unchanged.
     *
     * @param agentDef 当前 session 绑定的 agent 定义（含 skillIds）
     * @param sessionId 当前 session id；{@code null} = 无 session 上下文（legacy / test 路径）
     * @return 不为 null 的 view（无授权时返回 {@link SessionSkillView#EMPTY}）
     */
    default SessionSkillView resolveFor(AgentDefinition agentDef, String sessionId) {
        return resolveFor(agentDef);
    }
}
