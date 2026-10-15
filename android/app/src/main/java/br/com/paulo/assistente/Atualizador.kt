package br.com.paulo.assistente

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * O app se atualiza sozinho: confere painel/versao.json no GitHub Pages; se houver
 * versão nova, baixa o APK e abre a tela do Android para o toque em "Atualizar".
 * O APK novo precisa ser assinado com a mesma chave.
 */
object Atualizador {

    private const val VERSAO = "https://contatoprss-eng.github.io/Assistente/painel/versao.json"
    private const val INTERVALO = 30 * 60 * 1000L
    @Volatile private var trabalhando = false
    private val principal = Handler(Looper.getMainLooper())

    fun conferir(tela: Activity, forcar: Boolean = false) {
        val prefs = tela.getSharedPreferences("atualizador", Context.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        if (trabalhando) return
        if (!forcar && agora - prefs.getLong("ultima", 0) < INTERVALO) return
        trabalhando = true
        prefs.edit().putLong("ultima", agora).apply()
        Thread {
            try {
                val json = JSONObject(baixarTexto("$VERSAO?t=$agora"))
                val nova = json.getInt("versao")
                val apk = json.getString("apk")
                if (nova > BuildConfig.VERSION_CODE) {
                    principal.post { preparar(tela, apk, nova) }
                    return@Thread
                }
            } catch (_: Exception) {
            }
            trabalhando = false
        }.start()
    }

    private fun preparar(tela: Activity, apk: String, nova: Int) {
        if (tela.isFinishing) { trabalhando = false; return }
        if (!tela.packageManager.canRequestPackageInstalls()) {
            trabalhando = false
            AlertDialog.Builder(tela)
                .setTitle("Versão nova do Assistente")
                .setMessage("Para eu atualizar o app sozinho, o Android pede uma permissão uma única vez.\n\n" +
                        "Na próxima tela, ligue \"Permitir desta fonte\" e volte.")
                .setPositiveButton("Abrir") { _, _ ->
                    tela.getSharedPreferences("atualizador", Context.MODE_PRIVATE).edit().putLong("ultima", 0).apply()
                    tela.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + tela.packageName)))
                }
                .setNegativeButton("Agora não", null)
                .show()
            return
        }
        val ctx = tela.applicationContext
        Thread {
            try {
                val arquivo = File(ctx.cacheDir, "atualizacao-$nova.apk")
                baixarArquivo(apk, arquivo)
                instalar(ctx, arquivo)
            } catch (_: Exception) {
                ctx.getSharedPreferences("atualizador", Context.MODE_PRIVATE).edit().putLong("ultima", 0).apply()
            } finally {
                trabalhando = false
            }
        }.start()
    }

    private fun abrir(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.useCaches = false
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return c
    }

    private fun baixarTexto(url: String) = abrir(url).inputStream.bufferedReader().use { it.readText() }

    private fun baixarArquivo(url: String, destino: File) {
        val parte = File(destino.path + ".part")
        abrir(url + (if ('?' in url) "&" else "?") + "t=" + System.currentTimeMillis()).inputStream.use { ent ->
            FileOutputStream(parte).use { ent.copyTo(it, 64 * 1024) }
        }
        destino.delete()
        if (!parte.renameTo(destino)) throw IllegalStateException("renomear")
    }

    private fun instalar(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { sessao ->
            sessao.openWrite("app.apk", 0, apk.length()).use { sai ->
                FileInputStream(apk).use { it.copyTo(sai, 64 * 1024) }
                sessao.fsync(sai)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, Instalacao::class.java), flags)
            sessao.commit(pend.intentSender)
        }
    }

    /** Recebe o andamento da instalação; abre a tela "Atualizar" do Android quando ela pede o toque. */
    class Instalacao : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirmar = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirmar)
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                ctx.getSharedPreferences("atualizador", Context.MODE_PRIVATE).edit().putLong("ultima", 0).apply()
            }
        }
    }
}
