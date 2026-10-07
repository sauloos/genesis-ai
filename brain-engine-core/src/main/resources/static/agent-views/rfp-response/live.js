import { mountGenerateView } from '/agent-views/_shared/genericGenerate.js';

export async function mount(container, agent, ctx) {
  await mountGenerateView(container, {
    agentId: 'rfp-response',
    mode: 'live',
    placeholder: 'Describe the RFP/proposal request and any key requirements…',
    onGenerated: () => ctx.onAssetGenerated?.(),
  });
}
