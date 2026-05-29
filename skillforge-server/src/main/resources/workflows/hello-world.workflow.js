export const meta = {
  name: 'hello-world',
  description: 'Sprint 1 smoke workflow: one phase, one log line, one sub-agent call.',
  phases: [
    { title: 'Greet', detail: 'say hi via a sub-agent' }
  ]
}

phase('Greet')
log('hello from workflow')
var greeting = agent('Say hi in one short sentence.', { agentSlug: 'session-annotator' })
return { ok: true, greeting: greeting }
