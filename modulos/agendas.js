/* Módulo Agendas: mostra as agendas do Google do Paulo, sincronizadas pelo próprio Google.
 * Os endereços das agendas vêm do link privado de configuração (App.config().agendas),
 * nunca do código público. É preciso estar logado no Google neste celular. */
(function () {
  const MODOS = [['AGENDA', 'Lista'], ['WEEK', 'Semana'], ['MONTH', 'Mês']];
  let modo = App.dados.ler('ag:modo', 'AGENDA');

  function url(agendas) {
    const p = new URLSearchParams();
    agendas.forEach(a => { p.append('src', a.id); p.append('color', a.cor || '#3565CF'); });
    p.set('ctz', 'America/Sao_Paulo'); p.set('hl', 'pt_BR'); p.set('mode', modo);
    p.set('showTitle', '0'); p.set('showPrint', '0'); p.set('showTabs', '0'); p.set('showCalendars', '1'); p.set('showTz', '0');
    return 'https://calendar.google.com/calendar/embed?' + p.toString();
  }

  function montar(el) {
    const agendas = App.config().agendas || [];
    if (!agendas.length) {
      el.innerHTML = `<header class="topo"><h1 class="mes" style="text-transform:none">Agendas</h1></header>
        <p class="vazio">Falta configurar. Abra neste celular o link de configuração que o assistente te mandou.</p>`;
      return;
    }
    el.innerHTML = `
      <header class="topo">
        <p class="sobre">${agendas.map(a => a.nome).join(' · ')}</p>
        <h1 class="mes" style="text-transform:none">Agendas</h1>
      </header>
      <div class="chips filtros" role="group" aria-label="Visualização">
        ${MODOS.map(([k, n]) => `<button type="button" class="filtro${modo === k ? ' ativo' : ''}" data-modo="${k}">${n}</button>`).join('')}
      </div>
      <div class="gcal"><iframe title="Agendas do Google" src="${url(agendas)}" loading="lazy"></iframe></div>
      <p class="dica">Se aparecer vazio ou pedir login, entre na sua conta Google no Chrome deste celular. <a href="https://calendar.google.com/calendar/r" target="_blank" rel="noopener">Abrir no Google Agenda</a></p>`;
    el.onclick = e => {
      const b = e.target.closest('[data-modo]');
      if (!b) return;
      modo = b.dataset.modo; App.dados.salvar('ag:modo', modo); montar(el);
    };
  }

  App.registrar({ id: 'agendas', nome: 'Agendas', icone: '◷', montar });
})();
