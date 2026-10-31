package combine.util;
import arc.struct.ObjectSet;
import arc.util.Log;

/**
 * 组合建筑信息面板的异常保护。
 *
 * 信息面板（Building.display）是**每帧**由游戏调用的；只要里面抛一次异常，
 * Arc/Mindustry 会当场崩（"Mindustry has crashed"），而且这张面板只画了一半 ——
 * 半成品面板里可能留下没有挂到场景上的元素，之后鼠标点上去就会在输入分发里
 * 报 `Element.getScene() is null`（Arc 的 addTouchFocus），表现成另一个看着毫不相干的崩溃。
 *
 * 所以组合建筑自己往面板里加的东西，一律包一层：出错就记日志（同一处只记一次），
 * 绝不把异常抛回游戏。
 */
public class ComboUi {
  private static final ObjectSet<String> reported = new ObjectSet<>();

  /** 设置键：组合建筑 display() 里要不要画"详细信息"（构成 + 物品池 + 液体池 + 电力条）。 */
  public static final String DETAIL_KEY = "combine-display-detail";
  /** 设置键：点击组合体时要不要弹"悬浮信息面板"（CoopPanel）。 */
  public static final String PANEL_KEY = "combine-show-panel";
  /** 设置键：点击组合体时要不要弹"组合体共享面板"（ComboSharePanel：勾选物品/液体/电力/热量）。 */
  public static final String SHARE_KEY = "combine-show-share-panel";

  /**
   * 【用户 2026-10-11】提示文字按平台分：手机端只写"手指怎么点"、桌面端只写"鼠标/键盘怎么按"，
   * 别把两套操作混进同一句话（手机上看到"拖动左键 / 右键取消"没人看得懂）。
   */
  public static String hint(String desktop, String mobile) {
    return mindustry.Vars.mobile ? mobile : desktop;
  }

  /** 点击**自己队伍**的组合体时要不要弹"组合体共享"面板（设置里可关；默认开）。 */
  public static boolean sharePanel() {
    try {
      return arc.Core.settings == null || arc.Core.settings.getBool(SHARE_KEY, true);
    } catch (Throwable ignored) {
      return true;
    }
  }

  public static void setSharePanel(boolean value) {
    if (arc.Core.settings != null) {
      arc.Core.settings.put(SHARE_KEY, value);
      arc.Core.settings.saveValues();
    }
  }

  /**
   * display() 里要不要显示组合建筑的"详细信息"（构成 / 物品池 / 液体池 / 电力条）。
   *
   * <p>设置里关掉就退回**原版面板**（由各组合建筑的 display() 调 {@code super.display(table)}），
   * 用户口径："一个控制 display 是否显示详细信息，不显示详细信息就用 super.display(table)"。
   */
  public static boolean detail() {
    try {
      return arc.Core.settings == null || arc.Core.settings.getBool(DETAIL_KEY, true);
    } catch (Throwable ignored) {
      return true;
    }
  }

  /** 点击组合体时要不要弹那个悬浮信息面板（设置里可关）。 */
  public static boolean panel() {
    try {
      return arc.Core.settings == null || arc.Core.settings.getBool(PANEL_KEY, true);
    } catch (Throwable ignored) {
      return true;
    }
  }

  public static void setDetail(boolean value) {
    if (arc.Core.settings != null) {
      arc.Core.settings.put(DETAIL_KEY, value);
      arc.Core.settings.saveValues();
    }
  }

  public static void setPanel(boolean value) {
    if (arc.Core.settings != null) {
      arc.Core.settings.put(PANEL_KEY, value);
      arc.Core.settings.saveValues();
    }
  }

  /** 安全执行一段界面绘制代码；同 tag 只报告一次，避免每帧刷屏。 */
  public static void safe(String tag, Runnable runnable) {
    try {
      runnable.run();
    } catch (Throwable t) {
      if (reported.add(tag)) {
        Log.err("[combine] 组合建筑界面绘制出错(" + tag + ")，已跳过（不影响游戏运行）", t);
      }
    }
  }

  /**
   * 给组合面板画一条"电力"条：按**整组**耗电 × 电网状态显示；组里没人耗电就不画。
   *
   * 注意不要用原版 displayBars() —— 它会把原版注册的那些条一起带出来，其中"液体条"读的是
   * block.liquidCapacity（组合建筑为了不让管道算出负流量把它抬成了 9999 假容量），
   * 于是面板上会多出一条 水 160/9.99K 的假条，还和组合自己的液条重复。
   */
  public static void addPowerBar(arc.scene.ui.layout.Table table, mindustry.gen.Building self, float totalUsage) {
    if (table == null || self == null || self.power == null || totalUsage <= 0.001f) return;
    final float usage = totalUsage;
    final mindustry.world.modules.PowerModule pw = self.power;
    table.add(new mindustry.ui.Bar(
        () -> "电力 " + arc.util.Strings.fixed(usage * pw.status * 60f, 1) + " ⚡/s",
        () -> mindustry.graphics.Pal.power,
        () -> pw.status)).width(COMPOSITION_WIDTH).height(18f).pad(4).left();
    table.row();
  }

  /** "组合体构成"那一行文字的宽度（信息面板固定 260 宽，留点内边距）。 */
  public static final float COMPOSITION_WIDTH = 236f;

  /**
   * 信息面板上的"组合体构成"：**一行文字、超宽自动换行**。
   *
   * <p>用户要求："既然有悬浮面板了，组合建筑 display() 里就别再列物品/液体/电力了，
   * 只显示组合体构成，而且会换行"。以前各组合类都是一台一行地列构成，组合体一大就把
   * 右侧信息面板顶穿（用户早先也报过"组合的东西过多时显示面板非常大"）。现在统一成
   * 一行 `构成: duo x4  scatter x3  …`，按面板宽度换行后高度有限。
   *
   * <p>成员口径和悬浮面板一致：{@link combine.net.ComboNet#displayMembers}（连接器/节点
   * 接起来的整张网络都算"一个组合体"）。{@code localCount} 传本地组合体的成员数。
   */
  public static void addComposition(arc.scene.ui.layout.Table table, mindustry.gen.Building self, int localCount) {
    if (table == null || self == null) return;
    arc.struct.ObjectIntMap<mindustry.world.Block> counts = new arc.struct.ObjectIntMap<>();
    try {
      for (mindustry.gen.Building m : combine.net.ComboNet.displayMembers(self, localCount)) {
        if (m != null && m.isValid() && m.block != null) counts.increment(m.block, 1);
      }
    } catch (Throwable t) {
      // 统计失败也别让面板空着/崩：退回"只有自己"
      counts.clear();
      counts.increment(self.block, 1);
    }
    arc.struct.Seq<mindustry.world.Block> blocks = new arc.struct.Seq<>();
    for (mindustry.world.Block b : counts.keys()) blocks.add(b);
    blocks.sort(b -> b.id);
    StringBuilder sb = new StringBuilder();
    for (mindustry.world.Block b : blocks) {
      if (sb.length() > 0) sb.append("  ");
      String name = b.localizedName == null ? b.name : String.valueOf(b.localizedName);
      sb.append(name).append(" x").append(counts.get(b, 0));
    }
    if (sb.length() == 0) sb.append("无");
    table.add("[lightgray]构成: " + sb + "[]").left().width(COMPOSITION_WIDTH).wrap();
    table.row();
    // 用户要求把 display() 的"详细信息"加回来：整组的物品池 / 液体池 / 电力条。
    // 只在设置里开着"显示详细信息"时画（关掉走的是原版 super.display）。
    if (detail())
      addPoolBars(table, self);
  }

  /**
   * 整组的物品池 / 液体池 / 电力条（display() 的"详细信息"）。
   *
   * <p>用户报过"display 里只留构成"是把物品/液体/电力删掉了（commit ae21e33），这里加回来。
   * 分子分母都用 {@link combine.net.ComboNet} 的整网口径（和悬浮面板同源），
   * 免得出现"1482273/60"这种池子在别处、分母还是单台的数字。
   */
  public static void addPoolBars(arc.scene.ui.layout.Table table, mindustry.gen.Building self) {
    if (table == null || self == null || self.block == null)
      return;
    try {
      int itemCap = Math.max(combine.net.ComboNet.panelItemCap(self), 1);
      mindustry.world.modules.ItemModule items = combine.net.ComboNet.panelItemPool(self);
      if (items != null) {
        for (mindustry.type.Item item : mindustry.Vars.content.items()) {
          int amount = items.get(item);
          if (amount <= 0)
            continue;
          final int t = amount, c = itemCap;
          table.add(new mindustry.ui.Bar(
              () -> item.localizedName + ": " + t + "/" + c,
              () -> item.color,
              () -> (float) t / c)).width(COMPOSITION_WIDTH).height(18f).pad(4).left();
          table.row();
        }
      }

      float liquidCap = Math.max(combine.net.ComboNet.panelLiquidCap(self), 1f);
      mindustry.world.modules.LiquidModule liquids = combine.net.ComboNet.panelLiquidPool(self);
      if (liquids != null) {
        for (mindustry.type.Liquid liquid : mindustry.Vars.content.liquids()) {
          float amount = liquids.get(liquid);
          if (amount <= 0.001f)
            continue;
          final float t = amount, c = liquidCap;
          table.add(new mindustry.ui.Bar(
              () -> liquid.localizedName + ": " + arc.util.Strings.fixed(t, 1) + "/" + arc.util.Strings.fixed(c, 1),
              () -> liquid.barColor != null ? liquid.barColor : liquid.color,
              () -> t / c)).width(COMPOSITION_WIDTH).height(18f).pad(4).left();
          table.row();
        }
      }

      // 电力条：整网耗电（组里没人耗电就不画）
      float totalPower = 0f;
      for (mindustry.gen.Building m : combine.net.ComboNet.displayMembers(self, 1)) {
        if (m != null && m.isValid() && m.block != null && m.block.consPower != null)
          totalPower += m.block.consPower.usage;
      }
      addPowerBar(table, self, totalPower);
    } catch (Throwable t) {
      if (reported.add("pools")) {
        Log.err("[combine] 组合面板画物品/液体/电力条失败（已跳过）", t);
      }
    }
  }
}
