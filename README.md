# Simple PDF Reader для Android

Простая и лёгкая читалка PDF-файлов для Android, написанная на **Kotlin** и **Jetpack Compose** с использованием встроенного системного движка `android.graphics.pdf.PdfRenderer` (без тяжёлых сторонних нативных библиотек).

## Возможности
- Открытие любого PDF-файла через системный файловый пикер (Storage Access Framework — не требует разрешений на доступ ко всем файлам).
- Открытие PDF-файлов напрямую из других приложений / проводника («Открыть с помощью...»).
- Вертикальная постраничная прокрутка с ленивой подгрузкой страниц.
- Масштабирование щипком (Pinch-to-Zoom) и двойным тапом, а также кнопка быстрого сброса масштаба.
- Индикатор текущей страницы и общего количества страниц в верхней панели.

## Автоматическая сборка через GitHub Actions (без Android Studio)

В проект уже добавлен workflow [`.github/workflows/android-build.yml`](.github/workflows/android-build.yml), который автоматически собирает готовый к установке `app-debug.apk` на серверах GitHub.

### Как собрать APK на GitHub:
1. Создайте новый репозиторий на GitHub.
2. Загрузите файлы этого проекта в репозиторий:
   ```bash
   git init
   git add .
   git commit -m "Initial commit: Simple PDF Reader"
   git branch -M main
   git remote add origin https://github.com/<ваш-аккаунт>/<ваш-репозиторий>.git
   git push -u origin main
   ```
3. Перейдите во вкладку **Actions** в вашем репозитории на GitHub.
4. Дождитесь завершения задачи **Build Android APK** (или запустите её вручную кнопкой **Run workflow**).
5. Внизу страницы завершённого запуска в блоке **Artifacts** скачайте архив **`SimplePdfReader-debug-apk`** — внутри находится готовый файл `app-debug.apk` для установки на телефон.
