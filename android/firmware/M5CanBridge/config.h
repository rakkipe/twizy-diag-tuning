#pragma once
// ─── M5CanBridge v1.0 — WiFi <-> CAN brug voor OpenRLink ───────────────────
// M5StickC Plus2 + M5 CAN Unit (SKU:U085, CA-IS3050G) — native TWAI driver.

#define FW_NAME        "M5CanBridge"
#define FW_VERSION     "1.0"

// CAN (Twizy OEM: 500 kbps, 11-bit)
#define CAN_TX_PIN     GPIO_NUM_32     // GROVE geel
#define CAN_RX_PIN     GPIO_NUM_33     // GROVE wit

// WiFi Access Point
#define AP_SSID        "TwizyBridge"
#define AP_PASS        "twizy2012"     // min. 8 tekens
#define TCP_PORT       35000           // conventie WiFi-ELM327

// ELM-emulatie defaults
#define DEFAULT_TX_ID  0x7DF           // OBD functional request
#define DEFAULT_RX_ID  0x7E8           // typische ECU-respons
#define ISOTP_FC_TIMEOUT_MS  1000      // wachten op Flow Control
#define ISOTP_CF_TIMEOUT_MS  1000      // wachten op Consecutive Frames
#define RX_COLLECT_MS  300             // verzamelvenster losse responsen (CAF0)

// SEVCON CANopen referentie (voor de statusregel op het TFT)
#define SEVCON_NODE    0x01
#define SDO_TX_ID      (0x600 + SEVCON_NODE)   // 0x601
#define SDO_RX_ID      (0x580 + SEVCON_NODE)   // 0x581
