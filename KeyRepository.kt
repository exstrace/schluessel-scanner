package de.ric.schluessel

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Speichert alle Schlüssel lokal im App-Speicher:
 *   files/keys.json          – Stammdaten
 *   files/images/*.jpg       – Fotos (zugeschnitten auf den Bart)
 *   files/images/*.orb       – vorberechnete Bildmerkmale
 */
object KeyRepository {

    private lateinit var root: File
    lateinit var imagesDir: File
        private set

    val keys = mutableListOf<KeyEntry>()
    private var initialized = false

    private val jsonFile get() = File(root, "keys.json")
    private val safeName = Regex("[A-Za-z0-9_.-]+")

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        root = ctx.applicationContext.filesDir
        imagesDir = File(root, "images").apply { mkdirs() }
        load()
        initialized = true
    }

    private fun load() {
        keys.clear()
        if (!jsonFile.exists()) return
        runCatching {
            keys.addAll(parseKeys(JSONObject(jsonFile.readText()).optJSONArray("keys")))
        }
    }

    fun parseKeys(arr: JSONArray?): List<KeyEntry> {
        if (arr == null) return emptyList()
        val out = ArrayList<KeyEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val imgs = mutableListOf<KeyImage>()
            o.optJSONArray("images")?.let { ia ->
                for (j in 0 until ia.length()) {
                    val io = ia.getJSONObject(j)
                    val file = io.optString("file")
                    if (file.matches(safeName)) imgs.add(KeyImage(file, io.optString("side", SIDE_A)))
                }
            }
            val id = o.optString("id")
            if (!id.matches(safeName)) continue
            out.add(
                KeyEntry(
                    id = id,
                    name = o.optString("name"),
                    number = o.optString("number"),
                    location = o.optString("location"),
                    note = o.optString("note"),
                    created = o.optLong("created", System.currentTimeMillis()),
                    images = imgs
                )
            )
        }
        return out
    }

    fun toJson(list: List<KeyEntry> = keys): JSONObject {
        val arr = JSONArray()
        for (k in list) {
            val imgs = JSONArray()
            for (img in k.images) imgs.put(JSONObject().put("file", img.file).put("side", img.side))
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("name", k.name)
                    .put("number", k.number)
                    .put("location", k.location)
                    .put("note", k.note)
                    .put("created", k.created)
                    .put("images", imgs)
            )
        }
        return JSONObject().put("version", 1).put("keys", arr)
    }

    @Synchronized
    fun save() {
        val tmp = File(root, "keys.json.tmp")
        tmp.writeText(toJson().toString(2))
        if (!tmp.renameTo(jsonFile)) {
            jsonFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun get(id: String): KeyEntry? = keys.find { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun add(entry: KeyEntry) {
        keys.add(entry)
        save()
    }

    fun delete(entry: KeyEntry) {
        entry.images.forEach { deleteImageFiles(it.file) }
        keys.remove(entry)
        save()
    }

    fun removeImage(entry: KeyEntry, img: KeyImage) {
        deleteImageFiles(img.file)
        entry.images.remove(img)
        save()
    }

    fun imageFile(name: String) = File(imagesDir, name)
    fun featureFile(name: String) = File(imagesDir, name.substringBeforeLast('.') + ".orb")

    private fun deleteImageFiles(name: String) {
        imageFile(name).delete()
        featureFile(name).delete()
        KeyMatcher.evict(name)
        ImageUtil.evictThumb(name)
    }

    /**
     * Kopiert ein aufgenommenes Foto in den Speicher und berechnet seine Merkmale.
     * Läuft im Hintergrund. Der Eintrag selbst wird NICHT gespeichert –
     * das erledigt der Aufrufer auf dem Main-Thread.
     */
    fun importPhoto(entryId: String, src: File, side: String): KeyImage {
        val name = "${entryId}_${side}_${System.currentTimeMillis()}_${(1000..9999).random()}.jpg"
        src.copyTo(imageFile(name), overwrite = true)
        KeyMatcher.featuresFor(name)
        return KeyImage(name, side)
    }

    // ---------------------------------------------------------------- Export / Import

    fun export(ctx: Context, uri: Uri) {
        val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Datei konnte nicht geöffnet werden")
        ZipOutputStream(BufferedOutputStream(os)).use { zip ->
            zip.putNextEntry(ZipEntry("keys.json"))
            zip.write(toJson().toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (k in keys) for (img in k.images) {
                for (f in listOf(imageFile(img.file), featureFile(img.file))) {
                    if (!f.exists()) continue
                    zip.putNextEntry(ZipEntry("images/" + f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    data class ImportResult(val added: Int, val updated: Int)

    /** Führt eine exportierte Datenbank mit der vorhandenen zusammen (gleiche ID = aktualisieren). */
    fun import(ctx: Context, uri: Uri): ImportResult {
        val tmpDir = File(ctx.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        var json: String? = null
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Datei konnte nicht geöffnet werden")
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name == "keys.json") {
                    json = zis.readBytes().toString(Charsets.UTF_8)
                } else if (name.startsWith("images/")) {
                    val base = name.substringAfterLast('/')
                    if (base.matches(safeName)) File(tmpDir, base).outputStream().use { zis.copyTo(it) }
                }
            }
        }
        val text = json ?: throw IOException("Keine gültige Schlüssel-Datenbank (keys.json fehlt)")
        val imported = parseKeys(JSONObject(text).optJSONArray("keys"))

        var added = 0
        var updated = 0
        for (k in imported) {
            val available = k.images.filter { File(tmpDir, it.file).exists() || imageFile(it.file).exists() }
            for (img in available) {
                val src = File(tmpDir, img.file)
                if (src.exists()) src.copyTo(imageFile(img.file), overwrite = true)
                val fsrc = File(tmpDir, featureFile(img.file).name)
                if (fsrc.exists()) fsrc.copyTo(featureFile(img.file), overwrite = true)
                KeyMatcher.evict(img.file)
                ImageUtil.evictThumb(img.file)
            }
            val existing = get(k.id)
            if (existing == null) {
                k.images.clear(); k.images.addAll(available)
                keys.add(k)
                added++
            } else {
                existing.name = k.name
                existing.number = k.number
                existing.location = k.location
                existing.note = k.note
                for (img in available) if (existing.images.none { it.file == img.file }) existing.images.add(img)
                updated++
            }
        }
        save()
        tmpDir.deleteRecursively()
        return ImportResult(added, updated)
    }
}
