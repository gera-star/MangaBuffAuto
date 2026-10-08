# MangaBuffAuto

> 🤖 Android-приложение для автоматизации задач на [MangaBuff](https://mangabuff.ru)

[![Telegram](https://img.shields.io/badge/Telegram-%40mangabuffauto-229ED9?logo=telegram&logoColor=white)](https://t.me/mangabuffauto)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-16-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)

## 📱 Что это

**MangaBuffAuto** помогает автоматизировать рутинные действия на MangaBuff прямо на Android.

Можно самостоятельно выбрать нужные задачи и их количество. Приложение рассчитано в том числе на работу с несколькими аккаунтами и фоновую автоматизацию.

## ⚙️ Возможности

- 📖 **Читалка** — автоматическое чтение глав и контроль завершения главы.
- 📺 **Реклама** — автоматический просмотр доступной рекламы.
- ⛏️ **Шахта** — добыча руды, улучшения и обмен.
- 🧠 **Квиз** — автоматическое выполнение заданий.
- ⚔️ **Бои** — автоматические боевые циклы.
- 💬 **Комментарии** — автоматическая отправка комментариев с настраиваемым количеством.
- 🎟️ **Промокоды** — обработка промокодов.
- 👥 **Несколько аккаунтов** — отдельные WebView-профили и разделение сессий.
- 🌙 **Фоновая работа** — Foreground Service для продолжения автоматизации при свернутом приложении и выключенном экране.
- ⚙️ **Настройка количества** — можно указать, сколько рекламы смотреть, глав читать, комментариев отправлять и т. д.

## 📦 Скачать APK

### Текущая сборка background

**MangaBuff Auto 1.0-background — текущая основная версия**

[⬇️ Скачать APK](https://raw.githubusercontent.com/gera-star/MangaBuffAuto/main/releases/MangaBuffAuto-1.0-background.apk)

> APK размещён в ветке `background-rebuild`. Перед установкой рекомендуется удалить предыдущую версию приложения, если Android сообщает о несовместимой подписи.

## 🚀 Установка

1. Скачайте APK.
2. Разрешите установку приложений из этого источника в настройках Android, если система попросит.
3. Установите приложение.
4. Добавьте аккаунт MangaBuff.
5. Включите нужные задачи и задайте их количество.
6. Для фоновой работы разрешите уведомления и исключите приложение из ограничений энергосбережения, если это требуется вашим устройством.

## 🔐 Важно

Проект находится в активной разработке. Поведение отдельных задач может зависеть от изменений сайта MangaBuff и версии Android.

Не передавайте другим людям файлы сессий, cookies или данные аккаунта.

## 🛠️ Для разработчиков

Основная рабочая ветка текущей версии:

`main`

Стек: Kotlin, Jetpack Compose, Android WebView, Foreground Service.

Контрольная версия читалки:

[`archive/reader-stable/MangaBuffAutomation.kt`](archive/reader-stable/MangaBuffAutomation.kt)

## 📢 Telegram

[**@mangabuffauto**](https://t.me/mangabuffauto)

Новости, обновления, новые APK и информация о проекте.

Если проект оказался полезен — ⭐ **поставьте Star на GitHub** и поделитесь ссылкой с другими пользователями MangaBuff.

---

**GitHub:** https://github.com/gera-star/MangaBuffAuto  
**Telegram:** https://t.me/mangabuffauto
