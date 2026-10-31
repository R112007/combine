package combine.dbg;
import arc.*; import arc.backend.headless.HeadlessApplication; import arc.util.Log;
import mindustry.*; import mindustry.content.*; import mindustry.core.*; import mindustry.game.*; import mindustry.gen.*;
import mindustry.maps.Map; import mindustry.mod.*; import mindustry.net.Net;
import mindustry.ui.Fonts; import mindustry.world.*;

/**
 * 矿渣制热机（HeatProducer，组合后是 CombinedCrafter.heatproducer）：
 * **对外**的 heat() 必须是"这一台自己的热量"，否则贴着一个组合体里 3 台产热机
 * 的耗热方会把整组热量算 3 遍（10 台 80 热 → 耗热方看到 2400）。
 * 组内共享（heatcrafter 成员的 availableHeat）另算，不受影响。
 */
public class HeatProducerTest implements ApplicationListener{
    static String dataDir="/tmp/mp_cj/data";
    static int pass=0, fail=0;
    public static void main(String[] a){ if(a.length>0) dataDir=a[0];
        Vars.platform=new Platform(){}; Vars.net=new Net(Vars.platform.getNet());
        Log.logger=(l,t)->{ if(l.ordinal()>=arc.util.Log.LogLevel.warn.ordinal()) System.out.println("[L] "+t); };
        new HeadlessApplication(new HeatProducerTest(), t->t.printStackTrace()); }
    static void check(String n, boolean ok){ System.out.println("[HP] " + (ok?"PASS ":"FAIL ") + n); if(ok) pass++; else fail++; }
    static void run(int f){ for(int i=0;i<f;i++){ arc.util.Time.delta=1f; Vars.logic.update(); } }
    static Building place(Block b,int x,int y,Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null,b,team,ax,ay,0,null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax,ay),b,null,(byte)0,team,null);
        Building bu = Vars.world.build(ax,ay);
        if(bu != null){ try{ bu.updateProximity(); }catch(Throwable ignored){} }
        return bu; }
    static Block find(String name){ for(Block b : Vars.content.blocks()) if(b.name.equals(name)) return b; return null; }
    static void clearArea(){
        for(int y=40;y<140;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5); }
    /** 用节点的"点目标 = 连线"接口把节点跟组合体连上（等同玩家点两下）。 */
    static void tapNode(Building node, Building target){
        try{
            java.lang.reflect.Method m = node.getClass().getMethod("onConfigureBuildTapped", Building.class);
            m.setAccessible(true);
            m.invoke(node, target);
        }catch(Throwable t){ System.out.println("[HP] 节点连线失败: " + t); } }
    static boolean adjacent(Building a, Building b){
        if(a == null || b == null || a.proximity == null) return false;
        for(Building nb : a.proximity) if(nb == b) return true;
        return false; }
    static void setRotation(Building b, int rotation){
        try{
            var f = b.getClass().getField("rotation");
            f.setByte(b, (byte)rotation);
        }catch(Throwable t){ System.out.println("[HP] 设置朝向失败: " + t); } }
    static int rel(Building a, Building b){
        try{
            java.lang.reflect.Method m = a.getClass().getMethod("relativeTo", Building.class);
            return ((Number)m.invoke(a, b)).intValue();
        }catch(Throwable t){ return -99; } }
    /** 读 heat()（HeathBlock 接口方法在实现类上，Building 类型没有）。 */
    static float heatOf(Object o){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var m = c.getDeclaredMethod("heat"); m.setAccessible(true); return ((Number)m.invoke(o)).floatValue(); }
                catch(NoSuchMethodException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1f; }
    static float methodF(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var m = c.getDeclaredMethod(name); m.setAccessible(true); return ((Number)m.invoke(o)).floatValue(); }
                catch(NoSuchMethodException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1f; }
    static float fieldF(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.getFloat(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return -1f; }
    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }
    static Block originalOf(Block combo){
        try{
            Class<?> cloner = combo.getClass().getClassLoader().loadClass("combine.BlockCloner");
            Object map = cloner.getField("comboToOriginal").get(null);
            Object value = map.getClass().getMethod("get", Object.class).invoke(map, combo);
            return value instanceof Block b ? b : null;
        }catch(Throwable ignored){}
        return null;
    }

    @Override public void init(){
      try{
        Core.settings.setDataDirectory(Core.files.local(dataDir));
        Vars.loadLocales=false; Vars.loadSettings(); Vars.headless=true; Vars.init();
        UI.loadColors(); Fonts.loadContentIconsHeadless();
        Vars.content.createBaseContent(); Vars.mods.loadScripts(); Vars.content.createModContent(); Vars.content.init();
        Vars.mods.eachClass(Mod::init);
        if(Vars.logic==null) Vars.logic=new Logic();
        if(Vars.netServer==null) Vars.netServer=new NetServer();
        if(Vars.netClient==null) Vars.netClient=new NetClient();
        Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
        Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
        Vars.logic.play();
        run(20);
        for(int y=30;y<170;y++) for(int x=10;x<250;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
        run(5);

        Block heater = find("slag-heater");
        Block router = find("heat-router");
        if(heater == null){ System.out.println("[HP] 没找到 slag-heater"); System.exit(3); }
        System.out.println("[HP] 制热机=" + heater.name + "/" + heater.getClass().getName()
            + " size=" + heater.size + " heatOutput=" + fieldF(heater, "heatOutput")
            + " warmupRate=" + fieldF(heater, "warmupRate")
            + " | 导热=" + (router==null?null:router.name + "/" + router.getClass().getName()));
        Block originalHeater = originalOf(heater);
        float originalWarmupRate = originalHeater == null ? -1f : fieldF(originalHeater, "warmupRate");
        float comboWarmupRate = fieldF(heater, "warmupRate");
        System.out.println("[HP] 升温率: 原版=" + originalWarmupRate + " 组合=" + comboWarmupRate);
        check("组合产热机保留原版 warmupRate（" + comboWarmupRate + "）",
            originalWarmupRate > 0f && Math.abs(comboWarmupRate - originalWarmupRate) < 0.0001f);

        int sz = Math.max(heater.size, 1);
        Building[] all = new Building[10];
        for(int i = 0; i < all.length; i++) all[i] = place(heater, 60 + i * sz, 60, Team.sharded);
        run(20);
        // 矿渣制热机要喝矿渣才会运转（组合后共用一个液池，灌一台就行）
        all[0].liquids.add(Liquids.slag, 100000f);
        run(100);
        // 跑一会儿再补一次矿渣（池容量 = 10 × 120，会被 clamp，消耗 6.7/tick）
        all[0].liquids.add(Liquids.slag, 100000f);
        run(20);

        float ownSum = 0f, heatSum = 0f;
        StringBuilder sb = new StringBuilder();
        float perOwn = fieldF(all[0], "producerHeat");
        boolean sameOwn = true;
        for(Building b : all){
            float own = fieldF(b, "producerHeat");
            ownSum += own;
            heatSum += heatOf(b);
            sb.append(String.format(java.util.Locale.ROOT, "%.1f/%.1f ", own, heatOf(b)));
            if(Math.abs(own - perOwn) > 0.01f) sameOwn = false;
        }
        System.out.println("[HP] 10 台（自身/对外heat）: " + sb);
        System.out.println("[HP] 自身之和=" + ownSum + " 对外 heat() 之和=" + heatSum);
        float per = ownSum / all.length;
        check("十台都在产热（每台 " + per + " > 0）", per > 0.1f && sameOwn);
        float ratedHeat = fieldF(heater, "heatOutput");
        check("120 tick 后达到额定热量（每台 " + per + " / " + ratedHeat + "）",
            per >= ratedHeat * 0.9f && per <= ratedHeat * 1.1f);
        check("每台对外报的就是自己那一份（heat() == 自身）", Math.abs(heatSum - ownSum) < 1f);
        float threeSide = 0f;
        for(int i = 0; i < 3; i++) threeSide += heatOf(all[i]);
        System.out.println("[HP] 只有 3 台挨着耗热方时，耗热方看到 " + threeSide + "（整组自身之和 " + ownSum + "）");
        check("贴 3 台只算这 3 台（" + threeSide + " < 整组 " + ownSum + "）", threeSide < ownSum - 0.01f);

        // 组内共享不能退化：同组的"组合需热工厂"要能看到整组热量
        Block heatCrafter = null;
        for(Block b : Vars.content.blocks()){
            if(!b.getClass().getName().equals("combine.production.CombinedCrafter")) continue;
            Object m = null;
            try{ var f = b.getClass().getDeclaredField("mode"); f.setAccessible(true); m = f.get(b); }catch(Throwable ignored){}
            if(m != null && m.toString().equals("heatcrafter")){ heatCrafter = b; break; }
        }
        if(heatCrafter != null){
            int csz = Math.max(heatCrafter.size, 1);
            Building hc = place(heatCrafter, 60, 60 + sz, Team.sharded);
            all[0].liquids.add(Liquids.slag, 100000f);
            run(40);
            float avail = methodF(hc, "availableHeat");
            System.out.println("[HP] 同组需热工厂 " + heatCrafter.name + " 看到的热量=" + avail + "（整组自身之和 " + ownSum + "）");
            check("组内需热工厂照样拿到整组热量（" + avail + " > 单台 " + per + "）", avail > per * 1.5f);
        }

        // 贴着 3 台放一个耗热方看它到底拿到多少热
        if(router != null){
            Building r = place(router, 60 + sz, 60 + sz, Team.sharded);
            // 原版热量是"要对着"的（HeatProducer.rotate = true）：制热机必须朝向耗热方那一边。
            // 朝向编码别猜：按原版判据 rotation == (relativeTo(源) + 2) % 4 反推每一台的朝向。
            for(Building b : all) if(adjacent(r, b)) setRotation(b, (rel(r, b) + 2) % 4);
            all[0].liquids.add(Liquids.slag, 100000f);
            run(60);
            System.out.println("[HP] 导热/耗热方 heat=" + heatOf(r) + "（自身 " + fieldF(r, "heat") + "）");
            // 产热会继续涨（producerHeat 是平滑逼近的），所以这里重新量一次整组产量
            float ownSumNow = 0f;
            for(Building b : all) ownSumNow += fieldF(b, "producerHeat");
            float perNow = ownSumNow / all.length;
            System.out.println("[HP] 此刻整组产量=" + ownSumNow + "（每台 " + perNow + "）");
            // 用户报："一个组合体的热量不能通过热量传输器统一输出，只能输出靠近热量传输器的一个的"
            // → 组合体 = 一台大机器：贴上去的导热管应该拿到**整组**的热量；
            //   但不能 N 倍（10 台各报整组 = 耗热方看到 10× 整组，那是以前修过的老 bug）
            check("导热管拿到整组热量（不再只是贴着的那一台：" + heatOf(r) + " ≈ 整组 " + ownSumNow + "）",
                heatOf(r) > perNow * 1.5f && heatOf(r) > ownSumNow * 0.9f);
            check("导热管拿到的不是 N 倍（≤ 整组之和 " + ownSumNow + "）", heatOf(r) <= ownSumNow + 1f);
        }

        // ---- 组合节点 / 组合连接器传热（用户报的"组合节点和组合连接器不能传递热量"）----
        Block nodeB = null, connB = null, redirector = find("heat-redirector");
        for(Block b : Vars.content.blocks()){
            if(nodeB == null && b.getClass().getName().equals("combine.net.ComboNode")) nodeB = b;
            if(connB == null && b.getClass().getName().equals("combine.net.ComboConnector")) connB = b;
        }
        if(redirector == null || nodeB == null || connB == null){
            System.out.println("[HP] 缺导热管/节点/连接器，跳过传热用例（redirector=" + redirector + " node=" + nodeB + " conn=" + connB + "）");
        }else{
            // 组合节点：两台制热机成组 → 节点（激光连上）→ 贴着节点的导热管
            clearArea();
            Building h1 = place(heater, 60, 60, Team.sharded);
            Building h2 = place(heater, 63, 60, Team.sharded);
            Building node = place(nodeB, 68, 60, Team.sharded);      // 1x1，占 (68,60)
            Building rd = place(redirector, 69, 59, Team.sharded);   // 3x3，占 (69..71,59..61)，只贴着节点
            run(10);
            tapNode(node, h2);
            run(10);
            h1.liquids.add(Liquids.slag, 100000f);
            run(120);
            float groupOwn = fieldF(h1, "producerHeat") + fieldF(h2, "producerHeat");
            float nodeHeat = fieldF(node, "heat"), rdHeat = heatOf(rd);
            System.out.println("[HP] 节点传热: 组内两台产热=" + groupOwn + " 节点heat=" + nodeHeat
                + " 贴着节点的导热管 heat=" + rdHeat + "（导热管自身字段 " + fieldF(rd, "heat") + "）");
            check("组合节点把整组热量传给贴着的导热管（组内 " + groupOwn + "）",
                groupOwn > 0.1f && rdHeat > groupOwn * 0.9f && rdHeat < groupOwn * 2.1f);

            // 组合连接器：两组制热机中间只放连接器，导热管只贴着连接器
            clearArea();
            Building c1 = place(heater, 60, 60, Team.sharded);
            Building c2 = place(heater, 63, 60, Team.sharded);
            Building conn = place(connB, 66, 60, Team.sharded);      // 1x1，贴着 c2
            Building rd2 = place(redirector, 67, 59, Team.sharded);  // 3x3，占 (67..69,59..61)，只贴着连接器
            run(10);
            c1.liquids.add(Liquids.slag, 100000f);
            run(120);
            float groupOwn2 = fieldF(c1, "producerHeat") + fieldF(c2, "producerHeat");
            float connHeat = fieldF(conn, "heat"), rdHeat2 = heatOf(rd2);
            System.out.println("[HP] 连接器传热: 组内两台产热=" + groupOwn2 + " 连接器heat=" + connHeat
                + " 贴着连接器的导热管 heat=" + rdHeat2 + " 相邻吗=" + adjacent(conn, rd2));
            check("组合连接器把整组热量传给贴着的导热管（组内 " + groupOwn2 + "）",
                groupOwn2 > 0.1f && rdHeat2 > groupOwn2 * 0.9f && rdHeat2 < groupOwn2 * 2.1f);
        }

        System.out.println("[HP] RESULT " + (fail==0?"ALL PASS":(fail+" FAILED")) + " (pass="+pass+")");
        System.exit(fail==0?0:1);
      }catch(Throwable t){ t.printStackTrace(); System.exit(2); }
    }
}
