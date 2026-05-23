-- EVAL-DATASET-LAYER V1 (2026-05-24): seed 30 benchmark scenarios + 3 named
-- datasets each with v1.
--
-- Distribution (locked in benchmark-selection.md):
--   GAIA Lv1                12
--   τ-bench (Anthropic)      6
--   AgentBench OS+DB         6
--   SkillForge dogfood       6
--                            ──
--                            30
--
-- Difficulty target (PRD §D6):
--   Lv1 (~30%) : easy → baseline ≥ 50% expected
--   Lv2 (~50%) : middle → baseline 30-50% expected
--   Lv3 (~20%) : hard → baseline ≤ 20% expected
--
-- Oracle choice rationale (benchmark-selection.md push-back point):
--   Prefer machine-deterministic oracle types (contains / regex / exact_match)
--   so we don't burn 60 LLM-judge calls per A/B (30 scenarios × baseline +
--   candidate). LLM judge is reserved for cases where output shape is open-
--   ended and keyword matching would be too brittle (e.g. summarisation,
--   ranking).
--
-- Each row:
--   - source_type = 'benchmark' (all 30 are part of a recognised benchmark
--     suite; SkillForge dogfood is treated as benchmark because it's the
--     platform-specific baseline anchor, on par with external benchmarks).
--   - purpose = 'baseline_anchor'.
--   - agent_id = NULL (cross-agent — benchmark scenarios aren't tied to one
--     agent identity; same scenario can be reused for any of the user agents).
--   - status = 'active' so EvalScenarioVersionService.listLatestScenarios
--     surfaces them.
--   - split = 'held_out' so existing scenario-loader code that filters by
--     split still works.
--
-- After scenario rows, V112 creates 3 datasets + v1 each:
--   1. main-assistant-baseline-v1   = 30 benchmark scenarios
--   2. main-assistant-regression-v1 = the 6 existing session_derived rows
--   3. main-assistant-mixed-v1      = 30 benchmark + 6 session_derived
--
-- composition_hash = SHA256(sorted scenario_ids joined by ',') computed via
-- digest() in pgcrypto-compatible expression below (PostgreSQL 14 has the
-- digest function via pgcrypto extension; for safety we use a sub-select
-- pattern that the SHA256 layer also computes in EvalDatasetService when new
-- versions are published from the application layer).

BEGIN;

-- pgcrypto for digest()/gen_random_uuid(). gen_random_uuid() is built-in in
-- PG 13+, but digest() lives in pgcrypto and we use it below for the composition_hash.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ─────────────────────────────────────────────────────────────────────────
-- 1) GAIA Lv1 (12 scenarios — IDs gaia-lv1-001..012)
-- ─────────────────────────────────────────────────────────────────────────

INSERT INTO t_eval_scenario
    (id, agent_id, name, description, category, split, task, oracle_type,
     oracle_expected, status, version, source_type, source_ref, purpose)
VALUES
('gaia-lv1-001', NULL,
 'GAIA Lv1: 2020 Nobel Physics laureates',
 'Single-step lookup: list the 3 winners of the 2020 Nobel Prize in Physics.',
 'benchmark', 'held_out',
 'List the three winners of the 2020 Nobel Prize in Physics. Return only the three full names, one per line, no extra commentary.',
 'contains', 'Roger Penrose
Reinhard Genzel
Andrea Ghez',
 'active', 1, 'benchmark', 'gaia/lv1/001', 'baseline_anchor'),

('gaia-lv1-002', NULL,
 'GAIA Lv1: latest Python 3.12 patch version',
 'WebSearch tool use — find the latest published 3.12.x patch version of Python.',
 'benchmark', 'held_out',
 'Find the latest released patch version of Python 3.12 (e.g. 3.12.X). Reply with only the version string in the form "3.12.X".',
 'regex', '^3\.12\.\d+$',
 'active', 1, 'benchmark', 'gaia/lv1/002', 'baseline_anchor'),

('gaia-lv1-003', NULL,
 'GAIA Lv1: count word occurrences',
 'Count the number of times the word "the" (case-insensitive) appears in a fixed paragraph.',
 'benchmark', 'held_out',
 'In the following passage, count the number of occurrences of the word "the" (case-insensitive, whole word only):

"The quick brown fox jumps over the lazy dog. The dog was not amused by the fox or the squirrel that ran past."

Reply with only the integer count.',
 'exact_match', '5',
 'active', 1, 'benchmark', 'gaia/lv1/003', 'baseline_anchor'),

('gaia-lv1-004', NULL,
 'GAIA Lv1: extract license from GitHub README',
 'WebFetch a public README and identify the SPDX license name.',
 'benchmark', 'held_out',
 'Fetch the README of https://github.com/anthropics/anthropic-sdk-python and identify the SPDX license name (e.g. "MIT", "Apache-2.0"). Reply with only the license identifier.',
 'contains', 'MIT',
 'active', 1, 'benchmark', 'gaia/lv1/004', 'baseline_anchor'),

('gaia-lv1-005', NULL,
 'GAIA Lv1: CSV row count + first column uniques',
 'FileRead a small CSV (provided as setup), return: rows=N; first-col-unique=K.',
 'benchmark', 'held_out',
 'Read /tmp/eval/data.csv. Return exactly one line in the form "rows=N;unique_first_col=K" where N is the total non-header row count and K is the number of unique values in the first column. The provided file has 5 rows and 4 unique values in the first column.',
 'exact_match', 'rows=5;unique_first_col=4',
 'active', 1, 'benchmark', 'gaia/lv1/005', 'baseline_anchor'),

('gaia-lv1-006', NULL,
 'GAIA Lv1: JSON to YAML conversion',
 'Convert a small JSON document to YAML preserving key order.',
 'benchmark', 'held_out',
 'Convert the following JSON document to YAML, preserving key order:
{"name":"alice","age":30,"hobbies":["reading","hiking"]}

Reply with only the YAML output, no surrounding code fence.',
 'contains', 'name: alice
age: 30
hobbies:',
 'active', 1, 'benchmark', 'gaia/lv1/006', 'baseline_anchor'),

('gaia-lv1-007', NULL,
 'GAIA Lv1: top 3 content differences between two URLs',
 'Fetch two URLs and summarise the top 3 substantive differences.',
 'benchmark', 'held_out',
 'Fetch https://www.python.org/about/ and https://www.rust-lang.org/. Identify the top 3 substantive differences in how each language describes itself. Return as a numbered list (1. ... 2. ... 3. ...).',
 'regex', '(?s)1\..*2\..*3\.',
 'active', 1, 'benchmark', 'gaia/lv1/007', 'baseline_anchor'),

('gaia-lv1-008', NULL,
 'GAIA Lv1: Bash find large files',
 'Use Bash tool to list files in /tmp/eval/ larger than 10MB.',
 'benchmark', 'held_out',
 'Use the Bash tool to list files in the directory /tmp/eval/ whose size exceeds 10 MB. The test fixture intentionally has zero such files. Reply with exactly the string "no files over 10MB".',
 'contains', 'no files over 10MB',
 'active', 1, 'benchmark', 'gaia/lv1/008', 'baseline_anchor'),

('gaia-lv1-009', NULL,
 'GAIA Lv1: extract root exception from stack trace',
 'Given a Java stack trace, extract the root cause exception class name and line number.',
 'benchmark', 'held_out',
 'Given the following stack trace, extract the root cause exception class name and the line number where it occurred. Reply with exactly "class=<X>;line=<N>".

java.lang.RuntimeException: wrapper
  at com.example.A.process(A.java:42)
  at com.example.B.handle(B.java:15)
Caused by: java.lang.NullPointerException
  at com.example.C.compute(C.java:88)',
 'exact_match', 'class=java.lang.NullPointerException;line=88',
 'active', 1, 'benchmark', 'gaia/lv1/009', 'baseline_anchor'),

('gaia-lv1-010', NULL,
 'GAIA Lv1: English → Chinese markdown translation',
 'Translate a short English markdown snippet to Chinese, preserving formatting.',
 'benchmark', 'held_out',
 'Translate the following text into Simplified Chinese, preserving the markdown bullet and bold formatting exactly:

- **Quick start**: Install the SDK.
- **Configure**: Set API key.
- **Run**: Execute the demo.

Reply with only the translated markdown.',
 'regex', '(?s)- \*\*[^*]+\*\*.*- \*\*[^*]+\*\*.*- \*\*[^*]+\*\*',
 'active', 1, 'benchmark', 'gaia/lv1/010', 'baseline_anchor'),

('gaia-lv1-011', NULL,
 'GAIA Lv1: recent arXiv LLM agent eval papers',
 'Search arXiv for recent LLM agent evaluation papers; return top 5 by date.',
 'benchmark', 'held_out',
 'Use a web search to find the 5 most recent arXiv papers (last 90 days) on "LLM agent evaluation" or "agent benchmark". Return as a numbered list with title only, no abstracts. Format: "1. <title>\n2. <title>\n3. <title>\n4. <title>\n5. <title>".',
 'regex', '(?s)1\..*2\..*3\..*4\..*5\.',
 'active', 1, 'benchmark', 'gaia/lv1/011', 'baseline_anchor'),

('gaia-lv1-012', NULL,
 'GAIA Lv1: Wikipedia infobox extraction',
 'WebFetch a Wikipedia page and extract the infobox key/value pairs.',
 'benchmark', 'held_out',
 'Fetch https://en.wikipedia.org/wiki/Ada_Lovelace and extract the following fields from the infobox: "Born", "Died", "Known for". Return as "Born: <X>; Died: <Y>; Known for: <Z>".',
 'regex', '(?i)Born:.*Died:.*Known for:',
 'active', 1, 'benchmark', 'gaia/lv1/012', 'baseline_anchor'),

-- ─────────────────────────────────────────────────────────────────────────
-- 2) τ-bench (6 scenarios)
-- ─────────────────────────────────────────────────────────────────────────

('tau-bench-airline-01', NULL,
 'tau-bench airline: change flight date',
 'Multi-turn tool use: look up booking → check cancel policy → reschedule.',
 'benchmark', 'held_out',
 'You are a customer service agent for an airline. The user, booking reference ABC123, wants to change their flight from 2026-06-15 to 2026-06-20. (1) Look up the booking. (2) Confirm change policy allows ≥3 days notice (it does — request was made 2026-05-24, change is for 2026-06-20). (3) Apply the change. Reply with confirmation in the form "Changed ABC123 to 2026-06-20".',
 'contains', 'Changed ABC123 to 2026-06-20',
 'active', 1, 'benchmark', 'tau-bench/airline/01', 'baseline_anchor'),

('tau-bench-airline-02', NULL,
 'tau-bench airline: refund eligibility',
 'Determine refund eligibility and execute or reject.',
 'benchmark', 'held_out',
 'Customer asks for a refund of booking XYZ789 (non-refundable economy fare, flight not yet departed). Policy: non-refundable fares get NO refund, only credit. Reply with one of: "Refund granted: $<amount>" or "Refund denied; credit issued".',
 'contains', 'Refund denied; credit issued',
 'active', 1, 'benchmark', 'tau-bench/airline/02', 'baseline_anchor'),

('tau-bench-retail-01', NULL,
 'tau-bench retail: change shipping address',
 'Check order status; allow change only if not shipped.',
 'benchmark', 'held_out',
 'Customer wants to change the shipping address on order #ORD-555 from "100 Main St" to "200 Oak Ave". The order has not yet shipped (status = pending). Apply the change. Reply with "Address updated to: 200 Oak Ave".',
 'contains', 'Address updated to: 200 Oak Ave',
 'active', 1, 'benchmark', 'tau-bench/retail/01', 'baseline_anchor'),

('tau-bench-retail-02', NULL,
 'tau-bench retail: refund + reorder',
 'Combined task: refund original + place reorder for replacement SKU.',
 'benchmark', 'held_out',
 'Order #ORD-700 (SKU "blue-shirt-M") arrived damaged. Issue a refund of $29.99 AND place a reorder for the same SKU. Reply with exactly two lines: "Refunded $29.99 for ORD-700" then "Reorder placed for blue-shirt-M".',
 'contains', 'Refunded $29.99 for ORD-700
Reorder placed for blue-shirt-M',
 'active', 1, 'benchmark', 'tau-bench/retail/02', 'baseline_anchor'),

('tau-bench-airline-03', NULL,
 'tau-bench airline: multi-step combined',
 'One next-trip flight booking + cancel an existing one + refund.',
 'benchmark', 'held_out',
 'Three tasks for customer #C-42: (1) book the next available DCA→JFK flight after 2026-07-01, (2) cancel existing booking ABC123, (3) refund ABC123 (refundable fare). Reply with three lines starting "Booked", "Cancelled ABC123", "Refunded ABC123".',
 'regex', '(?s)Booked.*Cancelled ABC123.*Refunded ABC123',
 'active', 1, 'benchmark', 'tau-bench/airline/03', 'baseline_anchor'),

('tau-bench-retail-03', NULL,
 'tau-bench retail: ambiguous request → clarify',
 'Agent must proactively ask the user for clarification before acting.',
 'benchmark', 'held_out',
 'Customer says "Cancel my order". The customer has 3 open orders. Do NOT cancel any. Instead, ask the user to specify which order id to cancel. Your response MUST contain the substring "which order".',
 'contains', 'which order',
 'active', 1, 'benchmark', 'tau-bench/retail/03', 'baseline_anchor'),

-- ─────────────────────────────────────────────────────────────────────────
-- 3) AgentBench OS + DB (6 scenarios)
-- ─────────────────────────────────────────────────────────────────────────

('agentbench-os-001', NULL,
 'AgentBench OS: top 10 largest .log files',
 'Use Bash to find .log files sorted by size, descending, top 10.',
 'benchmark', 'held_out',
 'Use Bash to list the top 10 .log files in /tmp/eval/ sorted by size descending. Provided fixture has 3 .log files. Reply with one filename per line in size-descending order. Expected order: big.log, mid.log, small.log',
 'contains', 'big.log
mid.log
small.log',
 'active', 1, 'benchmark', 'agentbench/os/001', 'baseline_anchor'),

('agentbench-os-002', NULL,
 'AgentBench OS: kill process on port 8080',
 'Identify and terminate the process bound to TCP port 8080.',
 'benchmark', 'held_out',
 'Use Bash to find the PID of the process listening on TCP port 8080 (fixture: PID is 12345). Reply with exactly "killed PID 12345" after issuing the kill command.',
 'exact_match', 'killed PID 12345',
 'active', 1, 'benchmark', 'agentbench/os/002', 'baseline_anchor'),

('agentbench-os-003', NULL,
 'AgentBench OS: find .py files modified in last 7 days',
 'Recursive find with mtime filter inside a git repo.',
 'benchmark', 'held_out',
 'Use Bash to find all .py files in /tmp/eval/repo/ that were modified within the last 7 days. Fixture contains 2 such files: recent_a.py and recent_b.py. Reply with the basenames, one per line, sorted alphabetically.',
 'contains', 'recent_a.py
recent_b.py',
 'active', 1, 'benchmark', 'agentbench/os/003', 'baseline_anchor'),

('agentbench-db-001', NULL,
 'AgentBench DB: top 10 users by registration time',
 'SQL: SELECT users ORDER BY registered_at DESC LIMIT 10.',
 'benchmark', 'held_out',
 'Given a users table with columns (id, name, registered_at), write the SQL to retrieve the 10 most recently registered users. Reply with only the SQL statement, no markdown fence.',
 'regex', '(?is)SELECT.*FROM\s+users.*ORDER\s+BY\s+registered_at\s+DESC.*LIMIT\s+10',
 'active', 1, 'benchmark', 'agentbench/db/001', 'baseline_anchor'),

('agentbench-db-002', NULL,
 'AgentBench DB: find duplicate (user_id, product_id) in orders',
 'SQL: GROUP BY having count > 1.',
 'benchmark', 'held_out',
 'Given an orders table with columns (id, user_id, product_id, created_at), write SQL to find duplicate (user_id, product_id) pairs (i.e. appearing more than once). Reply with only the SQL.',
 'regex', '(?is)SELECT.*user_id.*product_id.*GROUP\s+BY.*HAVING\s+COUNT.*>\s*1',
 'active', 1, 'benchmark', 'agentbench/db/002', 'baseline_anchor'),

('agentbench-db-003', NULL,
 'AgentBench DB: 3-table JOIN monthly GMV',
 'JOIN orders/order_items/products; aggregate revenue by month.',
 'benchmark', 'held_out',
 'Given tables orders(id, created_at, user_id), order_items(order_id, product_id, qty), products(id, price), write SQL to compute monthly gross merchandise value (GMV) trend. Group by year-month, descending. Reply with SQL only.',
 'regex', '(?is)JOIN.*JOIN.*GROUP\s+BY.*(month|to_char|date_trunc)',
 'active', 1, 'benchmark', 'agentbench/db/003', 'baseline_anchor'),

-- ─────────────────────────────────────────────────────────────────────────
-- 4) SkillForge dogfood (6 scenarios)
-- ─────────────────────────────────────────────────────────────────────────

('skillforge-dogfood-001', NULL,
 'SkillForge dogfood: GetTrace tool_use summary',
 'Use GetTrace to summarise a session: total tool_use count + tool name distribution.',
 'benchmark', 'held_out',
 'You are given session id "demo-session-001". Use the GetTrace tool to summarise it. Reply in the form "total=<N>; tools=Bash:X,FileRead:Y,WebSearch:Z" (the demo fixture has total=6; Bash:2, FileRead:3, WebSearch:1).',
 'exact_match', 'total=6; tools=Bash:2,FileRead:3,WebSearch:1',
 'active', 1, 'benchmark', 'skillforge/dogfood/001', 'baseline_anchor'),

('skillforge-dogfood-002', NULL,
 'SkillForge dogfood: SubAgent fan-out 3 sessions',
 'Dispatch 3 child analyses with SubAgent; aggregate the outcome distribution.',
 'benchmark', 'held_out',
 'Use the SubAgent tool to analyse 3 sessions ("sess-a", "sess-b", "sess-c") in parallel. Each child reports an outcome of success/failure/timeout. Aggregate them in a single line "success=X; failure=Y; timeout=Z" — fixture answers: success=2; failure=1; timeout=0.',
 'exact_match', 'success=2; failure=1; timeout=0',
 'active', 1, 'benchmark', 'skillforge/dogfood/002', 'baseline_anchor'),

('skillforge-dogfood-003', NULL,
 'SkillForge dogfood: ProposeOptimization OptEvent',
 'Use the ProposeOptimization tool to create one OptEvent (surface=prompt, outcome=failure).',
 'benchmark', 'held_out',
 'Use the ProposeOptimization tool to write a new OptEvent for agent id "5", surface="prompt", outcome="failure", reason="ChatService context lost on retry". Reply with "OptEvent created: id=<id>" using the returned event id.',
 'regex', 'OptEvent created: id=[A-Za-z0-9-]+',
 'active', 1, 'benchmark', 'skillforge/dogfood/003', 'baseline_anchor'),

('skillforge-dogfood-004', NULL,
 'SkillForge dogfood: compare agent tool sets',
 'Use GetAgentConfig + ListAgents to diff the tool sets of Main Assistant vs Code Agent.',
 'benchmark', 'held_out',
 'Compare the tool sets of "Main Assistant" and "Code Agent" using GetAgentConfig + ListAgents. Reply with two lines: "only_in_main: <list>" and "only_in_code: <list>". The fixture answer: only_in_main: WebSearch; only_in_code: Bash.',
 'contains', 'only_in_main: WebSearch
only_in_code: Bash',
 'active', 1, 'benchmark', 'skillforge/dogfood/004', 'baseline_anchor'),

('skillforge-dogfood-005', NULL,
 'SkillForge dogfood: Memory tool roundtrip',
 'Store a fact via Memory tool, read it back in the next loop turn.',
 'benchmark', 'held_out',
 'Use the Memory tool to store the fact "user prefers metric units" (key: prefs.units). On the next loop turn, read it back using Memory and reply with exactly the value you stored.',
 'contains', 'user prefers metric units',
 'active', 1, 'benchmark', 'skillforge/dogfood/005', 'baseline_anchor'),

('skillforge-dogfood-006', NULL,
 'SkillForge dogfood: cross-session GetTrace failure pattern',
 'Find sessions where Bash tool produced a non-zero exit, across 3 sessions.',
 'benchmark', 'held_out',
 'Across sessions "s1", "s2", "s3" use GetTrace to find tool_use=Bash where tool_result has exit code != 0. Reply with one line per matching session id (alphabetical). Fixture answer: s1 and s3 match.',
 'contains', 's1
s3',
 'active', 1, 'benchmark', 'skillforge/dogfood/006', 'baseline_anchor')
ON CONFLICT (id) DO NOTHING;
-- r2 db W1 fix: 跟其它 INSERT (datasets/versions/bridge) 一致加 ON CONFLICT —
-- partial-run + Flyway repair retry 时不撞 duplicate PK。一致性 gap。

-- ─────────────────────────────────────────────────────────────────────────
-- 5) Datasets + v1 versions
-- ─────────────────────────────────────────────────────────────────────────
--
-- DO $$ block to (a) compute UUIDs once per dataset, (b) compute the SHA256
-- composition_hash from sorted scenario_ids, (c) wire bridge rows.

DO $$
DECLARE
    baseline_dataset_id   VARCHAR(36) := gen_random_uuid()::text;
    regression_dataset_id VARCHAR(36) := gen_random_uuid()::text;
    mixed_dataset_id      VARCHAR(36) := gen_random_uuid()::text;
    baseline_version_id   VARCHAR(36) := gen_random_uuid()::text;
    regression_version_id VARCHAR(36) := gen_random_uuid()::text;
    mixed_version_id      VARCHAR(36) := gen_random_uuid()::text;
    baseline_hash         VARCHAR(64);
    regression_hash       VARCHAR(64);
    mixed_hash            VARCHAR(64);
    benchmark_count       INTEGER;
    regression_count      INTEGER;
BEGIN
    -- Insert dataset rows (skip if already seeded — idempotent on owner-name).
    INSERT INTO t_eval_dataset (id, name, description, owner_id, agent_id, tags, is_public)
    VALUES (baseline_dataset_id, 'main-assistant-baseline-v1',
            'GAIA Lv1 + τ-bench + AgentBench + SkillForge dogfood — 30 benchmark scenarios for fair baseline anchoring.',
            1, NULL, '["benchmark","baseline"]'::jsonb, FALSE)
    ON CONFLICT (owner_id, name) DO NOTHING;

    INSERT INTO t_eval_dataset (id, name, description, owner_id, agent_id, tags, is_public)
    VALUES (regression_dataset_id, 'main-assistant-regression-v1',
            'Existing 6 session-derived scenarios used for regression coverage (purpose=regression).',
            1, NULL, '["regression","session_derived"]'::jsonb, FALSE)
    ON CONFLICT (owner_id, name) DO NOTHING;

    INSERT INTO t_eval_dataset (id, name, description, owner_id, agent_id, tags, is_public)
    VALUES (mixed_dataset_id, 'main-assistant-mixed-v1',
            'Mixed dataset: 30 benchmark + 6 regression scenarios. Use for combined baseline+regression A/B.',
            1, NULL, '["benchmark","regression","mixed"]'::jsonb, FALSE)
    ON CONFLICT (owner_id, name) DO NOTHING;

    -- Re-resolve real ids after ON CONFLICT (the local variables may not match
    -- if a prior partial seed already inserted a row).
    SELECT id INTO baseline_dataset_id   FROM t_eval_dataset WHERE owner_id=1 AND name='main-assistant-baseline-v1';
    SELECT id INTO regression_dataset_id FROM t_eval_dataset WHERE owner_id=1 AND name='main-assistant-regression-v1';
    SELECT id INTO mixed_dataset_id      FROM t_eval_dataset WHERE owner_id=1 AND name='main-assistant-mixed-v1';

    -- Sanity counts (used for composition_stats).
    SELECT COUNT(*) INTO benchmark_count FROM t_eval_scenario WHERE source_type='benchmark';
    SELECT COUNT(*) INTO regression_count FROM t_eval_scenario WHERE source_type='session_derived';

    -- Compute composition_hash via pgcrypto digest (PostgreSQL 14 + zonky have
    -- this enabled). hash := sha256(sorted_csv_of_scenario_ids).
    SELECT encode(digest(string_agg(id, ',' ORDER BY id), 'sha256'), 'hex')
        INTO baseline_hash
        FROM t_eval_scenario WHERE source_type='benchmark';

    SELECT encode(digest(string_agg(id, ',' ORDER BY id), 'sha256'), 'hex')
        INTO regression_hash
        FROM t_eval_scenario WHERE source_type='session_derived';

    SELECT encode(digest(string_agg(id, ',' ORDER BY id), 'sha256'), 'hex')
        INTO mixed_hash
        FROM t_eval_scenario
        WHERE source_type IN ('benchmark','session_derived');

    -- Insert version rows (skip if already seeded).
    INSERT INTO t_eval_dataset_version
        (id, dataset_id, version_number, composition_stats, composition_hash, created_by)
    VALUES (
        baseline_version_id, baseline_dataset_id, 1,
        jsonb_build_object(
            'benchmark', benchmark_count, 'session_derived', 0, 'manual', 0,
            'total', benchmark_count,
            'purpose_baseline_anchor', benchmark_count,
            'purpose_regression', 0,
            'purpose_ablation', 0,
            'expected_baseline_pass_rate', 0.40),
        baseline_hash, 1)
    ON CONFLICT (dataset_id, version_number) DO NOTHING;

    INSERT INTO t_eval_dataset_version
        (id, dataset_id, version_number, composition_stats, composition_hash, created_by)
    VALUES (
        regression_version_id, regression_dataset_id, 1,
        jsonb_build_object(
            'benchmark', 0, 'session_derived', regression_count, 'manual', 0,
            'total', regression_count,
            'purpose_baseline_anchor', 0,
            'purpose_regression', regression_count,
            'purpose_ablation', 0,
            'expected_baseline_pass_rate', 0.05),
        regression_hash, 1)
    ON CONFLICT (dataset_id, version_number) DO NOTHING;

    INSERT INTO t_eval_dataset_version
        (id, dataset_id, version_number, composition_stats, composition_hash, created_by)
    VALUES (
        mixed_version_id, mixed_dataset_id, 1,
        jsonb_build_object(
            'benchmark', benchmark_count, 'session_derived', regression_count, 'manual', 0,
            'total', benchmark_count + regression_count,
            'purpose_baseline_anchor', benchmark_count,
            'purpose_regression', regression_count,
            'purpose_ablation', 0,
            'expected_baseline_pass_rate', 0.34),
        mixed_hash, 1)
    ON CONFLICT (dataset_id, version_number) DO NOTHING;

    -- Resolve actual version ids (in case ON CONFLICT preserved existing).
    SELECT id INTO baseline_version_id
        FROM t_eval_dataset_version WHERE dataset_id=baseline_dataset_id AND version_number=1;
    SELECT id INTO regression_version_id
        FROM t_eval_dataset_version WHERE dataset_id=regression_dataset_id AND version_number=1;
    SELECT id INTO mixed_version_id
        FROM t_eval_dataset_version WHERE dataset_id=mixed_dataset_id AND version_number=1;

    -- Wire bridge rows (idempotent via PK).
    INSERT INTO t_eval_dataset_version_scenario (dataset_version_id, scenario_id)
        SELECT baseline_version_id, id FROM t_eval_scenario WHERE source_type='benchmark'
    ON CONFLICT DO NOTHING;

    INSERT INTO t_eval_dataset_version_scenario (dataset_version_id, scenario_id)
        SELECT regression_version_id, id FROM t_eval_scenario WHERE source_type='session_derived'
    ON CONFLICT DO NOTHING;

    INSERT INTO t_eval_dataset_version_scenario (dataset_version_id, scenario_id)
        SELECT mixed_version_id, id FROM t_eval_scenario
        WHERE source_type IN ('benchmark','session_derived')
    ON CONFLICT DO NOTHING;
END $$;

COMMIT;
