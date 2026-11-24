package justepicdimensions.world;

import com.mojang.serialization.Dynamic;
import java.util.UUID;
import justepicdimensions.definition.JEDDefinitionException;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.timers.*;

/** Independent native data owner, selected before ServerLevel initializes its final data fields. */
public final class JEDLevelData implements ServerLevelData {
    private BlockPos spawn;
    private float spawnAngle,dayFraction,dayRate;
    private long time,day;
    private String name;
    private GameType gameType;
    private Difficulty difficulty;
    private boolean hardcore,commands,initialized,difficultyLocked,raining,thundering;
    private int clearWeather,rainTime,thunderTime,traderDelay,traderChance;
    private UUID trader;
    private GameRules rules;
    private WorldBorder.Settings border;
    private TimerQueue<MinecraftServer> scheduled;

    private JEDLevelData() {}
    public void applyPatch(CompoundTag patch) {
        var merged=save();merged.merge(patch);var data=read(merged);
        spawn=data.spawn;spawnAngle=data.spawnAngle;dayFraction=data.dayFraction;dayRate=data.dayRate;time=data.time;day=data.day;name=data.name;gameType=data.gameType;difficulty=data.difficulty;hardcore=data.hardcore;commands=data.commands;initialized=data.initialized;difficultyLocked=data.difficultyLocked;raining=data.raining;thundering=data.thundering;clearWeather=data.clearWeather;rainTime=data.rainTime;thunderTime=data.thunderTime;traderDelay=data.traderDelay;traderChance=data.traderChance;trader=data.trader;rules=data.rules;border=data.border;scheduled=data.scheduled;
    }
    public void clearScheduledEvents(){scheduled=new TimerQueue<>(TimerCallbacks.SERVER_CALLBACKS);}
    public static JEDLevelData copyOf(ServerLevelData source) {
        var data=new JEDLevelData();data.spawn=source.getSpawnPos().immutable();data.spawnAngle=source.getSpawnAngle();
        data.time=source.getGameTime();data.day=source.getDayTime();data.dayFraction=source.getDayTimeFraction();data.dayRate=source.getDayTimePerTick();
        data.name=source.getLevelName();data.gameType=source.getGameType();data.difficulty=source.getDifficulty();data.hardcore=source.isHardcore();data.commands=source.isAllowCommands();data.initialized=source.isInitialized();data.difficultyLocked=source.isDifficultyLocked();
        data.raining=source.isRaining();data.thundering=source.isThundering();data.clearWeather=source.getClearWeatherTime();data.rainTime=source.getRainTime();data.thunderTime=source.getThunderTime();
        data.traderDelay=source.getWanderingTraderSpawnDelay();data.traderChance=source.getWanderingTraderSpawnChance();data.trader=source.getWanderingTraderId();
        data.rules=source.getGameRules().copy();data.border=source.getWorldBorder();
        data.scheduled=new TimerQueue<>(TimerCallbacks.SERVER_CALLBACKS,new Dynamic<>(NbtOps.INSTANCE,source.getScheduledEvents().store()).asStream());
        return data;
    }
    public CompoundTag save() {
        var tag=new CompoundTag();tag.putInt("SpawnX",spawn.getX());tag.putInt("SpawnY",spawn.getY());tag.putInt("SpawnZ",spawn.getZ());tag.putFloat("SpawnAngle",spawnAngle);
        tag.putLong("Time",time);tag.putLong("DayTime",day);tag.putFloat("neoDayTimeFraction",dayFraction);tag.putFloat("neoDayTimePerTick",dayRate);
        tag.putString("LevelName",name);tag.putInt("GameType",gameType.getId());tag.putByte("Difficulty",(byte)difficulty.getId());tag.putBoolean("DifficultyLocked",difficultyLocked);
        tag.putBoolean("hardcore",hardcore);tag.putBoolean("allowCommands",commands);tag.putBoolean("initialized",initialized);
        tag.putBoolean("raining",raining);tag.putBoolean("thundering",thundering);tag.putInt("clearWeatherTime",clearWeather);tag.putInt("rainTime",rainTime);tag.putInt("thunderTime",thunderTime);
        tag.putInt("WanderingTraderSpawnDelay",traderDelay);tag.putInt("WanderingTraderSpawnChance",traderChance);if(trader!=null)tag.putUUID("WanderingTraderId",trader);
        tag.put("GameRules",rules.createTag());border.write(tag);tag.put("ScheduledEvents",scheduled.store());return tag;
    }
    public static JEDLevelData read(CompoundTag tag) {
        if(!tag.contains("LevelName",Tag.TAG_STRING)||!tag.contains("GameRules",Tag.TAG_COMPOUND)||!tag.contains("ScheduledEvents",Tag.TAG_LIST))throw new JEDDefinitionException("Incomplete JED level-state data");
        int mode=tag.getInt("GameType"),difficulty=tag.getByte("Difficulty");
        if(mode<0||mode>3||difficulty<0||difficulty>3)throw new JEDDefinitionException("Invalid JED level-state mode/difficulty");
        var data=new JEDLevelData();data.spawn=new BlockPos(tag.getInt("SpawnX"),tag.getInt("SpawnY"),tag.getInt("SpawnZ"));data.spawnAngle=tag.getFloat("SpawnAngle");
        data.time=tag.getLong("Time");data.day=tag.getLong("DayTime");data.dayFraction=tag.getFloat("neoDayTimeFraction");data.dayRate=tag.getFloat("neoDayTimePerTick");
        if(!Float.isFinite(data.spawnAngle)||!Float.isFinite(data.dayFraction)||!Float.isFinite(data.dayRate))throw new JEDDefinitionException("Non-finite JED level-state value");
        data.name=tag.getString("LevelName");data.gameType=GameType.byId(mode);data.difficulty=Difficulty.byId(difficulty);data.difficultyLocked=tag.getBoolean("DifficultyLocked");data.hardcore=tag.getBoolean("hardcore");data.commands=tag.getBoolean("allowCommands");data.initialized=tag.getBoolean("initialized");
        data.raining=tag.getBoolean("raining");data.thundering=tag.getBoolean("thundering");data.clearWeather=tag.getInt("clearWeatherTime");data.rainTime=tag.getInt("rainTime");data.thunderTime=tag.getInt("thunderTime");
        data.traderDelay=tag.getInt("WanderingTraderSpawnDelay");data.traderChance=tag.getInt("WanderingTraderSpawnChance");data.trader=tag.hasUUID("WanderingTraderId")?tag.getUUID("WanderingTraderId"):null;
        data.rules=new GameRules(new Dynamic<>(NbtOps.INSTANCE,tag.getCompound("GameRules")));data.border=WorldBorder.Settings.read(new Dynamic<>(NbtOps.INSTANCE,tag),WorldBorder.DEFAULT_SETTINGS);
        data.scheduled=new TimerQueue<>(TimerCallbacks.SERVER_CALLBACKS,new Dynamic<>(NbtOps.INSTANCE,tag.getList("ScheduledEvents",Tag.TAG_COMPOUND)).asStream());return data;
    }
    public BlockPos getSpawnPos(){return spawn;} public float getSpawnAngle(){return spawnAngle;} public void setSpawn(BlockPos pos,float angle){spawn=pos.immutable();spawnAngle=angle;}
    public long getGameTime(){return time;} public void setGameTime(long value){time=value;} public long getDayTime(){return day;} public void setDayTime(long value){day=value;}
    public float getDayTimeFraction(){return dayFraction;} public void setDayTimeFraction(float value){dayFraction=value;} public float getDayTimePerTick(){return dayRate;} public void setDayTimePerTick(float value){dayRate=value;}
    public String getLevelName(){return name;} public void setLevelName(String value){name=value;}
    public GameType getGameType(){return gameType;} public void setGameType(GameType value){gameType=value;}
    public Difficulty getDifficulty(){return difficulty;} public void setDifficulty(Difficulty value){difficulty=value;} public boolean isDifficultyLocked(){return difficultyLocked;} public void setDifficultyLocked(boolean value){difficultyLocked=value;}
    public boolean isHardcore(){return hardcore;} public void setHardcore(boolean value){hardcore=value;} public boolean isAllowCommands(){return commands;} public void setAllowCommands(boolean value){commands=value;}
    public boolean isInitialized(){return initialized;} public void setInitialized(boolean value){initialized=value;}
    public GameRules getGameRules(){return rules;} public WorldBorder.Settings getWorldBorder(){return border;} public void setWorldBorder(WorldBorder.Settings value){border=value;}
    public boolean isRaining(){return raining;} public void setRaining(boolean value){raining=value;} public boolean isThundering(){return thundering;} public void setThundering(boolean value){thundering=value;}
    public int getClearWeatherTime(){return clearWeather;} public void setClearWeatherTime(int value){clearWeather=value;} public int getRainTime(){return rainTime;} public void setRainTime(int value){rainTime=value;} public int getThunderTime(){return thunderTime;} public void setThunderTime(int value){thunderTime=value;}
    public int getWanderingTraderSpawnDelay(){return traderDelay;} public void setWanderingTraderSpawnDelay(int value){traderDelay=value;} public int getWanderingTraderSpawnChance(){return traderChance;} public void setWanderingTraderSpawnChance(int value){traderChance=value;} public UUID getWanderingTraderId(){return trader;} public void setWanderingTraderId(UUID value){trader=value;}
    public TimerQueue<MinecraftServer> getScheduledEvents(){return scheduled;}
}
