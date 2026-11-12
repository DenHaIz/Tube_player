# Tube и Tube Player

Два Android-приложения для YouTube в России — по принципу ByeDPI / zapret, без внешних серверов.

- **Tube Player** (`client`) — свой лёгкий клиент YouTube со встроенным обходом DPI: VPN не нужен, работает рядом с любым VPN. Интерфейс и жесты как в YouTube (телефон и планшет — стоя и боком, с боковыми вкладками и панелями), без рекламы, SponsorBlock, дизлайки, комментарии, лайки, фон, PiP, плавающее окно и мини-плеер с видео, выбор качества и скорости, HD-превью, AMOLED-тема, история и подписки без аккаунта; вход в Google — по желанию; обновление ленты потягиванием вниз; самообновление с GitHub и зеркал. Релизы: https://github.com/DenHaIz/Tube_player/releases
- **Tube** (`app`) — локальный VPN, который пускает через обход только выбранные приложения (YouTube, ReVanced, Morphe). Релизы: https://github.com/DenHaIz/Tube_DPI/releases

## Tube

```
YouTube / ReVanced / Morphe ─► TUN (VpnService, только выбранные приложения)
   ─► hev-socks5-tunnel ─► SOCKS5 127.0.0.1:<свободный порт> ─► byedpi (desync) ─► интернет
```

- **Только выбранные приложения** идут через туннель (`addAllowedApplication`), остальные — напрямую.
- **Автозапуск с YouTube**: служба спецвозможностей видит только имя приложения на экране; включает обход при открытии клиента и выключает через N минут после ухода (не выключает, пока идёт фоновое воспроизведение).
- **Автоподбор стратегии**: ~80 вариантов (свой список + набор ByeByeDPI), каждый — в отдельном процессе byedpi на своём порту; проверяются `www.youtube.com` (> 64 КБ, видно замедление), `i.ytimg.com` и настоящий видеоузел сети `rrN---sn-….googlevideo.com` — и полным TLS, и ClientHello как у Chrome/YouTube (Kyber, ~1.8 КБ). Результаты хранятся по сетям, сторож меняет сломавшуюся стратегию.
- **DNS через DoH**: с февраля 2026 youtube.com не резолвится через национальный DNS. Туннель отвечает приложениям сам (mapdns), а byedpi резолвит имена через DNS-сервер Tube на 127.0.0.1, который ходит в DoH Google/Cloudflare по IP (`getaddrinfo` подменён при линковке, см. `core-byedpi/src/main/cpp/tube_dns.c`).
- **Техники Flowseal zapret-discord-youtube** перенесены в пределах byedpi без root: фейковые ClientHello google.com / vk.com / gosuslugi.ru и QUIC Initial из zapret (`assets/fake`), multisplit, hostfakesplit-аналог, фейковый SNI на выбор. Не переносятся без root: seqovl, badseq, ts-fooling, autottl.
- **QUIC** по умолчанию запрещён (`byedpi -U`) — YouTube уходит на TCP.
- Плитка в шторке, ярлык «YouTube через Tube», RU/EN, светлая/тёмная тема, стекло из Woody.

## Модули

| Модуль | Что внутри |
|---|---|
| `core-byedpi` | byedpi (submodule) → исполняемый `libbyedpi.so`; `ByeDpiProcess`, `StrategyArgs`, `Strategies`, `StrategyTester` |
| `core-tunnel` | hev-socks5-tunnel (submodule, ndk-build) + JNI `TProxyService`, `TunnelConfig` |
| `core-strategy` | подбор стратегии, сторож, результаты по сетям — общие для обоих приложений |
| `core-update` | обновления из релизов GitHub и зеркал с проверкой SHA-256 и подписи — общие для обоих приложений |
| `core-ui` | тема, стекло, компоненты (порт из Woody) |
| `app` | Tube: VPN-сервис, автозапуск, плитка, экраны |
| `client` | Tube Player: NewPipeExtractor, Media3 (DASH), OkHttp через встроенный byedpi, SponsorBlock, вход в Google |

byedpi собирается исполняемым файлом, а не библиотекой: у него глобальное состояние, поэтому каждый экземпляр — отдельный процесс (`packaging.jniLibs.useLegacyPackaging = true`, чтобы файл распаковывался в `nativeLibraryDir`).

## Сборка

Тулчейн общий с Woody: `.toolchain` — junction на `..\Android TODO\.toolchain` (создаётся `env.ps1`). Путь без пробелов обязателен: ndk-build ломается на пробеле, а 8.3-имена Gradle разворачивает обратно.

```powershell
git submodule update --init --recursive
. .\env.ps1
.\gradlew.bat testDebugUnitTest                  # логика (Robolectric)
.\gradlew.bat :app:testDebugUnitTest -Pscreenshots   # скриншоты → app\build\outputs\roborazzi
.\gradlew.bat :app:lintDebug :app:assembleRelease
.\gradlew.bat :client:testDebugUnitTest -Pscreenshots   # скриншоты Tube Player
.\gradlew.bat :client:lintDebug :client:assembleRelease
```

Подробности для продолжения работы — в [HANDOFF.md](HANDOFF.md).

Release подписан debug-ключом общего тулчейна (`.toolchain/android-user-home/debug.keystore`) — не терять, иначе обновление не встанет поверх.

Логотип: `python -I tools/pixel_logo.py` (превью) и `python -I tools/pixel_logo.py android app/src/main/res`.

## Ограничения

- При «белых списках» мобильных операторов (фактическое отключение интернета) DPI-обход не помогает.
- С ноября 2026 у части провайдеров (ТТК, Ростелеком, Дом.ру) тормозят видео, которых нет в кэше GGC провайдера: это не DPI, стратегии тут не помогают.
- Одновременно в Android может работать только один VPN.
- Android 13+ для APK не из магазина: перед включением службы спецвозможностей нужно «Сведения о приложении → ⋮ → Разрешить ограниченные настройки».

## Лицензия

GPL-3.0. NewPipeExtractor (TeamNewPipe) — GPL-3.0; SponsorBlock и Return YouTube Dislike — открытые API сообществ. byedpi (hufrea) и hev-socks5-tunnel (heiher) — MIT; фейковые пакеты из zapret (bol-van) — MIT; набор стратегий из ByeByeDPI (romanvht) — GPL-3.0.
