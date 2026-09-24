# Boîte à outils diagnostic & tuning Twizy

![License: MIT](https://img.shields.io/badge/license-MIT-green)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20Android-blue)
![Vehicle](https://img.shields.io/badge/vehicle-Renault%20Twizy%2080-orange)
![Interface](https://img.shields.io/badge/interface-vLinker%20%2F%20ELM327-lightgrey)
[![Download APK](https://img.shields.io/badge/download-latest%20Android%20APK-brightgreen)](https://github.com/rakkipe/twizy-diag-tuning/releases/latest)

Diagnostic et tuning open-source pour la **Renault Twizy 80 (SEVCON Gen4)**, via un
adaptateur USB-CAN vLinker / ELM327. Deux applications partageant la même logique vérifiée :

- **Twizy Pitservice PRO** — un outil d'atelier Windows (Python/Tkinter)
- **OpenRLink** — une application Android (Kotlin/Compose) pour smartphone + adaptateur OTG

**Langues :** [🇬🇧 English](README.md) · [🇳🇱 Nederlands](README.nl.md) · [🇩🇪 Deutsch](README.de.md) · [🇫🇷 Français](README.fr.md)

---

## ⚠️ Avertissement — à lire en premier

Ce logiciel écrit des paramètres de tuning (vitesse, couple, courant, récupération)
directement dans le contrôleur moteur. Les profils agressifs poussent la transmission
**au-delà de ses limites de conception** et peuvent endommager le moteur, la boîte,
l'embrayage ou la batterie, et réduire fortement l'autonomie.

- Utilisation entièrement **à vos propres risques**. Les auteurs déclinent **toute
  responsabilité** en cas de dommage, blessure, perte de garantie ou conséquences
  légales/assurance.
- Un tuning augmentant la vitesse ou la puissance peut rendre le véhicule **non homologué
  pour la route** et annuler l'assurance. Destiné à un usage **sur circuit fermé / banc**.
- N'écrire qu'avec le véhicule **à l'arrêt et en N (pas GO)**, contact mis.
- Ce n'est **pas** un outil officiel Renault et sans lien avec Renault ou SEVCON.

Si vous n'acceptez pas cela, n'utilisez pas le logiciel.

---

## Captures d'écran

**Twizy Pitservice PRO (Windows)** — onglet télémétrie en direct :

![Twizy Pitservice PRO](docs/screenshots/pc-pitservice-pro.png)

**OpenRLink (Android)** — onglet profils de tuning :

<img src="docs/screenshots/android-openrlink.png" width="320" alt="OpenRLink Android">

## Ce que c'est

Les deux applications dialoguent avec le **SEVCON Gen4** via **CANopen SDO** (tuning +
télémétrie en direct) et avec les autres calculateurs via **UDS/KWP sur ISO-TP** (lecture
des codes défaut). Les tables de registres et les calculs de tuning sont un portage fidèle
du travail open-source
[OVMS](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) /
[dexterbg Twizy-Cfg](https://github.com/dexterbg/Twizy-Cfg) — vérifié, non inventé.

## Structure du dépôt

```
android/         OpenRLink — application Android (Kotlin/Compose)
pc-tuning-gui/   Twizy Pitservice PRO — application Python Windows
windows/         ancien client Python expérimental (référence)
docs/            notes
```

## Fonctionnalités

- **Profils de tuning** : STOCK, ECO, VILLE/STAD, AUTOROUTE/SNELWEG, RACE-LITE (et 110 Nm / RACE sur PC)
- **Mesures en direct** (OVMS 0x4600/0x4602) : tension batterie/condensateur, courant/tension moteur, puissance, couple, fréquence de sortie
- **Diagnostic** : état SEVCON + défauts actifs (CANopen), scan DTC multi-ECU (UDS/KWP)
- **Texte DTC optionnel** : traduit les codes défaut avec *votre propre* base DDT4All (voir ci-dessous)
- Extras PC : sauvegarde-avant-écriture, export d'ordre de travail, lanceur DDT4All

## Prérequis

- Renault Twizy 80 avec contrôleur **SEVCON Gen4**
- Adaptateur USB-CAN **vLinker FS / ELM327** (STN recommandé), 500 kbps
- App PC : Windows + Python 3.8+ et `pyserial`
- App Android : Android 8+ (minSdk 26), câble USB-OTG

## Démarrage rapide — PC (Twizy Pitservice PRO)

```
cd pc-tuning-gui
python -m pip install pyserial
python Twizy_Pitservice_GUI.py      # ou double-cliquez run.bat
```
Choisissez l'adaptateur + le port COM → **Verbinden** (Connecter). Onglets : Live, Diagnose,
ECU-scan, Tuning, Werkbon, DDT4All, Recovery.

## Démarrage rapide — Android (OpenRLink)

Installez l'APK signé (compilez-le vous-même avec Android Studio / Gradle, ou utilisez une
APK release). Branchez le vLinker avec un **câble OTG** → **Verbinden → Verbind USB**.
Onglets : Twizy, Tuning, Live, ECU, Terminal.

> La compilation requiert JDK 17 + Android SDK. Une build release nécessite votre propre
> keystore de signature (à ne jamais committer — il est dans `.gitignore`).

## Base ECU (ecu.zip) — optionnelle, fournie par vous

Les applications fonctionnent **sans** base — le scan ECU affiche alors les codes DTC bruts.
Pour voir aussi les **descriptions en clair**, les apps lisent une `ecu.zip` au format DDT4All
que **vous fournissez depuis votre propre installation DDT4All**.

> ⚖️ La base ECU Renault (DDT2000) est **propriétaire**. Elle n'est **pas** incluse dans ce
> dépôt et **aucun** téléchargement n'est proposé ici. Procurez-la uniquement par des voies
> légitimes et pour un usage personnel. `ecu.zip` est volontairement dans `.gitignore`.

**Où la placer**
- **PC** : placez `ecu.zip` là où l'app la cherche (par défaut `D:\downloads\ecu.zip`, ou à
  côté de `Twizy_Pitservice_GUI.py`). L'onglet ECU-scan indique si elle a été trouvée.
- **Android** : onglet ECU → **Kies ecu.zip** (sélecteur de fichiers système) — l'app la
  copie dans son propre stockage et la lit là.

L'app *lit* seulement votre fichier à l'exécution ; elle n'intègre ni ne redistribue jamais la base.

## Sécurité — résumé

À l'arrêt, en N, contact mis. Faites d'abord une sauvegarde (PC). Commencez doucement
(ECO/VILLE), montez progressivement. Surveillez les mesures Live — si la tension chute ou la
température monte, le SEVCON réduit de lui-même : c'est une protection, pas un défaut. STOCK
restaure les valeurs d'usine.

## Remerciements

- [OVMS](https://github.com/openvehicles/Open-Vehicle-Monitoring-System-3) et
  [dexterbg Twizy-Cfg](https://github.com/dexterbg/Twizy-Cfg) — tables de registres SEVCON & maths de tuning
- [DDT4All](https://github.com/cedricp/ddt4all) — format de base ECU (lu à l'exécution ; non inclus)

## Licence

MIT — voir `LICENSE`. Fourni « tel quel », sans aucune garantie. La licence MIT couvre
uniquement le code propre de ce projet, pas une base tierce que vous utiliseriez avec lui.

