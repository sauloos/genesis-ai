export function mount(container) {
  container.classList.add('dc-panel');
  container.innerHTML = `
    <div class="dc-panel-label">Catalog</div>
    <div class="dc-coming-soon">Products for your brand — coming soon</div>
  `;
}
