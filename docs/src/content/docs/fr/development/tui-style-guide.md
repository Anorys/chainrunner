---
title: Guide de style de l'interface
description: Les règles visuelles de l'interface terminal, la palette Anorys dans le terminal.
---

Chaque couleur et chaque symbole de l'interface viennent de `com.anorys.chainrunner.tui.theme.Theme` ;
les vues n'en codent jamais en dur. Changez le thème là et toute l'interface suit, `NO_COLOR` et le
repli ASCII compris.

## Principes

L'interface suit l'identité visuelle Anorys (mode sombre) : le vert pour agir, le gris pour structurer,
une couleur et un symbole par état.

1. **L'état d'abord.** Les erreurs sont la seule couleur chaude et saturée à l'écran ; les lignes
   saines restent discrètes.
2. **Le gris structure, le vert agit.** Les grandes surfaces utilisent les gris neutres ; les verts
   marquent ce sur quoi on peut agir : la sélection, l'onglet actif, les lettres des touches, ce qui
   tourne.
3. **Le point orange est rare.** Comme dans le logo, l'orange marque la seule chose en cours : le
   repère de la ligne sélectionnée. Jamais une couleur d'état.
4. **Chaque état a un symbole et un mot.** La couleur n'est jamais le seul signal.
5. **Les touches sont toujours visibles.** Le pied de page montre les touches actives sur l'écran courant.

## Palette

L'interface peint son propre fond, elle a donc le même aspect quel que soit le thème du terminal.

| Jeton | Hex | Usage |
|---|---|---|
| `CRUST` | `#171919` | barres d'en-tête et de pied de page |
| `MANTLE` | `#1B1D1D` | dialogues |
| `BASE` | `#202323` | fond principal |
| `SURFACE0` | `#163631` | ligne sélectionnée |
| `SURFACE1` | `#303535` | bordures, pastilles de touches |
| `OVERLAY` | `#6E7875` | texte atténué : indications, unités |
| `SUBTEXT` | `#A2ABA8` | texte secondaire |
| `TEXT` | `#E3E7E6` | texte principal |
| `FACET_DARK`, `FACET`, `FACET_LIGHT` | `#08473F`, `#0E7A6B`, `#17A58F` | logo ; le dégradé de la marque |
| `ACCENT` | `#4FC1B1` | focus, onglet actif, lettres des touches, étapes en cours |
| `SPARK` | `#FF7A1A` | le point orange du logo, le repère de la ligne sélectionnée |

## États

| État | Jeton | Hex | Symbole | ASCII |
|---|---|---|---|---|
| OK, trouvé | `GREEN` | `#5FD39A` | `✔` | `+` |
| Avertissement, délai dépassé | `AMBER` | `#F0A766` | `▲` | `!` |
| Échec, refus | `RED` | `#F07178` | `✘` | `x` |
| En cours, attente d'une ligne | `ACCENT` | `#4FC1B1` | spinner | `*` |
| Pas encore lancé, sauté, inutile | `OVERLAY` | `#6E7875` | `◌` | `.` |

Les méthodes HTTP sont colorées selon leur nature : lectures en `BLUE`, écritures en `ACCENT`, `DELETE`
en `AMBER`, SQL en `VIOLET`.

## Composants

- **En-tête** (2 lignes, `CRUST`) : l'hexagone à facettes, le logo-texte dans le dégradé de la marque
  (une ligne de lettres simples sous 110 colonnes), le chemin de la configuration, le badge
  d'environnement et les jetons.
- **Onglets** : les séquences ; l'actif en gras `ACCENT` avec un soulignement en dégradé.
- **Panneaux** : bordures arrondies, celui qui a le focus en `ACCENT`.
- **Tableaux** : pas de lignes de grille internes ; la ligne sélectionnée sur `SURFACE0` avec un repère
  `▌` en `SPARK`.
- **Pied de page** : pastilles de touches, la touche en `ACCENT` sur `SURFACE1`, l'action en `SUBTEXT`.
- **Toasts** : une ligne au-dessus du pied de page, colorée selon la gravité, qui disparaît après
  quelques secondes.
- **Dialogues** : centrés, bordure `ACCENT` sur `MANTLE`, actions en pastilles de touches ; chaque
  lancement demande d'abord confirmation.

## Mouvement

L'animation dit seulement que quelque chose tourne : les spinners, et le dégradé du logo-texte qui
glisse pendant un lancement. Rien ne bouge quand rien ne tourne.

## Replis

- `CHAINRUNNER_ASCII=1`, ou une console incapable d'afficher les symboles, utilise la colonne ASCII
  ci-dessus.
- `NO_COLOR` est respecté : les états restent lisibles grâce à leurs symboles et leurs mots.
- Sous 80 × 24, l'interface n'affiche qu'un message « agrandissez le terminal ».
