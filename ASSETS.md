# Assets

Règle du projet : uniquement des assets **CC0**, chacun listé ici avec sa source et sa licence.
Aucun nom, son, visuel ou asset repris d’un jeu existant ou d’une licence tierce.

## Images (V1.5)

Toutes les images livrées sont dérivées de deux sources fournies par l’auteur, par le script
[`art/tools/make_assets.py`](art/tools/make_assets.py) (voir [`art/README.md`](art/README.md)).

| Fichier source | Origine | Licence |
|---|---|---|
| `art/source/kysheon-logo.jpg` | généré par l’auteur avec Google Gemini (octobre 2026) | CC0 1.0 |
| `art/source/kysheon-poster.jpg` | généré par l’auteur avec Google Gemini (octobre 2026) | CC0 1.0 |

Notes sur ces deux images :
- Les conditions d’utilisation de Google prévoient que Google ne revendique pas la propriété des contenus générés ;
  l’auteur les place sous **CC0 1.0** (renonciation à tout droit qu’il pourrait détenir).
- Une image générée par IA n’est souvent pas protégeable par le droit d’auteur et n’est pas exclusive : un tiers
  peut obtenir une image proche. Elles ne reprennent aucun nom ni visuel d’un jeu ou d’une licence existante.
- Google intègre un filigrane invisible (SynthID) à ces images ; les dérivés pixel art n’en garantissent pas la
  conservation.

Dérivés : `androidApp/src/main/res/drawable-nodpi/*.png`, `iosApp/iosApp/Assets.xcassets/**`,
`app/src/commonMain/composeResources/drawable/title_banner.jpg`, `docs/images/kysheon-banner.jpg`.

Tout le reste (combattants, projectiles, particules, fond, sons) est dessiné ou synthétisé dans le code :
aucun autre asset.

## V2 (prévu)

Sprites des créatures et des compétences en pixel art : un atlas unique, chaque sprite listé ici.
