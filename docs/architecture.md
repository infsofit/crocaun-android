# Архитектура Crocaun Android

## Платформа

Приложение — нативный Android-клиент на Kotlin и Jetpack Compose. Минимальная версия — Android 11 (API 30). Windows GUI остаётся независимым и не переносится.

## Реализованный LAN-чат

`app/src/main/java/com/infsofit/crocaun/chat/LanChatClient.kt` реализует первый Android `ChatBackend` для локальной Windows chat-сети:

- TCP listener и ручное подключение к peer на порту `29014`; JSON Lines и поля `hello`, `who`, `rooms`, `have`, `m`, `get`, `file` совместимы с `chatnet.go`.
- PBKDF2-HMAC-SHA256 (`crocau-chat-v1`, 20 000 итераций), room ID и AES-256-GCM payload повторяют `chatcore.go`; Kotlin golden tests фиксируют ключ и room ID общей комнаты.
- История записывается app-private JSONL из зашифрованных кадров. Android пароль комнаты не сохраняет.
- LAN chat file offer переносит SAF `content://` выбранный документ во временное app-private staging и предоставляет прямую TCP-загрузку по `get`/`file`, совместимую с Windows local chat.
- Источник одной сессии живёт в корне Compose приложения, поэтому соединение не останавливается при переключении вкладки. Экран History читает сообщения из тех же комнат.

Discovery по UDP `29015` пока не реализован; peer вводится вручную. Internet/Nostr backend не реализован. Это означает только частичную совместимость чата: LAN-сообщения, комнаты, синхронизация истории и прямые chat attachments; Nostr события, подписи, статусы roster, общая очистка и Internet file hosting Android пока не поддерживает. Перед публичным production-использованием нужна живая interop-проверка Android ↔ Windows.

LAN protocol шифрует chat frame содержимое, но не весь TCP транспорт. LAN-вложение передаётся напрямую без AES файлового шифрования, как в Windows local-chat backend. Не использовать его в недоверенных сетях.

## Основная передача Croc

Windows основной transfer использует Croc CLI `github.com/schollz/croc/v10 v10.0.12`, кодирование Crocaun-пароля из `runner.go` и патч `patches/croc-v10.0.12-sendfile-race.patch`. Текущая Windows-обёртка — `package main`, запускает собственный EXE дочерним процессом и зависит от Windows UI/DPAPI/firewall слоёв. Прямой `gomobile bind` на эту обёртку не является рабочим Android API.

В `go-core/transfer` добавлен отдельный portable Go-фасад поверх Croc API. `scripts/prepare-croc-go.sh` загружает upstream Croc по зафиксированному commit v10.0.12 и применяет Windows race-fix плюс Android-патчи: они скрывают Croc secret от stderr/logcat и пропускают ненужный startup DNS lookup, так как facade задаёт relay явно. Фасад повторяет Windows password mapping и password-derived relay order; golden unit tests фиксируют эти значения и базовые Croc options. `scripts/build-transfer-aar.sh` запускает тесты, проверяет Java signatures через gobind и собирает Android AAR через gomobile; workflow сохраняет AAR и APK как временные CI artifacts. Совместная AAR/APK-сборка успешно прошла в run `45346211978`.

Начальные Send/Receive Compose-экраны уже вызывают AAR вне UI-потока. Send копирует один выбранный SAF-документ в приватную app-папку, а Receive принимает в новую приватную staging-папку и экспортирует результат обратно через SAF. Передача всё ещё требует живой Android ↔ Windows Croc interop-проверки. Foreground service, отмена и достоверный прогресс не реализованы; синхронный Go facade не должен вызываться на UI-потоке, а процесс нужно держать открытым до окончания операции.

## Автоматизация релизов

`.github/workflows/android.yml` запускается на push/PR для проверки, ежедневно и вручную для синхронизации. Скрипт `scripts/discover_releases.py` перебирает опубликованные стабильные Windows releases, принимает только канонические version tags и сверяет их с `gui_windows.go` на соответствующем теге. Для отсутствующих Android tags/releases workflow собирает APK, создаёт одинаковый Android tag и prerelease; если релиз без APK, asset добавляется повторно. Tag/release publication выполняется в том же run, который создал тег, так как tag push от `GITHUB_TOKEN` не должен запускать отдельный workflow. Права `contents: write` выдаются только publication job.

APK пока debug-signed. Это preview, а не публикация в Google Play; release signing key требует отдельного решения владельца.

Последовательность функциональных gates и правило временных CI-сборок описаны в [`docs/release-roadmap.md`](release-roadmap.md).
