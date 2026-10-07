import { mountGenerateView } from '/agent-views/_shared/genericGenerate.js';

export async function mount(container, agent, ctx) {
  await mountGenerateView(container, {
    agentId: 'research-synthesis',
    mode: 'live',
    placeholder: 'Describe the research question or topic to synthesize…',
    onGenerated: () => ctx.onAssetGenerated?.(),
  });
}
