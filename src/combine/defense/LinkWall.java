package combine.defense;
import combine.net.ComboConnector.ComboConnectorBuild;
import combine.net.ComboConnector;
import combine.net.ComboNode.ComboNodeBuild;
import combine.net.ComboNode;
import combine.coop.CoopCombo;
import combine.util.ComboUi;
import combine.util.IComboGrouped;
import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Point2;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.Lightning;
import mindustry.gen.Bullet;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Edges;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Wall;
import arc.graphics.Color;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.graphics.Drawf;
import mindustry.ui.Bar;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatUnit;

public class LinkWall extends Wall {

  public enum Mode {
    wall,
    door,
    /** 自动门（原版 AutoDoor/blast-door）：靠"附近有没有地面单位"自己开关，点不动。 */
    autodoor,
    shield
  }

  public Mode mode = Mode.wall;
  public TextureRegion openRegion;

  // ===== 自动门（autodoor 模式）参数：与原版 AutoDoor 字段**同名**，copyFields 会自动拷过来 =====
  public float checkInterval = 20f;
  public float triggerMargin = 12f;
  public mindustry.entities.Effect openfx = mindustry.content.Fx.dooropen;
  public mindustry.entities.Effect closefx = mindustry.content.Fx.doorclose;
  public arc.audio.Sound doorSound = mindustry.gen.Sounds.door;
  /** 自动门检查节拍用的计时器槽（和原版 AutoDoor 一样取一个 timers 槽）。 */
  public final int timerToggle = timers++;

  /** 自动门每帧的临时容器（和原版 AutoDoor 一样是静态复用，避免每帧 new）。 */
  static final arc.math.geom.Rect autoRect = new arc.math.geom.Rect();
  static final Seq<mindustry.gen.Unit> autoUnits = new Seq<>();
  /** 原版 AutoDoor 的判据：地面单位、且不是会"踩台阶"的腿式单位。 */
  static final arc.func.Boolf<mindustry.gen.Unit> autoGroundCheck =
      u -> u.isGrounded() && !u.type.allowLegStep;

  public LinkWall(String name) {
    super(name);
    this.update = true;
    // 注意：copyFields 会用原版方块的 configurations 覆盖本表（其闭包指向原版
    // Build 类型，会 ClassCastException），故在 init() 里清空重注册
    buildType = LinkWallBuild::new;
  }

  /** 本帧待重算分组的墙（见 {@link LinkWallBuild#markSelfDirty()}）。 */
  private static final ObjectSet<LinkWallBuild> pendingRebuild = new ObjectSet<>();

  /** 在 Main.init() 里注册一次：每帧最多重算一遍（跑在建筑 update 之前）。 */
  public static void register() {
    arc.Events.run(mindustry.game.EventType.Trigger.update, LinkWall::flushPending);
  }

  private static void flushPending() {
    if (pendingRebuild.isEmpty())
      return;
    Seq<LinkWallBuild> list = new Seq<>();
    for (LinkWallBuild w : pendingRebuild)
      list.add(w);
    pendingRebuild.clear();
    for (LinkWallBuild w : list) {
      if (w == null || w.dead() || !w.linksDirty)
        continue; // 同一组里已经有人重建过 → 这一格的分组字段已经被写好了
      try {
        w.rebuildLinks();
      } catch (Throwable ignored) {
        // 分组重算失败不该影响游戏，下一帧还有机会
      }
    }
  }

  @Override
  public void load() {
    super.load();
    // FIX：openRegion 之前从未加载，门开着时永远走不到贴图分支
    openRegion = Core.atlas.find(name + "-open");
    // FIX[shield]: 相位盾自发光贴图（克隆体名字可能取不到，processWalls 里还会按原名再取一次兜底）
    glowRegion = Core.atlas.find(name + "-glow");
  }

  // ===== 相位盾（shield 模式）配置字段：与原版 ShieldWall 字段同名，copyFields 自动拷贝 =====
  public float shieldHealth = 900f;
  public float breakCooldown = 600f;
  public float regenSpeed = 2f;
  public Color glowColor = Color.valueOf("ff7531").a(0.5f);
  public float glowMag = 0.6f;
  public float glowScl = 8f;
  public TextureRegion glowRegion;

  @Override
  public void setStats() {
    super.setStats();
    if (mode == Mode.shield) {
      stats.add(Stat.shieldHealth, shieldHealth);
      stats.add(Stat.cooldownTime, breakCooldown / 60f, StatUnit.seconds);
    }
  }

  @Override
  public void setBars() {
    barMap.clear();
    super.setBars();
    if (mode == Mode.shield) {
      // 注意：LinkWallBuild 继承 Building，addBar 的实体形参是 WallBuild 类型，需显式转型。
      // 用 (String, Color, Floatp) 构造器，兼容性最好。
      addBar("charge", entity -> new Bar(
          "stat.shieldhealth",
          mindustry.graphics.Pal.shield,
          () -> {
            LinkWallBuild self = (LinkWallBuild) entity;
            return shieldHealth <= 0f ? 0f : self.shield / shieldHealth;
          }));
    }
  }

  @Override
  public void init() {
    super.init();
    update = true;
    if (mode == Mode.door || mode == Mode.autodoor) {
      solid = false;
      solidifes = true;
      // 自动门是无接触开关（玩家点它不做任何事），只有普通门才靠点击开/关。
      consumesTap = mode == Mode.door;
    } else {
      solid = true;
      solidifes = false;
      consumesTap = false;
    }
    if (mode == Mode.shield) {
      // 相位盾需要动态绘制（盾半径/受击闪白/自发光）
      drawDynamic = true;
    }
    // FIX：copyFields 已把原版 Door/Wall 的 configurations 拷进来（闭包类型不对），
    // 这里清空并按 LinkWallBuild 重新注册
    configurations.clear();
    config(Boolean.class, (LinkWallBuild b, Boolean open) -> {
      b.setOpen(open);
      // 自动门每格按自己附近的单位独立开关，不互相带动（和原版 AutoDoor 一致）。
      if (((LinkWall) b.block).mode == Mode.autodoor)
        return;
      Seq<LinkWallBuild> members = new Seq<>(b.group());
      for (LinkWallBuild other : members)
        if (other != b && other.isDoor())
          other.setOpen(open);
    });
  }

  public class LinkWallBuild extends Building implements IComboGrouped, combine.saves.ComboSaved {

    public Seq<LinkWallBuild> links = new Seq<>();
    public LinkWallBuild linkLeader;
    public boolean linksDirty = true;
    public int seqSize = 1;

    /**
     * 本格的分组需要重算 —— **不立刻做**，攒到本帧收口跑一次。
     *
     * 【性能/用户报"多线程建造后帧率下降十分明显"】一次放置会连着触发
     * placed() 自己 + 四周邻居的 onProximityUpdate()，原先每一遍都同步跑一次
     * {@link #rebuildLinks()}（整组 BFS + 节点扫描 + 血量摊平）：大墙组上放一格
     * 就是 5 遍 O(组员数)，多线程建造刷一片墙时单帧几毫秒到几十毫秒。
     * 现在只标脏，交给每帧一次的 {@link LinkWall#flushPending()}；
     * 整组只要有一个成员重建过，其余成员在这一帧里就被顺手清干净了（rebuildLinks
     * 会把 links/linkLeader 写回每个成员），所以一帧最多跑一遍。
     */
    public void markSelfDirty() {
      linksDirty = true;
      pendingRebuild.add(this);
    }
    /** 上一次"挨着/连着的组合连接器、节点的links"指纹：变了就重算分组（跨距离连线也要算同组）。 */
    public int lastLinkerHash = Integer.MIN_VALUE;
    /** 上一次"是否允许组合"的判定结果：队伍里玩家有变动时自动重算一次。 */
    public boolean groupAllowedLast = true;
    public boolean open = false;

    // ===== 相位盾（shield 模式）运行时状态 =====
    public float shield;
    public float shieldRadius;
    public float breakTimer;
    // LinkWallBuild 直接继承 Building（不是 WallBuild），没有继承的受击闪白字段，自管
    public float hit;

    public LinkWallBuild leader() {
      if (linkLeader != null && !linkLeader.isValid())
        linkLeader = null;
      return linkLeader == null ? this : linkLeader;
    }

    public boolean isLeader() {
      return leader() == this;
    }

    public Seq<LinkWallBuild> group() {
      LinkWallBuild l = leader();
      if (l.links == null)
        l.links = new Seq<>();
      return l.links;
    }

    public void markGroupDirty() {
      for (LinkWallBuild b : new Seq<>(group())) {
        if (!b.dead()) {
          b.linksDirty = true;
          pendingRebuild.add(b);
        }
      }
      linksDirty = true;
      pendingRebuild.add(this);
    }

    /**
     * 这面墙要不要参与"组合"（连成一组、共享一个血池）。
     *
     * 判据是"这个队伍里**有没有玩家**"，而不是队伍名、也不是 Team.isOnlyAI()：
     *   · Team.isOnlyAI() 只在"有波次/进攻模式/战役"里成立 —— 沙盒、自定义这些模式里
     *     敌方队伍会被算成"不是 AI"，照样组合（实测就是这样漏的）；
     *   · 按队伍名硬编码（只让 sharded 组合）又会破坏 PvP。
     * 所以：队伍里有玩家（PvP 两边、联机各方、玩家自己切到的队伍）→ 组合；
     * 纯 AI 势力的墙保持原版单格行为（血池不叠、不被"打一处整片掉"）。
     */
    public boolean groupAllowed() {
      if (team == null)
        return false;
      if (!team.data().players.isEmpty())
        return true;
      // 兜底：本地玩家所在的队伍（专用服务器上没有本地玩家，返回 null）
      return Vars.player != null && Vars.player.team() == team;
    }

    public void rebuildLinks() {
      Seq<LinkWallBuild> found = new Seq<>();
      // 不组合的队伍：自己就是一组（组员只有自己 → 伤害/治疗/门开关都退回原版单格行为）
      groupAllowedLast = groupAllowed();
      // 本墙的"本地连通块"（只沿墙邻格走，不过节点/连接器）：节点扫描用。
      // 不能用重建前的旧 group —— 断开节点后旧 group 里还列着对面半组，扫描会把
      // 已断开的节点再拉回来（断链拆组场景实测）。本地块是实时从地形算出来的，没这个问题。
      ObjectSet<LinkWallBuild> localGroup = new ObjectSet<>();
      {
        Queue<LinkWallBuild> lq = new Queue<>();
        lq.addLast(this);
        localGroup.add(this);
        while (!lq.isEmpty()) {
          LinkWallBuild cur = lq.removeFirst();
          for (Point2 edge : Edges.getEdges(cur.block.size)) {
            Tile t = Vars.world.tile(cur.tile.x + edge.x, cur.tile.y + edge.y);
            if (t == null || !(t.build instanceof LinkWallBuild w) || w.dead()
                || !(w.block instanceof LinkWall) || !localGroup.add(w))
              continue;
            lq.addLast(w);
          }
        }
      }
      if (!groupAllowedLast) {
        links = found;
        found.add(this);
        linkLeader = null;
        linksDirty = false;
        seqSize = 1;
        return;
      }
      ObjectSet<LinkWallBuild> visited = new ObjectSet<>();
      Queue<LinkWallBuild> queue = new Queue<>();
      // 连到本墙的组合连接器/节点（走过它们能到别的墙 —— 用户要的"接上节点/连接器就是完整组合"）
      ObjectSet<Building> seenLinkers = new ObjectSet<>();
      queue.addLast(this);
      visited.add(this);
      // 组合节点是**跨距离**的：节点不在墙的邻格里，墙自己发现不了"有人连我"。
      // 到节点登记表里找"links 里直接写了本地块任一成员"的节点，从它们出发走一遍。
      // 必须按"本地块"扫描而不是只扫自己：节点直链的可能是块里隔壁那格（直链 B，A/C 与 B
      // 相邻）——只扫"links 里有没有我"会让 A、C 失去跨节点合并的机会（墙链场景实测漏组）。
      // 完整性：被节点直链的墙在连线时自己那块也会被标脏重建，两侧各自都能把整链拉全。
      try {
        for (Building nb : CoopCombo.trackedNodesCopy()) {
          if (!(nb instanceof ComboNodeBuild node) || node.links == null)
            continue;
          for (LinkWallBuild member : localGroup) {
            if (member.dead())
              continue;
            if (node.links.contains(member.pos())) {
              walkLinkers(node, seenLinkers, visited, queue);
              break;
            }
          }
        }
      } catch (Throwable ignored) {
      }
      while (!queue.isEmpty()) {
        LinkWallBuild cur = queue.removeFirst();
        found.add(cur);
        for (Point2 edge : Edges.getEdges(cur.block.size)) {
          Tile t = Vars.world.tile(cur.tile.x + edge.x, cur.tile.y + edge.y);
          if (t == null || t.build == null)
            continue;
          if (t.build instanceof LinkWallBuild b) {
            if (!b.dead() && b.block instanceof LinkWall && visited.add(b))
              queue.addLast(b);
          } else if (t.build instanceof ComboConnectorBuild) {
            // 组合连接器：贴脸即连接，穿过它把邻墙并进这一组。
            // 组合节点不走这里 —— 节点的连接以 links 名单为准，贴墙的墙不被拉进
            // （否则墙一挨着节点就静默并入节点连的远处组，fuzz 实测多组员）。
            // 节点直链的墙由上面的节点扫描（links ∩ 本地块）发现。
            walkLinkers(t.build, seenLinkers, visited, queue);
          }
        }
      }
      LinkWallBuild newLeader = this;
      for (LinkWallBuild b : found)
        if (b.pos() < newLeader.pos())
          newLeader = b;

      for (LinkWallBuild b : found) {
        b.links = found;
        b.linkLeader = (b == newLeader) ? null : newLeader;
        b.linksDirty = false;
        b.seqSize = found.size;
      }
      // 成员增删后也摊平（新加进来的满血墙不会和受伤的同伴差一截）
      if (found.size > 1)
        redistributeHealth();
    }

    /** 被"组合节点/连接器"接起来的墙也要算同一组（于是血池、门联动这些整组行为跟着走）。 */
    private void walkLinkers(Building start, ObjectSet<Building> seenLinkers,
                             ObjectSet<LinkWallBuild> visited, Queue<LinkWallBuild> queue) {
      Queue<Building> linkers = new Queue<>();
      if (!seenLinkers.add(start))
        return;
      linkers.addLast(start);
      while (!linkers.isEmpty()) {
        Building cur = linkers.removeFirst();
        // 1) 这个连接件旁边贴着的墙 —— 只有连接器吃"贴脸入组"（它本来就是靠邻接工作的，
        //    没有连接表）。组合节点以自己的 links 名单为准：贴墙但没被连线的墙不入组，
        //    否则节点旁边没连的墙会被静默并进远处的组（fuzz 实测：组里多出邻格墙）。
        if (cur instanceof ComboConnectorBuild && cur.proximity != null) {
          for (Building nb : cur.proximity) {
            if (nb instanceof LinkWallBuild w && !w.dead() && w.block instanceof LinkWall && visited.add(w))
              queue.addLast(w);
          }
        }
        // 2) 顺着连接件之间的连线继续走
        if (cur instanceof ComboConnectorBuild c) {
          for (Building nb : c.proximity) {
            if (nb != null && nb.isValid() && isLinker(nb) && seenLinkers.add(nb))
              linkers.addLast(nb);
          }
        } else if (cur instanceof ComboNodeBuild n) {
          for (int i = 0; i < n.links.size; i++) {
            Building nb = Vars.world.build(n.links.get(i));
            if (nb == null || !nb.isValid())
              continue;
            if (isLinker(nb)) {
              if (seenLinkers.add(nb))
                linkers.addLast(nb);
            } else if (nb instanceof LinkWallBuild w && !w.dead() && w.block instanceof LinkWall && visited.add(w)) {
              queue.addLast(w);
            }
          }
        }
      }
    }

    private static boolean isLinker(Building b) {
      return b instanceof ComboConnectorBuild || b instanceof ComboNodeBuild;
    }

    /**
     * 挨着/连着的连接器、节点的指纹（节点把 links 也算进来）：
     * 节点那边"连上/断开"不会触发这面墙的 proximity 更新，只能自己每帧比一次指纹。
     */
    private int linkerHash() {
      int h = 5;
      for (Point2 edge : Edges.getEdges(block.size)) {
        Tile t = Vars.world.tile(tile.x + edge.x, tile.y + edge.y);
        if (t == null || t.build == null)
          continue;
        Building b = t.build;
        if (b instanceof ComboConnectorBuild) {
          h = h * 31 + b.pos();
        } else if (b instanceof ComboNodeBuild n) {
          h = h * 31 + b.pos();
          for (int i = 0; i < n.links.size; i++)
            h = h * 31 + n.links.get(i);
        }
      }
      return h;
    }

    @Override
    public void onProximityUpdate() {
      super.onProximityUpdate();
      if (!dead())
        markSelfDirty();
    }

    @Override
    public void placed() {
      super.placed();
      markSelfDirty();
    }

    @Override
    public void updateTile() {
      // 关掉相邻自动组合（总开关 / 只保留超级组合）/ 不是玩家队伍：照常跑自己的逻辑 ——
      // 分组被 ComboTeams.playerTeam 挡成"只有自己"时 = 原版单机行为（见 ComboReflect.markSolo）。
      boolean teamCombines = combine.util.ComboTeams.playerTeam(team);
      if (!teamCombines) combine.util.ComboReflect.markSolo(this);
      super.updateTile();
      // 连着的节点改了 links（连上/断开别的组合体）时，本墙要重算分组 ——
      // 节点的连线变化不会触发这面墙的 onProximityUpdate。
      // 非玩家队伍在"只玩家组合"模式下永远不会连节点，别为每面敌方墙每帧扫四邻。
      if (teamCombines && !dead() && !linksDirty && combine.net.ComboNet.hasTrackedLinkers()) {
        int h = linkerHash();
        if (h != lastLinkerHash) {
          lastLinkerHash = h;
          markGroupDirty();
        }
      }
      if (linksDirty && !dead()) {
        rebuildLinks();
      }
      // 队伍里玩家来了/走了（PvP 换边、联机加入等）→ 判定变了就重算一次：
      // 该组合的重新连起来，不该组合的拆回单格
      if (!linksDirty && !dead() && groupAllowedLast != groupAllowed())
        rebuildLinks();
      // FIX[shield]: 相位盾回复/破盾计时/盾半径动画（原版 ShieldWallBuild.updateTile 移植）
      if (isShield()) {
        if (hit > 0f)
          hit = Math.max(hit - Time.delta / 10f, 0f);
        if (breakTimer > 0f)
          breakTimer -= Time.delta;
        else
          shield = Mathf.clamp(shield + ((LinkWall) block).regenSpeed * edelta(), 0f, ((LinkWall) block).shieldHealth);
        shieldRadius = Mathf.lerpDelta(shieldRadius, shieldBroken() ? 0f : 1f, 0.12f);
      }
      // FIX[autodoor]: 原版 AutoDoor 的自动开关（服务端权威，客户端等 tileConfig 回来）。
      if (isAutoDoor() && !Vars.net.client()) {
        LinkWall w = (LinkWall) block;
        if (timer(w.timerToggle, w.checkInterval)) {
          autoUnits.clear();
          team.data().tree().intersect(
              autoRect.setSize(block.size * Vars.tilesize + w.triggerMargin * 2f).setCenter(x, y), autoUnits);
          boolean shouldOpen = autoUnits.contains(autoGroundCheck);
          if (open != shouldOpen)
            configureAny(shouldOpen); // 服务端本地生效 + 发 TileConfigCallPacket 给客户端
        }
      }
    }

    @Override
    public void onRemoved() {
      Seq<LinkWallBuild> members = new Seq<>(group());
      for (LinkWallBuild b : members) {
        if (b != this && !b.dead()) {
          b.linkLeader = null;
          b.linksDirty = true;
        }
      }
      links = new Seq<>();
      links.add(this);
      linkLeader = null;
      linksDirty = false;
      seqSize = 1;
      super.onRemoved();
    }

    public boolean isDoor() {
      return ((LinkWall) block).mode == Mode.door;
    }

    public boolean isAutoDoor() {
      return ((LinkWall) block).mode == Mode.autodoor;
    }

    /** 会体现"开着就不挡路"的门（普通门 + 自动门）。 */
    public boolean isOpenable() {
      return isDoor() || isAutoDoor();
    }

    public boolean isShield() {
      return ((LinkWall) block).mode == Mode.shield;
    }

    /** 盾是否处于破碎不可用状态（原版 ShieldWall.broken 语义） */
    public boolean shieldBroken() {
      return breakTimer > 0f || !canConsume();
    }

    public void setOpen(boolean open) {
      if (this.open == open)
        return;
      this.open = open;
      // 自动门开关时补上原版那点表现（特效/音效）。客户端由 tileConfig 包驱动这里。
      try {
        LinkWall w = (LinkWall) block;
        if (w.mode == Mode.autodoor && !Vars.headless && w.openfx != null && w.closefx != null && wasVisible) {
          (!open ? w.closefx : w.openfx).at(this, block.size);
          if (w.doorSound != null)
            w.doorSound.at(this);
        }
      } catch (Throwable ignored) {
      }
      recache();
      if (!Vars.world.isGenerating())
        Vars.pathfinder.updateTile(tile);
    }

    @Override
    public Boolean config() {
      return open;
    }

    @Override
    public void tapped() {
      if (!isDoor())
        return;
      LinkWallBuild lead = leader();
      if (lead == null)
        lead = this;
      Seq<LinkWallBuild> members = new Seq<>(lead.group());
      if (members.isEmpty())
        members.add(this);
      if (open && members.contains(b -> b.isDoor() && Units.anyEntities(b.tile)))
        return;
      if (!lead.timer(0, 60f))
        return;
      configure(!open);
    }

    @Override
    public boolean checkSolid() {
      return !(isOpenable() && open);
    }

    @Override
    public boolean collision(Bullet bullet) {
      if (isOpenable() && open)
        return false;
      // LinkWallBuild 直接继承 Building（不是 Wall.WallBuild），所以不能靠 super 拿到
      // 原版墙的受击逻辑。放电墙（surge wall）的闪电、反弹和受击闪白都在
      // WallBuild.collision 里，这里按原版逐行补回来；字段由 BlockCloner.copyFields
      // 从原版墙拷到 LinkWall（lightningChance/lightningDamage/...）。
      super.collision(bullet);
      LinkWall wall = (LinkWall) block;
      hit = 1f;

      if (wall.lightningChance > 0f && Mathf.chance(wall.lightningChance)) {
        Lightning.create(team, wall.lightningColor, wall.lightningDamage,
            x, y, bullet.rotation() + 180f, wall.lightningLength);
        if (wall.lightningSound != null)
          wall.lightningSound.at(tile, Mathf.random(0.9f, 1.1f));
      }

      if (wall.chanceDeflect > 0f) {
        // 原版：慢子弹不可反弹；反弹几率按子弹伤害衰减。
        if (bullet.vel.len() <= 0.1f || !bullet.type.reflectable)
          return true;
        if (!Mathf.chance(wall.chanceDeflect / bullet.damage()))
          return true;
        if (wall.deflectSound != null)
          wall.deflectSound.at(tile, Mathf.random(0.9f, 1.1f));

        bullet.trns(-bullet.vel.x, -bullet.vel.y);
        float penX = Math.abs(x - bullet.x), penY = Math.abs(y - bullet.y);
        if (penX > penY)
          bullet.vel.x *= -1;
        else
          bullet.vel.y *= -1;
        bullet.owner = this;
        bullet.team = team;
        bullet.time += 1f;
        return false;
      }
      return true;
    }

    @Override
    public void draw() {
      if (!isOpenable()) {
        super.draw();
        drawFlashFx();
        drawShieldFx();
        return;
      }
      TextureRegion openRegion = ((LinkWall) block).openRegion;
      if (open) {
        if (openRegion != null && openRegion.found())
          Draw.rect(openRegion, x, y);
        else
          Draw.rect(region, x, y + Vars.tilesize * size);
      } else {
        Draw.rect(region, x, y);
      }
    }

    /** 原版 WallBuild.draw 的受击闪白（组合墙直接继承 Building，需要自己补）。 */
    public void drawFlashFx() {
      LinkWall w = (LinkWall) block;
      if (!w.flashHit || hit < 0.001f)
        return;
      Draw.color(w.flashColor);
      Draw.alpha(hit * 0.5f);
      Draw.blend(arc.graphics.Blending.additive);
      Fill.rect(x, y, Vars.tilesize * block.size, Vars.tilesize * block.size);
      Draw.blend();
      Draw.reset();
      if (!Vars.state.isPaused())
        hit = Mathf.clamp(hit - Time.delta / 10f);
    }

    /** FIX[shield]: 相位盾覆盖层（原版 ShieldWallBuild.draw 移植） */
    public void drawShieldFx() {
      if (!isShield() || shieldRadius <= 0.001f)
        return;
      float radius = shieldRadius * 8f * block.size / 2f;
      Draw.z(125f);
      Draw.color(team.color, Color.white, Mathf.clamp(hit));
      if (Vars.renderer.animateShields) {
        Fill.square(x, y, radius);
      } else {
        Lines.stroke(1.5f);
        Draw.alpha(0.09f + Mathf.clamp(0.08f * hit));
        Fill.square(x, y, radius);
        Draw.alpha(1f);
        Lines.poly(x, y, 4, radius, 45f);
        Draw.reset();
      }
      Draw.reset();
      TextureRegion glow = ((LinkWall) block).glowRegion;
      if (glow != null && glow.found())
        Drawf.additive(glow, ((LinkWall) block).glowColor,
            (1f - ((LinkWall) block).glowMag + Mathf.absin(((LinkWall) block).glowScl, ((LinkWall) block).glowMag))
                * shieldRadius,
            x, y, 0f, 31f);
    }

    // 地图区里只写"原版那一块"的字节：墙什么都不加、门一个 open、相位墙一个 shield
    // （原版三者的 version 都是 0）。模组自己的字段（breakTimer）挪到自定义存档块
    // ComboSaveState —— 详见 ComboSaved。
    @Override
    public byte version() {
      return combine.saves.ComboSaveState.vanillaVersion(block);
    }

    @Override
    public void write(arc.util.io.Writes write) {
      super.write(write);
      LinkWall wall = (LinkWall) block;
      // 原版 AutoDoor 的地图区字节就是"一个 bool open"，门也是一样 —— autodoor 走同一条。
      if (wall.mode == Mode.door || wall.mode == Mode.autodoor)
        write.bool(open);
      else if (wall.mode == Mode.shield)
        write.f(shield);
    }

    @Override
    public void writeCombo(arc.util.io.Writes write) {
      write.f(breakTimer);
    }

    @Override
    public void read(arc.util.io.Reads read, byte revision) {
      super.read(read, revision);
      if (revision >= 2) {
        // 旧档（≤2.6）：模组把 open/shield/breakTimer 全写在了地图区里
        open = read.bool();
        shield = read.f();
        breakTimer = read.f();
        return;
      }

      LinkWall wall = (LinkWall) block;
      if (wall.mode == Mode.door || wall.mode == Mode.autodoor)
        open = read.bool();
      else if (wall.mode == Mode.shield)
        shield = read.f();
    }

    @Override
    public void readCombo(arc.util.io.Reads read, byte revision) {
      breakTimer = read.f();
    }

    /**
     * 修复：先按原版补"被治疗的那一格"（修复投影/修复塔只会照到射程内的墙，
     * 所以只有那几格会进这里），然后**立刻**把整组的血量按最大血量比重重新摊一遍。
     *
     * 这样组仍然是"一整面墙"的血池，但任何时候每格都是同一个血量百分比：
     *   · 治疗量只按射程内实际照到的墙计入池子，不会出现"一个修复源等于在修整面墙"的夸张效果；
     *   · 摊平之后不会再出现"某一格先见底 → 连带整组一起炸"。
     */
    @Override
    public void heal(float amount) {
      Seq<LinkWallBuild> members = liveMembers();
      if (members.size <= 1) {
        super.heal(amount);
        return;
      }
      // 【整组=一整面墙】治疗量加在"血池总量"上，再按各成员最大血量摊回去。
      //
      // 以前的写法是 super.heal(amount)（只治这一格，并且会被这格的 maxHealth 截断）
      // 之后再 redistributeHealth()：血池越接近满，那一次治疗里被截掉的部分就越多，
      // 于是每修一次只涨"当前缺口"的一小部分（几何收敛），最后卡在比满血低一点点的
      // 浮点定点上 —— 表现就是**怎么修都还是破损状态**（用户报的）。
      float pool = poolHealth(members);
      float totalMax = poolMax(members);
      distributeHealth(members, Math.min(pool + amount, totalMax), totalMax);
    }

    /** 组里还活着的成员（死掉的格子不该继续摊血，否则活着的永远修不满）。 */
    private Seq<LinkWallBuild> liveMembers() {
      Seq<LinkWallBuild> out = new Seq<>();
      for (LinkWallBuild b : group())
        if (b != null && !b.dead() && b.isValid())
          out.add(b);
      if (out.isEmpty())
        out.add(this);
      return out;
    }

    private static float poolHealth(Seq<LinkWallBuild> members) {
      float total = 0f;
      for (LinkWallBuild b : members)
        total += Math.max(b.health, 0f);
      return total;
    }

    private static float poolMax(Seq<LinkWallBuild> members) {
      float total = 0f;
      for (LinkWallBuild b : members)
        total += Math.max(b.maxHealth, 0f);
      return total;
    }

    /**
     * 把血池总量 total 按各成员最大血量的比重摊到每一格上。
     *
     * 最后一个成员拿"剩余量"（而不是各算各的浮点比例），并且整段用 double 算 ——
     * float 比例乘法每格都差一丁点，加起来正好让血池离满血差最后一丝，
     * `damaged()`（health &lt; maxHealth - 0.001）就一直为真：画面上永远有裂纹。
     */
    private static void distributeHealth(Seq<LinkWallBuild> members, float total, float totalMax) {
      if (members.size <= 0)
        return;
      if (totalMax <= 0.001f) {
        for (LinkWallBuild b : members) {
          b.health = Math.max(total, 0f);
          b.healthChanged();
        }
        return;
      }
      double assigned = 0.0;
      for (int i = 0; i < members.size; i++) {
        LinkWallBuild b = members.get(i);
        double v = i == members.size - 1
            ? (double) total - assigned
            : (double) total * ((double) b.maxHealth / (double) totalMax);
        assigned += v;
        b.health = (float) Math.max(0.0, Math.min(v, b.maxHealth));
        b.healthChanged();
      }
    }

    /** 把整组的血量按各成员最大血量比重重新分配（总量不变，各格回到同一百分比）。 */
    public void redistributeHealth() {
      Seq<LinkWallBuild> members = liveMembers();
      if (members.size <= 1)
        return;
      distributeHealth(members, poolHealth(members), poolMax(members));
    }

    @Override
    public void damage(float damage) {
      if (dead() || Vars.net.client())
        return;
      // FIX[shield]: 各自相位盾先吸收（原版 ShieldWallBuild.damage 移植），
      // 剩余伤害才进组血池；被打的成员盾先扛，保持"组=一整面墙"的语义
      if (isShield() && !shieldBroken() && shield > 0f) {
        float taken = Math.min(shield, damage);
        shield -= taken;
        hit = 1f;
        damage -= taken;
        if (shield <= 1e-5f && taken > 0f)
          breakTimer = ((LinkWall) block).breakCooldown;
        if (damage <= 0f)
          return;
      }
      // FIX[生命倍率]: 与原版 BuildingComp.damage 一致——组血池分摊前必须按
      // state.rules.blockHealth(team) 折算伤害，否则规则里的建筑生命倍率对组合墙失效
      float dm = Vars.state.rules.blockHealth(team);
      if (Mathf.zero(dm)) {
        damage = health + 1f;
      } else {
        damage /= dm;
      }
      Seq<LinkWallBuild> members = liveMembers();
      if (members.isEmpty())
        return;
      float totalMax = poolMax(members);
      if (totalMax <= 0.001f) {
        super.damage(damage);
        return;
      }
      // 伤害从"血池总量"里扣，再摊平：各格始终保持同一个百分比，
      // 不会出现"某一格血量先见底就被单独打掉"，也不会因为那一格见底把满血同伴一起带走。
      float pool = Math.max(poolHealth(members) - damage, 0f);
      distributeHealth(members, pool, totalMax);
      // 只有整组的血池被打空，这面组合墙才算倒
      if (pool <= 0.001f) {
        for (LinkWallBuild b : members)
          if (!b.dead())
            b.kill();
      }
    }

    @Override
    public void pickedUp() {
      super.pickedUp();
      shieldRadius = 0f;
    }

    @Override
    public void display(Table table) {
      // 面板每帧都会被调用：绝不能让异常抛回游戏（否则整个游戏崩，且面板只画一半）
      ComboUi.safe("linkwall:display", () -> displayInner(table));
    }

    void displayInner(Table table) {
      super.display(table);
      if (Vars.player.team() == this.team) {
        table.row();
        table.label(() -> "链接数量" + this.seqSize).pad(4).wrap().width(200f).left();
        table.row();
        table.label(() -> isDoor() ? (open ? "模式: 门(开)" : "模式: 门(关)")
            : isAutoDoor() ? (open ? "模式: 自动门(开)" : "模式: 自动门(关)")
                : (isShield() ? "模式: 相位盾" : "模式: 墙")).pad(4)
            .wrap().width(200f).left();
      }
        }
  }
}
