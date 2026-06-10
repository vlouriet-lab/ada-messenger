# Forensic Security Audit — ADA Messenger

**Дата:** 12 апреля 2026  
**Область:** Android-приложение + Rust core (ada-core)  
**Цель:** Оценка устойчивости зашифрованного кэша к форензик-извлечению

---

## Общая оценка: ВЫСОКИЙ уровень защиты

ADA Messenger реализует многоуровневое шифрование с корректным применением криптографических примитивов. Архитектура значительно затрудняет извлечение данных даже при физическом доступе к устройству.

---

## 1. Шифрование базы данных — SQLCipher

### ✅ Реализовано правильно

| Компонент | Статус | Детали |
|-----------|--------|--------|
| SQLCipher AES-256 | ✓ | `bundled-sqlcipher-vendored-openssl` (Cargo.toml) |
| PRAGMA key первым | ✓ | storage.rs:229, store.rs:138 — до любых запросов |
| WAL шифрование | ✓ | SQLCipher 4.x шифрует WAL/SHM автоматически |
| KDF | ✓ | Argon2id: 64 MiB RAM, 3 итерации, 32 байта выход |

### Файлы на устройстве:
- `files/kv_store.db` — ключи, ratchet-состояния (зашифрованы SQLCipher)
- `files/messages.db` — история сообщений (зашифрованы SQLCipher)

### Второй слой: ratchet-шифрование
Ratchet-состояния в таблице `ratchet_enc` дополнительно зашифрованы XChaCha20-Poly1305.  
Ключ: HKDF-SHA256 от db_key с контекстом `"ada/ratchet-enc/v1"`.  
→ Даже при компрометации SQLCipher нужно ещё взломать ratchet-шифрование.

---

## 2. Деривация ключей — Pattern → DB Key

| Параметр | Значение |
|----------|----------|
| Алгоритм | Argon2id v0x13 |
| Память | 64 MiB |
| Итерации | 3 |
| Параллелизм | 1 |
| Выход | 32 байта (AES-256 ключ) |
| Соль | 32 байта, случайная (файл `ada_identity.salt`) |

**Оценка bruteforce:**
- Теоретическая энтропия паттерна: ~74 бит (C(64,16) × 3^16)
- Скорость перебора на GPU (RTX 4090): ~100 паттернов/сек (из-за 64 MiB Argon2id)
- 2^74 / 100 = ~5.9 × 10^20 секунд = **~19 триллионов лет**
- Даже при реалистичных 50 битах: 2^50 / 100 = ~357 лет на одном GPU

**Вывод:** Нереально методом перебора с корректно выбранным паттерном.

---

## 3. Хранение ключей — Android Keystore

### ✅ Background cells
- Зашифрованы AES-256-GCM через Android Keystore (hardware-backed)
- Ключ: `ada_bg_cells_key`, не экспортируемый
- IV генерируется заново при каждом сохранении
- Хранение: SharedPreferences (IV + ciphertext в Base64)

### ✅ PIN
- PBKDF2-HMAC-SHA256, 600 000 итераций
- EncryptedSharedPreferences (Android Keystore AES256-SIV + AES256-GCM)

---

## 4. Медиа-файлы

### ⚠️ Среднего риска: Временные файлы записи

**Файлы:**
- `cache/voice_*.ogg` — голосовые сообщения (plaintext, temp)
- `cache/vidnote_*.mp4` — видеосообщения (plaintext, temp)

**Жизненный цикл:**
1. Записано в `cacheDir/` в открытом виде (требование MediaRecorder)
2. Отправлено через core (зашифровано E2E)
3. Удалено вызовом `file.delete()`

**Риск:** `delete()` выполняет логическое удаление (unlink), а не перезапись.
На flash-памяти (Android) физические блоки могут сохраняться до TRIM.
Карвинг по magic-bytes OGG/MP4 теоретически может восстановить файл.

**Практический риск:** НИЗКИЙ
- Файлы живут доли секунды (время отправки)
- Android f2fs/ext4 с FBE (File-Based Encryption) шифрует на уровне FS
- На устройствах с Android 10+ (API 29) `cacheDir` находится в credential-encrypted storage
- Для извлечения нужен root + BFU (Before First Unlock) доступ — крайне маловероятно

**Рекомендация:** Добавить `secureDelete()` — запись нулями перед `delete()`:
```kotlin
fun secureDelete(file: File) {
    if (!file.isFile) return
    val len = file.length()
    RandomAccessFile(file, "rw").use { f -> f.write(ByteArray(minOf(len, 10_000_000L).toInt())) }
    file.delete()
}
```

---

## 5. Кэшированные вложения

**Путь:** `cache/attachments/{fileId}/{filename}`

Полученные файлы (изображения, видео, документы) сохраняются в `cacheDir` в дешифрованном виде для отображения в UI. Это **ожидаемое поведение** — идентично WhatsApp, Signal, Telegram.

**Защита:**
- Android credential-encrypted storage (FBE)
- Kill PIN уничтожает все кэшированные файлы
- Новая опция "Очистить кэш" в настройках (добавлена в этом обновлении)

---

## 6. Логирование

**Файл:** `files/ada.log` (ротация)

**Проверено:** Логи содержат только:
- Сетевые события (connected/disconnected)
- Transfer ID (hex, не связан с содержимым)
- Peer ID фрагменты в debug-level записях

**Рекомендация:** В production сборке установить уровень логирования `WARN` для core.

---

## 7. Сценарии форензик-извлечения

### Сценарий A: Разблокированный телефон (ADB)

| Данные | Доступ | Причина |
|--------|--------|---------|
| SQLite БД | ❌ | SQLCipher + нет ключа |
| Кэш медиа | ⚠️ | Plaintext в `cacheDir/`, но FBE защищает |
| Pattern/ключи | ❌ | Только в Keystore / в памяти процесса |
| Temp записей | ⚠️ | Теоретически карвинг, крайне маловероятно |

### Сценарий B: Root + физический доступ

| Данные | Доступ | Причина |
|--------|--------|---------|
| SQLite БД | ❌ | SQLCipher, ключ = Argon2id(pattern) |
| Ratchet states | ❌ | XChaCha20-Poly1305 поверх SQLCipher |
| Background cells | ❌ | Android Keystore (hardware) |
| PIN hash | ❌ | EncryptedSharedPreferences + Keystore |
| Кэш медиа | ✅ | Plaintext, если устройство разблокировано |
| Identity salt | ⚠️ | Plaintext файл, но бесполезен без паттерна |

### Сценарий C: Cellebrite / Oxygen / GrayKey

| Инструмент | Результат |
|------------|-----------|
| Cellebrite UFED | Извлечёт файловую систему, но БД зашифрованы |
| Oxygen Forensic | Может прочитать кэш медиа, если устройство разблокировано |
| GrayKey | Зависит от устройства, БД остаётся зашифрованной |
| Ручной dd + strings | Только незашифрованные фрагменты из кэша |

**Ключевой вывод:** Без знания паттерна пользователя невозможно расшифровать историю сообщений, ключи или ratchet-состояния. Медиа-кэш доступен только на разблокированном устройстве, как и в Signal/WhatsApp.

---

## 8. Сравнение с конкурентами

| Фича | ADA | Signal | WhatsApp | Telegram |
|-------|-----|--------|----------|----------|
| БД шифрование | SQLCipher | SQLCipher | SQLite (plain) | SQLite (plain) |
| KDF | Argon2id | HKDF | N/A | N/A |
| Ratchet шифр. | XChaCha20-Poly1305 | отдельная БД | ❌ | ❌ |
| Медиа шифр. at-rest | FBE only | FBE only | ❌ plaintext | ❌ plaintext |
| Kill PIN | ✅ | ❌ | ❌ | ❌ |
| Clean PIN (decoy) | ✅ | ❌ | ❌ | ❌ |
| Pattern auth | ✅ (74 bit) | PIN | PIN | PIN/password |

---

## 9. Рекомендации

### Высокий приоритет
1. **Secure delete temp recordings** — перезапись нулями перед `delete()` для voice/vidnote файлов
2. **Production log level** — `WARN` для Rust core в release builds

### Средний приоритет
3. **Медиа-кэш шифрование** — опционально шифровать файлы в `cacheDir/attachments/` через EncryptedFile API (за счёт CPU)
4. **Автоочистка кэша** — по таймеру (30 дней) или по объёму (> 500 МБ)

### Низкий приоритет
5. **Salt в БД** — перенести `ada_identity.salt` в зашифрованную SQLCipher таблицу (не критично, т.к. salt не является секретом)
6. **Ratchet HKDF salt** — добавить случайную соль (не критично при 32-byte IKM)
