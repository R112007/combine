package combine.production;
import combine.net.ComboNet;
import combine.util.ComboUi;
import combine.util.ComboReflect;
import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.struct.IntSet;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.io.Writes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.ui.Bar;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.WallCrafter;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.LiquidModule;

import static mindustry.Vars.*;

/**
 * 组合墙切割机 —— 相邻同型自动组合，共享物品池（总容量 = Σ 单台容量）。
 * 每台切割自己面前的墙，产出进入共享池，由全体成员向外输出。
 */
public class CombinedWallCrafter extends WallCrafter {
    public boolean allowCrossTypeCombo = false;
    public float itemCapacityMultiplier = 1f;
    /** 单台液容（记在 init 第一次，之后 liquidCapacity 被抬成 9999 假容量）。 */
    public float baseLiquidCapacity = 10f;

    public Seq<Item> cachedItems = new Seq<>();

    public CombinedWallCrafter(String name) {
        super(name);
        hasItems = true;
        update = true;
        solid = true;
        sync = true;
    }

    @Override
    public void init() {
        super.init();
        hasItems = true;
        // 【组合液池】把 liquidCapacity 抬到 9999（和别的组合建筑一个口径），
        // 否则共享池一超过单台液容(10)，原版管道就判定"满了"彻底断流 —— 组里其它台永远没氢可用。
        if (combine.util.ComboReflect.captureBaseLiquidCapOnce(this))
            baseLiquidCapacity = liquidCapacity;
        liquidCapacity = 9999f;
        cachedItems.clear();
        conductivePower = true;
        if (output != null)
            cachedItems.add(output);
        if (itemConsumer instanceof mindustry.world.consumers.ConsumeItems ci) {
            for (var stack : ci.items)
                if (!cachedItems.contains(stack.item))
                    cachedItems.add(stack.item);
        }
    }

    @Override
    public boolean canPlaceOn(Tile tile, Team team, int rotation) {
        return efficiencyAt(tile.x, tile.y, rotation, null, null) > 0;
    }

    // 复制自 WallCrafter.getEfficiency（原版为包级私有，跨包不可见）
    float efficiencyAt(int tx, int ty, int rotation, arc.func.Cons<Tile> ctile, arc.func.Intc2 cpos) {
        float eff = 0f;
        int cornerX = tx - (size - 1) / 2, cornerY = ty - (size - 1) / 2, s = size;
        for (int i = 0; i < size; i++) {
            int rx = 0, ry = 0;
            switch (rotation) {
                case 0 -> {
                    rx = cornerX + s;
                    ry = cornerY + i;
                }
                case 1 -> {
                    rx = cornerX + i;
                    ry = cornerY + s;
                }
                case 2 -> {
                    rx = cornerX - 1;
                    ry = cornerY + i;
                }
                case 3 -> {
                    rx = cornerX + i;
                    ry = cornerY - 1;
                }
            }
            if (cpos != null)
                cpos.get(rx, ry);
            Tile other = world.tile(rx, ry);
            if (other != null && other.solid()) {
                float at = other.block().attributes.get(attribute);
                eff += at;
                if (at > 0 && ctile != null)
                    ctile.get(other);
            }
        }
        return eff;
    }

    public class CombinedWallCrafterBuild extends WallCrafterBuild implements combine.saves.ComboSaved {
        public CombinedWallCrafterBuild comboLeader;
        public Seq<CombinedWallCrafterBuild> comboGroup = new Seq<>();
        public boolean comboDirty = true;
        public float comboTotalLiquidCap = 0f;
        public int comboTotalItemCap = 0;
        public int pendingLeaderPos = -1;

        public CombinedWallCrafterBuild leader() {
            if (comboLeader != null && (!comboLeader.isValid() || comboLeader.tile == null)) {
                comboLeader = null;
                comboDirty = true; // FIX: 失联后允许重建组合
            }
            return comboLeader == null ? this : comboLeader;
        }

        public boolean isLeader() {
            return leader() == this;
        }

        public Seq<CombinedWallCrafterBuild> group() {
            CombinedWallCrafterBuild l = leader();
            if (l.comboGroup == null)
                l.comboGroup = new Seq<>();
            return l.comboGroup;
        }

        // -------------------- 组合重建 --------------------
        public void rebuildCombo() {
            Seq<CombinedWallCrafterBuild> oldGroup = comboGroup != null ? new Seq<>(comboGroup) : new Seq<>();
            comboGroup = new Seq<>();
            comboGroup.add(this);
            // 分组 BFS 穿过组合节点/连接器：被节点连上 = 效果相当于直接组合（同 LinkWall 语义）
            for (Building b : ComboReflect.linkedReachable(this,
                    o -> o instanceof CombinedWallCrafterBuild other && other.team == team && other.isValid(),
                    (cur, o) -> cur.block.getClass() == o.block.getClass()
                    || ((CombinedWallCrafter) cur.block).allowCrossTypeCombo
                    || ((CombinedWallCrafter) o.block).allowCrossTypeCombo)) {
                if (b != this)
                    comboGroup.add((CombinedWallCrafterBuild) b);
            }
            CombinedWallCrafterBuild newLeader = this;
            for (CombinedWallCrafterBuild b : comboGroup)
                if (b.isValid() && b.pos() < newLeader.pos())
                    newLeader = b;
            Seq<CombinedWallCrafterBuild> newGroup = new Seq<>(comboGroup);
            newLeader.comboGroup = newGroup;
            for (CombinedWallCrafterBuild b : newGroup) {
                if (b.isValid()) {
                    b.comboLeader = newLeader;
                    b.comboGroup = newGroup;
                    b.comboDirty = false;
                }
            }
            newLeader.comboLeader = null;

            int totalItemCap = 0;
            for (CombinedWallCrafterBuild b : newGroup)
                if (b.isValid())
                    totalItemCap += b.block.itemCapacity;
            float totalLiquidCap = 0f;
            for (CombinedWallCrafterBuild b : newGroup)
                if (b.isValid())
                    totalLiquidCap += b.liquidCapacityForCombo();
            for (CombinedWallCrafterBuild b : newGroup)
                if (b.isValid()) {
                    // 【不共享物品】面板上"物品"没勾 = 每台各留各的模块，容量也只算自己那份
                    b.comboTotalItemCap = combine.net.ComboShare.shares(newLeader, combine.net.ComboShare.ITEMS)
                            ? totalItemCap : Math.max(ComboReflect.baseItemCap(b), 1);
                    b.comboTotalLiquidCap = totalLiquidCap;
                }

            if (oldGroup.size > newGroup.size)
                splitAssets(oldGroup, newGroup);
            shareModules(newLeader);

            for (CombinedWallCrafterBuild old : oldGroup) {
                if (old != this && old.isValid() && !newGroup.contains(old)) {
                    old.comboLeader = null;
                    old.comboGroup = new Seq<>();
                    old.comboDirty = true;
                    old.comboTotalItemCap = 0;
                    old.comboTotalLiquidCap = 0f;
                }
            }
        }

        /** 单台液容（block.liquidCapacity 被抬成 9999 假容量，这里取 init 记下的基础值）。 */
        public float liquidCapacityForCombo() {
            return ((CombinedWallCrafter) block).baseLiquidCapacity;
        }

        public void splitAssets(Seq<CombinedWallCrafterBuild> oldGroup,
                Seq<CombinedWallCrafterBuild> newGroup) {
            // FIX[双重拆分]: 网络层(ComboNet)可能已把池子按组件拆过一轮、给部分成员发过新模块。
            // 那些份额先并回主池（新组长持有的模块），再由本地逻辑按容量统一重分，
            // 否则两轮拆分各分各的，总额对不上账（物资凭空丢/复制）。
            CombinedWallCrafterBuild newLeader = null;
            for (CombinedWallCrafterBuild b : newGroup)
                if (b.isValid() && (newLeader == null || b.pos() < newLeader.pos()))
                    newLeader = b;
            if (newLeader == null)
                newLeader = this;
            ItemModule poolItems = newLeader.items;
            // 【池子可能不只本组在用】核心库存 / 组合节点接过来的别的组合体也在引用它时，
            // 本地拆分一律不动这口池子（退出成员拿空模块，由 ComboNet 按网络重新分配）。
            // 否则就是从核心库存里搬东西给退出的工厂 —— 用户报的"拆工厂时核心里的东西全没了"。
            // 【不共享物品】没有"公共池"：谁也别并进来、退出成员也别被换成空模块（否则等于丢物品）
            boolean localShareItems = combine.net.ComboShare.shares(newLeader, combine.net.ComboShare.ITEMS);
            boolean poolOurs = localShareItems && !ComboReflect.itemPoolSharedOutside(poolItems, oldGroup);
            for (CombinedWallCrafterBuild b : oldGroup) {
                if (!b.isValid())
                    continue;
                if (poolOurs && poolItems != null && b.items != null && b.items != poolItems) {
                    for (Item item : content.items()) {
                        int amt = b.items.get(item);
                        if (amt > 0) {
                            poolItems.add(item, amt);
                            b.items.remove(item, amt);
                        }
                    }
                }
            }
            CombinedWallCrafterBuild oldLeader = newLeader;
            ItemModule oldItems = poolItems;
            Seq<CombinedWallCrafterBuild> kicked = new Seq<>();
            for (CombinedWallCrafterBuild b : oldGroup)
                if (b.isValid() && !newGroup.contains(b))
                    kicked.add(b);
            if (kicked.isEmpty())
                return;

            int oldTotalItemCap = 0;
            for (CombinedWallCrafterBuild b : oldGroup)
                if (b.isValid())
                    oldTotalItemCap += b.block.itemCapacity;
            int[] itemCaps = new int[kicked.size];
            int kickedTotalItemCap = 0;
            for (int i = 0; i < kicked.size; i++) {
                CombinedWallCrafterBuild b = kicked.get(i);
                itemCaps[i] = b.block.itemCapacity;
                kickedTotalItemCap += itemCaps[i];
            }
            ItemModule[] newItemMods = new ItemModule[kicked.size];
            for (int i = 0; i < kicked.size; i++)
                newItemMods[i] = new ItemModule();

            if (poolOurs && oldItems != null && oldTotalItemCap > 0 && kickedTotalItemCap > 0) {
                for (Item item : content.items()) {
                    int total = oldItems.get(item);
                    if (total <= 0)
                        continue;
                    int kickedTotalShare = Math.round(total * (float) kickedTotalItemCap / oldTotalItemCap);
                    kickedTotalShare = Math.min(kickedTotalShare, total);
                    int remaining = kickedTotalShare;
                    for (int i = 0; i < kicked.size; i++) {
                        int ideal = (i == kicked.size - 1) ? remaining
                                : Math.round(kickedTotalShare * (float) itemCaps[i] / kickedTotalItemCap);
                        ideal = Math.min(ideal, remaining);
                        int share = ideal; // 不硬截断，宁可超容也不丢物品
                        if (share > 0) {
                            newItemMods[i].add(item, share);
                            remaining -= share;
                        }
                    }
                    oldItems.remove(item, kickedTotalShare - remaining);
                }
            }
            if (poolOurs && oldItems != null)
                for (CombinedWallCrafterBuild b : newGroup)
                    if (b.isValid())
                        b.items = oldItems;
            for (int i = 0; i < kicked.size; i++) {
                if (poolOurs) kicked.get(i).items = newItemMods[i];
                // 拆出去的成员拿一口空液池：液体留在组池里（和物品"留在组里"同一口径），
                // 避免它继续 alias 组池后被后续独立运行搬空/重复写档。
                // 【不共享液体】没有"组池"这回事：它自己那份液池留着，别换成空的（等于丢液体）。
                if (combine.net.ComboShare.shares(newLeader, combine.net.ComboShare.LIQUIDS))
                    kicked.get(i).liquids = new LiquidModule();
            }
        }

        public void shareModules(CombinedWallCrafterBuild leader) {
            int totalItemCap = leader.comboTotalItemCap;
            if (leader.items == null) {
                for (CombinedWallCrafterBuild m : group())
                    if (m.items != null) {
                        leader.items = m.items;
                        break;
                    }
            }
            // 【池子归属】组里有人拿着"组外也在用"的那份模块（核心库存/网络池）时，
            // 组长必须换成那一份再并池：否则会把核心库存复制进组长自己的模块里
            //（核心没动、工厂也多一份同样的物品），网络层随后把这多出来的一份并回核心 —— 库存凭空翻倍。
            ObjectSet<ItemModule> sharedItemPools = ComboReflect.itemPoolsSharedOutside(group());
            ItemModule sharedItems = sharedItemPools.isEmpty() ? null : sharedItemPools.first();
            // 【不共享物品】没勾"物品"时谁也别并谁（拆开由 ComboNet 按单台粒度做，见 perMemberGroups）
            boolean localShareItems = combine.net.ComboShare.shares(leader, combine.net.ComboShare.ITEMS);
            if (localShareItems && sharedItems != null) leader.items = sharedItems;
            ObjectSet<ItemModule> processedItems = new ObjectSet<>();
            if (localShareItems && leader.items != null) {
                processedItems.add(leader.items);
                for (CombinedWallCrafterBuild m : group()) {
                    if (m != leader && m.isValid() && m.items != null && !processedItems.contains(m.items)
                            // 组外也在用的模块不能"全额并入"（并入不清空源模块 = 凭空多一份）
                            && !sharedItemPools.contains(m.items)) {
                        processedItems.add(m.items);
                        for (Item item : content.items()) {
                            int amt = m.items.get(item);
                            if (amt > 0)
                                leader.items.add(item, amt); // 全额并入
                        }
                    }
                }
                for (CombinedWallCrafterBuild m : group())
                    if (m.isValid())
                        m.items = leader.items;
                if (leader.items != null) leader.items.stopFlow(); // 组内搬池子不算流量（见 ComboNet.moveItems）
            }

            // ---- 液体池同理：整组共用一份 liquids（去重后每口池子只写一份）----
            if (leader.liquids == null) {
                for (CombinedWallCrafterBuild m : group())
                    if (m.liquids != null) {
                        leader.liquids = m.liquids;
                        break;
                    }
            }
            ObjectSet<LiquidModule> sharedLiquidPools = ComboReflect.liquidPoolsSharedOutside(group());
            LiquidModule sharedLiquids = sharedLiquidPools.isEmpty() ? null : sharedLiquidPools.first();
            boolean localShareLiquids = combine.net.ComboShare.shares(leader, combine.net.ComboShare.LIQUIDS);
            if (localShareLiquids && sharedLiquids != null) leader.liquids = sharedLiquids;
            ObjectSet<LiquidModule> processedLiquids = new ObjectSet<>();
            if (localShareLiquids && leader.liquids != null) {
                processedLiquids.add(leader.liquids);
                for (CombinedWallCrafterBuild m : group()) {
                    if (m != leader && m.isValid() && m.liquids != null && !processedLiquids.contains(m.liquids)
                            && !sharedLiquidPools.contains(m.liquids)) {
                        processedLiquids.add(m.liquids);
                        for (Liquid liquid : content.liquids()) {
                            float amt = m.liquids.get(liquid);
                            if (amt > 0f)
                                leader.liquids.add(liquid, amt); // 全额并入
                        }
                    }
                }
                for (CombinedWallCrafterBuild m : group())
                    if (m.isValid())
                        m.liquids = leader.liquids;
                leader.liquids.stopFlow(); // 组内搬池子不算流量
            }
        }

        // -------------------- 生命周期 --------------------
        @Override
        public void created() {
            super.created();
            comboDirty = true;
        }

        @Override
        public void onProximityUpdate() {
            super.onProximityUpdate();
            comboDirty = true;
            for (CombinedWallCrafterBuild m : group())
                if (m.isValid())
                    m.comboDirty = true;
        }

        @Override
        public void onRemoved() {
            Seq<CombinedWallCrafterBuild> members = new Seq<>(group());
            boolean wasLeader = isLeader();
            ItemModule oldItems = this.items;
            // 【别动不属于本组的池子】核心库存/别的组合体也在用的那份模块：既不能按容量
            // "分一份给幸存者"（那是从核心库存里搬东西），也不能复制一份（核心库存会凭空翻倍）。
            boolean poolOurs = !ComboReflect.itemPoolSharedOutside(oldItems, members);
            if (!wasLeader) {
                if (items != null) {
                    boolean shared = false;
                    for (CombinedWallCrafterBuild m : members)
                        if (m != this && m.isValid() && m.items == this.items) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        items = new ItemModule();
                }
                if (liquids != null) {
                    boolean shared = false;
                    for (CombinedWallCrafterBuild m : members)
                        if (m != this && m.isValid() && m.liquids == this.liquids) {
                            shared = true;
                            break;
                        }
                    if (shared)
                        liquids = new LiquidModule();
                }
            }
            if (wasLeader) {
                Seq<CombinedWallCrafterBuild> survivors = new Seq<>();
                for (CombinedWallCrafterBuild b : members)
                    if (b != this && b.isValid())
                        survivors.add(b);
                int[] itemCaps = new int[survivors.size];
                int totalItemCap = 0;
                for (int i = 0; i < survivors.size; i++) {
                    CombinedWallCrafterBuild b = survivors.get(i);
                    itemCaps[i] = b.block.itemCapacity;
                    totalItemCap += itemCaps[i];
                }
                ItemModule[] itemMods = new ItemModule[survivors.size];
                for (int i = 0; i < survivors.size; i++)
                    itemMods[i] = new ItemModule();
                if (poolOurs && oldItems != null && totalItemCap > 0) {
                    for (Item item : content.items()) {
                        int total = oldItems.get(item);
                        if (total <= 0)
                            continue;
                        int remaining = total;
                        for (int i = 0; i < survivors.size; i++) {
                            int ideal = (i == survivors.size - 1) ? remaining
                                    : Math.round(total * (float) itemCaps[i] / totalItemCap);
                            ideal = Math.min(ideal, remaining);
                            int share = ideal; // 不硬截断
                            if (share > 0) {
                                itemMods[i].add(item, share);
                                remaining -= share;
                            }
                        }
                    }
                }
                for (int i = 0; i < survivors.size; i++) {
                    CombinedWallCrafterBuild b = survivors.get(i);
                    b.items = itemMods[i];
                    b.liquids = new LiquidModule(); // 液体整池留在原组长那一份，幸存者各自空池
                    b.comboLeader = null;
                    b.comboGroup = new Seq<>();
                    b.comboDirty = true;
                    b.comboTotalItemCap = 0;
                    b.comboTotalLiquidCap = 0f;
                }
            } else {
                CombinedWallCrafterBuild leader = leader();
                if (leader != null && leader.isValid() && leader != this)
                    leader.comboDirty = true;
            }
            comboLeader = null;
            comboGroup = new Seq<>();
            comboDirty = false;
            comboTotalItemCap = 0;
            super.onRemoved();
        }

        // -------------------- 核心逻辑 --------------------
        @Override
        public boolean acceptItem(Building source, Item item) {
            // 【石墨强化】原版 WallCrafter 的 itemConsumer（高级墙壁粉碎机 = consumeItem(graphite).boost()）
            // 是"吃物品加速"的，必须能收进来。以前一律 false，石墨永远进不去（用户报的）。
            // 和原版 Building.acceptItem 同款：只收本机/本组用得到的物品，且不超过整组物品上限。
            return block.hasItems && block.consumesItem(item)
                    && items != null && items.get(item) < getMaximumAccepted(item);
        }

        @Override
        public boolean acceptLiquid(Building source, Liquid liquid) {
            if (!block.hasLiquids || liquid == null)
                return false;
            // 只收本机真正消费的液体（高级墙壁粉碎机 = 氢气）；纯产出的墙切割机仍然拒收。
            return block.consumesLiquid(liquid)
                    && liquids != null && liquids.get(liquid) < Math.max(comboTotalLiquidCap, 1f) - 0.01f;
        }

        @Override
        public void handleLiquid(Building source, Liquid liquid, float amount) {
            if (amount <= 0.001f)
                return;
            float room = Math.max(comboTotalLiquidCap, 0f) - liquids.get(liquid);
            if (room <= 0f)
                return;
            super.handleLiquid(source, liquid, Math.min(amount, room));
        }

        @Override
        public void dumpLiquid(Liquid liquid, float scaling, int outputDir) {
            // 池子是整组的，管道要按整组液容算填充度，否则共享池超过单台液容就断流。
            float oldCap = block.liquidCapacity;
            block.liquidCapacity = Math.max(comboTotalLiquidCap, 1f);
            super.dumpLiquid(liquid, scaling, outputDir);
            block.liquidCapacity = oldCap;
        }

        @Override
        public int getMaximumAccepted(Item item) {
            return Math.max(comboTotalItemCap, 1);
        }

        @Override
        public boolean canDump(Building to, Item item) {
            // 同池目标（组合节点/连接器接过来的核心、仓库）不能当成出货口：
            // dump() 会加 1 再减 1、读数不变却返回成功，真正的传送带就永远轮不到。
            return to != null && items != null && to.items != items && super.canDump(to, item);
        }

        @Override
        public boolean shouldAmbientSound() {
            // FIX[声音线程崩溃]: MindustryX 的环境音在独立 AudioThread（20fps）上调用
            // shouldAmbientSound()，原版默认实现转调 shouldConsume()；
            // 组合建筑这一路会读库存/遍历共享序列，跨线程相撞就是
            // NoSuchElementException。组合建筑统一禁用环境音循环。
            return false;
        }

        public boolean shouldConsume() {
            return items.get(output) < Math.max(comboTotalItemCap, 1);
        }

        @Override
        public void updateTile() {
          // 关掉相邻自动组合（总开关 / 只保留超级组合）/ 不是玩家队伍：照常跑自己的逻辑 ——
          // 分组被 ComboTeams.playerTeam 挡成"只有自己"时 = 原版单机行为（见 ComboReflect.markSolo）。
          if (!combine.util.ComboTeams.playerTeam(team)) combine.util.ComboReflect.markSolo(this);
            if (pendingLeaderPos != -1) {
                Building b = world.build(pendingLeaderPos);
                if (b instanceof CombinedWallCrafterBuild leaderBuild && leaderBuild.isValid()
                        && leaderBuild.team == team) {
                    comboLeader = leaderBuild;
                    if (leaderBuild.items != null)
                        items = leaderBuild.items;
                } else {
                    comboLeader = null;
                }
                pendingLeaderPos = -1;
                comboDirty = true;
            }
            if (isLeader() && comboDirty)
                rebuildCombo();

            boolean cons = shouldConsume();
            boolean itemValid = itemConsumer != null && itemConsumer.efficiency(this) > 0;

            warmup = Mathf.approachDelta(warmup, Mathf.num(efficiency > 0), 1f / 40f);
            float dx = Geometry.d4x(rotation) * 0.5f, dy = Geometry.d4y(rotation) * 0.5f;

            float eff = efficiencyAt(tile.x, tile.y, rotation, dest -> {
                if (wasVisible && cons && Mathf.chanceDelta(updateEffectChance * warmup)) {
                    updateEffect.at(
                            dest.worldx() + Mathf.range(3f) - dx * tilesize,
                            dest.worldy() + Mathf.range(3f) - dy * tilesize,
                            dest.block().mapColor);
                }
            }, null) * Mathf.lerp(1f, liquidBoostIntensity,
                    hasLiquidBooster ? optionalEfficiency : 0f) * (itemValid ? itemBoostIntensity : 1f);

            if (itemValid && eff * efficiency > 0 && timer(timerUse, boostItemUseTime / timeScale)) {
                consume();
            }

            lastEfficiency = eff * timeScale * efficiency;

            if (cons && (time += edelta() * eff) >= drillTime) {
                items.add(output, 1); // 直接入共享池
                time %= drillTime;
            }

            totalTime += edelta() * warmup * (eff <= 0f ? 0f : 1f);

            // FIX[搬运粒度]: 同上，按每 tick 搬运
            if (timer(timerDump, CombinedCrafter.comboDumpInterval / timeScale)) {
                dump(output);
            }
        }

        // -------------------- 显示 --------------------
        @Override
        public void display(Table table) {
          if (!ComboUi.detail()) { super.display(table); return; }
          // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
          ComboUi.safe("combinedwallcrafter:display", () -> displayInner(table));
        }

        void displayInner(Table table) {
            table.table(cont -> {
                cont.top().left();
                cont.defaults().growX().left();
                cont.table(t -> {
                    t.left();
                    TextureRegion icon = block.getDisplayIcon(tile);
                    if (icon == null)
                        icon = Core.atlas.find("clear");
                    t.add(new Image(icon)).size(8 * 4);
                    int count = ComboNet.displayMembers(this, group().size).size;
                    String title = count > 1
                            ? "[accent]组合墙切割机[] x" + count + "\n" + block.getDisplayName(tile)
                            : block.getDisplayName(tile);
                    t.labelWrap(title).left().width(160f).padLeft(4);
                }).growX().left();
                cont.row();
                if (team != mindustry.Vars.player.team())
                    return;
                Table barsTable = new Table();
                barsTable.left();
                barsTable.update(() -> {
                    barsTable.clearChildren();
                    barsTable.defaults().width(ComboUi.COMPOSITION_WIDTH).height(18f).pad(4);
                    // 血条：手绘（不要用 displayBars —— 它会把原版那条按假容量 9999 算的液条也带出来，
                    // 和组合自己的液条重复）
                    if (!Mathf.zero(block.health, 0.001f)) {
                        final float h = health, mh = maxHealth;
                        barsTable.add(new Bar(
                                () -> Core.bundle.get("stat.health", "Health") + " " + (int) Math.max(h, 0),
                                () -> Pal.health, () -> Mathf.clamp(h / mh)));
                        barsTable.row();
                    }
                    final float le = lastEfficiency;
                    barsTable.add(new Bar(
                            () -> Core.bundle.format("bar.drillspeed", Strings.fixed(le * 60 / drillTime, 2)),
                            () -> Pal.ammo, () -> warmup));
                    barsTable.row();
                    // 物品池 / 电力条挪到悬浮面板（用户要求 display() 只留组合体构成）。
                });
                cont.add(barsTable).growX().left();
                cont.row();
                Table comboIO = new Table();
                comboIO.left();
                comboIO.update(() -> {
                    comboIO.clearChildren();
                    buildComboIO(comboIO);
                });
                cont.add(comboIO).growX().left();
            }).width(260f).left();
                }

        public void buildComboIO(Table table) {
            table.left();
            ComboUi.addComposition(table, this, group().size);
        }

        // -------------------- 序列化 --------------------
        // 地图区里只写"原版挖墙钻那一份字节"（WallCrafterBuild 就是 Building），
        // 模组自己的字段（组长、time）挪到自定义存档块 ComboSaveState —— 详见 ComboSaved。
        @Override
        public byte version() {
            return combine.saves.ComboSaveState.vanillaVersion(block);
        }

        @Override
        public void write(Writes write) {
            super.write(write);
        }

        // 存档先调 writeBase 写模块数据、后调 write —— "每口池子只写一份"必须挂在 writeBase 上
        // （写在 write() 里来不及），否则每个成员各写一份整池，读档合并后数量 ×N。
        @Override
        public void writeBase(Writes write) {
            ItemModule savedItems = items;
            LiquidModule savedLiquids = liquids;
            // 【每口池子只写一份】按模块身份去重（见 ComboSaveState.firstItemPool）：
            // 以前按"本地组组长"算，一条网络里 M 个本地组会把同一口池子写 M 遍，读档直接 ×M。
            if (!combine.saves.ComboSaveState.firstItemPool(this) && items != null)
                items = new ItemModule();
            if (!combine.saves.ComboSaveState.firstLiquidPool(this) && liquids != null)
                liquids = new LiquidModule();
            super.writeBase(write);
            items = savedItems;
            liquids = savedLiquids;
        }

        @Override
        public void writeCombo(Writes write) {
            Building leader = combine.saves.ComboSaveState.trueLeader(this, comboGroup);
            write.bool(leader != this);
            if (leader != this)
                write.i(leader.pos());
            write.f(time);
        }

        @Override
        public void read(Reads read, byte revision) {
            super.read(read, revision);
            if (revision >= 10) {
                applyLeader(read, true); // 旧档（≤2.6）：模组字段直接写在地图区里
                return;
            }
            comboDirty = true;
        }

        @Override
        public void readCombo(Reads read, byte revision) {
            // 新格式里"非组长"在存档里写的就是空模块，读档时整组已经被并成一份，
            // 这里不能再清空（清了就把并好的池子丢掉）。
            applyLeader(read, false);
        }

        void applyLeader(Reads read, boolean clearModules) {
            boolean hasLeader = read.bool();
            int leaderPos = hasLeader ? read.i() : -1;
            comboDirty = true;
            if (hasLeader && leaderPos != pos()) {
                pendingLeaderPos = leaderPos;
                if (clearModules && items != null)
                    items = new ItemModule();
            } else {
                pendingLeaderPos = -1;
                comboLeader = null;
            }
            time = read.f();
        }
    }
}
