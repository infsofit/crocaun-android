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

Будущий transfer module должен выделить отдельный portable Go-пакет с узким API send/receive/cancel/progress и повторно использовать точную Croc версию плюс Crocaun patch. Android adapter должен принимать SAF `content://`, stage-ить содержимое потоково, писать результат обратно через SAF, выполнять долговременную операцию с foreground service и хранить секреты через Android Keystore. Пока этот переносимый Go AAR не собран и не прошёл Android ↔ Windows interop tests, основные вкладки Send/Receive остаются заглушками; Android не должен обещать совместимость стандартной Croc-передачи.

## Автоматизация релизов

`.github/workflows/android.yml` запускается на push/PR для проверки, ежедневно и вручную для синхронизации. Скрипт `scripts/discover_releases.py` перебирает опубликованные стабильные Windows releases, принимает только канонические version tags и сверяет их с `gui_windows.go` на соответствующем теге. Для отсутствующих Android tags/releases workflow собирает APK, создаёт одинаковый Android tag и prerelease; если релиз без APK, asset добавляется повторно. Tag/release publication выполняется в том же run, который создал тег, так как tag push от `GITHUB_TOKEN` не должен запускать отдельный workflow. Права `contents: write` выдаются только publication job.

APK пока debug-signed. Это preview, а не публикация в Google Play; release signing key требует отдельного решения владельца.
