# OpenRLink 1.6.0-preview — brede ECU-scan

Deze versie breidt de ECU-tab uit van enkele batterijmodules naar de bekende Zoe- en Leaf-moduleadressen. Ze kan de volledige opgenomen lijst scannen of één module uitlezen. Een voortgangsmelding en stopknop maken lange scans zichtbaar en onderbreekbaar. Het ECU-rapport bevat de moduleherkomst, foutstatus, identificatie en ruwe antwoorden.

## Modulelijsten

| Voertuig | Zonder database | Met Peters huidige ecu.zip |
| --- | ---: | ---: |
| Zoe fase 1 (22/41 kWh) | 15 | 21 |
| Zoe ZE50 fase 2 | 27 | 29 |
| Leaf ZE0/AZE0 (24/30 kWh) | 16 | 16 |
| Leaf ZE1 (40/62 kWh) | 15 | 15 |

De Zoe-basislijst omvat onder meer EVC, LBC/LBC2, lader, omvormer, ABS, airbag, rempedaal, stuurinrichting, instrumenten, UCH/BCM, klimaat, USM, parkeerhulp en telematica. De ZE50-lijst omvat ook de elektrische parkeerrem, rembekrachtiger, gateway, frontcamera, radar, handsfree, navigatie, multimedia, laadcommunicatie, voetgangersgeluid en telefoonlader.

De Leaf-lijst omvat VCM, LBC, omvormer, ABS, elektronische rem, stuurinrichting, airbag, BCM, instrumenten, klimaat, VSP, telematica, multimedia, IPDM en lader. Het SHIFT-adres is alleen bij de oudere groep opgenomen, voor de vroege ZE0. De gepubliceerde AVM-aanvraag zonder bevestigd antwoordadres wordt niet met een gegokt antwoordadres bevraagd.

Dit is **geen garantie dat alle modules van iedere uitvoering fysiek bereikbaar zijn**. Uitrusting en ECU-versies verschillen; niet elke module zit op dezelfde CAN-bus. De app schakelt geen fysieke bedrading om en omzeilt geen gatewaybeveiliging. Modules zonder passend antwoord worden als afwezig, in slaap of niet bereikbaar gemeld, nooit als foutloos.

## Eigen ecu.zip

De app leest de eigen DDT4All-JSON-database lokaal. Voor Zoe fase 1 gebruikt zij exact project `x10`; voor fase 2 exact `x10ph2`, zonder onderscheid tussen hoofdletters. De metadata komen uit `db.json`, de fysieke CAN-adressen uit de bijbehorende ECU-definitie. Varianten met hetzelfde aanvraag-/antwoordadres worden als één module gegroepeerd. Busbeschrijvingen, adapterdefinities, virtuele adressen, broadcastadressen en onbekende protocollen worden niet als module bevraagd.

Een database is beschikbaar via de bestandskiezer of automatisch uit de eigen externe app-map:

`Android/data/be.technop.openrlink/files/ecu.zip`

Peters bestand is persoonlijk op de telefoon geplaatst en met SHA-256 tegen het lokale bestand gecontroleerd. De database zit niet in de APK, Git of een release. Deze database bevat geen Leaf-projecten; Leaf gebruikt de gepubliceerde modulelijst en krijgt geen Renault-foutomschrijvingen.

Import valideert een nieuwe kopie voordat de bestaande interne database atomair vervangen wordt. Een kapotte import laat het oude bestand behouden. **Verversen** vergeet de cache en leest de database opnieuw; een gewone terugkeer naar de tab hergebruikt de catalogus.

## Wat wordt uitgelezen?

- UDS `19 02` met de modulegebonden statusmaskers. Alleen na een expliciete afwijzing van het masker worden maximaal drie alternatieven uit de eigen database geprobeerd.
- Alleen na een expliciete afwijzing van de UDS-dienst/subfunctie wordt de read-only KWP-aanvraag `17 FF 00` geprobeerd. Een leeg, afgekapt of ontbrekend antwoord is nooit een geslaagde foutcodelijst. KWP-statusbits worden niet als UDS-statusbits uitgelegd.
- Met **Identificatie en software meelezen**: onderdeelnummer via `F187`; bij Zoe met database de vier identificatievelden `F1A0/F18A/F194/F195`, anders software `F188` en serienummer `F18C`, voor zover de ECU ze ondersteunt.
- Database-foutomschrijvingen vereisen een volledige, exacte match op diagnoseversie, leverancier, software en versie. Bij meerdere passende definities moet dezelfde omschrijving in alle definities staan. Er wordt niet tussen voertuigen of modules gezocht en geen byte-swap geraden.
- Sommige Zoe-modules vereisen een diagnosesessie. Alleen de bekende leessessies `10 C0` en `10 03` zijn toegelaten: bij de fase-1-lader zoals gepubliceerd, of na een expliciete sessie-afwijzing van de foutcodedienst. Daarna wordt de standaardsessie hersteld, ook bij stoppen. Een programmeersessie wordt geweigerd.
- Geen foutwissen, codering, tuning, security access of actuatortest in Zoe-/Leaf-modus.

De stopknop stopt op een antwoordgrens. Bij adaptertimeout of een niet herstelde diagnosesessie wordt de scan afgebroken en blijven de reeds voltooide module-antwoorden zichtbaar. Verbreek en verbind in dat geval opnieuw.

## Bronnen

- [CanZE Zoe-moduleadressen en sessie-/DTC-metadata](https://github.com/fesch/CanZE/blob/master/app/src/main/assets/ZOE/_Ecus.csv).
- [CanZE Zoe fase-2-moduleadressen](https://github.com/fesch/CanZE/blob/master/app/src/main/assets/ZOE_Ph2/_Ecus.csv).
- [Dala's Leaf-moduleadressen en verschillen tussen generaties](https://github.com/dalathegreat/leaf_can_bus_messages#what-about-active-can-polling).
- [DDT4All referentie voor diagnostische antwoorden](https://github.com/cedricp/ddt4all/blob/master/src/ddt4all/ui/parameters/param_widget.py), waaronder de KWP `17 FF 00`/`57`-indeling.

Alleen protocolfeiten zijn als eigen Kotlin-code verwerkt; de externe veldendatabases zijn niet gebundeld. De bestaande OVMS-vermelding blijft bij de batterijfuncties van toepassing.

## Testen

Unit-tests controleren de modulelijsten, correcte generatie/projectselectie, groepering van databasevarianten, adresvalidatie, ontbrekende/afgekapt ontvangen antwoorden, zelfteststatus, KWP-fallback, toegelaten sessies en herstel daarvan, maskerfallback, adaptertimeouts, exacte ECU-identificatiematches, conflicterende omschrijvingen en behoud bij een mislukte import. De eigen database is daarnaast lokaal als invoer getest; de inhoud staat niet in de testfixtures.

`android/scripts/smoke_ecu.py` controleert de vier scans en de stopknop op een expliciet gekozen, root-capabele emulator; een echte telefoon wordt door dat script geweigerd. Tests op echte Zoe-/Leaf-modules staan nog open.
