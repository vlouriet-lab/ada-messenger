# ADA Messenger — Описание идей (Roadmap 2.0)

> Актуализировано: 14 мая 2026. Статусы ниже разделяют наличие end-to-end кода и production-доказанность на реальных устройствах/сетях.

В связи с успешным завершением основного пула разработки (Roadmap 1.0), этот документ описывает план дальнейшего развития (Roadmap 2.0). Главный фокус — усиление безопасности, обхода цензуры и UX.

## 1. Усилители физической безопасности (Duress & Anti-Forensics)
> **Статус:** Частично реализовано (Kill PIN готов). Предстоит доработка UI компонентов.
- [x] **Duress Lock / Kill PIN:** Полное необратимое удаление всех криптографических баз, конфигов и сессий при вводе Panic PIN на экране разблокировки. (Уже встроено в `executeKillCode`).
- [x] **Clean Mode (Decoy PIN):** Отображение "чистого" мессенджера (без чатов и контактов) при вводе второго PIN-кода.
- [x] **Исчезающие сообщения (Ephemeral Messages / TTL):** Сообщения автоматически стираются через настроенный промежуток времени (например, 1 минута, 1 час) после их расшифровки (или прочтения). Требует поддержки в `MessageKind` и `store.rs`.
- [x] **Self-Destruct (Auto-Wipe):** Самоуничтожение локальной базы в случае неактивности приложения (например, 14 дней) или 10 неудачных попыток ввода паттерна/PIN (уже частично поддерживается на уровне счетчиков попыток).

## 2. Выживание при полном блэкауте (Network Blackout Survival)
- [x] **BLE / Wi-Fi Direct Mesh Transport (BETA / field-unproven):** Android BLE/Wi-Fi Direct transport, handshake, frame codec and Rust `receive_mesh_bytes` path are implemented end-to-end for offline/local delivery. Production readiness still depends on device-matrix validation: permissions, MTU/chunking, reconnects, Doze/background limits and cross-vendor Android behavior.
- [x] **Bridge Steganography:** Обёртка инвайт-стрингов и сетевых конфигураций (Relay nodes / Bridges) в стегано контейнеры (.jpg, .png), чтобы их можно было передавать через ненадёжные/обычные мессенджеры в обход AI-анализаторов пакетов DPI.

## 3. Пользовательский UX (Premium Messaging)
- [x] **Reactions (Реакции на сообщения 1-tap):** Эмодзи-реакции, интегрированные как новый MessageKind.
- [x] **Редактирование сообщений (Editing):** Отправка `MessageKind::Edit`, ссылающегося на старое сообщение по ID. Локальная БД будет отображать самое свежее измененное сообщение.
- [x] **Offline Full Text Search (FTS):** Миграция SQLite-движка на FTS5 для сверхбыстрого локального поиска по базе сообщений.

## 4. Мультиплатформенность
> Разработка Desktop и других клиентов пока заморожена. Фокусируемся на Mobile Anti-Censorship.