# Handoff : Kanban 1.1.0 (tableau partagé Firebase)

Pour reprendre dans une nouvelle conversation qui a le connecteur Firebase. Lire aussi `README.md`.

## Où on en est

- **`main` (cc7962c), release `v1.0.0`** : appli locale. Un rappel crée un événement dans l'agenda Google choisi via `CalendarContract` ; valider la tâche le supprime. Testée par Johan sur son téléphone : l'événement est bien arrivé dans « Parigots v2 ».
- **Branche `ccr-2423e9be-rt2xf0` (7c9ee56)** : version 1.1.0, tableau partagé entre Johan et sa copine (Android tous les deux) via Firebase. Le code est fini et les 34 tests JVM passent, mais `app/google-services.json` est un **faux** (`project_id: kanban-placeholder`). L'appli ne peut donc pas encore se connecter.
- **Version 1.2.0 (code 3)** par-dessus la 1.1.0 : onglet Backlog (tâches à faire sans date, ordonnables par appui long), pièces jointes stockées dans Firestore, éditeur plein écran, annulation des déplacements. Republier `firestore.rules` avant de l'installer.
- **Ne pas fusionner sur `main` avant d'avoir le vrai fichier.** Chaque push sur `main` fait publier par la CI la release `v<versionName>` avec l'APK (`.github/workflows/android.yml`). Avec le faux fichier, ce serait une 1.1.0 qui ne se connecte pas.

## Ce qu'il reste à faire

1. **Créer le projet Firebase** avec le connecteur (offre gratuite Spark, Johan ne veut rien payer), sur le compte Google de Johan :
   - Authentication : activer le fournisseur **Anonyme**.
   - Firestore : base `(default)`, **mode production**, emplacement **eur3**.
   - Déployer les règles de `firestore.rules` (à la racine du repo) telles quelles.
   - Enregistrer l'appli Android `com.johansouplis.kanban`, sans SHA-1 (pas de connexion Google, seulement l'authentification anonyme).
   - Récupérer `google-services.json` et remplacer `app/google-services.json`.
2. **Compiler** : `./gradlew testDebugUnitTest assembleRelease`, puis copier `app/build/outputs/apk/release/app-release.apk` vers `release/kanban.apk`. Vérifier avec `aapt dump badging` : version 1.1.0 (code 2) et le vrai `project_id` dans l'APK.
3. **Commit sur la branche, fusion sur `main`, push.** Attendre que la CI publie `v1.1.0`, puis vérifier que l'APK est attaché à la release (API publique, repo public : `curl https://api.github.com/repos/JohanSouplis/Kanban/releases`).
4. **Faire tester Johan**, dans cet ordre :
   - Tester d'abord le risque principal (ci-dessous).
   - Il crée le tableau ; ses tâches 1.0 doivent y être migrées.
   - Sa copine le rejoint avec le code (menu ⋮ > « Partager le tableau »).
   - Elle valide une tâche de Johan qui a un rappel : l'événement doit disparaître de « Parigots v2 ».

## Risques non vérifiés (rien n'a tourné sur un téléphone)

- **Clé d'agenda commune aux deux téléphones.** `CalendarRepository.calendars()` prend la première valeur qui contient un `@` parmi `Calendars.OWNER_ACCOUNT` et `_SYNC_ID`. À défaut, la clé est `local:<compte>:<id>` et ne vaut que pour ce téléphone. L'adresse attendue pour « Parigots v2 » est `e9b2eed58742721f8d524683ac0db6b95352fd49dee2818f9feb965d32770b8b@group.calendar.google.com` (lue via l'API Calendar). Si la clé est mauvaise, la carte affiche « agenda absent de ce téléphone » chez la copine.
- **Le marqueur `kanban:<taskId>`** ajouté à la description de l'événement doit être recopié par la synchronisation Google sur l'autre téléphone. C'est grâce à lui que les deux téléphones retrouvent le même événement.
- **Avec la vraie config**, la connexion anonyme et la création ou la jonction d'un tableau n'ont jamais été exécutées.

## Choix de conception (détails dans le README)

- **Firestore** :
  - `boards/{id}` contient `{joinCode, members, createdAt}`.
  - `joinCodes/{code}` contient `{boardId}`.
  - Chaque tâche est dans `boards/{id}/tasks/{taskId}`.
  - Les règles ont été testées dans l'émulateur : `firestore-tests/`, lancées avec `npm install && npm test`.
- **Événements d'agenda** :
  - Les règles métier sont pures et vivent dans `domain/Board.kt`.
  - Le téléphone qui fait l'action crée, modifie ou supprime l'événement.
  - Un changement reçu de l'autre téléphone peut **modifier ou supprimer** un événement, jamais en **créer** un. Sinon les deux téléphones créeraient chacun le leur.
  - `EventPlacement.placedBy` indique quel téléphone a créé l'événement, pour éviter les doublons tant que la synchronisation Google n'est pas passée.
  - Quand une opération sur l'agenda échoue (pas d'accès), la tâche est notée « en attente » ; on retente à chaque retour au premier plan.
- **Limites acceptées** :
  - Un doublon reste possible si les deux téléphones créent le même rappel exactement au même moment.
  - Une tâche enregistrée sur un téléphone sans accès à l'agenda n'a pas d'événement tant que quelqu'un ne la réenregistre pas depuis un téléphone qui y a accès.
- **Firebase BoM 34.19.0** : la 35.x exige Kotlin 2.4, alors que le projet est en Kotlin 2.2.

## Contexte utile

- **Fuseau horaire** : le téléphone de Johan était à l'heure de Tokyo (voyage). Les rappels suivent le fuseau du téléphone. Un choix de fuseau dans le rappel lui a été proposé ; il n'a pas répondu.
- **Branche par défaut GitHub** : c'est encore `ccr-2423e9be-rt2xf0`. Il faut passer `main` par défaut (Settings > General) ; c'est à Johan de le faire si la session n'en a pas le droit.
- **Limites de la session précédente** :
  - Push de tag interdit (403). C'est pour ça que la CI crée la release elle-même depuis `main`.
  - Pas d'outils GitHub MCP. Le statut de la CI se lit via l'API publique.
- **Environnement de build, dans un conteneur neuf** :
  - SDK Android : télécharger `commandlinetools-linux-11076708_latest.zip` depuis dl.google.com dans `~/android-sdk/cmdline-tools/latest`, puis lancer `sdkmanager --licenses` et `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"`.
  - Écrire `local.properties` avec `sdk.dir=/root/android-sdk`.
  - Pas d'émulateur (pas de KVM).
  - Maven Central renvoyait des 404 au hasard derrière le proxy. Le contournement était un script `~/.gradle/init.d/` qui met en premier le miroir Google de Maven Central (`https://maven-central.storage-download.googleapis.com/maven2/`).
- **Signature** : `keystore/release.jks`, alias `kanban`, mot de passe `kanbanrelease`. Il est volontairement dans le repo (appli perso installée à la main) ; sans lui, une nouvelle version ne pourrait pas s'installer par-dessus l'ancienne.
