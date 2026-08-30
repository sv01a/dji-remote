# Watch Compatibility — Huawei Watch 4 Pro

> Цель проекта: APK-приложение устанавливается **напрямую на часы** Huawei Watch 4 Pro
> (HarmonyOS 4.x, среда совместимости с Android). Без телефона, Wear Engine,
> Wear OS API и Google Play Services.

Эта страница заполняется **фактическими** результатами запуска диагностического APK
из STEP 2 («Hello DJI Remote», app id `com.dji360.watch`). Пока шаги «установить и
проверить на устройстве» не выполнены, поля помечены `<TO FILL>` — их запрещено
угадывать (правило #48: сначала проверяем BLE API на часах, потом архитектура).

## Методика снятия данных

1. Собрать и установить: `scripts/install-watch.sh` (или вручную adb — см. ниже).
2. Запустить приложение «OSMO 360 Remote» на часах.
3. Нажать **PERMS** и выдать Bluetooth-разрешения → блок BLUETOOTH заполнится.
4. Включить Bluetooth на камере/тестовых BT-устройствах рядом → нажать **SCAN**
   (8 секунд сканирования, список ближайших устройств с RSSI/name/service/mfr).
5. Проверить кнопки **BUZZ** и **ERROR PATTERN** (вибрация).
6. Значения из блока DEVICE/SCREEN/RAM/BATTERY перенести в таблицу ниже.
7. При желании снять логи: `adb logcat | grep DJIRemote`.

## Таблица совместимости (заполняется на устройстве)

| Параметр | Значение | Как получено |
|---|---|---|
| Build fingerprint | `<TO FILL>` | строка `BUILD:` |
| Android version / SDK_INT compat layer | `<TO FILL>` | строка `ANDROID:` |
| ABI | `<TO FILL>` | строка `ABI:` |
| Screen real px | `<TO FILL>` (ожидание: 466×466 round AMOLED 1.5″) | `SCREEN:` |
| Density dpi / scale | `<TO FILL>` | `DENSITY:` |
| Refresh rate | `<TO FILL>` | `DENSITY:` |
| RAM total / avail | `<TO FILL>` | `RAM:` |
| Battery during test | `<TO FILL>` | `WATCH BATT:` |
| App launches | ☐ да ☐ нет | — |
| Touch works | ☐ счётчик тапов растёт | TOUCH TEST |
| Runtime permission dialog появляется | ☐ да ☐ нет | PERMS |
| Bluetooth adapter accessible/enabled | ☐ enabled | ADAPTER: |
| BLE scanner available | ☐ available | LE SCAN: |
| SCAN видит ≥1 устройство (devices seen=N) | ☐ N=`<FILL>` | SCAN |
| Short vibration ощущается | ☐ да ☐ нет | BUZZ |
| Double-buzz pattern ощущается | ☐ да ☐ нет | ERROR PATTERN |

## Промежуточная проверка в эмуляторе (smoke test, не заменяет часы)

AVD `dji_watch`: 466×466 @440dpi (профиль Watch 4 Pro), образ google_apis API 35, arm64.
Установлен тот же `app-debug.apk`, автогрант разрешений, полный прогон кнопок:

| Проверка | Результат в эмуляторе |
|---|---|
| Launch (cold) | ✔ `Status: ok`, `Displayed +233..652ms` |
| Touch | ✔ счётчик тапов растёт (input tap по координатам кнопок) |
| Permissions | ✔ BT_SCAN/BT_CONNECT GRANTED (runtime dialog path проверен logcat) |
| BT adapter | ✔ `BT MANAGER: present`, `ADAPTER: enabled`, `LE SCAN: available` |
| BLE scan cycle | ✔ 8с цикл завершён корректно (`SCAN DONE, devices seen: 0` — в эмуляторе нет BLE-эфира с камерами) |
| Vibration API | ✔ `short buzz sent (80 ms)` / `double buzz sent (70/120/70 ms)` без исключений |
| Screen reading | ✔ реальные 466×466 полные (reported 466×466 после фикса) |

**Найденная проблема совместимости (важно и для часов):**
`android:screenOrientation="portrait"` на активности приводил к тому, что на
квадратных/круглых дисплеях система рендерила app в compat-колонку 300px
(letterbox), кнопки уходили за экран. Фикс: ориентационный лок убран из манифеста,
UI ужат под круглый экран. На Watch 4 Pro ожидаем тот же эффект — лок не возвращать.

Особенности эмулятора (НЕ относятся к часам): Pixel Launcher в этом AVD падал
в crash-loop на круглой конфигурации (его системный диалог перекрывал экран;
решено `pm uninstall --user 0 com.google.android.apps.nexuslauncher`).

## Известные публичные характеристики цели ⚠️ (не подставлять как факт)

| Свойство | Ожидается (публичные спеки) | Подтверждено на устройстве |
|---|---|---|
| Display | 1.5″ AMOLED, 466×466, круглый | ❓ |
| OS | HarmonyOS 4.x (обновление с 3.1) | ❓ |
| Android-слой | совместимость уровня ~API 29–31 | ❓ — читаем SDK_INT из приложения |
| Bluetooth | 5.x BR/EDR+BLE | ❓ |
| Wi-Fi | 802.11 b/g/n — используется только для debugging | ❓ |

Важно: даже если HarmonyOS-слой рекламирует конкретный Android API, поведение
permissions может отличаться от чистого Android. Решение о наборе прав меняется
только по факту (правило #34 «не добавлять разрешения вслепую»).

## Замечания по разрешениям (текущая стратегия в манифесте)

* `BLUETOOTH_SCAN` c флагом `neverForLocation`, `BLUETOOTH_CONNECT` — для API 31+.
* Легаси `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION` — объявлены с
  `maxSdkVersion="30"` на случай, если compat-слой рапортует SDK ≤ 30.
* Если окажется, что сканирование на устройстве работает только с FINE_LOCATION
  несмотря на SDK ≥ 31 (некоторые стеки игнорируют `neverForLocation`) — это будет
  зафиксировано здесь, после чего разрешение добавится явно.
* Фича `android.hardware.bluetooth_le` объявлена `required=false`: compat-слой
  может не анонсировать feature, хотя BLE физически работает.

## adb — установка

Через Wi-Fi ADB (часто самый надёжный путь для sideload APK на Watch 4 Pro):

```bash
# На часах: Настройки → О системе → тапать «Номер сборки» ×7 → Для разработчиков →
# Беспроводная отладка ON → прочитать IP
adb connect <watch-ip>:5555
adb devices               # статус device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Скрипт `scripts/install-watch.sh` автоматизирует сборку и установку (`--ip`, `--skip-build`).

## История результатов

| Дата | Сборка | Результат | Заметки |
|---|---|---|---|
| — | — | ещё не установлено на часы | ожидает устройства (STEP 1–2 живая проверка) |

После первой установки заполнить таблицу и добавить сюда строку результата +
снять пункты Definition of Done раздела 47 ТЗ, относящиеся к STEP 1–2.
