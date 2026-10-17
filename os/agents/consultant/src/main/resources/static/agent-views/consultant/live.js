import { mountConsultantChat } from '/agent-views/consultant/consultantChat.js';

export async function mount(container, agent, ctx) {
  await mountConsultantChat(container);
}
