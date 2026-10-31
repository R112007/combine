package combine.dbg;

import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.Logic;
import mindustry.core.NetClient;
import mindustry.core.NetServer;
import mindustry.core.Platform;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.maps.Map;
import mindustry.mod.Mod;
import mindustry.net.Net;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * 只装 combine 时，所有可组合家族都必须落到一条有效路径：
 * 要么被替换成 CombinedXxx，要么由 CoopCombo 接管。
 */
public class SingleModCoverageTest implements arc.ApplicationListener {
  static int pass, fail;
  static String dataDir = "";

  public static void main(String[] args) {
    dataDir = args.length == 0 ? "data" : args[0];
    Vars.platform = new Platform() {
    };
    Vars.net = new Net(Vars.platform.getNet());
    Log.logger = (level, text) -> {
      if (level.ordinal() >= arc.util.Log.LogLevel.warn.ordinal())
        System.out.println("[L] " + text);
    };
    new HeadlessApplication(new SingleModCoverageTest(), t -> t.printStackTrace());
  }

  static void check(String name, boolean ok) {
    System.out.println("[SMC] " + (ok ? "PASS " : "FAIL ") + name);
    if (ok)
      pass++;
    else
      fail++;
  }

  static Building place(Block block, int x, int y, Team team) {
    int size = Math.max(block.size, 1);
    int ax = x + (size - 1) / 2, ay = y + (size - 1) / 2;
    mindustry.world.Build.beginPlace(null, block, team, ax, ay, 0, null);
    mindustry.world.blocks.ConstructBlock.constructed(
        Vars.world.tile(ax, ay), block, null, (byte) 0, team, null);
    return Vars.world.build(ax, ay);
  }

  static void run(int frames) {
    for (int i = 0; i < frames; i++) {
      arc.util.Time.delta = 1f;
      Vars.logic.update();
    }
  }

  @Override
  public void init() {
    try {
      Core.settings.setDataDirectory(Core.files.local(dataDir));
      Vars.loadLocales = false;
      Vars.loadSettings();
      Vars.headless = true;
      Vars.init();
      mindustry.core.UI.loadColors();
      Fonts.loadContentIconsHeadless();
      Vars.content.createBaseContent();
      Vars.mods.loadScripts();
      Vars.content.createModContent();
      Vars.content.init();
      Vars.mods.eachClass(Mod::init);
      if (Vars.logic == null)
        Vars.logic = new Logic();
      if (Vars.netServer == null)
        Vars.netServer = new NetServer();
      if (Vars.netClient == null)
        Vars.netClient = new NetClient();

      ClassLoader loader = Vars.mods.getMod("combine").main.getClass().getClassLoader();
      Class<?> coop = Class.forName("combine.coop.CoopCombo", true, loader);
      var eligible = coop.getMethod("eligibleByClass", Block.class);
      var markDirty = coop.getMethod("markDirty");
      var coopGroup = coop.getMethod("coopGroup", Building.class);

      Block sublimate = Vars.content.block("sublimate");
      check("连续液体炮台 sublimate 被替换为组合炮台（"
              + (sublimate == null ? "-" : sublimate.getClass().getName()) + "）",
          sublimate != null
              && sublimate.getClass().getName().equals(
                  "combine.turret.CombinedContinuousLiquidTurret"));

      for (String name : new String[] {
          "heat-redirector", "small-heat-redirector", "heat-router",
          "thermal-generator", "solar-panel", "solar-panel-large", "turbine-condenser"
      }) {
        Block block = Vars.content.block(name);
        boolean ok = block != null && (Boolean) eligible.invoke(null, block);
        check("原版残余类型由协作组合接管: " + name, ok);
      }

      Block conveyor = Vars.content.block("conveyor");
      check("运输方块仍不会被协作组合误接管",
          conveyor != null && !((Boolean) eligible.invoke(null, conveyor)));

      Map map = Vars.maps.all().find(m -> m.name().contains("Archipelago"));
      Vars.world.loadMap(map, map.applyRules(Gamemode.survival));
      Vars.state.rules.canGameOver = false;
      Vars.state.rules.waves = false;
      Vars.logic.play();
      run(10);
      for (int y = 40; y <= 90; y++)
        for (int x = 40; x <= 100; x++) {
          Tile tile = Vars.world.tile(x, y);
          if (tile != null && tile.block() != mindustry.content.Blocks.air)
            tile.setBlock(mindustry.content.Blocks.air);
        }
      run(3);

      Block heat = Vars.content.block("heat-redirector");
      int hsz = Math.max(heat.size, 1);
      Building h1 = place(heat, 60, 60, Team.sharded);
      Building h2 = place(heat, 60 + hsz, 60, Team.sharded);
      markDirty.invoke(null);
      run(20);
      Seq<?> heatGroup = (Seq<?>) coopGroup.invoke(null, h1);
      System.out.println("[SMC] heat size=" + hsz + " h1=" + (h1 == null ? "null" : h1.block.name)
          + " h2=" + (h2 == null ? "null" : h2.block.name) + " group=" + heatGroup.size);
      check("纯热方块真实成组（heat-redirector x" + heatGroup.size + "）",
          h1 != null && h2 != null && heatGroup.size == 2);

      Block thermal = Vars.content.block("thermal-generator");
      int tsz = Math.max(thermal.size, 1);
      Building t1 = place(thermal, 70, 60, Team.sharded);
      Building t2 = place(thermal, 70 + tsz, 60, Team.sharded);
      markDirty.invoke(null);
      run(20);
      Seq<?> thermalGroup = (Seq<?>) coopGroup.invoke(null, t1);
      check("纯电力方块真实成组（thermal-generator x" + thermalGroup.size + "）",
          t1 != null && t2 != null && thermalGroup.size == 2);

      System.out.println("[SMC] RESULT PASS=" + pass + " FAIL=" + fail);
      System.exit(fail == 0 ? 0 : 1);
    } catch (Throwable t) {
      t.printStackTrace();
      System.exit(2);
    }
  }
}
