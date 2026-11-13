package com.tube.byedpi

/**
 * Строка аргументов byedpi (как в ByeByeDPI / командной строке ciadpi) → argv.
 * Поддерживает кавычки для значений с пробелами: `--fake-data ":GET / HTTP/1.1"`.
 */
object StrategyArgs {

    /** Опции byedpi: короткое имя → длинное и нужен ли аргумент (таблица options[] в main.c). */
    private val options: Map<Char, Pair<String, Boolean>> = mapOf(
        'N' to ("no-domain" to false),
        'U' to ("no-udp" to false),
        'X' to ("no-ipv6" to false),
        'I' to ("conn-ip" to true),
        'b' to ("buf-size" to true),
        'c' to ("max-conn" to true),
        'x' to ("debug" to true),
        'F' to ("tfo" to false),
        'A' to ("auto" to true),
        'L' to ("auto-mode" to true),
        'u' to ("cache-ttl" to true),
        'T' to ("tcp-timeout" to true),
        'W' to ("tls-timeout" to true),
        'B' to ("copy" to true),
        'K' to ("proto" to true),
        'H' to ("hosts" to true),
        'V' to ("pf" to true),
        'R' to ("round" to true),
        's' to ("split" to true),
        'd' to ("disorder" to true),
        'o' to ("oob" to true),
        'q' to ("disoob" to true),
        'f' to ("fake" to true),
        'S' to ("md5sig" to false),
        'n' to ("fake-sni" to true),
        't' to ("ttl" to true),
        'l' to ("fake-data" to true),
        'O' to ("fake-offset" to true),
        'Q' to ("fake-tls-mod" to true),
        'e' to ("oob-data" to true),
        'M' to ("mod-http" to true),
        'r' to ("tlsrec" to true),
        'm' to ("tlsminor" to true),
        'a' to ("udp-fake" to true),
        'g' to ("def-ttl" to true),
        'Z' to ("wait-send" to false),
        'z' to ("await-int" to true),
        'Y' to ("drop-sack" to false),
        'j' to ("ipset" to true),
        '0' to ("rst" to false),
        '#' to ("comment" to true),
    )
    private val longOptions = options.values.associate { it.first to it.second }

    /** Опции, которые задаёт само приложение (адрес и порт прокси) или которые ломают работу в процессе. */
    private val reserved = setOf("ip", "port", "daemon", "pidfile", "transparent", "help", "version", "protect-path", "cache-file", "cache-merge", "connect-to")

    private val FakeFile = Regex("""[A-Za-z0-9_][A-Za-z0-9_.-]*""")

    /**
     * Опции, значение которых byedpi читает как путь к файлу, если оно не начинается с `:`. Из своей стратегии
     * (её вставляют с форумов) путь отдал бы в сеть файлы приложения — например, базу cookie WebView.
     * Разрешены только `:данные` и встроенные `@fake/<имя>`.
     */
    private val FileOptions = setOf('l', 'H', 'j')

    private fun safeFileValue(value: String) = value.startsWith(":") || (value.startsWith("@fake/") && FakeFile.matches(value.removePrefix("@fake/")))

    sealed interface Result {
        data class Ok(val argv: List<String>) : Result
        data class Error(val message: String) : Result
    }

    fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasToken = false
        for (c in line) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> current.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    hasToken = true
                }
                c.isWhitespace() -> {
                    if (hasToken) out += current.toString()
                    current.clear()
                    hasToken = false
                }
                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
        }
        if (hasToken) out += current.toString()
        return out
    }

    /** Разбирает и проверяет строку; ошибки — понятные пользователю (имя опции). */
    fun parse(line: String): Result {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return Result.Error("empty")
        // Встроенные фейки — только по имени файла: «@fake/../…» отдал бы в сеть файлы приложения.
        tokens.firstOrNull { "@fake/" in it && !FakeFile.matches(it.substringAfter("@fake/")) }?.let { return Result.Error(it) }
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token.startsWith("--") -> {
                    val body = token.removePrefix("--")
                    val name = body.substringBefore('=')
                    if (name in reserved) return Result.Error("--$name")
                    val needsArg = longOptions[name] ?: return Result.Error(token)
                    var value: String? = if ('=' in body) body.substringAfter('=') else null
                    if (needsArg && '=' !in body) {
                        if (i + 1 >= tokens.size) return Result.Error(token)
                        i++
                        value = tokens[i]
                    }
                    val short = options.entries.firstOrNull { it.value.first == name }?.key
                    if (short in FileOptions && value != null && !safeFileValue(value)) return Result.Error("--$name $value")
                }
                token.startsWith("-") && token.length >= 2 -> {
                    val key = token[1]
                    if (key == 'i' || key == 'p' || key == 'D' || key == 'w' || key == 'E' || key == 'P' || key == 'y' || key == 'C') {
                        return Result.Error("-$key")
                    }
                    val (_, needsArg) = options[key] ?: return Result.Error(token)
                    if (needsArg && key in FileOptions) {
                        val value = if (token.length > 2) token.substring(2) else tokens.getOrNull(i + 1)
                        if (value != null && !safeFileValue(value)) return Result.Error("-$key $value")
                    }
                    if (needsArg && token.length == 2) {
                        if (i + 1 >= tokens.size) return Result.Error(token)
                        i++
                    } else if (!needsArg && token.length > 2) {
                        // Склеенные флаги без аргументов, например -SY.
                        if (token.drop(1).any { options[it]?.second != false }) return Result.Error(token)
                    }
                }
                else -> return Result.Error(token)
            }
            i++
        }
        return Result.Ok(tokens)
    }

    /** Полный argv для запуска: адрес прослушивания задаёт приложение. */
    fun commandLine(port: Int, strategy: List<String>, blockUdp: Boolean): List<String> = buildList {
        add("-i"); add("127.0.0.1")
        add("-p"); add(port.toString())
        if (blockUdp) add("-U")
        addAll(strategy)
    }
}
