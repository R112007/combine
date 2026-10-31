package combine.coop;
import combine.BlockCloner;
import combine.Main;
import combine.NoCombo;
import combine.Replacer;
import combine.net.ComboConnector.ComboConnectorBuild;
import combine.net.ComboConnector;
import combine.net.ComboNet;
import combine.net.ComboNode.ComboNodeBuild;
import combine.net.ComboNode;
import combine.production.CombinedCrafter;
import combine.storage.CombinedStorageBlock.CombinedStorageBuild;
import combine.storage.CombinedStorageBlock;
import combine.util.ComboReflect;
import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.HeatCrafter;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.production.SolidPump;
import mindustry.world.blocks.heat.HeatConductor;
import mindustry.world.blocks.heat.HeatProducer;
import mindustry.world.blocks.power.PowerGenerator;
import mindustry.world.blocks.production.WallCrafter;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * 协作组合（CoopCombo）—— 给「继承原版类、但写了新功能的方块」用的组合方案。
 *
 * 背景：combine 的组合机制是「把原版方块整只换成 CombinedXxx 实例」，靠 BlockCloner 深拷贝
 * 原方块字段。这套做法只对「与某个原版方块完全同名同构」的方块安全，于是 Replacer 用
 * isExact 过滤掉了子类（JS 模组 extend() 出来的 adapter 类、Java 模组的具名子类），
 * 代价就是这些方块完全不能组合：像废土科技的 library.js（多配方工厂）这类方块，
 * 一旦被替换成 CombinedCrafter，它自己的配方/配置/更新逻辑就全没了。
 *
 * 本办法换一条路：**不替换方块、也不改它们的 build 类**，只从外部把相邻同类机器的
 * 「库存模块」接在一起：
 *
 *   1) 相邻（4 邻接）、同队、同方块的多台机器算一组；
 *   2) 整组共用同一个 ItemModule / LiquidModule（池子合一），
 *      和 combine 的其它组合建筑一样 —— 一台机器吃到的料，全组都能用；
 *   3) 组容量 = 成员容量之和：把 block.itemCapacity / block.liquidCapacity
 *      临时放大到「基础值 × 该方块最大组员数」。这些方块自己的容量判断
 *      （例：library.js 里 this.items.get(item) >= this.getMaximumAccepted(item)）
 *      读的就是这两个字段，所以不用碰它们的代码就跟着一起变大；
 *   4) 拆组时按容量比例把池子分开（物品 + 液体），不丢东西；
 *   5) 读档后合并时做一次去重（存档里每台成员都写了一份同样的池子，
 *      直接相加会翻倍）。
 *
 * 因为方块类、build 类、配方逻辑一个字节都没动，所以这些方块的新功能（多配方、
 * 自定义配置、自定义 updateTile、自定义面板……）原样保留 —— 这也是本办法存在的理由。
 *
 * 已知边界（v1）：
 *   - 只处理「生产/仓储」家族的子类（见 {@link #familyOk}），不碰炮塔/核心/发电机等；
 *   - 只做同类相邻成组（不支持跨类型组合，也不参与 ComboNet 连接器/节点）；
 *   - 电力不共享（每台机器照原样各自接电）；
 *   - itemCapacity/liquidCapacity 是方块级字段，"最大的那个组"会把同类型散装单机的
 *     容量一起抬高（上限松一点，不会丢物品）。
 */
public class CoopCombo {

  /** 总开关：置 false 就完全退回"这些方块不组合"的旧行为。 */
  public static boolean enabled = true;

  /** 允许**不同类型**的机器组合（跨类型）。关掉就退回"只有同一种方块才能成组"。 */
  public static boolean allowCrossType = false;

  /**
   * 跨类型组里保护"中间产物"：把会吃这种料的邻居排到搬运顺序最前面，
   * 让 A 产的东西优先被组里的 B 吃掉，而不是被直接倒到外面的传送带上。
   * （同 combine 自家组合建筑的中间产物判定，见 CombinedCrafter.shouldDumpIntermediate。）
   */
  public static boolean keepIntermediates = true;

  /**
   * 组级收料：管道/传送带只贴到"自己不需要这种料"的成员时，替整组把它收进来。
   *
   * 这些方块的 acceptItem/acceptLiquid 是按**自己当前配方**判断的（例：library.js 的冷却机只要
   * "熔融铜+水"，隔壁冶炼厂只要"粗铜"）——水管贴在冶炼厂那一侧时，冶炼厂会拒收水，
   * 于是水根本进不了组。但组合体语义上就是一台大机器：**只要组里有人要，就该收得进来**。
   * 这里从运输类邻居（传送带/管道/路由器/运输桥…）把"组内有人要的"物品/液体搬进共享池，
   * 等于替那个要料的成员把货接下来；只有"贴着的这个成员自己不肯收"时才动手。
   */
  public static boolean groupIntake = true;

  /**
   * 是否允许**任意**（mod 提供的）Block 子类参与协作组合。
   *
   * 机制本身不认类：只要这个 build 带 items/liquids/power 模块，就能共享对应资源。
   * 打开时不再限制"必须是某个原版家族的子类"，任何非原版、非 combine 自己的方块
   * （自带物品/液体/电力/热量之一）都能成组；黑名单仍然可以单独排除。
   */
  public static boolean anyFamily = true;

  /** 组内共享热量（组里的产热方块给组里的耗热方块供热，等价于 combustion-generator 直接贴上去）。 */
  public static boolean shareHeat = true;

  /** 组级收料的节奏（每 N tick 一次）和单次上限（性能/手感护栏）。 */
  public static int intakeInterval = 2;
  public static int intakeItemsPerSource = 2;
  public static float intakeLiquidsPerSource = 20f;

  /**
   * 组内共享电力：把协作组合的方块当成"导电体"（等价于给它们设 conductivePower = true）。
   *
   * 原版规则（BuildingComp.getPowerConnections）：两台挨着的机器如果**都只是耗电方**
   * （consumesPower && !outputsPower），默认**不会**并到同一个电网里 —— 每台都得自己接电。
   * 只要其中一台是导电体（power-node/电池那种 conductivePower = true），两边就并网。
   * 组合体语义上是一台大机器，所以这里在"该方块确实成组了"的时候把它的 conductivePower
   * 打开（没成组的方块保持原样，不会顺手变成导线去桥接别的电网）。
   * 开关：sharePower（默认 true）。
   */
  public static boolean sharePower = true;

  /** 排查用：置 true 会把每次重算的细节打进日志。 */
  public static boolean debug = false;

  /** 黑名单（方块内部名）：个别子类方块组合起来语义不对时，可以在这里单独排除。 */
  public static final ObjectSet<String> blacklist = new ObjectSet<>();

  // ==================== 状态 ====================

  /** 已登记的可协作方块（不能靠 created() 挂钩子，只能自己在放置/读档时登记）。 */
  private static final ObjectSet<Building> tracked = new ObjectSet<>();

  /** 协作组合的分组结果：pos -> 组长、组长 pos -> 组员（每次重算刷新），供 ComboReflect/ComboNet/面板共用。 */
  private static final arc.struct.IntMap<Building> groupLeaderByPos = new arc.struct.IntMap<>();
  private static final arc.struct.IntMap<Seq<Building>> groupByLeaderPos = new arc.struct.IntMap<>();
  private static boolean groupsReady = false;
  private static long lastGroupSignature = 0L;

  /** 协作组合方块所在组的组长（{@link ComboReflect#leader} 桥接过来）。 */
  public static Building coopLeader(Building b) {
    if (b == null) return null;
    Building l = groupsReady ? groupLeaderByPos.get(b.pos()) : null;
    return l != null && l.isValid() ? l : b;
  }

  /** 协作组合方块所在组的成员（{@link ComboReflect#group} 桥接过来）。 */
  public static Seq<Building> coopGroup(Building b) {
    Seq<Building> out = new Seq<>();
    if (b == null) return out;
    Building l = coopLeader(b);
    Seq<Building> g = groupsReady && l != null ? groupByLeaderPos.get(l.pos()) : null;
    if (g != null && g.size > 0) return g;
    out.add(b);
    return out;
  }

  /** 已登记的组合节点（mod 自己的方块，created()/onRemoved() 里登记；它的 links 能跨距离连协作方块）。 */
  private static final ObjectSet<Building> trackedNodes = new ObjectSet<>();

  /** 组员数 > 1 的组（每帧"组级收料"用）。 */
  private static final Seq<Seq<Building>> intakeGroups = new Seq<>();
  private static int intakeCounter = 0;

  /**
   * 本次变化涉及到的格子（只在这些格子附近做局部补登记，避免全图扫描）。
   * 用 Set 去重：读档/蓝图一次改几百格时不会爆；每帧重算后清空，所以也不会无限涨。
   */
  private static final ObjectSet<Tile> changedTiles = new ObjectSet<>();

  /** 方块基础容量（放大前的原始值，内容装配时抓一次）。 */
  private static final ObjectMap<Block, Integer> baseItemCap = new ObjectMap<>();
  /** 方块原本的 conductivePower（成组时我们要临时打开，拆完要还原）。 */
  private static final ObjectMap<Block, Boolean> baseConductive = new ObjectMap<>();
  private static final ObjectMap<Block, Float> baseLiquidCap = new ObjectMap<>();

  private static boolean dirty = false;
  /** 读档那一轮：每个成员写的是同一份池子的副本，合并时要去重而不是相加。 */
  private static boolean dedupeOnce = false;

  // ==================== 注册 ====================

  /** 在 Main.init() 里注册一次（内容装配之后调用，保证方块 id 已定）。 */
  public static void register() {
    // Trigger 是 enum：必须用 Events.run(具体常量) 注册
    Events.run(EventType.Trigger.update, CoopCombo::update);
    Events.on(EventType.WorldLoadBeginEvent.class, e -> dedupeOnce = true);
    Events.on(EventType.WorldLoadEvent.class, e -> {
      captureBaseCaps();
      rescan();
      // 读档后整张图对账一次：共用电力模块的组合建筑可能在原版并网/拆网里掉队，见 ComboPower
      combine.util.ComboPower.markWorld();
    });
    Events.on(EventType.TileChangeEvent.class, e -> {
      if (e.tile != null) addChangedTile(e.tile);
    });
    Events.on(EventType.BlockBuildEndEvent.class, e -> {
      if (e.tile != null) addChangedTile(e.tile);
    });
    // 热量共享要跑在建筑更新之后（否则会被原版 calculateHeat 覆盖）
    Events.run(EventType.Trigger.afterGameUpdate, CoopCombo::shareHeatPass);

    // 点击查看组合体内容的面板（只给"改不了 display 方法"的那些方块，见 CoopPanel）
    CoopPanel.register();
  }

  /** 读档那一轮"去重语义"还没用掉（同 CombinedStorageBlock.pendingDedupe，供 ComboNet 判断）。 */
  public static boolean pendingDedupe() {
    return dedupeOnce;
  }

  /** 外部也可以主动叫一次（例如其它机制改动了方块）。 */
  public static void markDirty() {
    dirty = true;
  }

  private static void addChangedTile(Tile tile) {
    if (tile == null) return;
    changedTiles.add(tile);
    // 【性能】不再无条件置脏：多线程建造刷一大片时，每一格都会让"所有协作组合重算一遍"
    //（用户报的"服务端和客户端使用多线程建造后帧率下降十分明显"）。
    // 只有这一格真的跟协作组合有关（自己/邻居是能协作的方块，或者是组合节点/连接器）才置脏。
    if (relevant(tile)) dirty = true;
    // 【电网对账】方块增删会触发原版的拆网/并网，而共用一份 PowerModule 的组合建筑
    // 可能被原版那套"扇形拆分"漏在旧图上（读档、拆电线杆、拆组员都会遇到）。
    // 这里只要改动附近有带电建筑，就把"整张图对账"排上（见 combine.util.ComboPower）。
    for (int dx = -1; dx <= 1; dx++) {
      for (int dy = -1; dy <= 1; dy++) {
        Tile cur = world == null ? null : world.tile(tile.x + dx, tile.y + dy);
        if (cur == null) continue;
        Building b = cur.build;
        if (b != null && b.power != null) {
          combine.util.ComboPower.mark(b);
          return;
        }
      }
    }
  }

  /**
   * 这一格变化跟"协作组合"有没有关系：自己或邻居是能协作的方块（含已登记的成员），
   * 或者是组合节点/连接器（它们是把组合接成一张网的导线）。
   */
  private static boolean relevant(Tile tile) {
    try {
      for (int i = 0; i < 5; i++) {
        Tile cur = i == 0 ? tile : tile.nearby(i - 1);
        if (cur == null) continue;
        Building b = cur.build;
        if (b == null) continue;
        if (tracked.contains(b)) return true;
        if (eligible(b.block)) return true;
        if (b instanceof ComboNode.ComboNodeBuild || b instanceof ComboConnector.ComboConnectorBuild)
          return true;
      }
    } catch (Throwable ignored) {
      return true; // 判据出错宁可多算一遍
    }
    return false;
  }

  // ==================== 资格判定 ====================

  /**
   * 方块是否交给协作组合处理。
   * combine 自己替换出来的组合方块（combine.* 包下的类）一律跳过 —— 它们走原本那套机制。
   */
  private static final ObjectMap<Block, Boolean> eligibleCache = new ObjectMap<>();

  /** 改了 anyFamily / blacklist 之后叫一下，清掉资格缓存。 */
  public static void clearEligibilityCache() {
    eligibleCache.clear();
    // 【性能】ComboNet 的"组合相关建筑"登记表也按资格（isComboBuild → CoopCombo.eligible）过滤，
    // 资格变了必须让它重扫，否则新启用的方块永远进不了网络重建的名单。
    try {
      ComboNet.invalidateTracked();
    } catch (Throwable ignored) {
    }
  }

  // ==================== "不组合"名单的持久化（设置界面用） ====================

  /** 手动标记"不组合"的方块名存在 Core.settings 的这个键里（用 | 分隔）。 */
  public static final String BLACKLIST_KEY = "combine.blockBlacklist";
  private static boolean blacklistLoaded = false;
  /** 名单变过：下一轮重算时先全图重扫一次登记表（把重新启用的方块找回来）。 */
  private static boolean needsRescan = false;

  /** 从 Core.settings 读回名单（启动时调一次，之后靠 ensure 兜底）。 */
  public static void loadBlacklist() {
    blacklistLoaded = true;
    blacklist.clear();
    if (Core.settings == null)
      return;
    String raw = Core.settings.getString(BLACKLIST_KEY, "");
    for (String s : raw.split("\\|")) {
      if (!s.isEmpty())
        blacklist.add(s);
    }
    clearEligibilityCache();
  }

  private static void ensureBlacklistLoaded() {
    if (!blacklistLoaded)
      loadBlacklist();
  }

  /** 把名单写回 Core.settings。 */
  public static void saveBlacklist() {
    StringBuilder sb = new StringBuilder();
    for (String s : blacklist) {
      if (sb.length() > 0)
        sb.append('|');
      sb.append(s);
    }
    if (Core.settings != null) {
      Core.settings.put(BLACKLIST_KEY, sb.toString());
      Core.settings.saveValues();
    }
    clearEligibilityCache();
  }

  /** 设置界面用：把某个方块标成"不组合/可组合"，返回是否真的变了。 */
  public static boolean setBlocked(String name, boolean blocked) {
    if (name == null)
      return false;
    ensureBlacklistLoaded();
    boolean changed = blocked ? blacklist.add(name) : blacklist.remove(name);
    if (changed) {
      saveBlacklist();
      if (blocked) {
        // 关掉组合：**立刻**抛弃组合特性，不能等到下一轮重算 ——
        // 重算前的这十几毫秒里玩家看到的还是"物品/液体/电力照样共享、容量还是叠加的"。
        detachBlocked(name);
      } else {
        needsRescan = true; // 重新打开：下一轮重算先全图重扫，把它重新登记回 tracked
      }
      markDirty();          // 立刻重算分组（不用等下一次方块变化）
    }
    return changed;
  }

  /** 这个方块名是不是被手动标成"不组合"。 */
  public static boolean isBlocked(String name) {
    ensureBlacklistLoaded();
    return name != null && blacklist.contains(name);
  }

  /** 手动"不组合"的名单（拷贝）。 */
  public static arc.struct.Seq<String> blockedNames() {
    ensureBlacklistLoaded();
    arc.struct.Seq<String> out = new arc.struct.Seq<>();
    for (String s : blacklist)
      out.add(s);
    out.sort();
    return out;
  }

  public static boolean eligible(Block b) {
    if (b == null) return false;
    ensureBlacklistLoaded();
    Boolean cached = eligibleCache.get(b);
    if (cached != null) return cached;
    boolean r = computeEligible(b, false);
    eligibleCache.put(b, r);
    return r;
  }

  /**
   * 设置界面用：忽略"手动不组合"名单，只看这个方块**本来**能不能组合。
   * （界面上要能把它重新勾回"可组合"，所以列清单时不能受手动名单影响）
   */
  public static boolean eligibleByClass(Block b) {
    if (b == null) return false;
    ensureBlacklistLoaded();
    return computeEligible(b, true);
  }

  private static boolean computeEligible(Block b, boolean ignoreManualBlacklist) {
    if (!enabled) return false;
    // 【关掉相邻自动组合 = 协作组合也关】协作组合本身就是"相邻同类机器自动并池"，
    // 组合总开关关掉 / "只保留超级组合"时它必须一起停（否则玩家看到的是"开关关了、mod 方块
    // 之间还在共享库存/容量"）。关掉那一瞬间的"拆开"由 ComboTeams.detachEverything() 调
    // rebuild() 完成（这里判 false 就走 rebuild 里的脱离分支：还原容量/导电性 + 各拿一份）。
    if (!combine.util.ComboTeams.autoCombine()) return false;
    // 【不组合名单】传输类等明确不该组合的类（含 js/java 子类）直接排除；
    // 界面上列清单（ignoreManualBlacklist=true）时只看类名单，手动屏蔽的也要能列出来再勾回去
    if (ignoreManualBlacklist ? NoCombo.blockedByClass(b) : NoCombo.blocked(b)) return false;
    if (b.getClass().getName().startsWith("combine.")) return false;
    // 原版方块原则上不碰：combine 能安全替换的已经替换掉了，剩下的保持原样。
    // 唯独纯电力发电机和纯热量导热方块没有可供 CombinedXxx 深拷贝的稳定同名结构，
    // 却正是协作组合能安全覆盖的类型（只共享电力/热量，不改方块类与运行逻辑）。
    if (b.getClass().getName().startsWith("mindustry.")
        && !(b instanceof PowerGenerator || b instanceof HeatConductor)) return false;
    if (!ignoreManualBlacklist && blacklist.contains(b.name)) return false;
    if (b instanceof CoreBlock) return false;
    if (!b.hasItems && !b.hasLiquids && !b.hasPower
        && !(b instanceof HeatProducer || b instanceof HeatCrafter || b instanceof HeatConductor))
      return false;
    return familyOk(b);
  }

  /**
   * 「生产/仓储」家族白名单：只有这些家族的（子类）方块才允许共享池子。
   * 炮塔、核心、发电机、力墙…… 共享物品池语义不对，直接不算。
   */
  static boolean familyOk(Block b) {
    if (anyFamily) {
      // 任意 mod 方块：只要它真的有可共享的东西（物品/液体/电力/热量）就算
      return b.hasItems || b.hasLiquids || b.hasPower
          || b instanceof HeatProducer || b instanceof HeatCrafter || b instanceof HeatConductor;
    }
    return b instanceof GenericCrafter        // 含 AttributeCrafter / HeatCrafter / Separator
        || b instanceof Drill                 // 含 BeamDrill / BurstDrill
        || b instanceof WallCrafter
        || b instanceof Pump
        || b instanceof SolidPump
        || (b instanceof StorageBlock && !(b instanceof CoreBlock));
  }

  /**
   * 两台机器能不能连成一组：同队 + 都合规 + （同方块 或 允许跨类型）。
   * 跨类型时"谁吃谁"由各自的 acceptItem/acceptLiquid 决定，池子共享即可，
   * 中间产物靠 {@link #applyDumpOrders} 的顺序保护。
   */
  /** 供界面/测试用的公开别名（判定规则和分组完全一致）。 */
  public static boolean linkablePublic(Building a, Building b) {
    return linkable(a, b);
  }

  static boolean linkable(Building a, Building b) {
    if (a == null || b == null || a == b) return false;
    if (!ComboReflect.inWorld(a) || !ComboReflect.inWorld(b)) return false;
    if (a.team != b.team) return false;
    // 连接件当导线：只连"协作组合方块 / 别的连接件"，绝不把普通方块（传送带、容器、核心…）卷进组
    if (isLinker(a)) return joinable(b);
    if (isLinker(b)) return joinable(a);
    if (!eligible(a.block) || !eligible(b.block)) return false;
    // 【这里必须按"同一个方块"，不能按方块类】JS 模组的方块经常成批共享同一个
    // Rhino JavaAdapter 类（实测废土科技 103 个可组合方块只落在 26 个类里，
    // 光 `adapter42` 一个类就对应 28 个**完全不同**的建筑）。按类分组会把这 28 个
    // 毫不相干的建筑在基地里并成一个巨型协作组 —— 每帧搬运/每次放置重算都按组大小走，
    // 表现就是"加一个新建筑就卡一下、组合工厂的优化没了"（用户报的）。
    // 跨类型成组仍然由 allowCrossType 控制（默认关），要跨方块组合就走组合节点/连接器。
    return a.block == b.block || allowCrossType;
  }

  /** 组合连接器 / 组合节点：跨组合体的"导线"（不进成员表）。 */
  public static boolean isLinker(Building b) {
    return b instanceof ComboConnector.ComboConnectorBuild
        || b instanceof ComboNode.ComboNodeBuild;
  }

  /** 能不能被接进组合体：协作组合方块本身，或者连接件。 */
  static boolean joinable(Building b) {
    return ComboReflect.inWorld(b) && (eligible(b.block) || isLinker(b));
  }

  /**
   * 抓取基础容量：内容装配刚结束时调用，此时还没有任何放大。
   *
   * 【重要】只认第一次抓到的值。itemCapacity / liquidCapacity / conductivePower 这几个
   * 字段组合逻辑自己会按组放大（applyCapacities / applyPowerSharing），而这个方法在
   * {@code WorldLoadEvent} 上也会被叫一次 —— 如果那时再抓一遍，抓到的就是"已经放大过"
   * 的值，于是每存/读档一次容量就翻一倍（饱和火力倾倒站那种"读写后容量一直涨"）。
   */
  public static void captureBaseCaps() {
    for (Block b : content.blocks()) {
      // **所有方块都登记**，别只登记"当前合规"的：被手动标了"不组合"的方块在启动时是不合规的，
      // 玩家在设置里把它打开的那一刻再抓，抓到的就是"已经被放大过"的值 ——
      // 于是它关掉后容量还原不回去、导电性也还原不回去（就剩一个"还在共享"的假象）。
      // 只认第一次抓到的值（putIfAbsent 语义）：内容装配阶段抓的一定是没放大的原值。
      if (!baseItemCap.containsKey(b)) baseItemCap.put(b, b.itemCapacity);
      if (!baseLiquidCap.containsKey(b)) baseLiquidCap.put(b, b.liquidCapacity);
      if (!baseConductive.containsKey(b)) baseConductive.put(b, b.conductivePower);
    }
  }

  static int baseItemCap(Block b) {
    Integer v = baseItemCap.get(b);
    if (v == null) {
      // 兜底：第一次就登记（正常情况下 register/WorldLoad 已经抓过）
      v = b.itemCapacity;
      baseItemCap.put(b, v);
      baseLiquidCap.put(b, b.liquidCapacity);
    }
    return v;
  }

  static float baseLiquidCap(Block b) {
    Float v = baseLiquidCap.get(b);
    if (v == null) {
      baseItemCap.put(b, b.itemCapacity);
      v = b.liquidCapacity;
      baseLiquidCap.put(b, v);
    }
    return v;
  }

  /** 组合节点登记 / 注销（由 {@link ComboNode.ComboNodeBuild} 自己调用）。 */
  public static void trackNode(Building node) {
    if (node != null && trackedNodes.add(node)) dirty = true;
  }

  /**
   * 已登记的组合节点（拷贝一份）。
   *
   * 【组合墙用】节点是跨距离连接的：节点不在墙的邻格里，墙自己没法从 proximity 发现"有人连我"。
   * 所以墙重算分组时来这份名单里找"links 里有我的节点"。
   */
  public static Seq<Building> trackedNodesCopy() {
    Seq<Building> out = new Seq<>();
    for (Building b : trackedNodes) out.add(b);
    return out;
  }

  public static void untrackNode(Building node) {
    if (node != null && trackedNodes.remove(node)) dirty = true;
  }

  /**
   * 已登记的协作组合方块（拷贝一份）。
   *
   * 【ComboNet 用】存储类方块（container/vault/各种 mod 仓库）都是 {@code update = false}，
   * 根本不在 {@code Groups.build} 里 —— ComboNet.rebuild 只看 Groups.build 的话，
   * 它们就永远进不了网络分量，"放两个仓库下去应该同池"这种就失效了。
   */
  public static Seq<Building> trackedBuildings() {
    Seq<Building> out = new Seq<>();
    for (Building b : tracked) out.add(b);
    return out;
  }

  /** 仓库登记 / 注销（由 {@link CombinedStorageBuild} 自己调用）。 */

  /** 读档后全图扫一遍重建登记表。 */
  public static void rescan() {
    tracked.clear();
    trackedNodes.clear();
    if (world != null && world.tiles != null && anyEligibleBlock()) {
      // 【性能】"只玩家组合"打开时，AI 敌人那些协作组合方块根本不参与分组 ——
      // 别把它们登记进来（大型基地图上是几万台：登记了每轮 rebuild 都要过一遍）。
      boolean playersOnly = combine.util.ComboTeams.playersOnly;
      for (Tile tile : world.tiles) {
        if (tile == null || tile.build == null) continue;
        if (playersOnly && !combine.util.ComboTeams.playerTeam(tile.build.team)) continue;
        if (eligible(tile.block())) tracked.add(tile.build);
        if (tile.build instanceof ComboNode.ComboNodeBuild) trackedNodes.add(tile.build);
      }
    }
    dirty = true;
  }

  /**
   * 内容表里还有没有任何"可协作组合"的方块？没有的话 rescan 连全图都不用扫
   * （纯原版地图上这张表是空的，而 rescan 要遍历 512×512=26 万格，读档后第一帧能吃掉几十毫秒）。
   */
  private static boolean anyEligibleBlock() {
    try {
      for (Block b : mindustry.Vars.content.blocks())
        if (eligible(b))
          return true;
    } catch (Throwable ignored) {
    }
    return false;
  }

  /**
   * 设置里把某个方块标成"不组合"：让它**现存的每一台**立刻脱离组合。
   *
   * 【为什么不能只置脏等下一轮 rebuild】
   * rebuild 的前一步是 rescan()（重新启用时要靠它把方块登记回来），而 rescan 会
   * {@code tracked.clear()} 再按 eligible 重扫 —— 被关掉的方块这时已经不合格，
   * 于是它直接从登记表里消失，后面那段"清掉不合规成员 → 脱离"的循环根本看不到它：
   * 库存模块还是共享的那一份、容量还是放大后的值，表现就是"关掉组合后什么都没变"。
   * 所以这里当场把它拆出来。
   */
  private static void detachBlocked(String name) {
    // 先把命中的建筑拷出来：detachBuild 自己也要遍历 tracked，
    // 直接在 ObjectSet 上嵌套迭代会抛 "#iterator() cannot be used nested"。
    Seq<Building> hit = null;
    for (Building b : tracked) {
      if (b == null || b.block == null || !name.equals(b.block.name))
        continue;
      if (hit == null)
        hit = new Seq<>();
      hit.add(b);
    }
    if (hit == null)
      return;

    for (Building b : hit) {
      if (ComboReflect.inWorld(b)) {
        restoreBlockProps(b.block);
        detachBuild(b);
      }
      tracked.remove(b);
    }
    // 组结果里也不要再算它：面板/查询下一次 rebuild 才会刷新，这里先让查询失效
    groupsReady = false;
  }

  /**
   * 被"关掉组合"的方块：容量/导电性还原成**原版基础值**。
   *
   * applyCapacities / applyPowerSharing 都只处理"合规方块"，所以方块一旦被关掉，
   * 之前被放大的 itemCapacity/liquidCapacity 和被人为打开的 conductivePower 就没人管了
   * —— 表现就是"关掉组合后电力还在共享、容量还是叠加的"。
   */
  private static void restoreBlockProps(Block block) {
    if (block == null) return;
    try {
      int cap = baseItemCap(block);
      if (block.itemCapacity != cap) block.itemCapacity = cap;
      float lcap = baseLiquidCap(block);
      if (Math.abs(block.liquidCapacity - lcap) > 0.001f) block.liquidCapacity = lcap;
      boolean cond = baseConductive.get(block, block.conductivePower);
      if (block.conductivePower != cond) block.conductivePower = cond;
    } catch (Throwable t) {
      Log.err("[combine] 还原方块属性失败", t);
    }
  }

  /**
   * 这一台不再参与组合：把它的库存从共享池里"切"出来（按容量比例分一份，其余留给组），
   * 换成独立模块，并让电网按还原后的导电性重新划分。
   */
  private static void detachBuild(Building b) {
    try {
      // 物品
      ItemModule sharedItems = null;
      for (Building m : tracked) {
        if (m != b && m.isValid() && m.items != null && m.items == b.items) { sharedItems = b.items; break; }
      }
      if (sharedItems != null) {
        int myCap = Math.max(baseItemCap(b.block), 1);
        int allCap = 0;
        for (Building m : tracked) if (m.isValid() && m.items == sharedItems) allCap += Math.max(baseItemCap(m.block), 1);
        ItemModule own = new ItemModule();
        for (Item item : content.items()) {
          int total = sharedItems.get(item);
          if (total <= 0) continue;
          int share = (int) Math.round((double) total * myCap / Math.max(allCap, 1));
          share = Math.max(0, Math.min(share, total));
          if (share > 0) {
            own.add(item, share);
            sharedItems.remove(item, share);
          }
        }
        b.items = own;
      }
      // 液体
      LiquidModule sharedLiquids = null;
      for (Building m : tracked) {
        if (m != b && m.isValid() && m.liquids != null && m.liquids == b.liquids) { sharedLiquids = b.liquids; break; }
      }
      if (sharedLiquids != null) {
        float myCap = Math.max(baseLiquidCap(b.block), 1f);
        float allCap = 0f;
        for (Building m : tracked) if (m.isValid() && m.liquids == sharedLiquids) allCap += Math.max(baseLiquidCap(m.block), 1f);
        LiquidModule own = new LiquidModule();
        for (Liquid liquid : content.liquids()) {
          float total = sharedLiquids.get(liquid);
          if (total <= 0.001f) continue;
          float share = (float) Math.min(total, total * myCap / Math.max(allCap, 0.001f));
          if (share > 0.001f) {
            own.add(liquid, share);
            sharedLiquids.remove(liquid, share);
          }
        }
        b.liquids = own;
      }
      // 电网：导电性已经还原，重新划分一次
      if (b.power != null) {
        try {
          new mindustry.world.blocks.power.PowerGraph().reflow(b);
          combine.util.ComboPower.mark(b);
        } catch (Throwable ignored) {
        }
      }
    } catch (Throwable t) {
      Log.err("[combine] 脱离组合失败", t);
    }
  }

  /** 把变化格子（含其 4 邻居）附近的合规建筑补进登记表。 */
  private static void absorbChangedTiles() {
    for (Tile t : changedTiles) {
      for (int i = 0; i < 5; i++) {
        Tile cur = i == 0 ? t : t.nearby(i - 1);
        if (cur == null || cur.build == null) continue;
        if (eligible(cur.block())) tracked.add(cur.build);
      }
    }
    changedTiles.clear();
  }

  // ==================== 每帧驱动 ====================

  private static void update() {
    if (state == null || world == null || world.isGenerating()) return;
    if (dirty) {
      dirty = false;
      // 这一轮是被"世界被改过"叫醒的：电网拓扑可能已经变了（原版拆网/并网只认"一台一份模块"），
      // 排一次整张图对账，见 combine.util.ComboPower
      combine.util.ComboPower.markWorld();
      // 读档后头几帧也算去重语义（见 ComboNet.loadDedupeFrames）
      boolean dedupe = dedupeOnce || ComboNet.pendingLoadDedupe();
      dedupeOnce = false;
      rebuild(dedupe);
    }
    // 跨类型组的中间产物保护：每帧把搬运轮转压回组长那边（只在池子里真有中间产物时）
    maintainIntermediates();

    // 组内共享热量：在"建筑更新之后"统一补齐（写了 heat 值，下一帧耗热方就能用上）
    // 见 shareHeatPass()；注册在 Trigger.afterGameUpdate

    // 组级收料：管道/传送带贴在"不需要这种料"的成员上时，替整组把料收进来
    if (groupIntake && ++intakeCounter >= Math.max(intakeInterval, 1)) {
      intakeCounter = 0;
      intakeFromNeighbors();
    }
  }

  public static void rebuild() {
    rebuild(false);
  }

  /** 重算所有协作组合：分组、并池、拆池、容量。 */
  public static void rebuild(boolean dedupe) {
    if (state == null || world == null || world.isGenerating()) {
      dirty = true;
      return;
    }
    try {
      if (needsRescan) {
        needsRescan = false;
        rescan();
      }
      absorbChangedTiles();

      // 清掉已经消失的成员；**被设置里关掉组合的方块**要就地"脱离"：
      // 换回独立模块（按容量比例从共享池里分一份）+ 还原容量/导电性，
      // 否则关掉之后物品/液体/电力还是共享的、容量也还是放大后的值。
      ObjectSet<Building> dead = new ObjectSet<>();
      Seq<Building> detach = null;
      for (Building b : tracked) {
        // 读档后旧世界的对象 isValid() 还是 true，只有 inWorld 能认出来
        if (!ComboReflect.inWorld(b)) {
          dead.add(b);
        } else if (!eligible(b.block)) {
          // 脱离动作要**延后**做：detachBuild 自己也要遍历 tracked，
          // 就地调用会抛 "#iterator() cannot be used nested"（那会被外层 catch 吞掉，
          // 变成每帧重算一次 → 掉帧）。
          if (detach == null)
            detach = new Seq<>();
          detach.add(b);
        }
      }
      if (detach != null) {
        for (Building b : detach) {
          restoreBlockProps(b.block);
          detachBuild(b);
          dead.add(b);
        }
      }
      for (Building b : dead) tracked.remove(b);

      // ---- 0) 组合节点跨距离连线：成员 -> 链到它的节点们 ----
      ObjectMap<Building, Seq<Building>> linkedByNodes = new ObjectMap<>();
      for (Building nb : trackedNodes) {
        if (!ComboReflect.inWorld(nb) || !(nb instanceof ComboNode.ComboNodeBuild node) || node.links == null) continue;
        // 【共享配置里有一项没勾就不跨这个节点】两端不再是一台机器，剩下的部分由 ComboNet
        // 在网络层逐项共享（见 combine.net.ComboShare）；否则"没勾的那项"还是混在一起。
        if (!combine.net.ComboShare.allowLocalJoin(node)) continue;
        for (int i = 0; i < node.links.size; i++) {
          Building link = world.build(node.links.get(i));
          if (link == null || !link.isValid() || !eligible(link.block)) continue;
          linkedByNodes.get(link, Seq::new).add(node);
        }
      }

      // ---- 1) 连通分量：同队 + 4 邻接 +（同方块 或 允许跨类型），并可穿过连接器/节点 ----
      ObjectSet<Building> visited = new ObjectSet<>();
      Seq<Seq<Building>> comps = new Seq<>();
      for (Building start : tracked) {
        if (visited.contains(start)) continue;
        Seq<Building> comp = new Seq<>();
        Queue<Building> queue = new Queue<>();
        queue.addLast(start);
        visited.add(start);
        ObjectSet<Building> seen = new ObjectSet<>();
        seen.add(start);
        while (!queue.isEmpty()) {
          Building cur = queue.removeFirst();
          // 连接件只当"导线"穿过去，不进成员表（它没有物品/液体模块）
          if (!isLinker(cur)) comp.add(cur);
          // 【共享配置里有一项没勾的连接件不通】这条连接件两端不再是一台机器
          //（见 combine.net.ComboShare.allowLocalJoin），剩下的部分交给 ComboNet 逐项共享。
          boolean cross = !isLinker(cur) || combine.net.ComboShare.allowLocalJoin(cur);
          if (cross) {
            for (Building nb : cur.proximity) {
              if (joinable(nb) && combine.util.ComboTeams.playerTeam(nb.team) && linkable(cur, nb) && seen.add(nb)) {
                if (!isLinker(nb)) visited.add(nb);
                queue.addLast(nb);
              }
            }
            // 被"远处组合节点"链到的成员：从该成员出发也能走到那些节点（节点再连回它的其它目标）
            Seq<Building> viaNodes = linkedByNodes.get(cur);
            if (viaNodes != null) {
              for (Building n : viaNodes) {
                if (seen.add(n)) queue.addLast(n);
              }
            }
            // 组合节点：它激光连出去的 links 也算连通
            if (cur instanceof ComboNode.ComboNodeBuild node && node.links != null) {
              for (int i = 0; i < node.links.size; i++) {
                Building link = world.build(node.links.get(i));
                if (link != null && link.isValid() && link.team == cur.team
                    && joinable(link) && seen.add(link)) {
                  if (!isLinker(link)) visited.add(link);
                  queue.addLast(link);
                }
              }
            }
          }
        }
        comps.add(comp);
      }

      // ---- 2) 记下分组结果（组长选举），供 ComboReflect/ComboNet/面板共用 ----
      long sig = 1125899906842597L;
      groupLeaderByPos.clear();
      groupByLeaderPos.clear();
      for (Seq<Building> comp : comps) {
        if (comp.isEmpty()) continue;
        Building leader = comp.first();
        for (Building b : comp) if (b.pos() < leader.pos()) leader = b;
        for (Building b : comp) {
          groupLeaderByPos.put(b.pos(), leader);
          sig = sig * 31 + b.pos();
        }
        sig = sig * 31 + leader.pos();
        groupByLeaderPos.put(leader.pos(), comp);
      }
      groupsReady = true;
      // 分组变了 → 让 ComboNet 重算：物品/液体池的合并与拆分交给它统一处理，
      // 这样协作组合方块和原版替换出来的组合方块才能共享同一个池、互相显示。
      // 这里同步重算而不是置脏：CoopCombo.rebuild 本身最多每帧一次（事件都只置脏，
      // 由 Trigger.update 统一调），同步做掉既是"放下去立刻同池"的旧手感，
      // 也不会多出重建次数；真在 ComboNet 内部（重入）时退回置脏。
      if (sig != lastGroupSignature) {
        lastGroupSignature = sig;
        if (ComboNet.rebuilding()) {
          ComboNet.markDirty();
        } else {
          ComboNet.rebuild();
        }
      }

      // ---- 3) 组容量：按"这一整张网络"的基础容量之和（ComboNet 统一算） ----
      applyCapacities(comps);

      // ---- 5) 搬运顺序：把"会吃这种料"的邻居排前面，护住跨类型组里的中间产物 ----
      applyDumpOrders(comps);

      // ---- 6) 组内共享电力：成组时把这些方块当导电体（等价 conductivePower = true）----
      applyPowerSharing(comps);

      // ---- 7) 记住"多成员组"，供每帧的组级收料用 ----
      intakeGroups.clear();
      for (Seq<Building> comp : comps) {
        if (comp.size > 1) intakeGroups.add(comp);
      }

      if (debug) {
        StringBuilder sb = new StringBuilder();
        for (Seq<Building> comp : comps) {
          if (comp.size <= 1) continue;
          sb.append('[').append(comp.first().block.name).append(" x").append(comp.size).append("] ");
        }
        Log.info("[combine] 协作组合重算: 登记=@ 组=@ 多机组=@", tracked.size, comps.size, sb);
      }
    } catch (Throwable t) {
      Log.err("[combine] 协作组合重算失败", t);
      dirty = true;
    }
  }

  /**
   * 把一个分量的库存并到 leader 的模块上，并让所有成员共用该模块。
   *
   * 【遗留】并池/拆池现在统一由 {@link ComboNet} 负责（协作组合和原版组合方块要在
   * 同一张网络里共用一份模块，只能有一个地方做这件事），这个方法已经不再被调用。
   */
  private static void mergeInto(Seq<Building> comp, Building leader, boolean dedupe) {
    ItemModule pool = leader.items != null ? leader.items : new ItemModule();
    LiquidModule liquidPool = leader.liquids != null ? leader.liquids : new LiquidModule();

    for (Building b : comp) {
      if (b == leader) continue;
      // 物品：内容完全相同的副本（读档产物）直接丢；否则真正并入
      if (b.items != null && b.items != pool) {
        boolean duplicate = dedupe && sameItems(b.items, pool);
        if (!duplicate) moveItems(b.items, pool);
      }
      if (b.liquids != null && b.liquids != liquidPool) {
        boolean duplicate = dedupe && sameLiquids(b.liquids, liquidPool);
        if (!duplicate) moveLiquids(b.liquids, liquidPool);
      }
    }

    for (Building b : comp) {
      b.items = pool;
      b.liquids = liquidPool;
    }
  }

  /** 同一模块被多个分量引用（刚拆组）时按"分量容量占比"拆分，物品和液体都拆。同 {@link #mergeInto}：已由 ComboNet 接管。 */
  private static void splitSharedModules(Seq<Seq<Building>> comps) {
    ObjectMap<ItemModule, Seq<Seq<Building>>> itemUsers = new ObjectMap<>();
    ObjectMap<LiquidModule, Seq<Seq<Building>>> liquidUsers = new ObjectMap<>();

    for (Seq<Building> comp : comps) {
      if (comp.isEmpty()) continue;
      Building first = comp.first();
      if (first.items != null) itemUsers.get(first.items, Seq::new).add(comp);
      if (first.liquids != null) liquidUsers.get(first.liquids, Seq::new).add(comp);
    }

    for (ObjectMap.Entry<ItemModule, Seq<Seq<Building>>> entry : itemUsers) {
      Seq<Seq<Building>> users = entry.value;
      if (users.size <= 1) continue;
      ItemModule old = entry.key;
      int n = users.size;
      long[] caps = new long[n];
      long totalCap = 0;
      for (int i = 0; i < n; i++) {
        long cap = 0;
        for (Building b : users.get(i)) cap += baseItemCap(b.block);
        caps[i] = Math.max(cap, 1);
        totalCap += caps[i];
      }
      ItemModule[] shares = new ItemModule[n];
      for (int i = 0; i < n; i++) shares[i] = new ItemModule();
      for (Item item : content.items()) {
        int total = old.get(item);
        if (total <= 0) continue;
        int remaining = total;
        for (int i = 0; i < n; i++) {
          int ideal = i == n - 1 ? remaining : (int) (total * caps[i] / totalCap);
          ideal = Math.max(0, Math.min(ideal, remaining));
          if (ideal > 0) {
            shares[i].add(item, ideal);
            remaining -= ideal;
          }
        }
      }
      for (int i = 0; i < n; i++) {
        long cap = caps[i];
        for (Building b : users.get(i)) {
          b.items = shares[i];
          for (Item item : content.items()) {
            int amount = shares[i].get(item);
            if (amount > cap) shares[i].remove(item, amount - (int) cap);
          }
        }
      }
    }

    for (ObjectMap.Entry<LiquidModule, Seq<Seq<Building>>> entry : liquidUsers) {
      Seq<Seq<Building>> users = entry.value;
      if (users.size <= 1) continue;
      LiquidModule old = entry.key;
      int n = users.size;
      float[] caps = new float[n];
      float totalCap = 0f;
      for (int i = 0; i < n; i++) {
        float cap = 0f;
        for (Building b : users.get(i)) cap += baseLiquidCap(b.block);
        caps[i] = Math.max(cap, 0.001f);
        totalCap += caps[i];
      }
      LiquidModule[] shares = new LiquidModule[n];
      for (int i = 0; i < n; i++) shares[i] = new LiquidModule();
      for (Liquid liquid : content.liquids()) {
        float total = old.get(liquid);
        if (total <= 0.001f) continue;
        float remaining = total;
        for (int i = 0; i < n; i++) {
          float ideal = i == n - 1 ? remaining : total * caps[i] / totalCap;
          ideal = Math.max(0f, Math.min(ideal, remaining));
          if (ideal > 0.001f) {
            shares[i].add(liquid, ideal);
            remaining -= ideal;
          }
        }
      }
      for (int i = 0; i < n; i++) {
        float cap = caps[i];
        for (Building b : users.get(i)) {
          b.liquids = shares[i];
          for (Liquid liquid : content.liquids()) {
            float amount = shares[i].get(liquid);
            if (amount > cap) shares[i].remove(liquid, amount - cap);
          }
        }
      }
    }
  }

  /**
   * 组容量 = 组内各成员「基础容量」之和（跨类型组也是把不同方块的基础容量加起来）。
   *
   * 容量是**方块级**字段（getMaximumAccepted 读的就是 block.itemCapacity），
   * 所以同一种方块在多个组里出现时，取它见过的最大值 —— 只松上限，不会丢物品。
   * 没有成组的方块恢复成自己的基础容量。
   */
  private static void applyCapacities(Seq<Seq<Building>> comps) {
    ObjectMap<Block, Integer> wantItems = new ObjectMap<>();
    ObjectMap<Block, Float> wantLiquids = new ObjectMap<>();

    for (Seq<Building> comp : comps) {
      if (comp.isEmpty()) continue;
      Building sample = comp.first();
      if (sample == null || !sample.isValid()) continue;
      // 容量按"整张网络"算：协作组合方块 + 通过连接器/节点接上的原版组合方块，
      // 都由 ComboNet 统计（它的 effective*Cap 就是各组员基础容量之和）。
      int itemTotal = ComboNet.effectiveItemCap(sample);
      float liquidTotal = ComboNet.effectiveLiquidCap(sample);
      for (Building b : comp) {
        Block block = b.block;
        wantItems.put(block, Math.max(wantItems.get(block, 0), itemTotal));
        wantLiquids.put(block, Math.max(wantLiquids.get(block, 0f), liquidTotal));
      }
    }

    for (Block b : content.blocks()) {
      if (!eligible(b)) continue;
      int want = wantItems.get(b, baseItemCap(b));
      if (b.itemCapacity != want) b.itemCapacity = want;

      float wantLiq = wantLiquids.get(b, baseLiquidCap(b));
      if (Math.abs(b.liquidCapacity - wantLiq) > 0.001f) {
        b.liquidCapacity = wantLiq;
      }
    }
  }

  /** 网络/界面计算用：这个方块交给协作组合时"放大前"的基础物品容量；不属于协作组合返回 null。 */
  public static Integer coopBaseItemCap(Building b) {
    if (b == null || b.block == null || !eligible(b.block)) return null;
    return baseItemCap(b.block);
  }

  /** 同上，液体版。 */
  public static Float coopBaseLiquidCap(Building b) {
    if (b == null || b.block == null || !eligible(b.block)) return null;
    return baseLiquidCap(b.block);
  }

  // ==================== 搬运顺序 / 中间产物保护 ====================

  /**
   * 让每台成员搬东西时**先试组内邻居**：
   * [组内会吃料的机器邻居] → [组外邻居（传送带/容器/…）] → [组内纯仓储邻居]
   *
   * 原版 dump() 是按 proximity 顺序找第一个"愿意接收"的邻居。
   * 组内机器只接受自己需要的物品/液体，所以这样排之后：
   *   · A 产的、B 要用的中间产物 → 被 B 吃掉，留在组里（不会被倒到外面的传送带）；
   *   · 纯粹的产品 → 组内机器都不收，自然落到组外（传送带/容器）正常出货；
   *   · 纯仓储邻居排最后 → 外面塞不下时才囤进组里，不会把出货口堵死。
   *
   * 同时把跨类型组的 cdump（搬运轮转下标）压回 0，保证每次搬运都从"会吃料的邻居"开始试 ——
   * 否则轮转会漂到组外邻居上，中间产物还是会漏出去。
   */
  private static void applyDumpOrders(Seq<Seq<Building>> comps) {
    crossGroups.clear();
    if (!enabled) return;

    for (Seq<Building> comp : comps) {
      if (comp.size <= 1) continue;
      boolean mixed = false;
      for (Building b : comp) {
        if (b.block != comp.first().block) {
          mixed = true;
          break;
        }
      }
      if (!mixed) continue; // 同一种方块之间没有"中间产物"问题，保持原版的搬运轮转
      crossGroups.add(comp);

      for (Building m : comp) {
        if (m.proximity == null) continue;
        Seq<Building> ordered = new Seq<>(m.proximity.size);
        // 1) 组内"机器"邻居（不是纯仓储的那种）
        for (Building nb : m.proximity) {
          if (isSibling(comp, nb) && !isStorageLike(nb)) ordered.add(nb);
        }
        // 2) 组外邻居，保持原有相对顺序
        for (Building nb : m.proximity) {
          if (!isSibling(comp, nb)) ordered.add(nb);
        }
        // 3) 组内纯仓储邻居
        for (Building nb : m.proximity) {
          if (isSibling(comp, nb) && isStorageLike(nb)) ordered.add(nb);
        }
        if (!sameOrder(m.proximity, ordered)) {
          m.proximity.clear();
          m.proximity.addAll(ordered);
        }
      }
    }
  }

  private static boolean isSibling(Seq<Building> comp, Building b) {
    return b != null && comp.contains(b, true);
  }

  /** 纯仓储类邻居：什么都要，排最后，免得把出货口堵死。 */
  private static boolean isStorageLike(Building b) {
    return b != null && b.block instanceof StorageBlock;
  }

  private static boolean sameOrder(Seq<Building> a, Seq<Building> b) {
    if (a.size != b.size) return false;
    for (int i = 0; i < a.size; i++) {
      if (a.get(i) != b.get(i)) return false;
    }
    return true;
  }

  /**
   * 每帧维护：跨类型组的池子里只要还有"组内有人要吃"的货，就把成员的搬运轮转压回 0，
   * 保证这次搬运先喂组内的机器。
   * 只在池子内容签名变化时重算一次（acceptItem 对 JS 方块是跨语言调用，不能每帧狂打）。
   */
  private static void maintainIntermediates() {
    if (!enabled || !keepIntermediates) return;
    for (Seq<Building> comp : crossGroups) {
      if (comp.isEmpty()) continue;
      Building leader = comp.first();
      if (leader == null || !leader.isValid() || leader.items == null) continue;

      long sig = poolSignature(leader);
      Integer key = leader.pos();
      Long cachedSig = absorbSig.get(key);
      boolean need;
      if (cachedSig != null && cachedSig.longValue() == sig) {
        need = absorbNeed.get(key, 0) == 1;
      } else {
        need = computeAbsorbNeed(comp);
        absorbSig.put(key, sig);
        absorbNeed.put(key, need ? 1 : 0);
      }
      if (!need) continue;
      for (Building m : comp) {
        if (m != null && m.isValid()) m.cdump = 0;
      }
    }
  }

  /** 池子内容签名：哪些物品/液体不是空的（够用来判断"要不要重算"就行）。 */
  private static long poolSignature(Building leader) {
    long h = 1125899906842597L;
    if (leader.items != null) {
      for (Item item : content.items()) {
        if (leader.items.get(item) > 0) h = h * 31 + item.id;
      }
    }
    if (leader.liquids != null) {
      int base = content.items().size;
      for (Liquid liquid : content.liquids()) {
        if (leader.liquids.get(liquid) > 0.001f) h = h * 31 + base + liquid.id;
      }
    }
    return h;
  }

  /**
   * 池子里有没有"组内某台机器愿意接收"的物品/液体（= 中间产物还留在组里、值得保护）。
   * 只在池子内容签名变化时调用，而且检查数量封顶 —— JS 方块的 acceptItem 是跨语言调用，
   * 几百台一组的混合体不能每帧狂打。
   */
  private static boolean computeAbsorbNeed(Seq<Building> comp) {
    Building src = comp.first();
    ItemModule items = src.items;
    LiquidModule liquids = src.liquids;
    int checkedSiblings = 0;
    outer:
    for (Building m : comp) {
      if (m == src || m == null || !m.isValid()) continue;
      if (checkedSiblings++ >= maxAbsorbProbeSiblings) break;
      if (items != null) {
        int checked = 0;
        for (Item item : content.items()) {
          if (items.get(item) <= 0) continue;
          if (checked++ >= maxAbsorbProbeTypes) break;
          try {
            if (m.acceptItem(src, item)) return true;
          } catch (Throwable ignored) {
          }
        }
      }
      if (liquids != null) {
        int checked = 0;
        for (Liquid liquid : content.liquids()) {
          if (liquids.get(liquid) <= 0.001f) continue;
          if (checked++ >= maxAbsorbProbeTypes) continue outer;
          try {
            if (m.acceptLiquid(src, liquid)) return true;
          } catch (Throwable ignored) {
          }
        }
      }
    }
    return false;
  }

  /** 中间产物判定最多探几台邻居 / 几种货（性能护栏）。 */
  public static int maxAbsorbProbeSiblings = 8;
  public static int maxAbsorbProbeTypes = 6;

  /** 跨类型组（成员数的分量为"有中间产物风险"的组）。 */
  private static final Seq<Seq<Building>> crossGroups = new Seq<>();
  private static final arc.struct.IntMap<Long> absorbSig = new arc.struct.IntMap<>();
  private static final arc.struct.IntMap<Integer> absorbNeed = new arc.struct.IntMap<>();

  // ==================== 组内共享电力 ====================

  /**
   * 成组的方块打开 conductivePower（并让电网立刻并起来）；没有成组的还原成原值。
   * 只改"该方块确实有 ≥2 台成员"的那些 —— 散装单机保持原版行为，不会变成导线去桥接别的电网。
   */
  private static void applyPowerSharing(Seq<Seq<Building>> comps) {
    if (!sharePower) return;
    ObjectSet<Block> grouped = new ObjectSet<>();
    for (Seq<Building> comp : comps) {
      if (comp.size <= 1) continue;
      // 共享配置里没勾"电力"：这组不当导电体（各接各的电）
      if (!combine.net.ComboShare.shares(comp.first(), combine.net.ComboShare.POWER)) continue;
      for (Building b : comp) grouped.add(b.block);
    }

    for (Block b : content.blocks()) {
      if (!eligible(b) || !b.hasPower) continue;
      boolean base = baseConductive.get(b, b.conductivePower);
      boolean want = grouped.contains(b) || base;
      if (b.conductivePower == want) continue;

      b.conductivePower = want;
      // 让改动立刻生效：该方块在世界上已有的建筑要重新并网 / 拆网
      for (Building m : tracked) {
        if (m == null || !m.isValid() || m.block != b || m.power == null) continue;
        try {
          if (want) {
            m.updatePowerGraph();
          } else {
            // 还原成"纯耗电方"：重新划分电网（走不到的建筑留在原图里，等于拆分）
            new mindustry.world.blocks.power.PowerGraph().reflow(m);
          }
          // 并网/拆网可能有同组建筑留在原版那张没人更新的旧图上 —— 交给对账器收口
          combine.util.ComboPower.mark(m);
        } catch (Throwable ignored) {
        }
      }
    }
  }

  // ==================== 组级收料 ====================

  // ==================== 组内共享热量 ====================

  /** 每个 build 类缓存一份 "heat" 字段（HeatCrafterBuild/HeatConductorBuild/TurretBuild 都有）。 */
  private static final ObjectMap<Class<?>, java.lang.reflect.Field> heatFields = new ObjectMap<>();

  /**
   * 组内共享热量：组里所有产热方块的热量，供应给组里的耗热方块。
   *
   * 原版热量是"贴脸"算的（BuildingComp.calculateHeat 只统计 proximity 里的 HeatBlock），
   * 组合体里跨了一格就吃不到热。这里在**每帧建筑更新之后**统一补齐：
   * 组内 supply = Σ 各产热方的 heat()，耗热方的 heat 小于它就直接写上去
   * （写的是它自己那个 heat 字段，等价于"旁边多贴了产热方块"）。
   */
  public static void shareHeatPass() {
    if (!enabled || !shareHeat) return;
    for (Seq<Building> comp : intakeGroups) {
      if (comp.size <= 1) continue;
      // 共享配置里没勾"热量"：这组不通热
      if (!combine.net.ComboShare.shares(comp.first(), combine.net.ComboShare.HEAT)) continue;

      float supply = 0f;
      for (Building m : comp) {
        if (m == null || !m.isValid()) continue;
        if (m.block instanceof mindustry.world.blocks.heat.HeatProducer
            && m instanceof mindustry.world.blocks.heat.HeatBlock hb) {
          supply += Math.max(hb.heat(), 0f);
        }
      }
      if (supply <= 0.001f) continue;

      for (Building m : comp) {
        if (m == null || !m.isValid()) continue;
        if (!(m instanceof mindustry.world.blocks.heat.HeatConsumer hc)) continue;
        if (hc.heatRequirement() <= 0f) continue;
        java.lang.reflect.Field f = heatField(m.getClass());
        if (f == null) continue;
        try {
          float own = f.getFloat(m);
          if (own < supply - 0.001f) f.setFloat(m, supply);
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static java.lang.reflect.Field heatField(Class<?> cls) {
    java.lang.reflect.Field cached = heatFields.get(cls);
    if (cached != null) return cached == MISSING_HEAT_FIELD ? null : cached;
    Class<?> c = cls;
    while (c != null && c != Object.class) {
      try {
        java.lang.reflect.Field f = c.getDeclaredField("heat");
        f.setAccessible(true);
        heatFields.put(cls, f);
        return f;
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      } catch (Throwable ignored) {
        break;
      }
    }
    heatFields.put(cls, MISSING_HEAT_FIELD);
    return null;
  }

  /** 占位：表示"这个类没有 heat 字段"（ObjectMap 可以存 null，这里只为统一写法）。 */
  private static final java.lang.reflect.Field MISSING_HEAT_FIELD;
  static {
    java.lang.reflect.Field f = null;
    try {
      f = CoopCombo.class.getDeclaredField("intakeGroups");
    } catch (Throwable ignored) {
    }
    MISSING_HEAT_FIELD = f;
  }

  /**
   * 从贴着组的"运输类"邻居那里，把组内有人要的物品/液体搬进共享池。
   *
   * 判据：
   *   · 只有该成员自己**不收**时才动手（能正常走游戏流程的，一律不插手）；
   *   · 组里必须有另一个成员愿意收（acceptItem/acceptLiquid 为真）；
   *   · 源方必须是运输类方块（传送带/管道/路由器/运输桥/储液罐…），
   *     不去掏别的机器或容器的库存（那是装卸器/卸载器的活）。
   */
  public static void intakeFromNeighbors() {
    if (!enabled) return;
    for (Seq<Building> comp : intakeGroups) {
      if (comp.size <= 1) continue;
      boolean shareItems = combine.net.ComboShare.shares(comp.first(), combine.net.ComboShare.ITEMS);
      boolean shareLiquids = combine.net.ComboShare.shares(comp.first(), combine.net.ComboShare.LIQUIDS);
      // 没勾共享就别替组收料：那等于把料塞进"不共享"的那口池子
      if (!shareItems && !shareLiquids) continue;
      ItemModule pool = comp.first().items;
      LiquidModule liquidPool = comp.first().liquids;
      Building sample = comp.first();
      if (sample == null || !sample.isValid()) continue;
      int itemCap = Math.max(baseItemCap(sample.block) * comp.size, 1);
      float liquidCap = Math.max(baseLiquidCap(sample.block) * comp.size, 0.001f);

      for (Building m : comp) {
        if (m == null || !m.isValid() || m.proximity == null) continue;
        for (Building src : m.proximity) {
          if (src == null || !src.isValid()) continue;
          if (isSibling(comp, src)) continue;
          Block sb = src.block;
          if (sb == null || (sb.group != mindustry.world.meta.BlockGroup.transportation
              && sb.group != mindustry.world.meta.BlockGroup.liquids)) continue;

          // 物品
          if (shareItems && pool != null && src.items != null && src.items.total() > 0) {
            for (Item item : content.items()) {
              int available = src.items.get(item);
              if (available <= 0) continue;
              if (m.acceptItem(src, item)) continue;          // 游戏自己会处理
              if (!siblingAcceptsItem(comp, m, src, item)) continue;
              int room = itemCap - pool.get(item);
              int move = Math.min(Math.min(available, room), Math.max(intakeItemsPerSource, 1));
              if (move > 0) {
                pool.add(item, move);
                src.items.remove(item, move);
              }
            }
          }

          // 液体
          if (shareLiquids && liquidPool != null && src.liquids != null && src.liquids.currentAmount() > 0.001f) {
            for (Liquid liquid : content.liquids()) {
              float available = src.liquids.get(liquid);
              if (available <= 0.001f) continue;
              if (m.acceptLiquid(src, liquid)) continue;      // 游戏自己会处理
              if (!siblingAcceptsLiquid(comp, m, src, liquid)) continue;
              float room = liquidCap - liquidPool.get(liquid);
              float move = Math.min(Math.min(available, room), Math.max(intakeLiquidsPerSource, 1f));
              if (move > 0.001f) {
                liquidPool.add(liquid, move);
                src.liquids.remove(liquid, move);
              }
            }
          }
        }
      }
    }
  }

  /** 组里除了 self 之外，有没有成员愿意收这个物品。 */
  private static boolean siblingAcceptsItem(Seq<Building> comp, Building self, Building src, Item item) {
    for (Building other : comp) {
      if (other == self || other == null || !other.isValid()) continue;
      try {
        if (other.acceptItem(src, item)) return true;
      } catch (Throwable ignored) {
      }
    }
    return false;
  }

  /** 组里除了 self 之外，有没有成员愿意收这种液体。 */
  private static boolean siblingAcceptsLiquid(Seq<Building> comp, Building self, Building src, Liquid liquid) {
    for (Building other : comp) {
      if (other == self || other == null || !other.isValid()) continue;
      try {
        if (other.acceptLiquid(src, liquid)) return true;
      } catch (Throwable ignored) {
      }
    }
    return false;
  }

  // ==================== 工具 ====================

  static boolean sameItems(ItemModule a, ItemModule b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    for (Item item : content.items()) {
      if (a.get(item) != b.get(item)) return false;
    }
    return true;
  }

  static boolean sameLiquids(LiquidModule a, LiquidModule b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    for (Liquid liquid : content.liquids()) {
      if (Math.abs(a.get(liquid) - b.get(liquid)) > 0.01f) return false;
    }
    return true;
  }

  static void moveItems(ItemModule from, ItemModule to) {
    if (from == null || to == null || from == to) return;
    for (Item item : content.items()) {
      int amount = from.get(item);
      if (amount > 0) {
        to.add(item, amount);
        from.remove(item, amount);
      }
    }
  }

  static void moveLiquids(LiquidModule from, LiquidModule to) {
    if (from == null || to == null || from == to) return;
    for (Liquid liquid : content.liquids()) {
      float amount = from.get(liquid);
      if (amount > 0.001f) {
        to.add(liquid, amount);
        from.remove(liquid, amount);
      }
    }
    to.stopFlow(); // 见 ComboNet.moveItems 的说明：内部搬池子不算流量
  }

  /** 面板/调试用：这台机器所在的协作组合有几台。 */
  public static int groupSize(Building b) {
    if (b == null || !eligible(b.block)) return 1;
    int n = 1;
    ObjectSet<Building> seen = new ObjectSet<>();
    Seq<Building> stack = new Seq<>();
    stack.add(b);
    seen.add(b);
    while (stack.size > 0) {
      Building cur = stack.pop();
      for (Building nb : cur.proximity) {
        // 口径必须和 linkable 一致：同一个方块（不是"同一个方块类"，原因见 linkable）
        if (nb != null && nb.isValid() && nb.block == b.block && nb.team == b.team && seen.add(nb)) {
          stack.add(nb);
          n++;
        }
      }
    }
    return n;
  }
}
