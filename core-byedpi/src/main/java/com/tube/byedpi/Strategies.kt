package com.tube.byedpi

/**
 * Готовая стратегия обхода. [args] — аргументы byedpi без адреса и порта; `{sni}` — фейковое имя сайта,
 * `@fake/<файл>` — фейковый ClientHello из zapret (см. [ByeDpiProcess]).
 */
data class Strategy(
    val id: String,
    val name: String,
    val args: String,
    val descriptionRu: String,
    val descriptionEn: String,
)

object Strategies {

    /** Свой вариант пользователя хранится под этим id. */
    const val CUSTOM_ID = "custom"

    /** Подстановка для фейкового SNI в аргументах. */
    const val SNI = "{sni}"

    /** Варианты фейкового SNI: в августе 2026 ТСПУ начал узнавать www.google.com, поэтому есть замены. */
    val FakeSniOptions = listOf("google.com", "ya.ru", "vk.com", "fonts.google.com")

    /**
     * Основной список: строки, о которых в 2026 писали как о рабочих у крупных провайдеров
     * (issues byedpi/ByeByeDPI, ntc.party), и аналоги general*.bat из Flowseal zapret-discord-youtube
     * в пределах возможностей byedpi без root.
     */
    val builtIn: List<Strategy> = listOf(
        Strategy(
            "rtk_multisplit", "MULTISPLIT OOB",
            "-o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
            "OOB-байт и много разрезов вокруг SNI. Ростелеком, 2026",
            "OOB byte and many cuts around the SNI. Rostelecom, 2026",
        ),
        Strategy(
            "disorder_md5", "DISORDER MD5",
            "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1",
            "Перемешивание вокруг SNI + MD5-подпись фейков. ТТК, Ростелеком, Tele2",
            "Shuffling around the SNI plus MD5-signed fakes. TTK, Rostelecom, Tele2",
        ),
        Strategy(
            "disorder_tlsrec", "DISORDER TLSREC",
            "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As",
            "Перемешивание и деление TLS-записи. Ростелеком, август 2026",
            "Shuffling plus TLS record split. Rostelecom, August 2026",
        ),
        Strategy(
            "disorder_lite", "DISORDER LITE",
            "-d1 -d3+s -s6+s -d9+s -s20+s -d25+s -s30+s -a1",
            "Облегчённое перемешивание без подделок. Проводной Ростелеком",
            "Lighter shuffling without fakes. Wired Rostelecom",
        ),
        Strategy(
            "beeline", "BEELINE",
            "-Kt,h -H:\"googlevideo.com youtube.com ytimg.com youtubei.googleapis.com\" -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -An -Kt,h -o1 -r-5+se -a1 -At,r,s -d1 -n $SNI -Qr -f-1 -r2 -a1 -An",
            "Разрезы для доменов YouTube, для остальных — фейк с чужим SNI. Билайн",
            "Cuts for YouTube domains, fake with a foreign SNI for the rest. Beeline",
        ),
        Strategy(
            "mts", "MTS",
            "-Kt,h -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -At,r,s -d1 -Qr -f-1 -r2 -An",
            "Разрезы, при сбросе — фейк со случайными полями. МТС, МГТС",
            "Cuts, on reset a fake with random fields. MTS, MGTS",
        ),
        Strategy(
            "ttk", "TTK",
            "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1 -As -s1 -d1 -s5+s -s10+s -s15+s -s20+s -S -a1",
            "Три ступени с MD5-подписью фейков. ТТК",
            "Three tiers with MD5-signed fakes. TTK",
        ),
        Strategy(
            "oob_fake_auto", "OOB + FAKE",
            "-o1 -r-5+se -a1 -At,r,s,c -d1 -n $SNI -Qr -f-1 -a1",
            "OOB и TLS-запись, при блокировке — фейк с чужим SNI",
            "OOB plus TLS record, on block a fake with a foreign SNI",
        ),
        Strategy(
            "ntc_combo", "NTC COMBO",
            "-Kt,h -H:\"googlevideo.com youtu.be ytimg.com youtubei.googleapis.com\" -d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -An -s1 -q1 -a1 -Y -Ar -a1 -s5 -o2 -At -f-1 -r1+s -a1 -As -s1 -o1+s -s-1 -a1 -An",
            "Многоступенчатая схема с ntc.party для Android",
            "Multi-tier scheme from ntc.party for Android",
        ),
        Strategy(
            "fake_tlsrec", "FAKE SNI",
            "-n $SNI -Qr -f-1 -r1+s -a1",
            "Фейковый ClientHello с чужим SNI и случайными полями",
            "Fake ClientHello with a foreign SNI and random fields",
        ),
        Strategy(
            "fake_split_ttl8", "FAKE TTL 8",
            "--fake -1 --ttl 8 --split 1+s --disorder 3+s -a1",
            "Фейк с малым TTL: DPI его видит, сервер — нет",
            "Low-TTL fake: DPI sees it, the server does not",
        ),
        Strategy(
            "fake_ttl4", "FAKE TTL 4",
            "-d1 -f-1 -t4 -n $SNI -Qr -r1+s -a1",
            "То же с TTL 4 — если DPI стоит близко к абоненту",
            "Same with TTL 4 — when DPI sits close to the subscriber",
        ),
        Strategy(
            "flowseal_fake", "FLOWSEAL FAKE",
            "-d1 -f-1 -t6 -l @fake/tls_clienthello_www_google_com.bin -r1+s -a1",
            "Как ALT10 у Flowseal: настоящий ClientHello google.com в роли фейка",
            "Like Flowseal ALT10: a real google.com ClientHello as the fake",
        ),
        Strategy(
            "flowseal_multisplit", "FLOWSEAL SPLIT",
            "-f1 -t5 -l @fake/tls_clienthello_www_google_com.bin -s1 -s0+s -d3+s -r1+s -a1",
            "Как general у Flowseal: фейк google.com в начале и разрезы по SNI",
            "Like Flowseal general: a google.com fake first, then cuts at the SNI",
        ),
        Strategy(
            "flowseal_hostfake", "FLOWSEAL HOSTFAKE",
            "-n $SNI -Qo -O0+s -s0+s -f0+se -t5 -r1+s -a1",
            "Как hostfakesplit у Flowseal: на месте имени сайта сначала уходит фейковое",
            "Like Flowseal hostfakesplit: a fake host name goes first at the SNI position",
        ),
        Strategy(
            "fake_vk", "FAKE VK",
            "-d1 -f-1 -t6 -l @fake/tls_clienthello_vk_com.bin -s1+s -r1+s -a1",
            "Фейковый ClientHello vk.com — российский сайт, который DPI пропускает",
            "Fake vk.com ClientHello — a Russian site DPI lets through",
        ),
        Strategy(
            "fake_gosuslugi", "FAKE GOSUSLUGI",
            "-d1 -f-1 -t5 -l @fake/tls_clienthello_gosuslugi_ru.bin -s0+sm -r1+s -a1",
            "Фейковый ClientHello gosuslugi.ru и разрез посередине имени",
            "Fake gosuslugi.ru ClientHello and a cut mid-name",
        ),
        Strategy(
            "oob_timeout", "OOB TIMEOUT",
            "-b 65536 -o1 -d3 -s8 -s8+s -o30+s -r26+s -T5 -a1 -At -d1 -f-1 -Qo -n $SNI -t5 -a1",
            "OOB и деление записи; при сбое — фейк-копия ClientHello",
            "OOB plus record split; on failure, a fake copy of the ClientHello",
        ),
        Strategy(
            "quic_fake", "QUIC FAKE",
            "-Ku -a6 -l @fake/quic_initial_www_google_com.bin -An -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -r1+s -a1",
            "Для режима «QUIC через прокси»: фейковый QUIC Initial google.com ×6",
            "For “QUIC through proxy”: fake google.com QUIC Initial ×6",
        ),
        // Ноябрь 2026: возможности byedpi, которых не было в списке, и аналоги свежих general*.bat у Flowseal.
        Strategy(
            "fake_orig_sni", "FAKE ORIG SNI",
            "-d1 -f-1 -Qo -n $SNI -t5 -a1",
            "Фейк — копия настоящего ClientHello, заменено только имя сайта",
            "The fake is a copy of the real ClientHello with only the site name swapped",
        ),
        Strategy(
            "fake_orig_ttl3", "FAKE ORIG TTL 3",
            "-s1 -f-1 -Qo -n $SNI -t3 -r1+s -a1",
            "Копия ClientHello с TTL 3 — для DPI и кэшей Google рядом с абонентом",
            "ClientHello copy at TTL 3 — for DPI and Google caches close to you",
        ),
        Strategy(
            "fake_orig_rand", "FAKE ORIG RAND",
            "-o1 -f-1 -Qo,r -n $SNI -t6 -a1",
            "OOB и копия ClientHello со случайными полями",
            "OOB plus a ClientHello copy with random fields",
        ),
        Strategy(
            "hostfake_disorder", "HOSTFAKE DISORDER",
            "-n $SNI -Qo -O0+s -d1 -s0+s -f0+se -t4 -a1",
            "Фейковое имя на месте настоящего и перестановка первого байта",
            "Fake name at the real one's position plus first-byte disorder",
        ),
        Strategy(
            "fake_random_sni", "FAKE RANDOM SNI",
            "-d1 -f-1 -n www.??????????.com -Qr -t5 -r1+s -a1",
            "Фейк со случайным именем в каждом соединении",
            "A fake with a new random name on every connection",
        ),
        Strategy(
            "fake_mix_sni", "FAKE MIX SNI",
            "-d1 -f-1 -n $SNI -n vk.com -n ya.ru -n ok.ru -Qr -t5 -r1+s -a1",
            "Имя в фейке выбирается случайно из нескольких российских сайтов",
            "The fake's name is picked at random from several Russian sites",
        ),
        Strategy(
            "fake_ttl_auto", "FAKE TTL AUTO",
            "-d1 -f-1 -n $SNI -Qr -t8 -r1+s -a1 -At,s -d1 -f-1 -n $SNI -Qr -t5 -r1+s -a1 -At,s -d1 -f-1 -n $SNI -Qr -t3 -r1+s -a1",
            "Фейк с TTL 8, 5, 3: подошедший запоминается для каждого адреса",
            "Fake with TTL 8, 5, 3: the one that works is remembered per address",
        ),
        Strategy(
            "orig_ttl_auto", "FAKE ORIG TTL AUTO",
            "-d1 -f-1 -Qo -n $SNI -t7 -a1 -At,s -d1 -f-1 -Qo -n $SNI -t4 -a1 -At,s -d1 -f-1 -Qo -n $SNI -t2 -a1",
            "Копия ClientHello с TTL 7, 4, 2 и запоминанием",
            "ClientHello copy with TTL 7, 4, 2, remembered per address",
        ),
        Strategy(
            "freeze_auto", "16KB AUTO",
            "-W 3:1:65536 -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1 -At -d1 -f-1 -Qo -n $SNI -t5 -a1 -At -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -r1+s -a1",
            "Замечает «заморозку» после 16 КБ и переключает адрес на фейк",
            "Notices a freeze after 16 KB and moves that address to a fake",
        ),
        Strategy(
            "freeze_fake", "16KB FAKE",
            "-W 3:1:65536 -d1 -f-1 -n $SNI -Qr -t5 -r1+s -a1 -At -d1 -f-1 -t4 -l @fake/tls_clienthello_vk_com_kyber.bin -a1",
            "Фейк с чужим именем, при «заморозке» — фейк VK",
            "Foreign-name fake; on a freeze, switches to a VK fake",
        ),
        Strategy(
            "fake_vk_kyber", "FAKE VK KYBER",
            "-d1 -f-1 -t5 -l @fake/tls_clienthello_vk_com_kyber.bin -a1",
            "Фейк VK того же размера, что и ClientHello YouTube",
            "A VK fake the same size as YouTube's ClientHello",
        ),
        Strategy(
            "fake_sber", "FAKE SBER",
            "-o1 -f-1 -t5 -l @fake/tls_clienthello_sberbank_ru.bin -a1",
            "OOB и фейковый ClientHello sberbank.ru",
            "OOB plus a fake sberbank.ru ClientHello",
        ),
        Strategy(
            "fake_stun", "FAKE STUN",
            "-o1 -f-5 -t5 -l :\\x00\\x01\\x00\\x00\\x21\\x12\\xa4\\x42\\x6b\\x31\\x7a\\x0e\\x44\\x92\\x15\\xc3\\x58\\xa0\\x7d\\x21 -a1",
            "Фейк не TLS, а STUN: DPI принимает соединение за звонок",
            "A STUN fake instead of TLS: DPI takes the flow for a call",
        ),
        Strategy(
            "fake_zero", "FAKE ZEROS",
            "-f1 -f0+sm -l :\\0 -t5 -a1",
            "Фейки из нулей до середины имени, как fake-tls=0x00000000 у Flowseal",
            "Zero-filled fakes up to mid-name, like Flowseal's fake-tls=0x00000000",
        ),
        Strategy(
            "rand_sni_cut", "RANDOM SNI CUT",
            "-o1 -d0+sr -s0+se -r0+sr -a1",
            "Разрез и TLS-запись в случайном месте имени в каждом соединении",
            "Cut and TLS record at a random point of the name on every connection",
        ),
        Strategy(
            "tlsrec_x3", "TLSREC X3",
            "-r1:3:5+s -o1 -d3+s -a1",
            "Три TLS-записи внутри имени сайта, OOB и перестановка",
            "Three TLS records inside the name, OOB and disorder",
        ),
        Strategy(
            "oob_orig_auto", "OOB + ORIG FAKE",
            "-o1 -r-5+se -a1 -At,r,s -d1 -f-1 -Qo -n $SNI -t5 -a1",
            "OOB и TLS-запись, при блокировке — копия ClientHello",
            "OOB plus TLS record; on block, a ClientHello copy",
        ),
        Strategy(
            "disoob_orig", "DISOOB ORIG",
            "-q1 -f-1 -Qo -n $SNI -t4 -a1",
            "OOB с перестановкой и копия ClientHello",
            "Disordered OOB plus a ClientHello copy",
        ),
        Strategy(
            "yt_groups", "YT GROUPS",
            "-H:\"googlevideo.com\" -d1 -d3+s -s6+s -d9+s -s20+s -At -H:\"googlevideo.com\" -d1 -f-1 -Qo -n $SNI -t4 -An " +
                "-H:\"youtube.com youtu.be ytimg.com ggpht.com youtubei.googleapis.com youtube-nocookie.com googleapis.com gstatic.com\" -d1 -f-1 -Qo -n $SNI -t5 " +
                "-At -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -An -o1 -s1+s -a1",
            "Видео — лёгкие разрезы, сайт и API — фейк; у каждой группы своя замена",
            "Video gets light cuts, site and API get a fake; each with its own fallback",
        ),
        Strategy(
            "yt_only", "YT ONLY",
            "-Kt -H:\"googlevideo.com youtube.com youtu.be ytimg.com ggpht.com youtubei.googleapis.com youtube-nocookie.com\" -o1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s " +
                "-At -H:\"googlevideo.com youtube.com youtu.be ytimg.com ggpht.com youtubei.googleapis.com youtube-nocookie.com\" -d1 -f-1 -Qo -n $SNI -t5 -An",
            "Обход только для доменов YouTube, остальное без изменений",
            "Bypass only for YouTube domains, everything else untouched",
        ),
        Strategy(
            "quic_fake_low", "QUIC FAKE LOW",
            "-Ku -a10 -t4 -l @fake/quic_initial_www_google_com.bin -An -d1 -f-1 -Qo -n $SNI -t5",
            "QUIC: 10 фейков с TTL 4; TCP — копия ClientHello",
            "QUIC: 10 fakes at TTL 4; TCP gets a ClientHello copy",
        ),
        Strategy(
            "slow_split", "SLOW SPLIT",
            "-Z -z 150 -s1 -s0+s -s0+sm -s0+se -a1",
            "Части уходят с паузой 150 мс — DPI не успевает собрать запрос",
            "Parts go 150 ms apart so DPI gives up reassembling",
        ),
    )

    /** Набор ByeByeDPI: проверяется при автоподборе, в списке показываются только сработавшие. */
    val extended: List<Strategy> = ByeByeDpiPack.lines.mapIndexed { i, args ->
        val n = (i + 1).toString().padStart(2, '0')
        Strategy("bbd_$n", "BBD $n", args, "Из набора ByeByeDPI", "From the ByeByeDPI set")
    }

    val all: List<Strategy> get() = builtIn + extended

    /**
     * Кандидаты для автоподбора: без повторов (строки набора ByeByeDPI, совпадающие со встроенными с точностью
     * до пробелов) и, когда UDP закрыт (`-U`), без стратегий для QUIC — их TCP-часть повторяет другие.
     * Выбранные раньше стратегии по-прежнему находятся через [byId].
     */
    fun candidates(blockUdp: Boolean): List<Strategy> {
        val seen = HashSet<String>()
        return all.filter { s ->
            if (blockUdp && s.args.trimStart().startsWith("-Ku")) return@filter false
            seen.add(s.args.trim().split(Whitespace).joinToString(" "))
        }
    }

    private val Whitespace = Regex("\\s+")

    /** Фейк с именем www.google.com: с августа 2026 ТСПУ его узнаёт — такие стратегии проверяются последними. */
    fun usesGoogleFake(s: Strategy): Boolean = "www_google_com" in s.args

    val default: Strategy get() = builtIn.first()

    fun byId(id: String): Strategy? = all.firstOrNull { it.id == id }
}
