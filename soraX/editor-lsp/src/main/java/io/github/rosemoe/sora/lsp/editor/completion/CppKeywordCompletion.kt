package io.github.rosemoe.sora.lsp.editor.completion

import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem

object CppKeywordCompletion {
    private val cppExtensions =
        setOf("c", "h", "cc", "cp", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h++", "ipp", "tpp")

    private val cKeywords =
        listOf(
            "_Alignas",
            "_Alignof",
            "_Atomic",
            "_Bool",
            "_Complex",
            "_Generic",
            "_Imaginary",
            "_Noreturn",
            "_Static_assert",
            "_Thread_local",
            "auto",
            "break",
            "case",
            "char",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extern",
            "float",
            "for",
            "goto",
            "if",
            "inline",
            "int",
            "long",
            "register",
            "restrict",
            "return",
            "short",
            "signed",
            "sizeof",
            "static",
            "struct",
            "switch",
            "typedef",
            "union",
            "unsigned",
            "void",
            "volatile",
            "while",
        )

    private val cppKeywords =
        cKeywords +
            listOf(
                "alignas",
                "alignof",
                "and",
                "and_eq",
                "asm",
                "bitand",
                "bitor",
                "bool",
                "catch",
                "char8_t",
                "char16_t",
                "char32_t",
                "class",
                "compl",
                "concept",
                "consteval",
                "constexpr",
                "constinit",
                "const_cast",
                "co_await",
                "co_return",
                "co_yield",
                "decltype",
                "delete",
                "dynamic_cast",
                "explicit",
                "export",
                "false",
                "friend",
                "mutable",
                "namespace",
                "new",
                "noexcept",
                "not",
                "not_eq",
                "nullptr",
                "operator",
                "or",
                "or_eq",
                "private",
                "protected",
                "public",
                "reinterpret_cast",
                "requires",
                "static_assert",
                "static_cast",
                "template",
                "this",
                "thread_local",
                "throw",
                "true",
                "try",
                "typeid",
                "typename",
                "using",
                "virtual",
                "wchar_t",
                "xor",
                "xor_eq",
            )

    fun supports(extension: String): Boolean = extension.lowercase() in cppExtensions

    fun items(extension: String, prefix: String, prefixLength: Int): List<CompletionItem> {
        if (prefix.isEmpty() || !prefix.first().isLetter() && prefix.first() != '_') return emptyList()
        val keywords = if (extension.lowercase() == "c") cKeywords else cppKeywords
        val lower = prefix.lowercase()
        return keywords
            .filter { it.lowercase().startsWith(lower) }
            .map { keyword ->
                SimpleCompletionItem(keyword, "Keyword", prefixLength, keyword)
                    .kind(CompletionItemKind.Keyword)
            }
    }
}
