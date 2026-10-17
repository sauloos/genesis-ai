import { mountGenerateView } from '/agent-views/_shared/genericGenerate.js';

export async function mount(container, agent, ctx) {
  await mountGenerateView(container, {
    agentId: 'summarizer',
    mode: 'live',
    placeholder: 'Paste a document, transcript, or call notes to summarize…',
    onGenerated: () => ctx.onAssetGenerated?.(),
  });
}
