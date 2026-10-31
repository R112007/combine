package combine.util;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.PowerGraphUpdater;
import mindustry.world.blocks.power.PowerGraph;

import static mindustry.Vars.world;

/**
 * 组合体和电网的对账器 —— 修「装了 combine 之后，每次读写地图都要给电网一点刺激
 * （比如拆掉一个并网建筑）才会重新接上电」。
 *
 * 【根因】组合体（组合工厂/发电机/单位工厂/…… 以及协作组合）在组内**共用同一份
 * PowerModule**（`member.power = leader.power`，这样"任一成员接电 = 整组通电"）。
 * 原版 {@code PowerGraph} 假设"一台建筑一份模块"：
 * <ul>
 *   <li>{@code PowerGraph.addGraph()} 会把并进来的那张图的 updater 实体删掉；</li>
 *   <li>{@code PowerGraph.remove()} 的扇形拆分按 {@code power.graph != this} 跳过分支 ——
 *       共用一份模块的建筑里，只要有一台被拆走，同组其它建筑的 {@code power.graph}
 *       会被这一次赋值顺手改掉，后面那些分支就被整段跳过。</li>
 * </ul>
 * 于是读档/拆节点/拆成员之后，总会有那么一两台组合建筑被留在**一张没人更新的空电网**里
 * （`all` 里没有自己、也没有 updater 实体）：它的 `power.status` 再也不刷新，
 * 在游戏里就是"这台机器没电、要动一下电网才活过来"。
 *
 * 【办法】不跟原版较劲，改成事后对账：模块被换过 / 组被拆过 / 读过档的建筑先登记，
 * 每帧收口时检查它那张电网是不是"活的"（有 updater 实体）。不活就按它当前的
 * 邻接与连线整张重划一次（{@code new PowerGraph().reflow(b)}）—— 这正是原版自己在
 * 更换/断开线路后用的那套重建方式，会把分量里的每一台重新登记进新电网。
 *
 * 只对"确实掉了"的建筑动手：健康的分量一动不动，不产生额外开销。
 */
public class ComboPower {
  /** 待对账的建筑（模块换过 / 组拆过 / 从存档读回来的）。 */
  private static final ObjectSet<Building> pending = new ObjectSet<>();
  /** 读档之后整张图都要对账一次。 */
  private static boolean fullPass = false;
  /** 兜底巡检：每 N 帧把世界里的建筑过一遍，捡漏（原版某些拆除路径不会给我们任何钩子）。 */
  private static int sweepCounter = 0;
  /**
   * 正常增删/拆组都有事件精确登记；兜底巡检只是防漏，不需要 5 帧一次。
   * 30 帧（0.5 秒）在 3 万建筑的进攻图上能把常驻开销降低约 6 倍。
   */
  public static int sweepInterval = 30;

  /** 这台组合建筑的电力模块可能掉队了：下一帧对账一次。 */
  public static void mark(Building b) {
    if (b != null) {
      // 【为什么是"整张图对账"而不是只查这一台】原版的拆网扇形在共用模块时会把分支整段跳过，
      // 掉队的可能是旁边那台原版的发电机/电线杆，只查登记过的那几台会漏掉它。
      fullPass = true;
    }
  }

  /**
   * 这台建筑**和它电网上的邻居**都登记一遍。
   *
   * 原版的拆网扇形是"从这个点向各个邻居分支走"的：共用一份 PowerModule 时后续分支会被跳过，
   * 掉队的建筑可能散落到任意一支里（包括旁边的原版发电机/电线杆）。把邻居一起登记，
   * 收口时每个分支都能被查到。
   */
  public static void markAround(Building b) {
    if (b == null)
      return;
    fullPass = true;
    try {
      Seq<Building> tmp = new Seq<>();
      for (Building nb : b.getPowerConnections(tmp)) {
        if (nb != null)
          pending.add(nb);
      }
    } catch (Throwable ignored) {
    }
  }

  /** 一批建筑（例如整个组）。 */
  public static void markAll(Iterable<? extends Building> list) {
    if (list == null)
      return;
    for (Building b : list)
      mark(b);
  }

  /** 读档：下一帧整张图对账一次。 */
  public static void markWorld() {
    fullPass = true;
  }

  /** 在 Main.init() 里注册一次：每帧（Trigger.update）收口。 */
  public static void register() {
    Events.run(EventType.Trigger.update, ComboPower::flush);
  }

  private static void flush() {
    boolean sweep = ++sweepCounter >= Math.max(sweepInterval, 1);
    if (sweep)
      sweepCounter = 0;
    if (!fullPass && pending.isEmpty() && !sweep)
      return;
    try {
      Seq<Building> targets = new Seq<>();
      // "只玩家组合"打开时，AI 敌人的建筑不参与组合、也不需要模组替它们对账电网 ——
      // 大型进攻图上有几万台敌方建筑，全收进来就是一次几十毫秒的卡顿（用户报"进去非常卡"）。
      boolean playersOnly = ComboTeams.playersOnly;
      if (fullPass || sweep) {
        boolean full = fullPass;
        fullPass = false;
        // 电网只存在于会更新的建筑上；不用扫 262k 个 Tile。
        // 兜底与整图对账共用这份名单，避免大图首帧/每 0.5 秒多走一遍全地表格。
        for (Building b : Groups.build) {
          if (b != null && b.isValid() && b.power != null
              && (!playersOnly || ComboTeams.playerTeam(b.team)))
            targets.add(b);
        }
        if (!full && targets.isEmpty())
          return;
      }
      for (Building b : pending) {
        if (b != null && b.power != null && ComboReflect.inWorld(b) && !targets.contains(b, true))
          targets.add(b);
      }
      pending.clear();
      if (targets.isEmpty())
        return;

      // 同一份 PowerModule 会被整组共用：按模块分组，用来判断"这根线对面到底连着谁"。
      ObjectMap<mindustry.world.modules.PowerModule, Seq<Building>> byModule = new ObjectMap<>();
      for (Building b : targets)
        byModule.get(b.power, Seq::new).add(b);

      // ---- 1) 清掉失效/单向连线 ----
      // 组合体的模块会在组内搬来搬去（换组长、拆组、读档），原版那套"双向维护 links"的假设
      // 就会被破坏：某台机器手里留着一根"对面根本不指着它"的线 —— 它自己以为接在电网上
      // （getPowerConnections 把 links 当连接），实际对面那张电网跟它没关系。
      boolean linksChanged = false;
      for (Building b : targets) {
        mindustry.world.modules.PowerModule m = b.power;
        for (int i = m.links.size - 1; i >= 0; i--) {
          int link = m.links.get(i);
          Building other = world == null ? null : world.build(link);
          boolean keep = other != null && other.isValid() && other.power != null && reciprocated(m, other.power, byModule);
          if (!keep) {
            m.links.removeIndex(i);
            linksChanged = true;
          }
        }
      }

      // ---- 2) 逐"电网分量"检查：分量里只要有一台指到别的图（或指到没人更新的旧图），整张重划 ----
      ObjectSet<PowerGraph> live = new ObjectSet<>();
      for (var e : Groups.powerGraph) {
        if (e instanceof PowerGraphUpdater u && u.graph != null)
          live.add(u.graph);
      }
      ObjectSet<Building> checked = new ObjectSet<>();
      Seq<Building> comp = new Seq<>();
      Queue<Building> queue = new Queue<>();
      Seq<Building> conns = new Seq<>();
      for (Building seed : targets) {
        if (seed == null || seed.power == null || !ComboReflect.inWorld(seed) || checked.contains(seed))
          continue;
        comp.clear();
        queue.clear();
        queue.addLast(seed);
        checked.add(seed);
        PowerGraph expect = seed.power.graph;
        boolean broken = linksChanged || expect == null || !live.contains(expect);
        while (!queue.isEmpty()) {
          Building cur = queue.removeFirst();
          comp.add(cur);
          if (!broken && (cur.power.graph == null || !live.contains(cur.power.graph) || cur.power.graph != expect))
            broken = true;
          for (Building nb : cur.getPowerConnections(conns)) {
            if (nb != null && nb.power != null && checked.add(nb))
              queue.addLast(nb);
          }
        }
        // 掉队了：按当前邻接/连线把整张分量重划一次（原版换线/断线后用的也是这招）
        if (broken)
          new PowerGraph().reflow(comp.first());
      }
    } catch (Throwable ignored) {
      // 对账失败不影响游戏：下一帧还有机会
    }
  }

  /** 两个模块之间还有没有"互相指着"的连线（同一模块的组员都算）。 */
  private static boolean reciprocated(mindustry.world.modules.PowerModule self, mindustry.world.modules.PowerModule other,
                                     ObjectMap<mindustry.world.modules.PowerModule, Seq<Building>> byModule) {
    // 对面（或与它共用模块的组员）有没有指着本侧的任何一台
    Seq<Building> mine = byModule.get(self);
    if (mine == null)
      return false;
    for (int i = 0; i < mine.size; i++) {
      Building b = mine.get(i);
      if (b != null && other.links.contains(b.pos()))
        return true;
    }
    return false;
  }

  /**
   * 从"整组共用一份 PowerModule"里给离组成员分出独立模块时，别把整组的连线全抄过去。
   *
   * 共用模块里的 links 是**全组**的连线（组长自己的 + 各成员并进来的）。直接
   * {@code links.addAll(...)} 会让离组成员凭空多出一堆"对面并不指向它"的单向连线：
   * 它自己以为还接在那张电网上（{@code getPowerConnections()} 把 links 当连接），
   * 可对面那台（原版电线杆/电源）的模块里指的是组里别的成员 —— 两边分属不同电网，
   * 表现出来同样是"这台机器没电，动一下电网才连上"。
   *
   * 这里只保留**对面确实指着本台**的那些连线（原版连线本来就是双向维护的）。
   */
  public static void copyOwnLinks(mindustry.world.modules.PowerModule src, Building self,
                                  mindustry.world.modules.PowerModule dst) {
    if (src == null || dst == null || self == null)
      return;
    try {
      for (int i = 0; i < src.links.size; i++) {
        int link = src.links.get(i);
        Building other = world == null ? null : world.build(link);
        if (other == null || !other.isValid() || other.power == null || other == self)
          continue;
        // 对面指着本台 = 这根线是本台的；指着组里别的成员 = 那是别人的线
        if (other.power.links.contains(self.pos()))
          dst.links.addUnique(link);
      }
    } catch (Throwable ignored) {
    }
  }

}
