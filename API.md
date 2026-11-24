# Just Epic Dimensions API

Hooks other mods can use to work with JED dimensions. Everything here is in the `justepicdimensions` package of `justepicdimensions-2.0.0.jar`.

## Adding JED to your mod

Compile against the JED JAR (`compileOnly` for an optional integration) and declare the dependency in `META-INF/neoforge.mods.toml`:

```toml
[[dependencies.yourmod]]
modId = "justepicdimensions"
type = "optional"          # "required" if your mod needs JED
versionRange = "[2.0.0,)"
ordering = "AFTER"
side = "BOTH"
```

For an optional integration, only touch JED classes after checking `ModList.get().isLoaded("justepicdimensions")`.

---

## Dimension names

**`justepicdimensions.api.dimension.JEDDimensionNames`** (client)

Each JED dimension can have a display name. The server sends the names to every client, so map, minimap, HUD and waypoint mods can show "Frozen Wastes" instead of `mypack:frozen_wastes`. A lookup is empty before the client has synced, after it disconnects, and for dimensions without a name, so keep your own fallback.

```java
String label = JEDDimensionNames.displayName(level.dimension().location())
        .orElse(level.dimension().location().toString());

Map<ResourceLocation, String> all = JEDDimensionNames.displayNames(); // snapshot; query again when drawing
```

## Travel checks

**`justepicdimensions.api.access.JEDAccess`** (server thread)

Asks JED whether an entity, and everything riding with it, may enter a dimension, before you move anyone. It is the same check JED runs on every dimension transfer, so portal, teleport and waystone mods can refuse up front with JED's own reason instead of having the move refused part-way through. `evaluateRule` and `ruleIds` let your own gates reuse server owners' JED access rules. Just Epic Portals uses this API.

```java
Optional<Component> denied = JEDAccess.denial(player, destination);
if (denied.isPresent()) {
    player.displayClientMessage(denied.get(), true);
    return; // nobody is moved
}

// Reuse a server owner's rule, e.g. data/mypack/justepicdimensions/access_rules/vip.json
JEDAccess.evaluateRule(player, ResourceLocation.parse("mypack:vip"))
        .filter(result -> !result.allowed())
        .ifPresent(result -> player.displayClientMessage(result.reason(), true));
```

## Lifecycle notifications

**`justepicdimensions.api.lifecycle`**: `JEDNotificationRegistry`, `JEDOperation`, `JEDOperationCompletedEvent` (server)

Find out when a JED dimension is registered, loaded, unloaded, unregistered, reactivated, reset, deleted or reloaded. Notifications are saved before they are delivered and come again after a crash or restart until your mod acknowledges them, so nothing is missed. Use them to keep waypoints, claims, quests or databases in step with the server's dimensions. Your handler may receive the same operation more than once, so it must cope with duplicates.

Declare your consumer in `neoforge.mods.toml`:

```toml
[modproperties.yourmod]
jedNotificationProtocol = 1
jedNotificationConsumers = ["yourmod:waypoints"]
```

Register it during mod construction or common setup:

```java
JEDNotificationRegistry.register(
    ResourceLocation.parse("yourmod:waypoints"),
    JEDNotificationRegistry.PROTOCOL,
    JEDNotificationRegistry.Contract.DURABLE_DUPLICATE_SAFE_ACKNOWLEDGEMENTS,
    EnumSet.of(JEDOperation.Kind.RESET, JEDOperation.Kind.DELETED),
    delivery -> {
        clearWaypointsOnce(delivery.operation()); // save your change, keyed by delivery.operation().id()
        delivery.acknowledge();
    });
```

For best-effort listening without acknowledgements, subscribe to `JEDOperationCompletedEvent` on `NeoForge.EVENT_BUS`.

## Custom world providers

**`justepicdimensions.definition.JEDProviderFactoryRegistry`**, **`justepicdimensions.provider.JEDWorldProvider`**

Register your own provider type with its own JSON settings. Server owners pick it by ID in a dimension's file. A provider can override surface behaviour, water vaporising, spawn height, horizon, fog and music. Use `register` when only the server needs your mod, and `registerWithClient` when clients need it too. JED then checks before a player joins that the client has your mod.

Fog is drawn by the client, so this example uses `registerWithClient`:

```java
JEDProviderFactoryRegistry.registerWithClient(
    ResourceLocation.parse("yourmod:misty"),
    JEDClientLightPolicy.Profile.SURFACE,
    JEDProviderSettings.CODEC,
    (dimension, settings) -> new JEDWorldProvider(JEDClientLightPolicy.Profile.SURFACE, settings) {
        @Override public boolean fog(boolean nativeValue, Level level, BlockPos camera) {
            return camera.getY() < 40; // fog only in the lowlands
        }
    });
```

```json
"provider": { "type": "yourmod:misty", "settings": {} }
```

## Sky, cloud and weather renderers

**`justepicdimensions.client.JEDRendererFactoryRegistry`** (client)

Register a renderer for the sky, clouds or weather, such as an aurora, a custom sky or unusual weather. Server owners then assign it to any JED dimension. The factory returns a normal `DimensionSpecialEffects`. If a renderer is missing, JED falls back to the standard look and logs it. Implement `AutoCloseable` to release GPU resources; JED closes renderers when they are no longer used.

```java
JEDRendererFactoryRegistry.register(
    ResourceLocation.parse("yourmod:aurora"),
    EnumSet.of(JEDRendererFactoryRegistry.Channel.SKY),
    AuroraSettings.CODEC,
    (dimension, settings) -> new AuroraSkyEffects(settings));
```

```json
"render": { "sky": { "renderer": { "id": "yourmod:aurora", "settings": { "speed": 2 } } } }
```

## Equipment effects

**`justepicdimensions.api.player.JEDEquipmentEffectProviders`** (server)

JED can give each dimension its own health, XP, hunger and effects. Minecraft doesn't record which item gave an effect, so curio, accessory and armour mods can tell JED which effects their worn items provide. Those effects then stay with the gear and are not saved or removed as ordinary effects when a player changes dimension. Providers only report effects; they must not change the player or the item.

```java
JEDEquipmentEffectProviders.register(
    ResourceLocation.parse("yourmod:charms"),
    (player, equipment) -> equipment.item().is(YourItems.NIGHT_CHARM.get())
            ? List.of(ResourceLocation.parse("minecraft:night_vision"))
            : List.of());
```
