import { mountChatView } from '/agent-views/_shared/genericChat.js';

export async function mount(container, agent, ctx) {
  await mountChatView(container, {
    agentId: 'compliance-qa',
    basePath: '/api/agent-chat',
    emptyText: 'Ask a policy or compliance question — this agent answers from the knowledge base.',
    placeholder: 'Ask a policy or compliance question…',
  });
}
