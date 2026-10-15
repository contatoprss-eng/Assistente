package br.com.paulo.assistente

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Tela "Vozes": baixar com um toque as vozes naturais grátis e escolher qual o painel usa. */
class VozesActivity : Activity() {

    private val azul = 0xFF1D4ED8.toInt()
    private val tinta = 0xFF0B1B3A.toInt()
    private val suave = 0xFF5F6B85.toInt()
    private val linha = 0xFFE6EAF2.toInt()
    private val principal = Handler(Looper.getMainLooper())
    private lateinit var lista: LinearLayout
    private var tocador: MediaPlayer? = null
    private val progresso = mutableMapOf<String, Int>()

    override fun onCreate(salvo: Bundle?) {
        super.onCreate(salvo)
        val rolagem = ScrollView(this)
        rolagem.setBackgroundColor(Color.WHITE)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(20), dp(16), dp(24))
        rolagem.addView(col)

        col.addView(texto("Vozes naturais", 24f, azul, true))
        col.addView(texto("Grátis. Baixe uma vez e ela funciona até sem internet. " +
                "A voz que você baixar ou tocar em Usar passa a ser a voz do painel.", 16f, suave, false).also {
            (it.layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(6), 0, dp(14))
        })
        lista = LinearLayout(this)
        lista.orientation = LinearLayout.VERTICAL
        col.addView(lista)

        val voltar = botao("Voltar ao painel", false)
        voltar.setOnClickListener { finish() }
        col.addView(voltar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).also { it.topMargin = dp(12) })
        col.addView(texto("Vozes Piper (dados CC0) e Kokoro (Apache 2.0), baixadas do Hugging Face.", 12f, suave, false).also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
        })

        setContentView(rolagem)
        desenhar()
    }

    override fun onPause() {
        super.onPause()
        tocador?.release()
        tocador = null
    }

    private fun desenhar() {
        lista.removeAllViews()
        val usada = Neural.instaladas(this).firstOrNull()
        for (v in Neural.VOZES) {
            val p = Neural.pacote(v.pacote)
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(16), dp(14), dp(16), dp(14))
            card.background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(dp(1), if (v == usada) azul else linha)
            }
            val titulo = v.nome + if (v == usada) "  ✓ em uso" else ""
            card.addView(texto(titulo, 19f, if (v == usada) azul else tinta, true))
            card.addView(texto(v.descricao, 15f, suave, false))

            val fileira = LinearLayout(this)
            fileira.orientation = LinearLayout.HORIZONTAL
            fileira.gravity = Gravity.CENTER_VERTICAL
            val ouvir = botao("▶ Ouvir", false)
            ouvir.setOnClickListener { tocarAmostra(v.id) }
            fileira.addView(ouvir, LinearLayout.LayoutParams(0, dp(48), 1f).also { it.rightMargin = dp(8) })

            val instalado = Neural.instalado(this, v.pacote)
            val pct = progresso[v.pacote]
            val acao: Button
            when {
                pct != null || Neural.baixando(v.pacote) -> {
                    acao = botao("Baixando ${pct ?: 0}%", true); acao.isEnabled = false; acao.alpha = 0.75f
                }
                !instalado -> {
                    acao = botao("Baixar · ${p.megas} MB", true)
                    acao.setOnClickListener { baixar(v) }
                }
                v == usada -> {
                    acao = botao("Em uso ✓", true); acao.isEnabled = false; acao.alpha = 0.75f
                }
                else -> {
                    acao = botao("Usar", true)
                    acao.setOnClickListener { usar(v) }
                }
            }
            fileira.addView(acao, LinearLayout.LayoutParams(0, dp(48), 1.4f))
            card.addView(fileira, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) })

            if (instalado && pct == null) {
                val remover = TextView(this)
                remover.text = "Remover do celular"
                remover.setTextColor(suave)
                remover.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                remover.paintFlags = remover.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                remover.setPadding(0, dp(10), 0, 0)
                remover.setOnClickListener { confirmarRemover(v) }
                card.addView(remover)
            }
            lista.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(12) })
        }
    }

    private fun tocarAmostra(id: String) {
        tocador?.release()
        tocador = null
        try {
            val fd = assets.openFd("amostras/$id.wav")
            val mp = MediaPlayer()
            mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            mp.setOnCompletionListener { it.release(); if (tocador === it) tocador = null }
            mp.prepare()
            mp.start()
            tocador = mp
        } catch (_: Exception) {
        }
    }

    private fun baixar(v: Neural.VozN) {
        progresso[v.pacote] = 0
        desenhar()
        Neural.baixar(applicationContext, v.pacote, object : Neural.Progresso {
            override fun andou(pct: Int) = principal.post {
                if (progresso[v.pacote] != pct) { progresso[v.pacote] = pct; desenhar() }
            }.let { }

            override fun terminou(ok: Boolean) = principal.post {
                progresso.remove(v.pacote)
                if (ok) usar(v) else {
                    desenhar()
                    if (!isFinishing) AlertDialog.Builder(this@VozesActivity)
                        .setMessage("Não consegui baixar a voz agora. Confira a internet e toque em Baixar de novo.")
                        .setPositiveButton("Ok", null).show()
                }
            }.let { }
        })
    }

    private fun usar(v: Neural.VozN) {
        // a mais recente vira a primeira da lista (a que o painel escolhe)
        File(filesDir, "vozes/${v.pacote}/ok").setLastModified(System.currentTimeMillis())
        Neural.aoMudar?.invoke(v.rotulo)
        desenhar()
    }

    private fun confirmarRemover(v: Neural.VozN) {
        val juntas = Neural.VOZES.filter { it.pacote == v.pacote }.joinToString(" e ") { it.nome }
        AlertDialog.Builder(this)
            .setMessage("Remover a voz $juntas do celular? Dá para baixar de novo depois.")
            .setPositiveButton("Remover") { _, _ ->
                Neural.remover(applicationContext, v.pacote)
                Neural.aoMudar?.invoke(Neural.instaladas(this).firstOrNull()?.rotulo ?: "")
                desenhar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun texto(t: String, sp: Float, cor: Int, negrito: Boolean): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.setTextColor(cor)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (negrito) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return tv
    }

    private fun botao(t: String, cheio: Boolean): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.stateListAnimator = null
        b.setTextColor(if (cheio) Color.WHITE else azul)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (cheio) azul else 0xFFEEF2FF.toInt())
        }
        b.visibility = View.VISIBLE
        return b
    }
}
