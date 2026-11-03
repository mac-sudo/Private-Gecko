# Private Gecko — 0.9.0-beta.1

Неофициальная Android-оболочка MAX Web с ограниченным доступом к телефону.
Не связана с владельцами MAX или Mozilla. Предварительный выпуск, не аудит-сертифицированный продукт.

Репозиторий: https://github.com/mac-sudo/Private-Gecko

**[Скачать APK и проверить SHA-256](https://github.com/mac-sudo/Private-Gecko/releases/tag/v0.9.0-beta.1)**

Для установки скачайте файл Private-Gecko-0.9.0-beta.1.apk из Assets выпуска.
Архивы Source code предназначены для сборки, не устанавливаются на телефон.

## Возможности

- Сохранение входа; просмотр сообщений и видео через GeckoView.
- Без доступа к контактам, SMS, камере, микрофону и геолокации.
- Опциональная системная разблокировка: шестерёнка → Блокировка при открытии.
- Очистка обычных фото от EXIF/GPS в новой копии; оригиналы не меняются.
- Строгая защита Gecko, GPC, блокировка AppTracer, изолированные content-процессы.
- Общие уведомления без текста сообщения; явный выбор и сохранение файлов.

MAX получает переписку, вложения и IP. Оболочка не добавляет сквозное шифрование,
не отключает всю аналитику и не даёт анонимность. Звонки и запись голоса недоступны.
Фоновые уведомления не гарантируются после остановки процесса. См. [PRIVACY.md](PRIVACY.md).

## Поддержка

Только arm64-v8a, конфигурация Android 9+ (API 28), targetSdk 36, compileSdk 37.
Реально проверенные устройства и ограничения: [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).
Полная матрица устройств пока не пройдена. Для публичной 1.0 нужны дополнительные тесты.

## Установка и обновление

Используйте APK из Releases этого проекта, сверяйте SHA256SUMS и релизный сертификат
из [UPDATES.md](UPDATES.md).
Устанавливайте поверх старой версии: удаление приложения удалит вход.

Идентификатор остаётся ru.drtrip.maxprivategecko.privatemediatest ради совместимости.
Не переименовывайте его для обновления существующей установки.

## Сборка

Нужны Java 17, Android SDK platform 37, build-tools 36.0.0, Node.js 22+, Python 3 и unzip.
Gradle 9.8.0 загружается Wrapper с проверкой SHA-256.
Укажите ANDROID_HOME либо локальный sdk.dir в local.properties (его не публикуют).

```sh
./gradlew --dependency-verification strict :app:runtimeInventory
python3 scripts/prepare-notices.py
./gradlew --dependency-verification strict :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
node scripts/check-release.mjs app/build/outputs/apk/release/app-release-unsigned.apk --unsigned
```

Зависимости закреплены lockfile и verification metadata. Не обновляйте контрольные суммы
вслепую при несовпадении. Notices уже включены в исходники; их генерация использует
проверенные runtime-артефакты и локально кешированные POM, не серверы аккаунтов.

## Подпись

Релиз подписывается локально, ключ не передаётся GitHub Actions.
MAX_SIGNING_DIR — внешний каталог с ключом max-private-release.p12,
release.pass и debug-to-release.lineage. ANDROID_HOME указывает SDK.

```sh
scripts/sign-release.sh
node scripts/check-release.mjs app/build/outputs/apk/release/app-release.apk
```

Чужая самостоятельная сборка с другим ключом не обновит официальный APK.
Скрипт проверки официального подписанного выпуска ожидает закреплённый сертификат.
Для своей fork-сборки используйте собственный applicationId и свою политику подписи.

## Проверка без аккаунта

```sh
./gradlew :app:assembleVerification
adb install -r app/build/outputs/apk/verification/app-verification.apk
adb shell am start -n ru.drtrip.maxprivategecko.privatemediatest.hardeningcheck/ru.drtrip.maxprivategecko.MainActivity
```

Это отдельный пакет со свежим профилем и синтетическими файлами. Успешный итог:
Защиты=true; H264=true; MP4/WebM=1:1; OK/OK; фото=true; очистка=true.
Он тестирует очистку только своих фиктивных данных. Проверка аккаунта пользователя не нужна.
После проверки: adb uninstall ru.drtrip.maxprivategecko.privatemediatest.hardeningcheck.
Не удаляйте пакет без суффикса hardeningcheck.

## Подготовка GitHub

[RELEASING.md](RELEASING.md) описывает релизные проверки и публикацию.
`python3 scripts/package-source.py` создаёт архив по разрешённому списку,
без ключей, кешей, локальных настроек, APK и старых значков.
Workflow проверяет сборку и unsigned APK, но не публикует и не получает ключи.

## Лицензии

Собственная оболочка, скрипты и документация — MIT. Gecko и сторонние зависимости
не перелицензируются: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [DEPENDENCIES.json](DEPENDENCIES.json).
Точная ревизия исходников Gecko указана в уведомлениях и локальном меню приложения.
