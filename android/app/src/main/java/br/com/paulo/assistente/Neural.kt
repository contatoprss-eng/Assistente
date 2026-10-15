package br.com.paulo.assistente

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Vozes naturais grátis que rodam no próprio celular (sem internet depois de baixar),
 * pelo motor sherpa-onnx. Piper (dados CC0) e Kokoro (Apache 2.0).
 */
object Neural {

    class Pacote(val id: String, val repo: String, val arquivos: List<String>, val megas: Int)

    class VozN(
        val id: String, val nome: String, val descricao: String,
        val pacote: String, val kokoro: Boolean, val sid: Int,
    ) {
        /** nome que o painel vê na lista de vozes */
        val rotulo get() = "$nome · voz natural"
    }

    private const val HF = "https://huggingface.co/csukuangfj/"

    val PACOTES = listOf(
        Pacote("faber", "vits-piper-pt_BR-faber-medium", listOf("pt_BR-faber-medium.onnx", "tokens.txt"), 63),
        Pacote("cadu", "vits-piper-pt_BR-cadu-medium", listOf("pt_BR-cadu-medium.onnx", "tokens.txt"), 63),
        Pacote("jeff", "vits-piper-pt_BR-jeff-medium", listOf("pt_BR-jeff-medium.onnx", "tokens.txt"), 63),
        Pacote("kokoro", "kokoro-int8-multi-lang-v1_0", listOf("model.int8.onnx", "voices.bin", "tokens.txt"), 142),
    )

    val VOZES = listOf(
        VozN("faber", "Faber", "Masculina. Rápida e clara.", "faber", false, 0),
        VozN("cadu", "Cadu", "Masculina, tom mais grave.", "cadu", false, 0),
        VozN("jeff", "Jeff", "Masculina, tom mais leve.", "jeff", false, 0),
        VozN("dora", "Dora", "Feminina. A mais natural, demora um instante para começar a falar.", "kokoro", true, 42),
        VozN("alex", "Alex", "Masculina, mesmo pacote da Dora (baixa as duas juntas).", "kokoro", true, 43),
    )

    /** avisa o app quando muda a voz em uso (recebe o rótulo da voz, ou "" se nenhuma) */
    @JvmStatic var aoMudar: ((String) -> Unit)? = null

    fun pacote(id: String) = PACOTES.first { it.id == id }
    fun porRotulo(rotulo: String) = VOZES.firstOrNull { it.rotulo == rotulo }

    private fun pasta(ctx: Context, p: String) = File(ctx.filesDir, "vozes/$p")
    fun instalado(ctx: Context, p: String) = File(pasta(ctx, p), "ok").exists()

    /** vozes baixadas, a mais recente primeiro (o painel usa a primeira da lista) */
    fun instaladas(ctx: Context): List<VozN> =
        VOZES.filter { instalado(ctx, it.pacote) }
            .sortedByDescending { File(pasta(ctx, it.pacote), "ok").lastModified() * 10 - VOZES.indexOf(it) }

    /* ---------------- Download ---------------- */

    interface Progresso {
        fun andou(pct: Int)
        fun terminou(ok: Boolean)
    }

    private val baixando = mutableSetOf<String>()
    fun baixando(p: String) = synchronized(baixando) { p in baixando }

    fun baixar(ctx: Context, p: String, cb: Progresso) {
        synchronized(baixando) { if (!baixando.add(p)) return }
        Thread {
            var ok = false
            try {
                val pc = pacote(p)
                val dir = pasta(ctx, p)
                dir.mkdirs()
                File(dir, "ok").delete()
                val total = pc.megas * 1024L * 1024L
                var feito = 0L
                for (nome in pc.arquivos) {
                    val parte = File(dir, "$nome.part")
                    val con = URL(HF + pc.repo + "/resolve/main/" + nome).openConnection() as HttpURLConnection
                    con.connectTimeout = 20000
                    con.readTimeout = 30000
                    con.instanceFollowRedirects = true
                    if (con.responseCode !in 200..299) throw IllegalStateException("HTTP ${con.responseCode}")
                    con.inputStream.use { ent ->
                        FileOutputStream(parte).use { sai ->
                            val buf = ByteArray(64 * 1024)
                            var ultimo = -1
                            while (true) {
                                val n = ent.read(buf)
                                if (n < 0) break
                                sai.write(buf, 0, n)
                                feito += n
                                val pct = (feito * 100 / total).toInt().coerceIn(0, 99)
                                if (pct != ultimo) { ultimo = pct; cb.andou(pct) }
                            }
                        }
                    }
                    if (!parte.renameTo(File(dir, nome))) throw IllegalStateException("renomear")
                }
                File(dir, "ok").writeText("1")
                ok = true
            } catch (e: Exception) {
                ok = false
            } finally {
                synchronized(baixando) { baixando.remove(p) }
                cb.terminou(ok)
            }
        }.start()
    }

    fun remover(ctx: Context, p: String) {
        synchronized(this) {
            if (motorPacote == p) { motor?.release(); motor = null; motorPacote = null }
        }
        pasta(ctx, p).deleteRecursively()
    }

    /* ---------------- Dados do espeak (pronúncia) ---------------- */

    private fun espeak(ctx: Context): String {
        val dir = File(ctx.filesDir, "espeak-ng-data")
        val marca = File(dir, ".versao")
        val versao = BuildConfig.VERSION_CODE.toString()
        if (marca.exists() && marca.readText() == versao) return dir.absolutePath
        val lista = ctx.assets.open("espeak-ng-data.lista").bufferedReader().readLines()
        for (caminho in lista) {
            if (caminho.isBlank()) continue
            val destino = File(ctx.filesDir, caminho)
            destino.parentFile?.mkdirs()
            try {
                ctx.assets.open(caminho).use { ent -> FileOutputStream(destino).use { ent.copyTo(it) } }
            } catch (e: Exception) {
                // arquivo opcional que não entrou no APK
            }
        }
        marca.writeText(versao)
        return dir.absolutePath
    }

    /* ---------------- Fala ---------------- */

    interface Aviso {
        fun comecou(id: String)
        fun terminou(id: String, erro: Boolean)
    }

    private val fila = Executors.newSingleThreadExecutor()
    private val geracao = AtomicInteger(0)
    private var motor: OfflineTts? = null
    private var motorPacote: String? = null
    @Volatile private var trilha: AudioTrack? = null

    private fun carregar(ctx: Context, v: VozN): OfflineTts {
        synchronized(this) {
            motor?.let { if (motorPacote == v.pacote) return it }
            motor?.release()
            motor = null
            val dir = pasta(ctx, v.pacote).absolutePath
            val dados = espeak(ctx)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val modelo = if (v.kokoro) OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/model.int8.onnx", voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt", dataDir = dados, lang = "pt-br",
                ),
                numThreads = threads,
            ) else OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/" + pacote(v.pacote).arquivos[0],
                    tokens = "$dir/tokens.txt", dataDir = dados,
                ),
                numThreads = threads,
            )
            val m = OfflineTts(config = OfflineTtsConfig(model = modelo, maxNumSentences = 1))
            motor = m
            motorPacote = v.pacote
            return m
        }
    }

    fun falar(ctx: Context, v: VozN, texto: String, velocidade: Float, id: String, aviso: Aviso) {
        val minha = geracao.get()
        fila.execute {
            if (minha != geracao.get()) { aviso.terminou(id, false); return@execute }
            var trilhaLocal: AudioTrack? = null
            try {
                val m = carregar(ctx, v)
                val taxa = m.sampleRate()
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(taxa)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(AudioTrack.getMinBufferSize(taxa, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                trilhaLocal = t
                trilha = t
                var escritos = 0L
                var comecou = false
                m.generateWithCallback(texto, v.sid, velocidade.coerceIn(0.5f, 2f)) { amostras ->
                    if (minha != geracao.get()) return@generateWithCallback 0
                    if (!comecou) { comecou = true; t.play(); aviso.comecou(id) }
                    t.write(amostras, 0, amostras.size, AudioTrack.WRITE_BLOCKING)
                    escritos += amostras.size
                    if (minha != geracao.get()) 0 else 1
                }
                // stop() toca o que falta no buffer; espera acabar (com prazo, caso a posição não ande)
                if (comecou && minha == geracao.get()) {
                    val falta = escritos - t.playbackHeadPosition
                    t.stop()
                    val prazo = System.currentTimeMillis() + falta * 1000L / taxa + 400
                    while (minha == geracao.get() && t.playbackHeadPosition < escritos && System.currentTimeMillis() < prazo) Thread.sleep(40)
                }
                aviso.terminou(id, false)
            } catch (e: Throwable) {
                aviso.terminou(id, true)
            } finally {
                try { trilhaLocal?.stop() } catch (_: Exception) {}
                trilhaLocal?.release()
                if (trilha === trilhaLocal) trilha = null
            }
        }
    }

    fun parar() {
        geracao.incrementAndGet()
        try { trilha?.pause(); trilha?.flush() } catch (_: Exception) {}
    }
}
