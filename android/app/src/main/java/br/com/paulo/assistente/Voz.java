package br.com.paulo.assistente;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fala e escuta do Android para o painel (ver assets/ponte.js). Tudo roda na thread principal. */
class Voz {

    private final Activity tela;
    private final Handler principal = new Handler(Looper.getMainLooper());

    // reconhecimento
    private SpeechRecognizer rec;
    private JavaScriptReplyProxy ouvinte;
    private final List<String> finais = new ArrayList<>();
    private String lang = "pt-BR";
    private boolean continuo, parado, ouvindo, ativo, comecou;

    // leitura em voz alta
    private TextToSpeech tts;
    private boolean ttsPronto;
    private final Set<JavaScriptReplyProxy> paginas = new LinkedHashSet<>();
    private final Map<String, Object[]> falas = new HashMap<>(); // id nativo -> {proxy, id da página}
    private final List<JSONObject> esperandoTts = new ArrayList<>();
    private final List<JavaScriptReplyProxy> esperandoProxy = new ArrayList<>();
    private int seq;

    Voz(Activity tela) {
        this.tela = tela;
        // a tela Vozes avisa quando uma voz natural é baixada, escolhida ou removida
        Neural.setAoMudar(rotulo -> {
            for (JavaScriptReplyProxy p : paginas) {
                mandarVozes(p);
                JSONObject u = msg("usar-voz");
                try {
                    u.put("nome", rotulo);
                } catch (Exception ignorado) {
                }
                mandar(p, u);
            }
            return kotlin.Unit.INSTANCE;
        });
        tts = new TextToSpeech(tela, status -> principal.post(() -> {
            ttsPronto = status == TextToSpeech.SUCCESS;
            if (!ttsPronto) return;
            tts.setLanguage(new Locale("pt", "BR"));
            tts.setOnUtteranceProgressListener(new Progresso());
            for (JavaScriptReplyProxy p : paginas) mandarVozes(p);
            for (int k = 0; k < esperandoTts.size(); k++) falar(esperandoProxy.get(k), esperandoTts.get(k));
            esperandoTts.clear();
            esperandoProxy.clear();
        }));
    }

    void recebeu(String dados, JavaScriptReplyProxy proxy) {
        JSONObject m;
        try {
            m = new JSONObject(dados);
        } catch (Exception e) {
            return;
        }
        switch (m.optString("t")) {
            case "pronto":
                paginas.add(proxy);
                if (ttsPronto || !Neural.INSTANCE.instaladas(tela).isEmpty()) mandarVozes(proxy);
                break;
            case "abrir-vozes":
                tela.startActivity(new Intent(tela, VozesActivity.class));
                break;
            case "rec-inicia":
                iniciar(proxy, m.optString("lang", "pt-BR"), m.optBoolean("continuo"));
                break;
            case "rec-para":
                parar();
                break;
            case "rec-cancela":
                cancelar();
                break;
            case "fala":
                if (falarNatural(proxy, m)) break;
                if (ttsPronto) falar(proxy, m);
                else {
                    esperandoTts.add(m);
                    esperandoProxy.add(proxy);
                }
                break;
            case "fala-para":
                esperandoTts.clear();
                esperandoProxy.clear();
                falas.clear();
                if (tts != null) tts.stop();
                Neural.INSTANCE.parar();
                break;
        }
    }

    private static void mandar(JavaScriptReplyProxy p, JSONObject m) {
        if (p == null) return;
        try {
            p.postMessage(m.toString());
        } catch (Exception ignorado) {
        }
    }

    private static JSONObject msg(String t) {
        JSONObject m = new JSONObject();
        try {
            m.put("t", t);
        } catch (Exception ignorado) {
        }
        return m;
    }

    /* ---------------- Reconhecimento ---------------- */

    private void iniciar(JavaScriptReplyProxy proxy, String lingua, boolean cont) {
        if (ativo) cancelar();
        ouvinte = proxy;
        lang = lingua.isEmpty() ? "pt-BR" : lingua;
        continuo = cont;
        parado = false;
        comecou = false;
        finais.clear();
        ativo = true;
        if (tela.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            tela.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            erro("not-allowed");
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(tela)) {
            erro("service-not-allowed");
            return;
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(tela);
            rec.setRecognitionListener(new Ouvido());
        }
        escutar();
    }

    private void escutar() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, tela.getPackageName());
        if (continuo) {
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L);
        }
        ouvindo = true;
        try {
            rec.startListening(i);
        } catch (Exception e) {
            ouvindo = false;
            erro("audio-capture");
        }
    }

    private void parar() {
        if (!ativo) return;
        parado = true;
        if (ouvindo && rec != null) rec.stopListening();
        else fim();
    }

    private void cancelar() {
        if (!ativo) return;
        parado = true;
        if (rec != null) rec.cancel();
        fim();
    }

    void pausar() {
        cancelar();
        Neural.INSTANCE.parar();
        if (tts != null) tts.stop();
        falas.clear();
    }

    private void resultado(String parcial) {
        JSONObject m = msg("rec-resultado");
        try {
            m.put("finais", new JSONArray(finais));
            m.put("parcial", parcial == null ? "" : parcial);
        } catch (Exception ignorado) {
        }
        mandar(ouvinte, m);
    }

    private void erro(String codigo) {
        JSONObject m = msg("rec-erro");
        try {
            m.put("erro", codigo);
        } catch (Exception ignorado) {
        }
        mandar(ouvinte, m);
        fim();
    }

    private void fim() {
        if (!ativo) return;
        ativo = false;
        ouvindo = false;
        mandar(ouvinte, msg("rec-fim"));
    }

    private static String primeiro(Bundle b) {
        if (b == null) return "";
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return l == null || l.isEmpty() || l.get(0) == null ? "" : l.get(0).trim();
    }

    private class Ouvido implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle p) {
            if (!ativo || comecou) return;
            comecou = true;
            mandar(ouvinte, msg("rec-comeca"));
        }

        @Override
        public void onBeginningOfSpeech() {
            if (ativo) mandar(ouvinte, msg("rec-voz"));
        }

        @Override
        public void onPartialResults(Bundle b) {
            if (!ativo) return;
            String p = primeiro(b);
            if (!p.isEmpty()) resultado(p);
        }

        @Override
        public void onResults(Bundle b) {
            ouvindo = false;
            if (!ativo) return;
            String t = primeiro(b);
            if (!t.isEmpty()) finais.add(t);
            resultado(null);
            if (continuo && !parado) principal.postDelayed(() -> { if (ativo && !parado) escutar(); }, 60);
            else fim();
        }

        @Override
        public void onError(int codigo) {
            ouvindo = false;
            if (!ativo) return;
            boolean silencio = codigo == SpeechRecognizer.ERROR_NO_MATCH || codigo == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (continuo && !parado && silencio) {
                principal.postDelayed(() -> { if (ativo && !parado) escutar(); }, 60);
                return;
            }
            if (!finais.isEmpty() || (parado && (silencio || codigo == SpeechRecognizer.ERROR_CLIENT))) {
                fim();
                return;
            }
            switch (codigo) {
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    erro("not-allowed");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    erro("network");
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    erro("audio-capture");
                    break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    erro("no-speech");
                    break;
                default:
                    erro("aborted");
            }
        }

        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int t, Bundle b) { }
    }

    /* ---------------- Leitura em voz alta ---------------- */

    private void mandarVozes(JavaScriptReplyProxy p) {
        JSONArray lista = new JSONArray();
        // vozes naturais baixadas primeiro (a mais recente é a que o painel usa)
        try {
            for (Neural.VozN v : Neural.INSTANCE.instaladas(tela)) {
                JSONObject o = new JSONObject();
                o.put("nome", v.getRotulo());
                o.put("lang", "pt-BR");
                o.put("local", true);
                o.put("padrao", lista.length() == 0);
                lista.put(o);
            }
        } catch (Exception ignorado) {
        }
        if (ttsPronto) try {
            Voice padrao = tts.getDefaultVoice();
            Set<Voice> vs = tts.getVoices();
            if (vs != null) for (Voice v : vs) {
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                JSONObject o = new JSONObject();
                o.put("nome", v.getName());
                o.put("lang", v.getLocale().toLanguageTag());
                o.put("local", !v.isNetworkConnectionRequired());
                o.put("padrao", padrao != null && padrao.getName().equals(v.getName()));
                lista.put(o);
            }
        } catch (Exception ignorado) {
        }
        JSONObject m = msg("vozes");
        try {
            m.put("vozes", lista);
        } catch (Exception ignorado) {
        }
        mandar(p, m);
    }

    /** fala com uma voz natural quando a página pediu uma (ou não pediu nenhuma e há uma baixada) */
    private boolean falarNatural(JavaScriptReplyProxy proxy, JSONObject m) {
        String nome = m.optString("voz", "");
        Neural.VozN v = nome.isEmpty() ? null : Neural.INSTANCE.porRotulo(nome);
        if (v == null && nome.isEmpty()) {
            java.util.List<Neural.VozN> ins = Neural.INSTANCE.instaladas(tela);
            if (!ins.isEmpty()) v = ins.get(0);
        }
        if (v == null || !Neural.INSTANCE.instalado(tela, v.getPacote())) return false;
        final String idPagina = m.optString("id");
        Neural.INSTANCE.falar(tela.getApplicationContext(), v, m.optString("texto", ""), (float) m.optDouble("rate", 1), idPagina,
                new Neural.Aviso() {
                    @Override
                    public void comecou(String id) {
                        principal.post(() -> mandarFala(proxy, "fala-comeca", id));
                    }

                    @Override
                    public void terminou(String id, boolean erro) {
                        principal.post(() -> mandarFala(proxy, erro ? "fala-erro" : "fala-fim", id));
                    }
                });
        return true;
    }

    private void mandarFala(JavaScriptReplyProxy proxy, String tipo, String id) {
        JSONObject r = msg(tipo);
        try {
            r.put("id", id);
        } catch (Exception ignorado) {
        }
        mandar(proxy, r);
    }

    private void falar(JavaScriptReplyProxy proxy, JSONObject m) {
        String idNativo = "n" + (++seq);
        falas.put(idNativo, new Object[]{proxy, m.optString("id")});
        tts.setSpeechRate((float) m.optDouble("rate", 1));
        tts.setPitch((float) m.optDouble("pitch", 1));
        String nome = m.optString("voz", "");
        boolean achou = false;
        if (!nome.isEmpty()) {
            try {
                for (Voice v : tts.getVoices()) {
                    if (v.getName().equals(nome)) {
                        tts.setVoice(v);
                        achou = true;
                        break;
                    }
                }
            } catch (Exception ignorado) {
            }
        }
        if (!achou) tts.setLanguage(Locale.forLanguageTag(m.optString("lang", "pt-BR")));
        tts.speak(m.optString("texto", ""), TextToSpeech.QUEUE_ADD, null, idNativo);
    }

    private void avisar(String idNativo, String tipo, boolean terminou) {
        principal.post(() -> {
            Object[] f = terminou ? falas.remove(idNativo) : falas.get(idNativo);
            if (f == null) return;
            JSONObject m = msg(tipo);
            try {
                m.put("id", f[1]);
            } catch (Exception ignorado) {
            }
            mandar((JavaScriptReplyProxy) f[0], m);
        });
    }

    private class Progresso extends UtteranceProgressListener {
        @Override public void onStart(String id) { avisar(id, "fala-comeca", false); }
        @Override public void onDone(String id) { avisar(id, "fala-fim", true); }
        @Override public void onError(String id) { avisar(id, "fala-erro", true); }
        @Override public void onStop(String id, boolean interrompida) { avisar(id, "fala-fim", true); }
    }

    void encerrar() {
        if (rec != null) rec.destroy();
        if (tts != null) tts.shutdown();
    }
}
