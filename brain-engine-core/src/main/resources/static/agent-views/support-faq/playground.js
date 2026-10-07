import { mountChatView } from '/agent-views/_shared/genericChat.js';

export async function mount(container, agent, ctx) {
  await mountChatView(container, {
    agentId: 'support-faq',
    basePath: '/api/agent-chat',
    emptyText: 'Ask a question — this agent answers from the knowledge base.',
    placeholder: 'Ask a support question…',
  });
}
