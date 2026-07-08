# AGENTS.md — ProjectAddonsFolia

## Project identity

This repository is `ProjectAddonsFolia`, a Paper/Folia/Canvas-compatible fork of ProjectAddons for ProjectKorra.

This is not a single PlantArmor-only plugin. PlantArmor is one Waterbending ability inside a larger addon plugin that also contains Avatar, Airbending, Chiblocking, Earthbending, Firebending, and Waterbending abilities.

Do not make broad rewrites that only consider PlantArmor and accidentally break other abilities.

## Current priority

Primary current task:

1. Audit the entire plugin for Folia/Canvas scheduler safety.
2. Patch PlantArmor armor duplication.
3. Improve compatibility with Canvas running the Affinity scheduler.
4. Preserve existing gameplay behavior unless a behavior is unsafe or directly causes duplication/threading bugs.

## Hard rules

Do not rewrite the whole plugin unless specifically asked.

Do not rename packages, ability names, config keys, permissions, ProjectKorra ability registrations, or public commands unless specifically asked.

Do not remove existing abilities.

Do not convert Maven to Gradle unless specifically asked.

Do not assume this is only PlantArmor.

Do not add Canvas-exclusive APIs unless they are actually required.

Prefer Paper/Folia-safe APIs first. Use Canvas-exclusive APIs only when there is a clear benefit and the plugin is intentionally becoming Canvas-only.

## Build system

This repo uses Maven unless changed intentionally.

Use the existing `pom.xml` as the source of truth.

Minecraft plugin dependencies should generally be `provided`/`compileOnly` style, not shaded into the final jar, unless the dependency is a real library that must be bundled.

ProjectKorra, Spigot/Paper APIs, PlaceholderAPI, WorldGuard, Vault, and similar server plugin APIs should not be bundled unless there is a very specific reason.

Before changing dependencies, inspect the current `pom.xml`.

When possible, preserve the current Java language level unless code changes require updating it.

## Folia / Canvas / Affinity model

Canvas is Folia-based. There is no universal safe Bukkit main thread.

The plugin must not rely on old Bukkit main-thread assumptions.

The Canvas Affinity scheduler is a server-side scheduler configuration. Plugin code does not manually configure CPU affinity. The plugin’s job is to avoid cross-region/entity thread violations, avoid blocking region threads, and schedule work onto the correct context.

Affinity-friendly plugin code:

- Does not create global repeating scans over all players/entities.
- Does not use BukkitScheduler.
- Does not mutate players/entities from async threads.
- Does not mutate entities from the global scheduler.
- Does not mutate blocks/world locations from entity/global contexts unless ownership is correct.
- Keeps per-tick ability work small.
- Uses event-driven or per-ability/per-player scheduling instead of broad polling where possible.
- Avoids blocking IO or heavy calculations on entity/region/global schedulers.

## Scheduler rules

Never use:

- `Bukkit.getScheduler()`
- `BukkitScheduler`
- `BukkitRunnable`
- `runTask`
- `runTaskLater`
- `runTaskTimer`
- async Bukkit scheduler calls that touch Bukkit API

Correct scheduler usage:

### Entity / player work

Use the entity scheduler for anything tied to a player/entity.

Examples:

- player inventory
- armor/equipment
- health
- potion effects
- metadata
- velocity
- passenger/vehicle state
- ProjectKorra ability state tied to a player
- player messages where player validity matters
- dispatching commands as a player/entity

Pattern:

```java
player.getScheduler().execute(plugin, task -> {
    // player/entity-safe work here
}, null);