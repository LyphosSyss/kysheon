# Flux E — UI Compose Multiplatform

**Périmètre** : `app/`, `androidApp/`, `iosApp/`.

- Écrans : accueil (héberger / rejoindre), connexion, partie (Canvas, letterbox), résultat (rejouer), overlay de debug.
- `render/` : interpolation, état lu en phase de dessin, aucune allocation par image, temps d’image p50/p95/p99.
- Accès Bluetooth (permissions, état de l’adaptateur) et fabrique de `Link` via `expect`/`actual`.
- Textes : `stringResource` EN + FR, `’`, `%1$s`. Pas d’emoji.
- Direction graphique originale : formes géométriques, aucune référence à un jeu existant.
