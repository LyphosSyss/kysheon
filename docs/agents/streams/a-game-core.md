# Flux A — :game-core

**Périmètre** : `game-core/` uniquement. Kotlin pur, zéro dépendance, pas de notion de temps réel (le temps arrive en paramètre).

- Espace logique d’une moitié : x ∈ [0, 1], y ∈ [0, aspect] après mise à l’échelle (aspect = hauteur/largeur commun aux deux appareils).
- Host = moitié gauche (frontière en x = 1), guest = moitié droite (frontière en x = 0). Pas de miroir : vx > 0 va de gauche à droite.
- Pas fixe 60 Hz, accumulateur avec rattrapage borné, alpha d’interpolation exposé pour le rendu.
- Collisions balayées (segment contre cercle) : aucun effet tunnel.
- Entrée d’un projectile (HANDOFF) : apparition sur la frontière puis avance de `elapsed` en pas fixes, avec collisions.
  Déterministe pour une même entrée.
- Calcul en `Double`, opérations de base et `sqrt` uniquement dans la boucle (résultats identiques JVM / Native).
- Tests : collisions, pas fixe, déterminisme du handoff, 3 PV et fin de partie.
