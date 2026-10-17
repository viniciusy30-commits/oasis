package com.oasis.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.concurrent.thread

object Updater {
    const val REPO = "viniciusy30-commits/oasis"
    private const val PREFS = "oasis_updater"
    private const val SEIS_HORAS = 6L * 60 * 60 * 1000

    private fun instalada(act: Activity): Long =
        PackageInfoCompat.getLongVersionCode(act.packageManager.getPackageInfo(act.packageName, 0))

    private fun toast(act: Activity, msg: String) {
        act.runOnUiThread { Toast.makeText(act, msg, Toast.LENGTH_SHORT).show() }
    }

    /** Verificação automática ao abrir: no máximo a cada 6 horas e só avisa se houver versão nova. */
    fun autoCheck(act: Activity) {
        val p = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        if (agora - p.getLong("ultima", 0L) < SEIS_HORAS) return
        p.edit().putLong("ultima", agora).apply()
        check(act, false)
    }

    fun check(act: Activity, manual: Boolean) {
        if (manual) toast(act, "Procurando atualização…")
        thread {
            try {
                val con = URL("https://api.github.com/repos/$REPO/releases/latest")
                    .openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 8000
                con.setRequestProperty("Accept", "application/vnd.github+json")
                val codigo = con.responseCode
                if (codigo == 404) {
                    if (manual) toast(act, "Nenhuma versão publicada ainda.")
                    return@thread
                }
                if (codigo != 200) throw IOException("HTTP $codigo")
                val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
                val tag = json.getString("tag_name").removePrefix("v").trim().toLongOrNull() ?: 0L
                val assets = json.optJSONArray("assets")
                var url: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.getString("name").endsWith(".apk")) {
                            url = a.getString("browser_download_url")
                            break
                        }
                    }
                }
                val link = url
                act.runOnUiThread {
                    if (link != null && tag > instalada(act)) {
                        dialogoNova(act, tag, link)
                    } else if (manual) {
                        Toast.makeText(act, "Você já está na versão mais nova.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: UnknownHostException) {
                if (manual) toast(act, "Sem internet.")
            } catch (e: ConnectException) {
                if (manual) toast(act, "Sem internet.")
            } catch (e: SocketTimeoutException) {
                if (manual) toast(act, "Sem internet.")
            } catch (e: Exception) {
                if (manual) toast(act, "Não foi possível verificar agora.")
            }
        }
    }

    private fun dialogoNova(act: Activity, tag: Long, url: String) {
        AlertDialog.Builder(act)
            .setTitle("Nova versão disponível")
            .setMessage("A versão 1.$tag do Oasis está pronta. Deseja atualizar agora?")
            .setPositiveButton("Atualizar") { _, _ -> iniciar(act, url) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun iniciar(act: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("Permissão necessária")
                .setMessage(
                    "Para instalar a atualização, o Android precisa liberar \"Instalar apps desconhecidos\" para o Oasis. " +
                        "Toque em Abrir configuração, ative a opção e volte para o app."
                )
                .setPositiveButton("Abrir configuração") { _, _ ->
                    val i = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${act.packageName}")
                    )
                    act.startActivity(i)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        baixar(act, url)
    }

    private fun baixar(act: Activity, url: String) {
        val caixa = LinearLayout(act)
        caixa.orientation = LinearLayout.VERTICAL
        val pad = (20 * act.resources.displayMetrics.density).toInt()
        caixa.setPadding(pad, pad, pad, pad)
        val texto = TextView(act)
        texto.text = "Baixando… 0%"
        texto.gravity = Gravity.CENTER
        val barra = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        barra.max = 100
        caixa.addView(texto)
        caixa.addView(barra)
        val dlg = AlertDialog.Builder(act)
            .setTitle("Atualizando o Oasis")
            .setView(caixa)
            .setCancelable(false)
            .create()
        dlg.show()

        thread {
            try {
                val pasta = File(act.cacheDir, "updates")
                pasta.mkdirs()
                pasta.listFiles()?.forEach { it.delete() }
                val arq = File(pasta, "Oasis.apk")
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 15000
                con.readTimeout = 15000
                con.instanceFollowRedirects = true
                val total = con.contentLengthLong
                var lido = 0L
                var ultimo = -1
                con.inputStream.use { entrada ->
                    arq.outputStream().use { saida ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = entrada.read(buf)
                            if (n < 0) break
                            saida.write(buf, 0, n)
                            lido += n
                            if (total > 0) {
                                val pct = (lido * 100 / total).toInt()
                                if (pct != ultimo) {
                                    ultimo = pct
                                    act.runOnUiThread {
                                        barra.progress = pct
                                        texto.text = "Baixando… $pct%"
                                    }
                                }
                            }
                        }
                    }
                }
                act.runOnUiThread {
                    dlg.dismiss()
                    instalar(act, arq)
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(act, "Falha no download. Tente de novo.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun instalar(act: Activity, arq: File) {
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", arq)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
    }
}
