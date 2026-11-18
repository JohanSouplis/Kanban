# Kanban

Tableau Kanban personnel pour Android (Kotlin, Jetpack Compose, Material 3).

## Fonctionnement

- Trois colonnes : **À faire**, **En cours**, **Fait**. Glisser horizontalement ou toucher un onglet pour changer de colonne.
- Le bouton **+** crée une tâche : titre, description optionnelle, colonne et rappel optionnel.
- Une carte se modifie en la touchant. Son menu permet de la déplacer ou de la supprimer, et **✓ Valider** l'envoie dans « Fait ».
- Les tâches sont enregistrées sur le téléphone (`board.json` dans le stockage privé de l'appli).

## Rappels dans l'agenda

L'appli n'utilise pas de compte Google directement. Elle écrit dans les agendas déjà synchronisés sur le téléphone, via le fournisseur d'agenda d'Android (autorisation « Agenda » demandée au premier usage).

- Un rappel crée un événement de 30 minutes dans l'agenda choisi, avec une alerte (à l'heure, 5, 15, 30 ou 60 minutes avant, ou la veille). Google Agenda le synchronise ensuite vers le compte.
- Seuls les agendas visibles et modifiables apparaissent. Un agenda partagé doit donc être affiché et synchronisé dans l'appli Google Agenda du téléphone, et le partage doit donner le droit de modifier les événements.
- L'agenda utilisé en dernier est proposé par défaut.
- Valider une tâche (« Fait ») supprime son événement. La ramener dans une autre colonne recrée l'événement si le rappel est encore à venir. Supprimer une tâche supprime aussi son événement.
- Si l'événement a été supprimé à la main dans l'agenda, l'appli le recrée à la prochaine modification de la tâche.
- Sans l'autorisation « Agenda », le tableau fonctionne normalement. Le formulaire propose alors un bouton pour l'accorder.
- Si l'accès à l'agenda est retiré alors qu'une tâche a déjà un événement, valider ou modifier la tâche ne touche pas l'agenda. La carte affiche « agenda à mettre à jour », et l'appli applique le changement dès que l'accès revient.
- Supprimer une tâche sans accès à l'agenda laisse son événement dans l'agenda. La confirmation le signale et propose d'autoriser l'accès d'abord.

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
