package justepicdimensions.config;

import java.util.*;
import com.electronwill.nightconfig.core.CommentedConfig;

/** Shared explanations for native generation and TOMLs created by the configuration editor. */
public final class JEDConfigComments {
    private static final Map<String,String> MAIN = Map.ofEntries(
        Map.entry("copyDimensionConfigToWorld", """
            Copy dimension JSON into a save when missing, or when its manifest has the same config_id and a higher config_version.
            Copying and selecting which files to read are independent settings.
            Example for shared global JSON: usePerWorldDimensionConfig = false
            Example for independent save JSON: copyDimensionConfigToWorld = true and usePerWorldDimensionConfig = true
            The save copy is under <save>/serverconfig/justepicdimensions/.
            """.stripTrailing()),
        Map.entry("usePerWorldDimensionConfig", """
            Read dimension JSON from the save when available instead of config/justepicdimensions/data.
            false: use the shared global definitions. true: select this save's definitions.
            This switch does not itself copy or overwrite files; copyDimensionConfigToWorld controls copying.
            """.stripTrailing()),
        Map.entry("useShowcase", """
            Unpack missing playable showcase files into config/justepicdimensions/data before dimension discovery. Existing files are preserved. Reopen the world/server to register new dimensions; disabling does not delete installed files.
            """.stripTrailing()),
        Map.entry("enableReplacingRegisteredDimensions", """
            Allow JED JSON to replace native declarations of existing dimensions. Changes apply on world/server restart.
            """.stripTrailing()),
        Map.entry("enableUnregisteringDimensions", """
            Honor lifecycle.enabled=false requests in dimension JSON on world/server restart. Files are retained; manual lifecycle commands are independent.
            """.stripTrailing()),
        Map.entry("automaticUnloadDimensions", """
            Automatically unload eligible idle dimensions, including vanilla and other mods.
            Example: automaticUnloadDimensions = true with automaticUnloadIdleSeconds = 900 waits fifteen eligible idle minutes.
            Explicit dimension settings override these defaults. The Overworld and temporary worlds are excluded from this global default.
            Unloading saves ordinary world terrain; it is separate from resetting a temporary world.
            """.stripTrailing()),
        Map.entry("automaticUnloadIdleSeconds", """
            Idle delay in seconds for dimensions inheriting global automatic unloading.
            300 = five minutes; 900 = fifteen minutes; 3600 = one hour.
            Example: automaticUnloadIdleSeconds = 900
            Explicit lifecycle.idle_seconds in dimension JSON overrides this value. Temporary worlds use their local timeout.
            """.stripTrailing()),
        Map.entry("monitoringEnabled", """
            Record dimension-monitoring history. Current diagnostics remain available when recording is disabled.
            Example for global recording: monitoringEnabled = true
            Individual dimensions can override whether history is recorded.
            """.stripTrailing()),
        Map.entry("monitoringPolicy", """
            Optional ID of a defined reusable monitoring policy. Empty uses five-second samples, seven-day history and hourly disk scans.
            Example: monitoringPolicy = "example:standard"
            The referenced policy must exist in the selected JED data or a datapack; this is not a browser URL or network port.
            """.stripTrailing()),
        Map.entry("dimensionSyncTimeoutSeconds", """
            Maximum real-time seconds for unfinished JED dimension-registry synchronization.
            600 = ten minutes; 1200 = twenty minutes. 0 disables only this JED deadline.
            Example: dimensionSyncTimeoutSeconds = 1200
            Minecraft's own connection/keep-alive rules still apply. Replacement snapshots and keep-alives do not restart this deadline.
            Changes apply to the next synchronization wait.
            """.stripTrailing()),
        Map.entry("instanceRegisteredLimit", """
            Maximum instance identities registered during one server session, including retired identities.
            Example: instanceRegisteredLimit = 256 permits up to 256 registered identities, not 256 simultaneously loaded worlds.
            Restarting the server starts a new session; instanceLoadedLimit controls currently loaded worlds separately.
            """.stripTrailing()),
        Map.entry("instanceLoadedLimit", """
            Maximum simultaneously loaded instance worlds. Instance policies may impose stricter caps.
            Example: instanceLoadedLimit = 16 allows sixteen loaded instances; this is not a limit of sixteen saved instance worlds.
            """.stripTrailing()),
        Map.entry("pregenerationConcurrentJobs", """
            Maximum JED-managed pregeneration jobs generating at the same time.
            1 runs one job at a time; 2 allows two jobs concurrently.
            Example: pregenerationConcurrentJobs = 2
            More simultaneous jobs share server CPU and disk work; this setting does not change the configured generation area.
            """.stripTrailing()),
        Map.entry("enableOverrideWorldProvider", """
            Allow JED provider policies on existing vanilla/mod dimensions. New JED dimensions are unaffected.
            """.stripTrailing()),
        Map.entry("enableOverrideBiomeProvider", """
            Allow JED JSON to change biome sources of existing vanilla/mod dimensions. New JED dimensions are unaffected. Changes apply on restart.
            """.stripTrailing()),
        Map.entry("enableLoggingInfo", """
            Log routine JED operation information. Required conflict, warning and error reports are independent.
            """.stripTrailing()),
        Map.entry("world_preset", """
            World-generation preset used when creating a new world. Leave empty to use Minecraft's selected preset.
            Examples:
            world_preset = "minecraft:large_biomes"
            world_preset = "minecraft:amplified"
            Existing saves keep their generation settings; explicit dimension JSON takes precedence over preset dimensions.
            """.stripTrailing()),
        Map.entry("tpjAdminOnly", """
            Require operator permission for /tpj. This is server controlled and synchronized to clients.
            Operator-only example: tpjAdminOnly = true with tpjAdminLevel = 2
            Ordinary-player example: tpjAdminOnly = false
            Dimension access rules, travel restrictions and instance permissions still apply independently.
            """.stripTrailing()),
        Map.entry("tpjAdminLevel", """
            Minimum native permission level for /tpj when tpjAdminOnly is true.
            Example: tpjAdminLevel = 2
            Accepted levels are 0 through 4; level 0 does not require operator privileges.
            This controls command authorization, not automatic bypass of a dimension's entry requirements.
            """.stripTrailing()),
        Map.entry("enableSeparateWorldInfo", """
            Independent state for dimensions with a state object; requires reopening the world to change.
            """.stripTrailing()),
        Map.entry("enableSeparateWorldBorders", """
            Keep dimension borders separate; requires reopening the world to change.
            """.stripTrailing()),
        Map.entry("enableCommandRedirecting", """
            Use scoped world commands for command sources in independent dimensions.
            """.stripTrailing()),
        Map.entry("initial_spawn_dimension", """
            Dimension for genuinely new players' initial placement. Leave empty for native placement.
            Use a full registered dimension ID; this does not create the dimension.
            Example: initial_spawn_dimension = "example:starting_island"
            Existing players are not relocated by changing this setting.
            Spawn-area retention is separate: it follows each dimension's spawnChunkRadius gamerule unless spawn_chunks.radius overrides it in dimension JSON.
            Larger spawnChunkRadius values increase RAM and CPU processing requirements. Setting the radius to 0 disables spawn retention.
            Set the gamerule in state.initial.gamerules or state.on_load.gamerules; keep_loaded enables spawn retention unless explicitly opted out.
            """.stripTrailing()),
        Map.entry("enablePlayerInventoryGroups", """
            Enable inventory groups, including equipment, Ender Chest and supported accessory backends.
            """.stripTrailing()),
        Map.entry("enableForcedGameModes", """
            Enable dimension player.force_game_mode policies. Each dimension supplies its own default game mode.
            Example: enableForcedGameModes = true allows a configured Creative hub and Survival arena.
            normalGameMode supplies the initial exit mode only for new players starting in a forced-mode dimension.
            """.stripTrailing()),
        Map.entry("normalGameMode", """
            Normal game mode for a genuinely new player whose first dimension forces another mode.
            Accepted names: "survival", "creative", "adventure", "spectator".
            Example: normalGameMode = "survival" lets a new player leave a forced-Creative starting hub in Survival.
            Existing players return to their saved normal mode. This does not choose every dimension's default mode.
            """.stripTrailing()),
        Map.entry("config_id", """
            Pack identity for copied main TOML upgrades. This is separate from JSON format_version.
            Example: config_id = "my_modpack"
            Keep the same identity for updates to that pack. A different identity does not replace an existing save copy.
            Dimension-JSON upgrades use config_id/config_version in the separate manifest.json.
            """.stripTrailing()),
        Map.entry("copyMainConfigToWorld", """
            Copy the main TOML into a save when missing, or when the same config_id has a higher config_version.
            The save copy is <save>/serverconfig/justepicdimensions/justepicdimensions.toml.
            This does not select that copy for use; usePerWorldMainConfig controls selection.
            """.stripTrailing()),
        Map.entry("usePerWorldMainConfig", """
            Select the save's main TOML when available. false selects the global main TOML.
            Example for independent save settings:
            copyMainConfigToWorld = true
            usePerWorldMainConfig = true
            Copy/use choices for the main TOML are independent of the dimension-JSON choices.
            """.stripTrailing()),
        Map.entry("config_version", """
            Version of this pack's main TOML configuration, not the JED mod version or JSON format version.
            Example: release 1 uses config_version = 1; an updated configuration uses config_version = 2.
            With copying enabled, an existing save copy is replaced only when config_id matches and this version is higher.
            """.stripTrailing()),
        Map.entry("globalInventoryItems", """
            Items retained through dimension inventory swaps and loadout replacement.
            Covers hotbar, main inventory, offhand, armor and supported equipped accessory/cosmetic slots, including Curios.
            Ender Chest and items inside nested storage are separate.
            Use full item registry IDs, one per line. All variants/components of a listed item ID match.
            This preserves items the player already has; it does not grant items or protect them from normal death/grave rules.
            The dimension's Remove Global Inventory Items override only applies during an actual inventory swap.
            Loadout equipment takes priority in occupied slots; retained items use free carried space. If none fits, travel stops.
            An empty list designates no global items. IDs from absent mods simply do not match any items.
            The default retains only the registered JED guide book: "justepicdimensions:guide_book".
            Ordinary "minecraft:written_book" items are not retained unless you add that ID yourself.
            Example:
            globalInventoryItems = [
                "example:dimension_key",
                "example:guide_book"
            ]
            """.stripTrailing())
    );
    private static final Map<String,String> CLIENT = Map.ofEntries(
        Map.entry("enableColorOverrides", """
            Display JED grass, foliage and water colors on this client.
            Example: enableColorOverrides = false displays ordinary biome colors instead.
            This changes appearance on this client, not the server's blocks or biome storage.
            """.stripTrailing()),
        Map.entry("client_light_verification.positions_per_tick", """
            Maximum block positions examined by JED light verification per client tick, across all tracked chunks.
            The default 512 means up to 512 examined positions per tick. Server policy determines coverage.
            This bounds JED's checking work; it does not set the brightness of a dimension.
            """.stripTrailing()),
        Map.entry("client_light_verification.submissions_per_tick", """
            Maximum native light-check submissions made by JED per client tick, including adjacent emitters.
            The default 512 caps submissions, not examined positions; the two limits are separate.
            """.stripTrailing()),
        Map.entry("client_light_verification.own_time_ms", """
            Soft time budget, in milliseconds per client tick, for JED scanning and check submissions.
            The default 0.5 means half a millisecond.
            Example: own_time_ms = 0.5
            Time spent in Minecraft's subsequent native light propagation cannot be capped here.
            """.stripTrailing()),
        Map.entry("client_light_verification.drain_pressure_ms", """
            Native-light drain pressure threshold, in milliseconds, used by JED's feedback control.
            The default 2.0 means two milliseconds. This is separate from JED's own_time_ms scanner budget.
            Example: drain_pressure_ms = 2.0
            """.stripTrailing())
    );
    public static String text(String key,boolean client){
        return Objects.requireNonNull((client?CLIENT:MAIN).get(key),"Missing TOML explanation: "+key);
    }
    public static void populateMissing(CommentedConfig config,boolean client){
        for(var field:JEDConfigFields.fields(client)){
            var previous=config.getComment(field.key());
            if(previous==null||previous.isBlank())config.setComment(field.key(),text(field.key(),client)+"\nDefault: "+field.defaultValue());
        }
    }
    private JEDConfigComments(){}
}
