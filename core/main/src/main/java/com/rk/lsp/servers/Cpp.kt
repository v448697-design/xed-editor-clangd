package com.rk.lsp.servers

import android.app.Activity
import android.content.Context
import com.rk.file.BuiltinFileType
import com.rk.file.FileObject
import com.rk.lsp.LspConnectionConfig
import com.rk.lsp.LspServer
import com.rk.utils.application
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object Cpp : LspServer() {
    private const val VERSION = "21.1.8-3"
    private const val ASSET_ZIP = "clangd/headers.zip"

    override val id = "cpp"
    override val languageName = "C/C++"
    override val serverName = "clangd"
    override val supportedExtensions = BuiltinFileType.C.extensions + BuiltinFileType.CPP.extensions
    override val icon = BuiltinFileType.CPP.icon
    override val canBeUninstalled = false

    private fun nativeLibraryDir(context: Context) = context.applicationInfo.nativeLibraryDir

    private fun resourceRoot(context: Context = application!!) = File(context.filesDir, "clangd")

    private fun resourceDir(context: Context = application!!) = File(resourceRoot(context), "lib/clang/21")

    private fun includeDirs(context: Context = application!!): List<File> {
        val root = resourceRoot(context)
        return listOf(File(root, "include/c++/v1"), File(root, "include"), File(root, "include/aarch64-linux-android"))
    }

    override suspend fun isInstalled(context: Context): Boolean {
        return File(nativeLibraryDir(context), "libclangd.so").exists()
    }

    override fun install(activity: Activity) {}

    override fun uninstall(activity: Activity) {}

    override suspend fun isUpdatable(context: Context): Boolean = false

    override fun update(activity: Activity) {}

    override suspend fun beforeConnect() {
        withContext(Dispatchers.IO) { ensureHeaders() }
    }

    override fun getConnectionConfig(): LspConnectionConfig {
        val context = application!!
        val args =
            arrayOf(
                File(nativeLibraryDir(context), "libclangd.so").absolutePath,
                "--resource-dir=${resourceDir(context).absolutePath}",
                "--background-index",
                "--clang-tidy=false",
                "--all-scopes-completion=true",
                "--completion-style=bundled",
                "--header-insertion=iwyu",
                "--pch-storage=memory",
                "--log=error",
            )
        return LspConnectionConfig.AndroidProcess(args)
    }

    fun ensureProjectFlags(projectRoot: FileObject, isCpp: Boolean = true) {
        val root = File(projectRoot.getAbsolutePath())
        if (!root.isDirectory) return
        if (File(root, "compile_commands.json").exists()) return
        if (File(root, ".clangd").exists()) return

        val flags = if (isCpp) generatedFlagsCpp() else generatedFlags()
        val flagsFile = File(root, "compile_flags.txt")
        if (flagsFile.exists()) {
            val existing = runCatching { flagsFile.readText() }.getOrNull() ?: return
            if (!isOwnedFlags(existing)) return
            if (existing == flags) return
        }
        runCatching { flagsFile.writeText(flags) }
    }

    private fun generatedFlags(): String =
        buildString {
            appendLine("-std=c17")
            appendLine("-xc")
            appendLine("--target=aarch64-linux-android26")
            includeDirs().forEach {
                appendLine("-isystem")
                appendLine(it.absolutePath)
            }
        }

    private fun generatedFlagsCpp(): String =
        buildString {
            appendLine("-std=c++17")
            appendLine("-xc++")
            appendLine("--target=aarch64-linux-android26")
            includeDirs().forEach {
                appendLine("-isystem")
                appendLine(it.absolutePath)
            }
        }

    private fun isOwnedFlags(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val owned =
            listOf(
                generatedFlags().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                generatedFlagsCpp().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
                listOf("-std=c++17") + generatedFlags().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList(),
            )
        return lines in owned
    }

    private fun ensureHeaders() {
        val context = application ?: return
        val root = resourceRoot(context)
        val marker = File(root, ".version")
        if (marker.exists() && runCatching { marker.readText() }.getOrNull() == VERSION) return

        root.deleteRecursively()
        root.mkdirs()
        context.assets.open(ASSET_ZIP).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = File(root, entry.name)
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        FileOutputStream(output).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        marker.writeText(VERSION)
    }
}
