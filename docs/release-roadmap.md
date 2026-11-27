# План функциональных Android-релизов Crocaun

Windows-релиз остаётся базой Android-нумерации. Для Android-only функционального preview разрешены отдельные суффиксные теги `v<Windows-версия>-android.N`, например `v1.11-android.1` и `v1.11-android.2`. Ручной workflow проверяет совпадающую стабильную Windows-базу и уникальность тега, требует `interop_confirmed=true`; существующие теги и релизы не переиспользуются. До прохождения release gate сборка остаётся GitHub Actions artifact.

## Актуальный статус

| Область | Состояние |
|---|---|
| Опубликовано | `v1.11` — ранний debug-signed preview. Его APK и тег не изменять и не переиспользовать. |
| Подтверждено пользователем | Базовые Croc Send/Receive Android↔Windows работают. Ранее завершены CI gates Go/AAR, Kotlin unit tests и debug APK. |
| Реализовано в текущей ветке, локально проверено и прошло CI; ожидает interop gate | SAF-выбор папок Send; ручные абсолютные пути файлов/папок; папка получения через SAF или абсолютный путь с auto-save; опциональная безопасная распаковка ZIP; Windows-совместимый LAN chat search UDP `29015` с Android responder; отдельное обозначение Internet chat как не реализованного. Croc LAN discovery UDP `29008` и relay checks были добавлены ранее. |
| Не реализовано | Сохранение структуры вложенных папок при отправке (сейчас файлы передаются плоским списком), Internet/Nostr chat, реальная proxy routing/configuration, foreground service, cancellation, byte-level progress, релизная подпись и лицензирование. Proxy UI не добавлять до включения и тестирования маршрутизации. |
| Текущая проверка | Локально прошли `scripts/build-transfer-aar.sh`, Go-тесты, `./gradlew --no-daemon --stacktrace testDebugUnitTest assembleDebug` (31 JVM-тест), XML-проверка ресурсов и `git diff --check`. [GitHub Actions run `45980463742`](https://github.com/infsofit/crocaun-android/actions/runs/45980463742) успешно завершился для коммита `d88fa05` (AAR, тесты, debug APK и Android-suffix smoke APK). Остаётся реальная Android↔Windows interop-проверка. |

## Релизная последовательность и gates

1. **CI gate пройден.** GitHub Actions run [`45980463742`](https://github.com/infsofit/crocaun-android/actions/runs/45980463742) успешно собрал AAR, выполнил Android-тесты и собрал APK-артефакты для коммита `d88fa05`.
2. **Текущий gate — Android↔Windows interop.** На реальных устройствах проверить обычную и пакетную отправку, SAF-папки через встроенный и сторонний Document Provider, ручные пути и ограничения scoped storage, вложенные каталоги/Unicode/конфликтующие имена, получение в SAF и ручной каталог, отсутствие перезаписи, автосохранение/ошибки доступа, ZIP с валидными и повреждёнными данными и Zip Slip, Croc discovery/локальный relay, chat UDP discovery и подключение найденного peer, обмен комнатами/сообщениями/историей/вложениями. Проверить неверный пароль, firewall/hotspot/Wi-Fi/VPN и отмену discovery.
3. **Android preview `v1.11-android.1`.** Кандидат только после двух gate выше и подтверждения `interop_confirmed=true`. Публиковать как тестовый debug-signed prerelease, не как production/Google Play. Не менять опубликованные `v1.11` Windows/Android теги.
4. **Надёжная длительная передача.** После базовой interop-стабильности добавить foreground service, отмену, реальный байтовый прогресс, восстановление/поведение при закрытии процесса и повторное подключение. Сейчас Croc facade синхронный, поэтому сначала нужен тестируемый Android-friendly cancellation/progress API на стороне Go.
5. **Internet chat.** Спроектировать самостоятельный Android backend, совместимый с Windows Nostr chat: ключи/identity, подписи, room/event mapping, history sync, presence и Internet file hosting. До полноценной проверки на тестовых аккаунтах поддерживать только LAN chat и явно маркировать Internet chat как недоступный.
6. **Proxy routing.** Реализовать в общих Settings только вместе с фактической поддержкой и тестированием каждого включённого сетевого пути (Croc transfer, Internet chat, при необходимости relay checks). Сверить схемы прокси с Windows; проверять SOCKS5/SOCKS4/HTTP CONNECT, authentication, ошибки/таймауты, direct mode и отсутствие утечки секретов. Пока proxy config отсутствует, потому что UI без работающей маршрутизации был бы вводящим в заблуждение.
7. **Стабильное распространение.** Выбрать лицензию, проверить лицензии зависимостей и права на иконки/ресурсы, определить политику диагностики, подготовить постоянный release signing key и восстановление, проверить release APK на чистой установке/обновлении и пройти правила Google Play.

## Завершённые этапы — историческая база

### Этап 1 — переносимое ядро Croc

Croc `v10.0.12` с Crocaun race-патчем, совместимый Windows password/relay mapping, Go facade, golden tests и сборка AAR. CI run `45346211978` собрал ядро и debug APK на коммите `6145ea2`. Живая Windows↔Android передача в этот gate не входила.

### Этап 2 — базовая передача файлов

Send/Receive экраны, приватный staging, SAF-экспорт, Go/AAR и unit tests. Этап прошёл CI run `45534230357` на коммите `1c24baa`; пользователь отдельно подтвердил базовую двустороннюю передачу Android↔Windows. Расширенные негативные и folder-selection сценарии должны пройти текущий interop gate.

### Этап 3 — подготовка файлов и Croc discovery

Ранее CI run `45545301885` подтвердил пакетную SAF-отправку. В текущей ветке добавлены ручные абсолютные пути файлов/каталогов и выбор SAF-каталога; проверка файлового доступа и staging обязательны. Croc discovery использует `CROCAU1?`/`CROCAU1!` на UDP `29008`, находит режимы `send`/`recv`, ограничивает подсети и позволяет отменить поиск. Выбранный peer задаёт прямой relay; также доступна проверка публичных relay ping/pong. SAF и ручной выбор папок подготавливают вложенные файлы, но Croc отправляет их плоским списком без восстановления исходной структуры. Поле ручного пути может требовать отдельно выданный `MANAGE_EXTERNAL_STORAGE`; приложение не запрашивает широкий доступ автоматически и перед распространением нужно проверить требования Google Play.

## Совместимость чата и сети

LAN chat — отдельный от Croc передачи протокол. TCP слушатель использует `29014`, Windows-совместимый UDP discovery — `29015` и формат `CROCAUCHAT1?`/`CROCAUCHAT1!`. Текущая реализация поиска/responder должна пройти реальную interop-проверку и проверку Wi-Fi/hotspot/firewall. Вложения LAN-чата не используют Croc file-transfer relay. Соединение TCP не шифруется целиком: перед запуском в недоверенной сети требуется отдельная оценка протокольных ограничений.

Android сейчас использует target SDK 36. До повышения target до 37+ добавить runtime-разрешение `ACCESS_LOCAL_NETWORK`, которого требует Android 17 для локальных TCP/UDP-соединений.

## Правило публикации

Каждый новый release-stage сначала проходит CI, затем межплатформенную проверку на реальных устройствах. Функция указывается в release notes только после своего gate. Если Windows-база уже опубликована, Android-only prerelease создаётся вручную через `workflow_dispatch` с `android_version` и подтверждением `interop_confirmed=true`; существующие Windows и Android tags/releases не изменяются. До этого момента результат остаётся Actions artifact.
