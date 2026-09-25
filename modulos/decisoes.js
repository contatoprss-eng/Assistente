/*
 * Módulo Decisões: cartões com opções clicáveis para a agência e o freelance.
 * As decisões vêm de dois lugares:
 *  - dados/decisoes.json, que o assistente (Claude) atualiza no repositório;
 *  - as que o próprio Paulo cria no app.
 * O que foi escolhido fica salvo no aparelho.
 */
(function () {
  const FRENTES = {
    agencia:   { nome: 'Agência', cor: 'var(--trabalho)' },
    freelance: { nome: 'Freelance', cor: 'var(--lazer)' },
    financas:  { nome: 'Finanças', cor: 'var(--familia)' },
    pessoal:   { nome: 'Pessoal', cor: 'var(--saude)' }
  };
  const D = App.dados;
  let proprias = D.ler('dec:proprias', []);
  let respostas = D.ler('dec:respostas', {}); // id -> { opcao, em }
  let remotas = [];
  let filtro = D.ler('dec:filtro', 'todas');
  let raiz;

  const EXEMPLOS = [
    { id: 'ex-d1', frente: 'freelance', titulo: 'Aceitar a diária de gravação no sábado?',
      contexto: 'Exemplo. O cachê é bom, mas o sábado estava reservado para a família.',
      prazo: null, exemplo: true,
      opcoes: [
        { id: 'a', texto: 'Aceitar e remarcar a família para domingo', efeito: 'Entra dinheiro; você precisa avisar em casa hoje.' },
        { id: 'b', texto: 'Aceitar só se subir o cachê', efeito: 'Negocia; se recusarem, o sábado fica livre.', recomendada: true },
        { id: 'c', texto: 'Recusar', efeito: 'Sábado garantido; indica outra pessoa para manter o contato.' }
      ] },
    { id: 'ex-d2', frente: 'agencia', titulo: 'Qual cliente atender primeiro esta semana?',
      contexto: 'Exemplo. Duas entregas vencem na sexta e só dá para focar em uma de manhã.',
      prazo: null, exemplo: true,
      opcoes: [
        { id: 'a', texto: 'O cliente que paga mais', efeito: 'Protege a receita do mês.' },
        { id: 'b', texto: 'O que está mais atrasado', efeito: 'Evita desgaste com o cliente.', recomendada: true },
        { id: 'c', texto: 'Delegar um dos dois para a equipe', efeito: 'Libera você; exige passar o briefing hoje.' }
      ] }
  ];

  async function carregarRemotas() {
    try {
      const r = await fetch('dados/decisoes.json', { cache: 'no-store' });
      if (r.ok) { const j = await r.json(); remotas = Array.isArray(j.decisoes) ? j.decisoes : []; }
    } catch (e) {}
  }
  const todas = () => {
    const base = remotas.length ? remotas : (D.ler('dec:exemplos-apagados', false) ? [] : EXEMPLOS);
    return base.concat(proprias);
  };
  const salvar = () => { D.salvar('dec:proprias', proprias); D.salvar('dec:respostas', respostas); D.salvar('dec:filtro', filtro); };
  const h = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  function prazoTexto(p) {
    if (!p) return '';
    const hoje = new Date(); hoje.setHours(0, 0, 0, 0);
    const [a, m, d] = p.split('-').map(Number);
    const dias = Math.round((new Date(a, m - 1, d) - hoje) / 86400000);
    if (dias < 0) return 'prazo passou';
    if (dias === 0) return 'decidir hoje';
    if (dias === 1) return 'decidir até amanhã';
    return 'decidir em ' + dias + ' dias';
  }

  function montar(el) {
    raiz = el;
    desenhar();
    carregarRemotas().then(() => { if (raiz && raiz.isConnected) desenhar(); });
  }

  function desenhar() {
    const lista = todas().filter(d => filtro === 'todas' || d.frente === filtro);
    const pendentes = lista.filter(d => !respostas[d.id]);
    const feitas = lista.filter(d => respostas[d.id]);
    const temExemplo = todas().some(d => d.exemplo);
    const filtros = [['todas', 'Todas']].concat(Object.keys(FRENTES).map(k => [k, FRENTES[k].nome]));
    raiz.innerHTML = `
      <header class="topo">
        <p class="sobre">Uma decisão por vez. Toque na opção que você escolhe.</p>
        <h1 class="mes" style="text-transform:none">Decisões <span>${pendentes.length ? pendentes.length + ' pendente' + (pendentes.length > 1 ? 's' : '') : 'em dia'}</span></h1>
      </header>
      <div class="chips filtros" role="group" aria-label="Filtrar por frente">
        ${filtros.map(([k, n]) => `<button type="button" class="filtro${filtro === k ? ' ativo' : ''}" data-filtro="${k}">${n}</button>`).join('')}
      </div>
      ${pendentes.length ? pendentes.map(cartao).join('') : '<p class="vazio">Nenhuma decisão pendente aqui.</p>'}
      ${feitas.length ? `<section class="decididas"><p class="rotulo">Já decidido</p>${feitas.map(decidida).join('')}</section>` : ''}
      ${temExemplo ? `<p class="rodape-ex">Os cartões marcados como exemplo são só para mostrar. <button type="button" class="link" data-acao="limpar-ex">Apagar exemplos</button></p>` : ''}
      <button type="button" class="fab" data-acao="nova"><span aria-hidden="true">+</span> Decisão</button>`;
    raiz.onclick = clique;
  }

  function cartao(d) {
    const f = FRENTES[d.frente] || FRENTES.pessoal;
    const prazo = prazoTexto(d.prazo);
    return `<article class="decisao" style="--cor:${f.cor}">
      <div class="dec-topo"><span class="tag area">${f.nome}</span>${prazo ? `<span class="tag prazo">${prazo}</span>` : ''}${d.exemplo ? '<span class="tag ex">exemplo</span>' : ''}</div>
      <h2>${h(d.titulo)}</h2>
      ${d.contexto ? `<p class="dec-ctx">${h(d.contexto)}</p>` : ''}
      <div class="opcoes">
        ${d.opcoes.map(o => `<button type="button" class="opcao${o.recomendada ? ' rec' : ''}" data-dec="${h(d.id)}" data-op="${h(o.id)}">
          <span class="op-texto">${h(o.texto)}${o.recomendada ? ' <em>sugerida</em>' : ''}</span>
          ${o.efeito ? `<span class="op-efeito">${h(o.efeito)}</span>` : ''}
        </button>`).join('')}
      </div>
      ${d.propria ? `<button type="button" class="link apagar" data-apagar="${h(d.id)}">Apagar esta decisão</button>` : ''}
    </article>`;
  }

  function decidida(d) {
    const r = respostas[d.id];
    const o = d.opcoes.find(x => x.id === r.opcao) || { texto: '?' };
    return `<div class="dec-feita"><div><b>${h(d.titulo)}</b><span>${h(o.texto)}</span></div>
      <button type="button" class="link" data-desfazer="${h(d.id)}">Mudar</button></div>`;
  }

  function clique(ev) {
    const t = ev.target.closest('button');
    if (!t) return;
    if (t.dataset.filtro) { filtro = t.dataset.filtro; salvar(); return desenhar(); }
    if (t.dataset.dec) {
      respostas[t.dataset.dec] = { opcao: t.dataset.op, em: new Date().toISOString() };
      salvar(); App.aviso('Decidido. Uma coisa a menos na cabeça.'); return desenhar();
    }
    if (t.dataset.desfazer) { delete respostas[t.dataset.desfazer]; salvar(); return desenhar(); }
    if (t.dataset.apagar) {
      if (t.dataset.confirmar) { proprias = proprias.filter(d => d.id !== t.dataset.apagar); salvar(); return desenhar(); }
      t.dataset.confirmar = '1'; t.textContent = 'Toque de novo para apagar'; return;
    }
    if (t.dataset.acao === 'limpar-ex') { D.salvar('dec:exemplos-apagados', true); return desenhar(); }
    if (t.dataset.acao === 'nova') return abrirFicha();
  }

  function abrirFicha() {
    const dlg = document.createElement('dialog');
    dlg.className = 'ficha';
    dlg.innerHTML = `<form id="form-decisao">
      <div class="ficha-topo"><h2>Nova decisão</h2><button type="button" class="icone-btn" data-fechar aria-label="Fechar">×</button></div>
      <label class="campo"><span>O que você precisa decidir</span><input id="dec-titulo" name="titulo" required maxlength="100" placeholder="Ex.: Contratar editor para o projeto X?" autocomplete="off"></label>
      <fieldset class="campo"><legend>Frente</legend><div class="chips">${Object.keys(FRENTES).map((k, i) => `<label class="chip" style="--cor:${FRENTES[k].cor}"><input type="radio" name="frente" value="${k}" ${i === 0 ? 'checked' : ''}><span>${FRENTES[k].nome}</span></label>`).join('')}</div></fieldset>
      <label class="campo"><span>Opção 1</span><input id="dec-op1" name="op1" required maxlength="80"></label>
      <label class="campo"><span>Opção 2</span><input id="dec-op2" name="op2" required maxlength="80"></label>
      <label class="campo"><span>Opção 3 (opcional)</span><input id="dec-op3" name="op3" maxlength="80"></label>
      <label class="campo"><span>Decidir até (opcional)</span><input id="dec-prazo" name="prazo" type="date"></label>
      <div class="acoes"><button type="submit" class="principal">Salvar</button></div>
    </form>`;
    document.body.appendChild(dlg);
    const fechar = () => { dlg.close(); dlg.remove(); };
    dlg.addEventListener('cancel', () => setTimeout(() => dlg.remove(), 0));
    dlg.querySelector('[data-fechar]').onclick = fechar;
    dlg.querySelector('form').addEventListener('submit', s => {
      s.preventDefault();
      const f = new FormData(s.target);
      const ops = ['op1', 'op2', 'op3'].map(k => (f.get(k) || '').trim()).filter(Boolean);
      proprias.push({
        id: 'p-' + Date.now().toString(36), propria: true, frente: f.get('frente'),
        titulo: f.get('titulo').trim(), contexto: '', prazo: f.get('prazo') || null,
        opcoes: ops.map((t, i) => ({ id: String(i), texto: t }))
      });
      salvar(); fechar(); App.aviso('Decisão salva.'); desenhar();
    });
    dlg.showModal();
    setTimeout(() => dlg.querySelector('#dec-titulo').focus(), 50);
  }

  App.registrar({ id: 'decisoes', nome: 'Decisões', icone: '◆', montar });
})();
