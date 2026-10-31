package combine.util;
import combine.coop.CoopCombo;
import combine.net.ComboNet;
import combine.production.CombinedGenerator.CombinedGeneratorBuild;
import combine.production.CombinedGenerator;
import combine.storage.CombinedStorageBlock;
import arc.struct.Seq;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import combine.net.ComboConnector.ComboConnectorBuild;
import combine.net.ComboNode.ComboNodeBuild;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.world;

/**
 * 反射访问所有组合建筑共有的组合字段。
 *
 * 新建筑(连接器/节点)不属于任何 Combined* 类，但它们要和已放置的老组合建筑
 * 互通，因此这里不依赖具体内部类，只依赖各组合建筑已经存在的约定字段：
 * comboLeader / comboGroup / comboDirty / comboTotalItemCap / comboTotalLiquidCap，
 * 以及同名的约定方法（leader() / group() / rebuildCombo() / comboPreUpdate()）。
 *
 * 【这里刻意不认任何具体接口】组合单位工厂/重组厂/构造机的组合簿记是
 * {@code combine.units.IUnitCombo} 的 default 方法，而发射台/着陆台是各自类里写的方法 ——
 * 两边字段名与方法名是同一套约定，走"按名字找"这条路就够了；
 * 认接口反而会把这个通用的反射桥跟某一个接口绑死（见 {@link #findMethod} 对接口 default
 * 方法的支持：getDeclaredMethod 在实现类上是找不到 default 方法的）。
 */
public class ComboReflect {

  /**
   * 「这栋建筑是不是组合建筑」里**只跟类有关**的那部分判定结果缓存。
   *
   * 【性能】rebuild 里"每栋建筑 × 每趟循环"都要问一次 isComboBuild（超大基地 1 万+ 栋、
   * 每次放置还要重来一遍），原来每次都走两三张 ConcurrentHashMap（hasField）——
   * 现在按建筑类缓存成一个布尔，热路径只剩一次查表。
   *
   * 注意**不缓存** {@code CoopCombo.eligible(...)}：那条判定依赖能在设置界面里实时改的
   * 黑名单（它自己也有缓存，名单变了会 clear），不能在这里固化。
   */
  private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean> isComboBuildCache =
      new java.util.concurrent.ConcurrentHashMap<>();
  /**
   * 已确认处于单台状态的建筑。大型进攻图有上万台敌方组合建筑，非玩家队伍每帧都会走
   * markSolo；缓存后不再重复做反射字段读取。开关或换图时统一清空。
   */
  private static final ObjectSet<Building> knownSolo = new ObjectSet<>();

  public static boolean isComboBuild(Building b){
        if(b == null) return false;
        Boolean cached = isComboBuildCache.get(b.getClass());
        if(cached != null){
            if(cached) return true;
        }else{
            // 结构性的三条：组合仓库/容器；自己维护分组的功能方块（组合墙等，IComboGrouped）；
            // 以及各 Combined* 方块自己写的 comboGroup/comboLeader 字段约定。
            boolean r = b.block instanceof CombinedStorageBlock
                || b instanceof IComboGrouped
                || hasField(b, "comboGroup") || hasField(b, "comboLeader");
            isComboBuildCache.put(b.getClass(), r);
            if(r) return true;
        }
        // 协作组合（继承原版类、自己写了新功能的 js/java 方块）也当成组合方块：
        // 这样 ComboNet 的池子合并/拆分、连接器/节点、信息面板都能一视同仁。
        // 这条依赖运行时可改的黑名单，不进上面的缓存。
        return CoopCombo.eligible(b.block);
    }

    /**
     * 和"热量"有关的建筑：产热 / 导热 / 需热的，或者方块上写着 heatRequirement / heatOutput 的
     * （很多 mod 的炮台就是这种"字段型"需热方）。
     *
     * <p>用来让组合节点/连接器能连上**没被本模组替换过**的热相关方块 —— 用户报的
     * "afflict 和 slag-heater 用组合节点连接和没组合一样"就是这么来的：那些方块不在
     * 组合白名单里（是炮台 / 只传热不存物品液体），节点连线直接把它们拒了。
     */
    public static boolean isHeatBuild(Building b){
        if(b == null || b.block == null) return false;
        try{
            // HeatBlock / HeatConsumer 是 build 层接口，HeatConductor / HeatProducer 是方块类型
            if(b instanceof mindustry.world.blocks.heat.HeatBlock
                || b instanceof mindustry.world.blocks.heat.HeatConsumer
                || b.block instanceof mindustry.world.blocks.heat.HeatConductor
                || b.block instanceof mindustry.world.blocks.heat.HeatProducer) return true;
            Float req = getFloat(b.block, "heatRequirement");
            if(req != null && req > 0f) return true;
            Float out = getFloat(b.block, "heatOutput");
            if(out != null && out > 0f) return true;
            Float prod = getFloat(b, "producerHeat");
            if(prod != null && prod > 0f) return true;
        }catch(Throwable ignored){
        }
        return false;
    }

  /**
   * 让"自己维护分组"的组合建筑（组合墙等）重算分组 —— 组合节点/连接器的连线变了就要叫一次，
     * 否则玩家连上了线、墙却还是两组（血池不共享）。
     */
    public static void markGroupDirty(Building b){
        if(b instanceof IComboGrouped g){
            try{
                g.markGroupDirty();
            }catch(Throwable ignored){
            }
            return;
        }
        // 普通 Combined* 组合建筑：把当前这一组全部标脏，updateTile 里各自 rebuildCombo，
        // 重建时 linkedReachable() 会穿过组合节点/连接器 —— 连线变了分组跟着变
        //（节点连上/断开都要叫这里，见 ComboNode 的配置与移除逻辑）。
        if(isComboBuild(b) && !(b.block instanceof CombinedStorageBlock)
            && hasField(b, "comboDirty")){
            for(Building m : group(b)){
                if(m.isValid()) setDirty(m, true);
            }
        }
    }

    /**
     * 从 start 出发做 BFS：沿 proximity 相邻成员 + 组合连接件（组合节点/组合连接器）
     * 收集所有同队的组合成员（含 start）。
     *
     * memberTest：候选建筑是否属于本组合家族（调用方写 instanceof + 同队 + isValid）。
     * edgeAllowed：current（已入组）与 candidate 之间是否允许组合（同型/跨型开关），
     * 穿过连接件时以"从哪个成员踏上连接件"作为 current —— 即连接件两端等价于相邻。
     *
     * 这是 LinkWall.walkLinkers 的通用版：墙自己早就这么走（生命共享等整组功能
     * 跟着连线走），这里把同一语义带给所有 Combined* 建筑 —— 用户要的
     * "用组合节点连上 = 效果相当于直接组合"。
     */
    public static Seq<Building> linkedReachable(Building start,
                                                java.util.function.Predicate<Building> memberTest,
                                                java.util.function.BiPredicate<Building, Building> edgeAllowed){
        // 【只和玩家队友组合】总开关打开时，AI 敌人队伍的建筑不参与分组
        //（所有 Combined* 方块的本地分组都从这里走，一处闸门全覆盖）
        java.util.function.Predicate<Building> innerTest = memberTest;
        memberTest = b -> innerTest.test(b) && ComboTeams.playerTeam(b.team);
        Seq<Building> found = new Seq<>();
        if(start == null || start.dead()) return found;
        ObjectSet<Building> visited = new ObjectSet<>();
        Queue<Building> queue = new Queue<>();
        ObjectSet<Building> seenLinkers = new ObjectSet<>();
        queue.addLast(start);
        visited.add(start);
        // 【性能】节点名单只快照一次：以前这里在 BFS 的**每个节点**上都要
        // `CoopCombo.trackedNodesCopy()`（新建一个 Seq + 拷一遍全部节点），
        // 组一大就是 O(组成员 × 节点数)，每次放置都能卡一下。
        Seq<Building> nodeSnapshot = CoopCombo.trackedNodesCopy();
        // 跨距离的组合节点：links 里直接写了 start 的节点也是跳板（节点不在邻格里，发现不了"有人连我"）
        try{
            for(Building nb : nodeSnapshot){
                if(nb instanceof ComboNodeBuild node && node.links != null && node.links.contains(start.pos()))
                    walkLinkers(node, memberTest, edgeAllowed, seenLinkers, visited, queue, start);
            }
        }catch(Throwable ignored){
        }
        while(!queue.isEmpty()){
            Building cur = queue.removeFirst();
            found.add(cur);
            if(cur.proximity != null){
                for(Building nb : cur.proximity){
                    if(nb == null || !nb.isValid()) continue;
                    if(isLinker(nb)){
                        // 相邻的连接件：穿过它，把它够到的成员也并进来
                        walkLinkers(nb, memberTest, edgeAllowed, seenLinkers, visited, queue, cur);
                    }else if(memberTest.test(nb) && edgeAllowed.test(cur, nb) && visited.add(nb)){
                        // 相邻的组合成员：直接入队（原来的邻接 BFS 语义）
                        queue.addLast(nb);
                    }
                }
            }
            try{
                for(Building nb : nodeSnapshot){
                    if(nb instanceof ComboNodeBuild node && node.links != null && node.links.contains(cur.pos()))
                        walkLinkers(node, memberTest, edgeAllowed, seenLinkers, visited, queue, cur);
                }
            }catch(Throwable ignored){
            }
        }
        return found;
    }

    /** 穿连接件：连接器靠 proximity 接下一棒，节点靠 links 接下一棒；沿途碰到的成员按 edgeAllowed 判定后入队。 */
    private static void walkLinkers(Building start,
                                    java.util.function.Predicate<Building> memberTest,
                                    java.util.function.BiPredicate<Building, Building> edgeAllowed,
                                    ObjectSet<Building> seenLinkers, ObjectSet<Building> visited,
                                    Queue<Building> queue, Building entry){
        if(!seenLinkers.add(start)) return;
        // 【共享配置里有一项没勾就不穿这里】两端不再被当成"一台大机器"，
        // 剩下的部分交给 ComboNet 在网络层逐项共享（见 combine.net.ComboShare）。
        if(!combine.net.ComboShare.allowLocalJoin(start)) return;
        Queue<Building> linkers = new Queue<>();
        linkers.addLast(start);
        while(!linkers.isEmpty()){
            Building cur = linkers.removeFirst();
            // 1) 这个连接件旁边贴着的成员
            if(cur.proximity != null){
                for(Building nb : cur.proximity){
                    if(nb == null || !nb.isValid()) continue;
                    if(isLinker(nb)){
                        if(seenLinkers.add(nb) && combine.net.ComboShare.allowLocalJoin(nb)) linkers.addLast(nb);
                    }else if(memberTest.test(nb) && edgeAllowed.test(entry, nb) && visited.add(nb)){
                        queue.addLast(nb);
                    }
                }
            }
            // 2) 顺着连接件之间的连线继续走
            if(cur instanceof ComboConnectorBuild c){
                for(Building nb : c.proximity){
                    if(nb != null && nb.isValid() && isLinker(nb) && seenLinkers.add(nb)
                        && combine.net.ComboShare.allowLocalJoin(nb))
                        linkers.addLast(nb);
                }
            }else if(cur instanceof ComboNodeBuild n){
                for(int i = 0; i < n.links.size; i++){
                    Building nb = world.build(n.links.get(i));
                    if(nb == null || !nb.isValid()) continue;
                    if(isLinker(nb)){
                        if(seenLinkers.add(nb) && combine.net.ComboShare.allowLocalJoin(nb)) linkers.addLast(nb);
                    }else if(memberTest.test(nb) && edgeAllowed.test(entry, nb) && visited.add(nb)){
                        queue.addLast(nb);
                    }
                }
            }
        }
    }

    private static boolean isLinker(Building b){
        return b instanceof ComboConnectorBuild || b instanceof ComboNodeBuild;
    }

    /**
     * 这台机器还属于**当前世界**吗？
     *
     * 读档会重建整个世界：旧世界留下的 Building 对象 {@code isValid()} 仍然是 true
     * （Mindustry 不逐个调用 remove()），只有 {@code world.tile(x,y).build} 才能分辨出
     * "这块地上的建筑已经换成新对象了"。各处的登记表/网络索引都必须用它过滤，
     * 否则读档后新旧两批机器会被当成两组、容量直接翻倍（倾倒站那种）。
     */
    public static boolean inWorld(Building b){
        if(b == null || !b.isValid()) return false;
        if(b.tile == null) return false;
        Tile t = world == null ? null : world.tile(b.tileX(), b.tileY());
        return t != null && t.build == b;
    }

    public static Building leader(Building b){
        if(b == null) return null;
        if(CoopCombo.eligible(b.block)) return CoopCombo.coopLeader(b);
        // 组合单位工厂/重组厂/构造机：IUnitCombo 的 default leader()
        Object r = call(b, "leader");
        return r instanceof Building bl ? bl : b;
    }

    public static Seq<Building> group(Building b){
        Seq<Building> out = new Seq<>();
        if(b == null) return out;

        // 注意用 add 而不是 addUnique：各组合类自己的 group() 已经是去重的集合，
        // 这里只是过滤 isValid。addUnique 在 Seq 上是线性查找，对 N 台一组的组合体
        // 就是 O(N²) —— 信息面板每帧都要调这个，几百台一组时会明显掉帧。
        if(CoopCombo.eligible(b.block)) return CoopCombo.coopGroup(b);
        Object r = call(b, "group");
        if(r instanceof Seq<?> seq){
            for(Object o : seq){
                if(o instanceof Building bl && bl.isValid()) out.add(bl);
            }
        }
        if(out.isEmpty()) out.add(b);
        return out;
    }

    /** 用于容量计算的本机容量，绝不要读 comboTotal*（跨组合并后会变成全局值）。 */
    public static int baseItemCap(Building b){
        if(b == null || b.items == null || b.block == null) return 0;
        // 协作组合的方块容量是"放大后"的（组容量），统计组容量时必须用放大前的基础值
        Integer coopBase = CoopCombo.coopBaseItemCap(b);
        if(coopBase != null) return coopBase;
        return b.block.itemCapacity;
    }

    /**
     * 组合池里的液体总量。
     *
     * LiquidModule.currentAmount() 只是"最近被添加/移除的那种液体"的量，
     * 池子里混了两种以上液体时它会来回跳（谁最后被动过就是谁），
     * 用它做容量判断会出现"有时超容、有时没满就拒收"的随机现象。
     */
    public static float liquidTotal(LiquidModule module){
        return module == null ? 0f : module.sum((liquid, amount) -> amount);
    }

    /** 用于容量计算的本机液体容量。 */
    public static float baseLiquidCap(Building b){
        if(b == null || b.liquids == null || b.block == null) return 0f;
        Float coopBase = CoopCombo.coopBaseLiquidCap(b);
        if(coopBase != null) return coopBase;
        Float base = getFloat(b.block, "baseLiquidCapacity");
        if(base != null && base > 0f) return base;
        return Math.max(b.block.liquidCapacity, 0f);
    }

    public static int groupBaseItemCap(Building leader){
        int total = 0;
        for(Building m : group(leader)){
            if(m.isValid()) total += baseItemCap(m);
        }
        return total;
    }

    public static float groupBaseLiquidCap(Building leader){
        float total = 0f;
        for(Building m : group(leader)){
            if(m.isValid()) total += baseLiquidCap(m);
        }
        return total;
    }

    public static void setItemCap(Building b, int value){
        if(b == null) return;
        setInt(b, "comboTotalItemCap", value);
    }

    public static void setLiquidCap(Building b, float value){
        if(b == null) return;
        setFloat(b, "comboTotalLiquidCap", value);
        // 【用户报】"组合体/合体炮台的液体容量之和超过 9999 还是被限制在 9999"。
        // 组合建筑为了不让原版管道算出负流量，block.liquidCapacity 平时是 9999 假容量；
        // 可原版 transferLiquid / moveLiquid 是拿**目标方块的 block.liquidCapacity** 限流的
        // （flow = cap - 池内已有量），池子一到 9999 管道就再也送不进去 —— 真正的上限
        // （组容量，见 acceptLiquid/handleLiquid 的 comboTotalLiquidCap 判定）根本用不上。
        // 这里顺手把方块容量抬到"见过的最大组容量"（和 CoopCombo.applyCapacities 同一套口径：
        // 只松上限、永不回缩）。同一方块类型的多组共用这个字段，取最大值不会把任何一组的
        // 真实上限放宽 —— 每组该有的上限仍由 acceptLiquid 自己按 comboTotalLiquidCap 拦。
        if (b.block != null && value > b.block.liquidCapacity) b.block.liquidCapacity = value;
    }

    /**
     * 已经捕获过"基础液体容量"的方块。内容加载器可能对同一个方块再次 {@code init()}，
     * 那时 {@code block.liquidCapacity} 早已被改成 9999 假容量（甚至被 {@link #setLiquidCap}
     * 抬成了组容量）——再捕获一次就会把假容量/放大值记成"基础容量"，容量直接翻 N 倍。
     * 所以只认第一次。
     */
    private static final ObjectSet<Block> baseLiquidCapCaptured = new ObjectSet<>();

    /** 第一次 init 时返回 true（调用方据此把当前的 {@code liquidCapacity} 记为基础容量）。 */
    public static boolean captureBaseLiquidCapOnce(Block block){
        return block != null && baseLiquidCapCaptured.add(block);
    }

    public static void markClean(Building b){
        if(b == null) return;
        setBoolean(b, "comboDirty", false);
    }

    public static void setDirty(Building b, boolean value){
        if(b == null) return;
        if(value) knownSolo.remove(b);
        setBoolean(b, "comboDirty", value);
    }

    public static boolean isDirty(Building b){
        if(b == null) return false;
        Boolean v = getBoolean(b, "comboDirty");
        return v != null && v;
    }

    public static boolean hasPendingLeader(Building b){
        if(b == null) return false;
        Integer p = getInt(b, "pendingLeaderPos");
        return p != null && p != -1;
    }

    /**
     * 【关掉相邻自动组合时】把本机恢复成"单独一台"的组合状态。
     *
     * <p>为什么要有这个：各组合类重写的 {@code updateTile()} 开头都有一条
     * "不算组合 → {@code super.updateTile(); return;}". 那条**不能真的 return**：
     * <ul>
     * <li>组合钻头（{@code CombinedDrill}）的 super 是 {@code Building.updateTile()} —— 空实现，
     * 一 return 钻头就彻底停摆（用户报的"关掉组合后钻头类全部停摆"）；</li>
     * <li>组合发电机/单位工厂/炮塔的 super 也**不是**它们各自仿写的那个原版 build 类
     * （{@code CombinedGeneratorBuild extends ConsumeGeneratorBuild} 而不是 ImpactReactorBuild，
     * 于是冲击反应堆不预热、核反应堆不发热）。</li>
     * </ul>
     * 正确做法是让它们照常跑自己的逻辑 —— 那些逻辑在"组里只有自己"时就等价于原版单机行为
     * （容量/库存都是单台的，见各 {@code rebuildCombo()}）。本方法就是那个把状态拨回
     * "只有自己"的开关：清掉残留的 leader / 待恢复 leader，并置脏让下一次
     * {@code rebuildCombo()} 重新按"单台"算容量。
     *
     * <p>每帧都会被调用，所以**必须是廉价且幂等的**：已经是单台状态时立刻返回，
     * 不会每帧重建分组（否则大基地里就是每台建筑每帧一次重建 = 卡顿）。
     */
    public static void markSolo(Building b){
        if(b == null) return;
        try{
            if(knownSolo.contains(b)) return;
            // leader 还在 / 存档里还记着"该并进谁"：这才是"还处在组合态"，需要拨回来
            if(fieldValue(b, "comboLeader") == null && !hasPendingLeader(b)){
                knownSolo.add(b);
                return;
            }
            setField(b, "comboLeader", null);
            setInt(b, "pendingLeaderPos", -1);
            Seq<Object> self = new Seq<>();
            self.add(b);
            if(hasField(b, "comboGroup")) setField(b, "comboGroup", self);
            // 容量先按单台基础值给（rebuildCombo 之后也是这个值，这里只是别让本帧读到组容量）
            setItemCap(b, baseItemCap(b));
            setLiquidCap(b, baseLiquidCap(b));
            setDirty(b, true);
            knownSolo.add(b);
        }catch(Throwable ignored){
        }
    }

    /** 开关或换图后，"永远单台"的旧结论失效。 */
    public static void clearSoloCache(){
        knownSolo.clear();
    }

    /** 把任意字段设成 value（类型不符/字段不存在就静默跳过）。 */
    public static void setField(Object obj, String name, Object value){
        if(obj == null) return;
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.set(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static void rebuildLocal(Building b){
        if(b == null) return;
        call(b, "rebuildCombo");
    }

    public static boolean isStoredHeatBuild(Building b){
        return b != null && b.getClass().getName().equals("combine.production.CombinedGenerator$CombinedGeneratorBuild");
    }

    /** 读档后恢复共享模块的挂点：等价于各组合建筑 updateTile 开头的 comboPreUpdate()。 */
    public static void preUpdate(Building b){
        if(b == null) return;
        call(b, "comboPreUpdate");
    }

    public static float getStoredHeat(Building b){
        Object r = call(b, "getComboHeat");
        return r instanceof Number n ? n.floatValue() : 0f;
    }

    public static void setStoredHeat(Building b, float value){
        call(b, "setComboHeat", new Class<?>[]{float.class}, value);
    }

    public static boolean hasField(Object obj, String name){
        return findField(obj.getClass(), name) != null;
    }

    public static Integer getInt(Object obj, String name){
        Object v = fieldValue(obj, name);
        return v instanceof Number n ? n.intValue() : null;
    }

    public static Float getFloat(Object obj, String name){
        Object v = fieldValue(obj, name);
        return v instanceof Number n ? n.floatValue() : null;
    }

    public static void setInt(Object obj, String name, int value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setInt(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static void setFloat(Object obj, String name, float value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setFloat(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static void setBoolean(Object obj, String name, boolean value){
        Field f = findField(obj.getClass(), name);
        if(f == null) return;
        try{
            f.setBoolean(obj, value);
        }catch(Throwable ignored){
        }
    }

    public static Boolean getBoolean(Object obj, String name){
        Object v = fieldValue(obj, name);
        return v instanceof Boolean b ? b : null;
    }

    public static Object fieldValue(Object obj, String name){
        Field f = findField(obj.getClass(), name);
        if(f == null) return null;
        try{
            return f.get(obj);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Object call(Object obj, String name){
        Method m = findMethod(obj.getClass(), name);
        if(m == null) return null;
        try{
            return m.invoke(obj);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Object call(Object obj, String name, Class<?>[] types, Object value){
        Method m = findMethod(obj.getClass(), name, types);
        if(m == null) return null;
        try{
            return m.invoke(obj, value);
        }catch(Throwable ignored){
            return null;
        }
    }

    public static Field findField(Class<?> clazz, String name){
        // FIX[放置卡顿]: 字段查找必须缓存（按 Class 分层，避免每次拼字符串 key）。
        // isComboBuild() 走的是 hasField("comboGroup")，而 ComboNet.rebuild 里
        // "每栋建筑 × 每趟循环" 都要问一次；原实现每次都逐层 getDeclaredField，
        // 每层 miss 还会抛一次 NoSuchFieldException（构造异常很贵）——
        // 几百栋建筑时单趟 rebuild 就能吃掉好几毫秒，放在组合体旁边放连接器/节点
        // 那一下的疯狂掉帧就是这么来的。
        java.util.concurrent.ConcurrentHashMap<String, Field> perClass = fieldCache.get(clazz);
        if(perClass == null){
            perClass = new java.util.concurrent.ConcurrentHashMap<>();
            fieldCache.put(clazz, perClass);
        }
        Field cached = perClass.get(name);
        if(cached != null) return cached;
        java.util.Set<String> missing = missingFieldCache.get(clazz);
        if(missing != null && missing.contains(name)) return null;

        Class<?> c = clazz;
        while(c != null && c != Object.class){
            try{
                Field f = c.getDeclaredField(name);
                // 缓存时就 setAccessible：反射调用点每次 setAccessible 很贵，
                // 而 rebuild 里这些调用是"每建筑 × 每字段"量级的。
                try{ f.setAccessible(true); }catch(Throwable ignored){}
                perClass.put(name, f);
                return f;
            }catch(NoSuchFieldException ignored){
                c = c.getSuperclass();
            }
        }
        if(missing == null){
            missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
            missingFieldCache.put(clazz, missing);
        }
        missing.add(name);
        return null;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.concurrent.ConcurrentHashMap<String, Field>> fieldCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 记下"这个类确实没有这个字段"（ConcurrentHashMap 不允许 null 值，所以单独放）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Set<String>> missingFieldCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static Method findMethod(Class<?> clazz, String name, Class<?>... types){
        // 反射查找必须缓存：leader()/group()/preUpdate() 这些桥接方法在
        //   "每 tick × 每建筑 × 每条搬运请求" 的路径上被调用，
        // 每次都走一遍 getDeclaredMethod + setAccessible 会直接把帧数吃掉。
        StringBuilder key = new StringBuilder(name).append('(');
        if(types != null)
            for(Class<?> t : types) key.append(t.getName()).append(',');
        key.append(')');
        String k = key.toString();

        java.util.concurrent.ConcurrentHashMap<String, Method> perClass = methodCache.get(clazz);
        if(perClass == null){
            perClass = new java.util.concurrent.ConcurrentHashMap<>();
            methodCache.put(clazz, perClass);
        }
        Method cached = perClass.get(k);
        if(cached != null) return cached;
        // 注意：ConcurrentHashMap 不允许 null 值，所以"找不到"必须记在单独的集合里 ——
        // 之前把 null 直接 put 进去，一查询没有该方法类的类（例如组合仓库，
        // 它没有 leader()/group() 方法，靠下面的兜底返回自身）就 NPE 崩客户端。
        java.util.Set<String> missing = missingMethodCache.get(clazz);
        if(missing != null && missing.contains(k)) return null;

        Class<?> c = clazz;
        while(c != null && c != Object.class){
            try{
                Method m = types == null || types.length == 0
                    ? c.getDeclaredMethod(name)
                    : c.getDeclaredMethod(name, types);
                try{ m.setAccessible(true); }catch(Throwable ignored){}
                perClass.put(k, m);
                return m;
            }catch(NoSuchMethodException ignored){
                c = c.getSuperclass();
            }
        }
        // 【接口 default 方法也必须找得到】组合单位工厂/重组厂/构造机的 leader()/group()/
        // rebuildCombo()/comboPreUpdate() 全是 combine.units.IUnitCombo 的 default 方法 ——
        // 实现类自己**不声明**它们，getDeclaredMethod 顺着类往上走永远 miss，于是
        // preUpdate()/rebuildLocal()/leader() 会静默失效（读档并池、连线变分组全都不动了）。
        Method im = findInterfaceMethod(clazz, name, types);
        if(im != null){
            try{ im.setAccessible(true); }catch(Throwable ignored){}
            perClass.put(k, im);
            return im;
        }
        if(missing == null){
            missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
            missingMethodCache.put(clazz, missing);
        }
        missing.add(k);
        return null;
    }

    /**
     * 在 clazz 的**整条继承链**的接口里找方法（含接口的 default 方法与继承来的接口）。
     * 广度优先，命中即返回；找不到返回 null。
     */
    private static Method findInterfaceMethod(Class<?> clazz, String name, Class<?>[] types){
        ObjectSet<Class<?>> seen = new ObjectSet<>();
        Queue<Class<?>> queue = new Queue<>();
        for(Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()){
            for(Class<?> i : c.getInterfaces())
                if(seen.add(i)) queue.addLast(i);
        }
        while(!queue.isEmpty()){
            Class<?> i = queue.removeFirst();
            try{
                return types == null || types.length == 0
                    ? i.getDeclaredMethod(name)
                    : i.getDeclaredMethod(name, types);
            }catch(NoSuchMethodException ignored){}
            for(Class<?> s : i.getInterfaces())
                if(seen.add(s)) queue.addLast(s);
        }
        return null;
    }

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.concurrent.ConcurrentHashMap<String, Method>> methodCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    /** 记下"这个类确实没有这个方法"，避免每次重走父类查找。 */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, java.util.Set<String>> missingMethodCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    // -------------------- 组级"谁消耗什么"缓存 --------------------

    /**
     * 组级消耗表缓存（key = 组 leader 的 pos）。
     *
     * 每个组合建筑的 acceptItem/acceptLiquid 都要回答"组里有没有人吃这个物品/液体"，
     * 原先每次都现遍历整组 —— N 台一组的基地里，每条搬运请求都是 O(N)，
     * 物品一流动就变成每 tick O(N²)，直接卡成幻灯片。
     * 这里按 tick 缓存一次（同 tick 内组结构变化由 rebuild 后下一 tick 生效，
     * 消耗表滞后一帧无害），查询变成 O(1)。
     */
    private static long consumeTick = Long.MIN_VALUE;
    private static final arc.struct.IntMap<boolean[]> consumedItemCache = new arc.struct.IntMap<>();
    private static final arc.struct.IntMap<boolean[]> consumedLiquidCache = new arc.struct.IntMap<>();

    /** 组内是否有成员消耗该物品（含跨类型组合）。 */
    public static boolean groupConsumesItem(Building self, Item item){
        if(item == null) return false;
        boolean[] table = consumedTable(self, true);
        return table != null && item.id < table.length && table[item.id];
    }

    /** 组内是否有成员消耗该液体（含跨类型组合）。 */
    public static boolean groupConsumesLiquid(Building self, Liquid liquid){
        if(liquid == null) return false;
        boolean[] table = consumedTable(self, false);
        return table != null && liquid.id < table.length && table[liquid.id];
    }

    // -------------------- "这口池子是不是本组独有的" --------------------

    /**
     * 这份物品模块是不是**组外**的建筑也在用（核心库存、被组合节点接进来的别的组合体……）。
     *
     * 【为什么要有这个判断】各 Combined* 方块自己的"本地组合"在成员退组时会按容量
     * 从"本组的池子"里分一份给退出的成员。可组合节点接成一张网络之后，这口池子可能
     * 根本不属于本组 —— 典型就是**核心库存那一份**（并仓仓库 + 组合节点把工厂接进来，
     * ComboNet 会让整张网络共用核心的那份模块）。
     * 这时再"按容量分一份给退出的工厂"，就是从核心库存里直接搬东西到那台工厂手里：
     * 核心当场掉库存，等玩家把那台工厂拆掉，那份物品就跟着工厂的模块一起没了
     *（用户报的"把核心贴容器的组合和工厂用节点链接起来，拆工厂的时候核心里面的东西全没了"）。
     *
     * 所以本地拆分只在"池子完全属于本组"时才动手；池子被组外引用时一律不动它，
     * 交给 {@link ComboNet} 按网络统一分配（它认识核心池，会把退出成员换成空模块）。
     */
    public static boolean itemPoolSharedOutside(ItemModule pool, Iterable<? extends Building> group){
        if(pool == null) return false;
        ObjectSet<Building> mine = new ObjectSet<>();
        if(group != null)
            for(Building b : group)
                if(b != null) mine.add(b);
        for(Building b : ComboNet.allComboBuildings()){
            if(b.items == pool && !mine.contains(b)) return true;
        }
        return false;
    }

    /** 同上，液体版。 */
    public static boolean liquidPoolSharedOutside(LiquidModule pool, Iterable<? extends Building> group){
        if(pool == null) return false;
        ObjectSet<Building> mine = new ObjectSet<>();
        if(group != null)
            for(Building b : group)
                if(b != null) mine.add(b);
        for(Building b : ComboNet.allComboBuildings()){
            if(b.liquids == pool && !mine.contains(b)) return true;
        }
        return false;
    }

    /**
     * 组里"组外也在用"的那份物品模块（核心库存 / 网络共享池），没有就返回 null。
     *
     * 本地组合并池时必须围着它做：组长手里要是自己那份私有模块，就**换成它**再并，
     * 否则会把核心库存"复制"一份到组长的私有模块里（核心没动、工厂也多了一份同样的物品），
     * 之后网络层再把这多出来的一份并回核心 —— 库存凭空翻倍。
     */
    public static ItemModule sharedItemPool(Iterable<? extends Building> group){
        ObjectSet<ItemModule> all = itemPoolsSharedOutside(group);
        return all.isEmpty() ? null : all.first();
    }

    /** 同上，液体版。 */
    public static LiquidModule sharedLiquidPool(Iterable<? extends Building> group){
        ObjectSet<LiquidModule> all = liquidPoolsSharedOutside(group);
        return all.isEmpty() ? null : all.first();
    }

    /**
     * 组里所有"组外也在用"的物品模块（一次全图扫描算出来）。
     *
     * 组里的每个成员都要判一次"这份模块是不是别人的"，逐个调用 {@link #itemPoolSharedOutside}
     * 会是"成员数 × 全图扫描"；并池路径在放方块时会连着跑好几遍，这里一次算完。
     */
    public static ObjectSet<ItemModule> itemPoolsSharedOutside(Iterable<? extends Building> group){
        ObjectSet<ItemModule> out = new ObjectSet<>();
        if(group == null) return out;
        ObjectSet<Building> mine = new ObjectSet<>();
        ObjectSet<ItemModule> mods = new ObjectSet<>();
        for(Building b : group){
            if(b == null) continue;
            mine.add(b);
            if(b.items != null) mods.add(b.items);
        }
        if(mods.isEmpty()) return out;
        // 【性能】只有一台成员时"并池"根本无从谈起：调用方只会拿这份结果去挑"并到哪一份模块"、
        // 判断"某个非组长成员的模块能不能整体搬走" —— 单台组里两者都用不上，直接返回空即可。
        // 不早退的话这里要扫全图（ComboNet.allComboBuildings() 会为 1 万多台建筑建一份 Seq）：
        // 大型敌方基地里每台"没组合的机器"每次重算分组都要付这份钱（实测每台每次 3.35ms，
        // 一个 3 万建筑的进攻图每帧 100+ms，卡成幻灯片）。
        if(mine.size <= 1) return out;
        for(Building b : ComboNet.allComboBuildings()){
            if(b == null || mine.contains(b) || b.items == null) continue;
            if(mods.contains(b.items)) out.add(b.items);
        }
        return out;
    }

    /** 同上，液体版。 */
    public static ObjectSet<LiquidModule> liquidPoolsSharedOutside(Iterable<? extends Building> group){
        ObjectSet<LiquidModule> out = new ObjectSet<>();
        if(group == null) return out;
        ObjectSet<Building> mine = new ObjectSet<>();
        ObjectSet<LiquidModule> mods = new ObjectSet<>();
        for(Building b : group){
            if(b == null) continue;
            mine.add(b);
            if(b.liquids != null) mods.add(b.liquids);
        }
        if(mods.isEmpty()) return out;
        if(mine.size <= 1) return out;   // 单台组：同上，不用扫全图
        for(Building b : ComboNet.allComboBuildings()){
            if(b == null || mine.contains(b) || b.liquids == null) continue;
            if(mods.contains(b.liquids)) out.add(b.liquids);
        }
        return out;
    }

    private static boolean[] consumedTable(Building self, boolean items){
        if(self == null || state == null) return null;
        if(consumeTick != state.updateId){
            consumeTick = state.updateId;
            consumedItemCache.clear();
            consumedLiquidCache.clear();
            neededItemCache.clear();
        }
        Building leader = leader(self);
        if(leader == null) return null;
        arc.struct.IntMap<boolean[]> cache = items ? consumedItemCache : consumedLiquidCache;
        boolean[] table = cache.get(leader.pos());
        if(table != null) return table;

        table = new boolean[items ? content.items().size : content.liquids().size];
        for(Building m : group(leader)){
            if(m == null || !m.isValid() || m.block == null) continue;
            boolean[] filter = items ? m.block.itemFilter : m.block.liquidFilter;
            if(filter == null) continue;
            for(int i = 0; i < filter.length && i < table.length; i++)
                if(filter[i]) table[i] = true;
        }
        cache.put(leader.pos(), table);
        return table;
    }

    /**
     * 组内"需要"该物品：普通 filter 消耗 + 单位工厂/蓝图方块的动态配方需求。
     * （原版 consumesItem 认不出 ConsumeItemDynamic，单位工厂要看 plans 里的需求。）
     */
    public static boolean groupNeedsItem(Building self, Item item){
        if(item == null || self == null || state == null) return false;
        if(consumeTick != state.updateId){
            consumeTick = state.updateId;
            consumedItemCache.clear();
            consumedLiquidCache.clear();
            neededItemCache.clear();
        }
        Building leader = leader(self);
        if(leader == null) return false;
        boolean[] table = neededItemCache.get(leader.pos());
        if(table == null){
            table = new boolean[content.items().size];
            for(Building m : group(leader)){
                if(m == null || !m.isValid() || m.block == null) continue;
                boolean[] filter = m.block.itemFilter;
                if(filter != null)
                    for(int i = 0; i < filter.length && i < table.length; i++)
                        if(filter[i]) table[i] = true;
                // 单位工厂：配方需求是运行期动态的
                if(m instanceof mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild
                    && m.block instanceof mindustry.world.blocks.units.UnitFactory uf){
                    for(mindustry.world.blocks.units.UnitFactory.UnitPlan plan : uf.plans){
                        for(mindustry.type.ItemStack stack : plan.requirements){
                            if(stack.item != null && stack.item.id < table.length)
                                table[stack.item.id] = true;
                        }
                    }
                }
                // 蓝图方块：正在造什么就吃那台的建造需求
                if(m instanceof mindustry.world.blocks.payloads.Constructor.ConstructorBuild cb){
                    Block recipe = cb.recipe();
                    if(recipe != null && recipe.requirements != null){
                        for(mindustry.type.ItemStack stack : recipe.requirements){
                            if(stack.item != null && stack.item.id < table.length)
                                table[stack.item.id] = true;
                        }
                    }
                }
            }
            neededItemCache.put(leader.pos(), table);
        }
        return item.id < table.length && table[item.id];
    }

    private static final arc.struct.IntMap<boolean[]> neededItemCache = new arc.struct.IntMap<>();

    /**
     * 信息面板该列哪些物品：**块声明过的输入/输出**（{@code cachedItems}）+ **池子里实际有的**。
     *
     * 为什么必须带上"池子里实际有的"：很多方块的输入是**运行期动态**的（发电机的燃料是
     * {@code ConsumeItemFilter} 按可燃度过滤的任意物品、单位工厂按配方、蓝图方块按建造需求），
     * 这些都不在 {@code cachedItems} 里。结果就是"池子里明明堆满了燃料，点开组合建筑的信息面板
     * 却一行物品都不显示"——用户报的「观测组合建筑物品面板时（看着像）清空所有物资」。
     */
    public static arc.struct.Seq<mindustry.type.Item> displayItems(
            mindustry.world.modules.ItemModule mod, Iterable<mindustry.type.Item> declared){
        arc.struct.Seq<mindustry.type.Item> out = new arc.struct.Seq<>();
        if(declared != null)
            for(mindustry.type.Item it : declared)
                if(it != null && !out.contains(it)) out.add(it);
        if(mod != null)
            for(mindustry.type.Item it : content.items())
                if(mod.get(it) > 0 && !out.contains(it)) out.add(it);
        return out;
    }
}
