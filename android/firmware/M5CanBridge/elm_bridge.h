#pragma once
#include <Arduino.h>
#include "driver/twai.h"

// ─── ELM327-emulator over TWAI ──────────────────────────────────────────────
// Implementeert de subset die OpenRLink gebruikt:
//   ATZ ATI ATE ATL ATS ATH ATSP ATCAF ATSH ATCRA ATAR ATFCSH ATFCSD ATFCSM ATMA
//   + hex-PDU's:  CAF1 → ISO-TP (SF/FF/CF/FC),  CAF0 → raw 8-byte frames.
// Antwoorden eindigen met "\r\r>" zoals een echte ELM.

void   bridge_init();                          // TWAI starten
String bridge_handle(const String& line);      // 1 commandoregel -> respons (zonder prompt)
bool   bridge_monitor_active();                // ATMA-modus?
String bridge_monitor_poll();                  // volgende frames in ATMA-modus ("" = niets)
void   bridge_monitor_stop();
void   bridge_get_stats(uint32_t &tx, uint32_t &rx, uint32_t &err);
