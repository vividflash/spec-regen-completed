# Spec Regen Completed

Plays a sound the moment your special attack energy reaches the cost of the
weapon you are holding.

The trigger is the energy crossing that weapon's cost while regenerating, so a
dragon dagger sounds at 25 percent and an Armadyl godsword at 50 percent. It
sounds once per crossing: a dagger going 15 to 25 or 20 to 30 plays, 50 to 60
does not, because the energy was already past 25.

Weapons with no special attack are silent. 91 weapons are covered, including
their ornament-kit, corrupted, recoloured and charge-tier forms.

## Settings

- **Sound**, one of five bundled clips, default Sound 1.
- **Volume**, 0 to 100, default 50. At 0 the plugin plays nothing.

Type `::specready` in chat to hear the current sound at the current volume.
