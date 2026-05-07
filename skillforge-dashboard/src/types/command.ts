/**
 * P10 — Slash command types shared by FE popup, API client, and tests.
 *
 * Backend contract (see SlashCommandController):
 *   POST /api/commands/execute  body { sessionId, command, args, userId }
 *   returns CommandResult.
 *
 * `displayMode` controls how the FE renders the result:
 *   - "redirect": navigate to `/sessions/${newSessionId}` (used by `/new`)
 *   - "toast":    show `message.success`/`message.error` (used by `/model`, `/compact`)
 *   - "modal":    open `CommandResultModal` rendering `markdownBody`
 *                 (used by `/models`, `/skill`, `/tool`, `/context`, `/help`)
 *
 * INV-15 — `/model` and `/models` are TWO independent commands. The popup
 * lists both via prefix match, but execution sends the exact name typed
 * (or the popup-selected name) so the backend dispatches by exact match,
 * not prefix.
 */

export type CommandDisplayMode = 'redirect' | 'toast' | 'modal';

export interface CommandResult {
  success: boolean;
  /** Human-readable message — used as toast text when displayMode='toast'. */
  message: string;
  displayMode: CommandDisplayMode;
  /** Set when displayMode='redirect' (i.e. `/new`). */
  newSessionId?: string;
  /** Set by `/model` so the FE can update the chat header chip. */
  modelId?: string;
  /** Markdown body for `displayMode='modal'`. */
  markdownBody?: string;
  /** Populated when success=false; FE shows as toast error. */
  error?: string;
}

export interface CommandMetadata {
  /** Exact command name including the leading `/`, e.g. `/new`. */
  name: string;
  /** Short description shown in the popup row. */
  description: string;
  /** Usage hint, e.g. `/model <modelId>`. */
  usage: string;
}

/**
 * Static catalog of the 8 MVP commands. Order = popup default order when
 * filter is empty (just `/`). Keep `/model` BEFORE `/models` so a user typing
 * `/m` sees `/model` first (more common operation) — INV-15 still routes
 * dispatch by exact name, not list order.
 */
export const COMMANDS: ReadonlyArray<CommandMetadata> = [
  {
    name: '/new',
    description: 'Start a new session (optionally switch agent)',
    usage: '/new [agentName?]',
  },
  {
    name: '/compact',
    description: 'Compact context to free tokens',
    usage: '/compact',
  },
  {
    name: '/model',
    description: 'Switch model for this session',
    usage: '/model <modelId>',
  },
  {
    name: '/models',
    description: 'List available models',
    usage: '/models',
  },
  {
    name: '/skill',
    description: "List current agent's skills",
    usage: '/skill',
  },
  {
    name: '/tool',
    description: "List current agent's tools",
    usage: '/tool',
  },
  {
    name: '/context',
    description: 'Show context token breakdown',
    usage: '/context',
  },
  {
    name: '/help',
    description: 'Show all slash commands',
    usage: '/help',
  },
];

/**
 * Regex that matches "the user is still typing the command name" — i.e. the
 * input value is exactly `/`, or `/<letters>` with NO whitespace yet. Used by
 * ChatInput to decide whether to show the popup. INV-Q5 (a): popup ONLY
 * triggers when the FIRST character is `/`. Once the user types a space (i.e.
 * starts entering args like `/model gpt-4`), the popup is hidden but the
 * Enter handler still routes to the slash-command path.
 */
export const COMMAND_NAME_REGEX = /^\/[A-Za-z]*$/;

/**
 * Filter the command catalog by the leading `/<prefix>` typed by the user.
 *
 * INV-15: prefix match means `/m` shows BOTH `/model` and `/models`, but
 * `/models` (exact) shows only `/models` because `model` does not startsWith
 * `models`. This is what guarantees the popup never silently rewrites
 * `/models` into `/model`.
 *
 * When `input` is just `/` (empty prefix), all 8 commands are returned in
 * catalog order.
 */
export function filterCommands(
  input: string,
  catalog: ReadonlyArray<CommandMetadata> = COMMANDS,
): CommandMetadata[] {
  if (!input.startsWith('/')) return [];
  const prefix = input.slice(1).toLowerCase();
  if (prefix.length === 0) return [...catalog];
  return catalog.filter((c) => c.name.slice(1).toLowerCase().startsWith(prefix));
}
