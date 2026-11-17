package mx.jse.captura

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ajustes de la app. El token del NAS se guarda cifrado con una llave del Android Keystore (no exportable). */
class Ajustes(ctx: Context) {
    private val p = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var calidad: String get() = p.getString("calidad", "1080")!!; set(v) = p.edit().putString("calidad", v).apply()
    var microfono: Boolean get() = p.getBoolean("microfono", false); set(v) = p.edit().putBoolean("microfono", v).apply()
    var audioInterno: Boolean get() = p.getBoolean("audioInterno", false); set(v) = p.edit().putBoolean("audioInterno", v).apply()
    var galeria: Boolean get() = p.getBoolean("galeria", false); set(v) = p.edit().putBoolean("galeria", v).apply()
    var direccion: String get() = p.getString("direccion", "")!!; set(v) = p.edit().putString("direccion", v).apply()
    var usuario: String get() = p.getString("usuario", "jse-captura")!!; set(v) = p.edit().putString("usuario", v).apply()
    var carpeta: String get() = p.getString("carpeta", "")!!; set(v) = p.edit().putString("carpeta", v).apply()
    var soloWifi: Boolean get() = p.getBoolean("soloWifi", true); set(v) = p.edit().putBoolean("soloWifi", v).apply()

    var token: String?
        get() = p.getString("token", null)?.let { descifrar(it) }
        set(v) { if (v == null) p.edit().remove("token").apply() else p.edit().putString("token", cifrar(v)).apply() }

    fun nasConfigurado() = direccion.isNotBlank() && token != null

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    private fun cifrar(t: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, llave()) }
        return Base64.encodeToString(c.iv + c.doFinal(t.toByteArray()), Base64.NO_WRAP)
    }

    private fun descifrar(s: String): String? = runCatching {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, b, 0, 12)) }
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrNull()

    companion object { private const val ALIAS = "jse-captura-token" }
}
