package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.struct.Seq; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*; import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net; import mindustry.type.*; import mindustry.ui.Fonts; import mindustry.world.*;
/**
 * 设置里关掉某个建筑组合后，要**立刻**抛弃所有组合特性：
 * 物品/液体不再共享、容量/导电性还原、重新打开后立刻恢复共享。
 */
public class DetachTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    static boolean sawErr=false;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.err.ordinal()){ System.out.println("[L] "+t); sawErr = true; } };
        new HeadlessApplication(new DetachTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[DT3] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static Building place(Block b,int x,int y,Team team){ mindustry.world.Build.beginPlace(null,b,team,x,y,0,null); mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x,y),b,null,(byte)0,team,null); return Vars.world.build(x,y); }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Block find(String suffix){ for(Block b : Vars.content.blocks()) if(b.name.endsWith(suffix)) return b; return null; }
    static Block findAny(String[] qs){ for(String q : qs){ Block b = find(q); if(b != null) return b; } return null; }
    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        ClassLoader ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        Class<?> coop = Class.forName("combine.coop.CoopCombo", true, ml);
        java.lang.reflect.Method setBlocked = coop.getMethod("setBlocked", String.class, boolean.class);
        java.lang.reflect.Method eligible = coop.getMethod("eligibleByClass", Block.class);
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        Block prod = findAny(new String[]{"coop-producer", "废土科技-冶炼厂", "废土科技-冷却机"});
        if(prod == null){
          // 不同数据集里扩展方块名不固定：退回到任意一个“有物品池的 mod 方块”。
          for(Block b : Vars.content.blocks()){
            if(b != null && b.hasItems && b.size <= 2
                && !b.getClass().getName().startsWith("mindustry.")
                && !b.getClass().getName().startsWith("combine.")
                && (Boolean)eligible.invoke(null, b)){
              prod = b;
              break;
            }
          }
        }
        if(prod == null){ System.out.println("[DT3] 找不到可测的扩展建筑"); System.exit(3); }
        System.out.println("[DT3] 被测建筑: " + prod.name + " cls=" + prod.getClass().getName() + " loader=" + prod.getClass().getClassLoader());
        setBlocked.invoke(null, prod.name, false);
        run(3);
        int sz = Math.max(prod.size, 1);
        int baseItem = prod.itemCapacity;
        for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null&&t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(3);
        Building p1 = place(prod, 60, 60, Team.sharded);
        Building p2 = place(prod, 60 + sz, 60, Team.sharded);
        run(40);
        System.out.println("[DT3] 初始: 同池=" + (p1.items == p2.items) + " 同液池=" + (p1.liquids == p2.liquids)
            + " 方块容量=" + prod.itemCapacity + " (基础 " + baseItem + ") 导电=" + prod.conductivePower);
        check("初始两台共用一个池", p1.items == p2.items && p1.liquids == p2.liquids);
        check("初始容量被放大（" + prod.itemCapacity + " > " + baseItem + "）", prod.itemCapacity > baseItem);

        setBlocked.invoke(null, prod.name, true);
        run(60);
        boolean sameItems = p1.items == p2.items, sameLiquids = p1.liquids == p2.liquids;
        System.out.println("[DT3] 关闭后: 同池=" + sameItems + " 同液池=" + sameLiquids
            + " 方块容量=" + prod.itemCapacity + " 导电=" + prod.conductivePower);
        check("关闭后物品不再共享", !sameItems);
        if(prod.hasLiquids) check("关闭后液体不再共享", !sameLiquids);
        check("关闭后容量还原成基础值 (" + prod.itemCapacity + ")", prod.itemCapacity == baseItem);
        check("关闭后导电性还原/电不再共享", !prod.conductivePower);
        check("关闭后仍能正常收料（各自独立）", p1.items != null && p2.items != null);

        setBlocked.invoke(null, prod.name, false);
        run(60);
        System.out.println("[DT3] 重新打开后: 同池=" + (p1.items == p2.items) + " 同液池=" + (p1.liquids == p2.liquids)
            + " 方块容量=" + prod.itemCapacity);
        check("重新打开后立刻恢复共享", p1.items == p2.items && p1.liquids == p2.liquids);
        check("重新打开后容量再次放大 (" + prod.itemCapacity + ")", prod.itemCapacity > baseItem);
        check("重新打开后电力又共享", prod.hasPower ? prod.conductivePower : true);

        Block liq = find("liquid-maker");
        if(liq != null && liq.hasLiquids){
          setBlocked.invoke(null, liq.name, false); run(3);
          int lsz = Math.max(liq.size, 1);
          Building l1 = place(liq, 60, 80, Team.sharded);
          Building l2 = place(liq, 60 + lsz, 80, Team.sharded);
          run(40);
          check("液体建筑初始共用一个液池", l1.liquids == l2.liquids);
          setBlocked.invoke(null, liq.name, true);
          run(60);
          System.out.println("[DT3] 液体建筑关闭后: 同液池=" + (l1.liquids == l2.liquids) + " 方块液容=" + liq.liquidCapacity);
          check("液体建筑关闭后液体不再共享", l1.liquids != l2.liquids);
          setBlocked.invoke(null, liq.name, false);
          run(60);
          check("液体建筑重新打开后又共享", l1.liquids == l2.liquids);
        }

        Class<?> listCls = Class.forName("combine.ui.ComboBlockList", true, ml);
        java.lang.reflect.Method kind = listCls.getMethod("kind", Block.class);
        java.lang.reflect.Method list3 = listCls.getMethod("list", String.class, int.class, int.class);
        int kindProd = (Integer) kind.invoke(null, prod);
        System.out.println("[DT3] 被测建筑 kind=" + kindProd);
        check("js/java 扩展建筑的分类是（0）", kindProd == 0);
        Seq<Block> onlyExt = (Seq<Block>) list3.invoke(null, "", 0, 0);
        boolean allExt = true;
        for(Block b : onlyExt) if(((Integer) kind.invoke(null, b)) != 0) allExt = false;
        System.out.println("[DT3] 只看扩展建筑: " + onlyExt.size + " 个, 全是扩展建筑=" + allExt);
        check("过滤「只看扩展建筑」时列表里只有扩展建筑", onlyExt.size > 0 && allExt);

        java.lang.reflect.Field cf = Class.forName("combine.NoCombo", true, ml).getField("classes");
        Seq<Class<?>> cls = (Seq<Class<?>>) cf.get(null);
        if(!cls.contains(prod.getClass()))
          cls.add(prod.getClass());
        coop.getMethod("clearEligibilityCache").invoke(null);
        coop.getMethod("markDirty").invoke(null);
        run(15);
        System.out.println("[DT3] 类名单变化后: 同池=" + (p1.items == p2.items) + " 容量=" + prod.itemCapacity + " 报错=" + sawErr);
        check("类名单变化时能就地脱离且不报错", !sawErr && p1.items != p2.items && prod.itemCapacity == baseItem);

        System.out.println("[DT3] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
