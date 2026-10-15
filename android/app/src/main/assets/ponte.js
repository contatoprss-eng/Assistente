// Ponte de voz do app Assistente: troca o reconhecimento de fala e a leitura em voz alta
// do navegador pelos do próprio Android (a WebView não tem os dois).
// Roda no início de cada página/iframe do claude.ai e do conteúdo do artifact.
(function () {
  if (window.__assistenteVoz) return;
  var N = window.AssistenteNativo;
  if (!N) return;
  window.__assistenteVoz = true;

  // O painel desliga o microfone quando vê "; wv)" no navegador; aqui o microfone funciona.
  try {
    var ua = navigator.userAgent.replace('; wv)', ')') + ' AssistenteApp';
    Object.defineProperty(Navigator.prototype, 'userAgent', { get: function () { return ua; }, configurable: true });
  } catch (e) {}

  function enviar(m) { try { N.postMessage(JSON.stringify(m)); } catch (e) {} }

  function evento(alvo, tipo, extra) {
    var ev;
    try { ev = new Event(tipo); } catch (e) { ev = document.createEvent('Event'); ev.initEvent(tipo, false, false); }
    if (extra) for (var k in extra) try { Object.defineProperty(ev, k, { value: extra[k] }); } catch (e) {}
    try { alvo.dispatchEvent(ev); } catch (e) {}
    var h = alvo['on' + tipo];
    if (typeof h === 'function') try { h.call(alvo, ev); } catch (e) { setTimeout(function () { throw e; }); }
  }

  /* ---------- Reconhecimento de fala ---------- */
  var ativo = null;

  function lista(itens) {
    // imita SpeechRecognitionResultList: results[i][0].transcript, results[i].isFinal
    var l = itens.map(function (it) {
      var alt = { transcript: it.texto, confidence: 0.9 };
      var r = [alt]; r.isFinal = it.final; r.item = function (i) { return r[i]; };
      return r;
    });
    l.item = function (i) { return l[i]; };
    return l;
  }

  function Reconhecimento() {
    this._et = new EventTarget();
    this.lang = 'pt-BR';
    this.continuous = false;
    this.interimResults = false;
    this.maxAlternatives = 1;
    this.grammars = null;
  }
  Reconhecimento.prototype.addEventListener = function (t, f, o) { this._et.addEventListener(t, f, o); };
  Reconhecimento.prototype.removeEventListener = function (t, f, o) { this._et.removeEventListener(t, f, o); };
  Reconhecimento.prototype.dispatchEvent = function (ev) { return this._et.dispatchEvent(ev); };
  Reconhecimento.prototype.start = function () {
    if (ativo === this) throw new DOMException('recognition has already started', 'InvalidStateError');
    if (ativo) ativo.abort();
    ativo = this;
    enviar({ t: 'rec-inicia', lang: this.lang || 'pt-BR', continuo: !!this.continuous, parcial: !!this.interimResults });
  };
  Reconhecimento.prototype.stop = function () { if (ativo === this) enviar({ t: 'rec-para' }); };
  Reconhecimento.prototype.abort = function () {
    if (ativo !== this) return;
    enviar({ t: 'rec-cancela' });
  };

  /* ---------- Leitura em voz alta ---------- */
  var vozes = [], fila = {}, seq = 0, pendentes = 0;

  function Fala(texto) {
    this._et = new EventTarget();
    this.text = texto == null ? '' : String(texto);
    this.lang = 'pt-BR'; this.rate = 1; this.pitch = 1; this.volume = 1; this.voice = null;
  }
  Fala.prototype.addEventListener = Reconhecimento.prototype.addEventListener;
  Fala.prototype.removeEventListener = Reconhecimento.prototype.removeEventListener;
  Fala.prototype.dispatchEvent = Reconhecimento.prototype.dispatchEvent;

  var sintese = {
    _et: new EventTarget(),
    onvoiceschanged: null,
    get speaking() { return pendentes > 0; },
    get pending() { return pendentes > 1; },
    get paused() { return false; },
    getVoices: function () { return vozes.slice(); },
    speak: function (u) {
      if (!u) return;
      var id = 'f' + (++seq);
      fila[id] = u; pendentes++;
      enviar({ t: 'fala', id: id, texto: u.text, lang: u.lang || 'pt-BR', rate: +u.rate || 1, pitch: +u.pitch || 1, voz: u.voice ? u.voice.name : '' });
    },
    cancel: function () {
      fila = {}; pendentes = 0;
      enviar({ t: 'fala-para' });
    },
    pause: function () {}, resume: function () {},
    addEventListener: function (t, f, o) { this._et.addEventListener(t, f, o); },
    removeEventListener: function (t, f, o) { this._et.removeEventListener(t, f, o); },
    dispatchEvent: function (ev) { return this._et.dispatchEvent(ev); }
  };

  try { window.SpeechRecognition = Reconhecimento; } catch (e) {}
  try { window.webkitSpeechRecognition = Reconhecimento; } catch (e) {}
  try { Object.defineProperty(window, 'SpeechSynthesisUtterance', { value: Fala, configurable: true, writable: true }); } catch (e) {}
  try { Object.defineProperty(window, 'speechSynthesis', { value: sintese, configurable: true, writable: true }); } catch (e) {}

  /* ---------- Mensagens do Android ---------- */
  N.onmessage = function (msg) {
    var m;
    try { m = JSON.parse(msg.data); } catch (e) { return; }
    if (m.t === 'vozes') {
      vozes = m.vozes.map(function (v) {
        return { name: v.nome, lang: v.lang, localService: v.local, voiceURI: v.nome, 'default': !!v.padrao };
      });
      evento(sintese, 'voiceschanged');
      return;
    }
    if (m.t && m.t.indexOf('fala-') === 0) {
      var u = fila[m.id];
      if (!u) return;
      if (m.t === 'fala-comeca') evento(u, 'start', { utterance: u, charIndex: 0 });
      if (m.t === 'fala-fim' || m.t === 'fala-erro') {
        delete fila[m.id]; pendentes = Math.max(0, pendentes - 1);
        if (m.t === 'fala-erro') evento(u, 'error', { utterance: u, error: 'synthesis-failed' });
        evento(u, 'end', { utterance: u, charIndex: u.text.length });
      }
      return;
    }
    var r = ativo;
    if (!r) return;
    if (m.t === 'rec-comeca') { evento(r, 'start'); evento(r, 'audiostart'); }
    else if (m.t === 'rec-voz') evento(r, 'speechstart');
    else if (m.t === 'rec-resultado') {
      var itens = m.finais.map(function (x) { return { texto: x, final: true }; });
      if (m.parcial && r.interimResults) itens.push({ texto: m.parcial, final: false });
      if (!itens.length) return;
      evento(r, 'result', { results: lista(itens), resultIndex: Math.max(0, itens.length - 1) });
    }
    else if (m.t === 'rec-erro') evento(r, 'error', { error: m.erro, message: m.erro });
    else if (m.t === 'rec-fim') { ativo = null; evento(r, 'audioend'); evento(r, 'end'); }
  };
  enviar({ t: 'pronto' });
})();
