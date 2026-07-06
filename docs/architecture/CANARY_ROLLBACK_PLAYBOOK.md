# Canary and Rollback Playbook

Дата: 2026-04-14

## Goal

Безопасно раскатывать anti-censorship stack с bridge/mailbox, не теряя доставку текста при массовой блокировке или деградации bridge fleet.

## Entry Criteria

Перед canary должны быть выполнены:

1. `cargo test --lib`
2. `cargo test --test integration hostile_network_ -- --nocapture`
3. hostile-network CI step зелёный
4. reference bridge `/healthz` и `/ops/status` доступны
5. security review принят для controlled canary

## Canary Stages

1. Stage 0: internal dogfood only.
2. Stage 1: 1% cohort, 24 часа.
3. Stage 2: 5% cohort, 24 часа.
4. Stage 3: 20% cohort, 24 часа.
5. Stage 4: 50% cohort, 24 часа.
6. Stage 5: 100% rollout.

Переход на следующий stage разрешён только если нет критических regressions по текстовой доставке.

## Success Metrics

1. route success rate не ниже 95% на canary cohort.
2. mailbox lag не превышает 60 секунд sustained.
3. max queue utilization не превышает 80% sustained.
4. auth failure burst не выходит за baseline более чем в 2x.

## Rollback Triggers

Немедленный rollback обязателен при любом из условий:

1. route success rate < 80% более 10 минут.
2. mailbox lag > 5 минут.
3. массовое выпадение bridge fleet или `healthz.status = degraded` на 30%+ мостов.
4. рост `failed` route outcomes release-over-release в canary cohort.
5. security signal: unexplained auth failure burst или evidence of replay abuse.

## Rollback Actions

1. Остановить расширение rollout.
2. Вернуть предыдущий stable manifest или bridge fleet config.
3. Если проблема в build/regression, откатить client release до предыдущего canary-safe version.
4. Оставить telemetry sampling и сохранить snapshots `/ops/status` и client bridge status JSON.
5. Открыть incident channel с таймлайном: detection, mitigation, recovery.

## Mass Bridge Block Incident

Если идёт массовая блокировка мостов:

1. Заморозить rollout.
2. Переключиться на emergency manifest rotation.
3. Удалить скомпрометированные bridge profiles из active manifest.
4. Проверить fallback на mailbox/offline queue path.
5. Сохранить evidence:
   - route mix before/after
   - mailbox lag trend
   - bridge saturation trend

## Post-Canary Checklist

1. Сохранить canary summary.
2. Привязать incident IDs или explicit no-incident record.
3. Зафиксировать final route success и mailbox lag baselines.
4. Обновить release notes operational caveats.