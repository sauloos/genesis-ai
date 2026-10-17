import { mountGenerateView } from '/agent-views/_shared/genericGenerate.js';

export async function mount(container, agent, ctx) {
  await mountGenerateView(container, {
    agentId: 'research-synthesis',
    mode: 'playground',
    placeholder: 'Describe the research question or topic to synthesize…',
  });
}
