# Skill-verificatie: m5twizy-sevcon vs. OVMS broncode

Geverifieerd tegen: `openvehicles/Open-Vehicle-Monitoring-System-3` →
`vehicle_renaulttwizy/src/rt_sevcon.cpp` + `rt_sevcon_tuning.cpp` (master).
Dit is dé referentie-implementatie (Dexter Cs / Michael Balzer).

## ✅ KLOPT — veilig te gebruiken

| Onderdeel | Skill | OVMS | Status |
|---|---|---|---|
| CAN-snelheid | 500 kbps | 500 kbps | ✅ |
| SDO TX/RX ID | 0x601 / 0x581 (node 1) | idem (0x600+node / 0x580+node) | ✅ |
| Login reset | Write 0x5000.03 = 0 | Write 0x5000.03 = 0 | ✅ |
| Login wachtwoord | Write 0x5000.02 = 0x4BDF | Write 0x5000.02 = 0x4bdf | ✅ |
| Login verificatie | Read 0x5000.01 == 4 | Read 0x5000.01 == 4 | ✅ |
| Logout | — | Write 0x5000.03=0, 0x5000.02=0 | ✅ (skill mist logout, niet kritiek) |

De login-sequentie — het gevaarlijkste deel — is **100% correct**.

## ⚠️ FOUT of MISLEIDEND — niet blind gebruiken

| Parameter | Skill zegt | OVMS (geverifieerd) | Risico |
|---|---|---|---|
| **Max snelheid vooruit** | 0x2920 **sub 0x01** | 0x2920 **sub 0x05** | sub 0x01 = `m_drive_level` (heel iets anders!). Verkeerde sub = onverwacht gedrag. |
| **Max koppel** | 0x291C sub 0x01/0x02 | Koppel loopt via **0x6076.00** (peak) + **0x4611** (PMAP-curve) | 0x291C komt in OVMS niet voor als schrijfdoel. PMAP is exact wat je v14 corrumpeerde. |
| **Max motorcurrent** | 0x2916 sub 0x01 | **0x4641.02** + **0x6075.00** (met scaling) | 0x2916.01 is in OVMS-commentaar "rated torque", geen schrijfbare stroomlimiet. |
| **Regen** | 0x4600 sub 0x01/0x02 | Neutral-braking via **0x3813** sub 0x33/0x35/0x3b; 0x4600 is read-only monitoring (motorspanning) | 0x4600 schrijven = zinloos/risico. |
| **Acceleratie/decel** | 0x3813 sub 0x01/0x02 | 0x3813 wordt gebruikt voor **braking-rpm-drempels** (sub 0x33/35/3b), niet accel/decel sub 01/02 | sub-indices onjuist. |

## 🔑 Belangrijkste conclusie

De **lezende** acties en de **login** uit de skill zijn betrouwbaar.
De **schrijvende tuning-registers** (snelheid/koppel/stroom/regen) in de skill-tabel
zijn deels **fout of te simpel** voorgesteld. Echt tunen vereist het OVMS-model:
- koppel = `0x6076.00` (peak) **samen met** de PMAP-curve `0x4611.xx`
- snelheid = `0x2920.05` (niet .01)
- stroom = `0x4641.02` + `0x6075.00` mét scaling

→ Voor jouw herstel: gebruik de skill voor **login + lezen/diagnose**.
   Voor **schrijven** volg je het OVMS-tuningmodel, niet de skill-tabel.
   Dit sluit aan bij je eerdere v14-corruptie: 0x4611/0x4610 (PMAP/FMAP) zijn
   precies de gekoppelde maps die fout gingen.
