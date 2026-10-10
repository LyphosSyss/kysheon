---
id: KYS-0041
title: Direction « sacrée » et ressenti de jeu (V1.5)
date: 2026-10-10
status: accepted
tags: [ui, render, feel]
supersedes: []
related: [KYS-0005, KYS-0040]
---

## Contexte

Choix de l’auteur pour la V1.5 : direction **sacrée / mythologique** (préfigure les créatures-divinités de la V2),
tous les retours sensoriels (particules, tremblement, vibrations, sons), shaders limités au **fond et aux lueurs**,
priorité au **ressenti en jeu**.

## Décision

- **Identité Soleil contre Lune** : l’hôte (gauche) en or, l’invité (droite) en argent lunaire, sur un ciel nocturne.
  La frontière est un voile céleste qui ondule au passage d’un projectile.
- **Symboles inventés** : halos, rayons, croissants et points en orbite générés en code. Aucun symbole religieux réel,
  aucune rune historique : originalité garantie et respect des cultures (voir `~/Epheis/ideas/mythic-sprites.md`).
- **Événements de présentation** publiés par `MatchController` (`Feedback`) et consommés une fois par image :
  le rendu, les vibrations et le son n’ont aucun accès à la simulation.
- **Rendu sans allocation par image** : `ParticleField` en tableaux avec plage dense, `Shake` (traumatisme au carré),
  `Ripples` à capacité fixe, lueurs par disques additifs (`BlendMode.Plus`) au lieu de dégradés recréés.
- **Fond** : un seul programme compilé en AGSL (Android 13+) et en SkSL via Skia (iOS), repli Canvas en dessous
  d’Android 13 ou si la compilation échoue. Redessiné à 30 Hz sur une couche hors écran séparée, pour ne pas coûter
  au rendu 120 Hz de l’arène.
- **Vibrations** : effets prédéfinis du constructeur (API 29+) ou impulsions courtes ; `UIImpactFeedbackGenerator`
  préparé à l’avance sur iOS. Son et vibrations désactivables depuis l’accueil (pour la session, sans persistance).

## Conséquences

- Le source SkSL est compilé dans un test sur le simulateur iOS ; la compilation AGSL ne se vérifie que sur un
  téléphone Android 13+.
- Le coût réel (temps d’image sur le CMF Phone 1, le plus modeste des trois) reste à mesurer avec l’overlay de debug.
