# PR ActivityWatch Android — Configurable server bind address

## Contexte

Je travaille sur un fork de aw-android (ActivityWatch pour Android) : `~/Projects/aw-android`
- Remote origin : github.com/lucletoffe/aw-android
- Remote upstream : github.com/ActivityWatch/aw-android
- Branche actuelle : feature/configurable-host (un commit quick&dirty : bind 0.0.0.0 en dur)
- Le submodule aw-server-rust est dans ./aw-server-rust/

## Le probleme upstream

Issues #121 et #107 : le serveur AW Android ecoute sur 127.0.0.1 uniquement. Impossible d'y acceder depuis un autre device sur le reseau local. Beaucoup d'utilisateurs veulent pouvoir syncer leurs donnees vers un serveur central ou y acceder depuis leur PC.

## Ce qu'il faut faire

Creer une PR propre, quality open-source, avec :

1. **Reset la branche** : partir d'upstream/master propre, pas du commit 0.0.0.0 en dur

2. **Cote Kotlin (app Android)** :
   - Ajouter un setting "Allow network access" (boolean, default false) dans `mobile/src/main/java/net/activitywatch/android/` (probablement AWPreferences.kt ou equivalent)
   - UI : toggle dans les Settings de l'app avec un avertissement securite quand on active
   - Quand active : passer "0.0.0.0" comme host au serveur. Quand desactive : "127.0.0.1"

3. **Cote Rust (aw-server-rust submodule)** :
   - Dans `aw-server-rust/aw-server/src/android/mod.rs` : accepter le host comme parametre JNI au lieu du hardcode
   - Modifier la signature de `startServer()` JNI pour accepter un parametre host (String)
   - Fallback sur 127.0.0.1 si pas fourni (backward compatible)

4. **Documentation** :
   - Mettre a jour le README avec la nouvelle option
   - Security note : expliquer les risques (exposition reseau local)

5. **Commit history propre** :
   - 1-2 commits max, messages clairs en anglais
   - Pas de traces d'IA dans le code, les commits, ou la PR
   - Style de code coherent avec le reste du projet

6. **PR description** :
   - Ton humain, concis. "I needed this for my setup, figured I'd contribute it properly."
   - Reference issues #121 et #107
   - Screenshot du setting si possible
   - Expliquer le choix : off by default, opt-in, avec warning

## Fichiers cles a lire d'abord

- `mobile/src/main/java/net/activitywatch/android/` — code Kotlin de l'app
- `aw-server-rust/aw-server/src/android/mod.rs` — bridge JNI Rust
- `mobile/src/main/java/net/activitywatch/android/watcher/` — watchers
- `mobile/src/main/res/xml/` — preferences XML si existant
- Regarder comment les settings existants sont geres pour copier le pattern

## Contraintes

- Je n'ai PAS Android Studio installe, je ne peux PAS builder l'APK dans cette session. Focus sur le code, la PR sera testee apres.
- Le submodule aw-server-rust pointe vers un commit specifique. Il faudra peut-etre forker ce repo aussi.
- L'auteur du projet (Erik Bjäreholt) est actif et review les PRs. Qualite > vitesse.
