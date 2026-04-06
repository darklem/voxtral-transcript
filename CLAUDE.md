# Voxtral — Session dédiée

Application Android de transcription vocale via Mistral Voxtral Mini API.  
Fond animé licornes/cœurs/ballons, synthèse vocale, compteur de coût.  
**v1.1 fonctionnelle — OTA ajouté.**

---

## Accès build

| Ressource | Détail |
|-----------|--------|
| LXC android-build | `ssh android-build` → root@192.168.0.195 (clé ~/.ssh/proxmox) |
| Projet dans le LXC | `/opt/android-voxtral/` |
| Build | `ssh android-build "cd /opt/android-voxtral && /opt/gradle/bin/gradle assembleDebug --no-daemon"` |
| APK output | `app/build/outputs/apk/debug/app-debug.apk` |
| Récupérer l'APK | `scp -i ~/.ssh/proxmox root@192.168.0.195:/opt/android-voxtral/app/build/outputs/apk/debug/app-debug.apk /tmp/voxtral-vX.Y.apk` |
| Source locale | `/home/dl/projects/voxtral/` (ce dossier) |
| GitHub | `git@github.com:darklem/voxtral-transcript.git` (branch: main) |

> Synchro local → LXC :  
> `scp -r /home/dl/projects/voxtral/app/src android-build:/opt/android-voxtral/app/`

---

## Clé API Mistral (SECRET — ne jamais committer)

La clé est dans `local.properties` (gitignorée) :
```
mistral.api_key=<TA_CLE_MISTRAL>
```
Le `app/build.gradle.kts` la lit via `localProps.getProperty("mistral.api_key")` → `BuildConfig.MISTRAL_API_KEY`

---

## Fonctionnalités

- **Enregistrement** : MediaRecorder AAC 16kHz MPEG-4 → fichier .m4a temporaire
- **Transcription** : Mistral Voxtral Mini API (`voxtral-mini-latest`), langue = fr
- **Synthèse vocale** : Android TextToSpeech, langue FR avec fallback silencieux (compatible GrapheneOS)
- **Fond animé** : `FloatingEmojiView.kt` — 18 particules (🦄💜🎈💖🌸✨🎀💕), Canvas 30fps
- **Compteur coût** : durée × $0.004/min, total cumulé en SharedPreferences "voxtral_stats"
- **Copie** : long press sur une transcription → clipboard
- **OTA** : `UpdateManager.kt` — check `version.json` sur GitHub raw au démarrage → AlertDialog → DownloadManager → FileProvider install

---

## OTA

**Architecture** : OkHttp → auth anonyme Firebase REST → Firestore `/config/voxtral_update` → AlertDialog → DownloadManager → FileProvider

**APKs stockés dans** : Firebase Storage `messaging-app-71a13.firebasestorage.app/apks/`

**Clé service account** (pour uploads) : `~/.secrets/firebase-adminsdk.json`

**Script upload + mise à jour Firestore** :
```python
# voir /home/dl/projects/voxtral/scripts/release.py (à créer)
# ou utiliser le script Python en session
```

**Pour publier une mise à jour** :
1. Incrémenter `CURRENT_VERSION` dans `UpdateManager.kt`
2. Incrémenter `versionCode` + `versionName` dans `app/build.gradle.kts`
3. Builder l'APK
4. Uploader sur Firebase Storage via script Python (service account)
5. Mettre à jour Firestore `config/voxtral_update` → `version` + `url` + `notes`

---

## Fichiers clés

```
app/src/main/java/cat/canigo/voxtral/
├── MainActivity.kt        — UI principale, recording, API call, stats
├── FloatingEmojiView.kt   — animation fond (Canvas, 30fps)
└── UpdateManager.kt       — OTA (GitHub version.json → install)

app/src/main/res/
├── layout/activity_main.xml
└── xml/file_paths.xml     — FileProvider paths (Downloads/)

version.json               — manifest OTA (à la racine du repo)
```

---

## Dépendances (app/build.gradle.kts)

```kotlin
implementation("androidx.core:core-ktx:1.15.0")
implementation("androidx.appcompat:appcompat:1.7.0")
implementation("com.google.android.material:material:1.12.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```
Pas de Firebase — OTA via OkHttp natif.

---

## Versioning

| Version | versionCode | Notes |
|---------|-------------|-------|
| 1.0 | 1 | Version initiale |
| 1.1 | 1 | OTA + BuildConfig pour clé API |

> À la prochaine release : mettre versionCode=2, versionName="1.2", CURRENT_VERSION=2

---

## Permissions AndroidManifest

- `RECORD_AUDIO` — enregistrement micro
- `INTERNET` — API Mistral + check OTA
- `REQUEST_INSTALL_PACKAGES` — installer l'APK téléchargé via OTA
