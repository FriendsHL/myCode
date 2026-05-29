export const meta = {
  name: 'approve-smoke',
  description: 'humanApprove 烟雾测试: 无 LLM / 无 agent 调用。直接对一个静态 payload 触发人工审批 gate, 用来端到端验证 pause → review card → approve/reject → journal-replay 恢复 → 完成。绕开真实 opt-report 的 LLM/schema 依赖。',
  phases: [
    { title: 'Prepare', detail: '构造一个静态待审 payload (不调任何 agent)' },
    { title: 'Approve', detail: '人工审批 gate (humanApprove)' }
  ]
}

// ── Prepare: 纯 JS, 不调 agent/LLM。构造一个像 attribution summary 的静态 payload,
//    让 dashboard 的审批卡片有内容可渲染 (JsonViewer)。──
phase('Prepare')
log('approve-smoke: building a static review payload (no LLM)')
var summary = {
  kind: 'approve-smoke',
  note: '这是 humanApprove 烟雾测试的静态 payload, 没有调用任何 LLM。',
  candidateChanges: [
    { id: 'demo-1', title: '示例待审项 1', detail: '审批后 workflow 应通过 journal-replay 恢复并完成。' },
    { id: 'demo-2', title: '示例待审项 2', detail: 'reject 则 status=rejected。' }
  ],
  totalCandidates: 2
}

// ── Approve: 在此暂停, 等 dashboard 操作员 approve/reject。
//    humanApprove 返回 { approved, reviewerId, reason } (恢复后由 journal 回填)。──
phase('Approve')
var decision = humanApprove(summary)

return {
  status: decision && decision.approved ? 'approved' : 'rejected',
  reviewerId: decision ? decision.reviewerId : null,
  reason: decision ? decision.reason : null,
  reviewedCandidates: summary.totalCandidates
}
