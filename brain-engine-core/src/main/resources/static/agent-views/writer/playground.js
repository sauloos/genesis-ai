import { mountGenerateView } from '/agent-views/_shared/genericGenerate.js';

export async function mount(container, agent, ctx) {
  await mountGenerateView(container, {
    agentId: 'writer',
    mode: 'playground',
    placeholder: 'Describe the email, memo, or document you need drafted…',
  });
}
