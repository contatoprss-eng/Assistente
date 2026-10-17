package br.com.paulo.assistente;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Abre o painel "Assistente Pessoal" (artifact do claude.ai) numa WebView com
 * microfone e voz do próprio Android. O painel continua no claude.ai; o app só
 * dá a ele o que o navegador do app do Claude não dá: ouvir e falar.
 */
public class MainActivity extends Activity {

    static final String PAINEL = "https://claude.ai/artifact/AHBfN98yssXUUfH77jVvzx";
    // só estas origens recebem a ponte de voz
    static final Set<String> ORIGENS = new HashSet<>(Arrays.asList(
            "https://claude.ai", "https://*.claude.ai",
            "https://claudeusercontent.com", "https://*.claudeusercontent.com"));

    private static final int PEDIDO_MIC = 1;
    private static final int PEDIDO_ARQUIVO = 2;

    private WebView web;
    private ProgressBar barra;
    private Voz voz;
    private ValueCallback<Uri[]> arquivoCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle salvo) {
        super.onCreate(salvo);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.WHITE);
        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barra.setIndeterminate(false);
        barra.setMax(100);
        barra.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF1D4ED8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8, Gravity.TOP);
        raiz.addView(barra, lp);
        setContentView(raiz);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        voz = new Voz(this);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "AssistenteNativo", ORIGENS,
                    (view, mensagem, origem, principal, resposta) -> voz.recebeu(mensagem.getData(), resposta));
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, lerAsset("ponte.js"), ORIGENS);
        }

        web.setWebViewClient(new Cliente());
        web.setWebChromeClient(new Cromo());

        if (salvo != null) web.restoreState(salvo);
        else web.loadUrl(PAINEL);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PEDIDO_MIC);
        }
    }

    private String lerAsset(String nome) {
        try (InputStream in = getAssets().open(nome)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    static boolean dentroDoApp(Uri u) {
        String h = u.getHost();
        if (h == null) return false;
        return h.equals("claude.ai") || h.endsWith(".claude.ai")
                || h.equals("anthropic.com") || h.endsWith(".anthropic.com")
                || h.equals("claudeusercontent.com") || h.endsWith(".claudeusercontent.com")
                || h.equals("challenges.cloudflare.com");
    }

    private void abrirFora(Uri u) {
        try {
            if ("intent".equals(u.getScheme())) {
                Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String reserva = i.getStringExtra("browser_fallback_url");
                    if (reserva != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(reserva)));
                }
                return;
            }
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignorado) {
        }
    }

    private void avisoGoogle() {
        new AlertDialog.Builder(this)
                .setTitle("Entre com seu e-mail")
                .setMessage("O Google não deixa entrar com o botão do Google dentro de apps.\n\n"
                        + "Toque em \"Continuar com e-mail\", use o mesmo e-mail da sua conta do Claude e digite o código que chega nele. "
                        + "É só uma vez: depois o app lembra de você.")
                .setPositiveButton("Entendi", null)
                .show();
    }

    private class Cliente extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String esquema = u.getScheme();
            if ("about".equals(esquema) || "blob".equals(esquema) || "data".equals(esquema)) return false;
            String h = u.getHost() == null ? "" : u.getHost();
            if (h.equals("accounts.google.com")) {
                avisoGoogle();
                return true;
            }
            // claude.ai/code (chat do projeto) não abre dentro do app: vai para o app do Claude ou o Chrome
            if (h.equals("claude.ai") && u.getPath() != null && u.getPath().startsWith("/code")) {
                abrirFora(u);
                return true;
            }
            if (("https".equals(esquema) || "http".equals(esquema)) && dentroDoApp(u)) return false;
            if (!req.isForMainFrame()) return false;
            abrirFora(u);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError erro) {
            if (!req.isForMainFrame()) return;
            String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                    + "<body style='font:18px system-ui,sans-serif;display:flex;flex-direction:column;align-items:center;"
                    + "justify-content:center;min-height:90vh;gap:16px;color:#0B1B3A;text-align:center;padding:24px'>"
                    + "<b style='font-size:22px;color:#1D4ED8'>Sem conexão</b><div>Confira a internet e toque abaixo.</div>"
                    + "<a href='" + PAINEL + "' style='background:#1D4ED8;color:#fff;padding:14px 28px;border-radius:14px;"
                    + "text-decoration:none;font-weight:700'>Tentar de novo</a></body>";
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        }
    }

    private class Cromo extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            barra.setProgress(p);
            barra.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onPermissionRequest(PermissionRequest pedido) {
            // getUserMedia de áudio: libera se o Android já deu o microfone ao app
            boolean temMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            String origem = pedido.getOrigin() == null ? "" : pedido.getOrigin().toString();
            boolean nosso = dentroDoApp(Uri.parse(origem));
            if (temMic && nosso && Arrays.asList(pedido.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                pedido.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pedido.deny();
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (arquivoCallback != null) arquivoCallback.onReceiveValue(null);
            arquivoCallback = callback;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(i, "Anexar"), PEDIDO_ARQUIVO);
            } catch (ActivityNotFoundException e) {
                arquivoCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent dados) {
        if (pedido != PEDIDO_ARQUIVO || arquivoCallback == null) {
            super.onActivityResult(pedido, resultado, dados);
            return;
        }
        Uri[] uris = null;
        if (resultado == RESULT_OK && dados != null) {
            if (dados.getClipData() != null) {
                int n = dados.getClipData().getItemCount();
                uris = new Uri[n];
                for (int k = 0; k < n; k++) uris[k] = dados.getClipData().getItemAt(k).getUri();
            } else if (dados.getData() != null) {
                uris = new Uri[]{dados.getData()};
            }
        }
        arquivoCallback.onReceiveValue(uris);
        arquivoCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle estado) {
        super.onSaveInstanceState(estado);
        web.saveState(estado);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // versão nova no GitHub Pages: baixa e abre a tela "Atualizar" do Android
        Atualizador.INSTANCE.conferir(this, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        voz.pausar();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        voz.encerrar();
        web.destroy();
        super.onDestroy();
    }
}
