package com.mediacenter.app.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

data class EpubChapter(
    val href: String,
    val file: File,
    val title: String,
)

data class EpubTocItem(
    val title: String,
    val href: String,
    val level: Int,
    val spineIndex: Int,
)

class EpubBook(
    val title: String,
    val rootDir: File,
    val chapters: List<EpubChapter>,
    val toc: List<EpubTocItem>,
)

object EpubLoader {

    fun open(context: Context, uri: Uri, filePath: String?, resolveLocalPath: (Uri, String?) -> String?): EpubBook {
        val source = resolveSource(context, uri, filePath, resolveLocalPath)
        val dest = File(context.cacheDir, "epub/${source.nameWithoutExtension}-${source.length()}")
        if (!File(dest, ".ready").exists()) {
            dest.deleteRecursively()
            dest.mkdirs()
            unzip(source, dest)
            File(dest, ".ready").writeText("1")
        }
        val opf = findOpf(dest)
        val chapters = parseSpine(dest, opf)
        val toc = parseToc(dest, opf, chapters)
        val titled = applyTocTitles(chapters, toc)
        val title = parseTitle(opf).ifBlank { source.nameWithoutExtension }
        if (titled.isEmpty()) error("没有可读章节")
        return EpubBook(title, dest, titled, toc.ifEmpty { titled.mapIndexed { i, c ->
            EpubTocItem(c.title, c.href, 0, i)
        } })
    }

    private fun resolveSource(
        context: Context,
        uri: Uri,
        filePath: String?,
        resolveLocalPath: (Uri, String?) -> String?,
    ): File {
        resolveLocalPath(uri, filePath)?.let { path ->
            val file = File(path)
            if (file.isFile) return file
        }
        val dest = File(context.cacheDir, "epub-src-${uri.hashCode()}.epub")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        } ?: error("无法读取电子书")
        return dest
    }

    private fun unzip(source: File, dest: File) {
        val root = dest.canonicalFile
        var written = 0L
        ZipFile(source).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val out = File(root, entry.name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            written += n
                            if (written > MAX_UNZIP_BYTES) error("电子书太大")
                            output.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
    }

    private fun findOpf(root: File): File {
        val container = File(root, "META-INF/container.xml")
        if (container.isFile) {
            val text = container.readText()
            val match = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(text)
            match?.groupValues?.get(1)?.let { path ->
                val opf = File(root, path)
                if (opf.isFile) return opf
            }
        }
        return root.walkTopDown().firstOrNull { it.extension.equals("opf", true) }
            ?: error("找不到电子书目录")
    }

    private fun parseTitle(opf: File): String {
        val text = opf.readText()
        return Regex("<dc:title[^>]*>([^<]+)</dc:title>", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
    }

    private fun parseSpine(root: File, opf: File): List<EpubChapter> {
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        opf.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfter(':')) {
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            val props = parser.getAttributeValue(null, "properties").orEmpty()
                            val media = parser.getAttributeValue(null, "media-type").orEmpty()
                            if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                manifest[id] = ManifestItem(href, props, media)
                            }
                        }
                        "itemref" -> {
                            parser.getAttributeValue(null, "idref")?.let(spine::add)
                        }
                    }
                }
                event = parser.next()
            }
        }
        val opfDir = opf.parentFile ?: root
        return spine.mapNotNull { id ->
            val href = manifest[id]?.href ?: return@mapNotNull null
            val file = File(opfDir, href).canonicalFile
            if (!file.isFile) return@mapNotNull null
            EpubChapter(href = href, file = file, title = file.nameWithoutExtension)
        }
    }

    private fun parseToc(root: File, opf: File, chapters: List<EpubChapter>): List<EpubTocItem> {
        val opfDir = opf.parentFile ?: root
        val opfText = runCatching { opf.readText() }.getOrDefault("")
        val navHref = Regex(
            "properties\\s*=\\s*\"[^\"]*nav[^\"]*\"[^>]*href\\s*=\\s*\"([^\"]+)\"|href\\s*=\\s*\"([^\"]+)\"[^>]*properties\\s*=\\s*\"[^\"]*nav",
            RegexOption.IGNORE_CASE,
        ).find(opfText)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        if (!navHref.isNullOrBlank()) {
            val nav = File(opfDir, navHref)
            if (nav.isFile) {
                val items = parseNavXhtml(nav.readText(), chapters)
                if (items.isNotEmpty()) return items
            }
        }
        val ncxHref = Regex(
            "media-type\\s*=\\s*\"application/x-dtbncx\\+xml\"[^>]*href\\s*=\\s*\"([^\"]+)\"|href\\s*=\\s*\"([^\"]+\\.ncx)\"",
            RegexOption.IGNORE_CASE,
        ).find(opfText)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        if (!ncxHref.isNullOrBlank()) {
            val ncx = File(opfDir, ncxHref)
            if (ncx.isFile) {
                val items = parseNcx(ncx.readText(), chapters)
                if (items.isNotEmpty()) return items
            }
        }
        root.walkTopDown().firstOrNull { it.extension.equals("ncx", true) }?.let { ncx ->
            val items = parseNcx(ncx.readText(), chapters)
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    private fun parseNavXhtml(text: String, chapters: List<EpubChapter>): List<EpubTocItem> {
        val items = ArrayList<EpubTocItem>()
        val nav = Regex(
            "<nav[^>]*epub:type\\s*=\\s*\"toc\"[^>]*>([\\s\\S]*?)</nav>",
            RegexOption.IGNORE_CASE,
        ).find(text)?.groupValues?.get(1) ?: text
        val link = Regex("<a[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
        val olOpen = Regex("<ol\\b", RegexOption.IGNORE_CASE)
        val olClose = Regex("</ol>", RegexOption.IGNORE_CASE)
        var level = 0
        var cursor = 0
        val tokens = Regex("<ol\\b|</ol>|<a\\b[^>]*>[\\s\\S]*?</a>", RegexOption.IGNORE_CASE)
            .findAll(nav)
        for (token in tokens) {
            val raw = token.value
            when {
                olOpen.containsMatchIn(raw) && !raw.startsWith("<a", ignoreCase = true) -> level++
                olClose.containsMatchIn(raw) -> level = (level - 1).coerceAtLeast(0)
                else -> {
                    val match = link.find(raw) ?: continue
                    val href = match.groupValues[1]
                    val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    if (title.isBlank()) continue
                    items += EpubTocItem(
                        title = title,
                        href = href,
                        level = (level - 1).coerceAtLeast(0),
                        spineIndex = spineIndexOf(chapters, href),
                    )
                }
            }
            cursor = token.range.last
        }
        return items.filter { it.spineIndex >= 0 }
    }

    private fun parseNcx(text: String, chapters: List<EpubChapter>): List<EpubTocItem> {
        val items = ArrayList<EpubTocItem>()
        val point = Regex(
            "<navPoint[\\s\\S]*?<text>([\\s\\S]*?)</text>[\\s\\S]*?src\\s*=\\s*\"([^\"]+)\"",
            RegexOption.IGNORE_CASE,
        )
        val levels = ArrayDeque<Int>()
        val open = Regex("<navPoint\\b", RegexOption.IGNORE_CASE)
        val close = Regex("</navPoint>", RegexOption.IGNORE_CASE)
        val tokens = Regex("<navPoint\\b|</navPoint>|<text>[\\s\\S]*?</text>|[\\s\\S]{0}", RegexOption.IGNORE_CASE)
        var level = 0
        val combined = Regex(
            "<navPoint\\b[^>]*>|<text>([\\s\\S]*?)</text>|<content[^>]*src\\s*=\\s*\"([^\"]+)\"[^>]*/?>|</navPoint>",
            RegexOption.IGNORE_CASE,
        )
        var pendingTitle: String? = null
        for (token in combined.findAll(text)) {
            val raw = token.value
            when {
                raw.startsWith("<navPoint", ignoreCase = true) -> level++
                raw.startsWith("</navPoint", ignoreCase = true) -> level = (level - 1).coerceAtLeast(0)
                raw.startsWith("<text", ignoreCase = true) ->
                    pendingTitle = token.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
                raw.startsWith("<content", ignoreCase = true) -> {
                    val href = token.groupValues[2]
                    val title = pendingTitle.orEmpty()
                    pendingTitle = null
                    if (title.isBlank()) continue
                    val index = spineIndexOf(chapters, href)
                    if (index >= 0) {
                        items += EpubTocItem(title, href, (level - 1).coerceAtLeast(0), index)
                    }
                }
            }
        }
        return items
    }

    private fun applyTocTitles(chapters: List<EpubChapter>, toc: List<EpubTocItem>): List<EpubChapter> {
        if (toc.isEmpty()) return chapters
        val titles = HashMap<Int, String>()
        toc.forEach { item ->
            if (item.spineIndex >= 0 && item.title.isNotBlank()) {
                titles.putIfAbsent(item.spineIndex, item.title)
            }
        }
        return chapters.mapIndexed { index, chapter ->
            titles[index]?.let { chapter.copy(title = it) } ?: chapter
        }
    }

    private fun spineIndexOf(chapters: List<EpubChapter>, href: String): Int {
        val clean = href.substringBefore('#').replace('\\', '/').trim()
        if (clean.isBlank()) return -1
        val fileName = clean.substringAfterLast('/')
        val byHref = chapters.indexOfFirst { chapter ->
            val ch = chapter.href.replace('\\', '/').substringBefore('#')
            ch == clean || ch.endsWith("/$clean") || ch.endsWith(fileName) ||
                chapter.file.name.equals(fileName, ignoreCase = true)
        }
        return byHref
    }

    private data class ManifestItem(
        val href: String,
        val properties: String,
        val mediaType: String,
    )

    private const val MAX_UNZIP_BYTES = 200L * 1024 * 1024
}
