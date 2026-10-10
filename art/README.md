# Art

Sources fournies par l’auteur (images générées par IA, voir [`ASSETS.md`](../ASSETS.md)) et l’outil qui en tire
toutes les images livrées.

| Fichier | Rôle |
|---|---|
| `source/kysheon-logo.jpg` | Logo (emblème doré), 1408 × 768 |
| `source/kysheon-poster.jpg` | Affiche (temple, emblème, titre KYSHEON), 1408 × 768 |
| `tools/make_assets.py` | Génère icônes, écrans de démarrage, titre de l’accueil et bannière du README |

```bash
python3 art/tools/make_assets.py   # Pillow requis
```

Choix faits par le script :
- **Icône en pixel art** : l’emblème (recadré dans le cadre doré) est réduit à 96 × 96, palette de 28 couleurs, puis
  agrandi sans lissage. Il reste net à 1024 px alors que la source ne fait qu’environ 620 px, et rejoint la direction
  pixel art du jeu.
- **Android** : icône adaptative (avant-plan, fond `#141418`, monochrome pour les icônes à thème d’Android 13) et logo
  d’écran de démarrage sans fond. **iOS** : icône 1024 px opaque, logo de lancement sans fond.
- **Titre de l’accueil** : l’emblème et le titre de l’affiche, bords fondus dans le fond de l’app.
- **Bannière du README** : l’affiche sans son sous-titre.
