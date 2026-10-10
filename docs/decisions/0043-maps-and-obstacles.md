---
id: KYS-0043
title: Maps à obstacles, architecture et budget de performance prévus
date: 2026-10-10
status: proposed
tags: [architecture, game-core, render, performance, protocol]
supersedes: []
related: [KYS-0010, KYS-0041]
---

## Contexte

L’auteur prévoit plusieurs maps avec des obstacles. Rien n’est implémenté (hors périmètre V1/V1.5) : cette fiche
fixe l’architecture pour que les choix actuels n’y fassent pas obstacle, et le budget à tenir.

## Décision (prévue)

**Données.** Une map est une donnée, pas du code : `MapSpec(id, version, left: HalfLayout, right: HalfLayout)`, chaque
moitié listant ses obstacles statiques (rectangles alignés, cercles, segments) en unités logiques. Fichier de
ressources versionné, lu au démarrage d’une manche ; aucune dépendance (format texte simple ou JSON lu à la main).

**Accord entre les deux téléphones.** La map choisie (id + empreinte du contenu) est envoyée dans un message dédié du
protocole v2 ; une empreinte différente refuse la manche proprement, comme une version incompatible. Chaque appareil
ne simule que sa moitié : il n’a besoin que de ses propres obstacles, le handoff ne change pas.

**Collisions sans coût quadratique.**
- Grille uniforme précalculée au chargement de la map (par ex. 8 × 16 cellules par moitié), stockée en tableaux
  plats (index de début + liste d’obstacles par cellule, format CSR) : aucune allocation pendant les pas.
- Un projectile balayé parcourt les cellules traversées par son segment (DDA, comme un lancer de rayon) et ne teste
  que les obstacles de ces cellules : coût proportionnel aux cellules visitées, pas au nombre d’obstacles.
- Rebond ou absorption selon le type d’obstacle, résolus dans l’ordre du temps d’impact, avec un nombre de rebonds
  borné par pas ; mêmes règles de déterminisme qu’aujourd’hui (`Double`, opérations de base et `sqrt` seulement).
- L’entrée d’un projectile (rattrapage du handoff) passe par le même chemin, donc reste sans effet tunnel.

**Rendu.**
- Les obstacles statiques sont dessinés **une fois** dans la couche hors écran déjà utilisée pour le ciel
  (`drawSky`), qui n’est redessinée qu’au changement de taille : un décor riche ne coûte rien par image.
- Tuiles pixel art depuis un atlas unique (V2), dessinées sans lissage sur la grille de 96 pixels d’art.
- Un obstacle destructible ou animé (si un jour) invalide seulement sa propre couche, pas le décor entier.

## Budget et vérification

- Pas de simulation ≤ 1 ms sur le CMF Phone 1 avec 64 obstacles et 24 projectiles ; image p95 au niveau de la
  fréquence de l’écran (8,3 ms à 120 Hz).
- Tests : la grille donne exactement les mêmes impacts que la recherche exhaustive sur des maps aléatoires
  (test de propriété à graine fixe) ; déterminisme identique ; micro-mesure du pas en test JVM, et mesure réelle
  via l’overlay de debug et `dumpsys gfxinfo`.

## Conséquences

- Aucun changement nécessaire aujourd’hui : la simulation reste par moitié, le ciel est déjà une couche statique
  mise en cache, le protocole est versionné pour accueillir le message de map.
