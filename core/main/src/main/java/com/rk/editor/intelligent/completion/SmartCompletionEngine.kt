package com.rk.editor.intelligent.completion

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SmartSuggestion(val text: String, val score: Int, val source: String)

/**
 * Small, purely statistical completion model that lives on the device.
 *
 * It learns token transitions (bigram + trigram), identifier frequencies in the project, a
 * short-lived cache of the current file and which suggestions the user actually accepts. No
 * network access and no native model: memory is bounded by [MAX_IDS], [MAX_BIGRAMS],
 * [MAX_TRIGRAMS] and [HISTORY_LIMIT].
 */
class SmartCompletionEngine(context: Context) {

    companion object {
        private const val TAG = "SmartCompletion"
        private const val MAX_IDS = 12_000
        private const val MAX_BIGRAMS = 40_000
        private const val MAX_TRIGRAMS = 50_000
        private const val MAX_ACCEPT_WEIGHTS = 30_000
        private const val HISTORY_LIMIT = 256
        private const val MAX_TOKEN_LENGTH = 64
        private const val MAX_SUGGESTIONS = 6
        private const val MAX_INDEXED_FILES = 1000
        private const val MAX_INDEXED_BYTES = 4_000_000L
        private const val MAX_INDEXED_FILE_LENGTH = 512_000L

        private val codeExtensions = setOf(
            "c", "h", "cc", "cpp", "cxx", "hpp", "hxx", "hh", "m", "mm",
            "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "rs", "go",
            "cs", "php", "rb", "lua", "sh", "bash", "zsh", "sql", "css", "scss",
            "html", "xml", "json", "yaml", "yml", "toml", "gradle", "md", "txt"
        )

        fun isIdentifierStart(c: Char): Boolean = c == '_' || c == '$' || c.isLetter()

        fun isIdentifierPart(c: Char): Boolean = c == '_' || c == '$' || c.isLetterOrDigit()

        fun tokenize(text: CharSequence, limit: Int): List<String> {
            if (text.isEmpty()) return emptyList()
            val tokens = ArrayList<String>(min(limit, 256))
            var i = 0
            val length = text.length
            while (i < length && tokens.size < limit) {
                val c = text[i]
                if (c.isWhitespace()) {
                    i++
                    continue
                }
                if (isIdentifierStart(c)) {
                    val start = i
                    i++
                    while (i < length && isIdentifierPart(text[i]) && i - start < MAX_TOKEN_LENGTH) i++
                    tokens.add(text.subSequence(start, min(i, start + MAX_TOKEN_LENGTH)).toString())
                } else {
                    tokens.add(c.toString())
                    i++
                }
            }
            return tokens
        }

    }

    private val appContext = context.applicationContext
    private val storeDir = File(appContext.filesDir, "smart_completion")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = ConcurrentHashMap<String, ProjectModel>()
    private val loadedKeys = ConcurrentHashMap.newKeySet<String>()
    private val loadingKeys = ConcurrentHashMap.newKeySet<String>()
    private val indexedKeys = ConcurrentHashMap.newKeySet<String>()
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "smart-completion").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }

    init {
        storeDir.mkdirs()
        scope.launch {
            while (isActive) {
                delay(30_000L)
                flushDirty()
            }
        }
    }

    fun projectKey(path: String?): String {
        if (path.isNullOrBlank()) return ""
        return runCatching { File(path).absoluteFile.normalize().path }.getOrDefault(path)
    }

    fun attach(projectKey: String, filePath: String?, tail: CharSequence) {
        if (projectKey.isBlank() || filePath.isNullOrBlank()) return
        scope.launch {
            val model = ensureLoaded(projectKey) ?: return@launch
            synchronized(model) {
                if (model.seededFiles.add(filePath)) {
                    val tokens = tokenize(tail, 16_384)
                    model.learn(tokens, filePath, trimHistory = true)
                }
            }
            ensureIndexed(projectKey)
        }
    }

    fun onInsert(projectKey: String, filePath: String?, inserted: CharSequence) {
        if (projectKey.isBlank() || filePath.isNullOrBlank() || inserted.isEmpty()) return
        val text = inserted.toString()
        worker.execute {
            runCatching {
                if (!loadedKeys.contains(projectKey)) return@runCatching
                val model = models[projectKey] ?: return@runCatching
                synchronized(model) {
                    model.acceptOrReject(text)
                    val tokens = model.appendTyped(filePath, text)
                    if (tokens.isNotEmpty()) {
                        model.learn(tokens, filePath, trimHistory = false)
                    }
                    model.pending = null
                }
                dirtyKeys.add(projectKey)
            }
        }
    }

    fun onAccepted(projectKey: String, filePath: String?, name: String) {
        if (projectKey.isBlank() || filePath.isNullOrBlank()) return
        worker.execute {
            runCatching {
                val model = models[projectKey] ?: return@runCatching
                synchronized(model) {
                    model.accept(name)
                    model.pending = null
                }
                dirtyKeys.add(projectKey)
            }
        }
    }

    fun onDelete(projectKey: String, filePath: String?, deleted: CharSequence) {
        if (projectKey.isBlank() || filePath.isNullOrBlank() || deleted.isEmpty()) return
        val removed = tokenize(deleted, 512)
        val hasDelimiter = removed.any { it.length != 1 || isIdentifierPart(it[0]).not() }
        worker.execute {
            runCatching {
                val model = models[projectKey] ?: return@runCatching
                synchronized(model) {
                    if (hasDelimiter) {
                        val history = model.history(filePath)
                        repeat(min(removed.size, history.size)) { history.removeLast() }
                    }
                    model.typingBuffers.remove(filePath)?.setLength(0)
                    model.pending = null
                }
            }
        }
    }

    fun suggest(projectKey: String, filePath: String?, prefix: String): List<SmartSuggestion> {
        if (projectKey.isBlank() || filePath.isNullOrBlank()) return emptyList()
        if (!loadedKeys.contains(projectKey)) {
            requestLoad(projectKey)
            return emptyList()
        }
        val model = models[projectKey] ?: return emptyList()
        return synchronized(model) { model.suggest(prefix, filePath) }
    }

    fun flushDirty() {
        val pending = dirtyKeys.toList()
        for (key in pending) {
            if (!dirtyKeys.remove(key)) continue
            val model = models[key] ?: continue
            val snapshot = synchronized(model) {
                if (!model.loaded) return@synchronized null
                model.snapshot()
            } ?: continue
            runCatching { write(key, snapshot) }
                .onFailure { Log.w(TAG, "save failed for $key", it) }
        }
    }

    fun saveAll() = flushDirty()

    private fun requestLoad(key: String) {
        if (!loadingKeys.add(key)) return
        scope.launch {
            try {
                ensureLoaded(key)
            } finally {
                loadingKeys.remove(key)
            }
        }
    }

    private fun ensureLoaded(key: String): ProjectModel? {
        if (key.isBlank()) return null
        if (loadedKeys.contains(key)) return models[key]
        val model = models.computeIfAbsent(key) { ProjectModel(it) }
        synchronized(model) {
            if (model.loaded) {
                loadedKeys.add(key)
                return model
            }
            val file = storeFile(key)
            if (file.isFile && file.length() > 0) {
                runCatching { read(key, model, file) }
                    .onFailure { Log.w(TAG, "load failed for $key", it) }
            }
            model.loaded = true
        }
        loadedKeys.add(key)
        return model
    }

    private fun ensureIndexed(projectKey: String) {
        if (!indexedKeys.add(projectKey)) return
        scope.launch {
            val root = runCatching { File(projectKey) }.getOrNull() ?: return@launch
            if (!root.isDirectory || isStorageRoot(root)) return@launch
            val model = ensureLoaded(projectKey) ?: return@launch
            var files = 0
            var bytes = 0L
            runCatching {
                root.walkTopDown()
                    .onEnter { dir ->
                        val name = dir.name
                        !name.startsWith(".") && name != "build" && name != "node_modules" &&
                            name != "out" && name != "target"
                    }
                    .filter { file ->
                        file.isFile &&
                            file.length() in 1..MAX_INDEXED_FILE_LENGTH &&
                            file.extension.lowercase(Locale.ROOT) in codeExtensions
                    }
                    .take(MAX_INDEXED_FILES)
                    .forEach { file ->
                        if (bytes >= MAX_INDEXED_BYTES) return@forEach
                        if (Thread.currentThread().isInterrupted) return@forEach
                        val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                        bytes += text.length
                        files++
                        val tokens = tokenize(text, 8192)
                        synchronized(model) { model.learn(tokens, null, trimHistory = false) }
                    }
            }.onFailure { Log.w(TAG, "project index failed for $projectKey", it) }
            if (files > 0) dirtyKeys.add(projectKey)
            Log.d(TAG, "indexed $files files / $bytes chars for $projectKey")
        }
    }

    private fun isStorageRoot(root: File): Boolean {
        val path = root.absolutePath
        val primary = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
        return path == "/" || path == "/storage" || path == "/storage/emulated" ||
            path == "/sdcard" || (primary != null && path == primary)
    }

    private fun storeFile(key: String): File {
        val safe = key.hashCode().toUInt().toString(16) + "-" + key.length
        return File(storeDir, "project-$safe.scm")
    }

    private fun read(key: String, model: ProjectModel, file: File) {
        DataInputStream(file.inputStream().buffered()).use { input ->
            if (input.readUTF() != "XSC1") return
            val count = input.readInt().coerceIn(0, MAX_IDS)
            repeat(count) {
                val id = input.readInt()
                val name = input.readUTF()
                val ident = input.readBoolean()
                if (id > 0 && name.isNotEmpty()) model.restoreToken(id, name, ident)
            }
            val bigramCount = input.readInt().coerceIn(0, MAX_BIGRAMS)
            repeat(bigramCount) {
                val from = input.readInt()
                val size = input.readInt().coerceIn(0, 32)
                val values = IntArray(size)
                for (i in values.indices) values[i] = input.readInt()
                if (from > 0) model.bigrams[from] = values
            }
            val trigramCount = input.readInt().coerceIn(0, MAX_TRIGRAMS)
            repeat(trigramCount) {
                val first = input.readInt()
                val second = input.readInt()
                val size = input.readInt().coerceIn(0, 32)
                val values = IntArray(size)
                for (i in values.indices) values[i] = input.readInt()
                if (first > 0 && second > 0) model.trigrams[pair(first, second)] = values
            }
            val acceptCount = input.readInt().coerceIn(0, MAX_ACCEPT_WEIGHTS)
            repeat(acceptCount) {
                val keyValue = input.readLong()
                val weight = input.readInt()
                if (weight > 0) model.accepts[keyValue] = weight
            }
        }
        Log.d(TAG, "loaded ${model.nameCount()} identifiers for $key")
    }

    private fun write(key: String, snapshot: Snapshot) {
        val target = storeFile(key)
        val temp = File(target.parentFile, target.name + ".tmp")
        DataOutputStream(temp.outputStream().buffered()).use { output ->
            output.writeUTF("XSC1")
            output.writeInt(snapshot.tokens.size)
            snapshot.tokens.forEach { token ->
                output.writeInt(token.id)
                output.writeUTF(token.name)
                output.writeBoolean(token.ident)
            }
            output.writeInt(snapshot.bigrams.size)
            snapshot.bigrams.forEach { (from, values) ->
                output.writeInt(from)
                output.writeInt(values.size)
                values.forEach(output::writeInt)
            }
            output.writeInt(snapshot.trigrams.size)
            snapshot.trigrams.forEach { (keyValue, values) ->
                output.writeInt((keyValue ushr 32).toInt())
                output.writeInt(keyValue.toInt())
                output.writeInt(values.size)
                values.forEach(output::writeInt)
            }
            output.writeInt(snapshot.accepts.size)
            snapshot.accepts.forEach { (keyValue, weight) ->
                output.writeLong(keyValue)
                output.writeInt(weight)
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }



    private fun pair(first: Int, second: Int): Long =
        (first.toLong() shl 32) or (second.toLong() and 0xffffffffL)

    private fun mix(context: Long, candidate: Int): Long =
        (context * -7046029254386353131L) xor (candidate * -4658895280553007687L)

    private fun bump(values: IntArray, id: Int, amount: Int = 1) {
        var weakest = 0
        var i = 0
        while (i < values.size) {
            val current = values[i]
            if (current == id) {
                values[i + 1] += amount
                return
            }
            if (current == 0) {
                values[i] = id
                values[i + 1] = amount
                return
            }
            if (values[i + 1] < values[weakest + 1]) weakest = i
            i += 2
        }
        if (amount > values[weakest + 1]) {
            values[weakest] = id
            values[weakest + 1] = amount
        }
    }

    private class TrieNode {
        var children: HashMap<Char, TrieNode>? = null
        var id = 0
        var count = 0
    }

    private class Pending(
        val filePath: String,
        val prefix: String,
        val names: List<String>,
        val context: Long
    )

    private inner class ProjectModel(val key: String) {
        val idByName = HashMap<String, Int>()
        val names = ArrayList<String>(256).apply { add("") }
        val identifiers = ArrayList<Boolean>(256).apply { add(false) }
        val bigrams = HashMap<Int, IntArray>()
        val trigrams = HashMap<Long, IntArray>()
        val accepts = HashMap<Long, Int>()
        val histories = HashMap<String, ArrayDeque<Int>>()
        val typingBuffers = HashMap<String, StringBuilder>()
        val seededFiles = HashSet<String>()
        val root = TrieNode()
        var pending: Pending? = null
        var loaded = false

        fun nameCount(): Int = names.size - 1

        fun history(filePath: String): ArrayDeque<Int> =
            histories.getOrPut(filePath) { ArrayDeque() }

        fun appendTyped(filePath: String, text: CharSequence): List<String> {
            val buffer = typingBuffers.getOrPut(filePath) { StringBuilder() }
            buffer.append(text)
            if (buffer.length > 4096) buffer.delete(0, buffer.length - 1024)
            var cut = buffer.length
            while (cut > 0 && SmartCompletionEngine.isIdentifierPart(buffer[cut - 1])) cut--
            if (cut == 0) return emptyList()
            val ready = buffer.substring(0, cut)
            buffer.delete(0, cut)
            return SmartCompletionEngine.tokenize(ready, 512)
        }

        fun restoreToken(id: Int, name: String, ident: Boolean) {
            while (names.size <= id) {
                names.add("")
                identifiers.add(false)
            }
            names[id] = name
            identifiers[id] = ident
            idByName[name.lowercase(Locale.ROOT)] = id
            if (ident) insertTrie(name, id)
        }

        private fun insertTrie(name: String, id: Int) {
            var node = root
            val lower = name.lowercase(Locale.ROOT)
            for (c in lower) {
                val children = node.children ?: HashMap<Char, TrieNode>().also { node.children = it }
                node = children.getOrPut(c) { TrieNode() }
            }
            node.id = id
            node.count++
        }

        private fun idOf(token: String, create: Boolean): Int {
            val lower = token.lowercase(Locale.ROOT)
            idByName[lower]?.let { return it }
            if (!create || names.size - 1 >= MAX_IDS) return 0
            val id = names.size
            names.add(token)
            val ident = token.isNotEmpty() && (token[0] == '_' || token[0] == '$' || token[0].isLetter())
            identifiers.add(ident)
            idByName[lower] = id
            if (ident) insertTrie(token, id)
            return id
        }

        fun learn(tokens: List<String>, filePath: String?, trimHistory: Boolean) {
            if (tokens.isEmpty()) return
            var first = 0
            var second = 0
            var history: ArrayDeque<Int>? = null
            if (filePath != null) {
                history = history(filePath)
                val iterator = history.descendingIterator()
                if (iterator.hasNext()) {
                    second = iterator.next()
                    if (iterator.hasNext()) first = iterator.next()
                }
                if (trimHistory && history.size > HISTORY_LIMIT) {
                    while (history.size > HISTORY_LIMIT) history.removeFirst()
                }
            }
            for (token in tokens) {
                if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH) continue
                if (token.length == 1 && !isIdentifierStart(token[0]) && token[0].isWhitespace()) continue
                val id = idOf(token, create = true)
                if (id == 0) continue
                if (second != 0) {
                    val existing = bigrams[second]
                    if (existing != null) {
                        bump(existing, id)
                    } else if (bigrams.size < MAX_BIGRAMS) {
                        bigrams[second] = IntArray(16).also { bump(it, id) }
                    }
                }
                if (first != 0 && second != 0) {
                    val context = pair(first, second)
                    val existing = trigrams[context]
                    if (existing != null) {
                        bump(existing, id)
                    } else if (trigrams.size < MAX_TRIGRAMS) {
                        trigrams[context] = IntArray(16).also { bump(it, id) }
                    }
                }
                first = second
                second = id
                if (history != null) {
                    history.addLast(id)
                    while (history.size > HISTORY_LIMIT) history.removeFirst()
                }
            }
        }

        fun accept(name: String) {
            val current = pending ?: return
            if (current.names.none { it == name }) return
            val id = idByName[name.lowercase(Locale.ROOT)] ?: return
            val weightKey = mix(current.context, id)
            if (accepts.size < MAX_ACCEPT_WEIGHTS || accepts.containsKey(weightKey)) {
                accepts[weightKey] = ((accepts[weightKey] ?: 0) + 2).coerceAtMost(24)
            }
        }

        fun acceptOrReject(inserted: CharSequence) {
            val pending = pending ?: return
            val trimmed = inserted.toString().trim()
            val accepted = pending.names.any { name ->
                trimmed == name || (trimmed.length > name.length && trimmed.startsWith(name))
            }
            if (accepted) {
                pending.names.forEach { name ->
                    val id = idByName[name.lowercase(Locale.ROOT)] ?: return@forEach
                    val weightKey = mix(pending.context, id)
                    if (accepts.size < MAX_ACCEPT_WEIGHTS || accepts.containsKey(weightKey)) {
                        accepts[weightKey] = ((accepts[weightKey] ?: 0) + 2).coerceAtMost(24)
                    }
                }
                return
            }
            val firstToken = trimmed.substringBefore(' ').substringBefore('\n')
            val looksLikeIdentifier = firstToken.isNotEmpty() &&
                (firstToken[0] == '_' || firstToken[0] == '$' || firstToken[0].isLetter())
            if (!looksLikeIdentifier) return
            if (pending.prefix.isNotEmpty() &&
                !firstToken.lowercase(Locale.ROOT).startsWith(pending.prefix.lowercase(Locale.ROOT))
            ) {
                return
            }
            pending.names.forEach { name ->
                val id = idByName[name.lowercase(Locale.ROOT)] ?: return@forEach
                val weightKey = mix(pending.context, id)
                val current = accepts[weightKey] ?: return@forEach
                if (current > 1) accepts[weightKey] = current - 1
            }
        }

        fun suggest(prefix: String, filePath: String): List<SmartSuggestion> {
            val lowerPrefix = prefix.lowercase(Locale.ROOT)
            val history = histories[filePath]
            var last = 0
            var beforeLast = 0
            if (history != null && history.isNotEmpty()) {
                val iterator = history.descendingIterator()
                last = iterator.next()
                if (iterator.hasNext()) beforeLast = iterator.next()
            }
            val context = if (beforeLast != 0) pair(beforeLast, last) else last.toLong()
            val scores = HashMap<Int, Int>()
            val sources = HashMap<Int, String>()

            fun offer(id: Int, score: Int, source: String) {
                if (id <= 0 || score <= 0) return
                if (id >= identifiers.size || !identifiers[id]) return
                val name = names[id]
                if (name.length < 2) return
                if (lowerPrefix.isNotEmpty() && !name.lowercase(Locale.ROOT).startsWith(lowerPrefix)) return
                if (lowerPrefix.isNotEmpty() && name.equals(prefix, ignoreCase = true)) return
                if (lowerPrefix.isEmpty() && id == last) return
                scores[id] = (scores[id] ?: 0) + score
                if (sources[id] == null) sources[id] = source
            }

            if (last != 0) {
                bigrams[last]?.let { values ->
                    var i = 0
                    while (i < values.size && values[i] != 0) {
                        offer(values[i], values[i + 1] * 4, "ngram")
                        i += 2
                    }
                }
            }
            if (last != 0 && beforeLast != 0) {
                trigrams[pair(beforeLast, last)]?.let { values ->
                    var i = 0
                    while (i < values.size && values[i] != 0) {
                        offer(values[i], values[i + 1] * 8, "ngram")
                        i += 2
                    }
                }
            }
            if (history != null && history.isNotEmpty()) {
                var distance = 0
                val iterator = history.descendingIterator()
                while (iterator.hasNext() && distance < 64) {
                    offer(iterator.next(), 7 - distance / 16, "recent")
                    distance++
                }
            }
            if (lowerPrefix.isNotEmpty()) {
                collectPrefix(lowerPrefix, MAX_SUGGESTIONS * 6).forEach { (id, count) ->
                    offer(id, count.coerceAtMost(60), "prefix")
                }
            }
            if (scores.isEmpty()) return emptyList()
            val result = ArrayList<SmartSuggestion>(scores.size)
            val minimum = if (lowerPrefix.isEmpty()) 30 else 0
            for ((id, score) in scores) {
                val boost = accepts[mix(context, id)] ?: 0
                val total = score + boost * 12
                if (total > minimum) result.add(SmartSuggestion(names[id], total, sources[id] ?: "learned"))
            }
            result.sortByDescending { it.score }
            val unique = ArrayList<SmartSuggestion>(MAX_SUGGESTIONS)
            val seen = HashSet<String>(MAX_SUGGESTIONS)
            for (suggestion in result) {
                if (seen.add(suggestion.text.lowercase(Locale.ROOT))) {
                    unique.add(suggestion)
                    if (unique.size >= MAX_SUGGESTIONS) break
                }
            }
            if (unique.isNotEmpty()) {
                pending = Pending(filePath, prefix, unique.map { it.text }, context)
            }
            return unique
        }

        private fun collectPrefix(prefix: String, wanted: Int): List<Pair<Int, Int>> {
            var node: TrieNode = root
            for (c in prefix) {
                node = node.children?.get(c) ?: return emptyList()
            }
            val result = ArrayList<Pair<Int, Int>>(wanted)
            var visited = 0
            val stack = ArrayDeque<TrieNode>()
            stack.addLast(node)
            while (stack.isNotEmpty() && result.size < wanted && visited < 4000) {
                val current = stack.removeLast()
                visited++
                if (current.id != 0) result.add(current.id to current.count)
                current.children?.values?.forEach { stack.addLast(it) }
            }
            result.sortByDescending { it.second }
            return result
        }

        fun snapshot(): Snapshot {
            val tokens = ArrayList<TokenEntry>(names.size - 1)
            for (id in 1 until names.size) {
                val name = names[id]
                if (name.isEmpty()) continue
                tokens.add(TokenEntry(id, name, identifiers[id]))
            }
            return Snapshot(
                tokens = tokens,
                bigrams = bigrams.mapValues { it.value.copyOf() },
                trigrams = trigrams.mapValues { it.value.copyOf() },
                accepts = HashMap(accepts)
            )
        }
    }

    private class TokenEntry(val id: Int, val name: String, val ident: Boolean)

    private class Snapshot(
        val tokens: List<TokenEntry>,
        val bigrams: Map<Int, IntArray>,
        val trigrams: Map<Long, IntArray>,
        val accepts: Map<Long, Int>
    )
}
