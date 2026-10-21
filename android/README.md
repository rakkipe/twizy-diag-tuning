# OpenRLink voor Android

OpenRLink 1.6.0-preview breidt de bestaande Twizy-app uit met aparte uitleesprofielen voor Renault Zoe fase 1, Zoe ZE50 en Nissan Leaf 24/30 en 40/62 kWh.

Zie [de uitgebreide ECU-scan, databasewerking en teststatus](../docs/OpenRLink-1.6-ECU.md) en [de batterijprofielen](../docs/OpenRLink-1.5-EV.md). De nieuwe profielen zijn offline getest; fysieke validatie op Zoe/Leaf staat nog open.

- Voertuigkeuze bij Verbinden, vóór het verbinden.
- Accugegevens en 96 celspanningen, met herkenbare gedeeltelijke uitlezing.
- Live-kernwaarden, beperkte ECU-foutcodescan en tekstexport met ruwe antwoorden.
- Algemene OBD-II via CAN indien het voertuig die diensten ondersteunt.
- Zoe/Leaf uitsluitend uitlezen; Twizy behoudt de bestaande eigen profielen en tuning.
- USB-OTG, Bluetooth SPP, compatibele ELM TCP-brug en duidelijk gemarkeerde demo.

## Bouwen

JDK 17, Android SDK 34 en Gradle 8.9. Voorbeeld met een geïnstalleerde Gradle:

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug
```

Voor een release: `gradle :app:assembleRelease`. Release-ondertekening gebruikt uitsluitend je lokale `keystore.properties` en keystore; die horen niet in Git.

Applicatie-ID: `be.technop.openrlink`; minimaal Android 8 (API 26). Met dezelfde ondertekening kan versiecode 10 1.4 (code 8) en 1.5 (code 9) bijwerken.

## Architectuur

`transport/`: adapterverbindingen. `elm/`: AT-configuratie en gevalideerde ISO-TP-antwoorden. `ev/`: voertuigprofielen, leesaanvragen, batterijdecoders en rapport. `uds/`: services en NRC's. `obd/`: generieke CAN OBD. `renault/` en `sevcon/`: bestaande Twizy-functies. `ui/`: toestand en schermen.

Protocolbronnen en beperkingen staan in de handleiding. De nieuwe EV-profielen bundelen geen DDT4All-database en hebben geen `ecu.zip` nodig. De bestaande Twizy-omschrijvingendatabase blijft optioneel.
