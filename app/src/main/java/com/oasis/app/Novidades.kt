package com.oasis.app

import android.app.Activity
import androidx.appcompat.app.AlertDialog

object Novidades {
    private const val PREFS = "oasis_novidades"

    private class Secao(val id: String, val titulo: String, val itens: List<String>)

    private fun ler(act: Activity): List<Secao> {
        val texto = try {
            act.assets.open("novidades.txt").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return emptyList()
        }
        val lista = mutableListOf<Secao>()
        var id = ""
        var titulo = ""
        var itens = mutableListOf<String>()
        fun fechar() {
            if (id.isNotEmpty()) lista.add(Secao(id, titulo, itens))
            itens = mutableListOf()
        }
        for (linha in texto.lines()) {
            val l = linha.trim()
            when {
                l.startsWith("#id ") -> {
                    fechar()
                    id = l.removePrefix("#id ").trim()
                    titulo = ""
                }
                l.startsWith("#titulo ") -> titulo = l.removePrefix("#titulo ").trim()
                l.startsWith("- ") -> itens.add(l.removePrefix("- ").trim())
            }
        }
        fechar()
        return lista
    }

    /** Mostra a seção do topo. Se forcar=false, só mostra uma vez por id. */
    fun show(act: Activity, forcar: Boolean) {
        val topo = ler(act).firstOrNull() ?: return
        val prefs = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (!forcar && prefs.getString("visto", "") == topo.id) return
        prefs.edit().putString("visto", topo.id).apply()
        val msg = topo.itens.joinToString("\n\n") { "•  $it" }
        AlertDialog.Builder(act)
            .setTitle("O que mudou — ${topo.titulo}")
            .setMessage(msg)
            .setPositiveButton("Entendi", null)
            .show()
    }
}
