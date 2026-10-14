# План реализации улучшений в дизайне и пользовательском опыте (UI/UX) ADA Messenger

> **Статус документа:** Проект плана (ожидает подтверждения)  
> **Целевые платформы:** Android (Kotlin / Jetpack Compose) и Windows Desktop (Compose Multiplatform), ядро Rust (`ada-core`)  
> **Дата составления:** 15 октября 2026 г.

---

## Введение и цели

Настоящий план описывает пошаговую реализацию исправлений и улучшений в дизайне и пользовательском опыте (UI/UX) мессенджера ADA. План составлен на основе аудита кодовой базы (`ChatScreen.kt`, `NewChatInputBar.kt`, `PatternRegistrationScreen.kt`, `ConnectionStatusBar.kt`, `DesktopWorkspaceScreen.kt`), существующей тестовой базы (`ConnectionStatusBarTest`, `AdaModelsTest`, `MainActivityNavigationTest`, `integration.rs`, `AccountSyncProtocolTest`) и архитектурных документов проекта (`CONTACT_VERIFICATION.md`, `ROADMAP_2_0.md`).

### Ключевые цели
1. **Устранить критические визуальные и функциональные дефекты** в интерфейсе сообщений (дублирование цитат, потеря статуса `(изм.)`, невозможность открыть фото и сохранить файл в группах).
2. **Исправить эргономические проблемы управления** (срыв записи аудио/видео сообщений на экранах с высоким DPI, отсутствие кнопки возврата при ошибке ввода графического ключа).
3. **Раскрыть криптографические возможности ядра в UI** (Safety Numbers / 60-значные числа безопасности Signal-style, оповещения о смене ключей `ContactKeyChanged`, бейджи доверия).
4. **Улучшить эстетику и информативность сетевых состояний** (автоскрытие полосы соединения `GREEN`, понятный язык статусов для неподготовленных пользователей).
5. **Повысить зрелость десктопного приложения** (трехпанельный макет, горячие клавиши, создание групп).

---

## Фаза 0. Подготовка и фиксация тестового базиса (Pre-flight)

Перед внесением изменений в UI фиксируется текущее «зеленое» состояние тестов ядра и Android JVM unit-тестов.

- [ ] **0.1. Проверка baseline тестов Rust-ядра:**
  ```powershell
  cargo test --manifest-path ada-core/Cargo.toml --no-default-features --features mobile-dev --lib --tests
  ```
- [ ] **0.2. Проверка baseline unit-тестов Android:**
  ```powershell
  ./android-app/gradlew.bat -p android-app testDebugUnitTest
  ```
- [ ] **0.3. Проверка компиляции Android Debug APK:**
  ```powershell
  ./android-app/gradlew.bat -p android-app :app:assembleDebug
  ```

---

## Фаза 1. P0 — Критические исправления в интерфейсе чата и вводе сообщений

Фокус: ликвидация багов рендеринга и восстановление нормального поведения интерактивных элементов.

### 1.1. Ликвидация дублирования плашки цитирования в личном чате
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt#L809-L879)
* **Проблема:** Блок `AnimatedVisibility(visible = replyToMsg != null)` продублирован дважды подряд (строки 809–843 и 844–879).
* **Действие:**
  - Удалить дублирующий фрагмент.
  - Сохранить корректную верстку с цветовым индикатором `MaterialTheme.colorScheme.primary`, именем автора, текстом сообщения и кнопкой отмены (`Icons.Default.Close`).

### 1.2. Исправление суффикса «(изм.)» у отредактированных сообщений
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt#L1648-L1666)
* **Проблема:** В ветке `if (annotated.getStringAnnotations("URL", 0, annotated.length).isEmpty())` вызывается `Text(text = msg.text)`, где теряется пометка `(изм.)`, добавленная в `annotated`.
* **Действие:**
  - Унифицировать отображение текста: использовать `annotated` строку с суффиксом `(изм.)` как для текста со ссылками, так и для текста без ссылок.
  - При отсутствии ссылок выводить `Text(text = annotated)` с сохранением стилизации Material 3.

### 1.3. Восстановление полноэкранного просмотра и сохранения медиа в группах
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt#L2940-L2978)
* **Проблема:** В `GroupChatScreen` параметр `onOpenImage` заглушен `{ _, _ -> }`, а `onSaveFile` пропущен в вызове `MessageBubble`.
* **Действие:**
  - Добавить в `GroupChatScreen` состояние `openImage: OpenImagePayload?` и диалог полноэкранного просмотра картинок с жестами зума/панорамирования.
  - Подключить лаунчер `ActivityResultContracts.CreateDocument("*/*")` для сохранения файлов и пробросить `onSaveFile` в `MessageBubble`.

### 1.4. Исправление порогов жестов и локализация панели ввода
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/components/NewChatInputBar.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/components/NewChatInputBar.kt#L239-L305)
* **Проблема:** Пороги свайпа для отмены и фиксации замочка захардкожены в сырых пикселях (`dragX < -100f`, `dragY < -100f`), что вызывает срывы записи на экранах с высоким DPI; строка подсказки захардкожена на английском.
* **Действие:**
  - Рассчитывать пороги свайпа в `dp`:
    ```kotlin
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { 64.dp.toPx() }
    val lockThresholdPx = with(density) { 56.dp.toPx() }
    ```
  - Добавить строковый ресурс `R.string.chat_record_slide_hint` (*«< Проведите для отмены | ^ Заблокировать»*) в `values/strings.xml` и переводы.
  - Добавить вызов `haptic.performHapticFeedback(HapticFeedbackType.LongPress)` при фиксации замочка и `HapticFeedbackType.TextHandleMove` при свайпе отмены.

---

## Фаза 2. P1 — Онбординг: надежность графического ключа (Pattern Auth UX)

Фокус: исключение тупиковых сценариев при создании мастер-ключа.

### 2.1. Добавление шага отката при ошибке подтверждения паттерна
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/PatternRegistrationScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/PatternRegistrationScreen.kt#L168-L198)
* **Проблема:** На шаге `RegStep.CONFIRM` при несовпадении узора ячейки очищаются, но вернуться на шаг 1 невозможно — пользователь не может перерисовать или посмотреть забытый рисунок.
* **Действие:**
  - Добавить вторичное действие: `secondaryActionLabel = stringResource(R.string.pattern_register_change_first)` (*«Изменить начальный узор»*).
  - При нажатии возвращать состояние в `step = RegStep.DRAW`, сохраняя первоначальный выбор ячеек для корректировки.

### 2.2. Улучшение обратной связи сетки 8×8 (PatternBoardView)
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/components/PatternBoardView.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/components/PatternBoardView.kt)
* **Действие:**
  - Добавить поддержку непрерывного ведения пальцем (drag-to-paint) с отсечкой повторных срабатываний в пределах одной ячейки.
  - Добавить короткую вибрацию при закрашивании ячейки.
  - Отображать цветной бейдж-счетчик: сколько ячеек какого цвета выбрано (например: 5 синих, 6 бирюзовых, 5 оранжевых).

---

## Фаза 3. P1 — Раскрытие криптографической безопасности в UI (Contact Safety & Verification)

Фокус: Signal-level подтверждение подлинности собеседников на базе реализованного в `ada-core` протокола.

### 3.1. JNI и ViewModel обвязка верификации контактов
* **Файлы:**
  - [`ada-core/src/jni.rs`](file:///d:/QUADA/ada-core/src/jni.rs)
  - [`android-app/app/src/main/java/com/ada/messenger/core/AdaCore.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/core/AdaCore.kt)
  - [`android-app/app/src/main/java/com/ada/messenger/core/AdaCoreViewModel.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/core/AdaCoreViewModel.kt)
* **Действие:**
  - Пробросить методы FFI: `ada_get_contact_security_json`, `ada_mark_contact_verified`, `ada_clear_contact_verified`, `ada_accept_contact_key_change`, `ada_reject_contact_key_change`.
  - Подписаться на событие `ADAEvent::ContactKeyChanged` в цикле опроса событий `pollEvents()` и транслировать его в `StateFlow<ContactKeyChangeEvent?>`.

### 3.2. Экран / BottomSheet «Безопасность собеседника» (Safety Numbers)
* **Файл:** Создать `android-app/app/src/main/java/com/ada/messenger/ui/screens/ContactSecuritySheet.kt`
* **Действие:**
  - Отображение 60 цифр безопасности, разделенных на 12 блоков по 5 цифр:
    ```
    12345 67890 12345 67890 12345 67890
    12345 67890 12345 67890 12345 67890
    ```
  - QR-код для моментальной очной сверки двумя смартфонами.
  - Кнопка-тумблер «Отметить контакт как проверенный» (`markVerified`).
  - Статус подписи карточки (Карточка v4 с доверенной привязкой `ik_sig` vs TOFU).

### 3.3. Баннер предупреждения об изменении ключа в чате
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt)
* **Действие:**
  - Интегрировать предупреждающую плашку цвета `errorContainer`:
    *«Внимание: открытый ключ шифрования собеседника изменился. Это может означать переустановку приложения либо попытку перехвата трафика. [Сверить ключи] [Принять] [Отклонить]»*.

### 3.4. Визуальные маркеры проверенных собеседников
* **Файлы:**
  - [`android-app/app/src/main/java/com/ada/messenger/ui/screens/MainScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/MainScreen.kt)
  - [`android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt)
* **Действие:**
  - Отображать зеленый бейдж-щит (`Icons.Filled.VerifiedUser` или аналогичный) рядом с именем контакта в списке диалогов и в заголовке чата для подтвержденных контактов.

---

## Фаза 4. P2 — Сетевой статус и адаптивность (ConnectionStatusBar UX)

Фокус: освобождение полезного пространства экрана и понятный язык статусов.

### 4.1. Автоматическое сворачивание плашки при идеальном соединении
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/components/ConnectionStatusBar.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/components/ConnectionStatusBar.kt)
* **Действие:**
  - При переходе в `ConnectionLevel.GREEN` запускать таймер на 3.5 секунды, после чего анимированно сворачивать плашку (`AnimatedVisibility` с `shrinkVertically() + fadeOut()`).
  - В шапке `TopAppBar` отображать компактный светодиодный индикатор (аккуратная зеленая точка 6dp), сигнализирующий о статусе сети без занятия отдельной строки.
  - При переходе в `YELLOW`, `ORANGE`, `RED` плашка немедленно раскрывается с подробным описанием ситуации.

### 4.2. Человекопонятные описания сетевых маршрутов
* **Файл:** [`android-app/app/src/main/res/values/strings.xml`](file:///d:/QUADA/android-app/app/src/main/res/values/strings.xml) и переводы
* **Действие:**
  - Обновить формулировки строк:
    - `conn_route_iroh_live`: «Прямое P2P-соединение»
    - `conn_route_bridge`: «Защищенный мост обхода»
    - `conn_route_mailbox`: «Почтовый ящик (собеседник офлайн)»
    - `conn_route_local_mesh`: «Офлайн-сеть Mesh (Bluetooth/Wi-Fi)»
    - `conn_route_offline_queue`: «В локальной очереди на отправку»

---

## Фаза 5. P2 — UX входящих контактов и списков чатов

Фокус: устранение «страшных» криптографических строк для обычных пользователей.

### 5.1. Плашка неизвестного контакта в `ChatScreen.kt`
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt)
* **Действие:**
  - Если собеседник еще не сохранен в книге контактов (имя совпадает с усеченным Base64), показывать верхнюю панель:
    *«Собеседник отсутствует в контактах. [Задать имя] [Заблокировать]»*.
  - По нажатию «Задать имя» открывать диалог переименования (`setContactAlias`).

### 5.2. Генерация цветных визуальных аватаров для безымянных пиров
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/components/AvatarView.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/components/AvatarView.kt)
* **Действие:**
  - Для несохраненных контактов генерировать детерминированный градиент фона и уникальный двухбуквенный псевдоним (по байтам Peer ID) вместо серого силуэта со знаком вопроса.

---

## Фаза 6. P3 — Групповые P2P-видеозвонки (`CallScreen.kt`)

Фокус: масштабируемость отображения при нескольких собеседниках.

### 6.1. Адаптивная сетка видеозвонка (2–4 участника)
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/CallScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/CallScreen.kt#L445-L520)
* **Действие:**
  - Реализовать сетку: при 2 участниках — полноэкранное видео + PiP своего окна; при 3–4 участниках — сетка 2×2 с равными долями экрана.
  - Подсветка говорящего участника зеленой рамкой (по событию VAD / уровню звука WebRTC).

### 6.2. Пояснение ограничений P2P в диалоге добавления участников
* **Файл:** [`android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt`](file:///d:/QUADA/android-app/app/src/main/java/com/ada/messenger/ui/screens/ChatScreen.kt#L2420-L2435)
* **Действие:**
  - В `AddMemberDialog` добавить поясняющий тултип: *«Группы работают полностью децентрализованно без серверов (P2P Mesh). Лимит 16 участников гарантирует стабильность связи и экономит заряд батареи»*.

---

## Фаза 7. P3 — Паритет функционала Desktop-клиента (`desktopApp`)

Фокус: использование преимуществ больших мониторов и клавиатуры.

### 7.1. Трехпанельный режим на мониторах шириной от 1280dp
* **Файл:** [`android-app/desktopApp/src/commonMain/kotlin/com/ada/messenger/desktop/ui/screens/DesktopWorkspaceScreen.kt`](file:///d:/QUADA/android-app/desktopApp/src/commonMain/kotlin/com/ada/messenger/desktop/ui/screens/DesktopWorkspaceScreen.kt#L175-L265)
* **Действие:**
  - При ширине окна `>= 1280.dp` отображать 3 панели:
    1. Навигационный рейл + список чатов с поиском.
    2. Область активной переписки.
    3. Боковая панель с деталями: открытый ключ, числа безопасности (Safety Numbers), общие медиафайлы и список участников группы.

### 7.2. Создание групп в десктопной версии
* **Файл:** [`android-app/desktopApp/src/commonMain/kotlin/com/ada/messenger/desktop/ui/screens/DesktopWorkspaceScreen.kt`](file:///d:/QUADA/android-app/desktopApp/src/commonMain/kotlin/com/ada/messenger/desktop/ui/screens/DesktopWorkspaceScreen.kt)
* **Действие:**
  - Добавить диалог «Создать группу» в меню действий на Desktop с генерацией топика через `ADACore::create_group`.

### 7.3. Поддержка глобальных горячих клавиш
* **Действие:**
  - `Ctrl + K` / `Cmd + K`: фокус на строку поиска чатов.
  - `Esc`: закрыть контекстное меню / очистить поле ввода / отменить ответ на сообщение.
  - `Ctrl + Shift + A`: открыть диалог выбора файла для отправки.

---

## Фаза 8. Тестирование, валидация и регрессионный контроль

- [ ] **8.1. Unit-тесты компонентов Android:**
  - Расширить [`ConnectionStatusBarTest.kt`](file:///d:/QUADA/android-app/app/src/test/java/com/ada/messenger/ui/components/ConnectionStatusBarTest.kt) тестами на автосворачивание и форматирование новых строк.
  - Добавить тест разбора Safety Numbers и события `ContactKeyChanged` в [`AdaModelsTest.kt`](file:///d:/QUADA/android-app/app/src/test/java/com/ada/messenger/core/AdaModelsTest.kt).
- [ ] **8.2. Инструментальные UI-тесты Compose:**
  - Дополнить [`MainActivityNavigationTest.kt`](file:///d:/QUADA/android-app/app/src/androidTest/java/com/ada/messenger/MainActivityNavigationTest.kt) тестами на шаг возврата при подтверждении паттерна (`RegStep.CONFIRM` -> `RegStep.DRAW`).
- [ ] **8.3. Кросс-платформенная проверка сборки:**
  - Полный прогон `cargo test` с фичей `mobile-dev`.
  - Сборка Android Debug и Signed Release APK.
  - Сборка и запуск десктопного модуля (`desktopApp:run`).

---

## Оценка трудозатрат и очередность выполнения

| Этап | Состав работ | Трудоемкость | Зависимости |
| :--- | :--- | :---: | :--- |
| **Этап 1 (P0)** | Исправление багов в `ChatScreen.kt` (дублирование Reply, отредактированный текст, медиа в группах) и нормализация порогов свайпа в `NewChatInputBar.kt` | 1–2 дня | Нет |
| **Этап 2 (P1)** | UX возврата при онбординге (`PatternRegistrationScreen.kt`) и тактильная сетка 8×8 | 1 день | Нет |
| **Этап 3 (P1)** | Связка ядра с UI: Contact Safety Numbers (сверка ключей, QR, алерт `ContactKeyChanged`) | 2–3 дня | Этап 1 |
| **Этап 4 (P2)** | Автоскрытие и понятные формулировки в `ConnectionStatusBar.kt`, плашка неизвестного контакта | 1 день | Этап 1 |
| **Этап 5 (P3)** | Адаптивная сетка звонков и развитие интерфейса Desktop Workspace (3 панели, горячие клавиши) | 2–3 дня | Этапы 1–4 |
