import { mountConsultantChat } from '/agent-views/consultant/consultantChat.js';

// ctx.text overrides the chat's copy — how a tenant's AgentViewExtension rebrands it.
export async function mount(container, agent, ctx) {
  await mountConsultantChat(container, ctx.text);
}
