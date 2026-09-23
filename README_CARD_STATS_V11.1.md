# MangaBuffAuto V11.1 — Card Statistics

Добавлена опциональная статистика карт по мотивам MangaBuff Card Statistics (Greasy Fork 2.0.3).

## Что добавлено
- В нижней панели появилась кнопка 🃏.
- Нажатие включает/выключает статистику карт.
- Состояние переключателя сохраняется между запусками приложения.
- На карточках MangaBuff показываются:
  - 👤 владельцев;
  - ❤️ желают.
- Для получения данных используются страницы MangaBuff:
  - `/cards/{id}/users`
  - `/cards/{id}/offers/want`
- Результаты кэшируются на 30 минут, чтобы не создавать лишнюю нагрузку.
- При переходе на следующую страницу WebView статистика автоматически подключается снова.
- При выключении все оверлеи статистики удаляются.

## Важно
Статистика отключена по умолчанию. Она не запускает запросы, пока пользователь не нажмёт 🃏.

Сборка в текущем окружении не выполнена: Gradle Wrapper попытался скачать Gradle 9.6.0 с services.gradle.org, но сетевой доступ из окружения недоступен.


## v3 correction
- Uses the exact 2.0.3 batch endpoint `https://mbstat.space/cards?ids=...`.
- Selects the response by matching card id.
- Native fallback URLs are absolute (`https://mangabuff.ru/...`), not relative.
- Cache namespace was bumped so previous incorrect values cannot be reused.
- No default `1/1`: unavailable values render as `—`.
