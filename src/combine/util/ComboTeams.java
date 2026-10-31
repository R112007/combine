package combine.util;

import arc.Core;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Player;

/**
 * "只和玩家队友组合"总开关（设置界面里的复选框）。
 *
 * <p>打开之后：**只有玩家队伍（本地玩家/联机队友）的建筑才互相组合**，
 * AI 敌人（crux/malis 这类没有玩家的队伍）的建筑之间不再合并库存/火力/血量。
 * 关掉就是老行为（谁跟谁贴一起都能组合）。
 *
 * <p>判据用"这个队伍里有没有玩家"（{@link Groups#player}），单机/联机/专用服务器都成立：
 * 单机只有本地玩家那一个队伍有 Player，AI 队伍一个 Player 都没有。
 */
public class ComboTeams {
  public static final String KEY = "combine-only-player-teams";
  public static final String KEY_ENABLED = "combine-enabled";
  public static boolean playersOnly = false;
  /**
   * 组合总开关（设置里那个按钮）：关掉之后**所有**组合建筑 —— 包括玩家自己队伍的 ——
   * 都按原版跑，物品/液体/电力/容量都不再并池。
   *
   * <p>和"只和玩家队友组合"是两回事：那个只是不管 AI 敌人的建筑，玩家队伍照常组合。
   */
  public static boolean enabled = true;
  /** 每 tick 缓存"队伍里是否有玩家"；大型基地一次更新会问上万次，结果只取决于玩家列表。 */
  private static final byte[] teamPlayerCache = new byte[256];
  private static long teamPlayerCacheTick = Long.MIN_VALUE;

  public static void load() {
    try {
      playersOnly = Core.settings.getBool(KEY, false);
      enabled = Core.settings.getBool(KEY_ENABLED, true);
      ComboReflect.clearSoloCache();
      clearTeamPlayerCache();
    } catch (Throwable ignored) {
    }
  }

  /** 设置界面用：组合总开关。关掉时把已经并在一起的组合体立刻拆成"每台各一份"。 */
  public static void setEnabled(boolean value) {
    enabled = value;
    ComboReflect.clearSoloCache();
    clearTeamPlayerCache();
    if (!value)
      detachEverything("组合总开关关闭");
    else
      rejoin();
    try {
      Core.settings.put(KEY_ENABLED, value);
    } catch (Throwable ignored) {
    }
  }

  /**
   * 关掉自动组合时把**已经并在一起**的东西立刻拆回"每台各一份"：
   * 物品/液体按容量比例分（总量不变），容量/导电性还原，协作组合也一并脱离。
   */
  private static void detachEverything(String reason) {
    try {
      combine.net.ComboNet.invalidateTracked();
    } catch (Throwable ignored) {
    }
    try {
      // 【不能只关闸】关掉的那一刻基地里可能已经并了一大片：模块还共用着，
      // 不主动拆的话玩家看到的是"开关关了但东西还是共享的"。按容量比例拆回每台一份。
      try {
        combine.net.ComboNet.detachAllCombos();
      } catch (Throwable ignored) {
      }
      // 协作组合（mod 方块相邻并池）也不该再共享：资格判定里已经关了，
      // 这里叫它重算一遍，把还在共享的方块按容量拆开、容量/导电性还原。
      try {
        combine.coop.CoopCombo.clearEligibilityCache();
        combine.coop.CoopCombo.markDirty();
        combine.coop.CoopCombo.rebuild();
      } catch (Throwable ignored) {
      }
      // 【本地分组也要重算】各组合建筑手里的 comboGroup/comboLeader 是上一次分组的结果：
      // 只把池子拆开、不置脏的话，它们的 group() 里还挂着别的成员（容量/搬运/面板都会错）。
      markAllCombosDirty();
    } finally {
      Log.info("[combine] @：已把组合体拆成各自独立（相邻自动组合不再生效）", reason);
    }
  }

  /** 重新打开时：下一轮重算把该并的并回去。 */
  private static void rejoin() {
    try {
      combine.net.ComboNet.invalidateTracked();
    } catch (Throwable ignored) {
    }
    try {
      combine.coop.CoopCombo.clearEligibilityCache();
    } catch (Throwable ignored) {
    }
    try {
      combine.net.ComboNet.markDirty(); // 重新打开：下一轮重算把该并的并回去
      combine.coop.CoopCombo.markDirty();
    } catch (Throwable ignored) {
    }
    // 重新打开时同理：不置脏的话每台手里的 comboGroup 还是"只有自己"，永远并不回去
    // （用户报的"关掉再打开，相邻的建筑不再并池了"）。
    markAllCombosDirty();
  }

  /** 把全世界组合建筑的本地分组标脏，让下一帧各自重算（开/关自动组合时各叫一次）。 */
  private static void markAllCombosDirty() {
    try {
      for (Building b : combine.net.ComboNet.allComboBuildings())
        combine.util.ComboReflect.setDirty(b, true);
    } catch (Throwable ignored) {
    }
  }

  public static void set(boolean value) {
    playersOnly = value;
    ComboReflect.clearSoloCache();
    clearTeamPlayerCache();
    // 【性能】"组合相关建筑"登记表是按这个开关过滤过后存的，开关一变就得重扫
    //（否则打开"只和玩家队友组合"之后，名单里还留着敌人的建筑）。
    try {
      combine.net.ComboNet.invalidateTracked();
    } catch (Throwable ignored) {
    }
    try {
      Core.settings.put(KEY, value);
    } catch (Throwable ignored) {
    }
  }

  /**
   * 「相邻自动组合」开着吗（= 组合总开关开着）。
   *
   * <p>【合体建筑已经独立到 merge 项目】本项目现在只有"相邻自动组合"这一半，
   * 所以这个判定就是总开关本身。框选出来的超级炮台 / 合体工厂在 merge 里，
   * 它们不看这个开关（那是玩家显式框选出来的，不是"附近有同类建筑就自动并"）。
   */
  public static boolean autoCombine() {
    return enabled;
  }

  /** 相邻自动组合开着时返回 true —— 关掉（总开关/仅超级组合）时返回 false。 */
  public static boolean autoCombine(Building b) {
    return autoCombine() && b != null && playerTeamRaw(b.team);
  }

  /** 这个队伍算"玩家队伍"吗（总开关关着时恒为 true = 不管）。 */
  public static boolean playerTeam(Team team) {
    // 相邻自动组合关掉（总开关 / 仅超级组合）：谁的建筑都不参与自动组合（含玩家队伍）
    if (!autoCombine())
      return false;
    return playerTeamRaw(team);
  }

  /** 只看"队伍里有没有玩家"，不看自动组合开关。 */
  public static boolean playerTeamRaw(Team team) {
    if (team == null)
      return false;
    boolean attackMode = false;
    try {
      attackMode = Vars.state != null && Vars.state.rules != null && Vars.state.rules.attackMode;
    } catch (Throwable ignored) {
    }
    // 战役进攻图上的敌方基地是成千上万台 AI 建筑。让它们自动组合既没有玩法收益，
    // 又会在每次敌重建时触发全图网络重算（planetaryTerminal 单次 42ms）。
    // 进攻模式默认只组合玩家队；普通生存/PvP 仍尊重原设置。
    if (!playersOnly && !attackMode)
      return true;
    long tick = Vars.state == null ? Long.MIN_VALUE : Vars.state.updateId;
    if (tick != Long.MIN_VALUE && tick != teamPlayerCacheTick) {
      for (int i = 0; i < teamPlayerCache.length; i++) teamPlayerCache[i] = 0;
      teamPlayerCacheTick = tick;
    }
    if (tick != Long.MIN_VALUE && team.id >= 0 && team.id < teamPlayerCache.length) {
      byte cached = teamPlayerCache[team.id];
      if (cached != 0) return cached == 1;
    }
    try {
      if (Vars.player != null && Vars.player.team() == team)
        return cacheTeamPlayer(team, true);
      for (Player p : Groups.player)
        if (p != null && p.team() == team)
          return cacheTeamPlayer(team, true);
    } catch (Throwable ignored) {
    }
    if (attackMode)
      return cacheTeamPlayer(team, Vars.state.rules.defaultTeam == team);
    // 一个玩家都没有的场合（无头测试 / 服务器刚开还没人）：把"默认队伍"当玩家队伍，
    // 其余队伍（crux/malis 这类 AI）一律不算 —— 有玩家时上面的判断先命中，不受影响。
    try {
      return cacheTeamPlayer(team, Vars.headless && Vars.state != null && Vars.state.rules != null
          && team == Vars.state.rules.defaultTeam);
    } catch (Throwable ignored) {
    }
    return cacheTeamPlayer(team, false);
  }

  private static boolean cacheTeamPlayer(Team team, boolean value) {
    if (team != null && team.id >= 0 && team.id < teamPlayerCache.length)
      teamPlayerCache[team.id] = value ? (byte)1 : (byte)2;
    return value;
  }

  public static void clearTeamPlayerCache() {
    for (int i = 0; i < teamPlayerCache.length; i++) teamPlayerCache[i] = 0;
    teamPlayerCacheTick = Long.MIN_VALUE;
  }

  /** 这两台建筑能不能组合成一组（总开关关着时只看同队）。 */
  public static boolean allowCombine(Building a, Building b) {
    if (a == null || b == null || a.team != b.team)
      return false;
    return playerTeam(a.team);
  }
}
