package dev.alder

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/** Local-only setup page, available for ten minutes while Settings is open. */
internal class ApiSetupServer(private val context: Context) {
    private val active = AtomicBoolean(false)
    private val token = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private var host = ""
    private var port = 0
    val address get() = "http://" + host + ":" + port + "/?token=" + token

    fun start(): String {
        if (active.get()) return address
        val ip = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?: error("Connect TV to a private local network")
        host = ip.hostAddress ?: error("No IPv4")
        server = ServerSocket(0, 4, ip).also { it.soTimeout = 1000; port = it.localPort }
        active.set(true)
        Thread({
            val deadline = System.nanoTime() + 600_000_000_000L
            while (active.get() && System.nanoTime() < deadline) {
                try { server?.accept()?.use(::handle) }
                catch (_: SocketTimeoutException) {}
                catch (_: Exception) { if (!active.get()) break }
            }
            stop()
        }, "AEM-API-setup").apply { isDaemon = true; start() }
        return address
    }

    fun stop() { active.set(false); runCatching { server?.close() }; server = null }

    private fun decode(s: String): Map<String, String> = s.split('&').mapNotNull {
        val p = it.split('=', limit = 2)
        if (p.size != 2) null else runCatching {
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p[1], "UTF-8")
        }.getOrNull()
    }.toMap()

    private fun handle(client: Socket) {
        client.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val line = reader.readLine() ?: return
        val p = line.split(" ")
        if (p.size != 3 || p[0] !in listOf("GET", "POST")) return reply(client, 400, "Bad request")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = reader.readLine() ?: return
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).lowercase()] = h.substring(i + 1).trim()
            if (headers.size > 40) return
        }
        if (headers["host"] != host + ":" + port) return reply(client, 403, "Forbidden")
        if (p[1].substringBefore('?') != "/" || decode(p[1].substringAfter('?', ""))["token"] != token)
            return reply(client, 403, "Forbidden")
        if (p[0] == "POST") {
            if (headers["origin"] != "http://" + host + ":" + port ||
                headers["content-type"]?.startsWith("application/x-www-form-urlencoded") != true)
                return reply(client, 403, "Forbidden")
            val size = headers["content-length"]?.toIntOrNull() ?: return reply(client, 400, "Length required")
            if (size !in 1..8192) return reply(client, 413, "Too large")
            val chars = CharArray(size)
            var count = 0
            while (count < size) {
                val n = reader.read(chars, count, size - count)
                if (n <= 0) return
                count += n
            }
            val form = decode(String(chars))
            if (form["token"] != token || form["service"] !in listOf("tmdb", "deepseek"))
                return reply(client, 403, "Forbidden")
            val service = form["service"]!!
            when (form["action"]) {
                "remove" -> ApiCredentials.set(context, service, null)
                "save" -> ApiCredentials.set(context, service, form["key"]?.takeIf { it.isNotBlank() }
                    ?: return reply(client, 400, "Missing key"))
                else -> return reply(client, 400, "Invalid action")
            }
        }
        reply(client, 200, page())
    }

    private fun page(): String {
        fun section(id: String, title: String): String {
            val status = if (ApiCredentials.configured(context, id)) "Configurada" else "Sin configurar"
            return "<section><h2>" + title + "</h2><p>" + status + "</p>" +
                "<form method='POST' action='/?token=" + token + "'>" +
                "<input type='hidden' name='token' value='" + token + "'>" +
                "<input type='hidden' name='service' value='" + id + "'>" +
                "<input type='password' name='key' maxlength='4096' autocomplete='off' placeholder='Clave API'>" +
                "<button name='action' value='save'>Guardar</button>" +
                "<button name='action' value='remove'>Eliminar</button></form></section>"
        }
        return "<!doctype html><html lang='es'><head><meta charset='utf-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<meta name='referrer' content='no-referrer'><title>AEM Media</title>" +
            "<style>body{font:16px system-ui;background:#101017;color:white;max-width:580px;margin:30px auto;padding:18px}" +
            "section{background:#262632;padding:20px;margin:15px 0;border-radius:12px}" +
            "input{display:block;padding:12px;margin:12px 0;width:90%}button{padding:12px;margin:5px}</style></head><body>" +
            "<h1>AEM Media</h1><p>Configuración temporal de claves API. No se muestran las claves guardadas.</p>" +
            section("tmdb", "TMDB") + section("deepseek", "DeepSeek") +
            "<p>Se cierra al salir de Ajustes o después de 10 minutos.</p></body></html>"
    }

    private fun reply(client: Socket, status: Int, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 " + status + " OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: " + body.size + "\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().write(head.toByteArray(Charsets.UTF_8) + body)
    }
}
