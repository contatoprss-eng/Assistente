/*
 * Núcleo do app. Cada função nova é um módulo que se registra com
 * App.registrar({ id, nome, icone, montar(elemento) }).
 * Para criar um módulo: copie modulos/calendario.js como modelo,
 * adicione o <script> no index.html e o arquivo na lista do sw.js.
 */
(function () {
  const PREFIXO = 'assistente:';
  const modulos = [];
  let ativo = null;

  const dados = {
    ler(chave, padrao) {
      try {
        const v = localStorage.getItem(PREFIXO + chave);
        return v == null ? padrao : JSON.parse(v);
      } catch (e) { return padrao; }
    },
    salvar(chave, valor) {
      try { localStorage.setItem(PREFIXO + chave, JSON.stringify(valor)); } catch (e) {}
    }
  };

  let timerAviso;
  function aviso(texto) {
    const el = document.getElementById('aviso');
    el.textContent = texto;
    el.hidden = false;
    clearTimeout(timerAviso);
    timerAviso = setTimeout(() => { el.hidden = true; }, 3500);
  }

  let registroSW = null;
  async function registrarSW() {
    if (!('serviceWorker' in navigator)) return;
    try { registroSW = await navigator.serviceWorker.register('sw.js'); } catch (e) {}
  }

  function podeNotificar() {
    return 'Notification' in window && Notification.permission === 'granted';
  }

  async function pedirNotificacoes() {
    if (!('Notification' in window)) {
      aviso('Este navegador não mostra notificações. No iPhone, instale o app na tela inicial primeiro.');
      return false;
    }
    try {
      const r = await Notification.requestPermission();
      if (r === 'granted') { aviso('Notificações ligadas.'); return true; }
      aviso('Notificações bloqueadas. Libere nas configurações do navegador.');
    } catch (e) {
      aviso('Não deu para pedir permissão de notificação aqui.');
    }
    return false;
  }

  async function notificar(titulo, corpo, tag) {
    if (navigator.vibrate) { try { navigator.vibrate([200, 100, 200]); } catch (e) {} }
    if (podeNotificar()) {
      const opcoes = { body: corpo, tag, icon: 'icones/icone-192.png', badge: 'icones/icone-192.png', renotify: true };
      try {
        const reg = registroSW || (navigator.serviceWorker && await navigator.serviceWorker.getRegistration());
        if (reg) { await reg.showNotification(titulo, opcoes); return; }
        new Notification(titulo, opcoes);
        return;
      } catch (e) {}
    }
    aviso(titulo + (corpo ? ' · ' + corpo : ''));
  }

  function montarAbas() {
    const nav = document.getElementById('abas');
    nav.hidden = modulos.length < 2;
    document.body.classList.toggle('com-abas', !nav.hidden);
    nav.innerHTML = '';
    modulos.forEach(m => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'aba' + (m === ativo ? ' ativa' : '');
      b.innerHTML = '<span class="aba-icone" aria-hidden="true">' + m.icone + '</span><span>' + m.nome + '</span>';
      b.addEventListener('click', () => abrir(m.id));
      nav.appendChild(b);
    });
  }

  function abrir(id) {
    const m = modulos.find(x => x.id === id) || modulos[0];
    if (!m) return;
    if (ativo && ativo.desmontar) ativo.desmontar();
    ativo = m;
    const tela = document.getElementById('tela');
    tela.innerHTML = '';
    m.montar(tela);
    dados.salvar('aba', m.id);
    montarAbas();
  }

  window.App = {
    dados, aviso, notificar, podeNotificar, pedirNotificacoes,
    registrar(modulo) { modulos.push(modulo); },
    iniciar() {
      registrarSW();
      // Tarefas de fundo dos módulos (ex.: checar lembretes) rodam mesmo fora da aba deles.
      modulos.forEach(m => m.fundo && m.fundo());
      abrir(dados.ler('aba', modulos[0] && modulos[0].id));
    }
  };
})();
