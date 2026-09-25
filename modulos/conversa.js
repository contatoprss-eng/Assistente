/* Módulo Conversa: atalho para falar com o assistente no projeto do Claude. */
(function () {
  const PROJETO = 'https://claude.ai/code/project/chan_01Nr5xnLBmVbcL89tVvjTzBs';

  function montar(el) {
    el.innerHTML = `
      <header class="topo">
        <p class="sobre">Fale comigo sobre qualquer coisa: agenda, agência, freelance, finanças.</p>
        <h1 class="mes" style="text-transform:none">Conversar</h1>
      </header>
      <a class="conversa-btn" href="${PROJETO}" target="_blank" rel="noopener">
        <span class="conversa-titulo">Abrir conversa com o Claude</span>
        <span class="conversa-sub">Abre o projeto Assistente Pessoal, com tudo o que já combinamos.</span>
      </a>
      <p class="dica">Se o app do Claude estiver instalado, o link abre direto nele.</p>`;
  }

  App.registrar({ id: 'conversa', nome: 'Conversar', icone: '✦', montar });
})();
