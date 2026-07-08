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

## Target Runtime Requirement

This fork targets Minecraft/Paper/Canvas 26.1.2 and newer.

Build rules:

1. Do not keep the project locked to Spigot API 1.20.5 or Paper API 1.21.x.
2. Use Paper API 26.1.2+ as the primary Minecraft API dependency.
3. For Maven, use:
   groupId: io.papermc.paper
   artifactId: paper-api
   version: [26.1.2.build,)
   scope: provided
4. Use Java 25 unless there is a hard compile reason not to.
5. Keep ProjectKorra provided.
6. Do not add Canvas API unless Canvas-exclusive APIs are actually used.
7. Do not add folia-supported or canvas-supported until scheduler safety work is complete.
8. If ProjectKorra 1.12.0 conflicts with the 26.1.2+ API, do not downgrade Paper. Report the conflict clearly and propose the smallest compatibility fix.

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

```

Do not store long-lived `Player` references in maps. Store `UUID`.

### Region / location work

Use the region scheduler for work tied to a specific `Location`.

Examples:

- block changes
- dropping items at a location
- spawning particles at fixed locations when not tied to an entity
- world interactions at a specific block/region

Pattern:

```java
Bukkit.getRegionScheduler().execute(plugin, location, () -> {
    // location/region-safe work here
});
```

### Global work

Use the global region scheduler only for server-wide/global operations.

Examples:

- console command dispatch
- world time/weather
- world border
- tick-rate/global server state

Pattern:

```java
Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
});
```

Never use the global scheduler as a lazy replacement for entity or location work.

### Async work

Use async only for non-Bukkit work.

Allowed async work:

- file IO
- database operations
- web requests
- expensive pure calculations
- parsing config data into plain objects

Forbidden async work:

- reading/writing player inventory
- entity/world/block access
- spawning particles/entities
- ProjectKorra player/entity state unless confirmed thread-safe
- Bukkit API calls in general

After async work completes, schedule a small continuation back onto the correct entity/region/global scheduler.

## Tick-thread ownership

When mutating a player/entity, the code must be running on that entity’s owning scheduler.

When mutating a location/block, the code must be running on that region’s owning scheduler.

If a method can be called from multiple contexts, either:

1. make it schedule itself onto the correct context, or
2. clearly document that the caller must already be on the correct scheduler.

Do not silently perform unsafe Bukkit operations from unknown context.

## Commands

Console command dispatch must run on the global region scheduler.

Player/entity command dispatch must run on that player/entity scheduler.

Do not call `Bukkit.dispatchCommand(...)` from async code or from the wrong region context.

## ProjectKorra ability caution

ProjectKorra abilities often run progress/tick-style logic.

Before changing an ability:

1. Identify how ProjectKorra calls the ability.
2. Identify whether the call is already on the owning player/entity context.
3. Do not assume ProjectKorra internals make all operations safe on Folia/Canvas.
4. Keep ability `progress()` methods lightweight.
5. Avoid scanning all online players/entities every progress tick.
6. Avoid storing direct Player references in static or long-lived maps.
7. Use UUID-keyed state when state must outlive a single method call.

## PlantArmor duplication bug

Known bug:

PlantArmor can duplicate the player’s original armor after activating/deactivating the ability.

This must be patched carefully.

Likely causes to audit:

- activation fires twice from main hand and off hand
- original armor is saved more than once
- original armor snapshot stores live `ItemStack` references instead of clones
- restore path runs multiple times from timer/death/quit/disable
- armor is restored and also dropped
- original armor is overwritten by temporary PlantArmor
- cleanup runs from the wrong scheduler
- player quits/dies/world-changes during active PlantArmor state
- armor slots contain non-PlantArmor items during restore and are overwritten/deleted

## Required PlantArmor design

Store active PlantArmor state by player UUID, not Player object.

Use an active session object, for example:

```java
public final class PlantArmorSession {
    private final UUID playerId;
    private final ItemStack[] originalArmor;
    private final AtomicBoolean restored = new AtomicBoolean(false);

    public PlantArmorSession(UUID playerId, ItemStack[] originalArmor) {
        this.playerId = playerId;
        this.originalArmor = cloneArmor(originalArmor);
    }

    public UUID playerId() {
        return playerId;
    }

    public ItemStack[] originalArmorClone() {
        return cloneArmor(originalArmor);
    }

    public boolean markRestored() {
        return restored.compareAndSet(false, true);
    }
}
```

The exact implementation may differ, but these rules are mandatory:

### Activation

Activation must run on the player/entity scheduler.

If activation comes from `PlayerInteractEvent`, ignore off-hand activation:

```java
if (event.getHand() != EquipmentSlot.HAND) {
    return;
}
```

If the player already has an active PlantArmor session, do not save armor again.

Either reject the second activation or refresh duration without replacing the original armor snapshot.

Snapshot original armor using deep clones:

```java
private static ItemStack[] cloneArmor(ItemStack[] armor) {
    ItemStack[] clone = new ItemStack[armor.length];
    for (int i = 0; i < armor.length; i++) {
        clone[i] = armor[i] == null ? null : armor[i].clone();
    }
    return clone;
}
```

Record the session before changing armor.

Do not store references from `player.getInventory().getArmorContents()` without cloning.

Do not ever save temporary PlantArmor as the player’s original armor.

### Temporary PlantArmor item identity

Temporary PlantArmor pieces must have a reliable marker.

Prefer `PersistentDataContainer` with a `NamespacedKey`, for example:

```java
new NamespacedKey(plugin, "plant_armor")
```

Do not rely only on display name or lore.

Restore logic should only treat tagged temporary pieces as PlantArmor.

### Restore / deactivation

Restore must run on the player/entity scheduler.

Remove the session atomically first:

```java
PlantArmorSession session = activeSessions.remove(player.getUniqueId());
if (session == null) {
    return;
}
if (!session.markRestored()) {
    return;
}
```

Restore exactly once.

Use fresh clones when restoring original armor.

Never restore the same original armor and also drop it.

Before restoring original armor, inspect current armor slots.

If a current armor slot contains a temporary PlantArmor piece, it can be replaced.

If a current armor slot contains a non-PlantArmor item, do not delete it. Move it to the player inventory first. If inventory is full, drop it at the player’s location using the correct region-safe flow.

Preferred behavior:

1. Move unexpected non-PlantArmor armor to inventory.
2. Restore original armor clone into armor slots.
3. Only drop overflow if inventory cannot accept it.
4. Never duplicate original armor.

### Quit/death/respawn/disable cleanup

Audit all cleanup paths.

PlayerQuitEvent:
- Restore safely if possible.
- Do not duplicate.
- Do not drop cloned original armor if it is also restored.
- Do not store Player references.

PlayerDeathEvent:
Choose one consistent behavior and document it in code.

Preferred safe behavior:
- restore original armor before vanilla death drops are calculated when possible, so vanilla handles drops normally.
- remove temporary PlantArmor from drops if necessary.
- do not add original armor manually to drops if it was restored to the inventory/equipment.

PlayerRespawnEvent:
- ensure no broken active PlantArmor session remains.
- do not restore twice.

Plugin disable:
- iterate active PlantArmor sessions by UUID
- find online players
- schedule restore on each player scheduler
- do not create/drop items for offline players unless persistence exists
- do not schedule new async tasks after plugin disable has begun

Teleport / portal / world change:
- do not mutate entity state during Canvas pre-teleport async events
- use post events or reschedule to the entity scheduler
- do not assume the old region still owns the player after teleport

## PlantArmor Durable Backup / No Armor Loss Requirement

PlantArmor must protect against **both armor duplication and armor loss**. A memory-only session is not acceptable because server restart, plugin reload, crash, watchdog halt, or severe lag could happen while a player is wearing temporary PlantArmor.

Rules:

1. Before replacing a player's real armor with temporary PlantArmor, the plugin must create a durable backup of the original armor.
2. The durable backup must be written before temporary PlantArmor is equipped.
3. If the backup write fails, activation must abort and the player's armor must not be changed.
4. The backup must be keyed by player UUID and a unique PlantArmor session id.
5. The same session id must be stored on every temporary PlantArmor piece using PersistentDataContainer.
6. On restore, only restore from the matching session backup.
7. Do not delete the backup until restore completes successfully.
8. On player join/startup, if a backup exists and the player is wearing matching temporary PlantArmor, restore the original armor.
9. If a backup exists but the player's current armor already matches the backup, clean up the stale backup without adding items.
10. If a backup exists but current state is ambiguous, do not blindly overwrite or delete anything. Log a warning and prefer safe manual recovery over item loss.
11. Delayed restore tasks must check the active session id before restoring, so lagged/stale tasks cannot restore old armor.
12. Restore must be idempotent. Repeated restore calls must not duplicate or delete armor.
13. Restart, reload, crash, plugin disable, or severe lag must not cause original armor to be lost.
14. Never store only live memory state as the source of truth for original armor.

Required transaction order:

`backup first` → `equip temporary armor` → `restore once` → `delete backup last`

Suggested implementation:

- `PlantArmorBackupStore`
- backups under `plugins/ProjectAddons/plantarmor-backups/<uuid>.yml`
- backup includes UUID, session id, created timestamp, cloned original armor contents
- use atomic write where practical: temp file then move/replace
- leave backup in place if restore fails or player is offline on disable

## ItemStack safety

Always clone stored `ItemStack`s.

Never store mutable inventory arrays directly.

Never pass a stored session array directly into `setArmorContents`.

Use:

```java
player.getInventory().setArmorContents(session.originalArmorClone());
```

Do not mutate `ItemStack` references from config/static templates without cloning first.

## Data structures

Prefer:

```java
Map<UUID, PlantArmorSession>
```

over:

```java
Map<Player, ...>
```

Use `ConcurrentHashMap<UUID, ...>` if callbacks may come from different scheduler contexts.

Even with `ConcurrentHashMap`, all player inventory/entity mutations must still happen on that player’s scheduler.

## Project-wide audit targets

Search for and patch unsafe usage of:

- `Bukkit.getScheduler`
- `BukkitRunnable`
- `runTask`
- `runTaskLater`
- `runTaskTimer`
- `runTaskAsynchronously`
- `scheduleSync`
- `getOnlinePlayers` inside repeating tasks
- static `Player` maps/lists
- live `ItemStack[]` snapshots
- `Bukkit.dispatchCommand`
- global scheduler used for player/entity work
- async tasks touching Bukkit/ProjectKorra entity/world APIs
- large loops inside ability progress methods
- block/entity access from unknown scheduler context

## Canvas support marker

Canvas will only attempt to load plugins that declare support.

Use one marker only:

```yaml
folia-supported: true
```

Use this when the plugin remains Paper/Folia-compatible and does not require Canvas-exclusive API.

Use:

```yaml
canvas-supported: true
```

only if the plugin uses Canvas-exclusive API and cannot run correctly on base Folia.

Do not set both unless explicitly requested and justified.

Adding a support marker is not enough. The code must actually be scheduler-safe.

## Canvas API dependency

Only add Canvas API if Canvas-exclusive API is actually used.

For Maven, Canvas API would be added as a provided dependency from the Canvas snapshots repository.

Do not add this just for normal Folia-safe scheduler work.

## Events

Canvas adds region-threading-specific events, especially around teleport, portal, world unload, and respawn.

If using Canvas-specific teleport/portal events:

- do not modify entity state in pre teleport/portal async events
- use post events for state changes after the entity is placed into the new region
- schedule player/entity mutations onto the entity scheduler when in doubt

## Tick-rate API

Do not use Bukkit’s tick-rate manager on Canvas.

Only touch Canvas tick-rate APIs if the feature specifically requires tick-rate manipulation.

ProjectAddons ability logic should normally not touch tick-rate APIs.

## Performance rules

Avoid global repeating loops.

Avoid scanning all entities/players every tick.

Avoid expensive particle/entity/block loops inside ability progress methods.

Prefer:

- per-player ability lifecycle
- event-driven state
- small bounded per-tick work
- caching immutable config values
- async IO/config parsing with scheduler-safe continuation

Do not block region/entity/global scheduler threads.

Never use:

```java
Thread.sleep(...)
future.get()
future.join()
```

inside scheduler callbacks or ability progress methods.

## Required workflow for agents

Before editing:

1. Inspect the repo structure.
2. Identify build system.
3. Identify plugin metadata file.
4. Identify main plugin class.
5. Identify ProjectKorra ability registration flow.
6. Identify all PlantArmor files/classes.
7. Identify scheduler/threading risks.
8. Produce an audit report.
9. Wait for approval before code changes.

First audit response must include:

- files/classes inspected
- current build metadata
- plugin support marker status
- unsafe scheduler/API usage found
- PlantArmor duplication suspects
- proposed patch plan
- compile/test plan

After approval, edit in small steps:

1. Add scheduler utility methods if useful.
2. Add/patch PlantArmor session state.
3. Add reliable temporary PlantArmor tagging.
4. Patch activation.
5. Patch restore/deactivation.
6. Patch quit/death/respawn/disable cleanup.
7. Replace unsafe scheduler usage.
8. Run Maven build.
9. Fix compile errors.
10. Summarize changed files and remaining risks.

## Testing checklist

After changes, test:

- activate PlantArmor with empty armor
- activate PlantArmor with full armor
- spam activate key/click
- activate from main hand and off hand
- deactivate normally
- let duration expire
- logout while active
- die while active
- respawn after active death
- teleport while active
- world change while active
- plugin disable while active
- inventory full while restoring
- armor slots changed while PlantArmor active
- multiple players active at once
- Folia/Canvas server console for thread ownership errors

Expected result:

- original armor returns exactly once
- temporary PlantArmor does not duplicate
- original armor does not duplicate
- player-added armor during active state is not deleted
- no `Thread failed main thread check`
- no `UnsupportedOperationException` from BukkitScheduler or wrong command dispatch
- no blocking scheduler warnings

## Style

Keep code simple.

Prefer readable helpers over clever abstractions.

Add comments only where they explain Folia/Canvas safety or dupe prevention.

Do not churn formatting across unrelated files.

Do not make unrelated gameplay changes.

When unsure, stop and report the uncertainty instead of guessing.