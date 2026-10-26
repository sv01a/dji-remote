# DJI R SDK protocol — реализация в этом проекте

Источник истины: официальный `dji-sdk/Osmo-GPS-Controller-Demo`
(локальная копия `reference/Osmo-GPS-Controller-Demo`). Ничего не придумано:
каждое поле сверено с `docs/protocol*.md` и `protocol/*.c` демо.

## Frame (dji_protocol_parser.c)

```
SOF(1)=0xAA | Ver/Length(2, LSB first) | CmdType(1) | ENC(1) | RES(3) | SEQ(2, LE)
  | CRC16(2, LE) | DATA: CmdSet(1) CmdID(1) payload(n) | CRC32(4, LE)
```

* Ver/Length: [15:10] version = 0, [9:0] = полная длина кадра в байтах.
* CRC-16 (custom_crc16.c): reflected poly 0xA001, **init 0x3AA3**, без xor-out, таблица 256.
  Считается от SOF до SEQ включительно (10 байт).
* CRC-32 (custom_crc32.c): reflected poly 0xEDB88320, **init 0x00003AA3**, без xor-out.
  Считается от SOF до DATA включительно.
* Всё little-endian.

Верификация: 10 кадров из `test/connect_cmd_frame.txt` официального демо.
Юнит-тест `FrameTests.rebuild official frame byte-exact` воспроизводит кадр #1
**байт-в-байт** (device_id 0x12345678, MAC 38:34:56:78:9A:BC, fw 0, verify_mode 0,
verify_data 0x1FFE, seq 1 → `AA3300…6C667C`).

## CmdType (logic/enums_logic.h)

| Значение | Смысл |
|---|---|
| 0x00 CMD_NO_RESPONSE | команда, ответ не нужен |
| 0x01 CMD_RESPONSE_OR_NOT | команда, ответ желателен |
| 0x02 CMD_WAIT_RESULT | команда, ответ обязателен |
| 0x20/0x21/0x22 ACK_* | ответные кадры (бит 5 = 1) |

## Реализованные команды

### Connection Request — 0x00/0x19 — СТАТУС: реализовано, ждёт живого теста

Команда (33 Б): `device_id(4) mac_len(1) mac[16] fw_version(4) conidx(1) verify_mode(1) verify_data(2) reserved(4)`.
Мы шлём: device_id = 0xFF66 (Osmo 360), свой MAC (6 Б), fw 0, conidx 0.

Ответ камеры (9 Б): `device_id(4) ret_code(1) reserved(4)`.

Handshake — ровно как `connect_logic_protocol_connect()`:
1. Мы → 0x00/0x19 (verify_mode=0 при повторном подключении; 1 при первой паре).
2. Камера шлёт ACK (ret_code) — либо сразу свой командный кадр.
3. Камера → свой 0x00/0x19 c **verify_mode=2** (verify_data 0 = принято, 1 = отказ).
   ЖИВОЙ ТЕСТ Osmo 360: камера также шлёт verify_mode=0 (без проверки, по истории
   паринга) — принимаем оба варианта; кадр повторяется ~1 раз/с до нашего ACK.
4. Мы → ACK (ret_code=0, reserved[0] = номер камеры = 0) → PROTOCOL_CONNECTED.

### Version Query — 0x00/0x00 — СТАТУС: не реализовано (позже)
Запрос без payload; ответ `ack(2) product_id(16) sdk_version(n)`.

### Recording Control — 0x1D/0x03 — СТАТУС: реализовано (REC/STOP)
`device_id(4) record_ctrl(1) reserved(4)`; 0x00 = start, 0x01 = stop.
Ответ: `ret_code(1)`. Отправляем CMD_RESPONSE_OR_NOT, ждём ACK по SEQ.
Итог подтверждаем также по camera status push (status 0x03/0x05).

### Mode Switch — 0x1D/0x04 — СТАТУС: метод готов, в UI не выведен (правило #20)
`device_id(4) mode(1) reserved(4)`; ответ `ret_code(1) reserved(4)`.

### Camera Status Subscription — 0x1D/0x05 — СТАТУС: реализовано (после handshake)
`push_mode(1) push_freq(1) reserved(4)`; шлём push_mode=3 (периодически + по изменению),
push_freq=20 (2 Гц — единственное допустимое значение). CMD_NO_RESPONSE.

### Camera Status Push — 0x1D/0x02 — СТАТУС: реализовано (38-байтный парсер)
Упакованные смещения `camera_status_push_command_frame` (38 Б суммарно):
mode@0, status@1, resolution@2, fps@3, eis@4, record_time@5..6, fov@7, ratio@8,
countdown@9..10, tl_interval@11..12, tl_duration@13..14, remain_capacity@15..18,
remain_photo@19..22, remain_time@23..26, user_mode@27, power_mode@28, next_flag@29,
temp@30, photo_countdown@31..34, loop_record@35..36, **battery@37**.
Парсер — `DjiLink.parseStatusPush`.

### New Camera Status Push — 0x1D/0x06 — СТАТУС: реализовано (имя/параметры режима)
`0x01 len(1) name[21] 0x02 len(1) param[21]` (ASCII, ≤20 Б).

### Key Reporting — 0x00/0x11 — СТАТУС: реализовано (PHOTO=SNAPSHOT, QS)
`key_code(1) mode(1) key_value(2)`; мы шлём mode=0x01, key_value=0x00 (short press).
key_code: 0x01 Record, 0x02 QS, 0x03 SNAPSHOT. Ответ: `ret_code(1)`.

### GPS Data Push — 0x00/0x17 — СТАТУС: реализовано (gps/GpsPusher.kt)
48-байтный командный кадр: ymd@0..3, (hour+8)hms@4..7, lon/lat(e7)@8..15,
height(mm)@16..19, speed_north/east/down(cm/s float)@20..31, vert/horiz acc(mm)@32..39,
speed acc(cm/s)@40..43, satellites@44..47. Шлём каждую секунду, пока CONNECTED,
CMD_NO_RESPONSE (ACK от камеры не обязателен). Источник координат — обычный
LocationManager (GPS + NETWORK провайдеры), без Google Play Services.

### Camera Power — 0x00/0x1A — СТАТУС: НЕ реализовано (правило #22; только архитектура)

### Wi-Fi Credential Extraction & DUML Subsystem — СТАТУС: реализовано (DumlFrame.kt, CameraWifiManager.kt)
DJI DUML протокол поверх BLE GATT (write `0xFFF5`, notify `0xFFF4`).
Кадр: `SOF(0x55) | Len/Ver(2, LE) | CRC8(1) | Sender(1) | Receiver(1) | SEQ(2, LE) | Flags(1) | CmdSet(1) | CmdId(1) | Payload | CRC16(2, LE)`
- CRC8: reflected poly 0x8C, init 0x77 over bytes 0..2
- CRC16: reflected poly 0x8408, init 0x3692 over frame minus trailing CRC16
- `0x07 / 0x07`: GetWifiSsid -> ответ `[status:1][len:1][SSID]`
- `0x07 / 0x0E`: GetWifiPassword -> ответ `[status:1][len:1][Passphrase]`
- `0x07 / 0x0C`: GetWifiMac -> ответ `[status:1][6B MAC]`
Часы подключаются к хотспоту камеры (192.168.2.1) через Android NetworkSpecifier и биндят сетевой стек процесса (`bindProcessToNetwork`) для приёма видеопотока через UDP Datalink (порт 9004).

## Приоритет источников

Официальное демо > всё остальное (правило #49). Если реальное поведение Osmo 360
отличится — правим по логам живого эфира, но только с опорой на demo-документацию.
