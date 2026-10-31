package combine.net;
import combine.net.ComboConnector.ComboConnectorBuild;
import combine.net.ComboNode.ComboNodeBuild;
import combine.Main;
import combine.coop.CoopCombo;
import combine.storage.CombinedStorageBlock.CombinedStorageBuild;
import combine.storage.CombinedStorageBlock;
import combine.util.ComboPower;
import combine.util.ComboReflect;
import combine.util.ComboUi;
import arc.struct.IntFloatMap;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Strings;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.heat.HeatBlock;
import mindustry.world.blocks.heat.HeatConsumer;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import java.util.IdentityHashMap;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * 组合连接器 / 组合节点的跨组合体网络。
 *
 * 现有 Combined* 建筑各自维护“直接相邻”的本地组合体；这里把多个本地组合体
 * 通过连接器或节点连成一个网络，统一共享物品/液体模块，并保持各建筑自己的
 * leader 选举和序列化机制不变。
 *
 * 电力走原版电网：连接器是 conductivePower 导体，节点的 links 会由
 * ComboNodeBuild 同步到原版 PowerModule.links。
 */
public class ComboNet {
    /**
     * 按"组 leader pos"记录的热量分配额，带写入帧号。
     *
     * 【时序修复】旧实现每帧第一次调用时整体清空 —— Groups.build 的更新顺序是放置顺序，
     * 需热建筑若比节点先 update（玩家先放炮台、后放节点就是这样），它读到的永远是
     * "本帧刚被清空、节点还没分配"的 0，热量永远传不到（用户报的 afflict 收不到热）。
     * 现在改成"保留最近一次分配结果 + 帧号过期判断"： 读取方拿到的是上一帧的分配值
     * （热量是连续量，差一帧毫无影响）；写入方只覆盖自己分量的键，互不连通的
     * 多张网络也不再互相清空。
     */
    private static final arc.struct.IntMap<HeatAllocEntry> heatAlloc = new arc.struct.IntMap<>();
    private static long heatFrame = -1L;
    /** 分配结果超过这么多 tick 没刷新就视为无效（组已拆/换图）。 */
    private static final long heatAllocExpireTicks = 30L;

    private static class HeatAllocEntry {
        long frame;
        float amount;
    }
    /**
     * 读档窗口标记。
     *
     * 存档时网络里所有成员写的是同一个池模块，读档后每个成员各自拿到一份副本；
     * 这期间（endMapLoad 的 proximity 刷新 + WorldLoadEvent）出现的任何合并都必须
     * 按“去重”处理，一旦当成两份真库存相加，池子就会翻倍。
     */
    private static boolean loadingWorld = false;

    /**
     * 延迟重建标记。
     *
     * 【性能】放一个连接器/节点，会连着触发 placed()、自己和四周邻居的 onProximityUpdate()、
     * updateTile() 里的 linkHash 变化、还有 Main 的 TileChangeEvent —— 一次放置能叫 5~10 遍
     * rebuild()。每遍都要把全图建筑过一遍、重建网络图，几百栋建筑时单遍就是几毫秒，
     * 叠起来就是"在组合体之间放连接器那一瞬间疯狂掉帧"。
     * 现在这些事件只置脏标记，真正的重建每帧最多跑一次（Trigger.update 在建筑 update 之前，
     * 所以下一帧开始前网络就已经是新的，逻辑上只差一帧）。
     */
    private static boolean dirty = false;

    /**
     * 读档后的"去重语义"还要保持多少帧。
     *
     * 读档时每台共享同一份池子的建筑都在存档里写了一份完整副本，合并这些副本必须**去重**
     * （内容相同只留一份）而不是相加。以前这个语义只覆盖"读档那一轮"的合并，
     * 但读档后的头几帧里本地组合体还在重建（updateTile / 组合节点网络重算），会再发生几次合并 ——
     * 那几次落到了窗口外，按"运行期相加"处理，每台手里的整份副本就被当成真库存加进去，
     * 物品正好翻倍（用户报的"重新读写后物品增加"，实测 993210 → 1983230）。
     *
     * 现在读档后头 N 帧一律按去重处理；世界一旦真的被改动（造/拆方块）就提前结束。
     */
    private static int loadDedupeFrames = 0;
    private static final int loadDedupeGraceFrames = 20;

    /**
     * 【现场拆出来的池子】"取消勾选共享"时，被拆开的模块是**真库存被分成两份**，
     * 不是存档里那种"同一口池子被每台各写了一份副本"。
     *
     * <p>读档去重（{@link #mergeDistinctItems}）是按"内容完全相同 = 重复副本、只留一份"判的 ——
     * 而现场拆出来的两份碰巧内容相同（例如按容量比例对半分），重新勾上共享时就会被当成副本丢掉一份，
     * 玩家看到的是"取消再勾上，物品少一半"。所以拆出来的模块在这里登记一下，
     * 重新合并时按**真库存相加**处理。
     */
    private static final ObjectSet<ItemModule> freshSplitItems = new ObjectSet<>();
    private static final ObjectSet<LiquidModule> freshSplitLiquids = new ObjectSet<>();
    private static final int freshSplitLimit = 512;

    /** 读档去重语义还生效吗（CombinedStorageBlock / CoopCombo 共用）。 */
    public static boolean pendingLoadDedupe(){
        return loadDedupeFrames > 0;
    }

    /** 世界被真正改动过（造/拆方块）→ 读档去重语义到此为止。 */
    public static void markWorldModified(){
        loadDedupeFrames = 0;
    }

    /** 在 Main.init() 里注册一次：每帧最多重建一次。 */
    public static void register(){
        arc.Events.run(mindustry.game.EventType.Trigger.update, ComboNet::flush);
    }

    /** 网络结构变了：置脏，交给本帧的 flush 统一重建。 */
    public static void markDirty(){
        dirty = true;
    }

    /**
     * 「组合总开关」关掉时调用：把**已经并在一起**的组合体拆回"每台各一份"
     * （物品/液体按各自容量比例分，总量不变；核心池不拆）。
     *
     * <p>为什么必须主动拆：开关只是让分组/并池不再把这些建筑当组合体
     * （见 {@link combine.util.ComboTeams#playerTeam}），已经共用同一份模块的建筑不会自己分开 ——
     * 不拆的话玩家看到的是"开关关了、物品照样共享"。
     *
     * <p>直接复用"分量之间按容量比例拆"那套（{@link #splitItemsAcrossComponents} /
     * {@link #splitLiquidsAcrossComponents}）：把每台建筑当成一个独立分量喂进去，
     * 共用模块就按容量比例分给各台，各自拿一份新模块。
     */
    public static void detachAllCombos(){
        if(world == null || Groups.build == null) return;
        try{
            Seq<Building> all = new Seq<>();
            ObjectSet<Building> seen = new ObjectSet<>();
            for(Building b : Groups.build){
                if(b == null || !b.isValid() || b.block == null) continue;
                if(!ComboReflect.isComboBuild(b)) continue;
                if(seen.add(b)) all.add(b);
            }
            for(CombinedStorageBlock.CombinedStorageBuild sb : CombinedStorageBlock.trackedSet()){
                if(sb != null && sb.isValid() && seen.add(sb)) all.add(sb);
            }
            for(Building cb : CoopCombo.trackedBuildings()){
                if(cb != null && cb.isValid() && seen.add(cb)) all.add(cb);
            }
            Seq<Seq<Building>> comps = new Seq<>();
            for(Building b : all){
                Seq<Building> one = new Seq<>();
                one.add(b);
                comps.add(one);
            }
            splitItemsAcrossComponents(comps);
            splitLiquidsAcrossComponents(comps);
            // 每台回到自己的基础容量（并池时把容量算成整组之和了）
            for(Building b : all){
                try{
                    ComboReflect.setItemCap(b, ComboReflect.baseItemCap(b));
                    ComboReflect.setLiquidCap(b, ComboReflect.baseLiquidCap(b));
                    ComboReflect.markClean(b);
                }catch(Throwable ignored){
                }
            }
            invalidateTracked();
            Log.info("[combine] 组合总开关关闭：已把 @ 台组合建筑拆成各自独立", all.size);
        }catch(Throwable t){
            Log.err("[combine] 拆散组合体失败（组合总开关已关闭，但部分池子可能还连着）", t);
        }
    }

    /** 上一次重建里"属于某张网络"的格子（pos）；用来判断一次格子变化要不要重建网络。 */
    private static final IntSet netMemberPos = new IntSet();

    /**
     * 一格方块变了（造/拆/替换）之后：**只有可能影响组合网络的**才置脏。
     *
     * 【性能/用户报"服务端和客户端使用多线程建造后帧率下降十分明显"】以前这里无条件置脏，
     * 于是"多线程建造刷一大片"时每一帧都在重建整张组合网络（全图建筑过一遍 + 建图 + 拆池 + 合池，
     * 大基地上单次就是几毫秒到几十毫秒，实测建造中 4~5ms/tick、峰值 45ms）。
     * 但绝大多数格子变化跟网络无关：传送带、生产线、矿机、墙……既不是连接器/节点，
     * 也不在任何网络的成员表里，更不挨着网络成员 —— 重建结果一定和上一帧一模一样。
     *
     * 判据（保守优先：拿不准就置脏）：
     *   · 这一格**原来**是网络成员（pos 在成员表里）→ 变了就得重算；
     *   · 新方块是组合连接器/节点 → 要重算；
     *   · 新方块是组合建筑，且自己或邻居是网络成员/连接件 → 可能刚并进网络 → 要重算。
     */
    public static void markTileChanged(mindustry.world.Tile tile){
        // 【性能】登记表就地更新（放置/拆除都从这里过）：这样下面这些判据、以及随后的整图重建
        // 迭代的都是"组合相关的小名单"，而不是全图建筑（见 allComboBuildings 的说明）。
        trackTile(tile);
        try{
            if(tile == null){
                markDirty();
                return;
            }
            if(netMemberPos.contains(tile.pos())){
                markDirty();
                return;
            }
            Building b = tile.build;
            if(b == null)
                return;
            // 【性能】开了"只玩家组合"时，非玩家队伍的建筑变化（敌人基地被打掉/自己重建）
            // 不该触发整张网络的重新扫描 —— 那一下在大型基地图上就是几十毫秒的卡顿。
            if(combine.util.ComboTeams.playersOnly && !combine.util.ComboTeams.playerTeam(b.team))
                return;
            if(isLinker(b)){
                markDirty();
                return;
            }
            if(!ComboReflect.isComboBuild(b))
                return;
            if(b.proximity != null){
                for(Building nb : b.proximity){
                    if(nb == null) continue;
                    // 【性能·用户报"加一个新建筑就卡一下、组合工厂的优化没了"】
                    // 条件必须收到"这次放置**真的可能改变分组**"：
                    //   · 邻居是连接件（节点/连接器）→ 可能刚被连上 → 要重算；
                    //   · 邻居能和这台**并成一个组合体**（同类方块 / 单位生产族的跨类型 / 合体炮台↔组合炮台）
                    //     → 要重算（这一条覆盖了"合体炮台挨着组合炮台"那种情况）。
                    // 36e0c49 一度放宽成"邻居是**任意**组合建筑就整图重算"，于是大型基地里每放一台
                    // 方块（哪怕旁边只是种类完全不同的机器）都要全图建图+拆池+合池 —— 那一下就是卡顿。
                    if(isLinker(nb) || canJoinWith(b, nb)){
                        markDirty();
                        return;
                    }
                }
            }
        }catch(Throwable t){
            // 判据出错宁可多算一遍，也不要漏掉网络变化
            markDirty();
        }
    }

    /** 是否正在 rebuild 内部（防止 CoopCombo → ComboNet → CoopCombo 的重入）。 */
    public static boolean rebuilding(){
        return inRebuild;
    }

    /**
     * 这台方块和邻居能不能**真的**并进同一个组合体（不是"都是组合建筑"就算）：
     * · 同一个方块类（同类相邻并池，见各 Combined*Build.rebuildCombo）；
     * · 或者两边都声明 allowCrossTypeCombo（单位生产族）／合体炮台↔组合炮台。
     *
     * 只用来给 {@link #markTileChanged} 判断"这次放置要不要整图重算"。
     */
    private static boolean canJoinWith(Building a, Building nb){
        if(a == null || nb == null || a.block == null || nb.block == null) return false;
        // 方块类相同（同类）→ 相邻就会并成一组
        if(a.block.getClass() == nb.block.getClass()) return true;
        if(!ComboReflect.isComboBuild(a) || !ComboReflect.isComboBuild(nb)) return false;
        return crossAdjacencyAllowed(a, nb);
    }

    private static boolean inRebuild = false;

    /** 每帧一次的收口：只有真的脏了才重建。 */
    private static void flush(){
        if(loadDedupeFrames > 0) loadDedupeFrames --;
        sweepAbandonedHeatProbes();
        // 拆掉的组合体不该把它的共享配置留给"同位置的下一台"（见 ComboShare.pruneBodyMasks）
        ComboShare.pruneBodyMasks();
        if(!dirty) return;
        dirty = false;
        rebuild();
    }

    /*
     * ============================ 「组合相关建筑」登记表（性能） ============================
     *
     * 【用户报"新建或拆除组合建筑的时候会卡一下，掉帧很严重"】
     *
     * 一次放置/拆除会同时叫「本地组合体重建」（各 Combined*Build.rebuildCombo，里面的
     * itemPoolSharedOutside 要问"这口池子组外还有谁在用"）和「整张组合网络重建」
     * （ComboNet.rebuildInner，要建全局图）—— 两边以前都调 {@link #allComboBuildings()}，
     * 而那个实现**每次都把全图建筑过一遍**（Groups.build + 组合仓库 + 协作组合）。
     * 实测（headless，Archipelago，1.2 万台建筑）：单次 rebuild 里
     * 「快照 4.0ms + settle 2.8ms」就占了 ~7ms，整次 11ms —— 放在手机上就是十几到几十毫秒的顿挫。
     *
     * 这里改成**增量登记**：只在"世界中真的出现/消失了一栋可能与组合有关的建筑"时更新这份表，
     * 平时 rebuild 直接迭代这份小名单。判定 {@link #relevantBuilding} 覆盖了所有会读这份名单的
     * 地方（组合建筑本身、组合节点/连接器、核心（池共享要与核心对账）、热相关方块（节点要能连上））。
     *
     * 【安全网】世界加载/换图/生成期间一律走 {@link #fullScanBuildings}（老逻辑），
     * 绝不拿"读了一半的登记表"去并池；只要有任何一处调用 {@link #invalidateTracked()}，
     * 下一次查询就重新全图扫一遍。所以最坏情况只是退回旧性能，不会算错。
     */
    private static final ObjectSet<Building> trackedSet = new ObjectSet<>();
    private static final IntMap<Building> trackedByPos = new IntMap<>();
    private static boolean trackedReady = false;
    /** 登记表里有多少个连接件 / 多少个"跨类型可连"的候选 —— 都为 0 时网络图一定没有边。 */
    private static int trackedLinkers = 0, trackedCrossCandidates = 0;

    /** 让登记表失效：下一次查询全图重扫（换图、读档、队伍过滤开关变化、协作名单变化都要叫）。 */
    public static void invalidateTracked(){
        trackedReady = false;
        trackedSet.clear();
        trackedByPos.clear();
        trackedLinkers = trackedCrossCandidates = 0;
    }

    /** "跨类型相邻也并"的候选（决定网络图里会不会有跨类型边）。只跟方块类型有关。 */
    private static boolean isCrossCandidateBuild(Building b){
        if(b == null || b.block == null) return false;
        // 【关掉相邻自动组合时一条自动边都不长】跨类型相邻并成一台、合体炮台贴着组合炮台当同一台，
        // 这些都是"自动"的（也是大型基地里建图/拆池卡顿的来源）。组合总开关关掉 / "只保留超级组合"
        // 时直接判"没有跨类型候选" —— rebuildInner 于是走"每个本地组合体自成一张网络"的快路。
        if(!combine.util.ComboTeams.autoCombine()) return false;
        return crossTypeCombinable(b) || isSuperTurret(b) || isTurretCombo(b);
    }

    /**
     * 这栋建筑会不会被某条读 {@link #allComboBuildings()} 的逻辑用到：
     * · 组合建筑（含组合仓库/容器、组合墙等功能方块、协作组合方块）—— 网络重建/容量/并池；
     * · 组合节点 / 组合连接器 —— 网络图的顶点；
     * · 核心 —— {@code itemPoolSharedOutside} 要认出"这口池子核心也在用"（判错就是物品翻倍/消失）；
     * · 热相关方块 —— 组合节点要能连上 afflict / 矿渣制热机这类"只传热不存池"的方块。
     */
    private static boolean relevantBuilding(Building b){
        if(b == null || b.block == null) return false;
        if(ComboReflect.isComboBuild(b)) return true;
        if(isLinker(b)) return true;
        if(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild) return true;
        return ComboReflect.isHeatBuild(b);
    }

    private static boolean teamAllowed(Building b){
        return !combine.util.ComboTeams.playersOnly || combine.util.ComboTeams.playerTeam(b.team);
    }

    private static void trackAdd(Building b){
        if(b == null || !relevantBuilding(b) || !teamAllowed(b)) return;
        if(!ComboReflect.inWorld(b)) return;
        if(trackedSet.add(b)){
            trackedByPos.put(b.pos(), b);
            if(isLinker(b)) trackedLinkers++;
            else if(isCrossCandidateBuild(b)) trackedCrossCandidates++;
        }
    }

    private static void trackRemove(Building b){
        if(b == null) return;
        if(trackedSet.remove(b)){
            trackedByPos.remove(b.pos());
            if(isLinker(b)) trackedLinkers = Math.max(0, trackedLinkers - 1);
            else if(isCrossCandidateBuild(b)) trackedCrossCandidates = Math.max(0, trackedCrossCandidates - 1);
        }
    }

    /** 按当前登记表重算"连接件数 / 跨类型候选数"（清理残骸之后叫一次）。 */
    private static void recountTracked(){
        trackedLinkers = trackedCrossCandidates = 0;
        for(Building b : trackedSet){
            if(b == null) continue;
            if(isLinker(b)) trackedLinkers++;
            else if(isCrossCandidateBuild(b)) trackedCrossCandidates++;
        }
    }

    /** 一格方块变了之后同步登记表（旧的那栋摘掉、新的这栋按需加进来）。 */
    private static void trackTile(Tile tile){
        if(tile == null){
            invalidateTracked();
            return;
        }
        Building old = trackedByPos.get(tile.pos());
        if(old != null && old != tile.build) trackRemove(old);
        // 非原点格（multiblock 覆盖格）变化时也兜一下：把这一格上原来那栋按格摘掉
        if(tile.build == null){
            Building here = trackedByPos.get(tile.pos());
            if(here != null) trackRemove(here);
        }
        trackAdd(tile.build);
    }

    /** 全图扫描（老实现）：世界加载/换图期间、或登记表失效时用。 */
    private static Seq<Building> fullScanBuildings(){
        Seq<Building> all = new Seq<>();
        ObjectSet<Building> set = new ObjectSet<>();
        // 【性能/用户报】"进大型基地图非常卡，我怀疑敌人的建筑也组合；设置成只有玩家建筑组合还是很卡"。
        // 分组那一步早就按 ComboTeams.playerTeam 挡了，可**扫描/重建**这一层没挡 —— 于是
        // 3 万台建筑（其中 1.7 万台可组合的敌方建筑）每帧/每次网络重建都要过一遍。开关打开时
        // 这里直接把非玩家队伍的建筑滤掉，模组的开销只跟玩家自己的建筑数有关。
        boolean playersOnly = combine.util.ComboTeams.playersOnly;
        for(Building b : Groups.build){
            if(b == null) continue;
            if(playersOnly && !combine.util.ComboTeams.playerTeam(b.team)) continue;
            if(relevantBuilding(b) && ComboReflect.inWorld(b) && set.add(b)) all.add(b);
        }
        for(CombinedStorageBlock.CombinedStorageBuild sb : CombinedStorageBlock.trackedSet()){
            if(sb == null) continue;
            if(playersOnly && !combine.util.ComboTeams.playerTeam(sb.team)) continue;
            if(ComboReflect.inWorld(sb) && set.add(sb)) all.add(sb);
        }
        for(Building cb : CoopCombo.trackedBuildings()){
            if(cb == null) continue;
            if(playersOnly && !combine.util.ComboTeams.playerTeam(cb.team)) continue;
            if(ComboReflect.inWorld(cb) && set.add(cb)) all.add(cb);
        }
        return all;
    }

    /** 世界加载/生成期间不能用增量表（那时建筑是一批批塞进来的，事件也可能不齐）。 */
    private static boolean canUseTrackedCache(){
        return trackedReady && !loadingWorld && world != null && !world.isGenerating();
    }

    /** 强制重建登记表（世界加载完之后叫一次）。 */
    public static void rescanTracked(){
        for(Building b : fullScanBuildings()){
            if(trackedSet.add(b)) trackedByPos.put(b.pos(), b);
        }
        recountTracked();
        trackedReady = true;
    }

    /** 登记表能不能用来判断"这张网络图一定没有边"（没有连接件、也没有跨类型候选）。 */
    private static boolean trackedHasNoEdges(){
        return canUseTrackedCache() && trackedLinkers == 0 && trackedCrossCandidates == 0;
    }

    /**
     * 世界里有没有组合连接器/节点。
     *
     * 没有连接件时，墙的跨距离连线指纹每帧都是同一个值；大型原有地图通常一个连接件都没有，
     * 没必要让每面墙每帧扫四邻。登记表还没准备好时保守返回 true。
     */
    public static boolean hasTrackedLinkers(){
        return !canUseTrackedCache() || trackedLinkers > 0;
    }

    /**
     * 当前世界里所有「可能与组合逻辑有关」的建筑。
     *
     * 注意不能只看 {@code Groups.build}：组合仓库/容器、以及各种 mod 的仓库方块都是
     * {@code update = false}，Mindustry 根本不把它们放进 Groups.build。组合节点找连接目标、
     * 蓝框预览、网络重建都得用这份完整名单（否则"节点连不上旁边的组合仓库"）。
     */
    public static Seq<Building> allComboBuildings(){
        if(!canUseTrackedCache()) return fullScanBuildings();
        Seq<Building> all = new Seq<>(trackedSet.size);
        // 【安全网】万一有极少数"已拆掉但事件没走到"的残骸留在登记表里，这里顺手清掉并把
        // 计数重算一遍（真拆过它们的格子下一次放置会重新登记）。正常路径上不会有残骸。
        Seq<Building> stale = null;
        for(Building b : trackedSet){
            if(b != null && b.isValid() && ComboReflect.inWorld(b)){
                all.add(b);
            }else{
                if(stale == null) stale = new Seq<>();
                if(b != null) stale.add(b);
            }
        }
        if(stale != null){
            for(int i = 0; i < stale.size; i++){
                Building b = stale.get(i);
                trackedSet.remove(b);
                try{
                    if(trackedByPos.get(b.pos()) == b) trackedByPos.remove(b.pos());
                }catch(Throwable ignored){
                }
            }
            recountTracked();
        }
        return all;
    }

    public static void rebuild(){
        rebuild(null, false);
    }

    public static void rebuildExcluding(Building excluded){
        rebuild(excluded, false);
    }

    /** WorldLoadBeginEvent：进入读档语义窗口。 */
    public static void beginWorldLoad(){
        loadingWorld = true;
        // 换图/读档：增量登记表作废（世界内容整批换，事件不一定齐），下次查询全图重扫
        invalidateTracked();
        // 换了世界：上一张地图现场拆出来的池子不再有意义
        freshSplitItems.clear();
        freshSplitLiquids.clear();
        // 读档后头几帧仍按去重语义合并（本地组合体/网络还在重建，见 loadDedupeFrames 注释）
        loadDedupeFrames = loadDedupeGraceFrames;
        // 这一轮读档的"离谱池子体检"还没做（见 repairInsanePools）
        poolRepairDone = false;
    }

    /** 本轮读档的池子体检是否已做过。 */
    private static boolean poolRepairDone = false;

    /**
     * 读档体检：把**明显被算岔的池子**夹回容量上限。
     *
     * <p>为什么需要：用户存档里出现过一台激光钻机的池子写着 {@code 煤=522167664}、
     * {@code 硅=568718682}，而该分量容量只有 1840（差了 30 万倍）—— 这是以前版本在某条
     * "瞎连"路径上把池子反复相加留下的烂账，数字已经烧进存档，光修代码清不掉。
     *
     * <p>阈值故意定得极保守（{@code max(分量容量×1000, 1000 万)}），正常玩法堆不到这个量级：
     * 只有确凿的坏账才会被夹。夹的时候逐条打日志，方便玩家对照。
     */
    public static void repairInsanePools(){
        if(poolRepairDone) return;
        poolRepairDone = true;
        try{
            ObjectSet<ItemModule> seen = new ObjectSet<>();
            int fixed = 0;
            for(Building b : allComboBuildings()){
                if(b == null || !b.isValid() || b.items == null || !seen.add(b.items)) continue;
                Seq<Building> members = componentMembers(b);
                int cap = Math.max(componentItemCap(members), 1);
                // 【阈值】原来还有一条"至少 1000 万"的地板，于是小容量的大池子（例如一台
                // 容量 950 的钻机池里塞着 128 万）就永远够不到阈值、每次读档都留着 ——
                // 用户存档里正是这种（1488 倍容量）。现在改成纯倍率：超过该分量容量 100 倍
                // 就是确凿的坏账（正常玩法里"拆掉大部分网络成员"最多也就组员数量级，很少上百），
                // 夹回容量并逐条打日志。100000 的地板只是让容量很小的池子不被鸡毛蒜皮触发。
                long limit = Math.max((long)cap * 100L, 100_000L);
                for(Item item : content.items()){
                    int amt = b.items.get(item);
                    if(amt <= limit) continue;
                    b.items.set(item, cap);
                    fixed++;
                    Log.warn("[combine] 存档池子坏账已夹回容量：@ 的 @ @ → @（该分量容量 @，阈值 @）",
                        b.block.name, item.name, amt, cap, cap, limit);
                }
            }
            if(fixed > 0){
                Log.warn("[combine] 共修正 @ 项离谱库存（历史版本「瞎连」留下的坏账，已夹回该分量容量）", fixed);
                markDirty();
            }
        }catch(Throwable t){
            Log.err("[combine] 读档池子体检失败（跳过，不影响游戏）", t);
        }
    }

    public static void rebuildLoading(){
        loadingWorld = true;
        try{
            // 世界已经load好：把"组合相关建筑"登记表重建一遍（增量表从这里开始生效）
            invalidateTracked();
            rescanTracked();
            rebuild(null, true);
        }finally{
            loadingWorld = false;
        }
    }

    /**
     * 只读查询：返回某个连接器/节点/组合建筑所在连通分量里的所有组合建筑成员。
     *
     * 【性能】这里是信息面板每帧都要调用的东西（displayMembers / effectiveItemCap /
     * effectiveLiquidCap 都会转进来，一次面板刷新就调 3~5 次）。原实现每次都
     * {@code Groups.build.copy()} 把全世界的建筑拷一遍再建全局图 —— 建筑一多，
     * 选中一台组合建筑就每帧卡好几毫秒（也就是"点一下组合建筑就掉帧"）。
     * 现在改成从给定的那个点做局部 BFS：只走这个连通分量里的连接器/节点/组合体，
     * 复杂度只和网络大小有关，与全图建筑数无关；同 tick 内再按"组 leader 的 pos"记忆一份，
     * 一帧只算一次。
     */
    public static Seq<Building> componentMembers(Building linker){
        Seq<Building> empty = new Seq<>();
        if(world == null || linker == null || !linker.isValid()) return empty;

        // 组合节点是**跨距离**连接的：节点不在成员的 proximity 里，
        // 从成员出发的局部 BFS 根本走不到节点，于是"点开一台只能看到自己那一格"。
        // rebuild 时算好的 netByPos 才是权威，有记录就直接用（并确认这份记录确实是它的）。
        Seq<Building> indexed = netByPos.get(linker.pos());
        if(indexed != null && indexed.size > 0){
            boolean found = false;
            for(int i = 0; i < indexed.size; i++){
                if(indexed.get(i) == linker){ found = true; break; }
            }
            if(found){
                boolean allValid = true;
                for(int i = 0; i < indexed.size; i++){
                    // 必须用 inWorld：读档后旧世界的建筑 isValid() 仍是 true
                    if(!ComboReflect.inWorld(indexed.get(i))){ allValid = false; break; }
                }
                if(allValid) return indexed;
                Seq<Building> filtered = new Seq<>();
                for(int i = 0; i < indexed.size; i++){
                    if(ComboReflect.inWorld(indexed.get(i))) filtered.add(indexed.get(i));
                }
                if(filtered.size > 0 && filtered.contains(linker, true)) return filtered;
            }
        }

        // 缓存：同一帧内、同一个网络只算一次（组合建筑按组 leader 归并）
        Building key = ComboReflect.isComboBuild(linker) ? ComboReflect.leader(linker) : linker;
        if(key == null || !key.isValid()) key = linker;
        boolean cacheable = state != null;
        if(cacheable){
            if(componentCacheTick != state.updateId){
                componentCacheTick = state.updateId;
                componentCache.clear();
            }
            Seq<Building> cached = componentCache.get(key.pos());
            if(cached != null) return cached;
        }

        Seq<Building> out = new Seq<>();
        ObjectSet<Building> visited = new ObjectSet<>();     // 已入队的连接器/节点
        ObjectSet<Building> visitedGroups = new ObjectSet<>();// 已收集的组合体(以 leader 计)
        Queue<Building> queue = new Queue<>();
        Queue<Building> groupQueue = new Queue<>();

        if(isLinker(key)){
            enqueueVertex(key, visited, queue);
        }else if(ComboReflect.isComboBuild(key)){
            collectGroup(key, out, visitedGroups, visited, queue, groupQueue);
        }

        while(!queue.isEmpty() || !groupQueue.isEmpty()){
            while(!groupQueue.isEmpty()){
                collectGroup(groupQueue.removeFirst(), out, visitedGroups, visited, queue, groupQueue);
            }
            if(queue.isEmpty()) continue;
            Building v = queue.removeFirst();
            if(v instanceof ComboConnector.ComboConnectorBuild c){
                for(Building nb : c.proximity) collectNeighbor(nb, out, visitedGroups, visited, queue, groupQueue);
            }else if(v instanceof ComboNode.ComboNodeBuild n){
                for(int i = 0; i < n.links.size; i++)
                    collectNeighbor(world.build(n.links.get(i)), out, visitedGroups, visited, queue, groupQueue);
                // 【贴着也算连上】用户报："用组合节点连接 afflict 和 slag-heater，虽然可以传热，
                // 但是这两个显示没有组合在一起、afflict 面板热量是 0" —— 根因就是这里：
                // 热量那条路（collectLinkedHeatBuildings）本来就认"贴着节点的建筑"，于是热传过去了；
                // 可组合网络这条只认 links，贴着的组合体不算成员 → 面板不成组、也读不到产热方。
                // 现在和连接器/节点贴节点统一：节点**贴着**的组合体（含它所在的本地组合体）也算连上。
                if(n.proximity != null)
                    for(Building nb : n.proximity) collectNeighbor(nb, out, visitedGroups, visited, queue, groupQueue);
            }
        }

        if(cacheable) componentCache.put(key.pos(), out);
        return out;
    }

    /** 同 tick 内的连通分量缓存（key = 组 leader / 连接器 / 节点的 pos）。 */
    private static long componentCacheTick = Long.MIN_VALUE;
    private static final arc.struct.IntMap<Seq<Building>> componentCache = new arc.struct.IntMap<>();

    /**
     * 悬浮面板用：网络分量成员 + 经连接件**明确连进来**的"纯热"建筑。
     *
     * <p>用户报："用组合节点连接 afflict 和 slag-heater，虽然可以传热，但是这两个显示没有组合在一起、
     * afflict 面板热量是 0"。原因是那类方块**没被本模组替换**（mod 自己的制热机 / 需热炮台），
     * 节点连线只把它们当"热端"（{@link #collectLinkedHeatBuildings}），进不了并池分量 —— 于是面板
     * 的"构成"里没有它们、热量那一段（Σ 产热方 heat()）也就读不到产热方。
     *
     * <p>这里只给**显示**用：把它们并进成员表（构成/热量能一起看），物品/液体的并池照旧只认
     * {@link #componentMembers}（这些方块不该被并池，见 collectLinkedHeatBuildings 的注释）。
     */
    public static Seq<Building> panelMembers(Building self){
        Seq<Building> comp = componentMembers(self);
        Seq<Building> linkers = new Seq<>();
        for(int i = 0; i < comp.size; i++)
            if(isLinker(comp.get(i))) linkers.add(comp.get(i));
        if(linkers.isEmpty()) return comp;
        Seq<Building> heat;
        try{
            heat = collectLinkedHeatBuildings(linkers);
        }catch(Throwable t){
            return comp;
        }
        if(heat.isEmpty()) return comp;
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(int i = 0; i < comp.size; i++)
            if(comp.get(i) != null && seen.add(comp.get(i))) out.add(comp.get(i));
        for(int i = 0; i < heat.size; i++)
            if(heat.get(i) != null && heat.get(i).isValid() && seen.add(heat.get(i))) out.add(heat.get(i));
        return out;
    }

    /** 清掉分量缓存（网络结构变化时调用，避免同一帧内显示旧网络）。 */
    static void invalidateComponentCache(){
        componentCacheTick = Long.MIN_VALUE;
        componentCache.clear();
    }

    /** 只和玩家队友组合：AI 敌人的建筑不进网络 */
    private static boolean ComboUtilTeamOk(Building b){
        return combine.util.ComboTeams.playerTeam(b == null ? null : b.team);
    }

    private static boolean isLinker(Building b){
        return b instanceof ComboConnector.ComboConnectorBuild
            || b instanceof ComboNode.ComboNodeBuild;
    }

    /**
     * 从某个连接件出发，收集**同一张网络里的全部连接件**（节点 / 连接器）。
     *
     * <p>走法必须和 {@link #componentMembers} 一致：连接器靠邻接接下一棒、节点靠 links
     * 接下一棒，成员只是"导线上的落脚点"（通过它的邻接/被谁链接找到别的连接件）。
     */
    public static Seq<Building> componentLinkers(Building linker){
        Seq<Building> out = new Seq<>();
        if(world == null || linker == null || !linker.isValid() || !isLinker(linker)) return out;
        ObjectSet<Building> seen = new ObjectSet<>();
        Queue<Building> queue = new Queue<>();
        seen.add(linker);
        queue.addLast(linker);
        while(!queue.isEmpty()){
            Building v = queue.removeFirst();
            out.add(v);
            if(v instanceof ComboConnector.ComboConnectorBuild c){
                if(c.proximity != null)
                    for(Building nb : c.proximity)
                        if(nb != null && nb.isValid() && isLinker(nb) && seen.add(nb)) queue.addLast(nb);
                // 贴着连接器的成员也可能是"节点连着的另一头"，从成员再找连接件
                for(Building m : componentMembersOfVertex(c))
                    for(Building l : nearbyLinkers(m))
                        if(seen.add(l)) queue.addLast(l);
            }else if(v instanceof ComboNode.ComboNodeBuild n){
                for(int i = 0; i < n.links.size; i++){
                    Building l = world.build(n.links.get(i));
                    if(l == null || !l.isValid()) continue;
                    if(isLinker(l)){
                        if(seen.add(l)) queue.addLast(l);
                    }else if(ComboReflect.isComboBuild(l)){
                        for(Building o : nearbyLinkers(l)) if(seen.add(o)) queue.addLast(o);
                    }
                }
                // 【贴着也算连上】和 componentMembers 保持一致：贴着节点的组合体（含它这一片本地组合体）
                // 也算同一张网络里的成员，再从成员身上找别的连接件（用户把节点摆在两台之间、
                // 不点连线时就是这么用的）。
                if(n.proximity != null)
                    for(Building nb : n.proximity){
                        if(nb == null || !nb.isValid()) continue;
                        if(nb instanceof ComboNode.ComboNodeBuild){
                            if(seen.add(nb)) queue.addLast(nb);
                        }else if(ComboReflect.isComboBuild(nb)){
                            for(Building m : ComboReflect.group(nb))
                                for(Building o : nearbyLinkers(m)) if(seen.add(o)) queue.addLast(o);
                        }
                    }
            }
        }
        return out;
    }

    /** 一个连接件旁边的组合体成员（只取邻接，不跨距离）。 */
    private static Seq<Building> componentMembersOfVertex(Building vertex){
        Seq<Building> out = new Seq<>();
        if(vertex == null || vertex.proximity == null) return out;
        for(Building nb : vertex.proximity)
            if(nb != null && nb.isValid() && ComboReflect.isComboBuild(nb)) out.add(nb);
        return out;
    }

    /** 贴着某个成员、或者 links 里写着这个成员的连接件。 */
    private static Seq<Building> nearbyLinkers(Building member){
        Seq<Building> out = new Seq<>();
        if(member == null) return out;
        try{
            if(member.proximity != null)
                for(Building nb : member.proximity)
                    if(nb != null && nb.isValid() && isLinker(nb)) out.add(nb);
            for(Building nb : CoopCombo.trackedNodesCopy())
                if(nb instanceof ComboNode.ComboNodeBuild node && node.links != null
                    && node.links.contains(member.pos())) out.add(nb);
        }catch(Throwable ignored){
        }
        return out;
    }

    /**
     * 共享配置变了（玩家在节点/连接器的面板上勾选）：立刻让它在整张网络上生效。
     *
     * <p>三件事：本地组合体重新分组（有任一项没勾就不再把两端当一台机器）、
     * 节点电力连线重新对齐、池子/容量重算（交给本帧的重建）。
     */
    public static void onShareChanged(Building linker){
        try{
            invalidateComponentCache();
            Seq<Building> linkers = componentLinkers(linker);
            for(int i = 0; i < linkers.size; i++)
                if(linkers.get(i) instanceof ComboNode.ComboNodeBuild node) syncNodePower(node);
            Seq<Building> members = componentMembers(linker);
            for(int i = 0; i < members.size; i++){
                Building m = members.get(i);
                ComboReflect.markGroupDirty(m);
                ComboPower.mark(m);
            }
            for(int i = 0; i < linkers.size; i++) ComboPower.mark(linkers.get(i));
        }catch(Throwable t){
            Log.err("[combine] 共享配置生效失败（不影响游戏）", t);
        }
        try{
            CombinedStorageBlock.markDirty();
            CoopCombo.markDirty();
        }catch(Throwable ignored){
        }
        markDirty();
    }

    /**
     * 节点与它连接目标的电力连线要和"电力"开关对齐：
     * 勾了就连上（并到一张电网），取消勾选就把连线拆掉、两端各建一张新电网。
     */
    private static void syncNodePower(ComboNode.ComboNodeBuild node){
        try{
            if(node == null || !node.isValid() || node.power == null) return;
            boolean on = node.shareBit(ComboShare.POWER);
            boolean changed = false;
            for(int i = 0; i < node.links.size; i++){
                Building other = world.build(node.links.get(i));
                if(other == null || !other.isValid() || other.power == null) continue;
                boolean linked = node.power.links.contains(other.pos()) || other.power.links.contains(node.pos());
                if(on){
                    if(!linked){
                        node.power.links.addUnique(other.pos());
                        other.power.links.addUnique(node.pos());
                        if(node.power.graph != null && other.power.graph != null) node.power.graph.addGraph(other.power.graph);
                        changed = true;
                    }
                }else if(linked){
                    node.power.links.removeValue(other.pos());
                    other.power.links.removeValue(node.pos());
                    changed = true;
                }
                ComboPower.mark(other);
            }
            if(changed){
                // 【两端各建一张新电网】只给一边 reflow 会把"旧的那张（合并过的）电网"继续留给对面
                mindustry.world.blocks.power.PowerGraph self = new mindustry.world.blocks.power.PowerGraph();
                self.reflow(node);
                self.update();
                for(int i = 0; i < node.links.size; i++){
                    Building other = world.build(node.links.get(i));
                    if(other != null && other.power != null && other.power.graph != self){
                        mindustry.world.blocks.power.PowerGraph og = new mindustry.world.blocks.power.PowerGraph();
                        og.reflow(other);
                        og.update();
                    }
                }
            }
            ComboPower.mark(node);
        }catch(Throwable ignored){
        }
    }

    private static void enqueueVertex(Building v, ObjectSet<Building> visited, Queue<Building> queue){
        if(v == null || !v.isValid()) return;
        if(visited.add(v)) queue.addLast(v);
    }

    /**
     * 局部 BFS 里"碰到一个邻居"的统一处理：是连接件就继续走，
     * 是组合体就把它的整组收进来（并顺着"相邻跨类型"那条边继续扩，见 {@link #collectGroup}）。
     */
    private static void collectNeighbor(Building nb, Seq<Building> out, ObjectSet<Building> visitedGroups,
                                        ObjectSet<Building> visited, Queue<Building> queue, Queue<Building> groupQueue){
        if(nb == null || !nb.isValid()) return;
        if(isLinker(nb)){
            enqueueVertex(nb, visited, queue);
        }else if(ComboReflect.isComboBuild(nb)){
            collectGroup(nb, out, visitedGroups, visited, queue, groupQueue);
        }
    }

    private static void collectGroup(Building member, Seq<Building> out, ObjectSet<Building> visitedGroups,
                                     ObjectSet<Building> visited, Queue<Building> queue, Queue<Building> groupQueue){
        Building leader = ComboReflect.leader(member);
        if(leader == null) leader = member;
        if(!visitedGroups.add(leader)) return;
        for(Building m : ComboReflect.group(leader)){
            if(m == null || !m.isValid()) continue;
            if(!ComboUtilTeamOk(m)) continue; // 只和玩家队友组合
            out.addUnique(m);
            for(Building nb : m.proximity){
                if(nb == null || !nb.isValid()) continue;
                if(isLinker(nb)){
                    enqueueVertex(nb, visited, queue);
                }else if(ComboReflect.isComboBuild(nb) && crossAdjacencyAllowed(leader, nb)){
                    // 相邻的两台不同类机器（两边都允许跨类型组合）= 一台整机：权威图
                    // （rebuildInner 里 crossTypeCombinable 那条边）也是这么连的，
                    // 这里不跟上的话，"节点/连接器面板少算几台、池子其实已经并了"就又会不一致。
                    Building other = ComboNode.groupRep(nb);
                    if(other == null || other == leader || !visitedGroups.contains(other)) groupQueue.addLast(nb);
                }
            }
        }
    }

    /** 显示/生产用的有效物品容量：网络组合存在时按网络成员合计，否则回退到本地缓存值。 */
    public static int effectiveItemCap(Building self){
        if(self == null) return 1;
        int cap = 0;
        for(Building m : itemScope(self)){
            if(m.isValid()) cap += ComboReflect.baseItemCap(m);
        }
        if(cap <= 0){
            Integer v = ComboReflect.getInt(self, "comboTotalItemCap");
            cap = v == null ? 0 : v;
        }
        return Math.max(cap, 1);
    }

    /** 显示/生产用的有效液体容量：网络组合存在时按网络成员合计，否则回退到本地缓存值。 */
    public static float effectiveLiquidCap(Building self){
        if(self == null) return 1f;
        float cap = 0f;
        for(Building m : liquidScope(self)){
            if(m.isValid()) cap += ComboReflect.baseLiquidCap(m);
        }
        if(cap <= 0.001f){
            Float v = ComboReflect.getFloat(self, "comboTotalLiquidCap");
            cap = v == null ? 0f : v;
        }
        return Math.max(cap, 1f);
    }

    /**
     * 物品容量的取值范围：勾了"物品"共享就按整张网络算；没勾就只看**这一台自己**
     * （"不共享物品"= 每台各留各的模块，容量自然也只能算自己那一份，见 perMemberGroups）。
     */
    public static Seq<Building> itemScope(Building self){
        if(self == null) return new Seq<>();
        if(!ComboShare.shares(self, ComboShare.ITEMS)){
            Seq<Building> one = new Seq<>();
            if(self.isValid()) one.add(self);
            return one;
        }
        return displayMembers(self, ComboReflect.group(self).size);
    }

    /** 同上，液体版。 */
    public static Seq<Building> liquidScope(Building self){
        if(self == null) return new Seq<>();
        // 【每台各留各的】没勾"液体"= 这一台只看自己那份液池（同 itemScope）
        if(!ComboShare.shares(self, ComboShare.LIQUIDS)){
            Seq<Building> one = new Seq<>();
            if(self.isValid()) one.add(self);
            return one;
        }
        return displayMembers(self, ComboReflect.group(self).size);
    }

    /** display 用的“有效组合体”：网络组合大于本地组时返回网络成员，否则返回本地组。 */
    public static Seq<Building> displayMembers(Building self, int localCount){
        if(self == null) return new Seq<>();
        Seq<Building> network = componentMembers(self);
        if(network.size > localCount) return network;
        return ComboReflect.group(self);
    }

    // ==================== 给"本地组合"用的网络查询 ====================
    //
    // 组合仓库/容器有自己的并仓逻辑（CombinedStorageBlock），它按"仓库↔仓库相邻"求分量。
    // 连接器/节点接起来的那部分网络，ComboNet 才是权威：容量、模块都得以整张网络算，
    // 不然就会出现"池子是同一份、容量却只算自己那格"（用户看到的"容量没相加"）。

    /** 这个建筑所在网络的成员；不在任何网络里时只有它自己。 */
    public static Seq<Building> networkMembers(Building self){
        if(self == null) return new Seq<>();
        // 优先用 rebuild 时算好的全局索引：组合节点是跨距离连接的，
        // 从成员出发做局部 BFS 根本走不到那个节点（节点不在它的 proximity 里）。
        Seq<Building> indexed = netByPos.get(self.pos());
        if(indexed != null) return indexed;
        return componentMembers(self);
    }

    /** 成员 pos -> 该网络的全部成员（只有成员数 > 1 的网络才登记），每次 rebuild 重建。 */
    private static final arc.struct.IntMap<Seq<Building>> netByPos = new arc.struct.IntMap<>();
    /** 上一次的网络结构签名（用来判断"要不要叫组合仓库重算"）。 */
    private static long lastNetSignature = 0L;

    /** 网络成员数（不在网络里返回 1）。 */
    public static int networkSize(Building self){
        return Math.max(networkMembers(self).size, 1);
    }

    /** 网络合计物品容量；不在网络里（只有自己一台）返回 0。 */
    public static int networkItemCap(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return 0;
        int total = 0;
        for(Building m : members){
            if(m.isValid()) total += ComboReflect.baseItemCap(m);
        }
        return total;
    }

    /** 网络合计液体容量；不在网络里返回 0。 */
    public static float networkLiquidCap(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return 0f;
        float total = 0f;
        for(Building m : members){
            if(m.isValid()) total += ComboReflect.baseLiquidCap(m);
        }
        return total;
    }

    /** 同一张网络里的建筑返回同一个编号；不在网络里返回 0。 */
    public static int networkKey(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return 0;
        int min = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.isValid() && m.pos() < min) min = m.pos();
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /**
     * 网络里"应该共用"的那份物品模块（ComboNet 并池时会选中的那份）。
     * 网络成员已经共用一份时返回 null —— 调用方保持原样即可。
     */
    public static ItemModule poolModuleFor(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return null;
        Seq<ItemModule> mods = new Seq<>();
        for(Building m : members){
            if(m.items != null && !mods.contains(m.items, true)) mods.add(m.items);
        }
        return mods.size <= 1 ? null : moduleOfFirst(members, mods);
    }

    /** 同上，液体版。 */
    public static LiquidModule poolLiquidFor(Building self){
        Seq<Building> members = networkMembers(self);
        if(members.size <= 1) return null;
        Seq<LiquidModule> mods = new Seq<>();
        for(Building m : members){
            if(m.liquids != null && !mods.contains(m.liquids, true)) mods.add(m.liquids);
        }
        return mods.size <= 1 ? null : moduleOfFirstLiquid(members, mods);
    }

    /** 给任意 Combined* 建筑的 display 追加网络组合面板。 */
    public static boolean addNetworkDisplay(Table table, Building self, int localCount){
        if(self == null || table == null) return false;
        if(componentMembers(self).size <= localCount) return false;

        // 同 showPool：面板只在切换目标时重建一次，数字必须每帧重取
        Table net = new Table();
        net.left();
        net.update(() -> {
            net.clearChildren();
            net.defaults().left();
            ComboUi.safe("combonet:network", () -> buildNetworkContent(net, self, localCount));
        });
        table.row();
        table.add(net).growX().left();
        return true;
    }

    /** 网络面板上一条物品条的文字：每次调用取当前值。 */
    public static String poolItemText(Building pool, int cap, Item item){
        return item.localizedName + ": " + (pool == null || pool.items == null ? 0 : pool.items.get(item))
            + "/" + Math.max(cap, 1);
    }

    /** 同上，液体版。 */
    public static String poolLiquidText(Building pool, float cap, Liquid liquid){
        return liquid.localizedName + ": "
            + Strings.fixed(pool == null || pool.liquids == null ? 0f : pool.liquids.get(liquid), 1)
            + "/" + Strings.fixed(Math.max(cap, 1f), 1);
    }

    /** 网络面板内容：活数据（供面板每帧重画）。 */
    public static void buildNetworkContent(Table table, Building self, int localCount){
        Seq<Building> members = componentMembers(self);
        if(members.size <= localCount) return;

        // 【宽度必须封顶】这些内容挂在原版信息面板里（`display()`），面板宽度是**按内容撑**的：
        // 只要有一行不换行的长文字（成员构成、方块名），整张表就被撑到屏幕那么宽，
        // 里面 `growX` 的物品/液体条跟着变成"横跨全屏"的长条（用户报的
        // "容器的物品 bar 长度没有限制、组合体成员显示没有换行"）。统一按
        // ComboUi.COMPOSITION_WIDTH 换行 + 给条固定宽度。
        final float W = combine.util.ComboUi.COMPOSITION_WIDTH;
        table.add("[accent]网络组合 x" + members.size + "[] " + self.block.localizedName).left().width(W).wrap();

        Building itemPool = null, liquidPool = null;
        for(Building m : members){
            if(!m.isValid()) continue;
            if(m.items != null && (itemPool == null || m.pos() < itemPool.pos())) itemPool = m;
            if(m.liquids != null && (liquidPool == null || m.pos() < liquidPool.pos())) liquidPool = m;
        }
        final Building fi = itemPool, fl = liquidPool;
        // 分母和悬浮面板同源（含核心 storageCapacity 那部分扩容），别一边算网络合计、一边只算核心自己
        final int icap = panelItemCap(self);
        final float lcap = panelLiquidCap(self);

        if(fi != null && fi.items != null){
            for(Item item : content.items()){
                if(fi.items.get(item) <= 0) continue;
                table.row();
                table.add(new Bar(
                    () -> poolItemText(fi, icap, item),
                    () -> item.color,
                    () -> fi.items == null ? 0f : (float)fi.items.get(item) / icap)).width(W).height(18f).pad(4).left();
            }
        }

        if(fl != null && fl.liquids != null){
            for(Liquid liquid : content.liquids()){
                if(fl.liquids.get(liquid) <= 0.001f) continue;
                table.row();
                table.add(new Bar(
                    () -> poolLiquidText(fl, lcap, liquid),
                    () -> liquid.barColor != null ? liquid.barColor : liquid.color,
                    () -> fl.liquids == null ? 0f : fl.liquids.get(liquid) / lcap)).width(W).height(18f).pad(4).left();
            }
        }

        ObjectIntMap<Block> counts = new ObjectIntMap<>();
        for(Building m : members) counts.increment(m.block, 1);
        StringBuilder comp = new StringBuilder();
        for(Block b : counts.keys()){
            if(comp.length() > 0) comp.append("  ");
            comp.append(b.localizedName).append("*").append(counts.get(b, 0));
        }
        table.row();
        table.add("[lightgray]构成: " + comp + "[]").left().width(W).wrap();
    }

    /** 临时排查用：打印各阶段耗时（默认关）。 */
    public static boolean timeDebug = false;

    private static void rebuild(Building excluded, boolean loading){
        if(world == null || Groups.build == null) return;
        if(inRebuild) return;
        inRebuild = true;
        try{
            rebuildInner(excluded, loading);
        }finally{
            inRebuild = false;
        }
    }

    private static void rebuildInner(Building excluded, boolean loading){
        long _t0 = System.nanoTime(), _tA = _t0, _tB = _t0, _tC = _t0, _tD = _t0, _tE = _t0, _tF = _t0;
        long _tG = _t0, _tH = _t0, _tI = _t0, _tJ = _t0, _tK = _t0;

        // 网络结构变了：显示用的连通分量缓存立刻作废（同帧内别拿旧网络画面板）
        invalidateComponentCache();

        // endMapLoad 阶段连接器/节点会随 proximity 刷新提前触发 rebuild，
        // 那时各本地组合体手里还是存档写下的重复副本，必须按读档语义处理。
        // 读档窗口还要看"本地组合"那边的去重标志有没有用掉：读档后每台机器手里都写着
        // 同一份池子的副本，谁先合并谁就必须按"内容相同的副本只留一份"来算。
        // ComboNet 每帧跑在它们之前，所以要以它们的标志为准，否则第一帧就把副本当三份真库存相加了。
        boolean loadPhase = loading || loadingWorld || world.isGenerating()
            || CombinedStorageBlock.pendingDedupe() || CoopCombo.pendingDedupe() || pendingLoadDedupe();

        try{
            // 【性能】allComboBuildings() 返回的就是一份去过重的新 Seq（登记表本身是 ObjectSet），
            // 这里不用再建一张 ObjectSet 去重、也不用再拷一份 —— 只处理 excluded 即可。
            Seq<Building> all = allComboBuildings();
            if(excluded != null){
                for(int i = all.size - 1; i >= 0; i--) if(all.get(i) == excluded) all.remove(i);
            }
            _tA = System.nanoTime();

            // 1) 先让所有本地组合体完成自己的 leader 选举/邻接重建，再在其上架网络。
            settleLocalGroups(all);
            _tB = System.nanoTime();

            // 2) 找出所有本地组合体的 leader（同一组合体只出现一次），
            //    同时记下"这张图到底有没有边"的两个前提。
            Seq<Building> groupLeaders = new Seq<>();
            ObjectIntMap<Building> leaderIndex = new ObjectIntMap<>();
            // 【性能】登记表说"图里不可能有边"时，本地组合体下标 == 分量下标 ——
            // 那么第 5 步的"成员归类"可以并进这一趟做掉，省掉一整轮对全部组合建筑的反射（groupRep）。
            boolean fuseSimple = trackedHasNoEdges();
            Seq<Seq<Building>> fusedMembers = fuseSimple ? new Seq<>() : null;
            boolean hasLinker = false, hasCrossCandidate = false;
            for(Building b : all){
                if(b instanceof ComboConnector.ComboConnectorBuild
                    || b instanceof ComboNode.ComboNodeBuild){
                    hasLinker = true;
                }
                if(!ComboReflect.isComboBuild(b)) continue;
                // 用 localGroupRep：组合仓库/容器按"相邻成簇"算一个组合体。
                // 否则同一条仓库链里的每台都会成为独立顶点，它们共用的那份物品模块会被
                // splitAcrossComponents 当成"断开残留"再拆成每人一份（读档后"名义连在一起、
                // 模块却不共享"就是这个）。
                Building l = ComboNode.groupRep(b);
                if(l == null) l = b;
                int li = leaderIndex.get(l, -1);
                if(li < 0){
                    li = groupLeaders.size;
                    leaderIndex.put(l, li);
                    groupLeaders.add(l);
                    if(fuseSimple) fusedMembers.add(new Seq<Building>());
                    // 跨类型边只可能从"跨类型可组合 / 合体炮台 / 炮台"这几类顶点长出来
                    if(crossTypeCombinable(l) || isSuperTurret(l) || isTurretCombo(l)) hasCrossCandidate = true;
                }
                if(fuseSimple) fusedMembers.get(li).add(b);
            }
            _tG = System.nanoTime();

            // 3) 建图：顶点 = 连接器/节点 + 本地组合体 leader。
            //
            // 【性能·用户报"新建或拆除组合建筑的时候会卡一下，掉帧很严重"】
            // 没有连接件、也没有跨类型可连的顶点时（绝大多数基地的绝大多数时刻），
            // 顶点之间**不可能有边** —— 每个本地组合体自成一张网络。
            // 以前这种情形也照样"给每个顶点建一份 edges Seq + BFS + 建 components/leaderComponent"，
            // 1000+ 组合建筑时单这几步就是 4~6ms，还制造几千个临时对象喂给安卓的 GC。
            // 现在直接按 leader 给出单点分量，整张图一步都不建。
            final boolean simpleNet = !hasLinker && !hasCrossCandidate;
            Seq<Seq<Building>> components = null;
            ObjectIntMap<Building> compIdxOfLeader;
            int compCount;
            int vertexCount = 0;
            Seq<Seq<Building>> compMembers;
            if(simpleNet){
                // 快路：没有建图/拆池子步骤，这几段计时置零（timeDebug 打印不出负数）
                _tH = _tI = _tJ = _tK = _tG;
                compIdxOfLeader = leaderIndex;
                compCount = groupLeaders.size;
                if(fuseSimple){
                    compMembers = fusedMembers;
                }else{
                    compMembers = new Seq<>();
                    for(int i = 0; i < compCount; i++) compMembers.add(new Seq<Building>());
                    fillCompMembers(compMembers, all, compIdxOfLeader);
                }
            }else{
                Seq<Building> vertices = new Seq<>();
                vertexCount = 0; // 稍后赋值
                ObjectSet<Building> vertexSet = new ObjectSet<>();
                for(Building b : all){
                    if(b instanceof ComboConnector.ComboConnectorBuild
                        || b instanceof ComboNode.ComboNodeBuild){
                        if(vertexSet.add(b)) vertices.add(b);
                    }
                }
                for(Building l : groupLeaders){
                    if(vertexSet.add(l)) vertices.add(l);
                }
                vertexCount = vertices.size;

                ObjectMap<Building, Seq<Building>> edges = new ObjectMap<>();
                for(Building v : vertices) edges.put(v, new Seq<>());
                _tH = System.nanoTime();

                // 【相邻的不同"组合类"也算连上】用户报"挖铅钻头就没有和窑炉组合在一起"：
                // CombinedCrafter/CombinedDrill/CombinedGenerator… 各自的本地分组只认**同一种方块**
                // （allowCrossTypeCombo 只在同类内部生效），跨类型以前只能靠连接器/节点。
                // 而"两种工厂相邻放置就当一台整机"正是这个模组最初的玩法，所以这里把
                // 「相邻 + 两边都声明 allowCrossTypeCombo」的两个本地组合体直接连一条边 ——
                // 后续并池/容量/面板/电网全走连接器那套现成逻辑（热量本来就靠原版 proximity 直传，不受影响）。
                // 【关掉相邻自动组合时一条自动边都不长】下面这些边全是"自动"的
                // （跨类型相邻并成一台、合体炮台贴着组合炮台当同一台）。组合总开关关掉 /
                // "只保留超级组合"时整段跳过：每个本地组合体自成一张网络，连接器/节点的
                // 显式连线（下面的第二轮）照旧。
                for(Building leader : combine.util.ComboTeams.autoCombine() ? groupLeaders : new Seq<Building>()){
                    // 【性能】只有"跨类型可连"的候选才需要看邻居：其余组合体相邻也不会有边。
                    if(!(crossTypeCombinable(leader) || isSuperTurret(leader) || isTurretCombo(leader))) continue;
                    Seq<Building> group = ComboReflect.group(leader);
                    if(group == null || group.isEmpty()) group = Seq.with(leader);
                    for(Building mem : group){
                        if(mem == null || !mem.isValid() || mem.proximity == null) continue;
                        for(Building nb : mem.proximity){
                            if(nb == null || !nb.isValid() || !ComboReflect.isComboBuild(nb)) continue;
                            Building other = ComboNode.groupRep(nb);
                            if(other == null || other == leader) continue;
                            if(!vertexSet.contains(other)) continue;
                            if(!crossTypeCombinable(leader) || !crossTypeCombinable(other)) {
                                // 默认只有同种组合建筑相邻才并池；**合体炮台 ↔ 组合炮台**是例外，相邻也当一台整机。
                                if(!(isSuperTurret(leader) && isTurretCombo(other))
                                    && !(isSuperTurret(other) && isTurretCombo(leader))) continue;
                            }
                            addEdge(edges, leader, other);
                        }
                    }
                }
                _tI = System.nanoTime();

                for(Building v : vertices){
                    if(v instanceof ComboConnector.ComboConnectorBuild c){
                        for(Building nb : c.proximity){
                            connectVertex(edges, vertexSet, c, nb);
                        }
                    }else if(v instanceof ComboNode.ComboNodeBuild n){
                        for(int i = 0; i < n.links.size; i++){
                            connectVertex(edges, vertexSet, n, world.build(n.links.get(i)));
                        }
                        // 【贴着也算连上】节点紧挨着另一个节点 / 贴着一片组合体时，和 componentMembers
                        // 保持一致：并池/热量/面板都按同一张网络算（用户把节点摆在两台之间不点连线时
                        // 就是这么用的）。
                        if(n.proximity != null)
                            for(Building nb : n.proximity) connectVertex(edges, vertexSet, n, nb);
                    }
                }
                _tJ = System.nanoTime();

                // 4) 连通分量。
                ObjectSet<Building> visited = new ObjectSet<>();
                components = new Seq<>();
                for(Building v : vertices){
                    if(visited.contains(v)) continue;
                    Seq<Building> comp = new Seq<>();
                    Queue<Building> queue = new Queue<>();
                    queue.addLast(v);
                    visited.add(v);
                    while(!queue.isEmpty()){
                        Building cur = queue.removeFirst();
                        comp.add(cur);
                        Seq<Building> es = edges.get(cur);
                        if(es == null) continue;
                        for(Building nb : es){
                            if(visited.add(nb)) queue.addLast(nb);
                        }
                    }
                    components.add(comp);
                }
                _tK = System.nanoTime();

                // 5) 每个分量的组合体成员：按“当前 leader 归属”收集，不依赖可能过期的 group 缓存。
                compCount = components.size;
                compIdxOfLeader = new ObjectIntMap<>();
                for(int i = 0; i < compCount; i++){
                    for(Building b : components.get(i)){
                        if(ComboReflect.isComboBuild(b)) compIdxOfLeader.put(b, i);
                    }
                }
                compMembers = new Seq<>();
                for(int i = 0; i < compCount; i++) compMembers.add(new Seq<Building>());
                fillCompMembers(compMembers, all, compIdxOfLeader);
            }
            _tC = System.nanoTime();

            // 6) 断开连接器/节点时：被多个分量共用的模块按分量容量比例拆开，总值不变。
            splitAcrossComponents(compMembers);
            _tD = System.nanoTime();

            // 6.5) 共享配置：同一张网络里的连接件统一成同一份，并建"成员 -> 网络配置"索引。
            //      有任一项没勾的网络还会把节点电力连线重新对齐（勾上就连，取消就拆）。
            Seq<Integer> compMasks = new Seq<>();
            for(int i = 0; i < compCount; i++)
                compMasks.add(simpleNet ? resolveShareMaskOne(groupLeaders.get(i)) : resolveShareMask(components.get(i)));
            ComboShare.clearMemberMasks();
            for(int i = 0; i < compCount; i++){
                int mask = compMasks.get(i);
                if(ComboShare.fullShare(mask)) continue;
                Seq<Building> members = compMembers.get(i);
                for(int k = 0; k < members.size; k++) ComboShare.setMemberMask(members.get(k).pos(), mask);
            }
            if(!simpleNet){
                for(int i = 0; i < compCount; i++){
                    Seq<Building> verts = components.get(i);
                    for(int k = 0; k < verts.size; k++)
                        if(verts.get(k) instanceof ComboNode.ComboNodeBuild node) syncNodePower(node);
                }
            }

            // 7) 分量内合并共享池：运行期“相加”，读档窗口“去重”。
            for(int i = 0; i < compMembers.size; i++){
                mergeComponent(compMembers.get(i), compMasks.get(i), loadPhase);
            }
            // 8) 登记"网络成员 -> 整张网络"的索引，给组合仓库那套本地并仓逻辑查（见 networkMembers）。
            netByPos.clear();
            netMemberPos.clear();
            long netSig = 1125899906842597L;
            for(Seq<Building> members : compMembers){
                if(members.size <= 1) continue;
                for(Building m : members){
                    if(m != null && m.isValid()){
                        netByPos.put(m.pos(), members);
                        netMemberPos.add(m.pos());
                        netSig = netSig * 31 + m.pos();
                    }
                }
                netSig = netSig * 31 + 7;
            }
            // 网络结构变了 → 两边"本地组合"逻辑都要跟着重算（容量/面板/池子都按整张网络）。
            // 它们自己只认方块变化事件，连接器/节点的"连上/断开"不会触发它们，
            // 不叫一声就会出现"池子共享了、容量和面板还是自己那格"。
            if(netSig != lastNetSignature){
                lastNetSignature = netSig;
                CombinedStorageBlock.markDirty();
                CoopCombo.markDirty();
            }
            _tE = System.nanoTime();
            if(timeDebug){
                Log.info("[combine][time] 快照=@ms settle=@ms 建图=@ms 拆池=@ms 合池=@ms 合计=@ms all=@ verts=@ comps=@ "
                    + "| leaders=@ms vlist=@ms cross=@ms linker=@ms bfs=@ms members=@ms",
                    (_tA-_t0)/1e6, (_tB-_tA)/1e6, (_tC-_tB)/1e6, (_tD-_tC)/1e6, (_tE-_tD)/1e6, (_tE-_t0)/1e6,
                    all.size, vertexCount, compCount,
                    (_tG-_tB)/1e6, (_tH-_tG)/1e6, (_tI-_tH)/1e6, (_tJ-_tI)/1e6, (_tK-_tJ)/1e6, (_tC-_tK)/1e6);
            }

            // 读档语义只在读档过程中生效；任何一次运行期重建都意味着读档已经结束
            if(!loading && !world.isGenerating()) loadingWorld = false;
            // 读档窗口结束 → 做一次"离谱池子"体检（一次读档只做一次；见 repairInsanePools）
            if(loadPhase && !poolRepairDone && !loading && !world.isGenerating()) repairInsanePools();

            heatAlloc.clear();
        }catch(Throwable t){
            Log.err("[combine] ComboNet.rebuild failed", t);
        }
    }

    /** 局部组合体在成员被拆后可能仍是脏的单点；先调用它们自己的 rebuildCombo 恢复本地组。 */
    private static void settleLocalGroups(Seq<Building> all){
        // 读档留下的“待恢复 leader”先落地(与 updateTile 里的 comboPreUpdate 完全一致)。
        // 不落地的话这些成员会被当成独立的单点组，池归属与容量都会错位。
        for(Building b : all){
            if(ComboReflect.isComboBuild(b) && ComboReflect.hasPendingLeader(b)){
                ComboReflect.preUpdate(b);
                // 该成员所属的本地组合体也要重选一次，否则它的 group 缓存可能还是空的
                Building l = ComboReflect.leader(b);
                if(l != null && l != b) ComboReflect.setDirty(l, true);
            }
        }
        for(int iteration = 0; iteration < 6; iteration++){
            ObjectSet<Building> rebuilt = new ObjectSet<>();
            boolean changed = false;
            for(Building b : all){
                if(!ComboReflect.isComboBuild(b) || !ComboReflect.isDirty(b)) continue;
                if(ComboReflect.hasPendingLeader(b)) continue;
                Building leader = ComboReflect.leader(b);
                if(leader == null || !leader.isValid()) continue;
                if(rebuilt.add(leader)){
                    ComboReflect.rebuildLocal(leader);
                    changed = true;
                }
            }
            if(!changed) return;
        }
    }

    /**
     * 这个建筑愿不愿意跟**别的组合类**相邻合并（方块上声明 {@code allowCrossTypeCombo == true}）。
     *
     * <p>只有生产类组合方块（工厂/钻头/发电机/泵/抽油机/挖墙钻）声明了这个字段；
     * 墙（LinkWall）、炮塔、仓库、核心都没有 —— 它们不会被拉进这种"相邻跨类型"合并里
     * （墙尤其危险：它的血池按组算，混进一台工厂会把工厂血量也算进去）。
     */
    private static final ObjectMap<Block, Boolean> crossTypeCache = new ObjectMap<>();

    static boolean crossTypeCombinable(Building b){
        if(b == null || b.block == null) return false;
        Block blk = b.block;
        Boolean cached = crossTypeCache.get(blk);
        if(cached != null) return cached;
        boolean r;
        try{
            java.lang.reflect.Field f = blk.getClass().getField("allowCrossTypeCombo");
            r = f.getBoolean(blk);
        }catch(Throwable t){
            r = false;
        }
        crossTypeCache.put(blk, r);
        return r;
    }

    /** 相邻的两台不同类组合体能不能并成一台整机。默认要求两边都声明 allowCrossTypeCombo；
     *  例外：合体炮台（merge 模组的 SuperTurret）↔ 组合炮台（CombinedTurret 族）相邻也直接并。 */
    static boolean crossAdjacencyAllowed(Building a, Building b){
        if(a == null || b == null) return false;
        if(crossTypeCombinable(a) && crossTypeCombinable(b)) return true;
        return (isSuperTurret(a) && isTurretCombo(b)) || (isSuperTurret(b) && isTurretCombo(a));
    }

    /**
     * 这台是不是**合体炮台**（合体建筑已经独立成 {@code merge} 模组）。
     *
     * <p>两个模组各自一个类加载器、编译期看不到对方的类 —— 所以这里按**类名**认
     * （{@code merge.turret.SuperTurret}）。没装 merge 时恒 false，一切照旧。
     */
    static boolean isSuperTurret(Building b){
        if(b == null || b.block == null) return false;
        return b.block.getClass().getName().equals("merge.turret.SuperTurret");
    }

    /** 组合炮台族（本模组替换出来的炮塔）。 */
    static boolean isTurretCombo(Building b){
        return b != null && b.block instanceof mindustry.world.blocks.defense.turrets.Turret;
    }

    private static void addEdge(ObjectMap<Building, Seq<Building>> edges, Building a, Building b){
        if(a == null || b == null || a == b) return;
        Seq<Building> ea = edges.get(a);
        if(ea != null) ea.addUnique(b);
        Seq<Building> eb = edges.get(b);
        if(eb != null) eb.addUnique(a);
    }

    /**
     * 把一个连接件（节点/连接器）和它够到的东西连一条边。
     *
     * <p>【根因·用户报"和窑炉直接用了 9 个组合节点连接的挖铅钻头没有和窑炉组合"】
     * 这里以前只认 {@code ComboReflect.isComboBuild(nb)}，而组合节点/连接器本身**不是**组合建筑
     * （{@code isComboBuild} 走 comboGroup/comboLeader 字段与 CoopCombo 白名单，节点都没有），
     * 于是"节点连节点"的那一跳在**并池用的权威图**里被整条跳过：链子上的节点各自成孤点，
     * 只有链子两端够到的组合体各自成组 —— 表现就是"中间摆一串节点、两台不同类的机器还是没组合"
     *（同类机器因为本地分组也会穿节点链，反而看着是好的，所以只在跨类型时暴露）。
     * 显示面板走的是 componentMembers 的局部 BFS，那条路本来就认 links 里的节点，
     * 于是出现"面板说连上了、池子没共享"的诡异情况。现在两边一致。
     */
    private static void connectVertex(ObjectMap<Building, Seq<Building>> edges, ObjectSet<Building> vertexSet,
                                      Building from, Building nb){
        if(from == null || nb == null || !nb.isValid()) return;
        if(isLinker(nb)){
            if(vertexSet.contains(nb)) addEdge(edges, from, nb);
            return;
        }
        if(!ComboReflect.isComboBuild(nb)) return;
        Building l = ComboNode.groupRep(nb);
        if(l != null && vertexSet.contains(l)) addEdge(edges, from, l);
    }

    /**
     * 断开连接器/节点时，被多个连通分量共用的物品/液体模块必须拆开，
     * 否则断开后两个分量仍然共用同一个池。按各分量容量比例分配，拆分前后总值不变。
     */
    private static void splitAcrossComponents(Seq<Seq<Building>> comps){
        splitItemsAcrossComponents(comps);
        splitLiquidsAcrossComponents(comps);
    }

    /**
     * 把每栋组合建筑按"它所属本地组合体的 leader"归到对应分量（保持 all 的原有顺序）。
     */
    private static void fillCompMembers(Seq<Seq<Building>> compMembers, Seq<Building> all,
                                        ObjectIntMap<Building> compIdxOfLeader){
        for(int i = 0; i < all.size; i++){
            Building b = all.get(i);
            if(!ComboReflect.isComboBuild(b)) continue;
            Building l = ComboNode.groupRep(b);
            if(l == null) l = b;
            int idx = compIdxOfLeader.get(l, -1);
            if(idx >= 0) compMembers.get(idx).add(b);
        }
    }

    /**
     * 有没有哪一份物品模块被**两个以上分量**同时引用（只有这种情况才需要按分量拆池）。
     * 只记"第一次见到的分量下标"，不是它自己就说明被多个分量共用 —— 不建任何 IntSet。
     */
    private static boolean anyItemPoolSharedAcross(Seq<Seq<Building>> comps){
        IdentityHashMap<ItemModule, Integer> first = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            Seq<Building> comp = comps.get(i);
            for(int k = 0; k < comp.size; k++){
                Building m = comp.get(k);
                if(m == null || m.items == null) continue;
                Integer prev = first.put(m.items, i);
                if(prev != null && prev != i) return true;
            }
        }
        return false;
    }

    /** 同上，液体版。 */
    private static boolean anyLiquidPoolSharedAcross(Seq<Seq<Building>> comps){
        IdentityHashMap<LiquidModule, Integer> first = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            Seq<Building> comp = comps.get(i);
            for(int k = 0; k < comp.size; k++){
                Building m = comp.get(k);
                if(m == null || m.liquids == null) continue;
                Integer prev = first.put(m.liquids, i);
                if(prev != null && prev != i) return true;
            }
        }
        return false;
    }

    /**
     * 这个建筑手里的库存模块是不是"核心的池子"：核心本身，或者已经并进核心的组合仓库。
     * 这类模块**故意**被多台建筑共用，绝不能当成"断开残留的共享池"去拆。
     */
    private static boolean isCorePool(Building b){
        if(b instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild) return true;
        if(b instanceof CombinedStorageBlock.CombinedStorageBuild s) return s.linkedCoreOf() != null;
        // 【注意】这里**不能**用"模块正好是核心那份"来判断：组合工厂临时挂在核心池上时
        // 也满足这个条件，那样断开组合后它会被当成核心成员、永远留在核心库存上。
        // 判定"谁有资格待在核心池里"只看并仓状态；usesCorePool() 只在挑池子时当偏好用。
        return false;
    }

    private static void splitItemsAcrossComponents(Seq<Seq<Building>> comps){
        // 【性能】先做一次"便宜"的判断：分量之间没有任何共用物品模块时（绝大多数基地的绝大多数时刻），
        // 下面的 allMembers/owners/coreOwned 三张表纯属白建 —— 1000+ 分量时这一下就是 1.5ms 起步。
        if(!anyItemPoolSharedAcross(comps)) return;
        ObjectSet<Building> allMembers = new ObjectSet<>();
        for(Seq<Building> comp : comps)
            for(Building m : comp)
                if(m != null) allMembers.add(m);
        IdentityHashMap<ItemModule, IntSet> owners = new IdentityHashMap<>();
        // 核心的池子（核心自己、或"并进核心"的组合仓库）**不能拆**：
        // 它们本来就是故意共用同一份模块的。按分量拆会把它复制成好几份
        // （核心那份还原样留着），下次再并回核心就把副本加进去 —— 物品凭空翻倍
        // （用户报的"钢化玻璃 4500 变 7000+"，最后被容量截断压成"正好等于容量"）。
        IdentityHashMap<ItemModule, Boolean> coreOwned = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            for(Building m : comps.get(i)){
                if(m.items == null) continue;
                if(isCorePool(m)) coreOwned.put(m.items, Boolean.TRUE);
                IntSet set = owners.get(m.items);
                if(set == null){
                    set = new IntSet();
                    owners.put(m.items, set);
                }
                set.add(i);
            }
        }

        for(var entry : owners.entrySet()){
            IntSet ownerComps = entry.getValue();
            if(ownerComps.size <= 1) continue;
            if(coreOwned.containsKey(entry.getKey())){
                // 核心那份池子不拆（核心 + 并仓仓库本来就说好共用）。
                // 但**不是**核心成员的（例如被组合节点接进来的组合工厂）必须脱离：
                // 断开之后还挂在核心库存上，就会出现"组合断了、物品还跟着那台机器走"。
                ItemModule core = entry.getKey();
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.items == core && !isCorePool(m)){
                            m.items = new ItemModule();
                        }
                    }
                }
                continue;
            }

            ItemModule old = entry.getKey();
            // 【池子被组外也在用】核心库存 / 组合节点接进来的别的组合体也引用着这份模块时，
            // 按容量"复制一份"给各分量就是凭空多一份（原模块还在别人手里）——
            // 用户报的"拆工厂时核心里的东西全没了/翻倍"就是这个。
            // 这份池子不属于这些分量：分量里的成员换成空模块，池子留给真正的所有者。
            if(ComboReflect.itemPoolSharedOutside(old, allMembers)){
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.items == old) m.items = new ItemModule();
                    }
                }
                continue;
            }
            int n = ownerComps.size;
            int[] compIdx = new int[n];
            int[] caps = new int[n];
            int totalCap = 0;
            IntSet.IntSetIterator it = ownerComps.iterator();
            for(int k = 0; k < n && it.hasNext; k++){
                compIdx[k] = it.next();
                caps[k] = Math.max(componentItemCap(comps.get(compIdx[k])), 1);
                totalCap += caps[k];
            }

            ItemModule[] shares = new ItemModule[n];
            for(int k = 0; k < n; k++) shares[k] = new ItemModule();
            for(Item item : content.items()){
                int total = old.get(item);
                if(total <= 0) continue;
                int remaining = total;
                for(int k = 0; k < n; k++){
                    int ideal = (k == n - 1) ? remaining : Math.round(total * (float)caps[k] / totalCap);
                    ideal = Math.min(ideal, remaining);
                    if(ideal > 0){
                        shares[k].add(item, ideal);
                        remaining -= ideal;
                    }
                }
            }
            for(int k = 0; k < n; k++){
                // 现场拆出来的池子：重新共享时按真库存相加（见 freshSplitItems）
                markFreshItem(shares[k]);
                for(Building m : comps.get(compIdx[k])){
                    if(m.items == old) m.items = shares[k];
                }
            }
            // 【必须把原池清空】上面只给"这次算进分量里的成员"换了新池；万一有持有者
            // 这一轮没被分到任何分量（读档早期分组还没稳定时会发生），它手里还是原池(old)，
            // 里面照样是整份库存 —— 下一次合并一算，新池们 + 原池 = 翻倍。
            // 份额之和本来就等于原总量，所以原池清空不会丢东西。
            old.clear();
            freshSplitItems.remove(old);
        }
    }

    private static void splitLiquidsAcrossComponents(Seq<Seq<Building>> comps){
        // 同 splitItemsAcrossComponents：分量间没有共用液体模块就直接返回。
        if(!anyLiquidPoolSharedAcross(comps)) return;
        ObjectSet<Building> allMembers = new ObjectSet<>();
        for(Seq<Building> comp : comps)
            for(Building m : comp)
                if(m != null) allMembers.add(m);
        IdentityHashMap<LiquidModule, IntSet> owners = new IdentityHashMap<>();
        IdentityHashMap<LiquidModule, Boolean> coreOwned = new IdentityHashMap<>();
        for(int i = 0; i < comps.size; i++){
            for(Building m : comps.get(i)){
                if(m.liquids == null) continue;
                if(isCorePool(m)) coreOwned.put(m.liquids, Boolean.TRUE);
                IntSet set = owners.get(m.liquids);
                if(set == null){
                    set = new IntSet();
                    owners.put(m.liquids, set);
                }
                set.add(i);
            }
        }

        for(var entry : owners.entrySet()){
            IntSet ownerComps = entry.getValue();
            if(ownerComps.size <= 1) continue;
            if(coreOwned.containsKey(entry.getKey())){
                // 同物品：核心的液体池不拆，但非核心成员要脱离
                LiquidModule core = entry.getKey();
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.liquids == core && !isCorePool(m)){
                            m.liquids = new LiquidModule();
                        }
                    }
                }
                continue;
            }

            LiquidModule old = entry.getKey();
            // 【池子被组外也在用】同物品：不是这些分量独有的池子不能"复制一份"分给各分量。
            if(ComboReflect.liquidPoolSharedOutside(old, allMembers)){
                for(int i = 0; i < comps.size; i++){
                    for(Building m : comps.get(i)){
                        if(m.liquids == old) m.liquids = new LiquidModule();
                    }
                }
                continue;
            }
            int n = ownerComps.size;
            int[] compIdx = new int[n];
            float[] caps = new float[n];
            float totalCap = 0f;
            IntSet.IntSetIterator it = ownerComps.iterator();
            for(int k = 0; k < n && it.hasNext; k++){
                compIdx[k] = it.next();
                caps[k] = Math.max(componentLiquidCap(comps.get(compIdx[k])), 1f);
                totalCap += caps[k];
            }

            LiquidModule[] shares = new LiquidModule[n];
            for(int k = 0; k < n; k++) shares[k] = new LiquidModule();
            for(Liquid liquid : content.liquids()){
                float total = old.get(liquid);
                if(total <= 0.001f) continue;
                float remaining = total;
                for(int k = 0; k < n; k++){
                    float ideal = (k == n - 1) ? remaining : total * caps[k] / totalCap;
                    ideal = Math.min(ideal, remaining);
                    if(ideal > 0.001f){
                        shares[k].add(liquid, ideal);
                        remaining -= ideal;
                    }
                }
            }
            for(int k = 0; k < n; k++){
                markFreshLiquid(shares[k]);
                for(Building m : comps.get(compIdx[k])){
                    if(m.liquids == old) m.liquids = shares[k];
                }
            }
            // 同物品：原池必须清空，否则"没被分到分量的持有者"手里那份会让总量翻倍。
            old.clear();
            freshSplitLiquids.remove(old);
        }
    }

    private static void markFreshItem(ItemModule mod){
        if(mod == null) return;
        if(freshSplitItems.size > freshSplitLimit) freshSplitItems.clear();
        freshSplitItems.add(mod);
    }

    private static void markFreshLiquid(LiquidModule mod){
        if(mod == null) return;
        if(freshSplitLiquids.size > freshSplitLimit) freshSplitLiquids.clear();
        freshSplitLiquids.add(mod);
    }

    /**
     * 分量内统一共享池。
     * 运行期(loadPhase=false)：各成员手里是真实库存，合并 = 相加到 pos 最小的成员模块；
     * 读档窗口(loadPhase=true)：各成员手里是存档写下的同一份池的重复副本，
     * 合并 = 相同副本只留一份、不同内容相加，绝不能把重复副本当两份真库存相加。
     */
    /**
     * 一张网络里每个连接件的共享配置统一成一份。
     *
     * <p>"连接在一起的组合节点行为一致、点任意一个都能改整张网络的配置"就靠这里兜底：
     * 取 stamp 最大（＝最后被改过）的那个连接件的配置，并列时取 pos 最小的，
     * 然后写回整张网络的所有连接件。纯本地组合体（没有连接件）恒为全共享 = 老行为。
     */
    private static int resolveShareMask(Seq<Building> vertices){
        int best = -1, bestStamp = Integer.MIN_VALUE, bestPos = Integer.MAX_VALUE;
        for(int i = 0; i < vertices.size; i++){
            Building v = vertices.get(i);
            if(v == null) continue;
            int st, m;
            if(v instanceof ComboShare.Holder h){
                st = h.shareStamp();
                m = h.shareMask();
            }else if(ComboShare.hasBodyMask(v)){
                // 组合体自己也带共享配置（悬浮面板里勾选的那份），和连接件一视同仁
                st = ComboShare.bodyStampOf(v);
                m = ComboShare.bodyMaskOf(v);
            }else{
                continue;
            }
            if(st > bestStamp || (st == bestStamp && v.pos() < bestPos)){
                bestStamp = st;
                bestPos = v.pos();
                best = m;
            }
        }
        if(best < 0) return ComboShare.ALL;
        for(int i = 0; i < vertices.size; i++){
            Building v = vertices.get(i);
            if(v instanceof ComboShare.Holder h){
                h.shareMask(best);
                h.shareStamp(bestStamp);
            }else if(ComboShare.hasBodyMask(v)){
                ComboShare.setBodyMask(v.pos(), best, bestStamp);
            }
        }
        return best & ComboShare.ALL;
    }

    /** 单点分量的共享配置（快路用：分量里只有一个组合体，没有节点/连接器要统一）。 */
    private static int resolveShareMaskOne(Building v){
        if(v == null) return ComboShare.ALL;
        if(v instanceof ComboShare.Holder h) return h.shareMask() & ComboShare.ALL;
        if(ComboShare.hasBodyMask(v)) return ComboShare.bodyMaskOf(v) & ComboShare.ALL;
        return ComboShare.ALL;
    }

    /**
     * 一张网络里的成员按"本地组合体"分组（节点/连接器跨距离接起来的那几片各自算一组）。
     *
     * <p>【现在只有"不共享"的项才需要分组】而且口径已经改成**按单台**（见 {@link #perMemberGroups}）——
     * 用户报的"关闭物品共享后物品模块还是共享的"就是因为以前按"本地组合体"分组、
     * 组内照旧共用一份。这个方法留着给"将来想按本地组合体粒度共享某项"用。
     */
    @SuppressWarnings("unused")
    private static Seq<Seq<Building>> localGroups(Seq<Building> members){
        Seq<Seq<Building>> groups = new Seq<>();
        ObjectMap<Building, Seq<Building>> byRep = new ObjectMap<>();
        for(int i = 0; i < members.size; i++){
            Building m = members.get(i);
            if(m == null) continue;
            Building rep = ComboNode.groupRep(m);
            if(rep == null) rep = m;
            Seq<Building> g = byRep.get(rep);
            if(g == null){
                g = new Seq<>();
                byRep.put(rep, g);
                groups.add(g);
            }
            g.add(m);
        }
        return groups;
    }

    /**
     * 每台成员各自一组。
     *
     * <p>【用户报】"关闭物品共享后物品模块还是共享的" —— 以前"不共享物品"只把物品池拆到
     * **本地组合体**那一层（相邻同类照旧共用一份），于是把一台组合体的"物品"取消勾选时
     * 什么都没变。现在只要"物品"没勾，就按**单台**粒度拆：每台自己一份模块、自己算容量。
     */
    private static Seq<Seq<Building>> perMemberGroups(Seq<Building> members){
        Seq<Seq<Building>> groups = new Seq<>();
        for(int i = 0; i < members.size; i++){
            Building m = members.get(i);
            if(m == null) continue;
            Seq<Building> g = new Seq<>();
            g.add(m);
            groups.add(g);
        }
        return groups;
    }

    /**
     * 分量内统一共享池 —— 按共享配置**逐项**处理。
     *
     * <p>运行期(loadPhase=false)：各成员手里是真实库存，合并 = 相加到 pos 最小的成员模块；
     * 读档窗口(loadPhase=true)：各成员手里是存档写下的同一份池的重复副本，
     * 合并 = 相同副本只留一份、不同内容相加，绝不能把重复副本当两份真库存相加。
     *
     * <p>没勾共享的那一项不在整张网络里并，而是回到"本地组合体"粒度：相邻成组的成员照旧
     * 共用一份（组合体本身的语义），组与组之间各留各的。
     */
    private static void mergeComponent(Seq<Building> members, int mask, boolean loadPhase){
        if(members.size <= 0) return;
        // 【性能】单台分量（独立的一台组合建筑，最常见）：它手里就是自己那份池子，
        // 下面那套 pickItemModule/localGroups 只是白建 Seq、白扫一遍。
        // 结果完全等价：itemCap/liquidCap 就是这一台的基础容量，模块一个都不动。
        if(members.size == 1){
            Building one = members.first();
            if(one != null){
                ComboReflect.setItemCap(one, ComboReflect.baseItemCap(one));
                ComboReflect.setLiquidCap(one, ComboReflect.baseLiquidCap(one));
                ComboReflect.markClean(one);
            }
            return;
        }
        boolean shareItems = (mask & ComboShare.ITEMS) != 0;
        boolean shareLiquids = (mask & ComboShare.LIQUIDS) != 0;
        // 两项都共享时不用分组，省掉一次遍历（默认配置就走这条路，和以前一模一样）。
        // 物品/液体没勾共享 → **按单台**分组（每台各留各的模块，见 perMemberGroups）：
        // 用户报的"关闭物品共享后物品模块还是共享的"，根因就是以前只拆到"本地组合体"那一层。
        Seq<Seq<Building>> liquidGroups = shareLiquids ? null : perMemberGroups(members);
        Seq<Seq<Building>> itemGroups = shareItems ? null : perMemberGroups(members);

        if(shareItems){
            ItemModule itemTarget = pickItemModule(members, loadPhase);
            if(itemTarget != null){
                for(Building m : members){
                    if(m.items == null || m.items == itemTarget) continue;
                    if(!loadPhase) moveItems(m.items, itemTarget);
                    m.items = itemTarget;
                }
            }
        }else{
            // 刚把"物品"取消勾选时大家还是同一口池子：先按各自的容量比例拆回"每台一份"
            splitItemsAcrossComponents(itemGroups);
            for(Seq<Building> g : itemGroups){
                ItemModule target = pickItemModule(g, loadPhase);
                if(target == null) continue;
                for(Building m : g){
                    if(m.items == null || m.items == target) continue;
                    if(!loadPhase) moveItems(m.items, target);
                    m.items = target;
                }
            }
        }

        if(shareLiquids){
            LiquidModule liquidTarget = pickLiquidModule(members, loadPhase);
            if(liquidTarget != null){
                for(Building m : members){
                    if(m.liquids == null || m.liquids == liquidTarget) continue;
                    if(!loadPhase) moveLiquids(m.liquids, liquidTarget);
                    m.liquids = liquidTarget;
                }
            }
        }else{
            splitLiquidsAcrossComponents(liquidGroups);
            for(Seq<Building> g : liquidGroups){
                LiquidModule target = pickLiquidModule(g, loadPhase);
                if(target == null) continue;
                for(Building m : g){
                    if(m.liquids == null || m.liquids == target) continue;
                    if(!loadPhase) moveLiquids(m.liquids, target);
                    m.liquids = target;
                }
            }
        }

        int itemCap = componentItemCap(members);
        float liquidCap = componentLiquidCap(members);
        // 容量：共享的按整分量算，不共享的按"自己那一组"算
        if(shareItems){
            for(Building m : members) ComboReflect.setItemCap(m, itemCap);
        }else{
            for(Seq<Building> g : itemGroups){
                int ic = componentItemCap(g);
                for(Building m : g) ComboReflect.setItemCap(m, ic);
            }
        }
        if(shareLiquids){
            for(Building m : members) ComboReflect.setLiquidCap(m, liquidCap);
        }else{
            for(Seq<Building> g : liquidGroups){
                float lc = componentLiquidCap(g);
                for(Building m : g) ComboReflect.setLiquidCap(m, lc);
            }
        }
        for(Building m : members) ComboReflect.markClean(m);
    }

    private static ItemModule pickItemModule(Seq<Building> members, boolean loadPhase){
        Seq<ItemModule> mods = new Seq<>();
        for(Building m : members){
            if(m.items != null && !mods.contains(m.items, true)) mods.add(m.items);
        }
        if(mods.size <= 1) return mods.isEmpty() ? null : mods.first();
        // 读档窗口里：同一分量的多份池子一律当"同一口池子的副本/历史代"处理，逐物品取**较大值**。
        // 以前用"内容完全相同才算副本、不同就相加"—— 用户存档里同一条网络留着好几代被乘过的池子
        // （1×/3×/9×…），一相加就再翻一倍（实测一轮存读 14.7M → 32.6M → 69M，用户报的"物品异常增长"）。
        // 取较大值不会把池子乘出来，也不会因为"有空模块"把库存抹成 0。
        return loadPhase ? mergeCopiesMax(members, mods) : moduleOfFirst(members, mods);
    }

    /** 读档：把同一分量的多份物品池按"逐物品取较大值"并成一份（不叠加）。 */
    private static ItemModule mergeCopiesMax(Seq<Building> members, Seq<ItemModule> mods){
        ItemModule dst = moduleOfFirst(members, mods);
        if(dst == null) return null;
        for(ItemModule mod : mods){
            if(mod == dst) continue;
            for(Item item : content.items()){
                if(mod.get(item) > dst.get(item)) dst.set(item, mod.get(item));
            }
            freshSplitItems.remove(mod);
        }
        return dst;
    }

    private static LiquidModule pickLiquidModule(Seq<Building> members, boolean loadPhase){
        Seq<LiquidModule> mods = new Seq<>();
        for(Building m : members){
            if(m.liquids != null && !mods.contains(m.liquids, true)) mods.add(m.liquids);
        }
        if(mods.size <= 1) return mods.isEmpty() ? null : mods.first();
        return loadPhase ? mergeCopiesMaxLiquid(members, mods) : moduleOfFirstLiquid(members, mods);
    }

    /** 读档：液体池版（逐液体取较大值）。 */
    private static LiquidModule mergeCopiesMaxLiquid(Seq<Building> members, Seq<LiquidModule> mods){
        LiquidModule dst = moduleOfFirstLiquid(members, mods);
        if(dst == null) return null;
        for(LiquidModule mod : mods){
            if(mod == dst) continue;
            for(Liquid liquid : content.liquids()){
                if(mod.get(liquid) > dst.get(liquid)) dst.set(liquid, mod.get(liquid));
            }
            freshSplitLiquids.remove(mod);
        }
        return dst;
    }

    /** 这台机器是不是"已经并进核心"的组合仓库（它的模块就是核心库存）。 */
    private static boolean coreLinked(Building m){
        return m instanceof CombinedStorageBlock.CombinedStorageBuild sb
            && sb.linkedCore != null && sb.linkedCore.isValid();
    }

    /**
     * 手里这份模块**就是某座核心的库存**吗（不管它是核心自己、并仓的仓库，还是别人的模块在
     * 网络重整时被换成了核心那份）。
     *
     * 【为什么还要查一遍】并仓状态（linkedCore）是 CombinedStorageBlock 那边重算出来的，
     * 和 ComboNet 的重建不是同一时刻：中间那一瞬 coreLinked() 可能还是 false，
     * 于是网络池会挑成组合工厂那一份 —— 玩家看到的就是"核心的东西全跑到工厂里去了"
     * （用户报的：组合节点接组合仓库 + 石墨压缩机）。
     */
    private static boolean usesCorePool(Building m){
        if(m == null || m.items == null || state == null || m.team == null) return false;
        var data = state.teams.get(m.team);
        if(data == null || data.cores == null) return false;
        for(var core : data.cores){
            if(core != null && core.items == m.items) return true;
        }
        return false;
    }

    /**
     * 取 pos 最小的成员手里的那份模块，保证每次重建选到同一个池对象。
     *
     * 例外：并进核心的组合仓库，它的模块**就是核心库存**。如果网络里有这么一台，
     * 目标模块必须是它 —— 否则整个网络换成别的模块时，核心会凭空少掉这些物品。
     */
    private static ItemModule moduleOfFirst(Seq<Building> members, Seq<ItemModule> candidates){
        ItemModule core = null;
        int corePos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.items != null && (coreLinked(m) || usesCorePool(m)) && candidates.contains(m.items, true) && m.pos() < corePos){
                core = m.items;
                corePos = m.pos();
            }
        }
        if(core != null) return core;

        ItemModule best = null;
        int bestPos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.items != null && candidates.contains(m.items, true) && m.pos() < bestPos){
                best = m.items;
                bestPos = m.pos();
            }
        }
        return best;
    }

    private static LiquidModule moduleOfFirstLiquid(Seq<Building> members, Seq<LiquidModule> candidates){
        LiquidModule core = null;
        int corePos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.liquids != null && (coreLinked(m) || usesCorePool(m)) && candidates.contains(m.liquids, true) && m.pos() < corePos){
                core = m.liquids;
                corePos = m.pos();
            }
        }
        if(core != null) return core;

        LiquidModule best = null;
        int bestPos = Integer.MAX_VALUE;
        for(Building m : members){
            if(m.liquids != null && candidates.contains(m.liquids, true) && m.pos() < bestPos){
                best = m.liquids;
                bestPos = m.pos();
            }
        }
        return best;
    }

    /**
     * 运行期的目标模块：pos 最小的成员手里的那一份（其余相加进去）。
     * 读档窗口则要小心：同一份池被每个成员各写了一份完全相同的副本，
     * 内容相同的副本只能保留一份，内容不同的（旧档/断开残留的分配结果）仍要相加，
     * 否则要么翻倍、要么丢库存。
     */
    private static ItemModule mergeDistinctItems(Seq<Building> members, Seq<ItemModule> mods){
        Seq<ItemModule> unique = new Seq<>();
        for(ItemModule mod : mods){
            boolean dup = false;
            for(ItemModule seen : unique){
                if(sameItems(seen, mod)){
                    dup = true;
                    break;
                }
            }
            if(!dup) unique.add(mod);
        }
        ItemModule dst = moduleOfFirst(members, unique);
        // 【必须遍历全部、只跳过目标自己】原来看起来是"把第 0 份留下、其余搬进 dst"，
        // 但 dst 是 moduleOfFirst() 挑的（优先核心池，否则 pos 最小的那台），
        // **不一定是 unique.get(0)**：一旦不是，第 0 份库存就永远不会被搬走，
        // 紧接着 mergeComponent() 把每个成员的模块都指向 dst —— 那份库存被静默丢掉
        //（用户报的"物品异常减少"）。
        for(ItemModule mod : unique){
            if(mod != dst){
                moveItems(mod, dst);
                freshSplitItems.remove(mod);
            }
        }
        return dst;
    }

    private static LiquidModule mergeDistinctLiquids(Seq<Building> members, Seq<LiquidModule> mods){
        Seq<LiquidModule> unique = new Seq<>();
        for(LiquidModule mod : mods){
            boolean dup = false;
            for(LiquidModule seen : unique){
                if(sameLiquids(seen, mod)){
                    dup = true;
                    break;
                }
            }
            if(!dup) unique.add(mod);
        }
        LiquidModule dst = moduleOfFirstLiquid(members, unique);
        // 同上：目标不一定是 unique.get(0)，必须遍历全部、只跳过目标自己
        for(LiquidModule mod : unique){
            if(mod != dst){
                moveLiquids(mod, dst);
                freshSplitLiquids.remove(mod);
            }
        }
        return dst;
    }

    private static boolean sameItems(ItemModule a, ItemModule b){
        if(a == b) return true;
        if(a == null || b == null) return false;
        // 现场拆出来的池子是真库存（不是读档留下的重复副本），哪怕内容一模一样也不能当副本丢掉
        if(freshSplitItems.contains(a) || freshSplitItems.contains(b)) return false;
        for(Item item : content.items()){
            if(a.get(item) != b.get(item)) return false;
        }
        return true;
    }

    private static boolean sameLiquids(LiquidModule a, LiquidModule b){
        if(a == b) return true;
        if(a == null || b == null) return false;
        if(freshSplitLiquids.contains(a) || freshSplitLiquids.contains(b)) return false;
        for(Liquid liquid : content.liquids()){
            if(Math.abs(a.get(liquid) - b.get(liquid)) > 0.001f) return false;
        }
        return true;
    }

    private static int componentItemCap(Seq<Building> members){
        int total = 0;
        for(Building m : members) total += ComboReflect.baseItemCap(m);
        return total;
    }

    /**
     * 面板用：这台建筑所在**分量**（= 共用同一份池子的那整张网络）的物品容量。
     *
     * <p>为什么要单独给一个：协作组合面板原来打印的分母是**这台方块自己**的容量
     * （例如"组合钻机 x6"= 6 台钻机的 60），而它显示的池子却可能是**整张网络**共用的那一份
     * —— 于是面板上出现"1482273/60"这种数字（用户视频里的现场：铜 1482273/60、
     * 硅 5555520/60，每帧还在百万/几十之间跳）。分子分母必须同源：池子是网络的，分母也得是网络的。
     */
    public static int panelItemCap(Building self){
        // 勾了"物品"共享才算整张网络；没勾就只看本地组合体（分子分母仍然同源）
        int cap = componentItemCap(itemScope(self));
        // 不在任何组合网络里（分量只有自己）时用这台方块自己的基础容量：
        // 否则核心这类方块会算出 1，面板/审计就会把"核心里 12000 物品"误判成坏账
        if(cap <= 0) cap = ComboReflect.baseItemCap(self);
        // 【核心扩容那部分不能漏】核心真正的每种物品上限写在 CoreBuild.storageCapacity 里
        // （原版按"核心 + 相邻仓库"算，本模组的组合仓库/节点并仓还会继续往上加），
        // 而 componentItemCap 只会把 core.block.itemCapacity 加一遍 —— 于是核心（以及并进核心的
        // 那些仓库）的悬浮面板只显示"核心自己那点容量"，容器/别的核心扩出来的部分全丢了
        // （用户报的："核心的悬浮面板物品最大容量只显示该核心的物品容量"）。
        try{
            for(Building m : itemScope(self)){
                if(m instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild core && core.isValid())
                    cap = Math.max(cap, core.storageCapacity);
            }
        }catch(Throwable ignored){
        }
        return Math.max(cap, 1);
    }

    /** 同上，液体版。 */
    public static float panelLiquidCap(Building self){
        float cap = componentLiquidCap(liquidScope(self));
        if(cap <= 0f) cap = ComboReflect.baseLiquidCap(self);
        return Math.max(cap, 1f);
    }

    /**
     * 面板用：这台建筑所在网络**实际共用的那一份**物品模块（网络已经共用时就是它自己的那份）。
     *
     * <p>面板原来直接读 {@code build.items}（这台方块自己的模块），可并池之后池子可能躺在网络里
     * 别的成员手里 —— 于是面板要么显示"（空）"，要么在不同模块之间来回跳（用户视频里
     * 组合钻机的面板数字每帧在百万/几十之间跳，就是这个）。
     */
    public static ItemModule panelItemPool(Building self){
        if(self == null) return null;
        // 1) 勾了"物品"共享时：节点/连接器接起来的**网络**共用池
        if(ComboShare.shares(self, ComboShare.ITEMS)){
            ItemModule netPool = poolModuleFor(self);
            if(netPool != null) return netPool;
        }
        // 2) 本地组合体（相邻成组）里成员真的共用一份时，取那一份
        ItemModule shared = null;
        for(Building m : ComboReflect.group(self)){
            if(m == null || m.items == null) continue;
            if(shared == null) shared = m.items;
            else if(shared != m.items) return self.items;   // 没共用：各自看各自的
        }
        return shared != null ? shared : self.items;
    }

    /** 同上，液体版。 */
    public static LiquidModule panelLiquidPool(Building self){
        if(self == null) return null;
        if(ComboShare.shares(self, ComboShare.LIQUIDS)){
            LiquidModule netPool = poolLiquidFor(self);
            if(netPool != null) return netPool;
        }
        LiquidModule shared = null;
        for(Building m : ComboReflect.group(self)){
            if(m == null || m.liquids == null) continue;
            if(shared == null) shared = m.liquids;
            else if(shared != m.liquids) return self.liquids;
        }
        return shared != null ? shared : self.liquids;
    }

    private static float componentLiquidCap(Seq<Building> members){
        float total = 0f;
        for(Building m : members) total += ComboReflect.baseLiquidCap(m);
        return total;
    }

    // 【别把"内部搬池子"记成流量】LiquidModule.add/ItemModule.add 会把搬运量记进
    // 流量窗口（cacheSums += amount），面板（含 MindustryX 的流量行）就会显示
    // "每秒几千的水进账"，其实只是把两份池子并成一份、总量一点没变 ——
    // 用户报的"莫名其妙显示进了水实则没有，储罐也没和抽水机连管"。
    // stopFlow() 让这份模块的流量窗口从头重新统计（下一次真实进液再算）。

    private static void moveItems(ItemModule from, ItemModule to){
        if(from == null || to == null || from == to) return;
        for(Item item : content.items()){
            int amt = from.get(item);
            if(amt > 0){
                to.add(item, amt);
                from.remove(item, amt);
            }
        }
        to.stopFlow();
    }

    private static void moveLiquids(LiquidModule from, LiquidModule to){
        if(from == null || to == null || from == to) return;
        for(Liquid liquid : content.liquids()){
            float amt = from.get(liquid);
            if(amt > 0.001f){
                to.add(liquid, amt);
                from.remove(liquid, amt);
            }
        }
        to.stopFlow();
    }

    // -------------------- 节点热量网络 --------------------

    public static float heatFor(Building consumer){
        if(consumer == null || state == null) return 0f;
        Building leader = ComboReflect.leader(consumer);
        if(leader == null) return 0f;
        HeatAllocEntry e = heatAlloc.get(leader.pos());
        // 没分配过，或分配结果已经过期（网络拆了/换图了）→ 视为无热
        if(e == null || state.updateId - e.frame > heatAllocExpireTicks) return 0f;
        return e.amount;
    }

    public static void updateHeatNode(ComboNode.ComboNodeBuild node){
        if(node == null || state == null || node.power == null) return;
        // 没勾"热量"：整张网络不通热（节点自己也不再当热源往外送）
        if(!node.shareBit(ComboShare.HEAT)){
            node.heat = 0f;
            // 【关键】探针不在这里摘就没人摘了：distributeHeat 不再跑，
            // feedRemoteHeatConsumers 里的"摘除不再喂热的探针"也随之停摆，
            // 探针把上一份热量永远喂给 afflict —— "取消热量传递后还在传热"（用户报的）。
            clearHeatProbes(componentMembers(node));
            return;
        }
        beginHeatFrame();

        // 节点互连后，同一条链上的每个节点看到的组完全一样 —— 热量分配必须由
        // 链上唯一"负责人"（互连/贴邻节点簇里 pos 最小者）做一次，否则同一批
        // 产热组的存量热被每个节点各扣一遍、需热组各领一份（越领越多/速耗翻倍）。
        // （不是负责人的节点**不要**把自己清零：负责人这一轮会把整张网络里所有连接件的
        //  对外热量一起写好，清零会把那份抹掉。）
        if(!isHeatAuthority(node)){
            return;
        }

        distributeHeat(node, true);
    }

    /**
     * 连接器网络的热量。
     *
     * <p>两部分：一是让**贴着连接器的导热管/热熔炉能读到整张网络的热量**（连接器和节点一样
     * 当导热管用，用户报的"组合连接器不能传递热量"）；二是配置里把某项取消勾选、两端不再是
     * 同一台机器时，按勾选的"热量"在网络层通算（全共享时本地组合体自己那套已经在算，
     * 这里不能再分配一遍，会重复计热）。
     */
    public static void updateHeatConnector(ComboConnector.ComboConnectorBuild conn){
        if(conn == null || state == null || world == null) return;
        if(!conn.shareBit(ComboShare.HEAT)){
            conn.heat = 0f;
            clearHeatProbes(componentMembers(conn));
            return;
        }
        beginHeatFrame();
        if(!isConnectorHeatAuthority(conn)) return;
        distributeHeat(conn, !ComboShare.fullShare(conn.shareMask()));
    }

    /** 连接器网络的热量负责人：分量里没有节点、且是 pos 最小的那个连接器。 */
    private static boolean isConnectorHeatAuthority(ComboConnector.ComboConnectorBuild conn){
        try{
            Seq<Building> linkers = componentLinkers(conn);
            for(int i = 0; i < linkers.size; i++){
                Building l = linkers.get(i);
                if(l instanceof ComboNode.ComboNodeBuild) return false;   // 有节点：交给节点那一套
                if(l instanceof ComboConnector.ComboConnectorBuild c && c != conn && c.pos() < conn.pos()) return false;
            }
        }catch(Throwable ignored){
        }
        return true;
    }

    /**
     * 网络热量：{@code allocate} = 要不要把整张网络的产热按需热比例分给组内需热端
     * （节点网络一直要；连接器网络只在"两端不再是同一台机器"时才要，否则重复计热）。
     *
     * <p>不管分不分配，都会把**整张网络的热量写到网络里每个连接件身上** ——
     * 节点和连接器都是 HeatBlock，贴着它们的原版导热管/热路由器/热熔炉就能读到整组热量
     * （用户报的"组合节点和组合连接器不能传递热量"）。
     */
    private static void distributeHeat(Building hub, boolean allocate){
        Seq<Building> groups = linkedHeatGroupsFor(hub);
        Seq<Building> linkers = componentLinkers(hub);
        Seq<Building> members = componentMembers(hub);
        ObjectSet<Building> memberSet = new ObjectSet<>();
        for(int i = 0; i < members.size; i++) memberSet.add(members.get(i));

        int n = groups.size;
        float[] source = new float[n];
        float[] demand = new float[n];
        // 经连接件从网络外面进来的热（导热管从别处引过来 / 原版产热方块贴着节点或连接器）
        float totalSource = linkerExternalHeat(linkers, memberSet);
        float totalDemand = 0f;
        for(int i = 0; i < n; i++){
            Building leader = groups.get(i);
            source[i] = groupHeatSource(leader);
            demand[i] = groupHeatDemand(leader);
            totalSource += source[i];
            totalDemand += demand[i];
        }
        // 【连线接进来的"纯热"方块】组合节点也能连 mod 的制热机 / 需热炮台（它们不在组合池里，
        // 不该共享物品/液体，但热量必须通算）：先把它们产的热并进网络总热量。
        Seq<Building> heatLinked = collectLinkedHeatBuildings(linkers);
        for(int i = 0; i < heatLinked.size; i++) totalSource += heatSourceOf(heatLinked.get(i));

        if(allocate && totalDemand > 0.001f){
            for(int i = 0; i < n; i++){
                float amount = totalSource * demand[i] / totalDemand;
                // 一个组只属于一个连通分量、一个分量只有一个分配负责人，直接覆盖写
                // （旧实现 increment 累加 + 每帧整体清空，两件事都与时序/多网络冲突，见 heatAlloc 声明）
                HeatAllocEntry e = heatAlloc.get(groups.get(i).pos());
                if(e == null){
                    e = new HeatAllocEntry();
                    heatAlloc.put(groups.get(i).pos(), e);
                }
                e.frame = state.updateId;
                e.amount = amount;
                if(amount > 0.001f && source[i] > 0.001f){
                    deductStoredHeat(groups.get(i), amount * source[i] / Math.max(totalSource, 0.001f));
                }
            }
        }

        // 连接件对外供热：贴在节点/连接器上的导热管、热熔炉都能读到整张网络的热量
        for(int i = 0; i < linkers.size; i++) setLinkerHeat(linkers.get(i), totalSource);

        // 【连线接进来的需热端】节点/连接器是跨距离连接的，可原版 calculateHeat 只看贴着
        // proximity 的邻居 —— "制热机 --节点连线-- 需热炮台"里炮台读不到节点上的热。
        // 这里给那些"在网里、但没贴着连接件"的需热建筑插一个热探针（用户报的那条）。
        feedRemoteHeatConsumers(groups, heatLinked, linkers, totalSource);
    }

    private static void setLinkerHeat(Building linker, float value){
        if(linker instanceof ComboNode.ComboNodeBuild node) node.heat = value;
        else if(linker instanceof ComboConnector.ComboConnectorBuild conn) conn.heat = value;
    }

    /** 给"连线接进来、又没贴着连接件"的需热建筑插的热探针（pos → 探针）。 */
    private static final arc.struct.IntMap<combine.util.ComboHeatProbe.ComboHeatProbeBuild> heatProbes = new arc.struct.IntMap<>();

    /**
     * 把整张网络的热量喂给**跨距离连线接进来**的需热建筑。
     *
     * <p>贴着节点/连接器的建筑本来就通过原版 {@code calculateHeat} 看得见（见
     * {@link #setLinkerHeat}），但"制热机 --节点连线-- 需热炮台"里炮台离节点很远，
     * 原版只看 {@code proximity}，读到的热是 0（用户报的"热量传不到炮台"）。
     * 这里给这类建筑在它自己坐标上插一个热探针（{@code rotate=false}，
     * 原版当作"整份热都在这一格"），其 {@code heat()} = 网络总热量。
     */
    private static void feedRemoteHeatConsumers(Seq<Building> groups, Seq<Building> heatLinked,
                                                Seq<Building> linkers, float totalSource){
        if(state == null || combine.util.ComboHeatProbe.probe == null){
            warnProbeNullOnce();
            return;
        }
        arc.struct.IntSet keep = new arc.struct.IntSet();
        for(int i = 0; i < groups.size; i++){
            Building leader = groups.get(i);
            if(leader == null) continue;
            for(Building m : ComboReflect.group(leader)){
                if(m == null || !m.isValid() || !isHeatConsumer(m)) continue;
                if(touchesLinker(m, linkers)) continue; // 贴着连接件：原版路径看得见，别重复计
                // 【已经有热邻居的不用探针】它贴着别的热方块（导热管链/产热方），原版 calculateHeat
                // 会顺着邻居把热传过来；再给每个都插一份"整网热量"的探针，就会在链条上逐格叠加
                // （用户报的"连接器接一排热量传输机，热量一格一格涨上去"）。
                if(hasHeatNeighbor(m)) continue;
                if(attachHeatProbe(m, totalSource)) keep.add(m.pos());
            }
        }
        // 连线接进来的"纯热"需热建筑（mod 炮台等）：一样给它们插探针
        for(int i = 0; i < heatLinked.size; i++){
            Building m = heatLinked.get(i);
            if(m == null || !m.isValid() || !isHeatConsumer(m)) continue;
            if(touchesLinker(m, linkers)) continue;
            if(hasHeatNeighbor(m)) continue;
            if(attachHeatProbe(m, totalSource)) keep.add(m.pos());
        }
        // 不再需要喂热的（断线/拆了/改成贴着连接件了）把探针摘掉，免得残留一份旧热量。
        // 【 guard 】只摘"最近两 tick 没人喂"的：本 tick 或上一 tick 刚被喂过的探针属于
        // 另一张并存的网络（同一 tick 里对方可能还没轮到 update），不能顺手摘掉，
        // 否则两张网络共存时探针每帧被拆了又建、afflict 的热量会闪烁。
        arc.struct.IntSeq stale = new arc.struct.IntSeq();
        for(var it = heatProbes.iterator(); it.hasNext();){
            var e = it.next();
            if(!keep.contains(e.key) && state.updateId - e.value.lastFed > 1) stale.add(e.key);
        }
        for(int i = 0; i < stale.size; i++){
            int pos = stale.get(i);
            combine.util.ComboHeatProbe.ComboHeatProbeBuild pb = heatProbes.remove(pos);
            Building host = world.build(pos);
            if(pb != null && host != null && host.proximity != null) host.proximity.remove((Building) pb);
        }
    }

    /**
     * 把一组建筑身上挂的热探针全部摘掉（取消"热量"共享时调用，见 updateHeatNode/updateHeatConnector）。
     * 只摘"本网络喂过"的（lastFed 在近两 tick 内）——并存的另一张网络可能也在喂其中某台，
     * 那种探针不归我们管（distributeHeat 正常走时它们会被续期/摘除）。
     */
    private static void clearHeatProbes(Seq<Building> members){
        if(members == null) return;
        boolean clearAlloc = heatAlloc.size > 0;
        for(int i = 0; i < members.size; i++){
            Building m = members.get(i);
            if(m == null || !m.isValid()) continue;
            // 网络分配额一并清：heatFor 的过期判断有 30 tick 宽限，不清的话
            // 取消共享后 afflict 还能从 heatAlloc 里捡到最多半秒的余热
            if(clearAlloc){
                Building l = ComboReflect.leader(m);
                if(l != null) heatAlloc.remove(l.pos());
            }
            combine.util.ComboHeatProbe.ComboHeatProbeBuild pb = heatProbes.get(m.pos());
            if(pb == null) continue;
            if(state != null && state.updateId - pb.lastFed > 1) continue;   // 别的网络在喂，别拆
            heatProbes.remove(m.pos());
            if(m.proximity != null) m.proximity.remove((Building) pb);
        }
    }

    /** 每帧一次的兜底清扫：任何网络都不再喂的探针（拆节点/节点停用/断线残留），超过 30 tick 摘除。 */
    private static void sweepAbandonedHeatProbes(){
        if(heatProbes.size == 0 || state == null) return;
        arc.struct.IntSeq stale = new arc.struct.IntSeq();
        for(var it = heatProbes.iterator(); it.hasNext();){
            var e = it.next();
            if(state.updateId - e.value.lastFed > heatAllocExpireTicks) stale.add(e.key);
        }
        for(int i = 0; i < stale.size; i++){
            int pos = stale.get(i);
            combine.util.ComboHeatProbe.ComboHeatProbeBuild pb = heatProbes.remove(pos);
            Building host = world.build(pos);
            if(pb != null && host != null && host.proximity != null) host.proximity.remove((Building) pb);
        }
    }

    private static boolean probeNullWarned = false;
    private static void warnProbeNullOnce(){
        if(probeNullWarned) return;
        probeNullWarned = true;
        Log.warn("[combine] ComboHeatProbe 未注册：远程需热建筑（如 afflict）无法通过网络获得热量，请确认加载时调用了 ComboHeatProbe.create()");
    }

    /** 给一台需热建筑插/更新探针。 */
    private static boolean attachHeatProbe(Building host, float heat){
        try{
            combine.util.ComboHeatProbe.ComboHeatProbeBuild pb = heatProbes.get(host.pos());
            if(pb == null){
                Building raw = combine.util.ComboHeatProbe.probe.newBuilding();
                if(!(raw instanceof combine.util.ComboHeatProbe.ComboHeatProbeBuild build)) return false;
                build.create(combine.util.ComboHeatProbe.probe, host.team);
                Tile fake = new Tile(host.tileX(), host.tileY());
                setTileBlock(fake, combine.util.ComboHeatProbe.probe);
                build.tile = fake;
                build.set(host.x, host.y);
                build.proximity = new Seq<>();
                build.team = host.team;
                fake.build = build; // isValid() 靠这一条（它不在世界网格里）
                pb = build;
                heatProbes.put(host.pos(), pb);
            }
            pb.amount = Math.max(0f, heat);
            pb.lastFed = state.updateId;   // 喂一次续一期，ComboNet 据此识别"没人喂的探针"
            pb.team = host.team;
            pb.set(host.x, host.y);
            if(host.proximity == null) host.proximity = new Seq<>();
            if(!host.proximity.contains(pb, true)) host.proximity.add(pb);
            return true;
        }catch(Throwable t){
            // 以前这里静默返回 false：探针挂不上时远程热量无声无息地断掉，
            // 玩家只看到" afflict 收不到热"却没有任何线索 —— 每台建筑只报一次
            if(host != null && attachFailWarned.add(host.pos())){
                Log.err("[combine] 给 " + host.block.name + "（" + host.tileX() + "," + host.tileY() + "）挂热探针失败，该建筑的远程组合热量将无法送达", t);
            }
            return false;
        }
    }

    private static final ObjectSet<Integer> attachFailWarned = new ObjectSet<>();

    /** 需热建筑：实现了原版 HeatConsumer，或 block 上写了正的 heatRequirement。 */
    private static boolean isHeatConsumer(Building b){
        if(b == null || b.block == null) return false;
        if(b instanceof mindustry.world.blocks.heat.HeatConsumer) return true;
        Float req = ComboReflect.getFloat(b.block, "heatRequirement");
        return req != null && req > 0f;
    }

    /**
     * 顺着连接件的连线/邻接收集"和热有关、但不属于组合池"的建筑。
     *
     * <p>用户报："afflict 和 slag-heater 用组合节点连接和没组合一样" —— 那些方块没被本模组
     * 替换（mod 自带的制热机 / 需热炮台），既不在组合白名单里、也没有组合分组。它们不该参与
     * 物品/液体并池（所以不进 componentMembers），但热量必须经节点通算，这里单独收出来。
     */
    private static Seq<Building> collectLinkedHeatBuildings(Seq<Building> linkers){
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        Queue<Building> q = new Queue<>();
        for(int i = 0; i < linkers.size; i++) q.addLast(linkers.get(i));
        int guard = 0;
        while(!q.isEmpty() && guard++ < 512){
            Building cur = q.removeFirst();
            Seq<Building> nbs = new Seq<>();
            if(cur instanceof ComboNode.ComboNodeBuild n){
                for(int i = 0; i < n.links.size; i++){
                    Building l = world.build(n.links.get(i));
                    if(l != null && l.isValid()) nbs.add(l);
                }
            }
            if(cur.proximity != null)
                for(Building nb : cur.proximity)
                    if(nb != null && nb.isValid()) nbs.add(nb);
            for(int i = 0; i < nbs.size; i++){
                Building nb = nbs.get(i);
                // 组合体/连接件本身走原来那套（池子+热量都在那边算），不在这里重复
                if(isLinker(nb) || ComboReflect.isComboBuild(nb)) continue;
                // 网络自己插的热探针不算"网络里的热建筑"：它是我们写进去的整网热量，顺着它接力=自馈。
                if(nb instanceof combine.util.ComboHeatProbe.ComboHeatProbeBuild) continue;
                if(!ComboReflect.isHeatBuild(nb)) continue;
                if(seen.add(nb)){
                    out.add(nb);
                    q.addLast(nb); // 热建筑之间还能继续接力（导热管串等）
                }
            }
        }
        return out;
    }

    /**
     * 这台建筑这一帧对外供的热（只有"源头型"产热方块才有；需热方/导热管一律 0）。
     *
     * <p>【必须排除 HeatConsumer，否则自馈到 Float.MAX_VALUE】导热管/热路由器
     * （{@code HeatConductor}）同时是 HeatBlock **和** HeatConsumer：它的 heat() 往往是
     * 从贴着它的组合节点/连接器读过去的（见 {@link #setLinkerHeat} 把网络总热量写进连接件）。
     * 如果这里再把它当"外来热源"加进总量：node.heat → 导热管.heat → 又算回总量 → node.heat 翻倍，
     * 每帧 ×2，几十帧就冲到 Float.MAX_VALUE（用户报的"节点连制热机+热量传输装置，热量输出变成
     * 浮点数最大值"）。产热源头不会再是从我们网络读热的下游，所以只认非 HeatConsumer 的 HeatBlock。
     */
    private static float heatSourceOf(Building b){
        try{
            if(b instanceof combine.util.ComboHeatProbe.ComboHeatProbeBuild) return 0f;
            if(b instanceof HeatBlock hb && !(b instanceof HeatConsumer)) return Math.max(0f, hb.heat());
        }catch(Throwable ignored){
        }
        return 0f;
    }

    /** 这台建筑有没有贴着连接件（贴着的走原版相邻路径，不需要探针）。 */
    private static boolean touchesLinker(Building b, Seq<Building> linkers){
        if(b.proximity == null) return false;
        for(int i = 0; i < b.proximity.size; i++){
            Building nb = b.proximity.get(i);
            if(nb == null) continue;
            for(int k = 0; k < linkers.size; k++)
                if(nb == linkers.get(k)) return true;
        }
        return false;
    }

    /** 这台建筑旁边有没有别的热方块（探针不算）——有的话原版 calculateHeat 会顺邻居把热传过来。 */
    private static boolean hasHeatNeighbor(Building b){
        if(b.proximity == null) return false;
        for(int i = 0; i < b.proximity.size; i++){
            Building nb = b.proximity.get(i);
            if(nb == null || !nb.isValid() || nb == b) continue;
            if(nb instanceof combine.util.ComboHeatProbe.ComboHeatProbeBuild) continue;
            if(nb instanceof HeatBlock) return true;
        }
        return false;
    }

    /** 让假 Tile 的 block() 也是这个方块（Tile.block 是 protected，反射设一次）。 */
    static void setTileBlock(Tile tile, Block block){
        try{
            java.lang.reflect.Field f = Tile.class.getDeclaredField("block");
            f.setAccessible(true);
            f.set(tile, block);
        }catch(Throwable ignored){
        }
    }

    /**
     * 贴着连接件、但**没有**贴着组内成员的外来热源 —— 让"热量从网络外面经节点/连接器进来"
     * 也成立（导热管把别处的热引到节点上，组内需热端就能用）。
     *
     * <p>贴着成员的（成员自己那套本来就看得见）和连接件本身（各自的分量会算）都不重复计入，
     * 否则"A 组的热经导热管兜一圈又算回 A 组"会自馈翻倍。
     */
    private static float linkerExternalHeat(Seq<Building> linkers, ObjectSet<Building> memberSet){
        float sum = 0f;
        for(int i = 0; i < linkers.size; i++){
            Building l = linkers.get(i);
            if(l == null || l.proximity == null) continue;
            for(Building nb : l.proximity){
                if(nb == null || !nb.isValid() || nb.team != l.team) continue;
                if(!(nb instanceof HeatBlock hb)) continue;
                if(memberSet.contains(nb) || isLinker(nb)) continue;
                // 网络自己插的"热探针"绝不能算外来热源：它身上就是整张网络的热量，算回来就是自馈。
                if(nb instanceof combine.util.ComboHeatProbe.ComboHeatProbeBuild) continue;
                // 【只收"源头型"邻居】导热管/热熔炉这类 HeatConsumer 的 heat 很可能就是从
                // 本网络（这个节点/连接器）读过去的 —— 再算成输入会自馈，几帧就涨到几百。
                if(nb instanceof mindustry.world.blocks.heat.HeatConsumer) continue;
                if(touchesMembers(nb, memberSet)) continue;
                sum += Math.max(0f, hb.heat());
            }
        }
        return sum;
    }

    private static boolean touchesMembers(Building b, ObjectSet<Building> memberSet){
        if(b.proximity == null) return false;
        for(Building nb : b.proximity) if(nb != null && memberSet.contains(nb)) return true;
        return false;
    }

    /**
     * 这条节点是不是自己所在"节点簇"的热量负责人。
     * 节点簇 = 经节点互连连线、贴邻、或同贴一个连接器而连在一起的全部节点 —
     * 簇内 pos 最小的节点负责给整个网络做热量分配。
     */
    private static boolean isHeatAuthority(ComboNode.ComboNodeBuild node){
        ObjectSet<Building> seen = new ObjectSet<>();
        Queue<Building> q = new Queue<>();
        q.addLast(node);
        seen.add(node);
        while(!q.isEmpty()){
            Building v = q.removeFirst();
            Seq<Building> nbs = new Seq<>();
            if(v instanceof ComboConnector.ComboConnectorBuild c){
                if(c.proximity != null) for(Building nb : c.proximity) nbs.add(nb);
            }else if(v instanceof ComboNode.ComboNodeBuild n){
                for(int i = 0; i < n.links.size; i++){
                    Building l = world.build(n.links.get(i));
                    if(l != null && l.isValid()) nbs.add(l);
                }
                if(n.proximity != null) for(Building nb : n.proximity) nbs.add(nb);
            }
            for(int i = 0; i < nbs.size; i++){
                Building nb = nbs.get(i);
                if(nb instanceof ComboNode.ComboNodeBuild nn && seen.add(nn)) q.addLast(nn);
            }
        }
        for(Building b : seen){
            if(b instanceof ComboNode.ComboNodeBuild nb && nb != node && nb.pos() < node.pos()) return false;
        }
        return true;
    }

    private static Seq<Building> linkedHeatGroupsFor(Building hub){
        // 直接走网络分量：节点互连/连接器接力后链上所有组都是同一个"组合网络"，
        // 产热/需热要和物品/液体池按同一套连通性通算。
        Seq<Building> out = new Seq<>();
        ObjectSet<Building> seen = new ObjectSet<>();
        for(Building m : componentMembers(hub)){
            if(m == null || !m.isValid() || m instanceof ComboNode.ComboNodeBuild) continue;
            if(!ComboReflect.isComboBuild(m)) continue;
            Building leader = ComboReflect.leader(m);
            if(leader != null && seen.add(leader)) out.add(leader);
        }
        return out;
    }

    /**
     * 一个组合体对外能提供的热量 = **整组成员**各自 heat() 之和。
     *
     * 以前这里是"取第一个成员（HeatBlock）的 heat()"——那时组合产热机的 heat() 报的是整组总量，
     * 所以取一台就够。现在产热机的 heat() 改回原版语义（只报自己那一份，避免相邻多台被重复计入），
     * 汇总就得在这里做：整组求和。
     */
    private static float groupHeatSource(Building leader){
        float sum = 0f;
        for(Building m : ComboReflect.group(leader)){
            if(m == null || !m.isValid() || m instanceof ComboNode.ComboNodeBuild) continue;
            // 必须和 linkerExternalHeat 使用同一口径：导热管/需热端也可能实现 HeatBlock，
            // 它们的 heat() 有一部分正是从本网络读到的；直接相加会把网络热量重新当产热源。
            sum += heatSourceOf(m);
        }
        return sum;
    }

    private static float groupHeatDemand(Building leader){
        float demand = 0f;
        for(Building m : ComboReflect.group(leader)){
            if(!m.isValid()) continue;
            // 合体炮台（merge 模组）：需求 = 里面每一台需热炮台之和（不是方块上那个"单台"标记值），
            // 否则网络按需分配热量时会把整台合体炮台当成只要一份热。跨模组走反射调它的 heatDemand()。
            if(isSuperTurret(m)){
                Object v = ComboReflect.call(m, "heatDemand");
                if(v instanceof Number n) demand += Math.max(0f, n.floatValue());
                continue;
            }
            Float req = ComboReflect.getFloat(m.block, "heatRequirement");
            if(req != null && req > 0f) demand += req;
        }
        return demand;
    }

    private static void deductStoredHeat(Building leader, float amount){
        Building stored = null;
        for(Building m : ComboReflect.group(leader)){
            if(ComboReflect.isStoredHeatBuild(m)){
                stored = m;
                break;
            }
        }
        if(stored == null && ComboReflect.isStoredHeatBuild(leader)) stored = leader;
        if(stored == null) return;
        float cur = ComboReflect.getStoredHeat(stored);
        ComboReflect.setStoredHeat(stored, Math.max(0f, cur - amount));
    }

    /**
     * 每帧最多一次的陈旧分配清理。只在网络写入方（节点/连接器的 updateTile）调用 ——
     * 读取方（heatFor）绝不清空，否则"需热建筑比节点先 update"时读到的永远是 0。
     */
    private static void beginHeatFrame(){
        if(state != null && heatFrame != state.updateId){
            heatFrame = state.updateId;
            arc.struct.IntSeq staleKeys = new arc.struct.IntSeq();
            for(var it = heatAlloc.iterator(); it.hasNext();){
                var en = it.next();
                if(state.updateId - en.value.frame > heatAllocExpireTicks) staleKeys.add(en.key);
            }
            for(int i = 0; i < staleKeys.size; i++) heatAlloc.remove(staleKeys.get(i));
        }
    }
}
