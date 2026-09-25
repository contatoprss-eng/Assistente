/* Módulo Calendário: compromissos com lembrete, repetição e área da vida. */
(function () {
  const AREAS = {
    trabalho: { nome: 'Trabalho', cor: 'var(--trabalho)' },
    familia:  { nome: 'Família',  cor: 'var(--familia)' },
    saude:    { nome: 'Saúde',    cor: 'var(--saude)' },
    lazer:    { nome: 'Lazer',    cor: 'var(--lazer)' }
  };
  const LEMBRETES = [
    [null, 'Sem lembrete'], [0, 'Na hora'], [5, '5 min antes'], [15, '15 min antes'],
    [30, '30 min antes'], [60, '1 hora antes'], [120, '2 horas antes'], [1440, '1 dia antes']
  ];
  const REPETIR = { nao: 'Não repete', diario: 'Todo dia', semanal: 'Toda semana', mensal: 'Todo mês' };
  const MESES = ['janeiro','fevereiro','março','abril','maio','junho','julho','agosto','setembro','outubro','novembro','dezembro'];
  const SEMANA = ['domingo','segunda','terça','quarta','quinta','sexta','sábado'];

  const D = App.dados;
  let eventos = D.ler('cal:eventos', null);
  let feitos = D.ler('cal:feitos', {});
  let disparados = D.ler('cal:disparados', {});

  // ---------- datas ----------
  const pad = n => String(n).padStart(2, '0');
  const iso = d => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  const deIso = s => { const [a, m, d] = s.split('-').map(Number); return new Date(a, m - 1, d); };
  const hojeIso = () => iso(new Date());
  const somaDias = (s, n) => { const d = deIso(s); d.setDate(d.getDate() + n); return iso(d); };

  if (!eventos) {
    const h = hojeIso();
    eventos = [
      { id: 'ex1', titulo: 'Caminhada de 30 min', data: h, hora: '07:00', lembrete: 15, repetir: 'diario', area: 'saude', notas: '', exemplo: true },
      { id: 'ex2', titulo: 'Reunião de pauta da agência', data: h, hora: '10:00', lembrete: 30, repetir: 'semanal', area: 'trabalho', notas: '', exemplo: true },
      { id: 'ex3', titulo: 'Jantar em família', data: somaDias(h, 1), hora: '19:30', lembrete: 60, repetir: 'nao', area: 'familia', notas: '', exemplo: true }
    ];
    D.salvar('cal:eventos', eventos);
  }
  const salvar = () => { D.salvar('cal:eventos', eventos); D.salvar('cal:feitos', feitos); D.salvar('cal:disparados', disparados); };

  function ocorreEm(ev, dia) {
    if (dia < ev.data) return false;
    if (ev.repetir === 'nao' || !ev.repetir) return dia === ev.data;
    if (ev.repetir === 'diario') return true;
    const a = deIso(ev.data), b = deIso(dia);
    if (ev.repetir === 'semanal') return a.getDay() === b.getDay();
    if (ev.repetir === 'mensal') return a.getDate() === b.getDate();
    return false;
  }
  const doDia = dia => eventos.filter(e => ocorreEm(e, dia))
    .sort((x, y) => (x.hora || '99').localeCompare(y.hora || '99'));
  const chave = (ev, dia) => ev.id + '|' + dia;

  // ---------- lembretes ----------
  function momento(ev, dia) {
    const d = deIso(dia);
    const [h, m] = (ev.hora || '09:00').split(':').map(Number);
    d.setHours(h, m, 0, 0);
    return d;
  }
  function checarLembretes() {
    const agora = Date.now();
    const hoje = hojeIso();
    [somaDias(hoje, -1), hoje, somaDias(hoje, 1), somaDias(hoje, 2)].forEach(dia => {
      doDia(dia).forEach(ev => {
        if (ev.lembrete == null) return;
        const k = chave(ev, dia);
        if (disparados[k] || feitos[k]) return;
        const quando = momento(ev, dia).getTime();
        const alvo = quando - ev.lembrete * 60000;
        // Só avisa se o horário do aviso passou há menos de 6 horas (evita enxurrada ao reabrir o app).
        if (agora >= alvo && agora - alvo < 6 * 3600000) {
          disparados[k] = agora;
          const falta = Math.round((quando - agora) / 60000);
          const corpo = (ev.hora ? ev.hora + ' · ' : '') + (falta > 1 ? 'em ' + textoFalta(falta) : 'agora');
          App.notificar(ev.titulo, corpo, k);
        }
      });
    });
    // limpa marcações antigas
    const limite = somaDias(hoje, -3);
    Object.keys(disparados).forEach(k => { if (k.split('|')[1] < limite) delete disparados[k]; });
    salvar();
  }
  function textoFalta(min) {
    if (min < 60) return min + ' min';
    if (min < 1440) { const h = Math.floor(min / 60), m = min % 60; return h + 'h' + (m ? pad(m) : ''); }
    return Math.round(min / 1440) + ' dia(s)';
  }

  // ---------- .ics (salva no calendário nativo do celular, que toca o alarme mesmo com o app fechado) ----------
  function gerarIcs(ev) {
    const [a, m, d] = ev.data.split('-');
    const esc = s => String(s || '').replace(/[\\;,]/g, c => '\\' + c).replace(/\n/g, '\\n');
    const linhas = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Assistente Paulo//PT-BR', 'BEGIN:VEVENT',
      'UID:' + ev.id + '@assistente-paulo', 'DTSTAMP:' + new Date().toISOString().replace(/[-:]/g, '').slice(0, 15) + 'Z',
      'SUMMARY:' + esc(ev.titulo)];
    if (ev.hora) {
      const [h, mi] = ev.hora.split(':');
      linhas.push('DTSTART:' + a + m + d + 'T' + h + mi + '00', 'DURATION:PT1H');
    } else {
      linhas.push('DTSTART;VALUE=DATE:' + a + m + d);
    }
    const rr = { diario: 'DAILY', semanal: 'WEEKLY', mensal: 'MONTHLY' }[ev.repetir];
    if (rr) linhas.push('RRULE:FREQ=' + rr);
    if (ev.notas) linhas.push('DESCRIPTION:' + esc(ev.notas));
    if (ev.lembrete != null) {
      linhas.push('BEGIN:VALARM', 'ACTION:DISPLAY', 'DESCRIPTION:' + esc(ev.titulo), 'TRIGGER:-PT' + ev.lembrete + 'M', 'END:VALARM');
    }
    linhas.push('END:VEVENT', 'END:VCALENDAR');
    return linhas.join('\r\n');
  }
  function baixarIcs(ev) {
    const blob = new Blob([gerarIcs(ev)], { type: 'text/calendar' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = ev.titulo.replace(/[^\wÀ-ú ]+/g, '').slice(0, 40) + '.ics';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  }

  // ---------- tela ----------
  let raiz, mesVisto, diaSel;
  const h = (s) => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  function montar(el) {
    raiz = el;
    const hoje = new Date();
    diaSel = diaSel || hojeIso();
    mesVisto = mesVisto || new Date(hoje.getFullYear(), hoje.getMonth(), 1);
    desenhar();
  }

  function desenhar() {
    const hoje = hojeIso();
    const dHoje = new Date();
    const semNotif = !App.podeNotificar();
    const temExemplo = eventos.some(e => e.exemplo);
    raiz.innerHTML = `
      <header class="topo">
        <p class="sobre">Hoje é ${SEMANA[dHoje.getDay()]}, ${dHoje.getDate()} de ${MESES[dHoje.getMonth()]}</p>
        <div class="mes-linha">
          <h1 class="mes">${MESES[mesVisto.getMonth()]} <span>${mesVisto.getFullYear()}</span></h1>
          <div class="mes-nav">
            <button type="button" class="icone-btn" data-acao="ant" aria-label="Mês anterior">‹</button>
            <button type="button" class="hoje-btn" data-acao="hoje">Hoje</button>
            <button type="button" class="icone-btn" data-acao="prox" aria-label="Próximo mês">›</button>
          </div>
        </div>
      </header>
      ${semNotif ? `<div class="faixa"><span>Ligue as notificações para receber os lembretes.</span><button type="button" data-acao="notif">Ligar</button></div>` : ''}
      ${grade(hoje)}
      ${equilibrio()}
      <section class="dia" aria-live="polite">${painelDia(hoje)}</section>
      ${temExemplo ? `<p class="rodape-ex">Os itens marcados como exemplo são só para mostrar. <button type="button" class="link" data-acao="limpar-ex">Apagar exemplos</button></p>` : ''}
      <button type="button" class="fab" data-acao="novo"><span aria-hidden="true">+</span> Novo</button>
    `;
    raiz.onclick = clique;
    raiz.onchange = mudanca;
  }

  function grade(hoje) {
    const ano = mesVisto.getFullYear(), mes = mesVisto.getMonth();
    const inicio = new Date(ano, mes, 1 - new Date(ano, mes, 1).getDay());
    let html = '<div class="grade" role="grid">';
    ['D', 'S', 'T', 'Q', 'Q', 'S', 'S'].forEach(l => { html += `<span class="dsem" aria-hidden="true">${l}</span>`; });
    for (let i = 0; i < 42; i++) {
      const d = new Date(inicio); d.setDate(inicio.getDate() + i);
      const s = iso(d);
      const fora = d.getMonth() !== mes;
      if (i === 35 && fora) break; // não desenha a 6ª linha quando ela é toda do mês seguinte
      const evs = doDia(s);
      const pontos = evs.slice(0, 3).map(e => `<i style="background:${AREAS[e.area].cor}"></i>`).join('');
      const cls = ['cel', fora && 'fora', s === hoje && 'hoje', s === diaSel && 'sel'].filter(Boolean).join(' ');
      html += `<button type="button" class="${cls}" data-dia="${s}" aria-label="${d.getDate()} de ${MESES[d.getMonth()]}, ${evs.length} compromisso(s)"><span>${d.getDate()}</span><span class="pontos">${pontos}</span></button>`;
    }
    return html + '</div>';
  }

  function equilibrio() {
    // Semana do dia selecionado, de domingo a sábado: quanto tempo foi para cada área da vida.
    const ini = somaDias(diaSel, -deIso(diaSel).getDay());
    const cont = { trabalho: 0, familia: 0, saude: 0, lazer: 0 };
    for (let i = 0; i < 7; i++) doDia(somaDias(ini, i)).forEach(e => cont[e.area]++);
    const itens = Object.keys(AREAS).map(k =>
      `<li class="${cont[k] === 0 ? 'zero' : ''}"><i style="background:${AREAS[k].cor}"></i>${AREAS[k].nome} <b>${cont[k]}</b></li>`).join('');
    return `<div class="equilibrio"><p class="rotulo">Equilíbrio da semana</p><ul>${itens}</ul></div>`;
  }

  function painelDia(hoje) {
    const d = deIso(diaSel);
    const titulo = diaSel === hoje ? 'Hoje' : diaSel === somaDias(hoje, 1) ? 'Amanhã' : SEMANA[d.getDay()];
    const evs = doDia(diaSel);
    let html = `<h2><span>${titulo}</span> ${d.getDate()} de ${MESES[d.getMonth()]}</h2>`;
    if (!evs.length) return html + `<p class="vazio">Nada marcado. Toque em <b>+ Novo</b> para adicionar.</p>`;
    html += '<ul class="lista">';
    evs.forEach(e => {
      const k = chave(e, diaSel);
      const feito = !!feitos[k];
      const lemb = e.lembrete == null ? '' : `<span class="tag">⏰ ${LEMBRETES.find(l => l[0] === e.lembrete)[1]}</span>`;
      const rep = e.repetir && e.repetir !== 'nao' ? `<span class="tag">↻ ${REPETIR[e.repetir]}</span>` : '';
      const ex = e.exemplo ? '<span class="tag ex">exemplo</span>' : '';
      html += `<li class="item${feito ? ' feito' : ''}" style="--cor:${AREAS[e.area].cor}">
        <label class="check"><input type="checkbox" data-feito="${k}" ${feito ? 'checked' : ''} aria-label="Marcar ${h(e.titulo)} como feito"><span></span></label>
        <button type="button" class="item-corpo" data-editar="${e.id}">
          <span class="hora">${e.hora || 'dia todo'}</span>
          <span class="titulo">${h(e.titulo)}</span>
          <span class="tags"><span class="tag area">${AREAS[e.area].nome}</span>${lemb}${rep}${ex}</span>
        </button>
      </li>`;
    });
    return html + '</ul>';
  }

  function clique(ev) {
    const t = ev.target.closest('button');
    if (!t) return;
    if (t.dataset.dia) {
      diaSel = t.dataset.dia;
      const d = deIso(diaSel);
      if (d.getMonth() !== mesVisto.getMonth()) mesVisto = new Date(d.getFullYear(), d.getMonth(), 1);
      return desenhar();
    }
    if (t.dataset.editar) return abrirFicha(eventos.find(e => e.id === t.dataset.editar));
    switch (t.dataset.acao) {
      case 'ant': mesVisto = new Date(mesVisto.getFullYear(), mesVisto.getMonth() - 1, 1); return desenhar();
      case 'prox': mesVisto = new Date(mesVisto.getFullYear(), mesVisto.getMonth() + 1, 1); return desenhar();
      case 'hoje': { const n = new Date(); mesVisto = new Date(n.getFullYear(), n.getMonth(), 1); diaSel = hojeIso(); return desenhar(); }
      case 'notif': return App.pedirNotificacoes().then(desenhar);
      case 'novo': return abrirFicha(null);
      case 'limpar-ex': eventos = eventos.filter(e => !e.exemplo); salvar(); App.aviso('Exemplos apagados.'); return desenhar();
    }
  }
  function mudanca(ev) {
    const k = ev.target.dataset.feito;
    if (!k) return;
    if (ev.target.checked) { feitos[k] = Date.now(); App.aviso('Feito! Um a menos.'); } else delete feitos[k];
    salvar(); desenhar();
  }

  // ---------- ficha de novo / editar ----------
  function abrirFicha(e) {
    const novo = !e;
    e = e || { id: '', titulo: '', data: diaSel, hora: '', lembrete: 15, repetir: 'nao', area: 'trabalho', notas: '' };
    const dlg = document.createElement('dialog');
    dlg.className = 'ficha';
    dlg.innerHTML = `
      <form method="dialog" id="form-evento">
        <div class="ficha-topo">
          <h2>${novo ? 'Novo compromisso' : 'Editar'}</h2>
          <button type="button" class="icone-btn" data-fechar aria-label="Fechar">×</button>
        </div>
        <label class="campo"><span>O que é</span>
          <input id="ev-titulo" name="titulo" required maxlength="80" placeholder="Ex.: Ligar para o cliente X" value="${h(e.titulo)}" autocomplete="off"></label>
        <div class="duas">
          <label class="campo"><span>Dia</span><input id="ev-data" name="data" type="date" required value="${e.data}"></label>
          <label class="campo"><span>Hora</span><input id="ev-hora" name="hora" type="time" value="${e.hora}"></label>
        </div>
        <fieldset class="campo"><legend>Área da vida</legend>
          <div class="chips">${Object.keys(AREAS).map(k => `<label class="chip" style="--cor:${AREAS[k].cor}"><input type="radio" name="area" value="${k}" ${e.area === k ? 'checked' : ''}><span>${AREAS[k].nome}</span></label>`).join('')}</div>
        </fieldset>
        <div class="duas">
          <label class="campo"><span>Lembrete</span><select id="ev-lembrete" name="lembrete">${LEMBRETES.map(([v, t]) => `<option value="${v == null ? '' : v}" ${e.lembrete === v ? 'selected' : ''}>${t}</option>`).join('')}</select></label>
          <label class="campo"><span>Repetir</span><select id="ev-repetir" name="repetir">${Object.keys(REPETIR).map(k => `<option value="${k}" ${e.repetir === k ? 'selected' : ''}>${REPETIR[k]}</option>`).join('')}</select></label>
        </div>
        <label class="campo"><span>Notas</span><textarea id="ev-notas" name="notas" rows="2" placeholder="Opcional">${h(e.notas || '')}</textarea></label>
        <p class="dica">Sem hora, o lembrete toca às 9h.</p>
        <div class="acoes">
          <button type="submit" class="principal">Salvar</button>
          ${novo ? '' : '<button type="button" class="secundario" data-ics>Pôr no calendário do celular</button><button type="button" class="perigo" data-excluir>Excluir</button>'}
        </div>
      </form>`;
    document.body.appendChild(dlg);
    const form = dlg.querySelector('form');
    const fechar = () => { dlg.close(); dlg.remove(); };
    dlg.addEventListener('cancel', () => setTimeout(() => dlg.remove(), 0));
    dlg.querySelector('[data-fechar]').onclick = fechar;
    const exc = dlg.querySelector('[data-excluir]');
    if (exc) exc.onclick = () => {
      if (exc.dataset.confirmar) {
        eventos = eventos.filter(x => x.id !== e.id); salvar(); fechar(); App.aviso('Compromisso excluído.'); desenhar();
      } else { exc.dataset.confirmar = '1'; exc.textContent = 'Toque de novo para excluir'; }
    };
    const ics = dlg.querySelector('[data-ics]');
    if (ics) ics.onclick = () => baixarIcs(e);
    form.addEventListener('submit', (s) => {
      s.preventDefault();
      const f = new FormData(form);
      const lemb = f.get('lembrete');
      const dados = {
        titulo: f.get('titulo').trim(), data: f.get('data'), hora: f.get('hora'),
        lembrete: lemb === '' ? null : Number(lemb), repetir: f.get('repetir'), area: f.get('area') || 'trabalho',
        notas: f.get('notas').trim()
      };
      if (!dados.titulo || !dados.data) return;
      if (novo) eventos.push(Object.assign({ id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6) }, dados));
      else {
        Object.assign(e, dados); delete e.exemplo;
        // mudou horário ou lembrete: pode avisar de novo
        Object.keys(disparados).forEach(k => { if (k.startsWith(e.id + '|')) delete disparados[k]; });
      }
      diaSel = dados.data;
      const d = deIso(diaSel); mesVisto = new Date(d.getFullYear(), d.getMonth(), 1);
      salvar(); fechar(); App.aviso(novo ? 'Compromisso salvo.' : 'Alterações salvas.'); desenhar(); checarLembretes();
    });
    dlg.showModal();
    if (novo) setTimeout(() => dlg.querySelector('#ev-titulo').focus(), 50);
  }

  App.registrar({
    id: 'calendario', nome: 'Agenda', icone: '▦',
    montar,
    fundo() {
      checarLembretes();
      setInterval(checarLembretes, 20000);
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') { checarLembretes(); if (raiz && raiz.isConnected) desenhar(); }
      });
    }
  });
})();
