-- CHAT-REASONING-PANEL: per-agent preference for reasoning panel visibility in chat UI.
-- null = collapsed (follow global default false). true = expanded by default.
-- nullable + no default: existing rows keep null which the FE resolves to false (collapsed).
ALTER TABLE t_agent ADD COLUMN thinking_visible BOOLEAN;
