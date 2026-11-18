package mx.jse.captura

/** Saca el primer link http(s) de un texto (lo que se pega o se comparte suele traer título + link). */
object Link {
    private val patron = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val dominio = Regex("""^(www\.)?[a-z0-9-]+(\.[a-z0-9-]+)+(/\S*)?$""", RegexOption.IGNORE_CASE)

    fun extraer(texto: String?): String? {
        val t = texto?.trim().orEmpty()
        if (t.isEmpty()) return null
        patron.find(t)?.let { return it.value.trimEnd('.', ',', ')', ']', '»') }
        // «youtube.com/watch?v=…» escrito sin https://
        return if (dominio.matches(t)) "https://$t" else null
    }

    fun sitio(url: String) = runCatching { android.net.Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: "link"
}
