# BLE Profile — DJI Osmo 360 (из официального DJI demo, верификация на устройстве — STEP 3–4)

Источник истины: `reference/Osmo-GPS-Controller-Demo/ble/ble.c`, строки 79–97
(`REMOTE_TARGET_SERVICE_UUID` / `REMOTE_NOTIFY_CHAR_UUID` / `REMOTE_WRITE_CHAR_UUID`).
UUID **не придумываются** — только из официального репозитория + замеры с реального эфира.

## GATT-профиль по данным official demo

| Роль | UUID16 | 128-bit форма | Свойства (по demo) |
|---|---|---|---|
| DJI service | `0xFFF0` | `0000fff0-0000-1000-8000-00805f9b34fb` | primary; фильтр при сканировании |
| Notify char (camera → watch) | `0xFFF4` | `0000fff4-0000-1000-8000-00805f9b34fb` | notification |
| Write char (watch → camera) | `0xFFF5` | `0000fff5-0000-1000-8000-00805f9b34fb` | write/write-without-response — уточнить свойство при discovery |
| CCCD descriptor | `0x2902` | стандартный | запись `0x0100` = enable notifications |

## MTU

В demo явных ограничений не зафиксировано → определить экспериментально.
План: после discovery `requestMtu(185)` → если ок, `247`; зафиксировать максимум,
при котором камера принимает полный кадр R SDK (максимальный кадр handshake ~40 Б,
но status push больше).

## Discovery-план (STEP 3)

* Сканирование: сначала без жёсткого фильтра (собираем все устройства),
  помечаем кандидатов, у которых advertised services содержат `FFF0`,
  имя содержит `Osmo/DJI`, либо есть manufacturer data DJI.
* В диагностическом APK уже есть заготовка (`Diagnostics.BleScanTest`), которая
  печатает name/addr/RSSI/service/mfr — она же станет датасетом для этого файла.

## Проверки при подключении (STEP 4)

- [ ] `discoverServices()` — дамп всех сервисов/характеристик в лог;
- [ ] подтверждение, что `FFF5` реально write(+without response?), `FFF4` notify;
- [ ] включение CCCD `0x0100`, получение эхо/кадров handshake;
- [ ] максимальный рабочий MTU.

## Реализованный GATT flow (ble/BleClient.kt) — ждёт живой проверки

1. `device.connectGatt(..., TRANSPORT_LE)` (autoConnect=false);
2. `onConnectionStateChange(CONNECTED)` → `discoverServices()`;
3. `onServicesDiscovered` → берём сервис `0xFFF0`, характеристики `0xFFF4` (notify)
   и `0xFFF5` (write); дамп отсутствующих сервисов — в лог;
4. `requestMtu(185)` → `onMtuChanged` → фиксируем реальный MTU в логе;
5. `setCharacteristicNotification(0xFFF4, true)` + запись `0x0100` в CCCD `0x2902`;
6. `onDescriptorWrite` → STATE_READY, дальше запускается DJI handshake.

Проверить на часах (STEP 4-чеклист):
- [ ] сервис 0xFFF0 находится;
- [ ] свойства 0xFFF4/0xFFF5 соответствуют ожиданию (notify / write+no-rsp);
- [ ] реальный MTU в логе;
- [ ] RX-пакеты handshake приходят на 0xFFF4 (лог `DJI RX: AA …`).

Замечание по API: на compileSdk 35 `writeDescriptor(d, value)` возвращает Int-статус
(GATT_SUCCESS) — учтено; старый сеттер `descriptor.value` помечен deprecated,
используется только на API < 33.
