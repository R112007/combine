package combine.storage;
import combine.Main;
import combine.net.ComboNet;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.Events;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.ObjectIntMap;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.modules.ItemModule;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * 组合仓库（container / vault，及它们的强化版）。
 *
 * 并仓机制全部收在本类里，两条规则：
 *
 * 1) 没连核心 —— 跟本模组其它组合建筑一样：相邻成组的仓库**共用一个物品模块**，
 * 组容量 = 各成员容量之和（每个成员 getMaximumAccepted 都返回组容量）。
 * 拆开时按容量比例把池子分开，不丢物品。
 *
 * 2) 连通到核心 —— 原版规则（CoreBlock.CoreBuild.onProximityUpdate）的传递闭包：
 * 核心容量 = 自身 + **所有连通**的仓库容量，这些仓库也并进核心的库存模块。
 * 原版只认"直接相邻"一层；这里按 proximity 洪水填充，任意深度都算
 * （a 连 b、b 连核心 ⇒ a 也算）。
 * 断开（拆掉中间那个）时：分离出去的仓库库存早已在核心池里，所以只解链、给它一份
 * 空模块（=「a 中所有物品进入核心」），再按缩小后的容量把核心库存截断
 * （=「超出核心容量的部分截断」）。
 *
   * 全程只改核心自己的 storageCapacity 字段与仓库的 linkedCore/items，不替换原版核心。
  */
public class CombinedStorageBlock extends StorageBlock {

  // ==================== 机制驱动 ====================

  /** 排查用：置 true 会把每次重算的细节打进日志。 */
  public static boolean debug = false;

  /**
   * 已登记的组合仓库。
   * 仓库方块 update=false，不会进 Groups.build，所以只能由仓库自己在 created()/onRemoved() 里登记。
   */
  private static final ObjectSet<CombinedStorageBuild> tracked = new ObjectSet<>();

  /** 有建筑变化时置位；每帧最多重算一次（保证在原版 proximity 更新之后跑）。 */
  private static boolean dirty = false;
  /** 读档那一轮：仓库手里是"核心库存的副本"，内容相同要丢弃而不是相加（否则读档翻倍）。 */
  private static boolean dedupeOnce = false;
  /** 读档时（原版回灌 sector info 之前）各核心的库存快照：核心位置 → 每种物品数量。 */
  private static final arc.struct.IntMap<int[]> loadItems = new arc.struct.IntMap<>();
  /**
   * 读档后还要兜几次恢复。
   * 原版那次截断在 SaveLoadEvent（Logic 的处理器）里，跟我们的处理器注册顺序有关，
   * 而且"事件处理器"之外还可能被别的路径再截一次 —— 读档后头几帧都补一下最稳。
   */
  private static int loadGrace = 0;

  /** 在 Main.init() 里注册一次。 */
  public static void register() {
    // Trigger 是 enum：必须用 Events.run(具体常量) 注册，Events.on(Trigger.class) 收不到
    Events.run(EventType.Trigger.update, CombinedStorageBlock::update);
    Events.on(EventType.WorldLoadBeginEvent.class, e -> {
      dedupeOnce = true;
      dirty = true;
    });
    Events.on(EventType.WorldLoadEvent.class, e -> {
      rescan();
      // 【立刻算一次】原版核心在自己的 updateTile 里会按 storageCapacity 清掉超出部分；
      // 要是等下一帧的 dirty 重算，核心已经按"自身容量"清过了 —— 物品就真没了。
      dedupeOnce = true;
      snapshotCoreItems();
      update();
    });
    // 【读档时的第二次截断】原版在 SaveLoadEvent 里做 sector info 回灌
    // （Logic → SectorInfo.write()：clear + add(sector.info.items) + 逐物品 clamp 到 storageCapacity）。
    // 这条路在 onProximityUpdate 之外，CombinedCoreBlock 的补救拦不住，
    // 上面在 WorldLoadEvent 先记下地图里的库存，这里把被削掉的补回来（只补不删）。
    Events.on(EventType.SaveLoadEvent.class, e -> restoreCoreItems());
    // 放置/拆除/替换方块都会触发；这里只置位，真正重算留到之后
    // 【性能】不再无条件置脏：多线程建造刷一大片时，每格都触发一次"全部仓库重算"
    // （用户报的"服务端和客户端使用多线程建造后帧率下降十分明显"）。
    // 只有"仓库/核心这一坨"真的变了才需要重算，见 markTileChanged。
    Events.on(EventType.TileChangeEvent.class, e -> markTileChanged(e.tile));
    Events.on(EventType.BlockBuildEndEvent.class, e -> markTileChanged(e.tile));
  }

  /** 这一格变化要不要重算仓库：仓库本身/核心，或者挨着它们的格子。 */
  public static void markTileChanged(mindustry.world.Tile tile){
    try{
      if(tile == null){
        dirty = true;
        return;
      }
      Building b = tile.build;
      if(b != null && isStorageLike(b)){
        dirty = true;
        return;
      }
      for(int i = 0; i < 4; i++){
        mindustry.world.Tile cur = tile.nearby(i);
        Building nb = cur == null ? null : cur.build;
        if(nb != null && isStorageLike(nb)){
          dirty = true;
          return;
        }
      }
    }catch(Throwable t){
      dirty = true; // 判据出错宁可多算
    }
  }

  /** 参与"仓库↔核心"并仓那一套的建筑：组合仓库（含被别的模组写的仓库）或核心。 */
  private static boolean isStorageLike(Building b){
    if(b == null) return false;
    if(b instanceof CombinedStorageBuild) return true;
    return b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild;
  }

  /**
   * 读档那一轮"去重语义"还没用掉。
   * ComboNet 重建网络时也读它：读档后每台仓库手里都写了一份同一池子的副本，
   * 这时候合并必须"内容相同的副本只留一份"，不能当三份真库存相加（否则物品直接翻三倍）。
   */
  public static boolean pendingDedupe() {
    return dedupeOnce;
  }

  /** 组合连接器/节点网络要用：仓库不在 Groups.build 里，得把这个登记表交出去。 */
  public static ObjectSet<CombinedStorageBuild> trackedSet() {
    return tracked;
  }

  /** 外部也可以主动叫一次。 */
  public static void markDirty() {
    dirty = true;
  }

  private static void update() {
    if (state == null || world == null || world.isGenerating())
      return;
    // 读档后的头几帧：把被原版回灌 sector info 削掉的库存补回来（只补不删）。
    // 那次截断在 SaveLoadEvent 里，跟我们的处理器注册顺序有关，光靠处理器不一定排在它后面，
    // 所以读档后的头几帧再兜几次。
    if (loadGrace > 0) {
      restoreCoreItems();
      if (--loadGrace == 0)
        loadItems.clear();
    }
    if (!dirty)
      return;
    dirty = false;
    // 读档完成后的第一次重算带着"去重"语义；只有真正算完才清掉这个标记
    // 读档后头几帧也算"去重语义"（见 ComboNet.loadDedupeFrames）：
    // 这几帧里本地组合体才刚重建完，还会再合并一次"每台手里的整份副本"，
    // 漏掉它就会按运行期相加 → 物品翻倍（用户报的"重新读写后物品增加"）。
    boolean dedupe = dedupeOnce || ComboNet.pendingLoadDedupe();
    dedupeOnce = false;
    sawStorage = false;
    rebuildAll(dedupe);
    // 【别让空转的一轮把去重语义吃掉】读档时可能先来一轮"仓库还没登记/还没接上核心"的重算，
    // 那轮什么都没处理却把 dedupeOnce 用掉了；下一轮真正合并时按"相加"处理，
    // 每台仓库手里的核心库存副本就被当成真货加进去 —— 物品补满（用户报的）。
    // 【但必须有界】以前这里只判 !sawStorage 就重新置位：地图里**没有核心/仓库**的时候
    // （或核心被拆掉之后）sawStorage 永远是 false，dedupeOnce 就一直被重新装上 ——
    // ComboNet 的 loadPhase 读的就是它，于是"读档去重"语义永久生效，之后每一次并池都按
    // "内容相同的副本只留一份"合并。玩家看到的是"把共享取消再勾上，物品少一半"
    // （BodyShareTest / ShareConfigTest 实测 40 → 20）。只在**读档窗口内**兜：
    // 窗口由 ComboNet 的 loadDedupeFrames 限定（读档后 20 帧、世界一被改动就结束）。
    if (dedupe && !sawStorage && ComboNet.pendingLoadDedupe())
      dedupeOnce = true;
  }

  /** 这一轮重算里到底处理到仓库没有（用来判断读档去重语义有没有被空转消耗掉）。 */
  private static boolean sawStorage = false;

  /** 刷新所有队伍的"仓库↔核心"连通关系、容量与并仓。 */
  public static void rebuildAll() {
    rebuildAll(false);
  }

  public static void rebuildAll(boolean dedupe) {
    if (state == null || world == null || world.isGenerating()) {
      dirty = true;
      return;
    }
    try {
      // 注意：不能用 state.teams.present —— 读档/服务器环境下它可能是空的，
      // 而队伍数据（cores / 仓库）是好的。这里遍历所有队伍：
      // 有核心的（要扩容）、或者有登记仓库的（要走"独立组合仓库组"规则）都要处理。
      for (Team team : Team.all) {
        TeamData data = team.data();
        if (data == null)
          continue;
        if (data.cores.isEmpty() && !hasTracked(team))
          continue;
        rebuildTeam(data, dedupe);
      }
    } catch (Throwable t) {
      Log.err("[combine] 组合仓库↔核心 重算失败", t);
    }
  }

  private static boolean hasTracked(Team team) {
    for (CombinedStorageBuild s : tracked) {
      if (ComboReflect.inWorld(s) && s.team == team)
        return true;
    }
    return false;
  }

  /** 仓库登记 / 注销（由 {@link CombinedStorageBuild} 自己调用）。 */
  public static void track(CombinedStorageBuild storage) {
    if (storage != null && tracked.add(storage))
      dirty = true;
  }

  public static void untrack(CombinedStorageBuild storage) {
    if (storage != null && tracked.remove(storage))
      dirty = true;
  }

  /** 读档后全图扫一遍重建登记表（兜底，正常情况下 created() 已经登记过）。 */
  public static void rescan() {
    tracked.clear();
    if (world != null) {
      // "只玩家组合"打开时别登记 AI 敌人的仓库：它们不会被组合，登记了每轮重建都要过一遍。
      boolean playersOnly = combine.util.ComboTeams.playersOnly;
      for (Tile tile : world.tiles) {
        // 所有组合仓库都登记（coreMerge=false 的强化版也要能"相邻成组"，
        // coreMerge 只决定"要不要并进核心"）
        if (tile != null && tile.build instanceof CombinedStorageBuild sb) {
          if (playersOnly && !combine.util.ComboTeams.playerTeam(sb.team))
            continue;
          tracked.add(sb);
        }
      }
    }
    dirty = true;
  }

  /**
   * 记下当前所有核心的库存（每种物品一份）。在读档流程的"原版回灌 sector info"之前调用。
   * 只记合法的核心，位置用 pos()（读档前后同一个核心位置不变）。
   */
  private static void snapshotCoreItems() {
    loadItems.clear();
    loadGrace = 3;
    if (world == null || state == null)
      return;
    for (Team team : Team.all) {
      TeamData data = team.data();
      if (data == null)
        continue;
      for (CoreBuild core : data.cores) {
        if (core == null || core.items == null || !core.isValid())
          continue;
        int[] arr = new int[content.items().size];
        for (Item item : content.items())
          arr[item.id] = core.items.get(item);
        loadItems.put(core.pos(), arr);
      }
    }
  }

  /**
   * 读档末尾（SaveLoadEvent）把 sector info 回灌时**削掉**的库存补回来。
   * 只往上补、不删任何东西 —— 容量数字仍然是"核心 + 连通仓库"，不跟着库存走。
   */
  private static void restoreCoreItems() {
    if (loadItems.isEmpty())
      return;
    for (Team team : Team.all) {
      TeamData data = team.data();
      if (data == null)
        continue;
      for (CoreBuild core : data.cores) {
        if (core == null || core.items == null || !core.isValid())
          continue;
        int[] arr = loadItems.get(core.pos());
        if (arr == null)
          continue;
        for (Item item : content.items())
          if (arr[item.id] > core.items.get(item))
            core.items.set(item, arr[item.id]);
      }
    }
    dirty = true;
  }

  private static void rebuildTeam(TeamData data, boolean dedupe) {
    Seq<CoreBuild> cores = new Seq<>(data.cores);

    // ---- 1) 本队所有参与并仓的仓库，按"仓库↔仓库相邻"求连通分量 ----
    Seq<CombinedStorageBuild> storages = new Seq<>();
    for (CombinedStorageBuild sb : tracked) {
      // 全部纳入分组：coreMerge 只影响"是否并进核心"，不影响"相邻成组共用一个池子"
      if (ComboReflect.inWorld(sb) && sb.team == data.team)
        storages.add(sb);
    }
    if (storages.size > 0)
      sawStorage = true;

    ObjectSet<CombinedStorageBuild> visited = new ObjectSet<>();
    Seq<Seq<CombinedStorageBuild>> comps = new Seq<>();

    // 同一张组合连接器/节点网络里的仓库，语义上和"直接贴着"完全一样，必须算同一个分量：
    // 容量相加、共用一个池子、面板显示整张网络。否则就会出现"池子共享了、容量只算自己"。
    arc.struct.IntMap<Seq<CombinedStorageBuild>> byNetwork = new arc.struct.IntMap<>();
    for (CombinedStorageBuild s : storages) {
      int key = ComboNet.networkKey(s);
      if (key != 0)
        byNetwork.get(key, Seq::new).add(s);
    }

    for (CombinedStorageBuild start : storages) {
      if (visited.contains(start))
        continue;
      Seq<CombinedStorageBuild> comp = new Seq<>();
      Queue<CombinedStorageBuild> queue = new Queue<>();
      queue.addLast(start);
      visited.add(start);
      while (!queue.isEmpty()) {
        CombinedStorageBuild cur = queue.removeFirst();
        comp.add(cur);
        for (Building nb : cur.proximity) {
          if (nb instanceof CombinedStorageBuild other && other.team == data.team
              && visited.add(other)) {
            queue.addLast(other);
          }
        }
        int key = ComboNet.networkKey(cur);
        if (key != 0) {
          Seq<CombinedStorageBuild> sameNet = byNetwork.get(key);
          if (sameNet != null) {
            for (CombinedStorageBuild other : sameNet) {
              if (other != cur && other.team == data.team && visited.add(other))
                queue.addLast(other);
            }
          }
        }
      }
      comps.add(comp);
    }

    if (debug) {
      StringBuilder sb = new StringBuilder();
      for (Seq<CombinedStorageBuild> comp : comps) {
        sb.append('[');
        for (CombinedStorageBuild x : comp) {
          sb.append(x.block.name).append('@').append(x.tileX()).append(',').append(x.tileY()).append(' ');
        }
        sb.append("] ");
      }
      Log.info("[combine] 重算 team=@ 已登记=@ 分量=@ -> @", data.team.name, storages.size, comps.size, sb);
    }

    // ---- 2) 每个分量：挨着核心 → 并进核心；否则独立组合仓库组 ----
    int capacity = 0;
    for (CoreBuild core : cores) {
      if (core != null && core.isValid())
        capacity += core.block.itemCapacity;
    }

    ObjectSet<CombinedStorageBuild> linkedNow = new ObjectSet<>();
    Seq<Seq<CombinedStorageBuild>> standaloneComps = new Seq<>();
    for (Seq<CombinedStorageBuild> comp : comps) {
      int compCap = 0;
      for (CombinedStorageBuild s : comp)
        compCap += s.block.itemCapacity;

      CoreBuild core = adjoiningCore(comp, data);
      if (core != null) {
        capacity += compCap;
        for (CombinedStorageBuild s : comp) {
          s.linkToCore(core, dedupe);
          linkedNow.add(s);
        }
      } else {
        standaloneComps.add(comp);
        standalone(comp, compCap, dedupe);
      }
    }

    // ---- 3) 曾经连着核心、这次不再连通的仓库：解链（物品已经留在核心池里） ----
    for (CombinedStorageBuild s : storages) {
      if (s.linkedCore != null && !linkedNow.contains(s))
        s.unlinkFromCore();
    }

    // ---- 4) 刚被拆开的**独立组**之间可能还共用同一个模块：按容量比例分开，不丢物品 ----
    // 【只对独立组做】并进核心的组本来就故意和核心共用同一个模块（s.items == core.items），
    // 那不是"被多个分量共用的残留池"：拿去 split 会把核心库存按各分量容量复制成好几份
    // （核心那份还原封不动），下次再并回核心就把副本加进去 —— 物品凭空翻倍
    // （用户报的"钢化玻璃从 4500 变 7000+"，最后被"超容截断"压到容量值，所以看着正好等于容量）。
    splitSharedModules(standaloneComps);

    // ---- 5) 核心容量 = 自身 + 所有连通的仓库；超容截断 ----
    ItemModule coreItems = null;
    for (CoreBuild core : cores) {
      if (core != null && core.isValid()) {
        // 【容量只由"核心 + 连通仓库"决定，绝不拿库存当地板】
        // storageCapacity 是**每种物品各自**的上限，只是"能存多少"，和"现在存了多少"无关。
        // 以前这里写了 max(容量, items.total()) / max(容量, 单项最大值) 当兜底，结果：
        //   - items.total()（所有物品总和）⇒ 6 种物品各装满 2.2 万，容量显示 13.3 万；
        //   - 单项最大值 ⇒ 只要某种物品曾经超过容量，容量就被那个数字钉死（9 千的核心 +
        //     44 个 300 的容器本该是 22200，面板却一直显示 23074，拆容器也不掉）。
        // 现在两者都不做：超容的存量照样留着（见下面的"不截断"说明与 CombinedCoreBlock 的补救），
        // 但上限数字永远等于"核心自身 + 所有连通仓库"。
        // 【不能压低原版自己算的容量】原版 CoreBuild.onProximityUpdate 会把"直接相邻的 StorageBlock"
        // 也算进 storageCapacity —— 比如模组里 js 写的集装箱（更多实用设备的 cargo）就是靠这个给核心扩容的。
        // 我们重算时如果把它的值覆盖掉，那些仓库的扩容能力就没了（用户报的）。
        core.storageCapacity = Math.max(capacity, core.storageCapacity);
        if (coreItems == null)
          coreItems = core.items;
      }
    }
    // 【不截断核心存量】旧实现把核心物品按 capacity 削一遍。可容量是这一轮现算的，
    // 只要某轮少算（读档中途、刚放置那几帧、仓库瞬时没接上核心），存量就被真删掉
    // （用户存档：造一个容器后每种物品从 22200 掉到 12000）。容量仍然限制"新物品进入"，
    // 存量超了就让它超着（和原版拆掉容器后的核心一致）。
  }

  /** 这个分量里有没有成员紧挨着本队的核心（有就整块并进核心）。 */
  private static CoreBuild adjoiningCore(Seq<CombinedStorageBuild> comp, TeamData data) {
    // 强化版仓库（coreMerge=false）本来就不跟核心并仓：整组都没有可并仓的成员时直接不并
    boolean anyMergeable = false;
    for (CombinedStorageBuild s : comp)
      if (s.coreMergeStorage()) { anyMergeable = true; break; }
    if (!anyMergeable)
      return null;
    for (CombinedStorageBuild s : comp) {
      for (Building nb : s.proximity) {
        if (nb instanceof CoreBuild core && core.team == data.team && core.isValid())
          return core;
      }
    }
    return null;
  }

  /**
   * 独立组合仓库组（没连核心）：跟其它组合建筑一样，整组共用一个物品模块、容量相加。
   * 注意刚从核心上分离出来的仓库，手里那份模块其实是核心的池子（物品归核心），
   * 必须换成空模块，别把核心池抢过来。
   */
  private static void standalone(Seq<CombinedStorageBuild> comp, int groupCap, boolean dedupe) {
    for (CombinedStorageBuild s : comp) {
      if (s.linkedCore != null) {
        s.items = new ItemModule(); // 它的库存已经并进核心，这里从空开始
        s.linkedCore = null;
      }
    }

    CombinedStorageBuild leader = comp.first();
    for (CombinedStorageBuild s : comp) {
      if (s.pos() < leader.pos())
        leader = s;
    }
    // 【不共享物品】面板上"物品"没勾 = 每台仓库各留各的模块（不并仓），容量也只算自己那台
    boolean shareItems = combine.net.ComboShare.shares(leader, combine.net.ComboShare.ITEMS);
    ItemModule pool = leader.items != null ? leader.items : new ItemModule();

    // 连了组合连接器/节点时，容量和池子都按"整张网络"算：整组报告网络合计容量，
    // 相当于这些机器直接贴在一起。池子目标也沿用 ComboNet 选中的那一份，避免两边来回抢。
    // 注意用"本组合计 + 网络里不属于本组的成员"而不是 max()：网络只连到本组一部分仓库时，
    // max() 会把网络里那些仓库再数一遍（数小了反而漏掉真正多出来的工厂容量）。
    ObjectSet<Building> inGroup = new ObjectSet<>();
    for (CombinedStorageBuild s : comp)
      inGroup.add(s);
    ObjectSet<Building> counted = new ObjectSet<>();
    for (CombinedStorageBuild s : comp) {
      // 注意要遍历**每个成员**的网络：本组里没接进网络的那几台，从它出发查网络只能查到它自己
      for (Building m : ComboNet.componentMembers(s)) {
        if (!ComboReflect.inWorld(m) || inGroup.contains(m) || !counted.add(m))
          continue;
        groupCap += ComboReflect.baseItemCap(m);
      }
    }
    ItemModule netPool = ComboNet.poolModuleFor(leader);
    if (shareItems && netPool != null)
      pool = netPool;

    for (CombinedStorageBuild s : comp) {
      if (shareItems && s.items != null && s.items != pool) {
        if (dedupe)
          mergeCopyMax(pool, s.items);
        else
          moveItems(s.items, pool);
      }
    }
    for (CombinedStorageBuild s : comp) {
      if (shareItems)
        s.items = pool;
      s.linkedCore = null;
      s.comboStorageCap = shareItems ? groupCap : Math.max(ComboReflect.baseItemCap(s), 1);
      // 面板上的"x N"就是本组仓库台数；网络里接过来的工厂之类由 extraNetworkText 单独报名字
      s.comboGroupSize = comp.size;
    }
    // 【不截断】以前这里按"本组容量"截断，仓库组刚从核心脱离（比如拆/重建贴着核心的那台）
    // 时，池子里原本属于核心的几千物品会被按"两个容器 600"直接删掉 —— 数据丢失。
    // 容量照样会挡住新物品进入（comboStorageCap / acceptItem），没必要删存量。
  }

  /** 同一个模块被多个"独立组合仓库组"共用（刚被拆开）时，按各组容量比例拆分。 */
  private static void splitSharedModules(Seq<Seq<CombinedStorageBuild>> comps) {
    ObjectMap<ItemModule, Seq<Seq<CombinedStorageBuild>>> byModule = new ObjectMap<>();
    for (Seq<CombinedStorageBuild> comp : comps) {
      if (comp.isEmpty())
        continue;
      CombinedStorageBuild first = comp.first();
      if (first.linkedCore != null || first.items == null)
        continue;
      byModule.get(first.items, Seq::new).add(comp);
    }

    for (ObjectMap.Entry<ItemModule, Seq<Seq<CombinedStorageBuild>>> entry : byModule) {
      Seq<Seq<CombinedStorageBuild>> users = entry.value;
      if (users.size <= 1)
        continue;

      ItemModule old = entry.key;
      // 【这口池子是不是本组独有的】并仓状态和这一轮重算不是同一时刻：仓库手里的模块可能
      // 还是**核心库存那一份**（网络层把整张网络的池子指成了核心的那份）。按容量"拆一份"
      // 给各组等于从核心库存里复制一份出去 —— 核心没动、仓库这边多出一份同样的物品，
      // 下次并回核心就翻倍（用户报的"拆工厂时核心里面的东西全没了"的另一面）。
      // 池子被参与拆分的这些组之外的建筑引用时，一律不动它。
      Seq<Building> members = new Seq<>();
      for (Seq<CombinedStorageBuild> user : users)
        for (CombinedStorageBuild s : user)
          if (s != null) members.add(s);
      if (ComboReflect.itemPoolSharedOutside(old, members))
        continue;

      int n = users.size;
      int[] caps = new int[n];
      int totalCap = 0;
      for (int i = 0; i < n; i++) {
        int cap = 0;
        for (CombinedStorageBuild s : users.get(i))
          cap += s.block.itemCapacity;
        caps[i] = Math.max(cap, 1);
        totalCap += caps[i];
      }

      ItemModule[] shares = new ItemModule[n];
      for (int i = 0; i < n; i++)
        shares[i] = new ItemModule();
      for (Item item : content.items()) {
        int total = old.get(item);
        if (total <= 0)
          continue;
        int remaining = total;
        for (int i = 0; i < n; i++) {
          int ideal = i == n - 1 ? remaining : Math.round(total * (float) caps[i] / totalCap);
          ideal = Math.max(0, Math.min(ideal, remaining));
          if (ideal > 0) {
            shares[i].add(item, ideal);
            remaining -= ideal;
          }
        }
      }
      for (int i = 0; i < n; i++) {
        for (CombinedStorageBuild s : users.get(i))
          s.items = shares[i];
        // 不按各自容量截断：拆分只是"把原来那一份分给几个组"，总量必须守恒
      }
    }
  }

  private static void clamp(ItemModule module, int capacity) {
    if (module == null)
      return;
    for (Item item : content.items()) {
      module.set(item, Math.min(module.get(item), capacity));
    }
  }

  /** 读档合并副本：逐物品取较大值（不叠加），用于"同一份池子的多份副本"。 */
  private static void mergeCopyMax(ItemModule into, ItemModule copy) {
    if (into == null || copy == null || into == copy)
      return;
    for (Item item : content.items()) {
      int v = copy.get(item);
      if (v > into.get(item))
        into.set(item, v);
    }
  }

  private static void moveItems(ItemModule from, ItemModule to) {
    if (from == null || to == null || from == to)
      return;
    for (Item item : content.items()) {
      int amount = from.get(item);
      if (amount > 0) {
        to.add(item, amount);
        from.remove(item, amount);
      }
    }
    to.stopFlow(); // 见 ComboNet.moveItems 的说明：内部搬池子不算流量
  }

  private static boolean sameItems(ItemModule a, ItemModule b) {
    if (a == b)
      return true;
    if (a == null || b == null)
      return false;
    for (Item item : content.items()) {
      if (a.get(item) != b.get(item))
        return false;
    }
    return true;
  }

  // ==================== 方块 ====================

  public CombinedStorageBlock(String name) {
    super(name);
  }

  public class CombinedStorageBuild extends StorageBuild {
    /**
     * 独立组合仓库组的组容量（>0 表示"没连核心，组内共用一个池子"）；
     * 并进核心时置 -1，容量一律走核心的 storageCapacity。
     */
    public int comboStorageCap = -1;
    /** 独立组的成员数（仅用于面板显示）。 */
    public int comboGroupSize = 1;

    /** 是否参与核心并仓（原版 container/vault 是 true，强化版是 false）。 */
    public boolean coreMergeStorage() {
      return coreMerge;
    }

    /** 已经并进的 cores（没并进返回 null）——给 ComboNet 判断"这是不是核心的池子"用。 */
    public CoreBuild linkedCoreOf() {
      return linkedCore instanceof CoreBuild core && core.isValid() ? core : null;
    }

    /** 当前实际可存容量：并进核心 → 核心容量；独立组 → 组容量；都没在组里 → 自身容量。 */
    public int comboCapacity() {
      if (linkedCore instanceof CoreBuild core && core.isValid())
        return core.storageCapacity;
      return comboStorageCap > 0 ? comboStorageCap : itemCapacity;
    }

    /** 面板上那行说明（UI 与测试共用同一处，免得两边算得不一样）。 */
    public String poolLabel() {
      int cap = Math.max(comboCapacity(), 1);
      return linkedCore instanceof CoreBuild
          ? "[accent]已并入核心[] 容量 " + cap
          : "[accent]组合仓库组 x" + comboGroupSize + "[] 容量 " + cap;
    }

    /** 并进核心：共用核心的物品模块（容量已计入核心）。 */
    void linkToCore(CoreBuild core, boolean dedupe) {
      if (debug)
        Log.info("[combine] 并入核心: 仓库=@ 核心=@ 同模块=@ 去重=@",
            items == null ? -1 : items.total(), core.items == null ? -1 : core.items.total(), items == core.items, dedupe);
      if (items != core.items) {
        if (dedupe) {
          // 读档那一轮：仓库手里是"核心池的副本"。不能相加（会翻倍），
          // 也不能简单丢弃 —— 原版核心会在自己的 tick 里按"核心自身容量"清掉超出部分，
          // 于是核心那份可能已经被削小（用户存档：核心 12000/种、仓库副本 22200/种），
          // 丢掉副本就把玩家真正的库存丢了。这里逐物品取**较大值**：副本不叠加、被削过的也不吃亏。
          mergeCopyMax(core.items, items);
        } else {
          moveItems(items, core.items);
        }
      }
      items = core.items;
      linkedCore = core;
      comboStorageCap = -1;
      comboGroupSize = 1;
    }

    /** 解链：物品早已在核心池里（链接期间共用模块），这里给它一份空模块即可。 */
    void unlinkFromCore() {
      // 【保险】并仓期间 ComboNet 可能把整张网络的池子换成了**别台建筑**那一份
      // （网络里混进了组合工厂/别的仓库时）。那种情况下手里这份不是核心库存 ——
      // 直接丢弃就等于把玩家的东西留在了别人身上：用户报的
      // 「核心东西全跑到石墨压缩机里，切断组合也不会复原」。
      // 解链前先把"不属于核心的那部分"还回核心，再给自己一份空模块。
      if (linkedCore instanceof CoreBuild core && core.isValid() && core.items != null
          && items != null && items != core.items) {
        try {
          moveItems(items, core.items);
        } catch (Throwable ignored) {
        }
      }
      linkedCore = null;
      items = new ItemModule();
      comboStorageCap = -1;
      comboGroupSize = 1;
    }

    @Override
    public void created() {
      super.created();
      track(this);
    }

    @Override
    public void onRemoved() {
      untrack(this);
      super.onRemoved();
    }

    @Override
    public int getMaximumAccepted(Item item) {
      if (linkedCore != null)
        return linkedCore.getMaximumAccepted(item);
      return comboStorageCap > 0 ? comboStorageCap : itemCapacity;
    }

    @Override
    public boolean acceptItem(Building source, Item item) {
      // 同池建筑（组合节点/连接器并进来的工厂或别的仓库）把物品"搬"进本仓库时，
      // 两边其实是同一个 ItemModule：加 1 再被对方减 1，等于没搬，却会让对方的
      // dump() 以为已经成功。返回 false，对方才会继续找真正的传送带/管道出口。
      if (source != null && source.items == items)
        return false;
      return super.acceptItem(source, item);
    }

    @Override
    public double sense(mindustry.logic.LAccess sensor) {
      if (sensor == mindustry.logic.LAccess.itemCapacity && linkedCore == null && comboStorageCap > 0) {
        return comboStorageCap;
      }
      return super.sense(sensor);
    }

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("combinedstorageblock:display", () -> displayInner(table));
    }

    void displayInner(Table table) {
      super.display(table);
      // 只留一段内容：仓库组的池子（它已经是"整张网络"的容量，见 standalone）。
      // 以前这里还会再挂一段 ComboNet 的"网络组合"面板，两段列的是同一份池子、
      // 数量却按各自统计（例如 5000/3000 这种超容显示），看着就是自相矛盾，所以合并掉。
      showPool(table);
    }

    /** 显示组合仓库当前共用的池子（独立组 = 组池；并进核心 = 核心池）。 */
    void showPool(Table table) {
      // 【关键】选中方块的信息面板只在**切换选中目标**时重建一次（PlacementFragment 里 hover 没变就直接 return），
      // 面板搭好之后一直复用。所以这里的数字必须在 update 回调里每帧重取，
      // 写成构建那一刻的常量就会出现"打开面板时的数量，之后一直不动"。
      Table pool = new Table();
      pool.left();
      pool.update(() -> {
        pool.clearChildren();
        pool.defaults().left();
        ComboUi.safe("combinedstorageblock:pool", () -> buildPool(pool));
      });
      table.row();
      // 【宽度必须封顶】这段是挂在原版信息面板最外层表格上的：不封顶的话"网络另有: …"
      // 那行一长，整张面板就被撑到屏幕宽，里面 growX 的物品条跟着横跨全屏
      // （用户报的"容器的组合体成员显示还是没换行 / 物品液体 bar 也没限制"）。
      table.add(pool).width(ComboUi.COMPOSITION_WIDTH + 24f).left();
    }

    /** 物品条的文字：每次调用取当前值（面板每帧重画 + Bar 自己的 update 都会读它）。 */
    public String itemLabel(Item item) {
      return item.localizedName + ": " + items.get(item) + "/" + Math.max(comboCapacity(), 1);
    }

    /** 本仓库「相邻成组」的整组成员（规则和 standalone 的并仓完全一致）。 */
    public Seq<CombinedStorageBuild> cluster() {
      Seq<CombinedStorageBuild> out = new Seq<>();
      ObjectSet<CombinedStorageBuild> seen = new ObjectSet<>();
      Queue<CombinedStorageBuild> queue = new Queue<>();
      queue.addLast(this);
      seen.add(this);
      while (!queue.isEmpty()) {
        CombinedStorageBuild cur = queue.removeFirst();
        out.add(cur);
        // coreMerge 只影响并核心，不影响"相邻成组"
        for (Building nb : cur.proximity) {
          if (nb instanceof CombinedStorageBuild other && other.team == team
              && ComboReflect.inWorld(other) && seen.add(other)) {
            queue.addLast(other);
          }
        }
      }
      return out;
    }

    /**
     * 这张网络里**不属于本仓库组**的成员（连接器/节点接过来的工厂之类），面板上只报个名字。
     * 它们的容量已经算进组容量里了，所以不再单独列一遍数字，免得出现两个互相矛盾的池子。
     */
    public String extraNetworkText() {
      ObjectSet<Building> mine = new ObjectSet<>();
      for (CombinedStorageBuild s : cluster())
        mine.add(s);
      ObjectIntMap<Block> counts = new ObjectIntMap<>();
      // 同样遍历组里每个成员的网络（没接进网络的那些从它出发只能查到自己）
      ObjectSet<Building> seen = new ObjectSet<>();
      for (CombinedStorageBuild s : cluster()) {
        for (Building m : ComboNet.componentMembers(s)) {
          if (!ComboReflect.inWorld(m) || mine.contains(m) || !seen.add(m))
            continue;
          if (m.block instanceof CombinedStorageBlock)
            continue; // 仓库（含网络接过来的）已经算进"组合仓库组 x N / 容量"里了
          counts.increment(m.block, 1);
        }
      }
      StringBuilder sb = new StringBuilder();
      for (Block b : counts.keys()) {
        if (sb.length() > 0)
          sb.append("  ");
        sb.append(b.localizedName).append("*").append(counts.get(b, 0));
      }
      return sb.toString();
    }

    /** 池子内容：活数据（供面板每帧重画）。 */
    public void buildPool(Table table) {
      table.add(poolLabel()).left().width(ComboUi.COMPOSITION_WIDTH).wrap();
      String extra = extraNetworkText();
      if (extra.length() > 0) {
        table.row();
        // 组合体成员（连接器/节点接过来的工厂之类）也要换行，否则这一行就是面板变宽的元凶
        table.add("[lightgray]网络另有: " + extra + "[]").left()
            .width(ComboUi.COMPOSITION_WIDTH).wrap();
      }
      if (items != null) {
        for (Item item : content.items()) {
          if (items.get(item) <= 0)
            continue;
          table.row();
          // 条也要给固定宽度（原来是 growX：面板一被撑宽它就跟着横跨全屏）
          table.add(new mindustry.ui.Bar(
              () -> itemLabel(item),
              () -> item.color,
              () -> items.get(item) / (float) Math.max(comboCapacity(), 1)))
              .width(ComboUi.COMPOSITION_WIDTH).height(18f).pad(4).left();
        }
      }
    }
  }
}
