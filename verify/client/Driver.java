package drv;
import arc.*; import arc.struct.Seq; import arc.util.*; import arc.util.Timer;
import mindustry.*; import mindustry.content.*; import mindustry.game.*; import mindustry.gen.*; import mindustry.mod.*;
import mindustry.ui.dialogs.*; import mindustry.world.*; import mindustry.world.modules.*; import mindustry.type.*;

/**
 * 验证驱动：截图 + 开设置列表 + 打开 CoopPanel 并改池子看是否实时刷新。
 *
 * 截图输出目录：-Ddrv.out=<目录>（默认"桌面/shots"）。文件按**跨次运行的连续序号**命名：
 *   001_main.png、002_settings_menu.png …  这样多次跑不会互相覆盖，也能看出先后顺序。
 */
public class Driver extends Mod{
    static String outDir = System.getProperty("drv.out", System.getProperty("user.home") + "/Desktop/shots");
    static ClassLoader ml;
    static Building b1, b2, c1;

    /** 目录里已有的最大序号 + 1（没有目录就从 1 开始）。 */
    static int nextIndex(){
        int max = 0;
        try{
            arc.files.Fi d = Core.files.absolute(outDir);
            d.mkdirs();
            for(arc.files.Fi f : d.list()){
                String n = f.name();
                int i = 0;
                while(i < n.length() && Character.isDigit(n.charAt(i))) i++;
                if(i > 0) max = Math.max(max, Integer.parseInt(n.substring(0, i)));
            }
        }catch(Throwable t){ Log.err("[drv] 读取截图目录失败", t); }
        return max + 1;
    }

    static int counter = -1;

    @Override public void init(){
        MpScenario.install();   // -Ddrv.scenario=1 时：服务端剧本（造单位/融合/解体）
        Events.on(EventType.ClientLoadEvent.class, e -> {
            Log.info("[drv] ClientLoadEvent mods=@ blocks=@ mode=@", Vars.mods.list().size, Vars.content.blocks().size, mode);
            try{ Core.files.absolute(outDir).mkdirs(); }catch(Throwable t){}
            counter = nextIndex();
            Log.info("[drv] 截图目录 @（从序号 @ 开始）", Core.files.absolute(outDir).absolutePath(), counter);
            ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            // 注：延迟/丢包是 verify/lagnet.py 那个 socket 代理做的，模组里不做任何网络拦截
            // -Ddrv.uiscale=200 → 模拟"小逻辑宽度"（PC 上 uiscale 调大/窗口小），用来复现排版问题
            String us = System.getProperty("drv.uiscale");
            if(us != null){
                try{
                    arc.scene.ui.layout.Scl.setProduct(Float.parseFloat(us) / 100f);
                    Core.settings.put("uiscale", Integer.parseInt(us));
                    Log.info("[drv] uiscale=@（逻辑宽度 ≈ @×@）", us, Core.graphics.getWidth(), Core.graphics.getHeight());
                }catch(Throwable t){ Log.err("[drv] uiscale 设置失败", t); }
            }
            if(mode.equals("coolant")){
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupCoolantScene, 5f);
                Timer.schedule(() -> { shot("coolant_selector"); Core.app.exit(); }, 11f);
            }else if(mode.equals("launch")){
                // 发射界面（LaunchLoadoutDialog）：核心被换成组合实例后，
                // universe.getLoadout(组合核心) 必须还能查到内置发射蓝图，否则 .first() 直接崩。
                // 软渲染很慢（1~5fps）：地图加载 + 建世界是几十秒级的真实耗时，
                // 按"固定秒数截图"会截到还没弹出的画面，所以按状态等（launchShown）。
                Timer.schedule(Driver::hideDialogs, 2f);
                Timer.schedule(Driver::setupLaunchScene, 3f);
                Timer.schedule(() -> {
                    if(!launchShown) return;
                    shot("launch_dialog");
                    Log.info("[drv] launch 模式结束（发射界面已截图）");
                    Core.app.exit();
                }, 10f, 1f);
                Timer.schedule(() -> { Log.info("[drv] launch 模式超时退出"); Core.app.exit(); }, 150f);
            }else if(mode.equals("coop")){
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupWorld, 5f);
                Timer.schedule(() -> { shot("coop_panel_open"); }, 8f);
                Timer.schedule(() -> {
                    try{
                        Class<?> coopPanel = Class.forName("combine.coop.CoopPanel", true, ml);
                        Object desc = coopPanel.getMethod("describe", Building.class).invoke(null, b1);
                        Log.info("[drv] t=@ paused=@ describe=@", arc.util.Time.time, Vars.state.isPaused(), desc);
                    }catch(Throwable t){ Log.err("[drv] describe failed", t); }
                }, 8.5f);
                Timer.schedule(Driver::addStuff, 9f);
                Timer.schedule(() -> {
                    try{
                        Class<?> coopPanel = Class.forName("combine.coop.CoopPanel", true, ml);
                        Object desc = coopPanel.getMethod("describe", Building.class).invoke(null, b1);
                        Log.info("[drv] t=@ paused=@ describe=@", arc.util.Time.time, Vars.state.isPaused(), desc);
                    }catch(Throwable t){ Log.err("[drv] describe failed", t); }
                }, 11f);
                // 软渲染下一帧很慢（1~5fps）：加完料**必须真的过了 30 帧**再截，
                // 否则会截到面板还没重画的那一帧（看起来像"没实时刷新"）。
                Timer.schedule(Driver::maybeShotAfter, 10f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] 超时，直接截"); shot("coop_panel_after"); finish(); }, 75f);
            }else if(mode.equals("gen")){
                // 核反应堆组合进"容量极大的发电机"后：
                //   1) 发电效率只看核反应堆自己的容量（喂满即可 100%）；
                //   2) 面板里的燃料条也要按同一个口径显示（不然看着像没燃料）。
                // 软渲染下一帧很慢：截图靠"真的过了 N 帧"来卡，不用固定秒数。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupGenScene, 5f);
                Timer.schedule(() -> { hideDialogs(); genSetFuel(Math.max(genNukeCap / 2, 1)); openGenPanel(); }, 12f);
                Timer.schedule(Driver::genStep, 13f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] gen 模式超时结束"); Core.app.exit(); }, 120f);
            }else if(mode.equals("conn")){
                // 组合连接器：两块组合工厂之间铺一连串连接器 → 世界截图（看贴图/连线）
                // + 连接器信息面板截图（看"连接组合 xN"和池子内容）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupConnScene, 5f);
                Timer.schedule(Driver::stepConn, 8f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] conn 模式超时结束"); Core.app.exit(); }, 90f);
            }else if(mode.equals("share")){
                // 组合节点/组合连接器的「共享哪些部分」配置面板：
                // 点开节点/连接器 → 勾选框截图 → 改一次配置（取消"液体"）→ 再看勾选框有没有跟着变。
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupShareScene, 5f);
                // 弹窗/面板有淡入动画：打开和截图要隔开几秒，否则截到的是 alpha≈0 的空画面
                Timer.schedule(Driver::shareOpenNode, 12f);
                Timer.schedule(() -> shot("share_node_panel"), 19f);
                Timer.schedule(Driver::shareStepToggle, 24f);
                Timer.schedule(() -> shot("share_node_panel_after"), 31f);
                Timer.schedule(Driver::shareOpenConn, 36f);
                Timer.schedule(() -> shot("share_conn_panel"), 43f);
                Timer.schedule(() -> { Log.info("[drv] share 模式结束"); Core.app.exit(); }, 52f);
            }else if(mode.equals("wall")){
                // 组合墙：3 面铜墙成一组 → 打成残血 → 修复投影修 → 截图看还破不破损（裂纹）
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupWallScene, 5f);
                Timer.schedule(() -> shot("wall_damaged"), 16f);
                Timer.schedule(Driver::wallRepairStep, 18f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] wall 模式超时结束"); Core.app.exit(); }, 150f);
            }else if(mode.equals("conv")){
                // 组合传送带：铺一排传送带（一格点几下叠到上限、再整排拖一遍）→ 世界截图（贴图/物品还在不在）
                // + 打开传送带的内容详情（说明里应出现【组合】玩法，图标不能是 error 贴图、不能崩）
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupConvScene, 5f);
                Timer.schedule(() -> shot("conv_world"), 14f);
                Timer.schedule(Driver::convOpenInfo, 16f);
                Timer.schedule(() -> shot("conv_info"), 24f);
                Timer.schedule(Driver::convOpenInfoUnloader, 26f);
                Timer.schedule(() -> shot("conv_info_unloader"), 34f);
                Timer.schedule(() -> { Log.info("[drv] conv 模式结束"); Core.app.exit(); }, 44f);
            }else if(mode.equals("bp")){
                // 蓝图里的组合连接器：写一个含连接器的蓝图 → 重新从磁盘 load() → 打印/截图看还在不在
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupBlueprintScene, 5f);
                Timer.schedule(() -> shot("blueprint_dialog"), 25f);
                Timer.schedule(() -> { Log.info("[drv] bp 模式结束"); Core.app.exit(); }, 30f);
            }else if(mode.equals("veasm")){
                // ve（VanillaExpansion）单位组装厂的"建筑载荷"：要求里的方块实例与世界里/打包机
                // 产出的实例必须是同一个（原版 acceptPayload 用的是 == 身份比较）。
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupVeAsmScene, 5f);
                Timer.schedule(Driver::veAsmCheck, 12f, 2f);
                Timer.schedule(() -> { Log.info("[drv] veasm 模式结束"); Core.app.exit(); }, 30f);
                Timer.schedule(() -> { Log.info("[drv] veasm 超时退出"); Core.app.exit(); }, 180f);
            }else if(mode.equals("rebuild")){
                // 用户报："核心单位修废墟/重建不会立刻全做完，只做几个；批量改传送带方向也一样"。
                // 真客户端走一遍原版 B 键框选（InputHandler.rebuildArea）+ 拖一排原地转向计划。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupRebuildScene, 5f);
                Timer.schedule(Driver::rebuildGo, 12f);
                Timer.schedule(Driver::rebuildStep, 14f, 0.5f);
                Timer.schedule(() -> { Log.info("[drv] rebuild 模式超时结束"); Core.app.exit(); }, 200f);
            }else if(mode.equals("align")){
                // 对照：2x2 原版机器 + 四角 1x1 小方块，量"贴图到底画在哪"
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupAlignScene, 6f);
                Timer.schedule(Driver::hideDialogs, 9f);
                Timer.schedule(() -> shot("align_ref"), 11f);
                Timer.schedule(() -> { Log.info("[drv] align 模式结束"); Core.app.exit(); }, 14f);
            }else if(mode.equals("shadow")){
                // 用户 2026-10-08：浮标旁边再加一个子按钮（Icon.production）= 框选矿物地板 →
                // 画一层阴影；阴影上放下的钻头缩到 1x1（挖矿仍按原来大小每格都盖住了矿物算）。
                // 要看的：① 三个子按钮的排版（不能被挤出屏幕）；② 阴影真的画在矿上；
                //        ③ 阴影上放钻头后是一个 1x1 的小钻头贴在阴影上。
                // 【截图必须按帧排队】软渲染 1~5fps，ScreenUtils 抓的是"上一次渲染完的帧"，
                // 用 Timer 排几步会落在同一帧里，三张图会一模一样（实测踩过）。这里改成
                // 每帧推进一步的流水线（改状态和截图之间隔一帧）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupShadowScene, 6f);
                Timer.schedule(Driver::hideDialogs, 9f);
                Timer.schedule(Driver::startShadowPipeline, 11f);
                Timer.schedule(() -> { Log.info("[drv] shadow 超时结束"); Core.app.exit(); }, 120f);
            }else if(mode.equals("hudbtn")){
                // HUD 悬浮浮标（手机/桌面同一个、可拖动）：点它展开子按钮。
                // 【合体建筑已独立到 merge 项目】现在浮标只剩一个子按钮「矿物阴影」，
                // 这里搭场景 + 跑检查（悬浮/可点/不改 input）+ 截图。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupShadowScene, 6f);
                Timer.schedule(Driver::checkHudButton, 14f);
                Timer.schedule(Driver::prepareSubButtonShot, 16f); // 展开子按钮（截图用）
                Timer.schedule(() -> shot("hudbtn_subbuttons"), 18f);
                Timer.schedule(Driver::toggleMergeSubButtons, 19f); // 收起
                Timer.schedule(() -> { Log.info("[drv] hudbtn 模式结束"); Core.app.exit(); }, 22f);
                Timer.schedule(() -> { Log.info("[drv] hudbtn 超时结束"); Core.app.exit(); }, 120f);
            }else if(mode.equals("rep")){
                // 用户复现存档（stainedMountains）：读档 → 存档 → 再读档，看物品会不会变多。
                // 这些模组（ve 建 GL shader、饱和火力要 Java 25）在 headless 里跑不起来，所以用真客户端。
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupReproScene, 6f);
                Timer.schedule(Driver::reproStep, 20f, 10f);
                Timer.schedule(() -> { Log.info("[drv] rep 模式超时结束"); Core.app.exit(); }, 600f);
            }else if(mode.equals("pwr")){
                // 用户报："每次读写地图后，都要给电网一些刺激（拆一个并入电网的建筑）
                // 才会触发组合建筑与电网的交互更新" —— 读用户存档 ×3 次存读，每次做一遍电网体检。
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupPowerScene, 6f);
                Timer.schedule(Driver::powerStep, 25f, 12f);
                Timer.schedule(() -> { Log.info("[drv] pwr 模式超时结束"); Core.app.exit(); }, 600f);
            }else if(mode.equals("inputguard")){
                // 用户 2026-09-29 崩溃：arc.scene.Element.notify 读 getScene() 为 null（UI 元素在触摸派发中被摘掉）。
                // 这里在**真客户端**里验三件事：① 我们的输入代理真的装进了输入链、且 Scene 没有被派发两遍；
                // ② 正常点击（真·touchDown/touchUp 走输入链）照旧生效；③ 复现那条 NPE 的现场被吞掉、不崩。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::inputGuardSetup, 6f);
                Timer.schedule(Driver::inputGuardChecks, 9f);
                Timer.schedule(() -> shot("inputguard"), 12f);
                Timer.schedule(() -> { Log.info("[drv] inputguard 模式结束"); Core.app.exit(); }, 16f);
                Timer.schedule(() -> { Log.info("[drv] inputguard 超时"); Core.app.exit(); }, 120f);
            }else if(mode.equals("status")){
                // 两块东西一起看：
                //  1) 升华站（sublimate）灌了氰气后的**方块状态**菱形（红=noinput / 绿=active）
                //     —— 开了 renderer.drawStatus 才会画出来，和玩家按 F6 看到的是同一条代码路径；
                //  2) 容器（组合仓库并进核心）的信息面板，看"已并入核心 容量 N"这个数字对不对。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupStatusScene, 5f);
                Timer.schedule(() -> shot("status_world"), 12f);
                Timer.schedule(Driver::openStatusPanel, 14f);
                Timer.schedule(() -> shot("status_panel"), 18f);
                Timer.schedule(() -> { Log.info("[drv] status 模式结束"); Core.app.exit(); }, 22f);
            }else if(mode.equals("mega")){
                // 用户报：mace + oct 组合成"巨兽"后 ①不绘制单位身体 ②力墙 bar 超上限。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupMegaScene, 5f);
                Timer.schedule(() -> shot("mega_before"), 10f);
                Timer.schedule(Driver::megaMerge, 14f);
                Timer.schedule(Driver::megaDumpStep, 20f);
                Timer.schedule(() -> shot("mega_after"), 22f);
                Timer.schedule(Driver::megaTakeControl, 26f);
                Timer.schedule(() -> shot("mega_hud"), 30f);
                Timer.schedule(Driver::megaOpenPanel, 34f);
                Timer.schedule(() -> shot("mega_panel"), 38f);
                // 指挥模式：框选巨兽后命令菜单里的图标（用户报"一直显示 dagger"）
                Timer.schedule(Driver::megaCommandMode, 42f);
                Timer.schedule(() -> shot("mega_command"), 47f);
                Timer.schedule(() -> { Log.info("[drv] mega 模式结束 frames=@", frames); Core.app.exit(); }, 53f);
            }else if(mode.equals("floatpanel")){
                // 用户要求："不要塞进原版信息面板，搞一个大的悬浮面板，跟 js/java 扩展建筑的一样"。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupBigComboScene, 5f);
                Timer.schedule(Driver::bigComboBuild, 8f);
                Timer.schedule(Driver::floatPanelFillAll, 17f);
                Timer.schedule(Driver::floatPanelTap, 18f);
                Timer.schedule(() -> shot("floatpanel_open"), 26f);
                Timer.schedule(Driver::floatPanelSizeReport, 27f);
                Timer.schedule(Driver::floatPanelTapElsewhere, 28f);
                Timer.schedule(() -> shot("floatpanel_after"), 34f);
                Timer.schedule(() -> { Log.info("[drv] floatpanel 模式结束"); Core.app.exit(); }, 38f);
                Timer.schedule(() -> { Log.info("[drv] floatpanel 超时"); Core.app.exit(); }, 200f);
            }else if(mode.equals("bodyshare")){
                // 【用户要求】"给组合体加一个新悬浮面板，可以选择该组合体是否共享物品/液体/电力/热量，
                // 像组合连接器和组合节点那种……不要和显示详细信息的悬浮面板重叠"。
                // 点一台组合体 → 两张悬浮面板都该弹出来（详情 + 共享配置），而且**不能叠在一起**；
                // 勾一下"物品" → 配置要跟着变。这里把两张面板的矩形都打出来当证据。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupBigComboScene, 5f);
                Timer.schedule(Driver::bigComboBuild, 8f);
                Timer.schedule(Driver::floatPanelFillAll, 16f);
                Timer.schedule(Driver::bodyShareTap, 18f);
                Timer.schedule(() -> shot("bodyshare_open"), 26f);
                Timer.schedule(Driver::bodyShareReport, 28f);
                Timer.schedule(Driver::bodyShareToggle, 30f);
                Timer.schedule(() -> shot("bodyshare_toggled"), 38f);
                Timer.schedule(Driver::bodyShareReport, 40f);
                // 新要求：只对自己队伍弹 + 设置里能关
                Timer.schedule(Driver::bodyShareForeign, 42f);
                Timer.schedule(() -> shot("bodyshare_foreign"), 46f);
                Timer.schedule(Driver::bodyShareSettingOff, 48f);
                Timer.schedule(() -> shot("bodyshare_setting_off"), 52f);
                Timer.schedule(() -> { Log.info("[drv] bodyshare 模式结束"); Core.app.exit(); }, 58f);
                Timer.schedule(() -> { Log.info("[drv] bodyshare 超时"); Core.app.exit(); }, 220f);
            }else if(mode.equals("bigcombo")){
                // 用户报的"组合的东西过多时显示面板非常大"：组合体构成列表要放进滚动窗、高度封顶。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupBigComboScene, 5f);
                Timer.schedule(Driver::bigComboBuild, 8f);
                Timer.schedule(Driver::bigComboPanel, 20f);
                Timer.schedule(() -> shot("bigcombo_top"), 26f);
                Timer.schedule(Driver::bigComboScrollBottom, 28f);
                Timer.schedule(() -> shot("bigcombo_bottom"), 34f);
                Timer.schedule(Driver::bigComboReport, 36f);
                Timer.schedule(() -> { Log.info("[drv] bigcombo 模式结束"); Core.app.exit(); }, 40f);
                Timer.schedule(() -> { Log.info("[drv] bigcombo 超时"); Core.app.exit(); }, 200f);
            }else if(mode.equals("beastname")){
                // 巨兽悬浮信息栏里的"类型名"（用户报的"mi2 在游戏上方显示组合巨兽的 type"）：
                // 巨兽派生类型共用一个占位类型，名字是通用名"组合巨兽"；这里换成成员构成。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupMegaScene, 5f);
                Timer.schedule(Driver::megaMerge, 14f);
                Timer.schedule(Driver::beastInspect, 18f);
                Timer.schedule(() -> shot("beastname_vanilla"), 24f);
                Timer.schedule(Driver::beastMi2, 27f);
                Timer.schedule(() -> shot("beastname_mi2"), 34f);
                Timer.schedule(() -> { Log.info("[drv] beastname 模式结束"); Core.app.exit(); }, 40f);
                Timer.schedule(() -> { Log.info("[drv] beastname 超时"); Core.app.exit(); }, 180f);
            }else if(mode.equals("shipmega")){
                // 用户报："两艘船组合后速度变得超级慢 / 没处理水的阻力"。
                // 两艘 risso 在**深水**里合体：看它还在不在水面、速度系数是不是和原版船一致
                // （原版船 1.3；没处理水阻时按普通单位算只有 0.2）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupShipScene, 5f);
                Timer.schedule(Driver::shipMerge, 12f);
                Timer.schedule(Driver::shipReport, 20f);
                Timer.schedule(() -> shot("ship_mega"), 24f);
                Timer.schedule(() -> { Log.info("[drv] shipmega 模式结束 frames=@", frames); Core.app.exit(); }, 30f);
            }else if(mode.equals("duo")){
                // 用户报：dagger + vela 组合后"会发射 vela 治疗武器的子弹"、"碰撞箱好像变了"。
                // 场景：dagger+vela 合体 → 打开碰撞箱显示 → 旁边放一个敌方单位逼它开火 → 截图。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupDuoScene, 5f);
                Timer.schedule(Driver::duoMerge, 12f);
                Timer.schedule(Driver::duoReport, 20f);
                Timer.schedule(() -> shot("duo_fight"), 30f);
                Timer.schedule(Driver::duoReport, 34f);
                Timer.schedule(() -> shot("duo_fight2"), 40f);
                Timer.schedule(() -> { Log.info("[drv] duo 模式结束 frames=@", frames); Core.app.exit(); }, 46f);
            }else if(mode.equals("legs")){
                // 用户报：组合巨兽（成员是腿类单位）没画腿，腿也要按比例放大。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupLegsScene, 5f);
                Timer.schedule(Driver::legsMerge, 12f);
                Timer.schedule(Driver::legsReport, 20f);
                // 参照 spiroct 紧跟着巨兽，同一张图里对比腿的比例（融合半径 160，必须融合后再放）
                Timer.schedule(Driver::legsRefSpawn, 21f);
                Timer.schedule(() -> shot("legs_mega"), 24f);
                Timer.schedule(Driver::legsReport, 28f);
                Timer.schedule(() -> {
                    Log.info("[drv] legs 参照: ref=@ 位置=(@,@) 腿数=@", legsRef == null ? "null" : legsRef.type.name,
                        legsRef == null ? 0 : (int)legsRef.x, legsRef == null ? 0 : (int)legsRef.y,
                        legsRef instanceof mindustry.gen.Legsc l ? l.legs().length : -1);
                    logLegSpan("巨兽", legsMega);
                    logLegSpan("参照spiroct", legsRef);
                }, 30f);
                Timer.schedule(() -> { Log.info("[drv] legs 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("mech")){
                // 机甲类成员（dagger/fortress…）合体：机甲腿也是原版分开画的（drawMech），
                // 一样要按体型放大——和 legs 模式同一套场景，只是把成员换成机甲。
                installFrameCounter();
                installCameraLock();
                keepDialogsHidden();
                Timer.schedule(Driver::setupLegsScene, 5f);
                Timer.schedule(Driver::legsMerge, 12f);
                Timer.schedule(Driver::legsRefSpawn, 21f);
                Timer.schedule(() -> shot("mech_mega"), 24f);
                Timer.schedule(() -> {
                    Log.info("[drv] mech 巨兽: attKind=@ 部件状态: 行走相位=@ baseRotation=@ hitSize=@",
                        field(legsMega, "attKind"), field(legsMega, "mechWalkTime"),
                        field(legsMega, "legBaseRotation"), legsMega == null ? -1f : legsMega.hitSize());
                    Log.info("[drv] mech 参照: @ hitSize=@", legsRef == null ? "null" : legsRef.type.name,
                        legsRef == null ? -1f : legsRef.hitSize());
                }, 28f);
                Timer.schedule(() -> { Log.info("[drv] mech 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("mp")){
                // 真联机：连接到 verify/run-mp.sh 起的 headless 服务器，看**服务端造/融合/解体单位**
                // 之后客户端这边到底同步成了什么样（单位在不在、成员数对不对、有没有幽灵残留）。
                installFrameCounter();
                keepDialogsHidden();
                // 【镜头必须自己钉住】客户端默认是"相机跟随玩家单位"，而玩家在核心那边、
                // 巨兽在地图另一头 —— 不钉镜头，截图永远拍不到巨兽（早先几轮联机截图就是
                // "全是水/全是基地"，看着像"客户端不显示巨兽"，其实是根本没框进画面）。
                Core.settings.put("detach-camera", true);
                installCameraLock();
                int mpSecs = Integer.parseInt(System.getProperty("drv.mpSeconds", "100"));
                Timer.schedule(Driver::mpConnect, 4f);
                Timer.schedule(Driver::mpReport, 8f, 1f, mpSecs);
                Timer.schedule(() -> shot("mp_joined"), 14f);
                // 每 0.25 秒也补一次（installCameraLock 走 HUD 帧更新，双保险）
                Timer.schedule(Driver::lookAtMega, 20f, 0.25f, 44 * 4);
                // 【客户端自己发起合体】服务端 t≈8 秒会在别处放两只 dagger，这里走命令面板那条
                // 入口（UnitComboMerge.requestMergeSelected）请求合体 —— 复现"客户端合体变幽灵"。
                // 每 3 秒试一次，最多 4 次（t≈13~22s）：要赶在剧本自己的 split（t=20/43s）之前，
                // 否则客户端这次请求会把"服务端刚拆出来的瞬态"又合掉，1 秒一次的采样就抓不到那个
                // 阶段状态了（那只说明判定的时间撞车，不是同步 bug）。
                Timer.schedule(Driver::mpRequestMerge, 13f, 3f, 4);
                // 服务端 t≈72s 会摆好"节点乱连"基地，之后由**客户端**每 3 秒随机连一根线/断一根线
                // （走玩家点击那条原版配置通道 Call.tileConfig），复现"客户端瞎连 → 物品异常增长/减少"
                Timer.schedule(Driver::mpNodeMess, 80f, 3f, 40);
                Timer.schedule(() -> shot("mp_mega"), 26f);
                Timer.schedule(() -> shot("mp_mega2"), 50f);
                Timer.schedule(() -> shot("mp_after_split"), 66f);
                // 服务端 t≈58（≈客户端 t≈62）会合一只矿工巨兽并让它挖矿：这张图用来看**挖矿光束**
                // 收尾的"挖矿光束"取证：服务端的挖矿阶段按真实秒排（t≈58 起），而客户端 Timer 按帧算，
                // 帧率漂移下拍不准某一秒 —— 干脆从 t=50s 起每 8 秒拍一张（连拍 6 张），总会拍到挖矿那一刻。
                Timer.schedule(() -> shot("mp_mining"), 50f, 8f, 6);
                Timer.schedule(Driver::mpVerdict, mpSecs - 2);
                Timer.schedule(() -> { Log.info("[drv] mp 模式结束 frames=@", frames); Core.app.exit(); }, mpSecs);
            }else if(mode.equals("userpanel")){
                // 用户报："组合**工厂**物品面板…清空所有物资"。直接读用户存档，挑几台装满货的
                // 组合工厂把信息面板弹出来截图，并把面板里的文字（Bar 的 label）打进日志。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::loadUserSave, 5f);
                Timer.schedule(Driver::userPanelShot0, 40f);
                Timer.schedule(Driver::userPanelShot1, 52f);
                Timer.schedule(() -> { Log.info("[drv] userpanel 模式结束 frames=@", frames); Core.app.exit(); }, 60f);
            }else if(mode.equals("pool")){
                // 用户报："组合建筑物品面板…清空所有物资""组合工厂物资满后有时也会莫名其妙清空物品"。
                // 场景：4 台并排的组合发电机（共享一口池子）灌满燃料 → 开信息面板截图 →
                // 拆掉其中一台成员（原来会把超出新容量的那份真删掉）→ 再看面板/池子。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupPoolScene, 5f);
                Timer.schedule(Driver::poolFill, 10f);
                Timer.schedule(Driver::poolOpenPanel, 14f);
                Timer.schedule(() -> shot("pool_panel_full"), 18f);
                Timer.schedule(Driver::poolBreakOne, 22f);
                Timer.schedule(Driver::poolOpenPanel, 26f);
                Timer.schedule(() -> shot("pool_panel_after"), 30f);
                // 用户报的"工厂资源间歇性清零"：存档标志 saving 会多留 3 个时间单位，
                // 那段时间里发的联机快照原来被当成存档 → 非组长写空模块 → 客户端整组清零。
                Timer.schedule(Driver::poolSnapshotWhileSavingCheck, 12f);
                Timer.schedule(() -> { Log.info("[drv] pool 模式结束 frames=@", frames); Core.app.exit(); }, 36f);
            }else if(mode.equals("tech")){
                // 科技树：真的把 ResearchDialog 打开（用户报"打开科技树崩溃"），
                // 顺带按原版 canSpend 的写法把整棵树解引用一遍，报出是哪个节点坏。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::techScan, 5f);
                Timer.schedule(() -> {
                    try{
                        Vars.ui.research.show();
                        Log.info("[drv] 科技树已打开");
                    }catch(Throwable t){ Log.err("[drv] 打开科技树失败", t); }
                }, 7f);
                Timer.schedule(() -> shot("tech_tree"), 12f);
                Timer.schedule(Driver::techScan, 13f);
                Timer.schedule(() -> { Log.info("[drv] tech 模式结束（frames=@）", frames); Core.app.exit(); }, 16f);
            }else if(mode.equals("tech2")){
                // 进图之后再开科技树：WorldLoadEvent 上本模组会按星球换造价 + 扫一遍树，
                // 这条路径和"主菜单直接开树"不一样，用户是在游戏里点开的。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(Driver::setupTechWorld, 5f);
                Timer.schedule(Driver::techScan, 20f);
                Timer.schedule(Driver::openTechRoots, 22f);
                Timer.schedule(Driver::techScan, 55f);
                Timer.schedule(() -> { Log.info("[drv] tech2 模式结束（frames=@）", frames); Core.app.exit(); }, 58f);
            }else if(mode.equals("technode")){
                // 只关心"三个方块在不在两棵树上、图标画得对不对"：切到塞普罗/埃里克尔树，
                // 把镜头居中到该节点再截图（树很大，不居中的话节点在屏幕外）。
                installFrameCounter();
                Timer.schedule(Driver::hideDialogs, 3f);
                Timer.schedule(() -> {
                    // 三个方块的节点在"没解锁前置物品"时会显示锁图标（原版行为），
                    // 这里把它们的材料解锁掉，让节点画出方块本身的样子，方便对图。
                    for(Item it : new Item[]{Items.copper, Items.lead, Items.silicon, Items.metaglass,
                        Items.beryllium, Items.tungsten, Items.oxide}){
                        it.unlock();
                    }
                    Vars.ui.research.show();
                    Timer.schedule(() -> Vars.ui.research.rebuildTree(Blocks.coreShard.techNode), 1f);
                    Timer.schedule(() -> Driver.treeHasOurNodes("打开后（未切换）"), 2.5f);
                    Timer.schedule(() -> centerTree("connection"), 3f);
                    Timer.schedule(Driver::locateTechNodes, 5f);
                    Timer.schedule(() -> shot("serpulo_connection"), 6f);
                    Timer.schedule(() -> centerTree("liquid-unloader"), 7f);
                    Timer.schedule(Driver::locateTechNodes, 9f);
                    Timer.schedule(() -> shot("serpulo_unloader"), 10f);
                    Timer.schedule(() -> Vars.ui.research.rebuildTree(Blocks.coreBastion.techNode), 11f);
                    Timer.schedule(() -> centerTree("connection"), 13f);
                    Timer.schedule(Driver::locateTechNodes, 15f);
                    Timer.schedule(() -> shot("erekir_connection"), 16f);
                    Timer.schedule(() -> centerTree("liquid-unloader"), 17f);
                    Timer.schedule(Driver::locateTechNodes, 19f);
                    Timer.schedule(() -> shot("erekir_unloader"), 20f);
                    Timer.schedule(() -> { Log.info("[drv] technode 模式结束"); Core.app.exit(); }, 23f);
                }, 6f);
            }else{
                Timer.schedule(Driver::step1, 4f);
            }
        });
    }

    static final String mode = System.getProperty("drv.mode", "list");

    // ---------------- 组合巨兽（mace + oct 融合） ----------------
    static Unit megaUnit, maceUnit, octUnit, octUnit2, polyUnit;
    static int megaPhase = 0, megaFrames = -1;

    static Object combineCall(String cls, String method, Class<?>[] sig, Object... args){
        try{
            // 单位侧机制（组合巨兽/共享承伤…）已经拆到 combineunit 模组：按名字前缀选它的类加载器
            // （没装 combineunit 时退回 combine 的加载器 → ClassNotFoundException，调用方自己兜）
            ClassLoader loader = cls.startsWith("combineunit.") ? unitMl() : ml;
            Class<?> c = Class.forName(cls, true, loader);
            java.lang.reflect.Method m = sig == null ? null : c.getMethod(method, sig);
            if(m == null) m = c.getMethod(method);
            m.setAccessible(true);
            return m.invoke(null, args);
        }catch(Throwable t){ Log.err("[drv] 调用 @.@ 失败", cls, method, t); return null; }
    }

    /** 单位侧机制（组合巨兽…）在 combineunit 模组里：优先用它的类加载器，没装则退回 combine 的。 */
    static ClassLoader unitMl(){
        try{
            var m = Vars.mods.getMod("combineunit");
            if(m != null && m.main != null) return m.main.getClass().getClassLoader();
        }catch(Throwable ignored){}
        return ml;
    }

    static void setupMegaScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 找一块陆地（别让 mace 落水里淹死）
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<125;y++){
                for(int x=35;x<190;x++){
                    boolean ok = true;
                    for(int dy=-1;dy<=1 && ok;dy++) for(int dx=-2;dx<=2;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] mega 没找到陆地"); return; }

            // 队伍得有核心，不然游戏会把队伍当成已出局、清掉单位
            Building core = placeBL(Blocks.coreShard, ox + 20, oy + 12);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            megaOx = ox; megaOy = oy;
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::megaSpawn, 2f);
            Timer.schedule(Driver::megaMerge, 5f);
        }catch(Throwable t){ Log.err("[drv] setupMegaScene failed", t); }
    }

    static int megaOx = -1, megaOy = -1;

    static void megaSpawn(){
        try{
            float cx = megaOx * 8f, cy = megaOy * 8f;
            maceUnit = UnitTypes.mace.create(Team.sharded);
            maceUnit.set(cx - 20f, cy);
            maceUnit.add();
            octUnit = UnitTypes.oct.create(Team.sharded);
            octUnit.set(cx + 30f, cy);
            octUnit.add();
            // 第二台带力场的成员：力场必须合并成一份，否则"力墙条"用单个成员的上限去除全组盾量（超 100%）
            octUnit2 = UnitTypes.oct.create(Team.sharded);
            octUnit2.set(cx + 70f, cy + 20f);
            octUnit2.add();
            // poly：工程/采矿单位 —— 自动重建 / 辅助建造 / 挖矿 这些指令都挂在它身上，
            // 合体后指令表必须继承（用户报的"poly 合体后这些命令消失"）
            polyUnit = UnitTypes.poly.create(Team.sharded);
            polyUnit.set(cx - 60f, cy + 20f);
            polyUnit.add();
            Core.camera.position.set(cx, cy);
            Log.info("[drv] mega 场景: 陆地=@,@ mace=@(@, @) oct=@(@, @) Groups.unit=@ frames=@", megaOx, megaOy,
                maceUnit.type.name, (int)maceUnit.x, (int)maceUnit.y, octUnit.type.name, (int)octUnit.x, (int)octUnit.y,
                Groups.unit.count(u -> true), frames);
        }catch(Throwable t){ Log.err("[drv] megaSpawn failed", t); }
    }

    static void megaDumpStep(){
        megaDump("融合后");
        arc.math.geom.Vec2 sp = Core.camera.project(megaUnit == null ? 0 : megaUnit.x, megaUnit == null ? 0 : megaUnit.y);
        Log.info("[drv] 屏幕坐标=@,@ 相机=@,@ frames=@ 力墙条: @", (int)sp.x, (int)sp.y,
            (int)Core.camera.position.x, (int)Core.camera.position.y, frames, shieldBar());
    }

    static void megaTakeControl(){
        if(megaUnit != null) Vars.player.unit(megaUnit);
        if(megaUnit != null) Core.camera.position.set(megaUnit.x, megaUnit.y);
        installCameraLock();
        Log.info("[drv] 玩家单位=@ frames=@", Vars.player.unit() == null ? "null" : Vars.player.unit().type.name, frames);
    }

    /** 力墙条比例 = 盾量 / 力场上限（原版 HUD 与信息面板都用这个数）。 */
    // ---------------- 组合发电机共享池（物品面板 + 满池拆一台） ----------------
    static arc.struct.Seq<Building> poolScene = new arc.struct.Seq<>();
    static Building poolMain;

    static int worldItems(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }

    // ---------------- 用户存档：组合工厂的信息面板 ----------------
    static arc.struct.Seq<Building> userFactories = new arc.struct.Seq<>();

    static void loadUserSave(){
        try{
            arc.files.Fi dir = Vars.saveDirectory;
            arc.files.Fi file = null;
            for(arc.files.Fi f : dir.list()){
                if(!f.name().endsWith(".msav") || f.name().contains("backup")) continue;
                if(f.name().contains("泰伯利亚-4")) file = f;
            }
            if(file == null){
                for(arc.files.Fi f : dir.list()) if(f.name().endsWith(".msav")) file = f;
            }
            if(file == null){ Log.err("[drv] userpanel: 没找到存档（@）", dir.absolutePath()); return; }
            Log.info("[drv] userpanel: 读档 @", file.name());
            mindustry.io.SaveIO.load(file);
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            Log.info("[drv] userpanel: 地图=@ 建筑=@", Vars.state.map.name(), Vars.state.teams.get(Team.sharded).buildings.size);

            // 收集装满货的组合工厂（同一份模块只留一台代表）
            java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Building> reps = new java.util.IdentityHashMap<>();
            for(Tile t : Vars.world.tiles){
                Building b = t == null ? null : t.build;
                if(b == null || !b.isValid() || b.items == null || b.items.total() <= 0) continue;
                String cn = b.getClass().getName();
                if(!cn.startsWith("combine.production.CombinedCrafter")
                    && !cn.startsWith("combine.units.CombinedUnitFactory")) continue;
                reps.put(b.items, b);
            }
            for(var e : reps.entrySet()) userFactories.add(e.getValue());
            userFactories.sort(b -> -b.items.total());
            Log.info("[drv] userpanel: 有货的组合工厂池子 @ 口", userFactories.size);
            for(int i = 0; i < Math.min(6, userFactories.size); i++){
                Building b = userFactories.get(i);
                Log.info("[drv]   #" + i + " " + b.block.name + "@" + b.tileX() + "," + b.tileY()
                    + " 类=" + b.getClass().getSimpleName() + " 池总=" + b.items.total()
                    + " 单台容量=" + b.block.itemCapacity + " 验收上限=" + b.getMaximumAccepted(Vars.content.item(0)));
            }
        }catch(Throwable t){ Log.err("[drv] loadUserSave failed", t); }
    }

    /** 把第 idx 台组合工厂的信息面板弹出来（原版 display 的那套），截图 + 打印面板文字。 */
    static void userPanelOpen(int idx){
        try{
            if(idx >= userFactories.size){ Log.info("[drv] userpanel: 没有第 @ 台", idx); return; }
            Building b = userFactories.get(idx);
            if(b == null || !b.isValid()){ Log.info("[drv] userpanel: 第 @ 台已失效", idx); return; }
            Core.camera.position.set(b.x, b.y);
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            b.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(b, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合工厂信息面板: " + b.block.name);
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] userpanel #" + idx + " 面板已弹出: " + b.block.name + "@" + b.tileX() + "," + b.tileY()
                + " 池总=" + (b.items == null ? -1 : b.items.total()));
            userPanel = panel;
        }catch(Throwable t){ Log.err("[drv] userPanelOpen failed", t); }
    }

    static arc.scene.ui.layout.Table userPanel;

    /** 面板显示一帧后，把里面的文字（Bar / Label）抠出来打进日志。 */
    static void userPanelTexts(String tag){
        try{
            if(userPanel == null) return;
            arc.struct.Seq<String> out = new arc.struct.Seq<>();
            collectTexts(userPanel, out);
            StringBuilder sb = new StringBuilder();
            for(String s : out) sb.append('「').append(s).append('」');
            Log.info("[drv] userpanel 面板文字（@）: @", tag, sb.length() == 0 ? "（空）" : sb.toString());
        }catch(Throwable t){ Log.err("[drv] userPanelTexts failed", t); }
    }

    static void collectTexts(arc.scene.Element e, arc.struct.Seq<String> out){
        if(e == null) return;
        if(e instanceof arc.scene.ui.Label l && l.getText() != null){
            String s = l.getText().toString().trim();
            if(!s.isEmpty()) out.add(s);
        }
        if(e instanceof arc.scene.Group g) for(arc.scene.Element c : g.getChildren()) collectTexts(c, out);
    }

    static void userPanelShot0(){ userPanelOpen(0); Timer.schedule(() -> { userPanelTexts("#0"); shot("user_factory_panel0"); }, 2f); }
    static void userPanelShot1(){ userPanelOpen(2); Timer.schedule(() -> { userPanelTexts("#2"); shot("user_factory_panel2"); }, 2f); }

    static String poolInfo(String tag){
        if(poolMain == null || !poolMain.isValid()) return tag + ": 主建筑没了";
        StringBuilder sb = new StringBuilder();
        sb.append(tag).append(": ").append(poolMain.block.name).append("@" ).append(poolMain.tileX()).append(',').append(poolMain.tileY())
          .append(" 组容量=").append(poolMain.getMaximumAccepted(Items.copper))
          .append(" 池总量=").append(poolMain.items == null ? -1 : poolMain.items.total())
          .append(" 煤=").append(poolMain.items == null ? -1 : poolMain.items.get(Items.coal))
          .append(" 同池台数=").append(poolGroupCount())
          .append(" 世界物品总量=").append(worldItems());
        return sb.toString();
    }

    static int poolGroupCount(){
        java.util.IdentityHashMap<Building, Boolean> seen = new java.util.IdentityHashMap<>();
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items != poolMain.items) continue;
            seen.put(t.build, Boolean.TRUE);
        }
        return seen.size();
    }

    static void setupPoolScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 找一块 6x4 的空地
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<110;y++){
                for(int x=35;x<180;x++){
                    boolean ok = true;
                    for(int dy=0;dy<3 && ok;dy++) for(int dx=0;dx<5;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] pool 没找到空地"); return; }

            Block gen = null;
            for(Block b : Vars.content.blocks())
                if(b.getClass().getName().equals("combine.production.CombinedGenerator") && b.size == 1 && b.hasItems){ gen = b; break; }
            if(gen == null){ Log.err("[drv] pool 没找到组合发电机"); return; }
            Log.info("[drv] pool 场景: 方块=@(@) 原点=@,@", gen.name, gen.getClass().getSimpleName(), ox, oy);
            for(int i = 0; i < 4; i++){
                Building b = placeBL(gen, ox + i, oy);
                if(b != null) poolScene.add(b);
            }
            poolMain = poolScene.isEmpty() ? null : poolScene.first();
            // 旁边放电源，免得发电机因为没电不工作
            Block src = null;
            for(Block b : Vars.content.blocks()) if(b.name.equals("power-source")) src = b;
            if(src != null) placeBL(src, ox + 1, oy + 2);
            Vars.player.unit(null);
            Core.camera.position.set((ox + 1.5f) * 8f, oy * 8f);
            Log.info("[drv] pool 场景就绪: @", poolInfo("放下后"));
        }catch(Throwable t){ Log.err("[drv] setupPoolScene failed", t); }
    }

    static void poolFill(){
        try{
            if(poolMain == null) return;
            int cap = poolMain.getMaximumAccepted(Items.coal);
            // 按验收上限"灌满"（游戏里 acceptItem 就是按这个上限放行的），
            // 不越过上限 —— 越限属于测试自己造出来的非现实状态
            if(poolMain.items != null) poolMain.items.set(Items.coal, cap);
            Log.info("[drv] pool 灌满: @", poolInfo("灌满后"));
        }catch(Throwable t){ Log.err("[drv] poolFill failed", t); }
    }

    /**
     * 用户报的"工厂资源间歇性清零"（原版 {@code Saves.update()} 里 saving=true 之后要 3 个
     * 时间单位才复位，而方块快照每 snapshotInterval 发一次，正好会掉进这段窗口）：
     * 模拟"正在存档"的同时取一份追随者的 writeSync 字节（= 服务端发的快照），
     * 再 readSync 回组长身上 —— 整组池子必须一份不少（修前会变成空的）。
     */
    static void poolSnapshotWhileSavingCheck(){
        try{
            // 挑两台**真的共用同一份物品模块**的成员（并排的组合发电机会共池；
            // 拆过一台之后剩下的可能已经各自重塑过，不能随便取首尾）
            Building leader = null, follower = null;
            for(int i = 0; i < poolScene.size && leader == null; i++){
                Building a = poolScene.get(i);
                if(a == null || !a.isValid() || a.items == null) continue;
                for(int j = 0; j < poolScene.size; j++){
                    Building b = poolScene.get(j);
                    if(b == null || !b.isValid() || b.items == null || b == a) continue;
                    if(a.items == b.items){ leader = a; follower = b; break; }
                }
            }
            if(leader == null) { Log.err("[drv] FAIL pool 场景里没有共用同一份池子的两台"); return; }
            int before = leader.items == null ? -1 : leader.items.total();
            boolean shared = leader.items == follower.items;
            // 把 Saves.saving 设成 true（模拟存档残留窗口）
            Object saves = Vars.control == null ? null : Vars.control.saves;
            java.lang.reflect.Field sf = null;
            for(Class<?> k = saves == null ? null : saves.getClass(); k != null && sf == null; k = k.getSuperclass()) {
                try { sf = k.getDeclaredField("saving"); } catch (NoSuchFieldException e) { }
            }
            boolean setOk = false;
            if(sf != null) { sf.setAccessible(true); sf.setBoolean(saves, true); setOk = true; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            arc.util.io.Writes w = new arc.util.io.Writes(new java.io.DataOutputStream(bos));
            follower.writeSync(w);
            byte[] snap = bos.toByteArray();
            // readSync 回来（客户端拿到快照做的事）：整组池子必须没被清零
            arc.util.io.Reads r = new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(snap)));
            follower.readSync(r, follower.version());
            if(sf != null) sf.setBoolean(saves, false);
            int after = leader.items == null ? -1 : leader.items.total();
            Log.info("[drv] 存档窗口内的快照: saving 置真=@ 同池=@ 快照字节=@ 池子 @ → @", setOk, shared, snap.length, before, after);
            if(shared && before > 0 && after == before)
                Log.info("[drv] PASS 存档窗口里发的联机快照仍然带真实池子（客户端不会被清零）");
            else
                Log.err("[drv] FAIL 存档窗口里的快照把池子清空了（@ → @）", before, after);
        }catch(Throwable t){ Log.err("[drv] poolSnapshotWhileSavingCheck failed", t); }
    }

    static void poolOpenPanel(){
        try{
            if(poolMain == null || !poolMain.isValid()) return;
            Log.info("[drv] pool 面板前: @", poolInfo("要看面板"));
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            poolMain.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(poolMain, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合发电机（组合体共享池）信息面板");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] pool 面板已弹出（子元素=@）", panel.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] poolOpenPanel failed", t); }
    }

    static void poolBreakOne(){
        try{
            if(poolScene.size < 2) return;
            // 拆"最后一台"：剩下的成员仍然相邻（整组还连着），能看清"容量变小但库存不丢"
            Building victim = poolScene.peek();
            Log.info("[drv] pool 拆前: @", poolInfo("拆前"));
            if(victim != null && victim.isValid()) victim.tile.setBlock(Blocks.air);
            Timer.schedule(() -> Log.info("[drv] pool 拆后: @", poolInfo("拆后")), 1f);
        }catch(Throwable t){ Log.err("[drv] poolBreakOne failed", t); }
    }

    static String shieldBar(){
        if(megaUnit == null) return "无巨兽";
        int fields = 0;
        float max = 0f, scaled = 0f;
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff){
                fields++;
                max += ff.max;
                scaled += ff.scaledMax(megaUnit);
            }
        }
        return "力场数=" + fields + " 盾=" + megaUnit.shield() + " max合计=" + max + " scaledMax合计=" + scaled
            + " 第一条bar比例=" + firstShieldRatio();
    }

    static String firstShieldRatio(){
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff)
                return (megaUnit.shield() / ff.max) + "（上限=" + ff.max + "）";
        }
        return "无";
    }

    /** 截图时想锁定的单位（默认是 mega 模式那台巨兽）。 */
    static Unit camTarget;
    /** 客户端请求合体的重试次数（联机里客户端实体同步可能慢半拍）。 */
    static int mpReqTries = 0;

    /** 每帧把相机钉在目标单位身上（llvmpipe 下相机跟随会跟丢，截图就看不到单位）。 */
    static void installCameraLock(){
        var t = new arc.scene.ui.layout.Table();
        t.touchable = arc.scene.event.Touchable.disabled;
        t.update(() -> {
            // camTarget 优先；其次是大模式自己造的巨兽；最后按类名找（联机模式：巨兽是服务端同步过来的，
            // 没有本地字段可指）—— 不这么兜底，联机截图会拍到"镜头跟着玩家、巨兽在画面外"。
            // 优先正在挖矿的巨兽（收尾那张"挖矿光束"取证图要拍它），其次本地大模式的巨兽，最后按类名找
            Unit u = miningMega();
            if(u == null) u = camTarget != null ? camTarget : (megaUnit != null ? megaUnit : megaUnit());
            if(u != null && u.isAdded()) Core.camera.position.set(u.x, u.y);
        });
        Vars.ui.hudGroup.addChild(t);
    }

    static void megaOpenPanel(){
        try{
            if(megaUnit == null) return;
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            megaUnit.type.display(megaUnit, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合巨兽信息面板");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 巨兽信息面板已弹出（子元素=@）frames=@", panel.getChildren().size, frames);
        }catch(Throwable t){ Log.err("[drv] megaOpenPanel failed", t); }
    }

    /**
     * 进指挥模式、把巨兽塞进选择集（= 玩家框选它），把命令菜单截图下来。
     * 面板里的单位图标走的是 content.unit(unit.type.id).uiIcon —— 派生类型共用基础巨兽的占位 id，
     * 所以这里顺手把"面板实际会用的那个类型/图标"打出来对账。
     */
    // ---------------- 腿类单位（spiroct 等）合体：腿 ----------------
    static Unit legsMega, legsA, legsB, legsRef;
    static int legsOx = -1, legsOy = -1;

    static void setupLegsScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<120;y++){
                for(int x=40;x<190;x++){
                    boolean ok = true;
                    for(int dy=-3;dy<=3 && ok;dy++) for(int dx=-4;dx<=4;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] legs: 没找到陆地"); return; }
            legsOx = ox; legsOy = oy;
            Building core = placeBL(Blocks.coreShard, ox + 18, oy + 10);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::legsSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupLegsScene failed", t); }
    }

    static void legsSpawn(){
        try{
            float cx = legsOx * 8f, cy = legsOy * 8f;
            if("mech".equals(mode)){
                // mech 模式：换成一中一小两台机甲（成员构成不同、hitSize 不同，缩放才看得出来）
                legsA = UnitTypes.dagger.create(Team.sharded);
                legsA.set(cx - 20f, cy);
                legsA.add();
                legsB = UnitTypes.fortress.create(Team.sharded);
                legsB.set(cx + 20f, cy);
                legsB.add();
                Log.info("[drv] mech 场景: dagger + fortress 已就位");
                return;
            }
            legsA = UnitTypes.spiroct.create(Team.sharded);
            legsA.set(cx - 20f, cy);
            legsA.add();
            legsB = UnitTypes.arkyid.create(Team.sharded);
            legsB.set(cx + 20f, cy);
            legsB.add();
            Log.info("[drv] legs 场景: spiroct + arkyid 已就位（腿=@/@ 段）",
                UnitTypes.spiroct.legCount, UnitTypes.arkyid.legCount);
        }catch(Throwable t){ Log.err("[drv] legsSpawn failed", t); }
    }

    /** 融合**之后**再放一只参照 spiroct 到巨兽旁边（融合半径内的会被卷进去，所以必须后放）。 */
    static void legsRefSpawn(){
        try{
            if(legsMega == null || !legsMega.isValid()){ Log.err("[drv] legsRefSpawn: 巨兽没了"); return; }
            // 挪到场景开头挑好的那块空地（离核心 18 格），免得巨兽正好站在核心上、贴图互相糊住
            legsMega.set(legsOx * 8f, legsOy * 8f);
            // 相机：关掉"跟随玩家单位"，让 installCameraLock 每帧把镜头钉在巨兽身上；
            // 缩放调大（1.3）保证整只巨兽连同放大后的腿都在画面里。
            Core.settings.put("detach-camera", true);
            Vars.renderer.setScale(1.3f);
            Core.camera.position.set(legsMega.x, legsMega.y);
            legsRef = ("mech".equals(mode) ? UnitTypes.fortress : UnitTypes.spiroct).create(Team.sharded);
            legsRef.set(legsMega.x - 110f, legsMega.y);
            legsRef.add();
            var under = Vars.world.buildWorld(legsMega.x, legsMega.y);
            Log.info("[drv] legs 参照物已放: 位置=(@,@) 巨兽脚下建筑=@ 相机=(@,@)",
                (int)legsRef.x, (int)legsRef.y, under == null ? "无" : under.block.name,
                (int)Core.camera.position.x, (int)Core.camera.position.y);
        }catch(Throwable t){ Log.err("[drv] legsRefSpawn failed", t); }
    }

    static void legsMerge(){
        try{
            Object merged = legsA == null ? null
                : combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, legsA);
            if(merged instanceof Unit u) legsMega = u;
            Log.info("[drv] legs 融合结果=@", merged);
        }catch(Throwable t){ Log.err("[drv] legsMerge failed", t); }
    }

    /** 打一只腿类单位的"腿展"（每根腿的 base 相对身体中心的距离），用来对账缩放比例。 */
    static void logLegSpan(String tag, Unit u){
        if(u == null || !(u instanceof mindustry.gen.Legsc l)){ Log.info("[drv] @ 不是腿类单位", tag); return; }
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < l.legs().length; i++){
            var leg = l.legs()[i];
            sb.append(String.format(" %.1f", arc.math.Mathf.dst(u.x, u.y, leg.base.x, leg.base.y)));
        }
        Log.info("[drv] @ 腿展(中心到脚)=@ hitSize=@ 腿数=@", tag, sb.toString(), u.hitSize(), l.legs().length);
    }

    static void legsReport(){
        try{
            if(legsMega == null || !legsMega.isValid()){ Log.info("[drv] legs: 巨兽没了"); return; }
            Unit u = legsMega;
            camTarget = u;
            Core.camera.position.set(u.x, u.y);
            Object dom = field(u.getClass(), u, "dominant");
            Log.info("[drv] legs 巨兽: type=@ hitSize=@ 代表类型=@(hitSize=@) 贴图缩放=@ dom.legRegion=@ dom.legCount=@",
                u.type.name, u.hitSize(), dom instanceof UnitType ? ((UnitType)dom).name : "-",
                dom instanceof UnitType ? ((UnitType)dom).hitSize : -1f,
                dom instanceof UnitType ? (u.hitSize() / ((UnitType)dom).hitSize) : -1f,
                dom instanceof UnitType ? regionName(((UnitType)dom).legRegion) : "-",
                dom instanceof UnitType ? ((UnitType)dom).legCount : -1);
            Log.info("[drv] legs 巨兽是不是 Legsc=" + (u instanceof mindustry.gen.Legsc)
                + " 成员数=" + memberCount(u));
            try{
                Object att = field(u.getClass(), u, "attKind");
                Object legs = field(u.getClass(), u, "legs");
                int n = legs instanceof Object[] a ? a.length : -1;
                StringBuilder sb = new StringBuilder();
                if(legs instanceof Object[] a){
                    for(int i = 0; i < Math.min(n, 6); i++){
                        var l = (mindustry.entities.Leg)a[i];
                        sb.append(" [").append(i).append(" base=").append((int)l.base.x).append(",").append((int)l.base.y)
                          .append(" joint=").append((int)l.joint.x).append(",").append((int)l.joint.y).append("]");
                    }
                }
                Log.info("[drv] legs 部件: attKind=@ legs.length=@ 单位位置=(@,@) 腿=@", att, n, (int)u.x, (int)u.y, sb.toString());
            }catch(Throwable t){ Log.err("[drv] legs 部件报告失败", t); }
        }catch(Throwable t){ Log.err("[drv] legsReport failed", t); }
    }

    // ---------------- dagger + vela（地面组合）：武器 + 碰撞箱 ----------------
    static Unit duoMega, duoDagger, duoVela, duoEnemy, duoAlly;
    static int duoOx = -1, duoOy = -1;

    static void setupDuoScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            // 关掉"设置里的绘制碰撞箱" → 画出 hitbox（看碰撞箱到底多大、和身体对不对得上）
            Core.settings.put("drawhitboxes", true);
            int ox = -1, oy = -1;
            outer:
            for(int y=45;y<120;y++){
                for(int x=40;x<190;x++){
                    boolean ok = true;
                    for(int dy=-2;dy<=2 && ok;dy++) for(int dx=-3;dx<=3;dx++){
                        Tile t = Vars.world.tile(x+dx, y+dy);
                        if(t == null || t.floor() == null || t.floor().isLiquid || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] duo: 没找到陆地"); return; }
            duoOx = ox; duoOy = oy;
            Building core = placeBL(Blocks.coreShard, ox + 16, oy + 10);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            Core.camera.position.set(ox * 8f, oy * 8f);
            Timer.schedule(Driver::duoSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupDuoScene failed", t); }
    }

    static void duoSpawn(){
        try{
            float cx = duoOx * 8f, cy = duoOy * 8f;
            duoDagger = UnitTypes.dagger.create(Team.sharded);
            duoDagger.set(cx - 20f, cy);
            duoDagger.add();
            duoVela = UnitTypes.vela.create(Team.sharded);
            duoVela.set(cx + 20f, cy);
            duoVela.add();
            // 敌方靶子：贴近一点（60 单位）逼它开火；看它到底发的是光束还是子弹、打谁
            duoEnemy = UnitTypes.dagger.create(Team.crux);
            duoEnemy.set(cx + 60f, cy);
            duoEnemy.add();
            // 自己人：故意打残（40% 血）—— vela 的维修光束（RepairBeamWeapon）本来只治它
            duoAlly = UnitTypes.dagger.create(Team.sharded);
            duoAlly.set(cx, cy + 40f);
            duoAlly.add();
            duoAlly.health(duoAlly.maxHealth() * 0.4f);
            Core.camera.position.set(cx + 40f, cy);
            Log.info("[drv] duo 场景: dagger+vela 已就位，敌方靶子 @,@ 伤员 @,@（血 @%）",
                (int)duoEnemy.x, (int)duoEnemy.y, (int)duoAlly.x, (int)duoAlly.y, (int)(duoAlly.healthf() * 100));
        }catch(Throwable t){ Log.err("[drv] duoSpawn failed", t); }
    }

    static void duoMerge(){
        try{
            Object merged = duoDagger == null ? null
                : combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, duoDagger);
            if(merged instanceof Unit u){
                duoMega = u;
                camTarget = u;
                installCameraLock();
                // 直接按住扳机（controllable 武器只有玩家扣扳机才开火；autoTarget 的维修光束
                // 会用自己的索敌覆盖 mount.shoot，所以这里只影响那些"要玩家瞄"的武器）
                installTrigger(u, duoEnemy);
                Log.info("[drv] duo 融合后立刻: 类=@ 成员=@ 挂座=@ 能力=@ 有武器=@", u.getClass().getName(),
                    memberCount(u), u.mounts() == null ? -1 : u.mounts().length, u.abilities() == null ? -1 : u.abilities().length, u.hasWeapons());
            }
        }catch(Throwable t){ Log.err("[drv] duoMerge failed", t); }
    }

    /** 每帧替目标单位按住扳机并对准 target（截图用：逼它开火，好看清武器到底发什么）。 */
    static void installTrigger(Unit u, Unit target){
        var t = new arc.scene.ui.layout.Table();
        t.touchable = arc.scene.event.Touchable.disabled;
        t.update(() -> {
            if(u == null || !u.isAdded()) return;
            float tx = target != null && target.isValid() ? target.x : u.x + 100f;
            float ty = target != null && target.isValid() ? target.y : u.y;
            if(u.mounts() == null) return;
            for(var m : u.mounts()){
                m.aimX = tx;
                m.aimY = ty;
                m.shoot = true;
                m.rotate = true;
            }
        });
        Vars.ui.hudGroup.addChild(t);
    }

    static int memberCount(Unit u){
        try{ return (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable t){ return -1; }
    }

    static void duoReport(){
        try{
            if(duoMega == null || !duoMega.isValid()){ Log.info("[drv] duo: 巨兽没了"); return; }
            Unit u = duoMega;
            camTarget = u;
            Core.camera.position.set(u.x, u.y);
            Object dom = field(u.getClass(), u, "dominant");
            Log.info("[drv] duo 巨兽: type=@ flying=@ 综合hitSize=@ 代表类型=@(hitSize=@) 贴图缩放=@",
                u.type.name, u.type.flying, u.hitSize(), dom instanceof UnitType ? ((UnitType)dom).name : "-",
                dom instanceof UnitType ? ((UnitType)dom).hitSize : -1f,
                dom instanceof UnitType ? (u.hitSize() / ((UnitType)dom).hitSize) : -1f);
            Log.info("[drv] duo 稳定后: 类=@ 成员=@ 挂座=@ 能力=@ 有武器=@", u.getClass().getName(), memberCount(u),
                u.mounts() == null ? -1 : u.mounts().length, u.abilities() == null ? -1 : u.abilities().length, u.hasWeapons());
            int idx = 0;
            if(u.mounts() != null) for(var m : u.mounts()){
                Log.info("[drv]   挂座@ 武器=@ mount=@ bullet=@ 目标=@", idx++,
                    m.weapon.getClass().getSimpleName(), m.getClass().getSimpleName(),
                    m.weapon.bullet == null ? "null" : m.weapon.bullet.getClass().getSimpleName(),
                    m.target == null ? "null" : m.target.getClass().getSimpleName());
            }
            if(duoEnemy != null)
                Log.info("[drv] duo 敌方靶子: 血=@/@ 在=@,@ 巨兽在=@,@ 玩家单位=@", duoEnemy.health(), duoEnemy.maxHealth(),
                    (int)duoEnemy.x, (int)duoEnemy.y, (int)u.x, (int)u.y,
                    Vars.player.unit() == null ? "null" : Vars.player.unit().type.name);
            if(duoAlly != null)
                Log.info("[drv] duo 伤员: 血=@%@", (int)(duoAlly.healthf() * 100));
            for(var m : u.mounts())
                Log.info("[drv] duo 挂座目标: 武器=@ target=@ shoot=@", m.weapon.getClass().getSimpleName(),
                    m.target == null ? "null" : m.target.getClass().getSimpleName(), m.shoot);
        }catch(Throwable t){ Log.err("[drv] duoReport failed", t); }
    }

    // ---------------- 组合巨兽：两艘船在深水里合体（水阻） ----------------
    static int shipOx = -1, shipOy = -1;
    static Unit shipMegaUnit, rissoA, rissoB, shipRef;

    static void setupShipScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            // 关掉战争迷雾：否则深水那块是没探索过的，单位 inFogTo() 直接不画，截图上啥都看不到
            Vars.state.rules.fog = false;
            Vars.state.rules.staticFog = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 150; y++){
                for(int x = 40; x < 200; x++){
                    boolean ok = true;
                    for(int dy = -2; dy <= 2 && ok; dy++) for(int dx = -3; dx <= 3; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor() == null || !t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] shipmega: 没找到深水"); return; }
            // 核心必须放在**陆地**上（水里 placeBL 会失败 → 队伍没核心 → 那块水域没被探索 →
            // 单位 inFogTo() 为真，截图里什么都看不到）
            int coreX = -1, coreY = -1;
            outerCore:
            for(int r = 3; r <= 12; r++){
                for(int dy = -r; dy <= r; dy++) for(int dx = -r; dx <= r; dx++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    Tile t = Vars.world.tile(ox + dx, oy + dy);
                    if(t != null && t.floor() != null && !t.floor().isLiquid && t.block() == Blocks.air){ coreX = ox + dx; coreY = oy + dy; break outerCore; }
                }
            }
            if(coreX >= 0){
                Building core = placeBL(Blocks.coreShard, coreX, coreY);
                if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
                Log.info("[drv] shipmega: 岸上核心 @,@ 放入=@", coreX, coreY, core != null);
            }else Log.err("[drv] shipmega: 附近没找到陆地放核心");
            shipOx = ox; shipOy = oy;
            Log.info("[drv] shipmega: 深水=@,@ 地形=@", ox, oy, Vars.world.tile(ox, oy).floor().name);
            Timer.schedule(Driver::shipSpawn, 2f);
        }catch(Throwable t){ Log.err("[drv] setupShipScene failed", t); }
    }

    static void shipSpawn(){
        try{
            float cx = shipOx * 8f, cy = shipOy * 8f;
            rissoA = UnitTypes.risso.create(Team.sharded);
            rissoA.set(cx - 12f, cy);
            rissoA.add();
            rissoB = UnitTypes.risso.create(Team.sharded);
            rissoB.set(cx + 12f, cy);
            rissoB.add();
            // 对照船放远一点：merge 会把 160 单位内的同队未编组单位一起并走
            shipRef = UnitTypes.risso.create(Team.sharded);
            shipRef.set(cx + 220f, cy);
            shipRef.add();
            Core.camera.position.set(cx, cy);
            Log.info("[drv] shipmega: 两艘 risso + 一台对照船已就位");
        }catch(Throwable t){ Log.err("[drv] shipSpawn failed", t); }
    }

    static void shipMerge(){
        try{
            Object merged = rissoA == null ? null
                : combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, rissoA);
            if(merged instanceof Unit u) shipMegaUnit = u;
            Log.info("[drv] shipmega 融合结果=@", merged);
        }catch(Throwable t){ Log.err("[drv] shipMerge failed", t); }
    }

    static void shipReport(){
        try{
            if(shipMegaUnit == null){ Log.info("[drv] shipmega: 没有巨兽"); return; }
            Unit u = shipMegaUnit;
            camTarget = u;
            installCameraLock();
            Core.camera.position.set(u.x, u.y);
            if(shipRef != null && shipRef.isValid())
                Log.info("[drv] shipmega 对照（同地形）: 巨兽 speed=@ 系数=@ 有效=@（地形 @） | 原版 risso speed=@ 系数=@ 有效=@",
                    u.type.speed, u.floorSpeedMultiplier(), u.type.speed * u.floorSpeedMultiplier(),
                    u.floorOn() == null ? "null" : u.floorOn().name,
                    shipRef.type.speed, shipRef.floorSpeedMultiplier(),
                    shipRef.type.speed * shipRef.floorSpeedMultiplier());
            Log.info("[drv] shipmega: type=@ hitSize=@ 溺水=@ elevation=@ 盾=@", u.type.name, u.hitSize(), u.canDrown(), u.elevation, u.shield());
        }catch(Throwable t){ Log.err("[drv] shipReport failed", t); }
    }

    static void megaCommandMode(){
        try{
            if(megaUnit == null) return;
            // 真按 Shift 进指挥模式做不到（commandMode 每帧按按键状态重算），
            // 所以这里把"命令菜单列表用的那个控件"直接搭出来：原版面板用的是
            // StatValues.stack(content.unit(unit.type.id), 数量) → stack(type.uiIcon, ...)，
            // 画出来的是不是巨兽自己的图标，一眼就能看。
            Vars.control.input.selectedUnits.clear();
            Vars.control.input.selectedUnits.add(megaUnit);
            arc.Events.fire(mindustry.game.EventType.Trigger.unitCommandChange);

            mindustry.type.UnitType resolved = Vars.content.unit(megaUnit.type.id);
            Object dominant = field(megaUnit.getClass(), megaUnit, "dominant");
            String domIcon = "-", resIcon = "-";
            if(dominant instanceof UnitType dt && dt.uiIcon != null) domIcon = regionName(dt.uiIcon);
            if(resolved != null && resolved.uiIcon != null) resIcon = regionName(resolved.uiIcon);
            Log.info("[drv] 指挥模式: 巨兽 type=@(id=@) → content.unit(id)=@(@) 名字=@",
                megaUnit.type.name, megaUnit.type.id, resolved == null ? "null" : resolved.name,
                resolved == null ? "-" : resolved.getClass().getSimpleName(),
                resolved == null ? "-" : resolved.localizedName);
            Log.info("[drv]   代表成员图标=@ 面板会用的图标=@ 同一个=@", domIcon, resIcon, domIcon.equals(resIcon));
            Log.info("[drv]   选择集=@", Vars.control.input.selectedUnits.size);

            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            panel.add("命令菜单里那一格（StatValues.stack(content.unit(type.id), 1)）：").left().row();
            panel.table(t -> {
                t.left();
                t.add(mindustry.world.meta.StatValues.stack(resolved, 1)).pad(6f);
                t.add(new arc.scene.ui.Image(UnitTypes.dagger.uiIcon)).size(32f).pad(6f).tooltip("这是 dagger（旧 bug 里显示的那个）");
                t.add("← 左=现在面板会显示的；右=dagger（旧 bug 对照）").left().padLeft(6f);
            }).left().row();
            // 命令菜单里的指令按钮行（原版面板就是遍历 content.unit(id).commands 画这些）
            panel.add("命令按钮（面板遍历 type.commands 画的就是这些）：").left().padTop(8f).row();
            StringBuilder names = new StringBuilder();
            panel.table(t -> {
                t.left();
                int col = 0;
                for(var command : resolved.commands){
                    t.button(mindustry.gen.Icon.icons.get(command.icon, mindustry.gen.Icon.cancel), mindustry.ui.Styles.clearNoneTogglei, () -> {})
                        .size(44f).pad(3f).tooltip(command.localized());
                    if(++col % 8 == 0) t.row();
                }
            }).left().row();
            for(var command : resolved.commands) names.append(command.name).append(' ');
            Log.info("[drv]   面板会用到的指令: @", names.toString());
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("指挥模式单位图标核对");
            d.cont.add(panel).pad(10f);
            d.show();
        }catch(Throwable t){ Log.err("[drv] megaCommandMode failed", t); }
    }

    static void megaMerge(){
        try{
            Log.info("[drv] 融合前: Groups.unit=@ mace 有效=@ 已添加=@ 血=@ oct 有效=@ 已添加=@ 血=@ 盾=@",
                Groups.unit.count(u -> true),
                maceUnit == null ? "-" : maceUnit.isValid(), maceUnit == null ? "-" : maceUnit.isAdded(), maceUnit == null ? -1f : maceUnit.health(),
                octUnit == null ? "-" : octUnit.isValid(), octUnit == null ? "-" : octUnit.isAdded(), octUnit == null ? -1f : octUnit.health(),
                octUnit == null ? -1f : octUnit.shield());
            Object gid = combineCall("combineunit.units.UnitComboDamage", "comboId", new Class<?>[]{Unit.class}, maceUnit);
            Log.info("[drv] mace comboId=@", gid);
            Object merged = combineCall("combineunit.units.UnitComboMerge", "merge", new Class<?>[]{Unit.class}, maceUnit);
            Log.info("[drv] 融合结果=@", merged);
            if(merged instanceof Unit u) megaUnit = u;
            if(megaUnit != null){
                megaDump("融合后");
                shot("mega_world");
            }
        }catch(Throwable t){ Log.err("[drv] megaMerge failed", t); }
    }

    static String regionName(arc.graphics.g2d.TextureRegion r){
        return r == null ? "null" : (r + "@" + System.identityHashCode(r));
    }

    static void megaDump(String tag){
        try{
            if(megaUnit == null){ Log.info("[drv] mega @: 没有巨兽", tag); return; }
            Unit u = megaUnit;
            Object dominant = field(u.getClass(), u, "dominant");
            Object drawScale = field(u.getClass(), u, "drawScale");
            Log.info("[drv] mega @: type=@ 类=@ 有效=@ 已添加=@ hitSize=@ 血=@/@ 盾=@ 力场数=@ 挂座=@ dominant=@ drawScale=@",
                tag, u.type.name, u.getClass().getName(), u.isValid(), u.isAdded(), u.hitSize(), u.health(), u.maxHealth(), u.shield(),
                u.abilities().length, u.mounts().length, dominant, drawScale);
            for(var a : u.abilities()){
                Log.info("[drv]   能力 @ (max=@ scaledMax=@)", a.getClass().getName(),
                    a instanceof mindustry.entities.abilities.ForceFieldAbility ff ? ff.max : "-",
                    a instanceof mindustry.entities.abilities.ForceFieldAbility ff ? ff.scaledMax(u) : "-");
            }
            try{
                Class<?> mt = Class.forName("combineunit.units.mega.MegaUnitType", true, unitMl());
                Log.info("[drv]   type 是 MegaUnitType? = @ 类=@", mt.isInstance(u.type), u.type.getClass().getName());
            }catch(Throwable t){ Log.err("[drv] 类型检查失败", t); }
            if(dominant instanceof UnitType dt){
                Log.info("[drv]   dominant fullIcon=@ found=@ region=@ found=@ uiIcon=@",
                    regionName(dt.fullIcon), dt.fullIcon != null && Core.atlas.isFound(dt.fullIcon),
                    regionName(dt.region), dt.region != null && Core.atlas.isFound(dt.region), regionName(dt.uiIcon));
            }
            Log.info("[drv]   type.fullIcon=@ found=@ type.region=@ found=@ drawShields=@ flying=@",
                regionName(u.type.fullIcon), u.type.fullIcon != null && Core.atlas.isFound(u.type.fullIcon),
                regionName(u.type.region), u.type.region != null && Core.atlas.isFound(u.type.region),
                u.type.drawShields, u.type.flying);
            // 绘制裁剪：EntityGroup.draw 用 clipSize 做视口裁剪
            try{
                float clip = u.clipSize();
                arc.math.geom.Rect vp = Core.camera.bounds(new arc.math.geom.Rect());
                boolean overlaps = vp.overlaps(u.x - clip / 2f, u.y - clip / 2f, clip, clip);
                boolean inDrawGroup = false;
                int drawSize = 0;
                for(var d : Groups.draw){
                    drawSize++;
                    if(d == (Object)u) inDrawGroup = true;
                }
                Log.info("[drv]   clipSize=@ 视口=@x@+@x@ 裁剪结果=@ 在绘制组=@ 绘制组大小=@ 在单位组=@",
                    clip, (int)vp.x, (int)vp.y, (int)vp.width, (int)vp.height, overlaps, inDrawGroup, drawSize,
                    Groups.unit.contains(o -> o == u));
            }catch(Throwable t){ Log.err("[drv] 裁剪诊断失败", t); }
        }catch(Throwable t){ Log.err("[drv] megaDump failed", t); }
    }

    static Object field(Class<?> c, Object o, String name){
        Class<?> k = c;
        while(k != null){
            try{
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            }catch(Throwable ignored){ k = k.getSuperclass(); }
        }
        return null;
    }

    static float megaShieldMax(){
        for(var a : megaUnit.abilities()){
            if(a instanceof mindustry.entities.abilities.ForceFieldAbility ff) return ff.max;
        }
        return -1f;
    }

    static void run(int ticks){
        for(int i = 0; i < ticks; i++){
            arc.util.Time.delta = 1f;
            Vars.logic.update();
        }
    }

    /** 帧计数器：挂在 HUD 上的空表，只用来数"画面真的跑了多少帧"。 */
    static int frames = 0, framesAtAdd = -1;
    static void installFrameCounter(){
        var t = new arc.scene.ui.layout.Table();
        t.update(() -> frames++);
        t.touchable = arc.scene.event.Touchable.disabled;
        Vars.ui.hudGroup.addChild(t);
    }
    static void maybeShotAfter(){
        if(framesAtAdd < 0) return;
        if(frames - framesAtAdd >= 30){
            Log.info("[drv] 加料后又跑了 @ 帧 → 截图", frames - framesAtAdd);
            shot("coop_panel_after");
            finish();
        }
    }
    static void finish(){
        Log.info("[drv] done（frames=@）", frames);
        Core.app.exit();
    }

    /** 关掉所有弹窗（模组信息框、设置界面……），免得盖住要截的东西。 */
    static void hideDialogs(){
        try{
            for(arc.scene.Element e : Core.scene.root.getChildren()){
                if(e instanceof arc.scene.ui.Dialog d){
                    // 弹窗里往往就是"被踢/连接失败"的原因（比如 @disconnect.closed），先把文字打出来再关
                    String text = dialogText(d);
                    Log.info("[drv] 关掉弹窗 @ @", d.getClass().getSimpleName(), text.isEmpty() ? "" : ("→ " + text));
                    d.hide();
                }
            }
        }catch(Throwable t){ Log.err("[drv] hideDialogs failed", t); }
    }

    /** 递归收集弹窗里的文字（诊断"被服务器踢了/连接失败"的原因）。 */
    static String dialogText(arc.scene.Element e){
        StringBuilder sb = new StringBuilder();
        collectText(e, sb);
        return sb.length() > 300 ? sb.substring(0, 300) : sb.toString();
    }

    static void collectText(arc.scene.Element e, StringBuilder sb){
        try{
            if(e instanceof arc.scene.ui.Label l && l.getText() != null && l.getText().length() > 0){
                if(sb.length() > 0) sb.append(" | ");
                sb.append(l.getText());
            }
            if(e instanceof arc.scene.Group g) for(arc.scene.Element c : g.getChildren()) collectText(c, sb);
        }catch(Throwable ignored){}
    }

    /**
     * 场景阶段**每秒**清一次弹窗。
     * 客户端的"检查更新"弹窗是启动后隔几秒才弹出来的（软渲染下时机不固定），
     * 只在场景开头清一次会正好被它盖住截图（照片里全是版本列表，看不到单位）。
     */
    static void keepDialogsHidden(){
        Timer.schedule(Driver::hideDialogs, 2f, 1f, 60);
    }

    // ==================== 联机（mode=mp） ====================

    static String mpHost = System.getProperty("drv.host", "127.0.0.1");
    static int mpPort = Integer.parseInt(System.getProperty("drv.port", "6567"));
    static Seq<String> mpSeen = new Seq<>();
    static boolean mpConnected = false;

    static void mpConnect(){
        try{
            // 服务器会踢掉空名字的连接（KickReason.nameEmpty）
            String name = System.getProperty("drv.name", "drv-mp");
            Vars.player.name = name;
            Core.settings.put("name", name);
            Log.info("[MP-CLIENT] 模组清单: @（本机名 @）", Vars.mods.getModStrings(), name);
            Log.info("[MP-CLIENT] 连接 @:@ …", mpHost, mpPort);
            Vars.netClient.beginConnecting();
            Vars.net.connect(mpHost, mpPort, () -> Log.info("[MP-CLIENT] 已连上服务器 @:@", mpHost, mpPort));
            mpConnected = true;
        }catch(Throwable t){
            Log.err("[MP-CLIENT] 连接失败", t);
        }
    }

    /** 每秒把"客户端看到的世界"打一行（和服务端 MpHost 同一格式，run-mp.sh 拿来比对）。 */
    static void mpReport(){
        try{
            if(!Vars.net.client()) return;
            String s = mpState();
            if(!mpSeen.contains(s)) mpSeen.add(s);
            Log.info("[MP-STATE] " + s);
            // 【逐 id 清单】run-mp.sh 拿两端**最后一行**做集合比对：客户端多出来的 id = 幽灵单位，
            // 少掉的 = 没同步过去。和 [MP-STATE] 那种"统计摘要"不同，这里是对 id 的一对一核对。
            StringBuilder ids = new StringBuilder();
            for(Unit u : Groups.unit){
                if(u.team() != Vars.player.team()) continue;
                int members = -1;
                try{ members = (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable ignored){}
                // 格式必须和服务端 MpScenario 的 [MP-IDS] 完全一致（id:成员数），否则比对全是假差异
                ids.append(u.id()).append(":").append(members);
                // 巨兽再带上成员 id（`id:成员数:成员id,成员id`）：脚本据此检查"同一个 id 既当
                // 独立单位、又是某只巨兽的成员"（那就是幽灵成员，用户报的"合体后变幽灵单位"）。
                if(members > 0) ids.append(":").append(memberIds(u));
                ids.append(" ");
            }
            // 时间戳用 epoch 毫秒（和 MpScenario 一致），脚本按毫秒把两端对齐到同一时刻
            Log.info("[MP-IDS] ms=@ player=@ @", System.currentTimeMillis(),
                Vars.player.unit() == null ? -1 : Vars.player.unit().id, ids);
            // 【物品总量】按模块身份去重（共享池算一次）：和服务端 MpScenario 的 [MP-ITEMS] 对齐比对
            Log.info("[MP-ITEMS] ms=@ total=@", System.currentTimeMillis(), worldItemTotal());
            // 【建造网络包】全图传送带的方向：服务端 [MP-ROT] 里那一排（它排了"原地改方向"计划）
            // 必须都能在下面这一串里找到**同样的方向** —— 用户报的"联机时两端拐角处方向对不上"
            // 就是这一步该抓的（修前服务端的建造武器只改本机世界，客户端一直停在旧方向）。
            Log.info("[MP-ROT] ms=@ world=@", System.currentTimeMillis(), conveyorRots());
            // 每秒把每个单位逐个打出来（排查"幽灵/看不见/成员数不对"时用）：默认关着，
            // 免得正常跑一次就刷几千行；要排查就加参数 -Ddrv.mpVerbose=1。
            if("1".equals(System.getProperty("drv.mpVerbose"))){
                Log.info("[MP-DBG] net类=@ 本地队伍=@ 全图单位=@ 方块=@ 玩家数=@ 位置=@,@ map=@",
                    Vars.net.getClass().getSimpleName(),
                    Vars.player.team().name, Groups.unit.count(u -> true), Groups.build.count(b -> true), Groups.player.size(),
                    (int)Vars.player.x, (int)Vars.player.y, Vars.state.map == null ? "null" : Vars.state.map.name());
                for(Unit u : Groups.unit){
                    String extra = "";
                    try{ extra = " members=" + u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable ignored){}
                    Log.info("[MP-DBG]   单位 id=@ 类=@ 类型=@ 队伍=@ 位置=@,@ 血量=@/@ 已加=@ 有效=@ hit=@ size=@ @",
                        u.id, u.getClass().getSimpleName(), u.type == null ? "null" : u.type.name,
                        u.team() == null ? "null" : u.team().name, (int)u.x, (int)u.y, (int)u.health, (int)u.maxHealth,
                        u.isAdded(), u.isValid(),
                        (int)u.hitSize, u.type == null ? -1 : (int)u.type.hitSize, extra);
                }
            }
            // 【绘制探针】"客户端不显示巨兽"要么是根本没进绘制、要么是被画到看不见的地方。
            // 这几个值能把两类原因分开：region 有没有贴图、clipSize/图层是不是负数、
            // elevation 走哪个绘制分支、迷雾判定、以及镜头到底对着哪。
            Unit mg = megaUnit();
            if(mg != null){
                String dom = "?", scale = "?";
                try{ dom = String.valueOf(mg.getClass().getField("dominant").get(mg)); }catch(Throwable ignored){}
                try{ scale = String.valueOf(mg.getClass().getField("drawScale").get(mg)); }catch(Throwable ignored){}
                Tmp.v1.set(mg.x, mg.y);
                Core.camera.project(Tmp.v1);
                Log.info("[MP-DRAW] 巨兽@ 位置=@,@ 屏幕=@,@ 相机=@,@ 有region=@ 有fullIcon=@ clipSize=@ flyingLayer=@ "
                    + "elevation=@ 代表类型=@ 缩放=@ 迷雾扣住=@ 已加=@ 血=@/@ 盾=@ 物品容量=@ 身上物品=@ itemTime=@ 物品底圈贴图=@ drawItems=@ 挖矿中=@ 矿格=@ 玩家单位=@,@（跟随@）",
                    mg.id, (int)mg.x, (int)mg.y, (int)Tmp.v1.x, (int)Tmp.v1.y,
                    (int)Core.camera.position.x, (int)Core.camera.position.y,
                    mg.type.region != null, mg.type.fullIcon != null, mg.type.clipSize,
                    mg.type.flyingLayer, mg.elevation, dom, scale,
                    mg.inFogTo(Vars.player.team()), mg.isAdded(), (int)mg.health, (int)mg.maxHealth, (int)mg.shield,
                    mg.type.itemCapacity, mg.stack().amount, mg.itemTime(),
                    mg.type.itemCircleRegion != null, mg.type.drawItems, mg.mining(),
                    mg.mineTile() == null ? "无" : (mg.mineTile().x + "," + mg.mineTile().y),
                    (int)Vars.player.x, (int)Vars.player.y,
                    Vars.player.unit() == null ? "无" : Vars.player.unit().type.name);
            }
        }catch(Throwable t){
            Log.err("[MP-CLIENT] mpReport 失败", t);
        }
    }

    static Seq<String> mpStates(){
        return mpSeen;
    }

    /** 全图传送带 → {@code x,y:方向}（服务端 [MP-ROT] 的每一格都要能在这一串里找到同样的方向）。 */
    static String conveyorRots(){
        StringBuilder b = new StringBuilder();
        for(mindustry.world.Tile t : Vars.world.tiles){
            if(t == null || t.build == null) continue;
            if(!(t.block() instanceof mindustry.world.blocks.distribution.Conveyor)) continue;
            b.append(t.x).append(',').append(t.y).append(':').append(t.build.rotation).append(' ');
        }
        return b.toString();
    }

    /** 镜头对准组合巨兽（截"联机时巨兽长什么样"的证据图用）。 */
    static boolean mpLoggedCam = false, mpLoggedMiss = false;

    /** 客户端这一侧看到的巨兽实体（按类名找，拿不到就 null）。 */
    static Unit megaUnit(){
        for(Unit u : Groups.unit)
            if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) return u;
        return null;
    }

    /** 正在挖矿的巨兽（收尾的"挖矿光束"取证：镜头要钉在它身上）。 */
    static Unit miningMega(){
        for(Unit u : Groups.unit){
            if(!u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) continue;
            if(u.mining()){
                if(!mpZoomed){
                    mpZoomed = true;
                    // 拉近 2 倍：挖矿激光只有几十像素长，默认视野下容易被单位自己的贴图盖住
                    try{ Vars.renderer.setScale(2f); }catch(Throwable ignored){}
                    Log.info("[MP-CAM] 发现挖矿中的巨兽 @，镜头拉近 2× 拍光束", u.id);
                }
                return u;
            }
        }
        return null;
    }
    static boolean mpZoomed = false;

    static void lookAtMega(){
        try{
            Unit u = miningMega();
            if(u == null) u = megaUnit();
            if(u == null){
                if(!mpLoggedMiss){
                    mpLoggedMiss = true;
                    Log.info("[MP-CAM] 没找到巨兽实体（Groups.unit=@）：@", Groups.unit.count(x -> true), unitSummary());
                }
                return;
            }
            Core.camera.position.set(u.x, u.y);
            if(!mpLoggedCam){
                mpLoggedCam = true;
                Log.info("[MP-CAM] 镜头已对准巨兽 id=@ 位置=@,@ 相机=@,@",
                    u.id, (int)u.x, (int)u.y, (int)Core.camera.position.x, (int)Core.camera.position.y);
            }
        }catch(Throwable t){ Log.err("[MP-CLIENT] lookAtMega 失败", t); }
    }

    static String unitSummary(){
        StringBuilder sb = new StringBuilder();
        for(Unit u : Groups.unit)
            sb.append(u.getClass().getName()).append("@").append(u.id).append(" ");
        return sb.toString();
    }

    /**
     * 世界物品总量：按**模块身份**去重（组合体共用的那份池子只算一次）。
     * 和服务端 MpScenario 的 [MP-ITEMS] 对齐比对 —— 用户报的"组合节点瞎连导致物品涨到 11m /
     * 变负数"在这一项上一眼就能看出来（涨了、跌了、或者两端对不上都算异常）。
     */
    /**
     * 客户端"瞎连"：随机挑一个组合节点和它附近的一栋组合建筑，走玩家点击那条原版配置通道
     * （{@code onConfigureBuildTapped} → {@code Building.configure} → {@code Call.tileConfig}）连/断。
     * 用户报的"组合节点瞎连导致物品异常增长/减少（11m、负数）"就是这么点出来的。
     */
    static void mpNodeMess(){
        try{
            Seq<Building> nodes = new Seq<>(), others = new Seq<>();
            for(mindustry.world.Tile t : Vars.world.tiles){
                Building b = t == null ? null : t.build;
                if(b == null || !b.isValid()) continue;
                if(b.getClass().getName().contains("ComboNode")) nodes.add(b);
                else if(b.team == Vars.player.team()) others.add(b);
            }
            if(nodes.isEmpty() || others.isEmpty()) return;
            Building nd = nodes.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(nodes.size));
            Seq<Building> near = new Seq<>();
            for(Building b : others) if(b.dst(nd) < 120f && b != nd) near.add(b);
            if(near.isEmpty()) return;
            Building tg = near.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(near.size));
            nd.onConfigureBuildTapped(tg);
            Log.info("[MP-LINK] ms=@ 客户端连线/断线 节点@,@ ↔ @ @@,@", System.currentTimeMillis(),
                nd.tileX(), nd.tileY(), tg.block.name, tg.tileX(), tg.tileY());
        }catch(Throwable t){
            Log.err("[MP-LINK] 客户端连接切换失败", t);
        }
    }

    static int worldItemTotal(){
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        // 按**世界格**遍历（Groups.build 在有些时机不全：实测漏过刚摆下的容器）
        for(mindustry.world.Tile t : Vars.world.tiles){
            Building b = t == null ? null : t.build;
            if(b == null || b.items == null || seen.put(b.items, Boolean.TRUE) != null) continue;
            for(mindustry.type.Item it : Vars.content.items()) total += b.items.get(it);
        }
        return total;
    }

    /** 巨兽的成员 id 列表（逗号分隔）；不是巨兽就返回空串。全部走反射，驱动不依赖模组类。 */
    static String memberIds(Unit u){
        try{
            Object seq = u.getClass().getMethod("members").invoke(u);
            StringBuilder sb = new StringBuilder();
            for(Object up : (Iterable<?>)seq){
                if(up == null) continue;
                Unit m = (Unit)up.getClass().getField("unit").get(up);
                if(m == null) continue;
                if(sb.length() > 0) sb.append(',');
                sb.append(m.id());
            }
            return sb.toString();
        }catch(Throwable t){
            return "";
        }
    }

    /**
     * 客户端自己发起合体：挑两只"别人的、还没合体的"单位，走命令面板那条入口请求合体
     * （{@code UnitComboMerge.requestMergeSelected} 在联机里只发 {@code MegaOrderPacket}，实体增删由
     * 服务端结算）。客户端如果在这条路上也本地动手，就会留下服务端不承认的**幽灵单位** ——
     * run-mp.sh 最后拿两端逐 id 清单做集合比对，专门抓这个。
     */
    static void mpRequestMerge(){
        // 用户是"看到单位才点合体"；客户端这边单位同步可能慢半拍，所以重试几次再放弃。
        if(mpReqTries++ > 6) return;
        try{
            Seq<Unit> cand = new Seq<>();
            // 前几次先按"别人的单位"合体；第 5 次起改成**把自己也框进去**（玩家常见操作）：
            // 玩家自己的单位被合进巨兽后，"自己那只"要由服务端删掉、客户端跟着走，
            // 这条路上最容易留下幽灵。
            boolean includeSelf = mpReqTries >= 3;
            for(Unit u : Groups.unit){
                if(u == null || !u.isAdded() || u.team() != Vars.player.team()) continue;
                if(u.isPlayer() && !includeSelf) continue;
                if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")) continue;
                cand.add(u);
            }
            if(cand.size < 2){
                Log.info("[MP-REQ] 第 @ 次：可合体的单位不足 2 只（@）：@", mpReqTries, cand.size, unitSummary());
                return;
            }
            Seq<Unit> sel = new Seq<>();
            if(includeSelf && Vars.player.unit() != null && cand.contains(Vars.player.unit())){
                sel.add(Vars.player.unit());
                for(Unit u : cand){
                    if(u != Vars.player.unit()){ sel.add(u); break; }
                }
            }else{
                sel.add(cand.get(0));
                sel.add(cand.get(1));
            }
            Class<?> c = Class.forName("combineunit.units.UnitComboMerge", true, unitMl());
            c.getMethod("requestMergeSelected", Seq.class).invoke(null, sel);
            Log.info("[MP-REQ] t=@s 客户端请求合体: @（@,@）", (int)(arc.util.Time.time / 60f),
                sel.map(u -> u.id() + ":" + u.type.name), (int)sel.first().x, (int)sel.first().y);
        }catch(Throwable t){
            Log.err("[MP-REQ] 客户端请求合体失败", t);
        }
    }

    static String mpState(){
        int mega = 0, members = 0, daggers = 0, fortresses = 0, octs = 0, units = 0;
        for(Unit u : Groups.unit){
            if(u.team() != Vars.player.team()) continue;
            units++;
            if(u.getClass().getName().equals("combineunit.units.mega.MegaUnitEntity")){
                mega++;
                try{ members += (Integer)u.getClass().getMethod("memberCount").invoke(u); }catch(Throwable ignored){}
            }
            if(u.type == UnitTypes.dagger) daggers++;
            if(u.type == UnitTypes.fortress) fortresses++;
            if(u.type == UnitTypes.oct) octs++;
        }
        return "mega=" + mega + " members=" + members + " daggers=" + daggers
            + " fortresses=" + fortresses + " octs=" + octs + " units=" + units;
    }

    /** 结尾报告：把客户端见过的所有状态打进日志（run-mp.sh 与服务端的做包含比对）。 */
    static void mpVerdict(){
        Log.info("[MP-CLIENT] 客户端见过的世界状态（共 @ 种）：", mpSeen.size);
        for(String s : mpSeen) Log.info("[MP-CLIENT-STATE] " + s);
        Log.info("[MP-CLIENT] 最终状态: @", mpState());
    }

    static void step1(){
        ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
        shot("main");
        Vars.ui.settings.show();
        Timer.schedule(() -> { shot("settings_menu"); clickButton("组合工厂"); }, 2.5f);
        Timer.schedule(() -> { shot("settings_list"); }, 5f);
        Timer.schedule(() -> { Log.info("[drv] list 模式结束"); Core.app.exit(); }, 6.5f);
    }

    /** 在场景里找文字包含 key 的 TextButton，触发它的 ClickListener。 */
    static boolean clickButton(String key){
        try{
            return hit(Core.scene.root, key);
        }catch(Throwable t){ Log.err("[drv] clickButton failed", t); }
        return false;
    }
    static boolean hit(arc.scene.Element e, String key){
        if(e instanceof arc.scene.ui.TextButton tb){
            CharSequence cs = tb.getText();
            if(cs != null && cs.toString().contains(key)){
                for(var l : tb.getListeners()){
                    if(l instanceof arc.scene.event.ClickListener cl){
                        cl.clicked(null, 0f, 0f);
                        Log.info("[drv] clicked button: @", cs);
                        return true;
                    }
                }
            }
        }
        if(e instanceof arc.scene.Group g){
            for(arc.scene.Element c : g.getChildren()) if(hit(c, key)) return true;
        }
        return false;
    }

    /** 载一张图并跑起来，让 WorldLoadEvent 上的处理（换星球造价 / 扫树）真的执行一遍。 */
    static void setupTechWorld(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            Log.info("[drv] tech2 场景: 地图=@ 星球=@ 战役=@", map.name(),
                Vars.state.getPlanet() == null ? "null" : Vars.state.getPlanet().name, Vars.state.isCampaign());
        }catch(Throwable t){ Log.err("[drv] setupTechWorld failed", t); }
    }

    /** 真正打开科技树，并把每棵根树的页面都切一遍（rebuildTree → rebuildAll → canSpend）。 */
    static void openTechRoots(){
        try{
            Vars.ui.research.show();
            Log.info("[drv] 科技树已打开，根数=@", mindustry.content.TechTree.roots.size);
            float t = 1f;
            for(mindustry.content.TechTree.TechNode r : new arc.struct.Seq<>(mindustry.content.TechTree.roots)){
                String rootName = r.content == null ? "null" : r.content.name;
                // 一棵树一步地切、切完再截：全挤在同一帧里切的话，截图永远只拍到最后一棵。
                // rebuildTree = switchTree + checkNodes + treeLayout（只 switchTree 不排布，节点会全叠中间）
                Timer.schedule(() -> {
                    try{
                        Vars.ui.research.rebuildTree(r);
                        Log.info("[drv] rebuildTree @ (content=@)", r.localizedName(), rootName);
                    }catch(Throwable ex){ Log.err("[drv] rebuildTree 崩了: @", r, ex); }
                }, t);
                t += 1f;
                Timer.schedule(() -> shot("tech_root_" + rootName), t);
                t += 1f;
            }
        }catch(Throwable t){ Log.err("[drv] openTechRoots failed", t); }
    }

    /** 科技树体检：按原版 canSpend 的写法把每个节点解引用一遍，报出坏节点是谁。 */
    /** 把镜头居中到当前这棵树里某个方块的节点上（树很大，不居中节点会在屏幕外）。 */
    /** 当前界面缓存的那棵树里，有没有我们三个方块（用来验 ResearchDialog 的节点缓存新不新）。 */
    static void treeHasOurNodes(String tag){
        try{
            StringBuilder sb = new StringBuilder();
            for(String n : new String[]{"connection", "node", "liquid-unloader"}){
                boolean found = false;
                for(mindustry.ui.dialogs.ResearchDialog.TechTreeNode ttn : Vars.ui.research.nodes){
                    if(ttn.node != null && ttn.node.content != null && ttn.node.content.name.equals(n)){ found = true; break; }
                }
                sb.append(n).append('=').append(found).append(' ');
            }
            Log.info("[drv] 界面树缓存（@）: 节点@ 个，其中 @", tag, Vars.ui.research.nodes.size, sb);
        }catch(Throwable t){ Log.err("[drv] treeHasOurNodes failed", t); }
    }

    static void centerTree(String contentName){
        try{
            for(mindustry.ui.dialogs.ResearchDialog.TechTreeNode ttn : Vars.ui.research.nodes){
                if(ttn.node == null || ttn.node.content == null) continue;
                if(!ttn.node.content.name.equals(contentName)) continue;
                Vars.ui.research.view.panX = -ttn.x;
                Vars.ui.research.view.panY = -ttn.y + 60f;
                Log.info("[drv] 居中 @: 树坐标 @,@ (当前树可见节点 @ 个)", contentName,
                    (int)ttn.x, (int)ttn.y, Vars.ui.research.nodes.size);
                return;
            }
            Log.err("[drv] 当前这棵树里没有 @", contentName);
        }catch(Throwable t){ Log.err("[drv] centerTree failed", t); }
    }

    /** 把三个方块在当前这棵树里的节点位置打出来（截图按坐标裁）。 */
    static void locateTechNodes(){
        try{
            for(arc.scene.Element e : Vars.ui.research.view.getChildren()){
                if(!(e instanceof arc.scene.ui.ImageButton btn)) continue;
                if(!(btn.userObject instanceof mindustry.content.TechTree.TechNode tn) || tn.content == null) continue;
                String n = tn.content.name;
                if(!(n.equals("connection") || n.equals("node") || n.equals("liquid-unloader"))) continue;
                arc.math.geom.Vec2 v = new arc.math.geom.Vec2(btn.x + btn.getWidth() / 2f, btn.y + btn.getHeight() / 2f);
                e.parent.localToStageCoordinates(v);
                Log.info("[drv] 节点 @: 屏幕 x=@ y=@ (截图坐标 y'=@) 材料@项 有图标=@",
                    n, (int)v.x, (int)v.y, (int)(Core.graphics.getHeight() - v.y),
                    tn.requirements.length, tn.content.uiIcon != null && tn.content.uiIcon.found());
            }
        }catch(Throwable t){ Log.err("[drv] locateTechNodes failed", t); }
    }

    static void techScan(){
        try{
            int bad = 0, total = mindustry.content.TechTree.all.size;
            for(mindustry.content.TechTree.TechNode n : mindustry.content.TechTree.all){
                if(n == null || n.content == null){ Log.err("[drv] 坏节点: content=null"); bad++; continue; }
                try{
                    if(n.requirements == null || n.finishedRequirements == null
                        || n.requirements.length != n.finishedRequirements.length){
                        Log.err("[drv] 坏节点 @: req=@ fin=@", n.content.name,
                            n.requirements == null ? "null" : n.requirements.length,
                            n.finishedRequirements == null ? "null" : n.finishedRequirements.length);
                        bad++;
                        continue;
                    }
                    for(int i = 0; i < n.requirements.length; i++){
                        // 照抄原版 canSpend 的取值顺序
                        int finAmount = n.finishedRequirements[i].amount, reqAmount = n.requirements[i].amount;
                        if(n.requirements[i].item == null){
                            Log.err("[drv] 坏节点 @ 第 @ 项 item=null", n.content.name, i);
                            bad++;
                        }
                    }
                }catch(NullPointerException e){
                    StringBuilder sb = new StringBuilder();
                    for(int i = 0; i < n.requirements.length; i++){
                        sb.append(i).append(':');
                        sb.append(n.requirements[i] == null ? "null"
                            : n.requirements[i].item == null ? "item=null" : n.requirements[i].item.name);
                        sb.append(n.finishedRequirements[i] == null ? "|fin=null " : " ");
                    }
                    Log.err("[drv] canSpend 会在 @ 上崩: @", n.content.name, sb);
                    bad++;
                }
            }
            Log.info("[drv] 科技树扫描: 总节点=@ 坏节点=@", total, bad);
            for(String bn : new String[]{"connection", "node", "liquid-unloader"}){
                Block b = Vars.content.block(bn);
                if(b == null){ Log.err("[drv] 找不到方块 @", bn); continue; }
                Log.info("[drv] 方块 @: cls=@ 有 techNode=@ techNodes=@ 解锁=@ uiIcon=@ 图标可用=@",
                    b.name, b.getClass().getName(), b.techNode != null, b.techNodes.size, b.alwaysUnlocked,
                    b.uiIcon, b.uiIcon != null && b.uiIcon.found());
            }
        }catch(Throwable t){ Log.err("[drv] techScan failed", t); }
    }

    static void shot(String name){
        try{
            closeMinimap(); // 万一哪一步误点开了小地图，之后的截图就全是小地图了
            if(counter < 0) counter = nextIndex();
            arc.files.Fi f = Core.files.absolute(outDir).child(String.format("%03d_%s.png", counter++, name));
            ScreenUtils.saveScreenshot(f);
            Log.info("[drv] shot @  (@x@)", f.name(), Core.graphics.getWidth(), Core.graphics.getHeight());
        }catch(Throwable t){ Log.err("[drv] shot failed: @", name, t); }
    }

    /** 截图前把小地图收起来（它铺满屏幕会把世界挡住）。 */
    static void closeMinimap(){
        try{
            if(Vars.ui != null && Vars.ui.minimapfrag != null && Vars.ui.minimapfrag.shown()){
                Vars.ui.minimapfrag.toggle();
                Log.info("[drv] 截图前收起了小地图");
            }
        }catch(Throwable ignored){}
    }

    /** 把 combine 的列表塞进一个独立对话框渲染（等同设置里那一页的内容）。 */
    static void showList(){
        try{
            ml = Vars.mods.getMod("combine").main.getClass().getClassLoader();
            // -Ddrv.uiscale=200 → 模拟"小逻辑宽度"（PC 上 uiscale 调大/窗口小），用来复现排版问题
            String us = System.getProperty("drv.uiscale");
            if(us != null){
                try{
                    arc.scene.ui.layout.Scl.setProduct(Float.parseFloat(us) / 100f);
                    Core.settings.put("uiscale", Integer.parseInt(us));
                    Log.info("[drv] uiscale=@（逻辑宽度 ≈ @×@）", us, Core.graphics.getWidth(), Core.graphics.getHeight());
                }catch(Throwable t){ Log.err("[drv] uiscale 设置失败", t); }
            }
            if(Vars.ui.settings != null){ try{ Vars.ui.settings.hide(); }catch(Throwable ignored){} }
            Class<?> stCls = Class.forName("mindustry.ui.dialogs.SettingsMenuDialog$SettingsTable", true, Driver.class.getClassLoader());
            Object st = stCls.getConstructor().newInstance();
            Class<?> cbl = Class.forName("combine.ui.ComboBlockList", true, ml);
            java.lang.reflect.Method build = cbl.getDeclaredMethod("build", stCls);
            build.setAccessible(true);
            build.invoke(null, st);
            Class<?> baseDlg = Class.forName("mindustry.ui.dialogs.BaseDialog", true, Driver.class.getClassLoader());
            Object d = baseDlg.getConstructor(String.class).newInstance("组合工厂");
            var cont = baseDlg.getField("cont").get(d);
            cont.getClass().getMethod("add", Class.forName("arc.scene.Element")).invoke(cont, st);
            baseDlg.getMethod("show").invoke(d);
            Log.info("[drv] list dialog shown");
        }catch(Throwable t){ Log.err("[drv] showList failed", t); }
    }

    /** 加载一张图、放两台扩展建筑、灌点东西进池子，然后打开 CoopPanel。 */
    /** 放一个炮台 + 一个装液体的伙伴 + 组合节点，然后把炮台的"配置面板"（冷却液选择）弹出来截图。 */
    static void setupCoolantScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            Block turretB = null, makerB = null, nodeB = null;
            for(Block b : Vars.content.blocks()){
                if(turretB == null && b.getClass().getName().startsWith("combine.turret.CombinedItemTurret")
                    && b instanceof mindustry.world.blocks.defense.turrets.ItemTurret it
                    && it.ammoTypes != null && it.ammoTypes.containsKey(Items.copper)) turretB = b;
                if(makerB == null && b.name.endsWith("liquid-maker")) makerB = b;
                if(nodeB == null && b.getClass().getName().equals("combine.net.ComboNode")) nodeB = b;
            }
            if(turretB == null || makerB == null || nodeB == null){
                Log.err("[drv] coolant 场景缺方块: @ @ @", turretB, makerB, nodeB);
                return;
            }
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            Building maker = place(makerB, 60, 60);
            Building turret = place(turretB, 74, 60);
            maker.items.add(Items.copper, 200);
            maker.liquids.add(Liquids.water, 300f);
            maker.liquids.add(Liquids.cryofluid, 300f);
            place(nodeB, 67, 60);
            Core.camera.position.set(turret.x, turret.y);
            // 把炮台的配置面板（冷却液选择）弹出来
            arc.scene.ui.layout.Table t2 = new arc.scene.ui.layout.Table();
            turret.getClass().getMethod("buildConfiguration", arc.scene.ui.layout.Table.class).invoke(turret, t2);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("冷却液选择（炮台配置面板）");
            d.cont.add(t2).pad(10f);
            d.show();
            Log.info("[drv] 冷却液选择面板已弹出");
        }catch(Throwable t){ Log.err("[drv] setupCoolantScene failed", t); }
    }

    /** 载入一张有核心的图，然后真的把原版发射界面弹出来（看它会不会崩 / 内置蓝图在不在）。 */
    static boolean launchShown;

    /**
     * 场1：核心 + 两台容器（并进核心）+ 一台灌了氰气的升华站。
     * 打开"方块状态"显示（renderer.drawStatus），并把容器信息面板弹出来截图对比数字。
     */
    static void setupStatusScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            // 方块状态菱形（红=noinput / 橙=nooutput / 绿=active）
            Vars.renderer.drawStatus = true;
            Core.settings.put("blockstatus", true);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block coreB = null, contB = null, subB = null;
            for(Block b : Vars.content.blocks()){
                if(b.name.equals("core-shard")) coreB = b;
                if(b.name.equals("container")) contB = b;
                if(b.name.equals("sublimate")) subB = b;
            }
            if(coreB == null || contB == null || subB == null){
                Log.err("[drv] status 场景缺方块: core=@ cont=@ sublimate=@", coreB, contB, subB);
                return;
            }
            Building core = placeBL(coreB, 60, 60);
            // 容器要**紧贴**核心才能并进核心池（中间隔一格就不算相邻）
            c1 = placeBL(contB, 63, 60);
            placeBL(contB, 65, 60);
            // 再往这一组里接一串**不同种类**的组合工厂（走节点连线）：容器面板上会出现很长的
            // "网络另有: 电解机*1 窑炉*1 …"——用来验"组合体成员显示要换行、物品条不能横跨全屏"。
            try{
                Block nodeB = null;
                for(Block b : Vars.content.blocks())
                    if(b.getClass().getName().equals("combine.net.ComboNode")){ nodeB = b; break; }
                String[] factories = {"graphite-press", "kiln", "pulverizer", "pyratite-mixer",
                    "separator", "multi-press", "silicon-smelter", "plastanium-compressor",
                    "phase-weaver", "surge-smelter", "alloy-smelter", "cryofluid-mixer"};
                Seq<Building> extra = new Seq<>();
                int ex = 74, ey = 56;
                for(String name : factories){
                    Block fb = null;
                    for(Block b : Vars.content.blocks()) if(b.name.equals(name)){ fb = b; break; }
                    if(fb == null) continue;
                    if(ex + Math.max(fb.size, 1) > 150) break;
                    Building eb = placeBL(fb, ex, ey);
                    ex += Math.max(fb.size, 1) + 1;
                    if(eb != null) extra.add(eb);
                }
                Building nd = nodeB == null ? null : placeBL(nodeB, 70, 60);
                if(nd != null){
                    tapNodeLink(nd, c1);
                    for(Building eb : extra) tapNodeLink(nd, eb);
                    Log.info("[drv] status 网络: 节点@,@ 接了 @ 台工厂", nd.tileX(), nd.tileY(), extra.size + 1);
                }
            }catch(Throwable t){ Log.err("[drv] status 网络搭建失败", t); }
            int cap = core instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild cb ? cb.storageCapacity : -1;
            // 每种物品各自装满容量：容量是"每种物品各自"的上限，
            // 以前的地板写成 items.total()（所有物品总和）→ 面板会显示成 6 倍的容量。
            Item[] types = {Items.copper, Items.lead, Items.graphite, Items.silicon, Items.metaglass, Items.titanium};
            // 故意塞到**超过**容量（用户存档就是这种历史超容存量）：上限必须还是"核心 + 容器"，
            // 不能跟着库存抬成 23074 这种数字，同时这些超容存量也不该被删。
            for(Item it : types) core.items.set(it, cap + 500);
            int total = core.items.total();

            // 升华站：只灌氰气（用户场景），方块状态菱形必须是绿的
            Building sub = placeBL(subB, 72, 60);
            sub.liquids.add(Liquids.cyanogen, 500f);
            // 镜头把三样东西都框进来：无限火力工厂 / 核心+容器 / 升华站
            Core.camera.position.set(sub.x - 56f, sub.y);

            // 无限火力（X 端的 rules.cheat）下组合单位工厂照样要产出单位：
            // 把工厂激活延迟清零、打开 cheat、进度推到完工线，几秒后它应该已经吐出一个单位载荷。
            Vars.state.rules.unitFactoryActivationDelay = 0f;
            Team.sharded.rules().unitFactoryActivationDelay = 0f;
            Block facB = null;
            for(Block blk : Vars.content.blocks())
                if(blk.getClass().getName().startsWith("combine.units.CombinedUnitFactory")){ facB = blk; break; }
            if(facB != null && ((mindustry.world.blocks.units.UnitFactory) facB).plans.size > 0){
                Building fac = placeBL(facB, 52, 60);
                Team.sharded.rules().cheat = true;
                var ufb = (mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild) fac;
                ufb.currentPlan = 0;
                ufb.progress = ((mindustry.world.blocks.units.UnitFactory) facB).plans.get(0).time - 1f;
                Log.info("[drv] 无限火力工厂: @ 计划=@ 时间=@ 池内物品=@ cheat=@",
                    facB.name, ((mindustry.world.blocks.units.UnitFactory) facB).plans.get(0).unit.name,
                    ((mindustry.world.blocks.units.UnitFactory) facB).plans.get(0).time, fac.items.total(), fac.cheating());
            }

            Log.info("[drv] status 场景: 核心静态容量=@ 已塞物品总和=@ 升华站类=@ 氰气=@",
                cap, total, subB.getClass().getName(), sub.liquids.get(Liquids.cyanogen));
            Events.on(EventType.UnitCreateEvent.class, e -> {
                unitCreates++;
                Log.info("[drv] 单位产出事件 #@: @", unitCreates, e.unit.type.name);
            });
        }catch(Throwable t){ Log.err("[drv] setupStatusScene failed", t); }
    }

    static int unitCreates = 0;

    // ---------------- 组合墙修复 ----------------
    // ---------------- 蓝图里的组合连接器 ----------------
    // ---------------- 用户复现存档：存读档物品变多 ----------------
    static int repPhase = 0;
    static int repBefore = -1;
    static String repSaveName = "sector-serpulo-223.msav";

    static int repWorldTotal(){
        java.util.IdentityHashMap<mindustry.world.modules.ItemModule, Boolean> seen = new java.util.IdentityHashMap<>();
        int total = 0;
        for(Tile t : Vars.world.tiles){
            if(t == null || t.build == null || t.build.items == null) continue;
            if(seen.put(t.build.items, Boolean.TRUE) != null) continue;
            total += t.build.items.total();
        }
        return total;
    }

    static String repCoreItems(){
        for(Building b : Vars.state.teams.get(Team.sharded).cores){
            StringBuilder sb = new StringBuilder();
            for(mindustry.type.Item it : Vars.content.items()){
                int n = b.items.get(it);
                if(n > 0) sb.append(it.name).append('=').append(n).append(' ');
            }
            return sb.toString();
        }
        return "无核心";
    }

    // ---------------- 用户报：读档之后组合建筑要"刺激电网"才更新 ----------------
    static int pwrPhase = 0;

    static void setupPowerScene(){
        try{
            hideDialogs();
            arc.files.Fi f = Vars.dataDirectory.child("saves/" + repSaveName);
            Log.info("[drv] pwr 存档=@ 存在=@", f.absolutePath(), f.exists());
            if(!f.exists()) f = Vars.dataDirectory.child("saves/" + repSaveName + "-backup.msav");
            mindustry.io.SaveIO.load(f);
            Vars.state.set(mindustry.core.GameState.State.playing);
            Log.info("[drv] pwr 载入完成 地图=@", Vars.state.map.name());
        }catch(Throwable t){ Log.err("[drv] setupPowerScene failed", t); }
    }

    static boolean pwrHasUpdater(mindustry.world.blocks.power.PowerGraph g){
        for(var e : Groups.powerGraph){
            if(e instanceof mindustry.gen.PowerGraphUpdater u && u.graph == g) return true;
        }
        return false;
    }

    /** 电网体检：每张电网列出来，看成员表/产耗/有没有 updater；再统计组合建筑的电网是否自洽。 */
    static void powerAudit(String tag){
        try{
            var byGraph = new java.util.LinkedHashMap<mindustry.world.blocks.power.PowerGraph, arc.struct.Seq<Building>>();
            int powered = 0, deadTopology = 0, deadGraph = 0, noProd = 0;
            for(Tile t : Vars.world.tiles){
                Building b = t == null ? null : t.build;
                if(b == null || !b.isValid() || b.power == null || b.power.graph == null) continue;
                powered++;
                byGraph.computeIfAbsent(b.power.graph, g -> new arc.struct.Seq<>()).add(b);
            }
            Log.info("[drv] pwr ===== @ : 有电建筑=@ 电网张数=@ =====", tag, powered, byGraph.size());
            for(var e : byGraph.entrySet()){
                var g = e.getKey();
                var members = e.getValue();
                boolean updater = pwrHasUpdater(g);
                // 自洽性：按原版连接规则重算这张电网里的建筑，看看有没有人被漏掉
                arc.struct.ObjectSet<Building> seen = new arc.struct.ObjectSet<>();
                arc.struct.Seq<Building> comp = new arc.struct.Seq<>();
                arc.struct.Queue<Building> q = new arc.struct.Queue<>();
                arc.struct.Seq<Building> tmp = new arc.struct.Seq<>();
                q.addLast(members.first()); seen.add(members.first());
                while(!q.isEmpty()){
                    Building cur = q.removeFirst();
                    comp.add(cur);
                    for(Building nb : cur.getPowerConnections(tmp)){
                        if(nb != null && nb.power != null && seen.add(nb)) q.addLast(nb);
                    }
                }
                boolean consistent = comp.size == members.size;
                if(!consistent) deadTopology++;
                if(!updater) deadGraph++;
                if(g.producers.size > 0 && g.getLastPowerProduced() <= 0f) noProd++;
                StringBuilder names = new StringBuilder();
                int shown = 0;
                for(Building b : members){
                    if(shown++ >= 8){ names.append(" +").append(members.size - 8); break; }
                    names.append(' ').append(b.block.name).append('@').append(b.tileX()).append(',').append(b.tileY());
                }
                Log.info("[drv] pwr 电网#@ 成员=@ 产=@ 耗=@ 产出=@ 需要=@ updater=@ 自洽=@ 建筑:@",
                    g.getID(), members.size, g.producers.size, g.consumers.size,
                    g.getLastPowerProduced(), g.getLastPowerNeeded(), updater, consistent, names);
            }
            Log.info("[drv] pwr @ 汇总: 有电=@ 电网=@ 不自洽电网=@ 无updater电网=@ 有待发电却产出0=@",
                tag, powered, byGraph.size(), deadTopology, deadGraph, noProd);
        }catch(Throwable t){ Log.err("[drv] powerAudit failed", t); }
    }

    static void powerStep(){
        try{
            if(pwrPhase == 0){
                powerAudit("A 读档后（没刺激过电网）");
                pwrPhase = 1;
            }else if(pwrPhase == 1){
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/pwr1.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/pwr1.msav"));
                Vars.state.set(mindustry.core.GameState.State.playing);
                pwrPhase = 2;
            }else if(pwrPhase == 2){
                powerAudit("B 存读一次后（没刺激过电网）");
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/pwr2.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/pwr2.msav"));
                Vars.state.set(mindustry.core.GameState.State.playing);
                pwrPhase = 3;
            }else if(pwrPhase == 3){
                powerAudit("C 再存读一次后（没刺激过电网）");
                Log.info("[drv] pwr 结束");
                Core.app.exit();
                pwrPhase = 4;
            }
        }catch(Throwable t){ Log.err("[drv] powerStep failed", t); }
    }

    static void setupReproScene(){
        try{
            hideDialogs();
            arc.files.Fi f = Vars.dataDirectory.child("saves/" + repSaveName);
            Log.info("[drv] rep 存档=@ 存在=@", f.absolutePath(), f.exists());
            if(!f.exists()){ // 回退：也看看备份
                f = Vars.dataDirectory.child("saves/" + repSaveName + "-backup.msav");
                Log.info("[drv] rep 换备份存档=@ 存在=@", f.absolutePath(), f.exists());
            }
            mindustry.io.SaveIO.load(f);
            Vars.state.set(mindustry.core.GameState.State.playing);
            Vars.state.set(mindustry.core.GameState.State.paused);   // 暂停：隔离生产/消耗
            Log.info("[drv] rep 载入完成 地图=@ sector=@", Vars.state.map.name(),
                Vars.state.rules.sector == null ? "null" : Vars.state.rules.sector.name());
        }catch(Throwable t){ Log.err("[drv] setupReproScene failed", t); }
    }

    static void repDump(String tag){
        try{
            Class.forName("combine.net.ComboNet", true, ml).getMethod("debugDumpPools", String.class).invoke(null, tag);
        }catch(Throwable t){ Log.err("[drv] repDump failed", t); }
    }

    static void reproStep(){
        try{
            if(repPhase == 0){
                repBefore = repWorldTotal();
                Log.info("[drv] rep A 世界总量=@ 核心物品: @", repBefore, repCoreItems());
                repDump("A 读档后");
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/repc1.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/repc1.msav"));
                repPhase = 1;
            }else if(repPhase == 1){
                int now = repWorldTotal();
                Log.info("[drv] rep B 存读一次后 世界总量=@（差 @） 核心物品: @", now, now - repBefore, repCoreItems());
                repDump("B 存读一次后");
                mindustry.io.SaveIO.save(Core.files.absolute("/tmp/cl/repc2.msav"));
                mindustry.io.SaveIO.load(Core.files.absolute("/tmp/cl/repc2.msav"));
                repPhase = 2;
            }else if(repPhase == 2){
                int now = repWorldTotal();
                Log.info("[drv] rep C 再存读一次后 世界总量=@（相对 A 差 @） 核心物品: @", now, now - repBefore, repCoreItems());
                repDump("C 再存读一次后");
                shot("repro_items");
                Log.info("[drv] rep 结束");
                Core.app.exit();
                repPhase = 3;
            }
        }catch(Throwable t){ Log.err("[drv] reproStep failed", t); }
    }

    static void setupBlueprintScene(){
        try{
            hideDialogs();
            Block conn = null;
            for(Block b : Vars.content.blocks()) if(b.getClass().getName().equals("combine.net.ComboConnector")) conn = b;
            if(conn == null){ Log.err("[drv] 没找到组合连接器"); return; }
            Log.info("[drv] 连接器=@ name=@ id=@", conn, conn.name, conn.id);
            Log.info("[drv] 连接器状态: alwaysUnlocked=@ unlocked=@ unlockedNow=@ visible=@ banned=@ placeable=@",
                conn.alwaysUnlocked, conn.unlocked(), conn.unlockedNow(), conn.isVisible(), conn.isBanned(), conn.isPlaceable());

            // 先看"启动时从磁盘读进来的"蓝图：修复前这里会缺连接器（读蓝图早于模组建方块）
            for(mindustry.game.Schematic sc : Vars.schematics.all()){
                if(!"组合连接器测试".equals(sc.tags.get("name"))) continue;
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for(mindustry.game.Schematic.Stile st : sc.tiles){
                    sb.append(st.block == null ? "air" : st.block.name).append(' ');
                    if(st.block != null && st.block.getClass().getName().equals("combine.net.ComboConnector")) n++;
                }
                Log.info("[drv] 启动时读到的老蓝图: 格数=@ 连接器=@/3 方块: @", sc.tiles.size, n, sb);
            }

            var s = new mindustry.game.Schematic(new arc.struct.Seq<>(), new arc.struct.StringMap(), 3, 1);
            for(int i = 0; i < 3; i++)
                s.tiles.add(new mindustry.game.Schematic.Stile(conn, i, 0, null, (byte)0));
            s.tags.put("name", "组合连接器测试");
            arc.files.Fi dir = Vars.dataDirectory.child("schematics");
            dir.mkdirs();
            arc.files.Fi file = dir.child("comboconn-test.msch");
            mindustry.game.Schematics.write(s, file);
            Log.info("[drv] 蓝图写好: @ (@ 字节) 格数=@", file.absolutePath(), file.length(), s.tiles.size);

            // 重新从磁盘读（= 退出重进时走的那条路）
            Vars.schematics.load();
            mindustry.game.Schematic found = null;
            for(mindustry.game.Schematic sc : Vars.schematics.all()){
                if("组合连接器测试".equals(sc.tags.get("name"))) found = sc;
            }
            if(found == null){
                Log.err("[drv] 重新 load() 后找不到这张蓝图！");
            }else{
                StringBuilder sb = new StringBuilder();
                int count = 0;
                for(mindustry.game.Schematic.Stile st : found.tiles){
                    sb.append(st.block == null ? "null" : st.block.name).append(' ');
                    if(st.block != null && st.block.getClass().getName().equals("combine.net.ComboConnector")) count++;
                }
                Log.info("[drv] 重新 load() 后: 格数=@ 连接器=@/3 方块: @", found.tiles.size, count, sb);
                var plans = Vars.schematics.toPlans(found, 10, 10, true);
                StringBuilder pb = new StringBuilder();
                int pconn = 0;
                for(var plan : plans){
                    pb.append(plan.block == null ? "null" : plan.block.name).append(' ');
                    if(plan.block != null && plan.block.getClass().getName().equals("combine.net.ComboConnector")) pconn++;
                }
                Log.info("[drv] 放到场上会生成 @ 个计划，其中连接器 @ 个: @", plans.size, pconn, pb);
            }
            Vars.ui.schematics.show();
        }catch(Throwable t){ Log.err("[drv] setupBlueprintScene failed", t); }
    }

    static Building wall1, wall2, wall3;
    static int wallFrames = -1, wallPhase = 0;

    static void setupWallScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<140;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            Block wall = null, mendBlock = null;
            for(Block b : Vars.content.blocks()){
                if(wall == null && b.name.equals("copper-wall")) wall = b;
                if(mendBlock == null && b.name.equals("mend-projector")) mendBlock = b;
            }
            final Block mend = mendBlock;
            if(wall == null || mend == null){ Log.err("[drv] wall 场景缺方块: wall=@ mend=@", wall, mend); return; }
            wall1 = placeBL(wall, 60, 60);
            wall2 = placeBL(wall, 61, 60);
            wall3 = placeBL(wall, 62, 60);
            // 修复投影要电：直接给这个队伍开 cheat
            Team.sharded.rules().cheat = true;
            wall2.damage(600f);
            Core.camera.position.set(wall2.x, wall2.y + 24f);
            Log.info("[drv] wall 场景: 1=@ 2=@ 3=@ 组=@ 血=@/@/@ 破损=@", wall1, wall2, wall3,
                field(wall1, "seqSize"), wall1.health, wall2.health, wall3.health, wall1.damaged());
            Timer.schedule(() -> {
                try{
                    placeBL(mend, 61, 56);
                    Log.info("[drv] 修复投影已放置");
                }catch(Throwable t){ Log.err("[drv] 放修复投影失败", t); }
            }, 10f);
        }catch(Throwable t){ Log.err("[drv] setupWallScene failed", t); }
    }

    // ---------------- 组合传送带（覆盖叠层提速，mode=conv） ----------------
    static Building[] convRow = new Building[8];
    /** 【2026-10-11】卸载器也能叠加：截图里要看得到它身上的层数数字。 */
    static Building[] unloaderRow = new Building[2];

    static void setupConvScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=50;y<90;y++) for(int x=50;x<160;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block conv = byName("conveyor");
            if(conv == null){ Log.err("[drv] conv 场景缺 conveyor"); return; }
            Log.info("[drv] 传送带方块类=@ uiIcon=@ region=@ 贴图行数=@", conv.getClass().getName(),
                conv.uiIcon == null ? "null" : ("found=" + conv.uiIcon.found()),
                conv.region == null ? "null" : ("found=" + conv.region.found()),
                ((mindustry.world.blocks.distribution.Conveyor)conv).regions == null ? -1
                    : ((mindustry.world.blocks.distribution.Conveyor)conv).regions.length);

            for(int i = 0; i < convRow.length; i++){
                convRow[i] = place(conv, 60 + i, 60);
            }
            // 覆盖叠层：手里选的就是这条传送带 + 方向一致 → 走组合传送带的"覆盖"通道
            Class<?> overlay = Class.forName("combine.distribution.ConveyorOverlay", true, ml);
            Class<?> layered = Class.forName("combine.distribution.Layered", true, ml);
            java.lang.reflect.Method applyLine =
                overlay.getMethod("applyLine", mindustry.gen.Player.class, Block.class);
            java.lang.reflect.Method addPreset = overlay.getMethod("addPreset", mindustry.gen.Player.class,
                arc.struct.Seq.class, Block.class, Tile.class, int.class);
            java.lang.reflect.Method isOverlayPlan =
                overlay.getMethod("isOverlayPlan", Block.class, mindustry.entities.units.BuildPlan.class);
            java.lang.reflect.Method layersGet = layered.getMethod("layers");

            // ① 桌面那套：拖一整排 → 松手提交（applyLine）；预览期间不该叠层
            arc.struct.Seq<mindustry.entities.units.BuildPlan> line = new arc.struct.Seq<>();
            for(int i = 0; i < convRow.length; i++)
                line.add(new mindustry.entities.units.BuildPlan(60 + i, 60, 0, conv, null));
            conv.handlePlacementLine(line); // 真正的方块方法：super（补桥/换路口）+ 记录这条线
            Log.info("[drv] 松手前（只有预览）第 0 格层数=@", layersGet.invoke(convRow[0]));
            int committed = (Integer) applyLine.invoke(null, Vars.player, conv);
            Log.info("[drv] 松手提交了 @ 格，第 0 格层数=@", committed, layersGet.invoke(convRow[0]));

            // ② 手机那套：放一个"覆盖预设" → 玩家按建造（✓）时原版把计划交给 onNewPlan → 才叠层
            arc.struct.Seq<mindustry.entities.units.BuildPlan> presets = new arc.struct.Seq<>();
            boolean presetAdded = (Boolean) addPreset.invoke(null, Vars.player, presets, conv,
                Vars.world.tile(62, 60), 0);
            if(presetAdded){
                var preset = presets.first();
                boolean valid = Vars.control.input.validPlace(preset.x, preset.y, preset.block, preset.rotation, null, true);
                Log.info("[drv] 覆盖预设：旋转=@（方向+@）原版 validPlace（✓ 按钮那一关）=@ 画虚影算可以放=@ 层数还=@",
                    preset.rotation, overlay.getField("PRESET_ROTATION_OFFSET").get(null), valid,
                    isOverlayPlan.invoke(null, conv, preset), layersGet.invoke(convRow[2]));
                var copy = preset.copy();
                preset.block.onNewPlan(copy); // = 原版 ✓ 按钮做的事
                Log.info("[drv] 按 ✓ 之后：层数=@，计划旋转=@（改回带子方向 @ → 会被单位丢掉，不会真造一条）",
                    layersGet.invoke(convRow[2]), copy.rotation, Vars.world.tile(62, 60).build.rotation);
            }else{
                Log.err("[drv] 覆盖预设没放下去（应该放得下）");
            }
            StringBuilder sb = new StringBuilder();
            for(int i = 0; i < convRow.length; i++){
                if(convRow[i] == null) continue;
                sb.append(i).append(":").append(layersGet.invoke(convRow[i])).append(" ");
            }
            Log.info("[drv] 传送带层数（格:层）= @", sb.toString().trim());
            // 每格塞一个物品：看得见传送带在动（也验证叠过层后物品照常走）
            for(int i = 0; i < convRow.length; i++){
                if(convRow[i] instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild cb){
                    cb.handleStack(Items.copper, 1, null);
                }
            }
            // 【用户 2026-10-11："让 unloader 也能叠加"】摆两台卸载器，右边那台叠一层 ——
            // 世界截图里要能看到它身上有个"2"（和传送带同一套层数徽标），
            // 详情面板截图里要能看到"每叠一层卸载速度翻一倍"那行提示。
            Block unloader = byName("unloader");
            if(unloader != null && unloader.getClass().getName().contains("CombinedUnloader")){
                Vars.state.rules.infiniteResources = true; // 叠层要扣料/退料，展示场景不折腾核心库存
                unloaderRow[0] = place(unloader, 60, 63);
                unloaderRow[1] = place(unloader, 66, 63);
                mindustry.entities.units.BuildPlan up = new mindustry.entities.units.BuildPlan(66, 63, 0, unloader, null);
                unloader.onNewPlan(up); // = 原版在松手/按 ✓ 时做的事
                Log.info("[drv] 卸载器: 类=@ rotate=@ 层数=@ 说明=@", unloader.getClass().getName(), unloader.rotate,
                    layersOf(unloaderRow[1]), unloader.description == null ? "null" : unloader.description.replace('\n', '|'));
            }else{
                Log.err("[drv] 卸载器没被换成组合版：@", unloader == null ? "null" : unloader.getClass().getName());
            }
            // 【2026-10-10】镜头钉到这条试验带上并拉近，截图要能看清"带子上叠了几层"的数字
            // （用户要求"数字显示放在传送带身上"）。以前镜头默认跟着玩家单位（在核心那边），
            // 8 格试验带根本不在画面里，看不出层数、也看不清贴图/物品。
            Core.settings.put("detach-camera", true); // 关掉"镜头跟随玩家"，否则每帧都会被拉回单位
            Core.camera.position.set((60 + convRow.length / 2f) * 8f, 60 * 8f);
            try {
                Vars.renderer.setScale(3.5f);
            } catch (Throwable ignored) {
            }
        }catch(Throwable t){ Log.err("[drv] setupConvScene failed", t); }
    }

    static void convOpenInfo(){
        try{
            Block conv = byName("conveyor");
            if(conv == null) return;
            Log.info("[drv] 传送带详情: name=@ desc=@", conv.localizedName,
                conv.description == null ? "null" : conv.description.replace('\n', '|'));
            Vars.ui.content.show(conv);
        }catch(Throwable t){ Log.err("[drv] convOpenInfo failed", t); }
    }

    /** 【2026-10-11】卸载器详情面板：说明里应该有"每叠一层卸载速度翻一倍"。 */
    static void convOpenInfoUnloader(){
        try{
            Block unloader = byName("unloader");
            if(unloader == null) return;
            Log.info("[drv] 卸载器详情: name=@ desc=@", unloader.localizedName,
                unloader.description == null ? "null" : unloader.description.replace('\n', '|'));
            Vars.ui.content.show(unloader);
        }catch(Throwable t){ Log.err("[drv] convOpenInfoUnloader failed", t); }
    }

    /** 读一个建筑的覆盖层数（combine 的 Layered 接口，模组 classloader）。 */
    static int layersOf(Building b){
        try{
            Class<?> layered = Class.forName("combine.distribution.Layered", true, ml);
            return (Integer) layered.getMethod("layers").invoke(b);
        }catch(Throwable t){ return -1; }
    }

    static void wallRepairStep(){
        try{
            if(wall1 == null) return;
            if(wallFrames < 0){
                wallFrames = frames;
                return;
            }
            if(frames - wallFrames < 30) return;
            if(wallPhase == 0){
                shot("wall_damaged");
                Log.info("[drv] wall 残血: 血=@/@/@ 破损=@", wall1.health, wall2.health, wall3.health, wall1.damaged());
                wallPhase = 1;
                wallFrames = -1;
            }else if(wallPhase == 1 && !wall1.damaged() && !wall2.damaged() && !wall3.damaged()){
                shot("wall_repaired");
                Log.info("[drv] wall 已修满: 血=@/@/@ 破损=@", wall1.health, wall2.health, wall3.health, wall1.damaged());
                wallPhase = 2;
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] wallRepairStep failed", t); }
    }

    // ---------------- 核心单位：修废墟 / 重建 / 批量改方向 ----------------
    static int rbPhase = 0, rbFrames = -1, rbRuins = 0, rbBroken = 0, rbRotConvs = 0, rbStartTick = 0;
    static Block rbWall, rbConv;

    static void setupRebuildScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.derelictRepair = true;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<150;y++) for(int x=30;x<210;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            for(Block b : Vars.content.blocks()){
                if(rbWall == null && b.name.equals("copper-wall")) rbWall = b;
                if(rbConv == null && b.name.equals("conveyor")) rbConv = b;
            }
            if(rbWall == null || rbConv == null){ Log.err("[drv] rebuild 场景缺方块 wall=@ conv=@", rbWall, rbConv); return; }

            // 找一块陆地
            int ox = -1, oy = -1;
            outer:
            for(int y = 45; y < 130; y++){
                for(int x = 35; x < 190; x++){
                    boolean ok = true;
                    for(int dy = 0; dy < 22 && ok; dy++) for(int dx = 0; dx < 22; dx++){
                        Tile t = Vars.world.tile(x + dx, y + dy);
                        if(t == null || t.floor().isDeep() || t.block() != Blocks.air){ ok = false; break; }
                    }
                    if(ok){ ox = x; oy = y; break outer; }
                }
            }
            if(ox < 0){ Log.err("[drv] 没找到陆地"); return; }
            rdOx = ox; rdOy = oy;

            // 玩家的核心 + 材料（重建要吃料）
            Building core = placeBL(Blocks.coreShard, ox + 18, oy + 18);
            if(core != null && core.items != null){
                for(Item it : Vars.content.items()) core.items.set(it, 4000);
            }
            // 4x4 面 derelict 废墟
            for(int y=0;y<4;y++) for(int x=0;x<4;x++){
                Building w = placeBL(rbWall, ox + 2 + x, oy + 6 + y);
                if(w != null) w.changeTeam(Team.derelict);
            }
            // 4 台"被摧毁的建筑"（队伍计划表：原版 rebuildArea 的第一段来源）
            Vars.player.team().data().plans.clear();
            for(int x=0;x<4;x++){
                Vars.player.team().data().plans.addLast(new Teams.BlockPlan(ox + 8 + x, oy + 6, (short)0, rbConv, null));
            }
            // 一排方向 0 的传送带（后面拖一排方向 1 的计划 = 批量改方向）
            for(int x=0;x<8;x++){
                Building c = placeBL(rbConv, ox + 2 + x, oy + 2);
                if(c != null){ c.rotation = 0; try{ c.updateProximity(); }catch(Throwable ignored){} }
            }
            // 把镜头对到场景中央（能同时看到三块）
            Core.camera.position.set((ox + 6) * 8f, (oy + 6) * 8f);

            // 真客户端里 player.unit() 默认是 null（玩家还没接管单位）：
            // 手动造一台核心单位（= 核心机，本模组按核心尺寸给它挂了多把建造武器）并接管它，
            // 这样下面调原版 InputHandler.rebuildArea 才走得通（它要求 player.isBuilder()）。
            Unit u = Vars.player.unit();
            if(u == null){
                u = ((mindustry.world.blocks.storage.CoreBlock) Blocks.coreShard).unitType.create(Vars.player.team());
                u.set((ox + 6) * 8f, (oy + 6) * 8f);
                u.add();
                Vars.player.unit(u);
            }else{
                u.set((ox + 6) * 8f, (oy + 6) * 8f);
            }
            int mountCount = 0;
            for(var wm : u.mounts) if(wm.weapon != null && wm.weapon.getClass().getName().equals("combine.MultiBuildWeapon")) mountCount++;
            Log.info("[drv] rebuild 场景: 原点=@,@ 核心=@ 废墟=16 待重建=4 玩家单位=@ 建造挂座=@",
                ox, oy, core, u.type.name, mountCount);

            rbRuins = 16; rbBroken = 4; rbRotConvs = 8;
        }catch(Throwable t){ Log.err("[drv] setupRebuildScene failed", t); }
    }

    /** 场景摆好后（截图"改之前"）再下命令：原版 B 键框选 + 拖一排原地转向。 */
    static void rebuildGo(){
        try{
            Unit u = Vars.player.unit();
            if(u == null){ Log.err("[drv] rebuildGo: 玩家没单位"); return; }
            shot("rebuild_before");
            // 原版 B 键框选（真流程）：一次把 废墟 + 被摧毁建筑 全排进玩家单位的队列
            Vars.control.input.rebuildArea(rdOx, rdOy + 2, rdOx + 12, rdOy + 10);
            // 再拖一排原地转向计划（和玩家拖着传送带划过自己那一排生成的一样）
            for(int x=0;x<8;x++) u.addBuild(new mindustry.entities.units.BuildPlan(rdOx + 2 + x, rdOy + 2, 1, rbConv, null));
            rbStartTick = (int)Vars.state.tick;
            Log.info("[drv] rebuild 场景: 排好计划 @ 条（框选 + 8 条转向）", u.plans().size);
        }catch(Throwable t){ Log.err("[drv] rebuildGo failed", t); }
    }

    static void rebuildStep(){
        try{
            if(rbFrames < 0){ rbFrames = frames; return; }
            int repaired = 0, rebuilt = 0, rotated = 0;
            for(Tile t : Vars.world.tiles){
                if(t == null || t.build == null) continue;
                if(t.block() == rbWall && t.team() == Team.sharded) repaired++;
                else if(t.block() == rbConv && t.build.rotation == 1) rotated++;
            }
            // 重建好的传送带（被摧毁建筑那 4 格）
            for(int x = 0; x < 4; x++){
                Tile t = Vars.world.tile(rdOx + 8 + x, rdOy + 6);
                if(t != null && t.block() == rbConv && t.build != null) rebuilt++;
            }
            Unit u = Vars.player.unit();
            int queue = u == null || u.plans() == null ? -1 : u.plans().size;
            Log.info("[drv] rebuild 检查@: 废墟修好=@/@ 重建成=@/@ 转向=@/@ 队列=@ 暂停=@ tick=@ state=@ isGame=@ 单位=@(@,@) updateBuilding=@ canBuild=@",
                rbPhase, repaired, rbRuins, rebuilt, rbBroken, rotated, rbRotConvs, queue,
                Vars.state.isPaused(), (int)Vars.state.tick,
                Vars.state.getState(), Vars.state.isGame(),
                u == null ? "null" : u.type.name, u == null ? -1 : (int)(u.x / 8), u == null ? -1 : (int)(u.y / 8),
                u == null ? "-" : u.updateBuilding(), u == null ? "-" : u.canBuild());
            if(rbPhase == 0){
                rbPhase = 1;
            }else if(repaired >= rbRuins && rebuilt >= rbBroken && rotated >= rbRotConvs){
                shot("rebuild_after");
                Log.info("[drv] rebuild 完成: 废墟=@ 重建=@ 转向=@ (用时 @ 渲染帧 / @ 逻辑 tick)",
                    repaired, rebuilt, rotated, frames - rbFrames, (int)Vars.state.tick - rbStartTick);
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] rebuildStep failed", t); }
    }

    static int rdOx = -1, rdOy = -1;

    // ---------------- 超级组合炮台：框选 → 虚影 → 放置 ----------------
    static int stOx = -1, stOy = -1, stTx = -1, stTy = -1;
    static int stPhase = 0;
    static int stFrames = -1;
    static int stTries = 0;
    static String stLayout = "";

    static void setStatic(Class<?> c, String name, Object value) {
        try {
            var f = c.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Throwable t) {
            Log.err("[drv] 写静态字段 @ 失败", name, t);
        }
    }

    static Object getStatic(Class<?> c, String name) {
        try {
            var f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 深度优先收集所有名字匹配的元素（排查"同名元素有几个"用）。 */
    static void collectByName(arc.scene.Element root, String name, Seq<arc.scene.Element> out) {
        if (root == null) return;
        if (name.equals(root.name)) out.add(root);
        if (root instanceof arc.scene.Group g)
            for (arc.scene.Element c : g.getChildren())
                collectByName(c, name, out);
    }

    /** 直接子元素里名字匹配的个数。 */
    static int countChildren(arc.scene.Element e, String name) {
        int n = 0;
        if (e instanceof arc.scene.Group g)
            for (arc.scene.Element c : g.getChildren())
                if (name.equals(c.name)) n++;
        return n;
    }

    /** 收集所有"图标是 turret"的 ImageButton（用来确认可见的那个就是模组插进去的）。 */
    static void collectTurretButtons(arc.scene.Element root, Seq<arc.scene.Element> out) {
        if (root == null) return;
        if (root instanceof arc.scene.ui.ImageButton ib
            && ib.getStyle() != null && ib.getStyle().imageUp == mindustry.gen.Icon.turret) out.add(ib);
        if (root instanceof arc.scene.Group g)
            for (arc.scene.Element c : g.getChildren())
                collectTurretButtons(c, out);
    }

    static Object getField(Object o, String name) {
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    var f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 总开关（SuperTurret.enabled）；停用时方块没注册，本模式要跳过。 */
    /** 停用检查：建造菜单里（= PlacementFragment 的取列表函数）不该再有超级组合炮台。 */
    static Building pdSup;

    /** 摆一台"里面是两格点防炮"的超级组合炮台（mode=pd 用）。 */
    /**
     * 【用户报"在其他星球的地图上合体炮台无法建造"】
     *
     * 注意它**故意不在建造菜单里**（{@code isVisible()==false}，入口是 HUD 上的悬浮按钮），
     * 所以"能不能建造"看的是放置判据：{@code Build.validPlace} → {@code validPlaceIgnoreUnits}
     * 里的 {@code type.environmentBuildable()}（原版 = {@code isOnPlanet(state.getPlanet())}）。
     * 这里在真客户端里把 rules.planet 换成"别的星球"，正反各验一次。
     */
    /** 摆一排"要被框选"的炮台：4 台 duo + 2 台 scatter。 */
    static void placeTurretRow(int tx, int ty) {
        Block duo = null, scatter = null;
        for (Block b : Vars.content.blocks()) {
            if (duo == null && b.name.equals("duo"))
                duo = b;
            if (scatter == null && b.name.equals("scatter"))
                scatter = b;
        }
        if (duo == null || scatter == null)
            return;
        for (int i = 0; i < 4; i++)
            placeBL(duo, tx + i, ty);
        placeBL(scatter, tx + 5, ty);
        placeBL(scatter, tx + 7, ty); // 2x2 之间留一格：挨着放会重叠成一台（origin 只差 1 格）
    }

    /**
     * 框选用的炮台：一排 6 台 1x1/2x2（duo/scatter）+ 一台 **4x4**（foreshadow）。
     *
     * <p>4x4 那台是给"一个 4*4 的炮台合体后只占一格"这条要求当样板的：
     * 截图里它必须整台缩进 3x3 超级炮台的**一格**里（不是铺满 4 格）。
     */
    static void placeTurretSources(int tx, int ty) {
        placeTurretRow(tx, ty);
        Block big = findTurret4x4();
        if (big != null)
            placeBL(big, tx, ty + 2);
        // 再塞一台 4x4 的 afflict（需热炮台）：超级炮台的悬浮面板"热量"行才有真实分母
        // （afflict heatRequirement=20；旧代码会写死成单台标记值 10，一眼能看出区别）。
        Block afflict = null;
        for (Block b : Vars.content.blocks())
            if (afflict == null && b.name.equals("afflict")) { afflict = b; break; }
        if (afflict != null)
            placeBL(afflict, tx + 4, ty + 2);
    }

    /**
     * 再放一台"drawer 带 parts 的炮台"（foreshadow 的 parts 是 0 个，看不出这条要求）。
     *
     * <p>用户要求"合体炮台显示的时候要把 drawer 的部件画上"：截图里这一格的炮台
     * 必须和世界里的原版炮台长得一样（有炮管/护板那些 parts），不是只剩底板。
     */
    /** 虚影用的假 build 能不能造出来（造不出来就只能退回整块贴图）。 */
    /** 一台 4x4 的原版炮台（foreshadow → meltdown → spectre…取第一个 size>=4 的）。 */
    static Block findTurret4x4() {
        for (String n : new String[] { "foreshadow", "meltdown", "spectre", "cyclone", "ripple" }) {
            for (Block b : Vars.content.blocks())
                if (b.name.equals(n) && b.size >= 4)
                    return b;
        }
        return null;
    }

    /** 反射调静态方法（一律 setAccessible：模组那边有些方法是包级可见的）。 */
    /** 反射调实例方法（取返回值，失败返回 null）。 */
    static Object invokeVirtual(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                var m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m.invoke(o);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    static Object invokeStatic(Class<?> c, String name, Class<?>[] sig, Object... args) {
        try {
            var m = sig == null ? c.getDeclaredMethod(name) : c.getDeclaredMethod(name, sig);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            Log.err("[drv] 调用 @.@ 失败", c.getSimpleName(), name, t);
            return null;
        }
    }

    /** 保证玩家有一个建造单位（框选/放置都要求 player.isBuilder()）。 */
    static Unit ensurePlayerUnit(float x, float y) {
        Unit u = Vars.player.unit();
        if (u == null) {
            u = ((mindustry.world.blocks.storage.CoreBlock) Blocks.coreShard).unitType.create(Vars.player.team());
            u.set(x, y);
            u.add();
            Vars.player.unit(u);
            Log.info("[drv] 造了玩家核心单位 @（@,@）", u.type.name, (int) (x / 8), (int) (y / 8));
        } else {
            u.set(x, y);
        }
        // 【必须】单位身上的"执行建造计划"开关：真人操作时由 Player.unit() 打开，
        // 驱动自己造出来的单位得手动开，否则 flushPlans 塞进队列的计划永远不执行。
        try {
            u.updateBuilding(true);
        } catch (Throwable t) {
            Log.err("[drv] 打开单位建造开关失败", t);
        }
        return u;
    }

    /**
     * 把鼠标摆到屏幕 (mx,my)（拿不到 setter 的实现在字段上反射写），失败也不抛。
     *
     * <p>软渲染的离屏窗口里没人动鼠标，位置全看 SDL 初始值：不主动设的话虚影可能落在
     * 屏幕角落/被 HUD 挡住，截图就白拍了。
     */
    static void setMouse(int mx, int my) {
        try {
            Class<?> c = Core.input.getClass();
            while (c != null) {
                for (String n : new String[] { "mouseX", "mouseY" }) {
                    try {
                        var f = c.getDeclaredField(n);
                        f.setAccessible(true);
                        f.setInt(Core.input, n.equals("mouseX") ? mx : my);
                    } catch (NoSuchFieldException ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            Log.err("[drv] 设置鼠标位置失败（继续用当前位置）", t);
        }
    }

    /**
     * 让虚影（跟随鼠标）落在世界点 (wx,wy) 上：量出"屏幕像素 → 世界"的线性映射再反解鼠标坐标。
     *
     * <p>调用前相机必须已经就位并**过了一帧**（{@code mouseWorld()} 读的是上一帧的相机矩阵，
     * 同一帧里改相机再量是量不准的，见 superTurretFinish → superTurretAim 分两步的原因）。
     */
    static void aimGhost(float wx, float wy) {
        try {
            // 注意：mouseWorld(x,y) 返回的是**同一个** mouseReturn 对象，必须当场把数值抄下来
            arc.math.geom.Vec2 t = Core.input.mouseWorld(0, 0);
            float ax = t.x, ay = t.y;
            t = Core.input.mouseWorld(1, 0);
            float kx = t.x - ax, ky = t.y - ay;
            t = Core.input.mouseWorld(0, 1);
            float lx = t.x - ax, ly = t.y - ay;
            float det = kx * ly - lx * ky;
            if (Math.abs(det) < 1e-6f) {
                Log.err("[drv] 虚影瞄准失败：屏幕→世界映射退化（k=(@,@) l=(@,@)）", kx, ky, lx, ly);
                return;
            }
            float dx = wx - ax, dy = wy - ay;
            int mx = Math.round((dx * ly - lx * dy) / det);
            int my = Math.round((kx * dy - dx * ky) / det);
            int beforeX = Core.input.mouseX(), beforeY = Core.input.mouseY();
            setMouse(mx, my);
            arc.math.geom.Vec2 now = Core.input.mouseWorld();
            Log.info("[drv] 虚影瞄准: 鼠标 (@,@)→(@,@) 想放 (@,@) 目标=(@,@) 现在指到=(@,@) 相机=(@,@) input=@",
                    beforeX, beforeY, Core.input.mouseX(), Core.input.mouseY(), mx, my,
                    (int) (wx / 8), (int) (wy / 8),
                    (int) (now.x / 8), (int) (now.y / 8),
                    (int) (Core.camera.position.x / 8), (int) (Core.camera.position.y / 8),
                    Core.input.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.err("[drv] 虚影瞄准失败", t);
        }
    }

    // ---------------- 组合工厂（合体工厂） ----------------
    static int fcOx, fcOy;
    /** 框选出来的工厂要合体到的锚点（虚影/落地位置），比 fcOx/fcOy 居中一点。 */
    static int fcGx, fcGy;
    static Building fcMerged;
    static int fcFeedTries;

    /** 对照场景：一个 2x2 原版机器 + 四角 1x1 小方块（量贴图和格子的对齐关系）。 */
    static void setupAlignScene() {
        try {
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.infiniteResources = true;
            Vars.logic.play();
            if (Vars.state.isPaused())
                Vars.state.set(mindustry.core.GameState.State.playing);
            for (int y = 40; y < 150; y++)
                for (int x = 30; x < 210; x++) {
                    Tile t = Vars.world.tile(x, y);
                    if (t != null && t.block() != Blocks.air)
                        t.setBlock(Blocks.air);
                }
            // 2x2 机器放 (60,60)（覆盖 60..61）；四角放 1x1 的 router 当"格子标尺"
            Block mixer = Vars.content.block("cryofluid-mixer");
            Block ruler = Blocks.router;
            place(mixer, 60, 60);
            place(ruler, 59, 59);
            place(ruler, 62, 59);
            place(ruler, 59, 62);
            place(ruler, 62, 62);
            // 同一张图里再摆一个**合体块**（4 台同型号）用同样的四角标尺围着，直接对比对齐
            Block cf2 = Vars.content.block("super-combine-factory-2");
            Object cfg = combineCall("combine.production.SuperCombineFactory", "encodeConfig",
                    new Class<?>[] { String.class, String.class, String[].class },
                    "cryofluid-mixer@0;cryofluid-mixer@0;cryofluid-mixer@0;cryofluid-mixer@0", "", null);
            if (cf2 != null) {
                mindustry.gen.Call.constructFinish(Vars.world.tile(70, 60), cf2, null, (byte) 0,
                        Team.sharded, cfg);
                place(ruler, 69, 59);
                place(ruler, 72, 59);
                place(ruler, 69, 62);
                place(ruler, 72, 62);
            }
            runLogical(5);
            Building b = Vars.world.build(60, 60);
            Log.info("[drv] align: 机器 build.x=@ y=@ offset=@ size=@（世界格 60..61）",
                    b == null ? -1 : b.x, b == null ? -1 : b.y, mixer.offset, mixer.size);
            Building m = Vars.world.build(70, 60);
            Log.info("[drv] align: 合体块 build.x=@ y=@ offset=@ size=@（世界格 70..71）",
                    m == null ? -1 : m.x, m == null ? -1 : m.y, cf2 == null ? -1 : cf2.offset,
                    cf2 == null ? -1 : cf2.size);
            // 3x3 对照：原版 3x3 机器 vs 合体 3x3（5 台），各自四角摆标尺
            Block press = Vars.content.block("multi-press");
            if (press != null && press.size == 3) {
                place(press, 90, 60);
                place(ruler, 88, 58);
                place(ruler, 92, 58);
                place(ruler, 88, 62);
                place(ruler, 92, 62);
            }
            Block cf3 = Vars.content.block("super-combine-factory-3");
            Object cfg3 = combineCall("combine.production.SuperCombineFactory", "encodeConfig",
                    new Class<?>[] { String.class, String.class, String[].class },
                    "cryofluid-mixer@0;cryofluid-mixer@0;cryofluid-mixer@0;cryofluid-mixer@0;cryofluid-mixer@0",
                    "", null);
            if (cf3 != null) {
                mindustry.gen.Call.constructFinish(Vars.world.tile(105, 60), cf3, null, (byte) 0,
                        Team.sharded, cfg3);
                place(ruler, 103, 58);
                place(ruler, 107, 58);
                place(ruler, 103, 62);
                place(ruler, 107, 62);
            }
            runLogical(5);
            Log.info("[drv] align 3x3: 原版锚点 build.x=@ 合体块 build.x=@（原版机 90,60 合体 105,60）",
                    Vars.world.build(90, 60) == null ? -1 : Vars.world.build(90, 60).x,
                    Vars.world.build(105, 60) == null ? -1 : Vars.world.build(105, 60).x);
            Core.camera.position.set(97f * 8f, 61f * 8f);
        } catch (Throwable t) {
            Log.err("[drv] setupAlignScene failed", t);
        }
    }

    static void runLogical(int frames) {
        for (int i = 0; i < frames; i++) {
            arc.util.Time.delta = 1f;
            Vars.logic.update();
        }
    }
    /** 框选那一步生成的虚影配置/位置：后面直接落地用（驱动里不等建造单位慢慢造）。 */
    /** 框选那一步数到的工厂台数（决定合体块边长）。 */
    /** 展开/收起浮标旁边的子按钮（现在只剩「矿物阴影」）——截图用。 */
    static void toggleMergeSubButtons() {
        try {
            combineCall("combine.ui.ComboHudButton", "toggleSubButtons", null);
        } catch (Throwable t) {
            Log.err("[drv] toggleMergeSubButtons failed", t);
        }
    }

    /** HUD 浮标的类（合体建筑独立到 merge 后，浮标只剩「矿物阴影」这一个入口）。 */
    static Class<?> hudButtonCls() throws Exception {
        return Class.forName("combine.ui.ComboHudButton", true, ml);
    }

    /** 截图前把浮标挪到屏幕左半边（子按钮应该在它右边），并把子按钮的位置打进日志。 */
    static void prepareSubButtonShot() {
        try {
            Class<?> cls = hudButtonCls();
            Object btn = getStatic(cls, "button");
            if (!(btn instanceof arc.scene.Element e) || e.parent == null) {
                Log.err("[drv] 子按钮截图准备: 找不到浮标（ComboHudButton.button=null）");
                return;
            }
            e.setPosition(70f, e.parent.getHeight() / 2f);
            java.lang.reflect.Method pos = cls.getDeclaredMethod("positionSubButtons");
            pos.setAccessible(true);
            // 先确保展开（前面的用例可能点过浮标，把子按钮收起/展开了，状态不定）
            java.lang.reflect.Method hide = cls.getDeclaredMethod("hideSubButtons");
            hide.setAccessible(true);
            java.lang.reflect.Method show = cls.getDeclaredMethod("showSubButtons");
            show.setAccessible(true);
            hide.invoke(null);
            show.invoke(null);
            pos.invoke(null);
            arc.scene.Element sP = (arc.scene.Element) getStatic(cls, "productionBtn");
            Log.info("[drv] 子按钮截图准备: 浮标=@,@ 矿物阴影子按钮=@,@ （父=@）",
                (int) e.x, (int) e.y,
                sP == null ? -1 : (int) sP.x, sP == null ? -1 : (int) sP.y,
                e.parent.getClass().getSimpleName());
        } catch (Throwable t) {
            Log.err("[drv] prepareSubButtonShot failed", t);
        }
    }

    // ---------------- 矿物阴影（新按钮：框矿物地板 → 阴影上放钻头缩到 1x1） ----------------

    /**
     * HUD 浮标自检：挂上了没有、位置/尺寸合理、点得到（没被别的 HUD 层盖住）、
     * 而且**没有**去改原版的 input 处理器（用户 2026-09-28 的要求）。
     */
    static void checkHudButton() {
        try {
            Class<?> cls = hudButtonCls();
            Object btn = getStatic(cls, "button");
            if (!(btn instanceof arc.scene.Element e) || e.parent == null) {
                Log.err("[drv] FAIL hudbtn: 浮标没挂上（button=@）", btn);
                return;
            }
            var in = Vars.control.input;
            boolean vanillaInput = in != null
                && (in.getClass().getName().equals("mindustry.input.DesktopInput")
                    || in.getClass().getName().equals("mindustry.input.MobileInput")
                    || in.getClass().getName().startsWith("mindustryX.input"));
            float cx = e.x + e.getWidth() / 2f, cy = e.y + e.getHeight() / 2f;
            arc.scene.Element hit = Core.scene.root.hit(cx, cy, true);
            Log.info("[drv] hudbtn: 浮标=@,@ 尺寸=@x@ 命中=@ input=@", (int) e.x, (int) e.y,
                (int) e.getWidth(), (int) e.getHeight(),
                hit == null ? "null" : hit.getClass().getSimpleName(),
                in == null ? "null" : in.getClass().getName());
            boolean okPos = e.x >= 0 && e.y >= 0
                && e.x + e.getWidth() <= e.parent.getWidth() + 0.5f
                && e.y + e.getHeight() <= e.parent.getHeight() + 0.5f;
            if (!okPos)
                Log.err("[drv] FAIL hudbtn: 浮标跑出屏幕了");
            if (!vanillaInput)
                Log.err("[drv] FAIL hudbtn: input 处理器被改了（@）", in == null ? "null" : in.getClass());
        } catch (Throwable t) {
            Log.err("[drv] checkHudButton failed", t);
        }
    }
    static int shOx, shOy;
    /** 阴影模式的逐帧流水线步号（-1 = 没在跑）。 */
    static int shStep = -1;

    static void startShadowPipeline() {
        shStep = 0;
        arc.Events.run(mindustry.game.EventType.Trigger.update, Driver::shadowPipeline);
    }

    /** 每帧推进一步：改状态和截图之间隔一帧，否则截到的是上一帧（软渲染 1~5fps）。 */
    static void shadowPipeline() {
        int s = shStep++;
        switch (s) {
            case 0 -> prepareSubButtonShot();
            case 2 -> shot("shadow_subbuttons");
            case 4 -> shadowFrameStart();
            // 【框选中的那一帧】用户 2026-10-09 报"框矿物的时候没有线条边框"——先留一张拖框中的图
            case 6 -> {
                // 截图前把"框选状态"打出来，并**强制钉住**（驱动造的单位 isBuilder=false，
                // 真实玩家不会有这个问题；钉住是为了单独验证"框到底画不画得出来"）
                try {
                    Class<?> p2 = Class.forName("combine.production.OreShadowPlacer", true, ml);
                    setStatic(p2, "selecting", true);
                    setStatic(p2, "dragging", true);
                    setStatic(p2, "sx", shOx + 2);
                    setStatic(p2, "sy", shOy + 2);
                    setStatic(p2, "ex", shOx + 11);
                    setStatic(p2, "ey", shOy + 6);
                    Log.info("[drv] 截图前 selecting=@ sx=@ sy=@ ex=@ ey=@  诊断: isGame=@ player=@ isBuilder=@ block=@ chat=@ kb=@",
                            getStatic(p2, "selecting"),
                            getStatic(p2, "sx"), getStatic(p2, "sy"), getStatic(p2, "ex"), getStatic(p2, "ey"),
                            Vars.state.isGame(), Vars.player != null,
                            Vars.player == null ? "-" : Vars.player.isBuilder(),
                            Vars.control.input == null ? "-" : Vars.control.input.block,
                            Vars.ui.chatfrag.shown(), Core.scene.hasKeyboard());
                } catch (Throwable t) {
                    Log.err("[drv] 查框选状态失败", t);
                }
            }
            // 【截图隔一帧】ScreenUtils 抓的是"上一帧"画面；而且驱动造的单位 isBuilder=false，
            // OreShadowPlacer.update 每帧都会取消框选 —— 所以下面两帧连着钉住，截图才不会抓到被取消的那帧。
            case 7 -> shadowPinFrame();
            case 8 -> shot("shadow_framing");
            case 10 -> shadowFrameFinish();
            case 12 -> shot("shadow_zone");
            case 14 -> shadowDropDrill();
            // 【预设那一帧】队列里的那颗 plan（用户说的"预设"）到底画成什么：1x1 的方块 + 哪张图？
            case 15 -> shot("shadow_preset");
            // 【真·落地检查】把一台钻头**按真人的路子**排进建造队列（player.unit().addBuild），
            // 让游戏自己走 validPlace + 施工那一步 —— 用户报的"阴影上摆钻头、点建造后钻头直接消失"
            // 就是这一步失败（hidden 方块默认 isPlaceable()=false / offset 偏半格导致 bounds 不过）。
            case 18 -> shadowVerifyPlaced("排队后");
            case 24 -> shadowVerifyPlaced("等施工后");
            case 26 -> shot("shadow_drill");
            // 【预设虚影单独验】再排一颗（阴影上的空位）后把建造单位**挪远**，让 plan 停在队列里
            // （不施工）——这样截到的就是纯"预设"那一帧：压格钻头的虚影该是"缩进 1 格的钻头"，
            // 不是"1x1 的空框 + 旁边挂着原来那张大图"（用户 2026-10-09 报的）。
            case 28 -> shadowQueueGhost();
            case 30 -> shot("shadow_preset_ghost");
            // 【用户 2026-10-10 报"合体钻头放置时预设变成 1x1 了，但 canplace 判断还是原来的尺寸"】
            // 走真·拖线（原版 updateLine → block.handlePlacementLine）造计划，再看 linePlans 里存的是什么方块
            case 32 -> shadowLinePresetStart();
            case 34 -> shot("shadow_line_plans");
            // 手机那条路：touchUp 走的就是 flushSelectPlans(linePlans) → 预设存进 selectPlans
            case 36 -> shadowMobilePreset();
            case 38 -> shot("shadow_select_preset");
            // 【拖拽虚影可见性】在一片干净（没有已建钻头/采矿烟）的阴影行上再拖一段：专门截"拖拽中的虚影"
            case 40 -> shadowCleanDragStart();
            case 42 -> {
                // 截图前把"手里的方块 / 模式"再钉一次：驱动是拿反射造的拖拽，中间几帧可能被游戏自己复位
                // （block 一旦是 null，原版那段"拖拽预览"整段就不会画，截图上当然什么都没有）
                try {
                    var in = Vars.control.input;
                    Log.info("[drv] 拖拽预览状态：block=@ mode=@（DesktopInput）linePlans=@",
                            in.block == null ? "null" : in.block.name,
                            in instanceof mindustry.input.DesktopInput d ? String.valueOf(d.mode) : "-",
                            in.linePlans.size);
                    if (in.block == null)
                        in.block = Vars.content.block("mechanical-drill");
                    if (in instanceof mindustry.input.DesktopInput d && d.mode != mindustry.input.PlaceMode.placing)
                        d.mode = mindustry.input.PlaceMode.placing;
                } catch (Throwable t) {
                    Log.err("[drv] 拖拽预览状态检查失败", t);
                }
                shot("shadow_drag_ghosts");
            }
            case 44 -> {
                Log.info("[drv] shadow 模式结束");
                Core.app.exit();
            }
            default -> {
            }
        }
    }

    /** 开一张干净图 + 铺一片铜矿，相机对准它。 */
    static void setupShadowScene() {
        try {
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.state.rules.infiniteResources = true;
            Vars.logic.play();
            if (Vars.state.isPaused())
                Vars.state.set(mindustry.core.GameState.State.playing);
            for (int y = 30; y < 160; y++)
                for (int x = 20; x < 200; x++) {
                    Tile t = Vars.world.tile(x, y);
                    if (t != null && t.block() != Blocks.air)
                        t.setBlock(Blocks.air);
                }
            int ox = 60, oy = 70;
            // 铺一片铜矿（阴影只认矿物地板）：地板换成石头、矿用 overlay 叠上去 ——
            // 这样"阴影把地板压暗"在截图上才看得出来（直接把 oreCopper 当地板铺是深色的，看不出差别）
            for (int y = oy - 2; y < oy + 10; y++)
                for (int x = ox - 6; x < ox + 14; x++) {
                    Tile t = Vars.world.tile(x, y);
                    if (t != null) {
                        t.setFloor((mindustry.world.blocks.environment.Floor) Blocks.darksand);
                        t.setOverlay(Blocks.oreCopper);
                    }
                }
            // 右下一小片换成**铅**：区域主导矿是铜，但第 3 台钻头坐在铅上 —— 用它核对
            // "中心那个矿物色块画的是自己脚下的矿，而不是区域主导矿"（用户 2026-10-09 报的）。
            for (int y = oy + 6; y < oy + 10; y++)
                for (int x = ox + 8; x < ox + 14; x++) {
                    Tile t = Vars.world.tile(x, y);
                    if (t != null)
                        t.setOverlay(Blocks.oreLead);
                }
            shOx = ox;
            shOy = oy;
            Core.camera.position.set((ox + 7) * 8f, (oy + 5) * 8f);
            Vars.control.input.block = null;
            Log.info("[drv] shadow 场景: 铜矿 @x@ 铺在 @,@", 14, 10, ox, oy);
        } catch (Throwable t) {
            Log.err("[drv] setupShadowScene failed", t);
        }
    }

    /** 进入框选并按住第一个框（**不松手**，好让"拖框中"那一帧能被截到）。 */
    static void shadowFrameStart() {
        try {
            // 【必须】框选要求 player.isBuilder()：驱动原来造的核心单位 alpha 不能建造（canBuild=false），
            // 于是 OreShadowPlacer.update 每帧都把框选取消掉（selecting 立刻变 false，框根本画不出来）。
            // 这里换成真能建造的单位（mono）。
            Unit u = Vars.player.unit();
            if (u == null || !u.canBuild()) {
                u = mindustry.content.UnitTypes.mono.spawn(Vars.player.team(), (shOx + 6) * 8f, (shOy + 4) * 8f);
                Vars.player.unit(u);
                Log.info("[drv] 换成能建造的单位 @", u.type.name);
            }
            try {
                u.updateBuilding(true);
            } catch (Throwable ignored) {
            }
            Class<?> p = Class.forName("combine.production.OreShadowPlacer", true, ml);
            invokeStatic(p, "start", null);
            setStatic(p, "dragging", true);
            setStatic(p, "sx", shOx + 2);
            setStatic(p, "sy", shOy + 2);
            setStatic(p, "ex", shOx + 11);
            setStatic(p, "ey", shOy + 6);
            Log.info("[drv] 框选中（第一笔 62,72 → 71,76），当前 selecting=@",
                    getStatic(p, "selecting"));
        } catch (Throwable t) {
            Log.err("[drv] shadowFrameStart failed", t);
        }
    }

    /** 松手落地：第一笔 + 第二笔拼成一个 L 形（用户要求：阴影可以是不规则的）。 */
    static void shadowFrameFinish() {
        try {
            Class<?> p = Class.forName("combine.production.OreShadowPlacer", true, ml);
            invokeStatic(p, "finish", null);
            frameOnce(p, shOx + 8, shOy + 7, shOx + 11, shOy + 9);
            // 【别依赖 placer 的 finish】OreShadowPlacer.update 每帧会按触摸状态复位 sx/sy/ex/ey，
            // 驱动没有真触摸 → 第一笔经常被复位掉（实测只记下第二笔的 12 格）。这里直接调
            // OreShadow.toggle 把"L 形"两笔都标上，保证"阴影上一定盖住钻头的地基"。
            Class<?> sh = Class.forName("combine.production.OreShadow", true, ml);
            invokeStatic(sh, "toggle", new Class<?>[]{int.class, int.class, int.class, int.class},
                    shOx + 2, shOy + 2, shOx + 11, shOy + 6);
            invokeStatic(sh, "toggle", new Class<?>[]{int.class, int.class, int.class, int.class},
                    shOx + 8, shOy + 7, shOx + 11, shOy + 9);
            Log.info("[drv] 矿物阴影（L 形）: 记下 @ 格", invokeStatic(sh, "size", null));
        } catch (Throwable t) {
            Log.err("[drv] shadowFrameFinish failed", t);
        }
    }

    /** 框一笔（模拟拖一个框松手）。 */
    static void frameOnce(Class<?> p, int x1, int y1, int x2, int y2) {
        setStatic(p, "dragging", true);
        setStatic(p, "sx", x1);
        setStatic(p, "sy", y1);
        setStatic(p, "ex", x2);
        setStatic(p, "ey", y2);
        invokeStatic(p, "finish", null);
    }

    /** 阴影上摆钻头（走原版落地流程 → 自动压成 1x1，物理占地也只有 1 格），阴影外再放一台当对照。 */
    static void shadowDropDrill() {
        try {
            Block drill = Vars.content.block("mechanical-drill");
            if (drill == null) {
                Log.err("[drv] shadowDropDrill: 找不到机械钻头");
                return;
            }
            ensurePlayerUnit((shOx + 6) * 8f, (shOy + 5) * 8f);
            // 走真路径：原版 flushPlans 里会调 plan.block.onNewPlan(copy)，阴影上的钻头在那里被换成 1x1 版本
            Class<?> cd = Class.forName("combine.production.CombinedDrill", true, ml);
            var m = cd.getMethod("onNewPlan", mindustry.entities.units.BuildPlan.class);
            m.setAccessible(true);
            Building first = null;
            int[][] spots = { { shOx + 3, shOy + 3 }, { shOx + 5, shOy + 4 }, { shOx + 9, shOy + 8 } };
            for (int i = 0; i < spots.length; i++) {
                int[] sp = spots[i];
                mindustry.entities.units.BuildPlan plan = new mindustry.entities.units.BuildPlan(sp[0], sp[1], 0, drill);
                m.invoke(drill, plan);
                if (i == 0) {
                    // 第一台交给游戏自己建（真·点建造）
                    shPlanX = plan.x;
                    shPlanY = plan.y;
                    shPlanBlock = plan.block;
                    Vars.player.unit().addBuild(plan);
                    // 【用户报的"预设直接画 1x1"】把队列里那颗 plan（= 预设）到底长什么样打出来：
                    // 方块名/size、以及绘制"预设"用的 fullIcon/uiIcon/region 有没有加载、多大。
                    Log.info("[drv] 阴影上的第 1 台钻头排队给游戏自己建：plan=@ size=@ @,@（validPlace=@）"
                            + " | 预设贴图 fullIcon=@ @x@ uiIcon=@ region=@ @x@",
                            plan.block.name, plan.block.size, plan.x, plan.y,
                            mindustry.world.Build.validPlace(plan.block, Team.sharded, plan.x, plan.y,
                                    plan.rotation, true, false),
                            plan.block.fullIcon != null && plan.block.fullIcon.found(),
                            plan.block.fullIcon == null ? 0 : plan.block.fullIcon.width,
                            plan.block.fullIcon == null ? 0 : plan.block.fullIcon.height,
                            plan.block.uiIcon != null && plan.block.uiIcon.found(),
                            plan.block.region != null && plan.block.region.found(),
                            plan.block.region == null ? 0 : plan.block.region.width,
                            plan.block.region == null ? 0 : plan.block.region.height);
                } else {
                    Building b = place(plan.block, plan.x, plan.y);
                    if (first == null)
                        first = b;
                }
            }
            Building off = place(drill, shOx - 4, shOy + 1);
            Log.info("[drv] 阴影上摆了 @ 台钻头（@，size=@），阴影外对照 1 台（@，size=@）",
                    spots.length, first == null ? "?" : first.block.name, first == null ? -1 : first.block.size,
                    off == null ? "?" : off.block.name, off == null ? -1 : off.block.size);
        } catch (Throwable t) {
            Log.err("[drv] shadowDropDrill failed", t);
        }
    }

    /** 把"正在框选"钉住一帧（驱动专用，真人玩家的建造单位不会有这个问题）。 */
    /**
     * 在阴影上排一颗钻头的 plan，然后立刻把建造单位挪到远处 —— 让这颗 plan **停在队列里不施工**，
     * 这样才能截到纯"预设"（虚影）那一帧。plan 走的是和真人一样的路（onNewPlan 压格）。
     */
    static void shadowQueueGhost() {
        try {
            Block drill = Vars.content.block("mechanical-drill");
            if (drill == null || Vars.player == null || Vars.player.unit() == null)
                return;
            Class<?> cd = Class.forName("combine.production.CombinedDrill", true, ml);
            var m = cd.getMethod("onNewPlan", mindustry.entities.units.BuildPlan.class);
            m.setAccessible(true);
            mindustry.entities.units.BuildPlan plan =
                    new mindustry.entities.units.BuildPlan(shOx + 6, shOy + 3, 0, drill);
            m.invoke(drill, plan);
            Vars.player.unit().addBuild(plan);
            Log.info("[drv] 预设虚影: 排队 plan=@ size=@ @,@", plan.block.name, plan.block.size, plan.x, plan.y);
            // 挪远：施工要求单位在 buildRange 内，挪远后这颗 plan 就一直是"预设"状态
            Vars.player.unit().set(shOx * 8f + 600f, shOy * 8f + 600f);
        } catch (Throwable t) {
            Log.err("[drv] shadowQueueGhost failed", t);
        }
    }

    static void shadowPinFrame() {
        try {
            Class<?> p2 = Class.forName("combine.production.OreShadowPlacer", true, ml);
            setStatic(p2, "selecting", true);
            setStatic(p2, "dragging", true);
            setStatic(p2, "sx", shOx + 2);
            setStatic(p2, "sy", shOy + 2);
            setStatic(p2, "ex", shOx + 11);
            setStatic(p2, "ey", shOy + 6);
        } catch (Throwable t) {
            Log.err("[drv] shadowPinFrame failed", t);
        }
    }

    /**
     * 【用户 2026-10-10 报"合体钻头放置时预设变成 1x1 了，但 canplace 判断还是原来的尺寸"】电脑那条路：
     * 手里拿的仍然是原钻头（选不到隐藏的压格版本），原版 {@code updateLine} 造出来的一串计划也存原钻头 ——
     * 于是原版画出来的**边框**（{@code Drawf.selected(x,y,plan.block,...)} 按 block.size）和松手时的
     * {@code validPlace} 都还是 size×size。这里走真路径造线（updateLine → block.handlePlacementLine），
     * 把 linePlans 里每份计划的方块 / size / 判据打出来核对。
     */
    static void shadowLinePresetStart() {
        try {
            Block drill = Vars.content.block("mechanical-drill");
            if (drill == null) {
                Log.err("[drv] shadowLinePresetStart: 找不到机械钻头");
                return;
            }
            ensurePlayerUnit((shOx + 3) * 8f, (shOy + 5) * 8f);
            // 【必须换回能建造的单位】拖拽预览只对 player.isBuilder() 的单位画：驱动前面几步把单位换成了
            // 不能建造的 alpha，那样原版根本不画这条拖拽虚影（截图上自然"什么都没有"，验不了看得见看不见）。
            Unit bu = Vars.player == null ? null : Vars.player.unit();
            if (bu == null || !bu.canBuild()) {
                bu = mindustry.content.UnitTypes.mono.spawn(Vars.player.team(), (shOx + 3) * 8f, (shOy + 5) * 8f);
                Vars.player.unit(bu);
                Log.info("[drv] 拖拽预览：把单位换成能建造的 mono");
            }
            try {
                bu.updateBuilding(true);
            } catch (Throwable ignored) {
            }
            // 拖线那一行的地基里先摆一台传送带（钻头本来就要贴着它出货）：原尺寸 2x2 的判据会因为这一格失败
            Building belt = place(Vars.content.block("conveyor"), shOx + 4, shOy + 4);
            var in = Vars.control.input;
            in.block = drill;
            in.rotation = 0;
            if (in instanceof mindustry.input.DesktopInput di)
                di.mode = mindustry.input.PlaceMode.placing;
            // 镜头钉在阴影上（不然截图可能跟着玩家单位跑）
            Core.settings.put("detach-camera", true);
            Core.camera.position.set((shOx + 3) * 8f, (shOy + 4) * 8f);
            try {
                Vars.renderer.setScale(3.5f); // 近景：要能看清虚影和它脚下的那个框是几格
            } catch (Throwable ignored) {
            }
            Class<?>[] sig = { int.class, int.class, int.class, int.class };
            var m = mindustry.input.InputHandler.class.getDeclaredMethod("updateLine", sig);
            m.setAccessible(true);
            m.invoke(in, shOx + 3, shOy + 4, shOx + 6, shOy + 4);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < in.linePlans.size; i++) {
                var p = in.linePlans.get(i);
                sb.append(p.block == null ? "null" : p.block.name)
                        .append("(size=").append(p.block == null ? -1 : p.block.size)
                        .append(",animScale=").append(p.animScale)
                        .append(")@").append(p.x).append(",").append(p.y).append("  ");
            }
            Log.info("[drv] 电脑拖线 linePlans（共 @ 份，传送带在 @,@）: " + sb.toString().trim(),
                    in.linePlans.size, belt == null ? -1 : belt.tileX(), belt == null ? -1 : belt.tileY());
            if (in.linePlans.size > 0) {
                var p = in.linePlans.first();
                Log.info("[drv] 同一格 @,@：原尺寸判据=@ / 计划里那份的判据=@（该 true）",
                        p.x, p.y,
                        mindustry.world.Build.validPlace(drill, Team.sharded, p.x, p.y, p.rotation, true, false),
                        mindustry.world.Build.validPlace(p.block, Team.sharded, p.x, p.y, p.rotation, true, false));
            }
        } catch (Throwable t) {
            Log.err("[drv] shadowLinePresetStart failed", t);
        }
    }

    /**
     * 手机那条路：真人 {@code MobileInput.touchUp} 走的就是 {@code flushSelectPlans(linePlans)} ——
     * 预设存进 {@code selectPlans}，按建造（✓）时原版再对 {@code plan.block} 做一次 {@code validPlace}。
     * 所以 selectPlans 里存的必须已经是压格版本（1x1），否则边框和 ✓ 那一关还是原尺寸。
     */
    static void shadowMobilePreset() {
        try {
            var in = Vars.control.input;
            ensurePlayerUnit((shOx + 20) * 8f, (shOy + 20) * 8f); // 挪远：别让建造单位挡着判据
            Core.settings.put("detach-camera", true);
            Core.camera.position.set((shOx + 3) * 8f, (shOy + 4) * 8f);
            try {
                Vars.renderer.setScale(3.5f); // 近景：预设的框应该只有 1 格
            } catch (Throwable ignored) {
            }
            Class<?>[] sig = { arc.struct.Seq.class };
            var m = mindustry.input.InputHandler.class.getDeclaredMethod("flushSelectPlans", sig);
            m.setAccessible(true);
            m.invoke(in, in.linePlans);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < in.selectPlans.size; i++) {
                var p = in.selectPlans.get(i);
                sb.append(p.block == null ? "null" : p.block.name)
                        .append("(size=").append(p.block == null ? -1 : p.block.size)
                        .append(")@").append(p.x).append(",").append(p.y).append("  ");
            }
            Log.info("[drv] 手机预设 selectPlans（共 @ 份）: " + sb.toString().trim(), in.selectPlans.size);
            if (in.selectPlans.size > 0) {
                var p = in.selectPlans.first();
                Log.info("[drv] 预设那一格的 ✓ 判据（对 plan.block）=@（该 true）",
                        mindustry.world.Build.validPlace(p.block, Team.sharded, p.x, p.y, p.rotation, true, false));
            }
        } catch (Throwable t) {
            Log.err("[drv] shadowMobilePreset failed", t);
        }
    }

    static int shPlanX = -1, shPlanY = -1;
    static Block shPlanBlock;

    /**
     * 【拖拽虚影可见性】在一片**干净**（没有已建钻头、没有采矿烟）的阴影行上再拖一段，专门给截图用：
     * 用户报的"拖拽的时候不显示预设"要能在这张图上直接看出来（1x1 虚影应当一路可见）。
     */
    static void shadowCleanDragStart() {
        try {
            var in = Vars.control.input;
            // 拖拽预览只对 isBuilder()（canBuild）的单位画
            if (Vars.player != null && (Vars.player.unit() == null || !Vars.player.unit().canBuild())) {
                var u = mindustry.content.UnitTypes.mono.spawn(Vars.player.team(),
                        (shOx + 4) * 8f, (shOy + 8) * 8f);
                Vars.player.unit(u);
                Log.info("[drv] 干净行拖拽：把单位换成能建造的 mono");
            }
            in.block = Vars.content.block("mechanical-drill");
            in.rotation = 0;
            if (in instanceof mindustry.input.DesktopInput di)
                di.mode = mindustry.input.PlaceMode.placing;
            // 先把前面步骤留下的钻头/卡住的计划清掉：它们的采矿烟和"不能建造"警告图标会盖住虚影，
            // 这张截图要干净地看到"拖拽中的 1x1 虚影"（阴影标记是模组自己的状态，清建筑不影响它）
            try {
                if (Vars.player != null && Vars.player.unit() != null)
                    Vars.player.unit().plans.clear();
            } catch (Throwable ignored) {
            }
            for (int y = shOy + 2; y <= shOy + 9; y++)
                for (int x = shOx + 1; x <= shOx + 12; x++) {
                    Tile t = Vars.world.tile(x, y);
                    if (t != null && t.block() != Blocks.air)
                        t.setBlock(Blocks.air);
                }
            Core.settings.put("detach-camera", true);
            // 【干净场地】前面那片阴影被已建钻头的采矿烟/警告图标污染了，截图看不清虚影：
            // 在最下面另铺一条矿、框成阴影，用一条全新的空行来截"拖拽中的 1x1 虚影"。
            int cleanY = shOy + 12;
            for (int x = shOx; x < shOx + 8; x++) {
                Tile t = Vars.world.tile(x, cleanY);
                if (t != null)
                    t.setFloor((mindustry.world.blocks.environment.Floor) Blocks.oreCopper);
            }
            try {
                Class<?> sh = Class.forName("combine.production.OreShadow", true, ml);
                invokeStatic(sh, "toggle", new Class<?>[] { int.class, int.class, int.class, int.class },
                        shOx, cleanY, shOx + 7, cleanY);
            } catch (Throwable t) {
                Log.err("[drv] 干净行：框阴影失败", t);
            }
            Core.camera.position.set((shOx + 3) * 8f, cleanY * 8f);
            Class<?>[] sig = { int.class, int.class, int.class, int.class };
            var m = mindustry.input.InputHandler.class.getDeclaredMethod("updateLine", sig);
            m.setAccessible(true);
            m.invoke(in, shOx + 2, cleanY, shOx + 5, cleanY);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < in.linePlans.size; i++) {
                var p = in.linePlans.get(i);
                sb.append(p.block == null ? "null" : p.block.name)
                        .append("(size=").append(p.block == null ? -1 : p.block.size)
                        .append(",animScale=").append(p.animScale)
                        .append(")@").append(p.x).append(",").append(p.y).append("  ");
            }
            Log.info("[drv] 干净行拖拽 linePlans（共 @ 份）: " + sb.toString().trim(), in.linePlans.size);
        } catch (Throwable t) {
            Log.err("[drv] shadowCleanDragStart failed", t);
        }
    }

    /** 真·落地检查：游戏自己建的那台钻头到底出现没有（用户报的"点建造后钻头直接消失"）。 */
    static void shadowVerifyPlaced(String when) {
        try {
            // 驱动造的单位不一定会执行建造队列（见 ensurePlayerUnit 的注释，veil 模式同款问题）：
            // 队列那步已经验过 validPlace=true，这里补一次游戏自己的"施工完成"调用，
            // 好让截图里真有这台钻头（走的是 Call.constructFinish，和真人建造完成是同一条路）。
            Tile tt = shPlanX < 0 ? null : Vars.world.tile(shPlanX, shPlanY);
            if (tt != null && (tt.build == null || tt.build.dead()) && shPlanBlock != null)
                mindustry.gen.Call.constructFinish(tt, shPlanBlock, null, (byte) 0, Team.sharded, null);
            Building b = shPlanX < 0 ? null : Vars.world.build(shPlanX, shPlanY);
            Tile t = shPlanX < 0 ? null : Vars.world.tile(shPlanX, shPlanY);
            Log.info("[drv] 真·落地检查(@): @,@ → @（tile.block=@ build=@ 方块=@ size=@ 建造队列=@）", when,
                    shPlanX, shPlanY, b == null ? "空（没建出来！）" : "已建成",
                    t == null ? "-" : t.block().name,
                    t == null || t.build == null ? "-" : t.build.getClass().getSimpleName(),
                    b == null ? "-" : b.block.name, b == null ? -1 : b.block.size,
                    Vars.player == null || Vars.player.unit() == null ? -1
                            : Vars.player.unit().plans.size);
        } catch (Throwable t) {
            Log.err("[drv] shadowVerifyPlaced failed", t);
        }
    }

    /** 摆三台工厂（硅冶炼厂/石墨压机/窑炉）当合体原料，相机对准它们。 */
    /**
     * 走**真流程**：进框选 → 拖一个框 → 松手（finish()）→ 生成合体虚影（跟着鼠标）。
     * 虚影就压在原料工厂上（原地合体），下一帧截图看"每格画的是不是对应工厂的贴图"。
     */
    /** 左键确定位置（原版蓝图那套：flushPlans），落地后贴电源 + 喂料，让格子转起来。 */
    /** 把虚影钉在原料工厂的原位置：蓝图虚影的位置由 DesktopInput.schematicX/Y 决定，
     *  那对字段只在鼠标事件里更新，所以每个想截虚影的帧都要按住它。 */
    /** 直接落地（等价于"虚影已经生成、玩家点了左键"）：驱动的世界里单位建造有随机性，
     *  这里用原版同一条 ConstructFinish 通道把虚影的 config 落到虚影位置上。 */
    /** 截图前的诊断：虚影到底在不在、在哪个格子。 */
    /**
     * 真·左键落地：调原版 {@code flushPlans}（会走 SuperCombineFactory.onNewPlan），
     * **不**手动清 selectPlans —— 复现"放下后还残留虚影"的场景。之后隔 ~1s / ~3s 各拍一张，
     * 比较新工厂有没有被暗底压住；同时打印 selectPlans 有没有被模组自己收掉。
     */
    /**
     * 落地 ~1.2s 后查：跟手的虚影（selectPlans）必须已经被模组收掉，而且**排队中的建造计划**
     * 不能出现在 selectPlans 里 —— drawGhost 就是靠这个判据决定要不要画黑板（暗底）。
     */
    static void veilDiag() {
        try {
            var in = Vars.control.input;
            var u = Vars.player == null ? null : Vars.player.unit();
            Object queued = u == null || u.plans().isEmpty() ? null : u.plans().first();
            boolean queuedHeld = false;
            for (var p : in.selectPlans)
                if (p == queued)
                    queuedHeld = true;
            Log.info("[drv] veil 诊断: selectPlans=@ 排队计划仍在 selectPlans=@ 单位计划=@ 合体方块=@",
                    in.selectPlans.size, queuedHeld, u == null ? -1 : u.plans().size, countMerged());
        } catch (Throwable t) {
            Log.err("[drv] veilDiag failed", t);
        }
    }

    static void factoryPlace() {
        try {
            // 放置要玩家有建造单位（和超级炮台那一套一样）
            ensurePlayerUnit(fcGx * 8f, fcGy * 8f);
            for (var p : Vars.control.input.selectPlans)
                Log.info("[drv] 计划 @,@ block=@ 可放=@ 那格现有=@ 队伍=@", p.x, p.y, p.block.name,
                        mindustry.world.Build.validPlace(p.block, Team.sharded, p.x, p.y, p.rotation),
                        Vars.world.build(p.x, p.y) == null ? "空" : Vars.world.build(p.x, p.y).block.name,
                        Vars.player == null ? "-" : Vars.player.team().name);
            if (Vars.player != null && Vars.player.unit() != null
                    && !Vars.control.input.selectPlans.isEmpty()) {
                var m = mindustry.input.InputHandler.class.getDeclaredMethod("flushPlans", Seq.class);
                m.setAccessible(true);
                m.invoke(Vars.control.input, Vars.control.input.selectPlans);
                Log.info("[drv] flushPlans 后: 单位计划=@ 世界里的合体方块=@",
                        Vars.player.unit().plans().size, countMerged());
            }
            Vars.control.input.selectPlans.clear();
            Vars.control.input.block = null;
        } catch (Throwable t) {
            Log.err("[drv] factoryPlace failed", t);
        }
    }

    static void factoryFeed() {
        try {
            // 落地位置以世界里的实际建筑为准（原版 flushPlans 可能把它放在锚点附近）
            fcMerged = null;
            for (Building b : mindustry.gen.Groups.build)
                if (b.block != null && b.block.name != null
                        && b.block.name.startsWith("super-combine-factory-"))
                    fcMerged = b;
            try {
                var unit = Vars.player == null ? null : Vars.player.unit();
                var plans = unit == null ? null : unit.plans();
                Object first = plans == null || plans.isEmpty() ? null : plans.first();
                Log.info("[drv] 等建造: 单位=@ 位置=@,@ 计划数=@ 计划进度=@ 可放=@ 全球方块=@",
                        unit == null ? "null" : unit.type.name,
                        unit == null ? -1 : (int) (unit.x / 8f), unit == null ? -1 : (int) (unit.y / 8f),
                        plans == null ? -1 : plans.size,
                        first instanceof mindustry.entities.units.BuildPlan bp ? bp.progress : -1f,
                        first instanceof mindustry.entities.units.BuildPlan bp2
                                ? mindustry.world.Build.validPlace(bp2.block, Team.sharded, bp2.x, bp2.y, bp2.rotation)
                                : "-",
                        countMerged());
            } catch (Throwable t) {
                Log.err("[drv] 等建造诊断失败", t);
            }
            if (fcMerged == null) {
                // 软渲染下 "直接落地" 那一帧可能比这里晚，重试几次
                fcFeedTries++;
                if (fcFeedTries <= 8) {
                    Log.info("[drv] 合体工厂还没出现，1.5s 后重试（第 @ 次）", fcFeedTries);
                    Timer.schedule(Driver::factoryFeed, 1.5f);
                } else {
                    Log.err("[drv] 合体工厂没落地（框选位置 @,@）", fcGx, fcGy);
                    // 还是要出图（至少能看"没落地"的样子）
                    Timer.schedule(() -> shot("factory_after"), 2f);
                    Timer.schedule(Driver::factoryOpenPanel, 4f);
                }
                return;
            }
            Block ps = Vars.content.block("power-source");
            if (ps != null)
                // 【别放进步伐里】合体块是 side×side：电源要摆在它左边一整块之外的格子，
                // 摆进去会把合体方块自己顶掉（drive 的 place 会强制 setBlock）。
                place(ps, fcMerged.tileX() - Math.max(fcMerged.block.size, 1), fcMerged.tileY());
            // 再贴一个原版 heat-source（不吃料、立刻产热）：让 display 的"热量"条有真实数字
            try {
                Block hs = Vars.content.block("heat-source");
                if (hs != null) {
                    placeBL(hs, fcMerged.tileX() + 2, fcMerged.tileY());
                    runLogical(20);
                    Log.info("[drv] 贴了 heat-source(@)=@ 合体工厂热池=@",
                            hs.name, fcMerged.tileX() + 2 + "," + fcMerged.tileY(),
                            fcMerged.getClass().getField("comboTotalHeat").get(fcMerged));
                }
            } catch (Throwable t) {
                Log.err("[drv] 贴合体工厂 heat-source 失败", t);
            }
            // 按"整台池子的真实上限"给（别灌成 400/50 那种超容数字，截图里量出来的条才准）
            int icap = (Integer) combineCall("combine.production.SuperCombineFactory", "itemCapOf",
                    new Class<?>[] { Building.class }, fcMerged);
            icap = Math.max(icap, 1);
            fcMerged.items.add(mindustry.content.Items.sand, Math.max(icap * 3 / 5, 1));
            fcMerged.items.add(mindustry.content.Items.coal, Math.max(icap / 2, 1));
            fcMerged.items.add(mindustry.content.Items.lead, Math.max(icap / 4, 1));
            // 给共享池灌液体：格子里的 DrawLiquidTile / DrawLiquidRegion 只有在池子里有液体时
            // 才画得出来 —— 截图里"合体块每格有没有在画抽屉（而不是一张死图标）"就看这一步。
            try {
                fcMerged.liquids.add(mindustry.content.Liquids.water, 4000f); // 会被 tick 裁到池上限
                // 再灌一种**不同**的液体（渣）：验"每格画自己那种液体"（用户报的"全都画同一种"）
                fcMerged.liquids.add(mindustry.content.Liquids.slag, 1200f);
                Log.info("[drv] 合体工厂灌液体: water=@ slag=@ 池液体容量=@",
                        fcMerged.liquids.get(mindustry.content.Liquids.water),
                        fcMerged.liquids.get(mindustry.content.Liquids.slag),
                        combineCall("combine.production.SuperCombineFactory", "liquidCapOf",
                                new Class<?>[] { Building.class }, fcMerged));
            } catch (Throwable t) {
                Log.err("[drv] 合体工厂灌水失败", t);
            }
            // 相机放在"合体块 + 右边那台原版参照机"中间，一张图里直接对比
            Core.camera.position.set((fcMerged.x + (fcOx + 20) * 8f) / 2f, fcMerged.y + 12f);
            // 【逐格核对缩放】再挂一个镜头锁 + 放大：用户报的"某些工厂缩放不对"必须放大才看得清
            installFactoryCamLock();
            Log.info("[drv] 合体工厂落地: 格数=@ 位置=@,@", combineCall(
                    "combine.production.SuperCombineFactory", "loadedCells",
                    new Class<?>[] { Building.class }, fcMerged),
                    fcMerged.tileX(), fcMerged.tileY());
            // 诊断：config 到底应用上了没（layout/sources/filledCount）—— "格数=0 / 原料没吃完"
            // 这类问题一眼看出卡在"配置没到"还是"原料核不中"。
            try {
                Object lay = fcMerged.getClass().getField("layout").get(fcMerged);
                Object src = fcMerged.getClass().getField("sources").get(fcMerged);
                Object filled = fcMerged.getClass().getMethod("filledCount").invoke(fcMerged);
                Log.info("[drv] 合体工厂诊断: layout=[@] sources=[@] filled=@", lay, src, filled);
            } catch (Throwable t) {
                Log.err("[drv] 合体工厂诊断失败", t);
            }
            Object[] arr = (Object[]) fcMerged.getClass().getField("cells").get(fcMerged);
            Object[] bs = (Object[]) fcMerged.getClass().getField("cellBlocks").get(fcMerged);
            // 诊断：这一格方块的 drawer 部件都是什么（用户报的 drawer 相关）
            try {
                for (Object o : bs) {
                    if (!(o instanceof Block b))
                        continue;
                    Object dr = combineCall("combine.util.ComboReflect", "fieldValue",
                            new Class<?>[] { Object.class, String.class }, b, "drawer");
                    Object usable = combineCall("combine.production.SuperCombineFactory", "drawerUsable",
                            new Class<?>[] { Block.class }, b);
                    Log.info("[drv] drawer(@)=@ usable=@", b.name,
                            dr == null ? "null" : dr.getClass().getName(), usable);
                    Object subs = dr == null ? null : combineCall("combine.util.ComboReflect", "fieldValue",
                            new Class<?>[] { Object.class, String.class }, dr, "drawers");
                    if (subs instanceof Object[] psx)
                        for (Object p : psx)
                            Log.info("[drv]   部件 @ region=@ liquid=@ rotate=@ main=@",
                                    p.getClass().getSimpleName(),
                                    combineCall("combine.util.ComboReflect", "fieldValue",
                                            new Class<?>[] { Object.class, String.class }, p, "region"),
                                    combineCall("combine.util.ComboReflect", "fieldValue",
                                            new Class<?>[] { Object.class, String.class }, p, "liquid"),
                                    combineCall("combine.util.ComboReflect", "fieldValue",
                                            new Class<?>[] { Object.class, String.class }, p, "rotateSpeed"),
                                    b.region);
                    break;
                }
            } catch (Throwable t) {
                Log.err("[drv] drawer 诊断失败", t);
            }
            StringBuilder sb = new StringBuilder();
            for (Object o : bs) {
                if (!(o instanceof Block b)) {
                    sb.append("null,");
                    continue;
                }
                Object k = combineCall("combine.production.SuperCombineFactory", "cellScale",
                        new Class<?>[] { Block.class }, b);
                // 【用户报的"phase-weaver / phase-synthesizer / sporePress 缩放还是有问题"】：
                // cellScale 只看方块主贴图宽度；把这些抽屉真正会画出来的图（icons）宽度也打出来，
                // 就能看出"主贴图 64 但 weave/piston 是 96/128"这种"按主贴图缩、零件还是大"的情况。
                int maxIcon = -1;
                try {
                    Object dr = b.getClass().getField("drawer").get(b);
                    if (dr instanceof mindustry.world.draw.DrawBlock db) {
                        var icons = db.icons(b);
                        if (icons != null)
                            for (var r : icons)
                                if (r != null && r.found())
                                    maxIcon = Math.max(maxIcon, r.width);
                    }
                } catch (Throwable ignored) {
                }
                sb.append(b.name).append("(size=").append(b.size)
                    .append(",region=").append(b.region == null ? -1 : b.region.width)
                    .append(",maxIcon=").append(maxIcon)
                    .append(",full=").append(b.fullIcon == null ? -1 : b.fullIcon.width)
                    .append(",k=").append(k).append("),");
                // 抽屉里**每一张**贴图的宽度（weave / glow / piston / heat 这些不在 icons() 里的也要看）
                try {
                    Object dr = b.getClass().getField("drawer").get(b);
                    StringBuilder rb = new StringBuilder();
                    dumpRegionSizes(dr, rb);
                    Log.info("[drv] 贴图尺寸 @: @", b.name, rb.toString());
                } catch (Throwable ignored) {
                }
            }
            Log.info("[drv] 合体工厂 cells=@ blocks=[@]", arr.length, sb.toString());
            // 原料必须都没了
            int remain = 0;
            for (Building bb : mindustry.gen.Groups.build)
                if (bb.block != null && bb.block.name != null
                        && !bb.block.name.startsWith("super-combine-factory-")
                        && Boolean.TRUE.equals(combineCall("combine.production.SuperCombineFactory",
                                "isFactoryBlock", new Class<?>[] { Block.class }, bb.block)))
                    remain++;
            Log.info("[drv] 原地合体后剩下的原料工厂=@ 台（应为 0）", remain);
            // 多等几秒让里面的格子转起来（工作特效/产物动画），再截图 + 弹面板
            Timer.schedule(() -> shot("factory_after"), 4f);
            Timer.schedule(Driver::factoryOpenDisplay, 6f);
        } catch (Throwable t) {
            Log.err("[drv] factoryFeed failed", t);
        }
    }

    /** 把方块自己的 buildConfiguration 表（点方块弹出的那张）放进对话框截图。 */
    /** 递归打印一个抽屉（含 DrawMulti 子抽屉 / DrawBlockParts）里所有 TextureRegion 字段的尺寸。 */
    /** 把镜头钉在合体块上并放大（逐格核对缩放用）。 */
    static void installFactoryCamLock() {
        var t = new arc.scene.ui.layout.Table();
        t.touchable = arc.scene.event.Touchable.disabled;
        t.update(() -> {
            if (fcMerged != null && fcMerged.isValid()) {
                Core.camera.position.set(fcMerged.x, fcMerged.y);
                // arc 的 Camera 没有 zoom 字段：直接缩小"可见世界范围"就是放大
                Core.camera.width = 40f;
                Core.camera.height = 30f;
            }
        });
        Vars.ui.hudGroup.addChild(t);
    }

    static void dumpRegionSizes(Object o, StringBuilder sb) {
        if (o == null)
            return;
        sb.append(o.getClass().getSimpleName()).append('{');
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!arc.graphics.g2d.TextureRegion.class.isAssignableFrom(f.getType()))
                    continue;
                try {
                    f.setAccessible(true);
                    Object r = f.get(o);
                    if (r instanceof arc.graphics.g2d.TextureRegion tr)
                        sb.append(f.getName()).append('=').append(tr.found() ? tr.width : -1).append(' ');
                } catch (Throwable ignored) {
                }
            }
        sb.append("} ");
        try {
            Object arr = o.getClass().getField("drawers").get(o);
            if (arr instanceof Object[] a)
                for (Object c : a)
                    dumpRegionSizes(c, sb);
        } catch (Throwable ignored) {
        }
        try {
            Object arr = o.getClass().getField("parts").get(o);
            if (arr instanceof Iterable<?> it)
                for (Object c : it)
                    dumpRegionSizes(c, sb);
        } catch (Throwable ignored) {
        }
    }

    /** 世界里现在有几台组合工厂（驱动检查用）。 */
    static int countMerged() {
        int n = 0;
        for (Building b : mindustry.gen.Groups.build)
            if (b.block != null && b.block.name != null
                    && b.block.name.startsWith("super-combine-factory-"))
                n++;
        return n;
    }

    /** 把方块自己的 display()（信息面板：物品/液体/电力/热量的条）放进对话框截图。 */
    static void factoryOpenDisplay() {
        try {
            if (fcMerged == null || !fcMerged.isValid()) {
                for (Building b : mindustry.gen.Groups.build)
                    if (b.block != null && b.block.name != null
                            && b.block.name.startsWith("super-combine-factory-"))
                        fcMerged = b;
            }
            if (fcMerged == null || !fcMerged.isValid()) {
                Log.err("[drv] 没有合体工厂，跳过 display 截图");
                Timer.schedule(Driver::factoryOpenPanel, 1f);
                return;
            }
            Core.camera.position.set(fcMerged.x, fcMerged.y);
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            fcMerged.getClass().getMethod("display", arc.scene.ui.layout.Table.class)
                    .invoke(fcMerged, panel);
            var d = new mindustry.ui.dialogs.BaseDialog("组合工厂 display（信息面板的条）");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 合体工厂 display 已弹出（顶层元素 @ 个）", panel.getChildren().size);
            Timer.schedule(() -> shot("factory_display"), 3f);
            Timer.schedule(() -> {
                try {
                    d.hide();
                } catch (Throwable ignored) {
                }
                factoryOpenPanel();
            }, 4f);
        } catch (Throwable t) {
            Log.err("[drv] factoryOpenDisplay failed", t);
            Timer.schedule(Driver::factoryOpenPanel, 1f);
        }
    }

    static void factoryOpenPanel() {
        try {
            // 兜底：定时器乱序时 fcMerged 可能还没记上，这里直接去世界里找
            if (fcMerged == null || !fcMerged.isValid()) {
                for (Building b : mindustry.gen.Groups.build)
                    if (b.block != null && b.block.name != null
                            && b.block.name.startsWith("super-combine-factory-"))
                        fcMerged = b;
            }
            if (fcMerged == null || !fcMerged.isValid()) {
                Log.err("[drv] 没有合体工厂，跳过面板截图");
                Timer.schedule(() -> shot("factory_panel"), 1f);
                Timer.schedule(() -> { Log.info("[drv] factory 模式结束"); Core.app.exit(); }, 3f);
                return;
            }
            Core.camera.position.set(fcMerged.x, fcMerged.y);
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            fcMerged.getClass().getMethod("buildConfiguration", arc.scene.ui.layout.Table.class)
                    .invoke(fcMerged, panel);
            var d = new mindustry.ui.dialogs.BaseDialog("组合工厂面板（点方块的界面）");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 合体工厂面板已弹出（构成 @ 台）", panel.getChildren().size);
            // 等面板排版/数据刷新一帧再截，然后收工
            Timer.schedule(() -> shot("factory_panel"), 3f);
            Timer.schedule(() -> { Log.info("[drv] factory 模式结束"); Core.app.exit(); }, 4f);
        } catch (Throwable t) {
            Log.err("[drv] factoryOpenPanel failed", t);
            Timer.schedule(() -> { Log.info("[drv] factory 模式结束"); Core.app.exit(); }, 2f);
        }
    }

    /**
     * 回归：框选模式下，用"真实事件链"点一下建造菜单里的方块 —— 必须点得动
     * （用户报的"现在建筑列表一个建筑都无法点击"）。
     *
     * <p>事件按 arc 的分发顺序从**最后一个**处理器往前送，和我们平时点鼠标一模一样：
     * 如果被谁吃掉了，`control.input.block` 就不会变。
     */
    /** ① 进入框选模式并拖出一个框（= 按 G / 点建造菜单里的按钮 + 拖动左键）。 */
    /**
     * 框选期间"玩家被锁住"的检查：用户要求点击合体按钮后玩家不能动（和原版重建/复制框选一样，
     * 框完才恢复）。做法是世界里的触摸必须被模组的输入处理器吃掉（手机摇杆也就起不来）。
     */
    /** ① 进入框选模式并拖出一个框（= 按 G / 点建造菜单里的按钮 + 拖动左键）。 */
    /** ② 框选完成 → 生成虚影，并把虚影挪到放置位（虚影里每格应该画出炮台图标）。 */
    /** ②b 相机已就位，把"鼠标指着的世界点"对准放置位 = 虚影跟着落到放置位。 */
    /** ③ 左键确定位置：把虚影变成建造计划。 */
    /** HUD 上的"超级组合炮台"按钮：存在 + 点一下能进框选模式（用户要的 UI 入口）。 */
    static void collectNamed(arc.scene.Element e, String name, arc.struct.Seq<arc.scene.Element> out){
        if(e == null) return;
        if(name.equals(e.name)) out.add(e);
        if(e instanceof arc.scene.Group g)
            for(arc.scene.Element c : g.getChildren()) collectNamed(c, name, out);
    }

    /** 给一个监听器直接喂一个 UI 输入事件（离屏渲染下 scene 自己的输入链路不可靠，只信监听器逻辑）。 */
    static void fireInput(java.lang.reflect.Method handle, arc.scene.event.EventListener l, arc.scene.Element actor,
            java.lang.reflect.Field fType, java.lang.reflect.Field fSX, java.lang.reflect.Field fSY,
            String type, float stageX, float stageY) throws Exception {
        var e = new arc.scene.event.InputEvent();
        e.listenerActor = actor;
        e.pointer = 0;
        e.keyCode = arc.input.KeyCode.mouseLeft;
        fType.set(e, arc.scene.event.InputEvent.InputEventType.valueOf(type));
        fSX.setFloat(e, stageX);
        fSY.setFloat(e, stageY);
        handle.invoke(l, e);
    }

    // ---------------- 炮台绘制对照（原版 duo / 内容表 duo / 格子里的 duo） ----------------

    static Building realDuo, comboDuo;

    /** 世界里放两台对照炮台：内容表里注册的 duo（组合版）+ 格子用的原版 duo。 */
    static String tr(arc.graphics.g2d.TextureRegion r) {
        return r == null ? "null" : (r.found() ? "有(" + (int) r.width + "x" + (int) r.height + ")" : "无");
    }

    /** 把炮台的绘制素材念一遍：虚影/格子画不出东西时，一眼看出缺哪张图。 */
    /**
     * 兜底直摆一台超级炮台（不走输入流程），配置串就是框选出来的布局。
     *
     * <p>只在"驱动环境里玩家单位的建造计划没被执行"时用；逻辑（框选/布局/并池/开火）由
     * headless 的 {@code combine.dbg.SuperTurretTest} 覆盖，这里只保证还能出截图。
     */
    // ---------------- 超级炮台：悬浮面板 / 手动操控 ----------------

    /** 悬浮面板：用户要求"给合体炮台加上悬浮面板看物品和液体数量"（走 CoopPanel 那套）。 */
    static void superTurretFloatPanel(Building b) {
        try {
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            cp.getMethod("init").invoke(null);
            // 只灌少量代表物品/液体：以前灌"每种各 40"，22 种物品 = 8 行，面板高过屏幕、
            // 被裁到只剩标题（截图里看不到热量行）。留一两行足够看"实时刷新"，也放得下热量。
            if (b.items != null) {
                b.items.add(Items.copper, 80);
                b.items.add(Items.graphite, 80);
                b.items.add(Items.silicon, 80);
            }
            if (b.liquids != null) {
                b.liquids.add(Liquids.water, 80f);
                b.liquids.add(Liquids.slag, 80f);
            }
            // 在炮台**正北边**贴一台活的矿渣制热机：让"热量"行有真实分子（headless 里量的是
            // 逻辑数值，这里看图 —— 分母应是里面每台需热炮台之和，分子是采到的整池热量）。
            // placeBL 的 x,y 是"左上角"，3x3 的中心 = (x+1,y+1)：要中心落在 (tileX, tileY+3)。
            try {
                Block heater = null;
                for (Block c : Vars.content.blocks())
                    if (heater == null && c.name.equals("slag-heater")) { heater = c; break; }
                Building hb = heater == null ? null
                        : placeBL(heater, b.tileX() - 1, b.tileY() + 2);
                if (hb != null) {
                    // HeatProducer.rotate=true：必须"对着"耗热方，原版 calculateHeat 才认。
                    // 朝向 = 从制热机指向炮台的方向（rotation 0=东 1=北 2=西 3=南）。
                    try {
                        int dirToTurret = hb.tileX() > b.tileX() ? 2
                                : hb.tileX() < b.tileX() ? 0
                                : (hb.tileY() > b.tileY() ? 3 : 1);
                        var f = Building.class.getField("rotation");
                        f.setAccessible(true);
                        f.setInt(hb, dirToTurret);
                    } catch (Throwable ignored) {
                    }
                    if (hb.liquids != null)
                        hb.liquids.add(Liquids.slag, 50000f);
                    b.updateProximity();
                    hb.updateProximity();
                    Log.info("[drv] 悬浮面板: 旁边放了一台 @ @,@ 朝向=@（给热量行做分子）",
                            hb.block.name, hb.tileX(), hb.tileY(), hb.rotation);
                }
            } catch (Throwable t) {
                Log.err("[drv] 悬浮面板: 放制热机失败（热量行分母仍可看）", t);
            }
            Object showable = cp.getMethod("showable", Building.class).invoke(null, b);
            cp.getMethod("tapped", mindustry.world.Tile.class).invoke(null, Vars.world.tile(b.tileX(), b.tileY()));
            String desc = String.valueOf(cp.getMethod("describe", Building.class).invoke(null, b));
            Log.info("[drv] 悬浮面板: showable=@ 可见=@ 内容=@", showable,
                    fieldOf(staticField(cp, "table"), "visible"), desc);
            // 镜头对准这台方块：单机的相机跟着玩家单位走，把单位挪到方块中心，面板才会
            // 浮在画面中间（以前只 set 一次 camera，下一帧就被"跟随玩家"覆盖，面板被挤到
            // 屏幕角落、截图里被裁掉）。
            ensurePlayerUnit(b.x, b.y);
            Core.camera.position.set(b.x, b.y);
            installCameraLock();
            // 硬锁镜头到这台方块：软渲染一帧要 0.3~0.5 秒，相机"跟随玩家单位"的平滑在截图
            // 那一刻还没走完，CoopPanel 每帧照旧相机定位 → 面板一直被裁。这个 update 在 UI
            // 阶段跑（晚于游戏更新），每帧把相机钉回方块，面板才能稳定摆到不裁的位置。
            arc.scene.ui.layout.Table camLock = new arc.scene.ui.layout.Table();
            camLock.touchable = arc.scene.event.Touchable.disabled;
            camLock.update(() -> Core.camera.position.set(b.x, b.y));
            Vars.ui.hudGroup.addChild(camLock);
            // 定位体检：面板高/宽、建筑锚点屏幕坐标、侧别 —— 面板被裁时一眼看出是"塞不下"
            // 还是"侧别选错/夹持用错尺寸"。
            Timer.schedule(() -> setStatic(cp, "side", 0), 1.6f);
            Timer.schedule(() -> {
                try {
                    arc.scene.ui.layout.Table tt =
                            (arc.scene.ui.layout.Table) staticField(cp, "table");
                    float[] a = (float[]) cp.getMethod("anchor", Building.class).invoke(null, b);
                    arc.math.geom.Vec2 v = Core.input.mouseScreen(a[0], a[1]);
                    arc.math.geom.Vec2 v2 = Core.input.mouseScreen(b.x, b.y - b.block.size * 4f);
                    Log.info("[drv] 面板定位: 建筑锚点屏Y=@ 下缘屏Y=@ 面板=@x@ pos=@,@ 屏=@x@ side=@",
                            v.y, v2.y, tt.getWidth(), tt.getHeight(), tt.x, tt.y,
                            Core.scene.getWidth(), Core.scene.getHeight(), getStatic(cp, "side"));
                } catch (Throwable t) {
                    Log.err("[drv] 面板定位体检失败", t);
                }
            }, 2.6f);
        } catch (Throwable t) {
            Log.err("[drv] 超级炮台悬浮面板打开失败", t);
        }
    }

    /** 点旁边一格：悬浮面板应该收起。 */
    static void superTurretHidePanel() {
        try {
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            cp.getMethod("tapped", mindustry.world.Tile.class)
                    .invoke(null, Vars.world.tile(stTx - 3, stTy - 3));
            Log.info("[drv] 点别处后悬浮面板可见=@", fieldOf(staticField(cp, "table"), "visible"));
        } catch (Throwable t) {
            Log.err("[drv] 收起悬浮面板失败", t);
        }
    }

    /** 手动操控：让玩家接管这台（= 接管第一格），照着准星开火。 */
    static Unit stProxyUnit;
    static Building stControlBuild;
    static boolean controlHookInstalled;

    /** 每帧把"玩家在瞄准 + 按住开火"写回被接管的单位（模拟真人操作）。 */
    /**
     * 直接摆一台 7x7 超级炮台（49 格）—— 验证"超过 6x6"也能用：底板改成程序化绘制。
     *
     * <p>不走框选流程（那需要 49 台真炮台），配置串直接给 49 个 duo。
     */
    /**
     * 用户要求："炮台缩放到 1*1 后相应的后坐力也要缩放"。
     * 摆一台带"大炮台"格子的超级炮台（大炮台每格缩放 < 1），把每格后坐拉满，
     * 报"缩放前/缩放后"的后坐位移，并留一张放大截图看炮管有没有飞出格子。
     */
    /** 后坐用例摆放出来的那台（每帧把它的后坐钉在最大值）。 */
    static Building recoilDemo;
    /** 每格"子弹从格心出来时的最近距离"（≈ 实际用到的 shootX/shootY 口径偏移）。 */
    static float[] recoilCellMinDist;
    static mindustry.gen.Unit recoilTarget;

    /** 后坐用例：把"缩放前/缩放后"的后坐位移抄到日志（格子 update 跑过几帧之后再调用）。 */
    /** 这台超级炮台里面所有格子一共打了几发。 */
    static int superTurretShots(Building b) {
        int n = 0;
        try {
            for (Object c : (Object[]) getField(b, "cells"))
                if (c instanceof mindustry.world.blocks.defense.turrets.Turret.TurretBuild t)
                    n += t.totalShots;
        } catch (Throwable t) {
            Log.err("[drv] 读总发射数失败", t);
        }
        return n;
    }

    /** ④ 等建好 → 截图 + 报告每格装了哪台炮台。 */
    // ---------------- 组合连接器 ----------------
    static Building connA, connB, connLink, connNode;
    /** "节点贴着 afflict + 制热机" 那一组（用户报的场景）。 */
    static Building connNodeHeat, connNodeHeatHeater, connNodeHeatAfflict;
    /** 第二种接法：节点离着放、靠点连线接起来的那一组。 */
    static Building connLinkedNode, connLinkedHeater, connLinkedAfflict;

    /** 走和玩家一样的入口给组合节点连线（onConfigureBuildTapped）。 */
    static void tapNodeLink(Building node, Building target){
        if(node == null || target == null) return;
        try{
            var m = node.getClass().getMethod("onConfigureBuildTapped", Building.class);
            m.setAccessible(true);
            m.invoke(node, target);
        }catch(Throwable t){ Log.err("[drv] 节点连线失败", t); }
    }

    /** 这台建筑算不算"组合方块"（CoopPanel/ComboNet 的判据）。 */
    static Object comboBuild(Building b){
        if(b == null) return null;
        try{
            Class<?> cr = Class.forName("combine.util.ComboReflect", true, ml);
            return cr.getMethod("isComboBuild", Building.class).invoke(null, b);
        }catch(Throwable t){ return "err"; }
    }

    /** CoopPanel.showable：点它到底弹不弹悬浮面板。 */
    static Object showable(Building b){
        if(b == null) return null;
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            return cp.getMethod("showable", Building.class).invoke(null, b);
        }catch(Throwable t){ return "err"; }
    }
    static int connPhase = 0, connFrames = -1;

    static void setupConnScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block press = null, connB_ = null, nodeB = null;
            for(Block b : Vars.content.blocks()){
                if(press == null && b.getClass().getName().equals("combine.production.CombinedCrafter") && b.size == 2) press = b;
                if(connB_ == null && b.getClass().getName().equals("combine.net.ComboConnector")) connB_ = b;
                if(nodeB == null && b.getClass().getName().equals("combine.net.ComboNode")) nodeB = b;
            }
            if(press == null || connB_ == null || nodeB == null){ Log.err("[drv] conn 场景缺方块"); return; }
            // 左边一台组合工厂 + 一串连接器 + 右边一台组合工厂
            connA = placeBL(press, 60, 60);
            connLink = placeBL(connB_, 62, 60);
            placeBL(connB_, 63, 60);
            placeBL(connB_, 64, 60);
            connB = placeBL(press, 65, 60);
            // 旁边再放一个组合节点（看它会不会自动连线）
            connNode = placeBL(nodeB, 61, 66);
            connA.items.add(Items.copper, 40);

            // 用户报的场景：组合节点**贴着** afflict 和 slag-heater 放（不点连线）时，
            // 面板应该把两者算成一组、并显示热量（修之前热量能传、但面板只有 1 台、热量 0）。
            Block heaterB = null, afflictB = null;
            for(Block b : Vars.content.blocks()){
                if(heaterB == null && b.name.equals("slag-heater")) heaterB = b;
                if(afflictB == null && b.name.equals("afflict")) afflictB = b;
            }
            connNodeHeat = placeBL(nodeB, 80, 60);
            connNodeHeatHeater = heaterB == null ? null : placeBL(heaterB, 81, 59);
            connNodeHeatAfflict = afflictB == null ? null : placeBL(afflictB, 76, 59);
            // 再贴一个原版 heat-source（不吃料、立刻产热）：保证截图里热量行有真实数字
            // （slag-heater 要等它把矿渣烧起来，软渲染下几秒内可能还是 0）。
            Building src = placeBL(Blocks.heatSource, 80, 59);
            if(connNodeHeatHeater != null && connNodeHeatHeater.liquids != null)
                connNodeHeatHeater.liquids.add(Liquids.slag, 5000f);
            connNodeHeat.updateProximity();
            if(src != null) src.updateProximity();
            if(connNodeHeatHeater != null) connNodeHeatHeater.updateProximity();
            if(connNodeHeatAfflict != null) connNodeHeatAfflict.updateProximity();
            Log.info("[drv] 方块类: afflict=@ 制热机=@ 节点=@ | isCombo(afflict)=@ isCombo(制热机)=@ showable(afflict)=@",
                afflictB == null ? null : afflictB.getClass().getName(),
                heaterB == null ? null : heaterB.getClass().getName(),
                nodeB.getClass().getName(),
                comboBuild(connNodeHeatAfflict), comboBuild(connNodeHeatHeater), showable(connNodeHeatAfflict));
            Log.info("[drv] conn 贴节点场景: 节点=@ 制热机=@ afflict=@ 节点邻格=@",
                connNodeHeat, connNodeHeatHeater, connNodeHeatAfflict,
                connNodeHeat.proximity == null ? -1 : connNodeHeat.proximity.size);

            // 第二种接法（用户最可能的用法）：节点离着放，**点连线**接制热机和 afflict（都在 6 格射程内）。
            connLinkedNode = placeBL(nodeB, 80, 72);
            connLinkedHeater = heaterB == null ? null : placeBL(heaterB, 84, 70);
            connLinkedAfflict = afflictB == null ? null : placeBL(afflictB, 72, 69);
            if(connLinkedHeater != null && connLinkedHeater.liquids != null)
                connLinkedHeater.liquids.add(Liquids.slag, 5000f);
            connLinkedNode.updateProximity();
            if(connLinkedHeater != null) connLinkedHeater.updateProximity();
            if(connLinkedAfflict != null) connLinkedAfflict.updateProximity();
            Building src2 = placeBL(Blocks.heatSource, 80, 68);   // 也在 6 格射程内
            if(src2 != null) src2.updateProximity();
            tapNodeLink(connLinkedNode, connLinkedHeater);
            tapNodeLink(connLinkedNode, connLinkedAfflict);
            tapNodeLink(connLinkedNode, src2);
            Log.info("[drv] conn 连线场景: 节点=@ 制热机=@ afflict=@ links=@",
                connLinkedNode, connLinkedHeater, connLinkedAfflict, field(connLinkedNode, "links"));
            Core.camera.position.set((connA.x + connB.x) / 2f, connA.y + 8f);
            Log.info("[drv] conn 场景: A=@ B=@ 同池=@ 连接器=@ 节点links=@",
                connA, connB, connA.items == connB.items, connLink, field(connNode, "links"));
        }catch(Throwable t){ Log.err("[drv] setupConnScene failed", t); }
    }

    static void stepConn(){
        try{
            if(connLink == null) return;
            if(connFrames < 0){ connFrames = frames; return; }
            if(frames - connFrames < 25) return;
            if(connPhase == 0){
                shot("conn_world");
                Log.info("[drv] conn 世界截图: A池=@ B池=@ 同池=@", connA.items.total(), connB.items.total(), connA.items == connB.items);
                connPhase = 1;
                connFrames = -1;
                openConnPanel();
            }else if(connPhase == 1){
                shot("conn_panel");
                connPhase = 2;
                connFrames = -1;
                openFactoryPanel();
            }else if(connPhase == 2){
                shot("conn_factory");
                connPhase = 3;
                connFrames = -1;
                openHeatPanel();
            }else if(connPhase == 3){
                shot("conn_heatpanel");
                connPhase = 4;
                connFrames = -1;
                openLinkedPanel();
            }else{
                shot("conn_linkedpanel");
                Log.info("[drv] conn 模式结束");
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] stepConn failed", t); }
    }

    static void openConnPanel(){
        try{
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            connLink.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(connLink, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合连接器信息");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] conn 面板已弹出: 子元素=@", panel.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] openConnPanel failed", t); }
    }

    /**
     * 组合建筑自己的信息面板（display）。
     *
     * <p>用户要求："既然有悬浮面板了，就把 display() 里的物品、液体、电力删了，只显示组合体构成，
     * 而且会换行" —— 这张截图就是拿来看"只剩构成那一行、没有物品/液体/电力条"的。
     */
    static void openFactoryPanel(){
        try{
            for(arc.scene.Element e : Core.scene.root.getChildren())
                if(e instanceof arc.scene.ui.Dialog dd && dd.isShown()) dd.hide();
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            connA.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(connA, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合工厂信息");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 组合工厂信息面板已弹出: 子元素=@", panel.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] openFactoryPanel failed", t); }
    }

    /**
     * 用户报的场景截图：组合节点贴着 afflict + slag-heater 放，点 afflict 弹悬浮面板 ——
     * 面板应该把两者算成一组、并且热量不为 0。
     */
    static void openHeatPanel(){
        try{
            for(arc.scene.Element e : Core.scene.root.getChildren())
                if(e instanceof arc.scene.ui.Dialog dd && dd.isShown()) dd.hide();
            if(connNodeHeatAfflict == null || !connNodeHeatAfflict.isValid()){
                Log.err("[drv] 贴节点场景没搭起来（afflict 为空）");
                return;
            }
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            cp.getMethod("init").invoke(null);
            // 镜头正对这台：方块落在画面中部，面板往上/下展开都不会顶到屏幕边。
            Core.camera.position.set(connNodeHeatAfflict.x, connNodeHeatAfflict.y);
            ensurePlayerUnit(connNodeHeatAfflict.x, connNodeHeatAfflict.y);
            try{
                Log.info("[drv] 热检查: 制热机 heat=@ producerHeat=@ slag=@ 效率=@ | afflict.heatReq=@ | 节点heat=@",
                    invokeVirtual(connNodeHeatHeater, "heat"),
                    getField(connNodeHeatHeater, "producerHeat"),
                    connNodeHeatHeater.liquids == null ? null : connNodeHeatHeater.liquids.get(Liquids.slag),
                    getField(connNodeHeatHeater, "efficiency"),
                    getField(connNodeHeatAfflict, "heatReq"),
                    getField(connNodeHeat, "heat"));
            }catch(Throwable t){ Log.err("[drv] 热检查失败", t); }
            cp.getMethod("tapped", mindustry.world.Tile.class)
                .invoke(null, Vars.world.tile(connNodeHeatAfflict.tileX(), connNodeHeatAfflict.tileY()));
            Log.info("[drv] 贴节点悬浮面板: showable=@ describe=@",
                cp.getMethod("showable", Building.class).invoke(null, connNodeHeatAfflict),
                cp.getMethod("describe", Building.class).invoke(null, connNodeHeatAfflict));
            Log.info("[drv] 贴节点·制热机侧: showable=@ describe=@",
                cp.getMethod("showable", Building.class).invoke(null, connNodeHeatHeater),
                cp.getMethod("describe", Building.class).invoke(null, connNodeHeatHeater));
        }catch(Throwable t){ Log.err("[drv] openHeatPanel failed", t); }
    }

    /** 第二种接法（点连线）的悬浮面板：也要成组、也要有热量。 */
    static void openLinkedPanel(){
        try{
            for(arc.scene.Element e : Core.scene.root.getChildren())
                if(e instanceof arc.scene.ui.Dialog dd && dd.isShown()) dd.hide();
            if(connLinkedAfflict == null || !connLinkedAfflict.isValid()){
                Log.err("[drv] 连线场景没搭起来（afflict 为空）");
                return;
            }
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            cp.getMethod("init").invoke(null);
            Core.camera.position.set(connLinkedAfflict.x, connLinkedAfflict.y + 30f);
            ensurePlayerUnit(connLinkedAfflict.x, connLinkedAfflict.y + 30f);
            cp.getMethod("tapped", mindustry.world.Tile.class)
                .invoke(null, Vars.world.tile(connLinkedAfflict.tileX(), connLinkedAfflict.tileY()));
            Log.info("[drv] 连线悬浮面板: showable=@ describe=@ | 节点links=@ afflict.heatReq=@ 制热机heat=@",
                cp.getMethod("showable", Building.class).invoke(null, connLinkedAfflict),
                cp.getMethod("describe", Building.class).invoke(null, connLinkedAfflict),
                field(connLinkedNode, "links"), getField(connLinkedAfflict, "heatReq"),
                invokeVirtual(connLinkedHeater, "heat"));
        }catch(Throwable t){ Log.err("[drv] openLinkedPanel failed", t); }
    }

    // ---------------- 核反应堆发电效率 / 燃料条 ----------------
    static Building genNuke, genOther;
    static int genNukeCap = 30;

    static Object field(Object o, String name){
        try{
            Class<?> c = o.getClass();
            while(c != null){
                try{ var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch(NoSuchFieldException ignored){ c = c.getSuperclass(); }
            }
        }catch(Throwable ignored){}
        return null; }

    static float productionEfficiency(Building b){
        Object v = field(b, "productionEfficiency");
        return v instanceof Number n ? n.floatValue() : -1f; }

    /**
     * 核反应堆 + 一台"容量被撑到 10 万"的发电机（模拟用户说的"组合了一台容量极大的建筑"）。
     * 核容量应该还是核反应堆自己那一份，燃料只要够那一份就该满效率。
     */
    static void setupGenScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            // 客户端 `display()` 里有 "不是本方就只画标题" 的检查，而暂停时 updateTile 不跑
            // （效率永远是 0），所以这里：建在玩家自己的队上 + 别停在暂停态。
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block nuke = null, other = null;
            for(Block b : Vars.content.blocks()){
                if(b.name.equals("thorium-reactor")) nuke = b;
                if(other == null && b.getClass().getName().endsWith("CombinedGenerator")
                    && "consume".equals(String.valueOf(field(b, "mode"))) && b.itemCapacity <= 20) other = b;
            }
            if(nuke == null || other == null){ Log.err("[drv] gen 场景缺方块: nuke=@ other=@", nuke, other); return; }
            other.itemCapacity = 100000;   // 撑大池子容量
            int nsz = Math.max(nuke.size, 1);
            genNukeCap = Math.max(nuke.itemCapacity, 1);
            genNuke = placeBL(nuke, 60, 60, Vars.player.team());
            genOther = placeBL(other, 60 + nsz, 60, Vars.player.team());
            Core.camera.position.set(genNuke.x, genNuke.y);
            Log.info("[drv] gen 场景: 队=@ 玩家队=@ paused=@ playing=@ 同池=@ 池容量=@ 核容量=@ 单台核容量=@",
                genNuke.team, Vars.player.team(), Vars.state.isPaused(), Vars.state.isPlaying(),
                genNuke.items == genOther.items, field(genNuke, "comboTotalItemCap"),
                field(genNuke, "comboNuclearItemCap"), nuke.itemCapacity);
        }catch(Throwable t){ Log.err("[drv] setupGenScene failed", t); }
    }

    static void genSetFuel(int n){
        try{
            if(genNuke == null){ Log.err("[drv] genNuke 为空"); return; }
            genNuke.items.set(Items.thorium, n);
            // 让下一帧真的跑过 updateTile（面板/效率都靠它算）
            Timer.schedule(() -> Log.info("[drv] gen 燃料=@ 效率=@", genNuke.items.get(Items.thorium),
                productionEfficiency(genNuke)), 0.5f);
        }catch(Throwable t){ Log.err("[drv] genSetFuel failed", t); }
    }

    static void openGenPanel(){
        try{
            if(genNuke == null){ Log.err("[drv] genNuke 为空"); return; }
            Log.info("[drv] gen 开面板: 燃料=@ 效率=@ 池容量=@ 核容量=@",
                genNuke.items.get(Items.thorium), productionEfficiency(genNuke),
                field(genNuke, "comboTotalItemCap"), field(genNuke, "comboNuclearItemCap"));
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            genNuke.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(genNuke, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("核反应堆面板（组合进大容量发电机）");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] gen 面板已弹出: 子元素=@ 场景顶层=@ 尺寸=@x@",
                panel.getChildren().size, Core.scene == null ? -1 : Core.scene.root.getChildren().size,
                (int)d.getWidth(), (int)d.getHeight());
        }catch(Throwable t){ Log.err("[drv] openGenPanel failed", t); }
    }

    /**
     * 卡帧数的截图推进：
     *   开面板 → 真过了 30 帧 → 截"半池" → 塞 1000 个（超核容量）重开面板 → 再 30 帧 → 截"满池" → 退出。
     */
    static int genPhase = 0, genFrames = -1;

    static void genStep(){
        try{
            if(genNuke == null) return;
            if(genFrames < 0){
                genFrames = frames;
                return;
            }
            if(frames - genFrames < 30) return;
            if(genPhase == 0){
                shot("gen_panel_half");
                Log.info("[drv] gen 半池: 燃料=@ 效率=@", genNuke.items.get(Items.thorium), productionEfficiency(genNuke));
                genPhase = 1;
                genFrames = -1;
                hideDialogs();
                genSetFuel(1000);
                openGenPanel();
            }else{
                shot("gen_panel_over1000");
                Log.info("[drv] gen 超容量: 燃料=@ 效率=@", genNuke.items.get(Items.thorium), productionEfficiency(genNuke));
                Log.info("[drv] gen 模式结束（frames=@ paused=@）", frames, Vars.state.isPaused());
                Core.app.exit();
            }
        }catch(Throwable t){ Log.err("[drv] genStep failed", t); }
    }

    /** 等仓库重算跑完（合并/容量都稳定）再打印数字 + 把容器的信息面板弹出来截图。 */
    static void openStatusPanel(){
        try{
            if(c1 == null){ Log.err("[drv] c1 为空，没法开面板"); return; }
            mindustry.world.blocks.storage.CoreBlock.CoreBuild core = c1.team.core();
            Log.info("[drv] status 结果: 核心容量=@ 库存总和=@ 容器并入核心=@ 容器模块==核心模块=@",
                core == null ? -1 : core.storageCapacity,
                core == null || core.items == null ? -1 : core.items.total(),
                c1 instanceof mindustry.world.blocks.storage.StorageBlock.StorageBuild sb && sb.linkedCore != null,
                core != null && c1.items == core.items);
            Building fac = null;
            for(Building b : Vars.state.teams.get(Team.sharded).buildings)
                if(b.block.getClass().getName().startsWith("combine.units.CombinedUnitFactory")) { fac = b; break; }
            if(fac != null){
                var ufb = (mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild) fac;
                Log.info("[drv] 无限火力工厂结果: progress=@ 计划=@ payload=@ 单位=@",
                    ufb.progress, ufb.unit() == null ? "-" : ufb.unit().name,
                    ufb.payload, ufb.payload == null ? "-" : ufb.payload.unit.type.name);
            }
            Log.info("[drv] 无限火力工厂累计产出单位数=@", unitCreates);
            arc.scene.ui.layout.Table panel = new arc.scene.ui.layout.Table();
            c1.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(c1, panel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("容器面板（组合仓库并进核心）");
            d.cont.add(panel).pad(10f);
            d.show();
            Log.info("[drv] 容器面板已弹出（panel 子元素=@）", panel.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] openStatusPanel failed", t); }
    }

    static void setupLaunchScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            mindustry.world.blocks.storage.CoreBlock coreB = null;
            for(Block b : Vars.content.blocks()) if(b.name.equals("core-shard") && b instanceof mindustry.world.blocks.storage.CoreBlock cb) coreB = cb;
            if(coreB == null){ Log.err("[drv] 没找到 core-shard"); return; }
            Log.info("[drv] core-shard 实例=@（组合类=@）", coreB.getClass().getName(),
                coreB.getClass().getName().startsWith("combine."));
            Building core = place(coreB, 60, 60);
            Core.camera.position.set(core.x, core.y);

            var sectors = Planets.serpulo.sectors;
            if(sectors.isEmpty()){ Log.err("[drv] serpulo 没有 sector，没法开发射界面"); return; }
            Sector dest = sectors.get(Math.min(3, sectors.size - 1));
            Vars.state.rules.sector = dest;

            Log.info("[drv] getLoadout(核心)=@ getLoadouts().get(核心)=@",
                Vars.universe.getLoadout(coreB),
                Vars.schematics.getLoadouts().get(coreB));

            // 真·发射界面（和玩家点"发射"是同一条代码路径）
            new mindustry.ui.dialogs.LaunchLoadoutDialog().show(coreB, dest, dest, () -> {});
            launchShown = true;
            Log.info("[drv] 发射界面已弹出（没崩）");
        }catch(Throwable t){ Log.err("[drv] setupLaunchScene failed", t); }
    }

    static void setupWorld(){
        try{
            // 先把设置界面收起来，不然它会一直盖在世界上面（截图就看不到 CoopPanel 了）
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            // 别让它因为"没有核心"判负：一旦弹结算界面，CoopPanel 就被盖住了
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            Class<?> coop = Class.forName("combine.coop.CoopCombo", true, ml);
            java.lang.reflect.Method eligible = coop.getMethod("eligible", Block.class);
            int ext = 0;
            for(Block b : Vars.content.blocks()) if((Boolean)eligible.invoke(null, b)) ext++;
            Log.info("[drv] 扩展建筑数量=@（方块总数 @）", ext, Vars.content.blocks().size);
            Block prod = null;
            for(Block b : Vars.content.blocks()){
                if((Boolean)eligible.invoke(null, b) && b.name.endsWith("冶炼厂") && b.hasItems && b.hasLiquids){ prod = b; break; }
            }
            for(Block b : Vars.content.blocks()){
                if(prod != null) break;
                if((Boolean)eligible.invoke(null, b) && b.hasItems && b.size <= 2){ prod = b; break; }
            }
            if(prod == null){ Log.err("[drv] 没找到可用的扩展建筑"); return; }
            Log.info("[drv] 用 @ (@) 做组合体", prod.name, prod.getClass().getName());
            int sz = Math.max(prod.size, 1);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            b1 = place(prod, 60, 60);
            b2 = place(prod, 60 + sz, 60);
            // 让镜头对着这两台
            Core.camera.position.set(b1.x, b1.y);
            // 给组里塞点东西（两台共用一份池子）
            addPool(Items.copper, 40, 0f);
            Log.info("[drv] placed @ @  items=@", b1.block.name, b2.block.name, b1.items.get(Items.copper));
            Class<?> coopPanel = Class.forName("combine.coop.CoopPanel", true, ml);
            coopPanel.getMethod("tapped", Tile.class).invoke(null, b1.tile);
            Log.info("[drv] CoopPanel tapped");
        }catch(Throwable t){ Log.err("[drv] setupWorld failed", t); }
    }

    // ---------------- ve 单位组装厂的"建筑载荷"（mode=veasm） ----------------
    //
    // 组装厂的配方要求（PayloadStack）是内容装配阶段抓的方块实例，原版
    // UnitAssemblerBuild.acceptPayload 用的是 `b.item == payload.content()` 身份比较：
    // 只要"要求里那件"和"世界里那件"不是同一个实例，建筑载荷就永远收不进去。
    static boolean veAsmDone;

    static Block byName(String n){
        for(Block b : Vars.content.blocks()) if(b.name.equals(n)) return b;
        return null;
    }

    /** 打包机的 filter 里有没有这一件（身份比较）。 */
    @SuppressWarnings("unchecked")
    static boolean filterHas(Object filterObj, Block b){
        if(!(filterObj instanceof Seq<?> s)) return false;
        for(Object o : s) if(o == b) return true;
        return false;
    }

    static Object fieldOf(Object o, String name){
        for(Class<?> c = o == null ? null : o.getClass(); c != null && c != Object.class; c = c.getSuperclass()){
            try{
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            }catch(NoSuchFieldException ignored){
            }catch(Throwable ignored){
                return null;
            }
        }
        return null;
    }

    /** 读静态字段（私有也能读：一路往上找 declared 字段）。 */
    static Object staticField(Class<?> cls, String name){
        for(Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()){
            try{
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(null);
            }catch(NoSuchFieldException ignored){
            }catch(Throwable t){
                Log.err("[drv] 读静态字段 @.@ 失败", cls.getSimpleName(), name, t);
                return null;
            }
        }
        return null;
    }

    static void setupVeAsmScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            Building core = placeBL(Blocks.coreShard, 40, 100);
            if(core != null && core.items != null) for(Item it : Vars.content.items()) core.items.set(it, 5000);
            Core.camera.position.set(60 * 8f, 62 * 8f);
        }catch(Throwable t){ Log.err("[drv] setupVeAsmScene failed", t); }
    }

    static void veAsmCheck(){
        if(veAsmDone) return;
        veAsmDone = true;
        try{
            Class<?> rep = Class.forName("combine.Replacer", true, ml);
            @SuppressWarnings("unchecked")
            arc.struct.ObjectMap<Block, Block> replaced =
                (arc.struct.ObjectMap<Block, Block>)rep.getField("replaced").get(null);

            Block asm = byName("ve-super-assembler"), pack = byName("ve-super-assemble-pack");
            Log.info("[drv] veasm 组装厂=@ (@) 套件=@ (@)", asm == null ? "无" : asm.name,
                asm == null ? "-" : asm.getClass().getName(), pack == null ? "无" : pack.name,
                pack == null ? "-" : pack.getClass().getName());
            if(asm == null || pack == null){
                Log.info("[drv] veasm 这个数据目录里没有 ve 组装厂，跳过");
                return;
            }
            Log.info("[drv] veasm 套件实例: 是旧实例(被换掉)? @ 是组合实例? @ 内容表按名查回同实例? @",
                replaced.containsKey(pack), replaced.containsValue(pack, true), Vars.content.block(pack.name) == pack);

            // 组装厂配方里的方块要求
            Block reqBlock = null;
            String reqName = "-";
            Object plansObj = fieldOf(asm, "plans");
            if(plansObj instanceof Seq<?> plans){
                int reqTotal = 0;
                for(Object plan : plans){
                    Object unit = fieldOf(plan, "unit");
                    Object reqs = fieldOf(plan, "requirements");
                    if(!(reqs instanceof Seq<?> rq)) continue;
                    for(Object o : rq){
                        if(!(o instanceof mindustry.type.PayloadStack st)) continue;
                        reqTotal++;
                        String kind = st.item instanceof Block ? "建筑" : (st.item instanceof UnitType ? "单位" : "其它");
                        String extra = "";
                        if(st.item instanceof Block bb){
                            extra = " | 旧实例? " + replaced.containsKey(bb)
                                + " 组合实例? " + replaced.containsValue(bb, true)
                                + " 内容表按名查回同实例? " + (Vars.content.block(bb.name) == bb)
                                + " 类=" + bb.getClass().getSimpleName();
                            if(reqBlock == null){ reqBlock = bb; reqName = bb.name; }
                        }
                        Log.info("[drv] veasm 方案「@」要求 @ x@ (@)@", unit == null ? "?" : unit, st.item.name, st.amount, kind, extra);
                    }
                }
                Log.info("[drv] veasm 组装厂方案数=@ 载荷要求数=@", plans.size, reqTotal);
            }

            // 世界里的一块套件（实际放的建筑用的是哪个实例）
            Building wall = placeBL(pack, 72, 62);
            Block worldInst = wall == null ? null : wall.block;
            Log.info("[drv] veasm 世界里的套件: @ (@) | 与要求同实例? @ | 与内容表同实例? @",
                worldInst == null ? "放不下来" : worldInst.name,
                worldInst == null ? "-" : worldInst.getClass().getName(),
                worldInst == reqBlock, worldInst == Vars.content.block(pack.name));

            // 打包机（ve-assemble-packer，Constructor 的 JS 子类）：它的 filter 是"能打包哪几种建筑"的
            // 白名单，JSON 里按名字抓的是**内容装配阶段**的方块实例；这一份要是没跟着换成组合实例，
            // 玩家从它面板里选中套件 → 打出来的载荷是**老实例** → 与组装厂要求（组合实例）身份不符。
            Block packer = byName("ve-assemble-packer");
            Block filterPack = null;
            if(packer != null){
                Object filterObj = fieldOf(packer, "filter");
                Log.info("[drv] veasm 打包机=@ (@) filter=@", packer.name, packer.getClass().getName(), filterObj);
                if(filterObj instanceof Seq<?> fs){
                    StringBuilder sb = new StringBuilder();
                    for(Object o : fs){
                        if(!(o instanceof Block fb)) continue;
                        boolean stale = replaced.containsKey(fb), combo = replaced.containsValue(fb, true);
                        sb.append(fb.name).append("/").append(fb.getClass().getSimpleName())
                          .append(stale ? "[旧实例]" : combo ? "[组合实例]" : "[普通]").append(" ");
                        if(fb.name.equals(pack.name) && filterPack == null) filterPack = fb;
                    }
                    Log.info("[drv] veasm 打包机 filter 里 " + fs.size + " 项: " + sb);
                }
                Log.info("[drv] veasm 打包机筛选条件: isVisible=@ size=@ 范围=@~@ 被禁?@ 本星球可造?@ filter里有?@ 现在星球=@",
                    pack.isVisible(), pack.size, fieldOf(packer, "minBlockSize"), fieldOf(packer, "maxBlockSize"),
                    Vars.state.rules.isBanned(pack), pack.environmentBuildable(),
                    filterHas(filterObj, pack), Vars.state.getPlanet());
                boolean canProd = false;
                try{ canProd = (Boolean)packer.getClass().getMethod("canProduce", Block.class).invoke(packer, pack); }catch(Throwable ignored){}
                Log.info("[drv] veasm 打包机能不能打包「内容表里的那个套件」: @", canProd);
                // ve 的组装套件只标在赛克兰特/玛瑞斯等星球上（shownPlanets）——env 不对时
                // Constructor.canProduce 本来就该是 false。换成 ve-cyclant 再判一次，看筛选到底通不通。
                mindustry.type.Planet cy = Vars.content.planet("ve-cyclant");
                if(cy != null){
                    mindustry.type.Planet oldPlanet = Vars.state.rules.planet;
                    mindustry.type.Sector oldSector = Vars.state.rules.sector;
                    Vars.state.rules.planet = cy;
                    Vars.state.rules.sector = null;
                    try{
                        boolean can2 = (Boolean)packer.getClass().getMethod("canProduce", Block.class).invoke(packer, pack);
                        Log.info("[drv] veasm 换成赛克兰特（ve-cyclant）后: 本星球可造?@ 能不能打包套件?@",
                            pack.environmentBuildable(), can2);
                    }catch(Throwable ignored){
                    }finally{
                        Vars.state.rules.planet = oldPlanet;
                        Vars.state.rules.sector = oldSector;
                    }
                }
            }

            Building ab = placeBL(asm, 60, 60);
            Log.info("[drv] veasm 组装厂落地=@ (@) unitCost=@", ab != null,
                ab == null ? "-" : ab.getClass().getName(), Vars.state.rules.unitCost(Team.sharded));
            if(ab == null) return;

            java.util.LinkedHashMap<String, Block> payloads = new java.util.LinkedHashMap<>();
            if(reqBlock != null) payloads.put("要求里那件(" + reqName + ")", reqBlock);
            payloads.put("内容表按名查回(" + pack.name + ")", pack);
            if(worldInst != null) payloads.put("世界里的(" + worldInst.name + ")", worldInst);
            if(filterPack != null) payloads.put("打包机 filter 里那件", filterPack);
            for(var e : payloads.entrySet()){
                var payload = new mindustry.world.blocks.payloads.BuildPayload(e.getValue(), Team.sharded);
                boolean acc = false;
                String err = "";
                try{ acc = ab.acceptPayload(ab, payload); }catch(Throwable t){ err = " 抛出 " + t; }
                Log.info("[drv] veasm 组装厂收载荷 @: @ @（载荷 content=@ / @）", e.getKey(), acc, err,
                    payload.content().name, payload.content().getClass().getSimpleName());
            }

            // 真塞一次：acceptPayload 说能收的才走 handlePayload，再看载荷模块里有没有计数
            var real = new mindustry.world.blocks.payloads.BuildPayload(worldInst == null ? pack : worldInst, Team.sharded);
            if(ab.acceptPayload(ab, real)){
                ab.handlePayload(ab, real);
                Object mod = fieldOf(ab, "blocks");
                Object got = mod instanceof mindustry.type.PayloadSeq ps ? ps.get(real.content()) : null;
                Log.info("[drv] veasm 已把套件塞进组装厂：载荷模块计数=@（content=@）", got, real.content().name);
            }else{
                Log.info("[drv] veasm 套件塞不进去（acceptPayload=false）—— 就是用户报的「不能输入建筑」");
            }
            shot("veasm_world");
        }catch(Throwable t){ Log.err("[drv] veAsmCheck failed", t); t.printStackTrace(); }
    }

    // ---------------- 组合体构成列表的滚动窗（mode=bigcombo） ----------------
    //
    // 用户报的"组合的东西过多时显示面板非常大"：摆一排**不同类型**的组合物品炮塔（跨类型组合，
    // 全在一个组合体里），把信息面板弹出来 —— 面板高度要封顶、列表要能滚。
    static final java.util.ArrayList<Building> bigComboMembers = new java.util.ArrayList<>();
    static arc.scene.ui.layout.Table bigComboPanel;
    static arc.scene.ui.ScrollPane bigComboPane;
    static int bigComboDistinct;

    static void setupBigComboScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(Vars.state.isPaused()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<130;y++) for(int x=20;x<240;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }
            Core.camera.position.set(70 * 8f, 60 * 8f);
        }catch(Throwable t){ Log.err("[drv] setupBigComboScene failed", t); }
    }

    /** 点一下组合方块：CoopPanel 应该弹出"大的悬浮面板"（和 js/java 扩展建筑同一个）。 */
    static void floatPanelTap(){
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            cp.getMethod("init").invoke(null);
            if(bigComboMembers.isEmpty()){ Log.info("[drv] floatpanel 没摆出方块"); return; }
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            Object showable = cp.getMethod("showable", Building.class).invoke(null, pick);
            cp.getMethod("tapped", mindustry.world.Tile.class).invoke(null, Vars.world.tile(pick.tileX(), pick.tileY()));
            Log.info("[drv] floatpanel 点了 @ 的格子: showable=@ 面板可见=@", pick.block.name, showable,
                fieldOf(staticField(cp, "table"), "visible"));
            Core.camera.position.set(pick.x, pick.y);
            installCameraLock();
        }catch(Throwable t){ Log.err("[drv] floatPanelTap failed", t); }
    }

    /** 把被点方块的池子灌满所有物品种类 —— 让悬浮面板内容足够高，能看出"封顶 + 可滚动"。 */
    static void floatPanelFillAll(){
        try{
            if(bigComboMembers.isEmpty()) return;
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            if(pick.items != null){
                int i = 0;
                for(mindustry.type.Item it : Vars.content.items()){
                    pick.items.add(it, 3 + (i % 5));
                    i++;
                }
            }
            if(pick.liquids != null){
                int i = 0;
                for(mindustry.type.Liquid lq : Vars.content.liquids()){
                    pick.liquids.add(lq, 5f + i);
                    i++;
                }
            }
            Log.info("[drv] 悬浮面板灌满所有物品/液体（内容会变高，用来看滚动）");
        }catch(Throwable t){ Log.err("[drv] floatPanelFillAll failed", t); }
    }

    /** 点一台组合体：详情悬浮面板 + 组合体共享面板都该弹出来。 */
    static void bodyShareTap(){
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            cp.getMethod("init").invoke(null);
            if(bigComboMembers.isEmpty()){ Log.info("[drv] bodyshare 没摆出方块"); return; }
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            cp.getMethod("tapped", mindustry.world.Tile.class).invoke(null, Vars.world.tile(pick.tileX(), pick.tileY()));
            Core.camera.position.set(pick.x, pick.y);
            installCameraLock();
            Log.info("[drv] bodyshare 点了 @ @", pick.block.name, pick.tileX() + "," + pick.tileY());
        }catch(Throwable t){ Log.err("[drv] bodyShareTap failed", t); }
    }

    /** 把两张悬浮面板的矩形打出来，并算一下有没有重叠（用户要求"不要和详情面板重叠"）。 */
    static void bodyShareReport(){
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            Class<?> sp2 = Class.forName("combine.net.ComboSharePanel", true, ml);
            float[] a = (float[])cp.getMethod("rect").invoke(null);
            float[] b = (float[])sp2.getMethod("rect").invoke(null);
            boolean overlap = a != null && b != null
                && a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
            Log.info("[drv] bodyshare 详情面板=@ 共享面板=@ 重叠=@", rectStr(a), rectStr(b), overlap);
            if(a == null) Log.err("[drv] FAIL 详情悬浮面板没显示");
            if(b == null) Log.err("[drv] FAIL 组合体共享面板没显示");
            if(a != null && b != null && !overlap)
                Log.info("[drv] PASS 两张悬浮面板都显示且不重叠");
            if(overlap) Log.err("[drv] FAIL 两张悬浮面板重叠了");
            Class<?> share = Class.forName("combine.net.ComboShare", true, ml);
            Object mask = share.getMethod("maskOf", Building.class).invoke(null, bigComboMembers.get(bigComboMembers.size() / 2));
            Log.info("[drv] bodyshare 当前共享配置 mask=@（15=全共享，1=物品，2=液体，4=电力，8=热量）", mask);
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            Log.info("[drv] bodyshare 诊断: built=@ target=@ visible=@ showable=@ 在场景里=@ 表尺寸=@x@",
                staticField(sp2, "built"), sp2.getMethod("target").invoke(null),
                fieldOf(staticField(sp2, "table"), "visible"),
                sp2.getMethod("showable", Building.class).invoke(null, pick),
                Core.scene.find("combosharepanel") != null,
                (int)fieldOfF(staticField(sp2, "table"), "width"), (int)fieldOfF(staticField(sp2, "table"), "height"));
        }catch(Throwable t){ Log.err("[drv] bodyShareReport failed", t); }
    }

    static float fieldOfF(Object o, String name){
        Object v = fieldOf(o, name);
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    static String rectStr(float[] r){
        return r == null ? "无" : ("[" + (int)r[0] + "," + (int)r[1] + " → " + (int)r[2] + "," + (int)r[3] + "]");
    }

    /** 在共享面板上勾掉"物品"（= ComboShare.toggle(bit=1)），看配置有没有变。 */
    static void bodyShareToggle(){
        try{
            if(bigComboMembers.isEmpty()) return;
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            Class<?> share = Class.forName("combine.net.ComboShare", true, ml);
            Object r = share.getMethod("toggle", Building.class, int.class).invoke(null, pick, 1);
            Log.info("[drv] bodyshare 勾掉物品 → mask=@", r);
        }catch(Throwable t){ Log.err("[drv] bodyShareToggle failed", t); }
    }

    /** 用户报的"怎么点别的队伍的建筑也有"：点一台敌方队伍的组合体，共享面板不该弹。 */
    static void bodyShareForeign(){
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            Class<?> sp2 = Class.forName("combine.net.ComboSharePanel", true, ml);
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            Building foe = placeBL(pick.block, pick.tileX() + 8, pick.tileY(), Team.crux);
            if(foe == null){ Log.err("[drv] FAIL 没摆出敌方组合体"); return; }
            cp.getMethod("tapped", mindustry.world.Tile.class).invoke(null, Vars.world.tile(foe.tileX(), foe.tileY()));
            Object r = sp2.getMethod("rect").invoke(null);
            Log.info("[drv] bodyshare 点敌方(@)建筑: 共享面板=@", foe.team, r == null ? "没弹" : "弹了");
            if(r == null) Log.info("[drv] PASS 点别的队伍的建筑不弹组合体共享面板");
            else Log.err("[drv] FAIL 点别的队伍的建筑也弹了组合体共享面板");
        }catch(Throwable t){ Log.err("[drv] bodyShareForeign failed", t); }
    }

    /** 设置里关掉"显示组合体共享面板"后，点自己的组合体也不该弹。 */
    static void bodyShareSettingOff(){
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            Class<?> sp2 = Class.forName("combine.net.ComboSharePanel", true, ml);
            Class<?> ui = Class.forName("combine.util.ComboUi", true, ml);
            java.lang.reflect.Method set = ui.getMethod("setSharePanel", boolean.class);
            set.invoke(null, false);
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            cp.getMethod("tapped", mindustry.world.Tile.class).invoke(null, Vars.world.tile(pick.tileX(), pick.tileY()));
            Object r = sp2.getMethod("rect").invoke(null);
            Log.info("[drv] bodyshare 设置里关掉后点自己的组合体: 共享面板=@", r == null ? "没弹" : "弹了");
            if(r == null) Log.info("[drv] PASS 设置里关掉后不再弹组合体共享面板");
            else Log.err("[drv] FAIL 设置里关掉后还是弹了");
            set.invoke(null, true); // 还原
        }catch(Throwable t){ Log.err("[drv] bodyShareSettingOff failed", t); }
    }

    /** 悬浮面板尺寸检查：面板必须封顶、内容在 ScrollPane 里（用户要求"大小限制一下、改成可滑动面板"）。 */
    static void floatPanelSizeReport(){
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            arc.scene.Element el = Core.scene == null ? null : Core.scene.find("coopinventory");
            if(el == null){ Log.err("[drv] 悬浮面板不在场景里"); return; }
            arc.scene.ui.ScrollPane sp = null;
            if(el instanceof arc.scene.Group g)
                for(arc.scene.Element c : g.getChildren())
                    if(c instanceof arc.scene.ui.ScrollPane p2){ sp = p2; break; }
            float pw = el.getWidth(), ph = el.getHeight();
            float sceneW = Core.scene.getWidth(), sceneH = Core.scene.getHeight();
            Log.info("[drv] 悬浮面板尺寸: 面板=@x@ 屏幕=@x@ 有滚动条容器=@", (int)pw, (int)ph,
                (int)sceneW, (int)sceneH, sp != null);
            if(sp != null){
                arc.scene.Element inner = sp.getWidget();
                Log.info("[drv] 悬浮面板: 内容=@x@ 可滚高度=@ 当前滚动=@",
                    (int)inner.getWidth(), (int)inner.getHeight(), (int)sp.getMaxY(), (int)sp.getScrollY());
                // 内容比面板高 → 必须能滚（maxY>0）；内容本来就装得下 → 不算失败
                boolean fits = inner.getHeight() + 12f <= ph + 1f;
                if(ph <= sceneH * 0.42f && pw <= sceneW * 0.55f && (sp.getMaxY() > 1f || fits))
                    Log.info("[drv] PASS 悬浮面板尺寸封顶（@x@ ≤ 屏幕 @x@ 的 42%/55%），内容可滚动=@（内容高 @ / 面板高 @）",
                        (int)pw, (int)ph, (int)sceneW, (int)sceneH, sp.getMaxY() > 1f, (int)inner.getHeight(), (int)ph);
                else
                    Log.err("[drv] FAIL 面板没封顶 / 没滚动（面板 @x@ 屏幕 @x@ 可滚 @）",
                        (int)pw, (int)ph, (int)sceneW, (int)sceneH, (int)sp.getMaxY());
            }else{
                Log.err("[drv] FAIL 悬浮面板里没有 ScrollPane");
            }
        }catch(Throwable t){ Log.err("[drv] floatPanelSizeReport failed", t); }
    }

    /** 点别处（空格子）：悬浮面板应该收起。 */
    static void floatPanelTapElsewhere(){
        try{
            Class<?> cp = Class.forName("combine.coop.CoopPanel", true, ml);
            cp.getMethod("tapped", mindustry.world.Tile.class).invoke(null, Vars.world.tile(45, 90));
            Log.info("[drv] floatpanel 点了别处: 面板可见=@", fieldOf(cp.getField("table").get(null), "visible"));
        }catch(Throwable t){ Log.err("[drv] floatPanelTapElsewhere failed", t); }
    }

    static void bigComboBuild(){
        try{
            java.util.LinkedHashMap<String, Block> picks = new java.util.LinkedHashMap<>();
            for(Block b : Vars.content.blocks()){
                if(!b.getClass().getName().equals("combine.turret.CombinedItemTurret")) continue;
                picks.putIfAbsent(b.name, b);
                if(picks.size() >= 16) break;
            }
            int x = 40, y = 62;
            StringBuilder names = new StringBuilder();
            for(Block b : picks.values()){
                Building mem = placeBL(b, x, y, Team.sharded);
                if(mem != null){
                    bigComboMembers.add(mem);
                    if(names.length() > 0) names.append(", ");
                    names.append(b.localizedName);
                }
                x += Math.max(b.size, 1);
                if(x > 150) break;
            }
            bigComboDistinct = bigComboMembers.size();
            // 跑几帧让分组生效（合并/分组在 updateTile 里重建）
            for(int i = 0; i < 30; i++) arc.util.Time.delta = 1f;
            Log.info("[drv] bigcombo 摆了 @ 台（@ 种不同类型）: @", bigComboMembers.size(), bigComboDistinct, names);
        }catch(Throwable t){ Log.err("[drv] bigComboBuild failed", t); }
    }

    static void findScrollPane(arc.scene.Element e){
        if(e == null || bigComboPane != null) return;
        if(e instanceof arc.scene.ui.ScrollPane sp){ bigComboPane = sp; return; }
        if(e instanceof arc.scene.Group g)
            for(arc.scene.Element c : g.getChildren()) findScrollPane(c);
    }

    static void bigComboPanel(){
        try{
            if(bigComboMembers.isEmpty()){ Log.info("[drv] bigcombo 没摆出炮塔"); return; }
            Building pick = bigComboMembers.get(bigComboMembers.size() / 2);
            bigComboPanel = new arc.scene.ui.layout.Table();
            pick.getClass().getMethod("display", arc.scene.ui.layout.Table.class).invoke(pick, bigComboPanel);
            findScrollPane(bigComboPanel);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog("组合体构成（滚动窗）");
            d.cont.add(bigComboPanel).pad(10f);
            d.show();
            Log.info("[drv] bigcombo 面板已弹出: 建筑=@ 找到滚动窗=@", pick.block.name, bigComboPane != null);
        }catch(Throwable t){ Log.err("[drv] bigComboPanel failed", t); }
    }

    static void bigComboScrollBottom(){
        try{
            // 面板内容是"挂到场景里之后由 update 回调"才画出来的：这一刻再找一次滚动窗
            bigComboPane = null;
            findScrollPane(bigComboPanel);
            if(bigComboPane == null) return;
            bigComboPane.setScrollYForce(bigComboPane.getMaxY());
            bigComboPane.updateVisualScroll();
            Log.info("[drv] bigcombo 滚到底: scrollY=@ maxY=@", bigComboPane.getScrollY(), bigComboPane.getMaxY());
        }catch(Throwable t){ Log.err("[drv] bigComboScrollBottom failed", t); }
    }

    static void bigComboReport(){
        try{
            bigComboPane = null;
            findScrollPane(bigComboPanel);
            StringBuilder sb = new StringBuilder();
            collectLabels(bigComboPanel, sb);
            Log.info("[drv] bigcombo 报告: 摆的台数=@ | 滚动窗高=@ 内容高=@ 可滚=@",
                bigComboMembers.size(),
                bigComboPane == null ? -1 : (int)bigComboPane.getHeight(),
                bigComboPane == null || bigComboPane.getWidget() == null ? -1 : (int)bigComboPane.getWidget().getHeight(),
                bigComboPane == null ? -1 : (int)bigComboPane.getMaxY());
            Log.info("[drv] bigcombo 面板上的每一行: @", sb);
        }catch(Throwable t){ Log.err("[drv] bigComboReport failed", t); }
    }

    // ---------------- 巨兽悬浮信息栏里的"类型名"（mode=beastname） ----------------

    static void beastInspect(){
        try{
            if(megaUnit == null){ Log.info("[drv] beastname 没合出巨兽"); return; }
            Unit beast = megaUnit;
            Object comp = combineCall("combineunit.units.UnitComboMerge", "composition",
                new Class<?>[]{Unit.class}, beast);
            Log.info("[drv] beastname 构成=@ | 类型名(localizedName)=@ | 类型内部名=@",
                comp, beast.type.localizedName, beast.type.name);

            // 走一遍原版 display（原版悬浮栏与 mi2 用的是同一条路），把里面的 Label 文字打出来
            arc.scene.ui.layout.Table t = new arc.scene.ui.layout.Table();
            beast.type.display(beast, t);
            StringBuilder sb = new StringBuilder();
            collectLabels(t, sb);
            Log.info("[drv] beastname display 文字: @", sb);

            // 原版悬浮信息栏画的就是 blockfrag.hover（Displayable）：直接设成这只巨兽，
            // 免得依赖真实鼠标位置（离屏 SDL 下鼠标在 0,0）
            try{
                java.lang.reflect.Field hf = null;
                for(Class<?> c = Vars.ui.hudfrag.blockfrag.getClass(); c != null && hf == null; c = c.getSuperclass()){
                    try{ hf = c.getDeclaredField("hover"); }catch(NoSuchFieldException ignored){}
                }
                if(hf != null){
                    hf.setAccessible(true);
                    hf.set(Vars.ui.hudfrag.blockfrag, beast);
                    Log.info("[drv] beastname 已把原版悬浮信息栏的 hover 设成巨兽");
                }
            }catch(Throwable t2){ Log.err("[drv] beastname 设置原版 hover 失败", t2); }
            Core.camera.position.set(beast.x, beast.y);
            installCameraLock();
        }catch(Throwable t){ Log.err("[drv] beastInspect failed", t); }
    }

    static void collectLabels(arc.scene.Element e, StringBuilder sb){
        if(e == null) return;
        if(e instanceof arc.scene.ui.Label l) sb.append('[').append(l.getText()).append("] ");
        if(e instanceof arc.scene.Group g)
            for(arc.scene.Element c : g.getChildren()) collectLabels(c, sb);
    }

    /** 打开 mi2 的"替换悬浮信息栏"，并把它记录的悬浮单位也设成这只巨兽，再截一张图。 */
    static void beastMi2(){
        try{
            var mod = Vars.mods.getMod("mi2-utilities-java");
            if(mod == null || mod.main == null){ Log.info("[drv] beastname 这个数据目录没装 mi2，跳过 mi2 那一张"); return; }
            ClassLoader l2 = mod.main.getClass().getClassLoader();
            Core.settings.put("MI2UI.replaceTopTable", true);
            Class.forName("mi2u.ModifyFuncs", true, l2).getMethod("betterTopTable").invoke(null);
            Class<?> htt = Class.forName("mi2u.ui.HoverTopTable", true, l2);
            Object info = htt.getField("hoverInfo").get(null);
            htt.getMethod("setHovered", Object.class).invoke(info, megaUnit);
            Log.info("[drv] beastname mi2 悬浮信息栏已指向巨兽（replaceTopTable=@）",
                Core.settings.getBool("MI2UI.replaceTopTable", false));
        }catch(Throwable t){ Log.err("[drv] beastMi2 failed", t); }
    }

    static Building place(Block b, int x, int y){
        mindustry.world.Build.beginPlace(null, b, Team.sharded, x, y, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(x, y), b, null, (byte)0, Team.sharded, null);
        return Vars.world.build(x, y);
    }

    /** 和 headless 测试同一套坐标约定：(x,y) 是方块左下角，锚点 = 左下角 + (size-1)/2。 */
    static Building placeBL(Block b, int x, int y){
        return placeBL(b, x, y, Team.sharded);
    }

    static Building placeBL(Block b, int x, int y, Team team){
        int size = Math.max(b.size, 1);
        int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
        mindustry.world.Build.beginPlace(null, b, team, ax, ay, 0, null);
        mindustry.world.blocks.ConstructBlock.constructed(Vars.world.tile(ax, ay), b, null, (byte)0, team, null);
        return Vars.world.build(ax, ay);
    }

    // ---------------- 组合节点/连接器的"共享哪些部分"配置面板 ----------------
    static Building shareA, shareB, shareNode, shareConn;

    static void setupShareScene(){
        try{
            hideDialogs();
            var map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
            Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
            Vars.state.rules.canGameOver = false;
            Vars.state.rules.waves = false;
            Vars.logic.play();
            if(!Vars.state.isPlaying()) Vars.state.set(mindustry.core.GameState.State.playing);
            for(int y=40;y<120;y++) for(int x=30;x<200;x++){ Tile t=Vars.world.tile(x,y); if(t!=null && t.block()!=Blocks.air) t.setBlock(Blocks.air); }

            Block press = null, connB = null, nodeB = null;
            for(Block b : Vars.content.blocks()){
                if(press == null && b.getClass().getName().equals("combine.production.CombinedCrafter") && b.size == 2) press = b;
                if(connB == null && b.getClass().getName().equals("combine.net.ComboConnector")) connB = b;
                if(nodeB == null && b.getClass().getName().equals("combine.net.ComboNode")) nodeB = b;
            }
            if(press == null || connB == null || nodeB == null){ Log.err("[drv] share 场景缺方块 press=@ conn=@ node=@", press, connB, nodeB); return; }

            // A(60,60) 2x2 + 连串连接器 + B(65,60)，旁边再放一个组合节点（激光连到 A/B）
            shareA = placeBL(press, 60, 60);
            shareConn = placeBL(connB, 62, 60);
            placeBL(connB, 63, 60);
            placeBL(connB, 64, 60);
            shareB = placeBL(press, 65, 60);
            shareNode = placeBL(nodeB, 63, 63);
            // 节点激光连上 A、B（等价于玩家点两下）
            try{
                java.lang.reflect.Method tap = shareNode.getClass().getMethod("onConfigureBuildTapped", Building.class);
                tap.setAccessible(true);
                tap.invoke(shareNode, shareA);
                tap.invoke(shareNode, shareB);
            }catch(Throwable t){ Log.err("[drv] 节点连线失败", t); }
            try{ shareA.items.add(Items.copper, 40); }catch(Throwable ignored){}
            Core.camera.position.set((shareA.x + shareB.x) / 2f, shareA.y + 6f);
            Log.info("[drv] share 场景: A=@ B=@ node=@(links=@) conn=@", shareA, shareB, shareNode, field(shareNode, "links"), shareConn);
        }catch(Throwable t){ Log.err("[drv] setupShareScene failed", t); }
    }

    static void shareStepNode(){
        try{
            if(shareNode == null){ Log.err("[drv] shareNode 为空"); return; }
            showShareConfig(shareNode, "组合节点：共享哪些部分");
            Log.info("[drv] 节点配置面板已弹出 mask=@ configFragmentShown=@",
                field(shareNode, "shareMask"), Vars.control.input.config.isShown());
        }catch(Throwable t){ Log.err("[drv] 打开节点配置失败", t); }
    }

    static void shareOpenNode(){
        shareStepNode();
    }

    static void shareOpenConn(){
        shareStepConn();
    }

    static void shareStepToggle(){
        try{
            // 取消勾选"液体"（15 → 13）：勾选框应该跟着变（每帧跟当前配置对齐）
            shareNode.configure("comboshare:13");
            Log.info("[drv] 改配置后 mask=@", field(shareNode, "shareMask"));
        }catch(Throwable t){ Log.err("[drv] 改配置失败", t); }
    }

    static void shareStepConn(){
        try{
            hideDialogs();
            showShareConfig(shareConn, "组合连接器：共享哪些部分");
            Log.info("[drv] 连接器配置面板已弹出 mask=@", field(shareConn, "shareMask"));
        }catch(Throwable t){ Log.err("[drv] 打开连接器配置失败", t); }
    }

    /**
     * 把方块的配置表格（原版 config fragment 里那张表，也就是 {@code buildConfiguration}）
     * 放到一个对话框里渲染 + 截图。离屏客户端里没有真实鼠标事件，走不了"点方块弹配置"那条路，
     * 但表格内容/勾选框和真机是同一份代码。
     */
    static void showShareConfig(Building b, String title){
        try{
            arc.scene.ui.layout.Table table = new arc.scene.ui.layout.Table();
            java.lang.reflect.Method m = b.getClass().getMethod("buildConfiguration", arc.scene.ui.layout.Table.class);
            m.setAccessible(true);
            m.invoke(b, table);
            mindustry.ui.dialogs.BaseDialog d = new mindustry.ui.dialogs.BaseDialog(title);
            d.cont.add(table).pad(12f);
            d.show();
            Log.info("[drv] 配置表格子元素=@", table.getChildren().size);
        }catch(Throwable t){ Log.err("[drv] showShareConfig failed", t); }
    }

    /** 往共享池里加物品/液体（CoopPanel 应该立刻显示出来）。 */
    static void addStuff(){
        try{
            addPool(Items.silicon, 25, 0f);
            addPool(null, 0, 30f);   // 液体
            Log.info("[drv] added: copper=@ silicon=@ liquids=@",
                b1.items.get(Items.copper), b1.items.get(Items.silicon), totalLiquid(b1));
            framesAtAdd = frames;
        }catch(Throwable t){ Log.err("[drv] addStuff failed", t); }
    }

    static void addPool(Item item, int amount, float liquidAmount){
        if(b1 == null) return;
        if(item != null && b1.items != null) b1.items.add(item, amount);
        if(liquidAmount > 0f && b1.liquids != null){
            Liquid liq = null;
            for(Liquid l : Vars.content.liquids()) if(l.name.equals("water")) liq = l;
            if(liq != null) b1.liquids.add(liq, liquidAmount);
        }
    }

    static float totalLiquid(Building b){
        if(b.liquids == null) return 0f;
        float s = 0f;
        for(Liquid l : Vars.content.liquids()) s += b.liquids.get(l);
        return s;
    }

    // ==================== mode=inputguard（arc 输入 NPE 兜底） ====================

    static arc.scene.ui.layout.Table guardOuter;
    static arc.scene.ui.TextButton guardNormalBtn;
    static boolean guardNormalClicked = false;
    /** 置真后：按钮上的监听器会把祖先（guardOuter）从场景里摘掉 —— 复现那条 arc NPE 的现场。 */
    static boolean guardDetach = false;

    /** 在场景最上面摆两样东西：一个普通按钮（验证输入没被代理弄坏）+ 一对"祖先会被摘掉"的元素。 */
    static void inputGuardSetup(){
        try{
            float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();

            guardOuter = new arc.scene.ui.layout.Table();
            guardOuter.name = "drv-guard-outer";
            guardOuter.setSize(w * 0.9f, h * 0.5f);
            guardOuter.setPosition(w * 0.05f, h * 0.05f);
            guardOuter.background(mindustry.gen.Tex.pane);
            // 祖先自己的监听器：命中就返回 true（arc 会为它 getScene().addTouchFocus）
            guardOuter.addListener(new arc.scene.event.InputListener(){
                @Override public boolean touchDown(arc.scene.event.InputEvent event, float x, float y, int pointer, arc.input.KeyCode button){
                    return true;
                }
            });

            // ① 普通按钮：点它应该照常触发 clicked（代理没把输入弄坏）；guardDetach=true 时
            //    它还会把祖先摘掉，用来复现"派发中祖先被移除 → arc 读 getScene() 为 null"的崩溃现场。
            guardNormalBtn = new arc.scene.ui.TextButton("点我");
            guardNormalBtn.setSize(200f, 60f);
            guardNormalBtn.addListener(new arc.scene.event.ClickListener(){
                @Override public void clicked(arc.scene.event.InputEvent event, float x, float y){
                    guardNormalClicked = true;
                }
            });
            guardNormalBtn.addListener(new arc.scene.event.InputListener(){
                @Override public boolean touchDown(arc.scene.event.InputEvent event, float x, float y, int pointer, arc.input.KeyCode button){
                    if(guardDetach) guardOuter.remove();
                    return true;
                }
            });
            guardOuter.add(guardNormalBtn).size(200f, 60f).pad(10f);

            Core.scene.root.addChild(guardOuter);
            Log.info("[drv] inputguard: 场景已摆好 outer=@x@ 按钮=@x@",
                guardOuter.getWidth(), guardOuter.getHeight(), guardNormalBtn.getWidth(), guardNormalBtn.getHeight());
        }catch(Throwable t){
            Log.err("[drv] inputguard 布置失败", t);
        }
    }

    /** 按 arc InputMultiplexer 的语义派发（处理了就停）：代理就在这条链里。 */
    static boolean guardDispatchDown(int x, int y){
        for(arc.input.InputProcessor p : Core.input.getInputProcessors()){
            if(p.touchDown(x, y, 0, arc.input.KeyCode.mouseLeft)){
                Log.info("[drv] inputguard: touchDown(@,@) 被 @ 消费", x, y, p.getClass().getName());
                return true;
            }
        }
        Log.info("[drv] inputguard: touchDown(@,@) 没有任何处理器消费", x, y);
        return false;
    }

    static boolean guardDispatchUp(int x, int y){
        for(arc.input.InputProcessor p : Core.input.getInputProcessors()){
            if(p.touchUp(x, y, 0, arc.input.KeyCode.mouseLeft)){
                Log.info("[drv] inputguard: touchUp(@,@) 被 @ 消费", x, y, p.getClass().getName());
                return true;
            }
        }
        Log.info("[drv] inputguard: touchUp(@,@) 没有任何处理器消费", x, y);
        return false;
    }

    /** 在整个屏幕里扫出一个"scene.hit() 真的命中 want（或它的子孙）"的屏幕点。 */
    static int[] findScreenPoint(arc.scene.Element want){
        try{
            for(int sy = 0; sy < Core.graphics.getHeight(); sy += 8){
                for(int sx = 0; sx < Core.graphics.getWidth(); sx += 8){
                    var stage = Core.scene.screenToStageCoordinates(new arc.math.geom.Vec2(sx, sy));
                    arc.scene.Element hit = Core.scene.hit(stage.x, stage.y, true);
                    for(arc.scene.Element e = hit; e != null; e = e.parent)
                        if(e == want) return new int[]{sx, sy};
                }
            }
        }catch(Throwable t){
            Log.err("[drv] inputguard: 扫描命中点失败", t);
        }
        return null;
    }

    static void inputGuardChecks(){
        try{
            // ① 代理装上了吗？Scene 是不是被派发两遍？
            int scenes = 0, guards = 0;
            for(arc.input.InputProcessor p : Core.input.getInputProcessors()){
                if(p == Core.scene) scenes++;
                if(p.getClass().getName().equals("combine.util.ComboInputGuard$Guard")) guards++;
            }
            Log.info("[drv] inputguard: 输入链里 Scene=@ 代理=@ 处理器数=@", scenes, guards, Core.input.getInputProcessors().size);
            for(int i = 0; i < Core.input.getInputProcessors().size; i++){
                Log.info("[drv] inputguard: 处理器[@] = @", i, Core.input.getInputProcessors().get(i).getClass().getName());
            }
            Log.info("[drv] " + (guards == 1 && scenes == 0 ? "PASS" : "FAIL")
                + " 输入代理已就位、Scene 不再重复派发（Scene=@ 代理=@）", scenes, guards);

            // ② 普通点击照旧生效（真·touchDown/touchUp 走完整输入链）
            guardNormalClicked = false;
            guardOuter.toFront();
            // 顶上有原版菜单/HUD 层，坐标换算又受 HDPI 影响 —— 直接在整个屏幕上**扫**一个
            // 真正命中我们元素的屏幕点（比猜坐标可靠），再拿它做点击/派发。
            int[] bp = findScreenPoint(guardNormalBtn);
            Log.info("[drv] inputguard: 扫描命中点 按钮=@", bp == null ? "null" : (bp[0] + "," + bp[1]));
            if(bp == null){
                Log.err("[drv] FAIL 按钮在屏幕上根本没被 hit() 命中（被菜单/HUD 压住或没布局）");
                return;
            }
            guardDispatchDown(bp[0], bp[1]);
            guardDispatchUp(bp[0], bp[1]);
            Log.info("[drv] " + (guardNormalClicked ? "PASS" : "FAIL")
                + " 普通点击照旧生效（屏幕 @,@ clicked=@）", bp[0], bp[1], guardNormalClicked);

            // ③ 复现"祖先被摘掉"的现场（同一个按钮，只是这次它顺手把祖先 guardOuter 摘掉）：
            //    应当被代理吞掉、不崩，且这一下被消费。
            guardOuter.toFront();
            guardDetach = true;
            boolean consumed = false, crashed = false;
            try{
                consumed = guardDispatchDown(bp[0], bp[1]);
            }catch(Throwable t){
                crashed = true;
                Log.err("[drv] FAIL 复现现场时崩了（兜底没生效）", t);
            }
            guardDetach = false;
            Log.info("[drv] " + (!crashed && consumed ? "PASS" : "FAIL")
                + " 祖先被摘掉的 NPE 被吞掉且这一下被消费（crashed=@ consumed=@）", crashed, consumed);
        }catch(Throwable t){
            Log.err("[drv] inputguard 检查失败", t);
        }
    }
}
