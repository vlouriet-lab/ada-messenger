# ADA Messenger — CI/CD & Logging Guide

> Актуализировано: 14 мая 2026. Точные количества тестов не фиксируются в этом документе, потому что они меняются вместе с кодом; источником истины являются workflow commands и вывод CI.

## 🚀 CI/CD GitHub Actions — Что это?

**CI/CD** = **Continuous Integration / Continuous Deployment**

Это автоматизированный процесс, который запускается при каждом push/pull request:

### Схема процесса

```
┌─ Разработчик pushit код в main/develop ──┐
│                                           │
├─ GitHub Actions автоматически:          │
│  1. Скачивает код                        │
│  2. Компилирует Rust ядро (ada-core)    │
│  3. Собирает Android APK                 │
│  4. Запускает тесты                      │
│  5. Проверяет безопасность               │
│  6. Создаёт release (если тег)           │
│                                           │
└─ Результат: готовый APK в Artifacts ─────┘
```

### Что происходит в build-android.yml

| Шаг | Что делает | Время |
|-----|-----------|-------|
| **build-core** | Компилирует Rust для 3 архитектур (ARM64, ARM, x86_64) | ~5 мин |
| **build-android-app** | Собирает debug + release APK | ~10 мин |
| **test-core** | Запускает Rust unit/integration tests, standard integration и hostile-network harness через `mobile-dev` feature set | ~3 мин+ |
| **security-audit** | Проверяет уязвимости | ~2 мин |
| **lint-kotlin** | Проверяет код Kotlin | ~1 мин |
| **release** | Создаёт GitHub Release (при теге) | - |

**Общее время:** ~20 минут

### Какие события запускают CI/CD?

```yaml
push:
  - На любой push в main/develop
  - На тег (v0.1.0, v0.1.0-beta.1 и т.д.)
pull_request:
  - На каждый PR в main/develop
workflow_dispatch:
  - Ручной запуск через GitHub UI
```

### Как использовать?

#### 1. Посмотреть статус сборки

```bash
# В репозитории GitHub:
Actions → build-android.yml → статус последней сборки
```

#### 2. Скачать APK

Когда сборка завершится, в **Artifacts** будут:
- `ada-messenger-debug.apk` — для тестирования
- `ada-messenger-release-unsigned.apk` — production-версия (без подписи)

```bash
# Установить debug APK:
adb install -r ada-messenger-debug.apk
```

#### 3. Создать release

Требуется создать git tag:

```bash
git tag v0.1.0-alpha.1
git push origin v0.1.0-alpha.1
```

GitHub Actions автоматически:
1. Собирёт APK для этого тега
2. Создаст Release с APK внутри
3. Разметит как pre-release если tag содержит alpha/beta

---

## 📝 Tracing-Based Logging — Структурированные логи

### Что это?

**Tracing** = структурированное логирование с фильтрацией и форматированием.

Вместо простых текстовых логов:
```
[ERROR] Connection failed
```

Получается структурированная информация:
```
[DEBUG] target=ada.network.hybrid peer=abc123 bytes=512 latency_ms=45 msg="iroh direct send ✓"
```

### Как инициализировать?

В **MainActivity.kt** (при старте приложения):

```kotlin
// В onCreate() или при инициализации Core
AdaCore.Companion.initTracing(dataDir, isMobile = true)
```

В **Rust** коде:

```rust
// В main.rs или bin
use ada_core::logging;

#[tokio::main]
async fn main() {
    logging::init_tracing("/var/log", is_mobile=false);
    // Теперь все логи готовы к работе
}
```

### Как логировать в коде?

#### В Rust:

```rust
use tracing::{debug, info, warn, error};

// Простой лог
debug!("Processing peer: {}", peer_id);

// Со стуктурированными данными
debug!(
    target: "ada.network.hybrid",
    peer = %peer_short,
    bytes = data.len(),
    latency_ms = elapsed,
    "iroh direct send ✓"
);

// С ошибками
warn!("Failed to establish connection: {}", error);
```

#### В Kotlin:

```kotlin
// Через логирование в Rust-стороне
// или через Android Log (текущее):
Log.d(TAG, "Message: ${data}")
```

### Уровни логирования

| Уровень | Когда использовать | Условие фильтра |
|---------|-------------------|-----------------|
| **TRACE** | Очень детальное состояние | Редко вкл. |
| **DEBUG** | Отладка (по умолчанию) | `ada=debug` |
| **INFO** | Важные события | `ada=info` |
| **WARN** | Предупреждения (проблемы, но работает) | Всегда вкл. |
| **ERROR** | Ошибки | Всегда вкл. |

### Как фильтровать логи?

На **десктопе** (Windows/Mac/Linux):

```bash
cd ada-core
export RUST_LOG=ada=debug,iroh=info
cargo run --bin ada-node
```

Результат в stderr:
```
[DEBUG] ada::network::iroh_fallback: peer=abc bytes=512 latency_ms=45 "iroh direct send ✓"
[INFO] ada::core: ADACore started successfully
```

На **Android** (в prod):

Логи пишутся в файл `/data/data/com.ada.messenger/ada.log`

Сделать дамп логов:
```bash
adb pull /data/data/com.ada.messenger/ada.log ./
cat ada.log | tail -100
```

### Пример: трассирование гибридной отправки

```rust
// iroh_fallback.rs
pub fn log_send_attempt(ctx: &SendContext, attempt: &SendAttempt, outcome: &SendOutcome) {
    let peer_short = format!("{}", ctx.peer_id).chars().take(8).collect::<String>();
    
    match attempt.transport {
        TransportKind::Iroh if attempt.success => {
            tracing::debug!(
                target: "ada.network.hybrid",
                peer = %peer_short,
                bytes = ctx.wire_bytes_len,
                latency_ms = attempt.latency_ms,
                "iroh direct send ✓"
            );
        }
        TransportKind::Iroh => {
            tracing::debug!(
                target: "ada.network.hybrid",
                peer = %peer_short,
                error = ?attempt.error,
                "iroh failed, trying fallback"
            );
        }
        // ...
    }
}
```

**Результат в логе:**
```json
{
  "timestamp": "2026-04-10T14:30:45.123Z",
  "level": "DEBUG",
  "target": "ada.network.hybrid",
  "peer": "abc12345",
  "bytes": 512,
  "latency_ms": 45,
  "message": "iroh direct send ✓"
}
```

### Как использовать для monitoring?

1. **Собирать логи в файлы** ✅ (реализовано в logging.rs)
2. **Парсить структурированные данные** — используя JSON-формат
3. **Отправлять в систему мониторинга** — Grafana, Datadog, CloudWatch

Пример отправки в Firebase:

```kotlin
// AdaCoreViewModel.kt (будущее)
val logSnapshot = AdaCore.getLogsSnapshot()
Firebase.crashlytics.log(logSnapshot)
```

---

## 📊 Прямой iroh transport с tracing

Логирование автоматически отслеживает:

1. **Попытка iroh** (QUIC unicast)
   - Таймаут или успех
   - Время отклика

2. **Fallback на bridge live delivery** (если iroh live не сработал)
  - WebSocket TLS bridge is the primary production fallback
  - Requires reachable bridge and recipient listener for realtime delivery

3. **Mailbox / offline queue** (если live delivery недоступна)
   - Сохранено для retry
   - Автоматическая переотправка при reconnect

```
┌─────────────┐
│ send_message│
└──────┬──────┘
       │
    timeout: 3s
       │
       ├──→ iroh QUIC ────┐
       │    (relay-first   │
       │     CONNECT)  ✗   ├──→ [DEBUG] iroh failed
       │                  │
    timeout: 2s           │
       │                  │
        ├──→ bridge live ──┤
        │    (WS TLS)      │
       │                ✗ ├──→ [INFO] offline queue
       │                  │
        └──→ mailbox/offline
          (persist + retry)
```

### Как видеть logы в production?

На Android:
```bash
# Реал-тайм логи
adb logcat tag:ada
# Или сохранить в файл
adb pull /data/data/com.ada.messenger/ada.log . && tail -f ada.log
```

На сервере (Linux):
```bash
# Смотреть последние 50 строк
tail -50 /var/log/ada.log
# Или грепить по ошибкам
grep "ERROR\|iroh failed" /var/log/ada.log
```

---

## 🔧 Настройка для вашего проекта

### 1. В .github/workflows/build-android.yml

Секреты для подписания APK:

```yaml
# Settings → Secrets → New repository secret
ANDROID_KEYSTORE_BASE64      # base64-кодированный keystore
ANDROID_KEYSTORE_PASSWORD    # пароль от keystore
ANDROID_KEY_PASSWORD         # пароль от ключа
```

### 2. В ada-core/Cargo.toml

Уже добавлены:
```toml
tracing = "0.1"
tracing-subscriber = { version = "0.3", features = ["env-filter", "fmt", "ansi"] }
tracing-appender = "0.2"
```

### 3. В app/build.gradle.kts

Минимум API 26 (android 8.0) для crypto APIs (уже установлен).

---

## 🎯 Быстрый старт

```bash
# 1. Инициализировать логирование при запуске приложения
# (в MainActivity.kt)
AdaCore.initTracing(filesDir.absolutePath, isMobile = true)

# 2. Push код
git add .
git commit -m "feat: add tracing and fallback transport"
git push origin main

# 3. GitHub Actions автоматически:
#    - Компилирует Rust
#    - Собирает APK
#    - Запускает тесты
#    - Создаёт artifacts

# 4. Скачать APK из Artifacts и установить
adb install -r ada-messenger-debug.apk

# 5. Видеть логи
adb logcat tag:ada
```

---

## 📚 Ссылки

- [Tracing Documentation](https://docs.rs/tracing/latest/tracing/)
- [GitHub Actions Docs](https://docs.github.com/en/actions)
- [Android Logging](https://developer.android.com/studio/debug/logcat)
- [Cargo NDK](https://github.com/bbqsrc/cargo-ndk)

