# Spec Regen Completed

Plays a sound the moment your special attack energy reaches the cost of the
weapon you are holding, and by default again at full energy.

The trigger is the energy crossing that weapon's cost while regenerating, so a
dragon dagger sounds at 25 percent and an Armadyl godsword at 50 percent. It
sounds once per crossing: a dagger going 15 to 25 or 20 to 30 plays, 50 to 60
does not, because the energy was already past 25.

While Liquid adrenaline from the Tombs of Amascut is active, every cost is
halved, so a dragon dagger sounds at 12.5 percent.

Weapons with no special attack are silent. 103 weapons are covered, including
their ornament-kit, corrupted, recoloured and charge-tier forms.

## Features

- **Play at full Energy**: `Both`, `Only at full` or `Only When Weapon Ready`. Default `Both`. The full energy sound needs a
  spec weapon equipped. A step that crosses the cost and fills the bar at once plays one sound.
- **Also Without Spec Weapon**: any weapon or none. Default `off`.
- **Sound**: `Sound 1` to `Sound 5`. Default `Sound 1`.
- **Volume**: 0 to 100. Default `50`.

Type `::specready` in chat to hear the current sound at the current volume.
