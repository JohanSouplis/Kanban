# Kanban

Tableau Kanban personnel pour Android (Kotlin, Jetpack Compose, Material 3).

## Fonctionnement

- Quatre onglets : **À faire**, **Backlog**, **En cours**, **Fait**. Glisser horizontalement ou toucher un onglet pour changer de colonne.
- **À faire** contient les tâches qui ont une date prévue (leur rappel), de la plus proche à la plus lointaine. **Backlog** contient les tâches à faire sans date. Ce n'est pas une colonne enregistrée à part : retirer la date d'une tâche la renvoie au backlog, lui en donner une (**Planifier**) l'en sort.
- Dans le backlog, un appui long sur une carte permet de la glisser plus haut ou plus bas. L'ordre est partagé entre les téléphones. Une tâche qui arrive dans le backlog (nouvelle, date retirée, ramenée d'« En cours » ou « Fait ») se place en bas.
- Le bouton **+** crée une tâche dans l'onglet affiché : titre, description, pièces jointes, colonne et date prévue (rappel). Depuis « À faire », le rappel est proposé d'office.
- Une carte se modifie en la touchant. Ses boutons **Planifier**, **Commencer** et **✓ Valider** la font avancer ; son menu permet de la déplacer, de retirer sa date ou de la supprimer. Un déplacement s'annule depuis le message en bas de l'écran.
- Les tâches sont enregistrées dans Cloud Firestore (Firebase) et partagées entre les téléphones du tableau. Firestore garde une copie sur le téléphone : l'appli marche hors ligne et envoie les changements au retour du réseau.

## Pièces jointes

Le formulaire d'une tâche accepte des photos (galerie ou appareil photo) et des fichiers, visibles sur les deux téléphones. Toucher une pièce jointe l'ouvre dans l'appli adaptée du téléphone.

- 10 pièces jointes au maximum par tâche.
- Une photo est réduite (1600 px de côté au plus) et recompressée en JPEG jusqu'à 700 Ko. Un autre fichier (ou un GIF) est refusé au-delà de 900 Ko.
- Les ajouts et retraits ne sont écrits qu'à l'enregistrement de la tâche. Supprimer une tâche supprime ses pièces jointes.
- Le contenu est stocké dans Firestore (`boards/{id}/files/{pièce jointe}`), un document par fichier, d'où la limite proche du maximum d'1 Mio par document. Firebase Storage serait plus adapté mais exige le forfait payant Blaze pour un nouveau projet ; Firestore reste dans le forfait gratuit Spark (1 Gio stocké).
- Les tâches ne contiennent que le nom, le type et la taille. Le contenu est téléchargé à la demande (vignette d'une photo à l'ouverture du formulaire, fichier quand on le touche), puis gardé dans le cache de Firestore : une pièce jointe jamais affichée sur ce téléphone n'est pas disponible hors ligne.

## Partage

Un tableau est partagé entre plusieurs téléphones Android, sans compte : chaque installation reçoit une identité Firebase anonyme.

1. Au premier lancement, choisir **Créer un tableau** sur un premier téléphone.
2. Menu ⋮ > **Partager le tableau** affiche le code du tableau (6 caractères).
3. Sur l'autre téléphone, choisir **Rejoindre avec un code** et saisir ce code. Créer ou rejoindre demande le réseau.
4. Les tâches se mettent ensuite à jour en direct sur les deux téléphones.

L'identité anonyme disparaît avec l'appli (désinstallation, effacement des données). Pour retrouver le tableau, rejoindre à nouveau avec le code : le garder quelque part.

Les tâches de la version 1.0 (`board.json`) sont reprises dans le tableau créé ou rejoint, puis le fichier est supprimé. Leurs rappels ne sont gardés que si l'accès à l'agenda est accordé avant : l'écran d'accueil le propose.

## Rappels dans l'agenda

L'appli n'utilise pas de compte Google directement. Elle écrit dans les agendas déjà synchronisés sur le téléphone, via le fournisseur d'agenda d'Android (autorisation « Agenda » demandée au premier usage).

- Un rappel crée un événement de 30 minutes dans l'agenda choisi, avec une alerte (à l'heure, 5, 15, 30 ou 60 minutes avant, ou la veille). Google Agenda le synchronise ensuite vers le compte, puis vers les autres téléphones qui affichent cet agenda.
- Seuls les agendas visibles et modifiables apparaissent. Un agenda partagé doit donc être affiché et synchronisé dans l'appli Google Agenda de chaque téléphone, et le partage doit donner le droit de modifier les événements.
- L'agenda utilisé en dernier est proposé par défaut.
- Valider une tâche (« Fait ») supprime son événement. La ramener dans une autre colonne recrée l'événement si le rappel est encore à venir. Supprimer une tâche supprime aussi son événement.
- Si l'événement a été supprimé à la main dans l'agenda, l'appli le recrée à la prochaine modification de la tâche sur le téléphone qui l'avait créé.
- Sans l'autorisation « Agenda », le tableau fonctionne normalement. Le formulaire propose alors un bouton pour l'accorder.
- Si l'accès à l'agenda manque quand une tâche change, la carte affiche « agenda à mettre à jour », et l'appli applique le changement dès que l'accès revient. Cela vaut aussi pour une tâche supprimée.

### Agenda partagé entre deux téléphones

Les deux téléphones voient le même agenda Google, mais chacun avec ses propres numéros d'agenda et d'événement. L'appli s'appuie donc sur deux repères qui passent par la synchronisation Google :

- l'agenda est désigné par son identifiant Google (`…@group.calendar.google.com`), le même sur les deux téléphones ;
- l'événement porte dans sa description une ligne `kanban:<id de la tâche>`. Ne pas l'effacer : c'est elle qui relie l'événement à la tâche.

Règles suivies pour éviter les doublons :

- Seul le téléphone où l'on agit crée un événement. La tâche enregistre quel téléphone l'a créé, dans quel agenda.
- Un changement reçu de l'autre téléphone ne crée jamais d'événement. Il met à jour ou supprime l'événement s'il est déjà sur ce téléphone. Ainsi, si une tâche créée sur le téléphone A est validée sur le téléphone B avant que Google y ait recopié l'événement, le téléphone A le supprime en recevant la tâche validée.
- Si l'agenda du rappel n'existe pas sur ce téléphone, la carte affiche « agenda absent de ce téléphone » et la tâche reste « hors agenda » tant qu'un téléphone qui a cet agenda ne l'enregistre pas.
- Les heures suivent le fuseau du téléphone qui saisit le rappel.

## Configurer Firebase (une fois)

Le projet Firebase est `kanban-89091` (compte Google de Johan, forfait gratuit Spark) et `app/google-services.json` est sa config. Pour le recréer de zéro :

1. Sur https://console.firebase.google.com, **Ajouter un projet** (par exemple « Kanban »). Google Analytics n'est pas nécessaire. Le forfait gratuit Spark suffit.
2. **Build > Authentication > Commencer**, onglet **Méthode de connexion** : activer **Anonyme** et enregistrer.
3. **Build > Firestore Database > Créer une base de données** : emplacement **eur3 (Europe)**, démarrer en **mode production**.
4. Onglet **Règles** de Firestore : remplacer tout le contenu par celui de [`firestore.rules`](firestore.rules), puis **Publier**.
5. **Paramètres du projet** (roue dentée) > **Vos applications** > icône Android : nom du package `com.johansouplis.kanban`, pas besoin de SHA-1. **Enregistrer l'application**.
6. Télécharger `google-services.json` et remplacer `app/google-services.json` par ce fichier (les étapes suivantes de l'assistant, SDK et plugin, sont déjà faites dans le projet).
7. Recompiler (`./gradlew assembleRelease`) et installer le nouvel APK sur les deux téléphones.

Les règles sont testées contre l'émulateur Firestore (JDK requis) : `cd firestore-tests && npm install && npm test`.

**Mise à jour depuis la 1.1** : la 1.2 ajoute le rang du backlog, les pièces jointes et la collection `files`. Republier [`firestore.rules`](firestore.rules) (étape 4) avant d'installer la 1.2, sinon Firestore refuse ses écritures.

## Installer l'APK

1. Télécharger `release/kanban.apk` (ou l'APK attaché à une release GitHub) sur le téléphone.
2. L'ouvrir et autoriser l'installation d'applications inconnues pour le navigateur ou le gestionnaire de fichiers quand Android le demande.
3. Android 8.0 (API 26) minimum.

## Compiler

Prérequis : JDK 17 ou plus et le SDK Android (plateforme 35). Indiquer le SDK dans `local.properties` (`sdk.dir=...`) ou via `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest    # tests unitaires
./gradlew assembleRelease      # APK signé : app/build/outputs/apk/release/app-release.apk
```

L'APK release est signé avec `keystore/release.jks` (alias `kanban`, mot de passe `kanbanrelease`), versionné dans le dépôt car l'appli est destinée au sideload personnel.

La CI GitHub Actions lance les tests et construit l'APK à chaque push. Sur `main`, elle publie la release `v<versionName>` avec l'APK `kanban.apk` : changer `versionName` dans `app/build.gradle.kts` pour sortir une nouvelle version.
