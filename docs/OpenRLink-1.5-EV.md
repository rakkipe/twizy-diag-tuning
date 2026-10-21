# OpenRLink 1.5.0-preview — Zoe en Leaf uitlezen

Deze Android-uitbreiding bouwt voort op de lokale OpenRLink 1.4.0 met eigen Twizy-profielen. De Windows-app verandert niet. De APK gebruikt dezelfde applicatie-ID en lokale release-ondertekening als de bestaande Android-app. De bronkopie bevat geen sleutel of ECU-database.

## Gebruik

1. Installeer `OpenRLink-1.5.0-preview.apk` als update; verwijder de bestaande app niet.
2. Kies bij **Verbinden → Voertuig** de juiste uitvoering voordat je verbindt.
3. Verbind de USB-, Bluetooth- of compatibele TCP-adapter. Voor een eerste kennismaking kun je **Demo-modus** gebruiken. De demo toont expliciet fictieve gegevens.
4. **Accu → Accu uitlezen** leest de kernwaarden. **Accu + 96 celspanningen** leest ook de cellen; bij ZE50 zijn dit 96 aparte aanvragen.
5. **Live** ververst de kernwaarden tien seconden na iedere afgeronde uitlezing. Stop Live voor een andere uitleesactie.
6. **ECU** leest de opgenomen modules. **Rapport** toont de ruwe antwoorden en bewaart de laatste batterijscan via de Android-bestandskiezer.
7. Verbreek de verbinding voordat je een ander voertuig kiest. Oude meetwaarden worden dan gewist.

## Opgenomen uitleesprofielen

| Voertuig | Batterijgegevens | Protocol en beperkingen |
| --- | --- | --- |
| Renault Twizy | Bestaande diagnose, live SEVCON en eigen tuningprofielen | Bestaande CANopen/UDS-routes. |
| Renault Zoe fase 1, 22/41 kWh | SOC, SOH, spanning, stroom, kilometerstand, 12 temperaturen, 96 celspanningen | 11-bit CAN, EVC `7E4/7EC`, LBC `79B/7BB`. Waarden kunnen per ECU-versie ontbreken. |
| Renault Zoe ZE50 fase 2 | SOC, SOH, spanning, stroom, gemiddelde temperatuur, beschikbare energie en 96 celspanningen | 29-bit CAN, LBC `18DADBF1/18DAF1DB`. Dit is een ander profiel dan fase 1. |
| Nissan Leaf ZE0/AZE0, 24/30 kWh | Capaciteit in Ah, Hx, temperaturen, 96 celspanningen en spanning via celgroep | EV-CAN LBC `79B/7BB`. Alleen de bekende 39/41-byte batterijindelingen; geen aangenomen SOC of berekende SOH. |
| Nissan Leaf ZE1, 40/62 kWh | SOC, capaciteit in Ah, Hx, SOH via groep 61, temperaturen, 96 celspanningen en spanning via celgroep | EV-CAN LBC `79B/7BB`, bekende 51-byte batterijindeling en 329-byte SOH-groep. Andere layouts worden niet gedecodeerd. |

Dit zijn softwareprofielen, **geen fysieke compatibiliteitscertificering**. De nieuwe uitleesroutes zijn nog niet op echte Zoes of Leafs gevalideerd. Nieuwere Leaf-generaties, aangepaste accupakketten en andere ECU-layouts zijn niet hiermee automatisch ondersteund. Contact en CAN-toegang moeten beschikbaar zijn; een gateway of andere busrouting kan een antwoord verhinderen. De app voert geen automatische busomschakeling of wake-up uit.

## Diagnose en grenzen

- Zoe fase 1: DTC-aanvraag bij LBC, EVC, BCB en PEB. ZE50: LBC, EVC, omvormer en BCM. Leaf: alleen LBC op de gekozen bus. Dit is **geen volledige scan van alle voertuigmodules** en de DTC-dienst kan ontbreken.
- De nieuwe profielen sturen alleen service `21`/`22` voor batterijgegevens en `19 02 FF` voor ECU-foutcodes. Er worden geen diagnose- of programmeersessies geopend op Zoe/Leaf.
- Tuning, foutwissen en vrije terminalcommando's zijn in Zoe/Leaf-modus ook in de uitvoerende code geblokkeerd. Twizy-functionaliteit blijft beschikbaar in Twizy-modus.
- Ontbrekende, verkeerde en afgekapt ontvangen antwoorden leveren geen meetwaarden of melding "geen DTC's" op. Een adaptertimeout breekt de batterij-uitlezing af; verbind dan opnieuw.
- Het celverschil wordt alleen berekend bij 96 geldige celspanningen uit één scan. Een gedeeltelijke cellijst blijft herkenbaar gedeeltelijk. Drie opeenvolgende ontbrekende ZE50-cellen stoppen de lange celronde.
- Hx is een aparte Nissan-waarde, geen SOH. Temperatuur uit Leaf-thermistoren is een benadering. Oudere Leafs krijgen geen SOH op basis van een willekeurige nieuwcapaciteit.
- Foutomschrijvingen uit de bestaande Twizy-`ecu.zip` worden nooit op Zoe/Leaf-codes toegepast. De EV-profielen werken zonder `ecu.zip` en tonen ruwe foutcodes per ECU.
- De algemene OBD-tab gebruikt CAN-emissiediensten 03/07/0A en mode-01-PID's. Elektrische voertuigen hoeven deze niet te ondersteunen. Iedere DTC-dienst krijgt een eigen status; een niet-ondersteunde dienst verbergt de codes van een beantwoorde dienst niet.
- Na een gerichte ECU-/CANopen-uitlezing worden voor algemene OBD de framing, filters, flow-control en broadcast-header opnieuw ingesteld.

## Onderbouwing

De protocolconstanten en omrekeningen zijn terug te vinden in primaire broncode:

- [OVMS Nissan Leaf-module](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3/tree/master/vehicle/OVMS.V3/components/vehicle_nissanleaf/src): batterijgroepen 01/02/04/61, generaties en lengtes.
- [OVMS Zoe fase 1](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3/tree/master/vehicle/OVMS.V3/components/vehicle_renaultzoe/src): EVC, temperatuur- en celgroepen.
- [OVMS Zoe fase 2](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3/tree/master/vehicle/OVMS.V3/components/vehicle_renaultzoe_ph2/src): 29-bit OBD-adressen, LBC-DID's en de celvolgorde die `9040`, `9060` en `9080` overslaat.
- [CanZE fase-1 EVC-velddefinities](https://github.com/fesch/CanZE/blob/master/app/src/main/assets/ZOE/EVC_Fields.csv): SOC DID `2002` (0,02% per bit), SOH DID `3206` (één byte procent). Alleen protocolfeiten gebruikt; geen CanZE-code of veldendatabase gebundeld.
- [ELM327 datasheet](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf): 11/29-bit protocolkeuze, headers, CAN automatic formatting en flow-control.

Zie ook `android/OVMS-EV-NOTICE.txt` voor de OVMS-licentievermelding.

## Verificatie

`gradle :app:testDebugUnitTest :app:assembleRelease --offline` is uitgevoerd met Gradle 8.9, JDK 17 en Android SDK 34. De 12 nieuwe EV-/protocoltests en 8 bestaande profieltests slagen. De nieuwe tests omvatten compact/spaced CAN, 29-bit headers, lange ISO-TP-antwoorden met telleromloop, verschillende ECU's door elkaar, ontbrekende frames, negatieve antwoorden, verkeerde DID's, onbekende layouts, de gepubliceerde 39-byte Leaf-capture, 96 cellen in alle vier demo's en uitsluitend leescommando's in de batterijroute.

Deze controles bewijzen verwerking en softwaregedrag. Adapters, echte CAN-bussen, daadwerkelijke DTC-ondersteuning en de juiste schaalfactoren voor een specifiek gemonteerd ECU moeten nog fysiek worden gecontroleerd.

De ondertekende release is ook in een Android 11-emulator geïnstalleerd en gecontroleerd: voertuigkeuze en volledige demo-uitlezing (96/96 cellen) voor Zoe fase 1, ZE50, Leaf 24/30 en Leaf 40/62, plus starten/stoppen van ZE50 Live. Dit gebruikte uitsluitend fictieve gegevens. Herhaalbaar met `android/scripts/smoke_ev.py`; het script accepteert uitsluitend een expliciet gekozen emulator. Er is geen echte telefoon of wagen gebruikt.
