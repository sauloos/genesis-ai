// Genesis Brands' Consultant live view — extends the core chat view with brand copy only.
import { mountConsultantChat } from '/agent-views/consultant/consultantChat.js';

export async function mount(container, agent, ctx) {
  await mountConsultantChat(container, {
    notReady: 'Finish your brand journey to chat with your consultant.',
    placeholder: 'Ask your consultant about your brand…',
    empty: 'Ask anything about your brand — your consultant knows your tagline, tone, palette, and logo.',
  });
}
