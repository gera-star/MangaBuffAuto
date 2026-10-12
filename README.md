# MangaBuffAutoBackground

Отдельное Android-приложение для фоновой автоматизации MangaBuff.

Это экспериментальная новая архитектура рядом с исходным MangaBuffAuto:
- automation/runtime принадлежит Foreground Service;
- Activity/Compose выступают только UI;
- состояние запуска сохраняется для START_STICKY recovery;
- WebView не уничтожается из Activity lifecycle;
- applicationId: com.example.myapplication.background

Исходное приложение в корне репозитория не изменяется.
