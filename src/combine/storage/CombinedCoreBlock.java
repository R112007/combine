package combine.storage;

import arc.struct.Seq;
import mindustry.content.Items;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.content;

/**
 * 组合核心：只做一件事 —— **不让原版把玩家的存货删掉**。
 *
 * 原版 {@link CoreBlock.CoreBuild#onProximityUpdate()} 每次重算都会：
 * <pre>
 *   storageCapacity = itemCapacity + 相邻的 StorageBlock 容量;
 *   for(Item item : content.items()) items.set(item, Math.min(items.get(item), storageCapacity));
 * </pre>
 * 也就是说它**按自己算出来的容量把超出的存货直接删掉**。而"组合仓库链 / 组合节点接过来的仓库"
 * 扩出来的那部分容量原版根本不认 —— 只要核心附近放/拆一个方块触发一次重算，
 * 玩家的存货就会按"核心自身容量"被削掉一大截（用户报的"造新组合仓库时物品消失"）。
 *
 * 这里在调用 super 前后把数量记下来、被削掉的补回去，并把容量抬到至少装得下现有存量
 * （注意：容量是**每种物品各自**的上限，抬到"单项最大值"即可，不能拿所有物品总和当容量）；
 * 容量本身仍然由 {@link CombinedStorageBlock} 按"核心 + 连通仓库"重算。
 */
public class CombinedCoreBlock extends CoreBlock {

    public CombinedCoreBlock(String name) {
        super(name);
        buildType = CombinedCoreBuild::new;
    }

    public class CombinedCoreBuild extends CoreBuild {
        @Override
        public boolean acceptItem(Building source, Item item) {
            // 同池来源（组合节点/连接器接过来的工厂或仓库）的 items 就是本核心的 items：
            // "搬进来"是加 1 再减 1 的空操作，却会让对方的 dump() 误以为成功，
            // 后面的传送带/管道永远轮不到。拒绝它，让对方继续找出货口。
            //
            // 注意 source 可能是核心自己：原版 Building.acceptStack(item, amount, team)
            // 内部就是 acceptItem(this, item)。玩家单位挖矿入核 / 手动转移都走 acceptStack；
            // 如果把 source == this 也拒掉，单位会把物品从背包扣掉，但核心永远收不到。
            if (source != null && source != this && source.items == items)
                return false;
            return super.acceptItem(source, item);
        }

        @Override
        public void onProximityUpdate() {
            // 调用前把每种物品的数量记下来（content.items() 的顺序稳定）
            int[] before = null;
            if (items != null) {
                before = new int[content.items().size];
                for (Item item : content.items())
                    before[item.id] = items.get(item);
            }

            super.onProximityUpdate();

            if (before == null || items == null)
                return;
            // 把被原版"超容截断"删掉的部分补回来：那些容量是组合仓库给的，原版不认
            for (Item item : content.items()) {
                int old = before[item.id];
                if (old > items.get(item)) {
                    items.set(item, old);
                }
            }
            // 【容量**不**跟着库存抬】storageCapacity 只反映"核心 + 连通仓库"的能力，见
            // CombinedStorageBlock.rebuildTeam：以前这里按"单项最大值/总和"抬高容量，
            // 结果是某种物品一旦超过容量，容量就被那个数字钉死（9000 核心 + 44×300 的容器
            // 本该显示 22200，面板却一直显示 23074，拆容器也不掉）。
            // 这里只负责"别让原版按缩小的容量把存货删掉"，上限数字依旧由仓库算出来。
        }
    }
}
