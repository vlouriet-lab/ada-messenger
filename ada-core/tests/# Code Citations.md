# Code Citations

## License: unknown
https://github.com/vipulyaara/Ibis/blob/c79cecfc155d63d2734cfbb85dec335e5936084b/ui-common/src/main/java/com/kafka/ui_common/theme/Type.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
//
```


## License: unknown
https://github.com/vipulyaara/Ibis/blob/c79cecfc155d63d2734cfbb85dec335e5936084b/ui-common/src/main/java/com/kafka/ui_common/theme/Type.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
//
```


## License: unknown
https://github.com/hulkdx/findprofessional-frontend-mobile/blob/6761e4c57b3be613ee21c34a1d0a839bf09f778a/android/core/src/main/kotlin/com/hulkdx/findprofessional/core/theme/Typography.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
// Вместо:
Text(text = slide.emoji, fontSize = 72.sp)

// Сделать:
Icon(
    painter = painterResource(slide.iconRes),
    contentDescription = null,
    modifier = Modifier
        .size(96.dp)
        .background
```


## License: unknown
https://github.com/vipulyaara/Ibis/blob/c79cecfc155d63d2734cfbb85dec335e5936084b/ui-common/src/main/java/com/kafka/ui_common/theme/Type.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
//
```


## License: unknown
https://github.com/hulkdx/findprofessional-frontend-mobile/blob/6761e4c57b3be613ee21c34a1d0a839bf09f778a/android/core/src/main/kotlin/com/hulkdx/findprofessional/core/theme/Typography.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
// Вместо:
Text(text = slide.emoji, fontSize = 72.sp)

// Сделать:
Icon(
    painter = painterResource(slide.iconRes),
    contentDescription = null,
    modifier = Modifier
        .size(96.dp)
        .background
```


## License: unknown
https://github.com/vipulyaara/Ibis/blob/c79cecfc155d63d2734cfbb85dec335e5936084b/ui-common/src/main/java/com/kafka/ui_common/theme/Type.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
//
```


## License: unknown
https://github.com/hulkdx/findprofessional-frontend-mobile/blob/6761e4c57b3be613ee21c34a1d0a839bf09f778a/android/core/src/main/kotlin/com/hulkdx/findprofessional/core/theme/Typography.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
// Вместо:
Text(text = slide.emoji, fontSize = 72.sp)

// Сделать:
Icon(
    painter = painterResource(slide.iconRes),
    contentDescription = null,
    modifier = Modifier
        .size(96.dp)
        .background
```


## License: unknown
https://github.com/vipulyaara/Ibis/blob/c79cecfc155d63d2734cfbb85dec335e5936084b/ui-common/src/main/java/com/kafka/ui_common/theme/Type.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
//
```


## License: unknown
https://github.com/hulkdx/findprofessional-frontend-mobile/blob/6761e4c57b3be613ee21c34a1d0a839bf09f778a/android/core/src/main/kotlin/com/hulkdx/findprofessional/core/theme/Typography.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
// Вместо:
Text(text = slide.emoji, fontSize = 72.sp)

// Сделать:
Icon(
    painter = painterResource(slide.iconRes),
    contentDescription = null,
    modifier = Modifier
        .size(96.dp)
        .background
```


## License: unknown
https://github.com/hulkdx/findprofessional-frontend-mobile/blob/6761e4c57b3be613ee21c34a1d0a839bf09f778a/android/core/src/main/kotlin/com/hulkdx/findprofessional/core/theme/Typography.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
// Вместо:
Text(text = slide.emoji, fontSize = 72.sp)

// Сделать:
Icon(
    painter = painterResource(slide.iconRes),
    contentDescription = null,
    modifier = Modifier
        .size(96.dp)
        .background
```


## License: unknown
https://github.com/vipulyaara/Ibis/blob/c79cecfc155d63d2734cfbb85dec335e5936084b/ui-common/src/main/java/com/kafka/ui_common/theme/Type.kt

```
Теперь у меня полная картина. Вот конкретный, поэтапный план с указанием файлов.

---

## Диагноз текущего состояния

| Проблема | Где |
|---|---|
| Emoji вместо иллюстраций в онбординге | `OnboardingScreen.kt` |
| Системный шрифт без кастомизации | `Theme.kt` |
| Аватары — просто кружки с буквой | `PeerAvatar / OwnAvatar` |
| Чат-пузыри без хвостиков и теней | `ChatScreen.kt` |
| TopAppBar — пустой и плоский | `MainScreen.kt` |
| Нет межэкранных переходов | `MainActivity.kt` |

---

## Фазы улучшения

### Фаза 1 — Шрифт (1 день, высокий impact)

Добавить **Inter** через Google Fonts API в `libs.versions.toml` + `build.gradle.kts`, прописать в `Theme.kt`. Это одно изменение поднимает восприятие качества на уровень.

```kotlin
// Theme.kt — заменить AdaTypography
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)
```

---

### Фаза 2 — Цветовая система (0.5 дня)

Текущий фиолетовый `#9C84FF` — слишком насыщен, выглядит дёшево.  
Заменить на более «дорогую» пару — холодный индиго + тёплый акцент:

```
primary        #7B6FE8   (приглушённый индиго)
secondary      #5AC8C4   (телеграм-бирюза)
background     #0A0A14   (глубже, не серый)
surface        #13132A   (заметный контраст с фоном)
surfaceVariant #1E1E3A   
```

---

### Фаза 3 — Онбординг (1 день)

Убрать emoji. Вместо них — `Canvas`-нарисованные анимированные иллюстрации или SVG-векторы из [Material Symbols](https://fonts.google.com/icons) с `size=96dp` + `tint=primary`.

```kotlin
// Вместо:
Text(text = slide.emoji, fontSize = 72.sp)

// Сделать:
Icon(
    painter = painterResource(slide.iconRes),
    contentDescription = null,
    modifier = Modifier
        .size(96.dp)
        .background
```

