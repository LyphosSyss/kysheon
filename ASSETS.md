# Assets

Règle du projet : uniquement des assets **CC0**, chacun listé ici avec sa source et sa licence.
Aucun nom, son, visuel ou asset repris d’un jeu existant ou d’une licence tierce.

## Images (V1.5)

Toutes les images livrées sont dérivées de deux sources fournies par l’auteur, par le script
[`art/tools/make_assets.py`](art/tools/make_assets.py) (voir [`art/README.md`](art/README.md)).

| Fichier source | Origine | Licence |
|---|---|---|
| `art/source/kysheon-logo.jpg` | généré par IA par l’auteur (outil à préciser) | **à confirmer** |
| `art/source/kysheon-poster.jpg` | généré par IA par l’auteur (outil à préciser) | **à confirmer** |

Dérivés : `androidApp/src/main/res/drawable-nodpi/*.png`, `iosApp/iosApp/Assets.xcassets/**`,
`app/src/commonMain/composeResources/drawable/title_banner.jpg`, `docs/images/kysheon-banner.jpg`.

Tout le reste (combattants, projectiles, particules, fond, sons) est dessiné ou synthétisé dans le code :
aucun autre asset.

## V2 (prévu)

Sprites des créatures et des compétences en pixel art : un atlas unique, chaque sprite listé ici.
