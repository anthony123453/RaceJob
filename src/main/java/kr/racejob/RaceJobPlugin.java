package kr.racejob;
 
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.*;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
 
public class RaceJobPlugin extends JavaPlugin {
 
    public static final double[] SWORD_BONUS = {0.20, 0.25, 0.30, 0.40};
    public static final double[] BOW_BONUS = {0.20, 0.25, 0.30, 0.40};
    public static final double[] BRAWL_BONUS = {0.20, 0.40, 0.60, 1.00};
    public static final int[] MUSIC_INTERVAL = {5, 3, 2, 1};
 
    public NamespacedKey kRace, kJob, kTier, kPendRace, kPendJob, kInit, kTag, kChoice;
 
    public final Map<UUID, Race> raceCache = new ConcurrentHashMap<>();
    public final Map<UUID, String> stateCache = new HashMap<>();
    public final Set<UUID> disguised = new HashSet<>();
    public final Map<UUID, Integer> musicCount = new HashMap<>();
    public final Map<UUID, Long> buffUntil = new HashMap<>();
    public final Map<UUID, Long> buffReady = new HashMap<>();
    public final Set<UUID> echoing = new HashSet<>();
 
    private String modNamespace;
 
    /** 종족/직업 선택 메뉴 식별용 */
    public static class MenuHolder implements InventoryHolder {
        public final boolean race;
        public Inventory inv;
        public MenuHolder(boolean race) { this.race = race; }
        @Override public Inventory getInventory() { return inv; }
    }
 
    @Override
    public void onEnable() {
        saveDefaultConfig();
        kRace = new NamespacedKey(this, "race");
        kJob = new NamespacedKey(this, "job");
        kTier = new NamespacedKey(this, "tier");
        kPendRace = new NamespacedKey(this, "pend_race");
        kPendJob = new NamespacedKey(this, "pend_job");
        kInit = new NamespacedKey(this, "init");
        kTag = new NamespacedKey(this, "tag");
        kChoice = new NamespacedKey(this, "choice");
        modNamespace = kRace.getNamespace();
 
        CraftListener craft = new CraftListener(this);
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(craft, this);
        craft.setupRecipes();
 
        Bukkit.getScheduler().runTaskTimer(this, this::tickFast, 20L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickSecond, 20L, 20L);
 
        for (Player p : Bukkit.getOnlinePlayers()) {
            raceCache.remove(p.getUniqueId());
            refresh(p);
        }
    }
 
    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearMods(p);
            syncDisguise(p, false);
        }
    }
 
    // ───────────────────────── 데이터 ─────────────────────────
 
    public Race getRace(Player p) {
        Race c = raceCache.get(p.getUniqueId());
        if (c != null) return c;
        String s = p.getPersistentDataContainer().get(kRace, PersistentDataType.STRING);
        Race r = Race.HUMAN;
        if (s != null) {
            try { r = Race.valueOf(s); } catch (IllegalArgumentException ignored) { }
        }
        raceCache.put(p.getUniqueId(), r);
        return r;
    }
 
    public void setRace(Player p, Race r) {
        p.getPersistentDataContainer().set(kRace, PersistentDataType.STRING, r.name());
        raceCache.put(p.getUniqueId(), r);
    }
 
    public Job getJob(Player p) {
        String s = p.getPersistentDataContainer().get(kJob, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Job.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }
 
    public void setJob(Player p, Job j) {
        if (j == null) p.getPersistentDataContainer().remove(kJob);
        else p.getPersistentDataContainer().set(kJob, PersistentDataType.STRING, j.name());
    }
 
    public int getTier(Player p) {
        Integer i = p.getPersistentDataContainer().get(kTier, PersistentDataType.INTEGER);
        return i == null ? 0 : Math.max(0, Math.min(3, i));
    }
 
    public void setTier(Player p, int t) {
        p.getPersistentDataContainer().set(kTier, PersistentDataType.INTEGER, Math.max(0, Math.min(3, t)));
    }
 
    public boolean flag(Player p, NamespacedKey k) {
        Byte b = p.getPersistentDataContainer().get(k, PersistentDataType.BYTE);
        return b != null && b == 1;
    }
 
    public void setFlag(Player p, NamespacedKey k, boolean v) {
        p.getPersistentDataContainer().set(k, PersistentDataType.BYTE, (byte) (v ? 1 : 0));
    }
 
    public boolean extraUnlocked(Player p) {
        return getTier(p) >= getConfig().getInt("extra-ability-tier", 3);
    }
 
    // ───────────────────────── 아이템 ─────────────────────────
 
    public ItemStack makeItem(Material m, String name, String tag, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        if (tag != null) meta.getPersistentDataContainer().set(kTag, PersistentDataType.STRING, tag);
        it.setItemMeta(meta);
        return it;
    }
 
    public String tagOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(kTag, PersistentDataType.STRING);
    }
 
    public ItemStack starItem() {
        ItemStack it = makeItem(Material.NETHER_STAR, "§e전직의 별", "star",
                "§7우클릭하면 직업이 한 단계 성장합니다", "§7(T1 → T2 → T3)");
        ItemMeta m = it.getItemMeta();
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }
 
    public ItemStack ticketItem(boolean race) {
        return race
                ? makeItem(Material.PAPER, "§a종족 선택권", "ticket_race", "§7우클릭하여 종족을 다시 선택합니다")
                : makeItem(Material.PAPER, "§b직업 선택권", "ticket_job", "§7우클릭하여 직업을 다시 선택합니다");
    }
 
    public void giveOrDrop(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
    }
 
    public void removeTagged(Player p, String tag) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            if (tag.equals(tagOf(c[i]))) inv.setItem(i, null);
        }
    }
 
    public boolean hasTagged(Player p, String tag) {
        for (ItemStack i : p.getInventory().getContents()) {
            if (tag.equals(tagOf(i))) return true;
        }
        return false;
    }
 
    private ItemStack cursed(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.addEnchant(Enchantment.BINDING_CURSE, 1, true);
        it.setItemMeta(m);
        return it;
    }
 
    private void giveRaceItems(Player p, Race r) {
        if (r == Race.BIRD && !hasTagged(p, "race_elytra")) {
            ItemStack e = cursed(makeItem(Material.ELYTRA, "§b새의 날개", "race_elytra"));
            if (p.getInventory().getChestplate() == null) p.getInventory().setChestplate(e);
            else giveOrDrop(p, e);
        }
    }
 
    private void giveMusicHelmet(Player p) {
        if (hasTagged(p, "music_helmet")) return;
        ItemStack h = cursed(makeItem(Material.JUKEBOX, "§d음악가의 주크박스", "music_helmet"));
        ItemStack old = p.getInventory().getHelmet();
        if (old != null && old.getType() != Material.AIR) giveOrDrop(p, old);
        p.getInventory().setHelmet(h);
    }
 
    /** 리스폰 후 종족/직업 전용 아이템 보충 */
    public void ensureItems(Player p) {
        giveRaceItems(p, getRace(p));
        if (getJob(p) == Job.MUSICIAN) giveMusicHelmet(p);
    }
 
    // ───────────────────────── 선택 ─────────────────────────
 
    public void chooseRace(Player p, Race r) {
        Race old = getRace(p);
        removeTagged(p, "race_elytra");
        if (old == Race.MERMAN) {
            p.removePotionEffect(PotionEffectType.WATER_BREATHING);
            p.removePotionEffect(PotionEffectType.STRENGTH);
            p.removePotionEffect(PotionEffectType.RESISTANCE);
        }
        if (old == Race.BLIND) p.removePotionEffect(PotionEffectType.BLINDNESS);
        setRace(p, r);
        setFlag(p, kPendRace, false);
        giveRaceItems(p, r);
        stateCache.remove(p.getUniqueId());
        refresh(p);
        p.sendMessage("§a종족 §f" + r.display + "§a을(를) 선택했습니다!");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }
 
    /** j == null 이면 직업 제거 */
    public void chooseJob(Player p, Job j) {
        removeTagged(p, "music_helmet");
        setJob(p, j);
        setTier(p, 0);
        setFlag(p, kPendJob, false);
        musicCount.remove(p.getUniqueId());
        if (j == null) {
            p.sendMessage("§e직업이 제거되었습니다.");
            return;
        }
        switch (j) {
            case SWORDSMAN -> giveOrDrop(p, new ItemStack(Material.STONE_SWORD));
            case ARCHER -> {
                giveOrDrop(p, new ItemStack(Material.BOW));
                int n = getConfig().getInt("archer-starter-arrows", 32);
                if (n > 0) giveOrDrop(p, new ItemStack(Material.ARROW, n));
            }
            case MUSICIAN -> giveMusicHelmet(p);
            default -> { }
        }
        p.sendMessage("§a직업 §f" + j.display + "§a을(를) 선택했습니다!");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
    }
 
    private ItemStack choiceItem(Material m, String name, String choice, String[] lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add("§7" + s);
        meta.setLore(l);
        meta.getPersistentDataContainer().set(kChoice, PersistentDataType.STRING, choice);
        it.setItemMeta(meta);
        return it;
    }
 
    public void openRaceMenu(Player p) {
        MenuHolder h = new MenuHolder(true);
        Inventory inv = Bukkit.createInventory(h, 9, "§8종족을 선택하세요");
        h.inv = inv;
        int i = 0;
        for (Race r : Race.values()) inv.setItem(i++, choiceItem(r.icon, "§a" + r.display, r.name(), r.lore));
        p.openInventory(inv);
    }
 
    public void openJobMenu(Player p) {
        MenuHolder h = new MenuHolder(false);
        Inventory inv = Bukkit.createInventory(h, 9, "§8직업을 선택하세요");
        h.inv = inv;
        int[] slots = {1, 3, 5, 7};
        int i = 0;
        for (Job j : Job.values()) inv.setItem(slots[i++], choiceItem(j.icon, "§b" + j.display, j.name(), j.lore));
        p.openInventory(inv);
    }
 
    // ───────────────────────── 능력치 갱신 ─────────────────────────
 
    private void clearMods(Player p) {
        for (Attribute a : Registry.ATTRIBUTE) {
            AttributeInstance ai = p.getAttribute(a);
            if (ai == null) continue;
            for (AttributeModifier m : new ArrayList<>(ai.getModifiers())) {
                if (m.getKey().getNamespace().equals(modNamespace)) ai.removeModifier(m);
            }
        }
    }
 
    private void mod(Player p, Attribute a, String id, double v, AttributeModifier.Operation op) {
        AttributeInstance ai = p.getAttribute(a);
        if (ai == null) return;
        ai.addModifier(new AttributeModifier(new NamespacedKey(this, id), v, op));
    }
 
    private boolean isNight(World w) {
        if (w.getEnvironment() != World.Environment.NORMAL) return false;
        long t = w.getTime();
        return t >= 13000 && t <= 23000;
    }
 
    private boolean isDay(World w) {
        return w.getEnvironment() == World.Environment.NORMAL && w.getTime() < 12000;
    }
 
    public void refresh(Player p) {
        Race r = getRace(p);
        World w = p.getWorld();
        boolean water = p.isInWater();
        AttributeInstance hp = p.getAttribute(Attribute.MAX_HEALTH);
        double oldMax = hp.getValue();
        double ratio = oldMax > 0 ? p.getHealth() / oldMax : 1.0;
 
        clearMods(p);
        AttributeModifier.Operation SCALAR = AttributeModifier.Operation.ADD_SCALAR;
        AttributeModifier.Operation NUMBER = AttributeModifier.Operation.ADD_NUMBER;
 
        switch (r) {
            case BIRD -> mod(p, Attribute.MAX_HEALTH, "bird_hp", -0.5, SCALAR);
            case FURRY -> {
                mod(p, Attribute.MOVEMENT_SPEED, "furry_spd", 0.2, SCALAR);
                mod(p, Attribute.JUMP_STRENGTH, "furry_jump", 0.2, SCALAR);
            }
            case VAMPIRE -> {
                if (isNight(w)) mod(p, Attribute.MOVEMENT_SPEED, "vamp_spd", 0.3, SCALAR);
            }
            case GIANT -> {
                mod(p, Attribute.SCALE, "giant_scale", 1.0, SCALAR);
                mod(p, Attribute.MOVEMENT_SPEED, "giant_spd", 0.5, SCALAR);
            }
            case DWARF -> mod(p, Attribute.MOVEMENT_SPEED, "dwarf_spd", 0.5, SCALAR);
            case MERMAN -> {
                if (water) {
                    mod(p, Attribute.SUBMERGED_MINING_SPEED, "mer_mine", 2.0, SCALAR);
                    mod(p, Attribute.WATER_MOVEMENT_EFFICIENCY, "mer_swim", 0.5, NUMBER);
                } else {
                    // 지상: 대구(체력 3, 속도 매우 느림)
                    mod(p, Attribute.MAX_HEALTH, "fish_hp", 3.0 - hp.getBaseValue(), NUMBER);
                    mod(p, Attribute.MOVEMENT_SPEED, "fish_spd", -0.6, SCALAR);
                }
            }
            default -> { }
        }
 
        syncDisguise(p, r == Race.MERMAN && !water);
 
        if (!p.isDead()) {
            double newMax = hp.getValue();
            if (Math.abs(newMax - oldMax) > 0.001) {
                p.setHealth(Math.max(0.1, Math.min(newMax, ratio * newMax)));
            } else if (p.getHealth() > newMax) {
                p.setHealth(newMax);
            }
        }
    }
 
    private void syncDisguise(Player p, boolean fish) {
        if (!getConfig().getBoolean("use-libsdisguises", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) return;
        UUID id = p.getUniqueId();
        if (fish && disguised.add(id)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "disguiseplayer " + p.getName() + " cod");
        } else if (!fish && disguised.remove(id)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "undisguiseplayer " + p.getName());
        }
    }
 
    private void eff(Player p, PotionEffectType t, int amp, int dur) {
        p.addPotionEffect(new PotionEffect(t, dur, amp, true, false, false));
    }
 
    // ───────────────────────── 주기 작업 ─────────────────────────
 
    private void tickFast() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            Race r = getRace(p);
            World w = p.getWorld();
            boolean water = p.isInWater();
            String state = r.name()
                    + (r == Race.MERMAN && water ? "W" : "")
                    + (r == Race.VAMPIRE && isNight(w) ? "N" : "");
            if (!state.equals(stateCache.get(p.getUniqueId()))) {
                stateCache.put(p.getUniqueId(), state);
                refresh(p);
            }
 
            switch (r) {
                case MERMAN -> {
                    if (water) {
                        eff(p, PotionEffectType.WATER_BREATHING, 0, 80);
                        eff(p, PotionEffectType.STRENGTH, 1, 80);
                        eff(p, PotionEffectType.RESISTANCE, 0, 80);
                    }
                }
                case BLIND -> eff(p, PotionEffectType.BLINDNESS, 0, 80);
                case VAMPIRE -> {
                    p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
                    if (getConfig().getBoolean("vampire-sun-burn", true)
                            && isDay(w) && !water && !w.hasStorm()
                            && p.getEyeLocation().getBlock().getLightFromSky() >= 15) {
                        p.setFireTicks(Math.max(p.getFireTicks(), 60));
                    }
                }
                default -> { }
            }
        }
    }
 
    private void tickSecond() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || getJob(p) != Job.MUSICIAN) continue;
            int interval = MUSIC_INTERVAL[getTier(p)];
            int c = musicCount.merge(p.getUniqueId(), 1, Integer::sum);
            if (c >= interval) {
                musicCount.put(p.getUniqueId(), 0);
                wave(p);
            }
        }
    }
 
    private void wave(Player p) {
        Location loc = p.getLocation().add(0, 1, 0);
        World w = p.getWorld();
        w.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.2f, 0.7f + ThreadLocalRandom.current().nextFloat() * 0.8f);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            w.spawnParticle(Particle.NOTE, loc.clone().add(Math.cos(a) * 3, 0, Math.sin(a) * 3), 1, 0, 0, 0, 1.0);
        }
        boolean mobs = getConfig().getBoolean("musician-affects-mobs", true);
        for (Entity en : p.getNearbyEntities(3, 3, 3)) {
            if (!(en instanceof LivingEntity v) || en instanceof ArmorStand) continue;
            if (v.getLocation().distanceSquared(p.getLocation()) > 9) continue;
            if (v instanceof Tameable t && t.isTamed()) continue;
            if (v instanceof Player vp) {
                if (vp.getGameMode() == GameMode.CREATIVE || vp.getGameMode() == GameMode.SPECTATOR) continue;
            } else if (!mobs) continue;
            trueDamage(p, v, 1.0);
        }
    }
 
    /** 갑옷 무시 고정 피해 */
    private void trueDamage(Player src, LivingEntity v, double dmg) {
        double hp = v.getHealth();
        if (hp - dmg <= 0) {
            v.damage(10000, src);
        } else {
            v.setHealth(hp - dmg);
            v.playHurtAnimation(0f);
        }
    }
 
    public void musicianBuff(Player p) {
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        Long ready = buffReady.get(id);
        if (ready != null && ready > now) {
            p.sendMessage("§c아직 사용할 수 없습니다. (" + ((ready - now) / 1000 + 1) + "초)");
            return;
        }
        buffUntil.put(id, now + 5000L);
        buffReady.put(id, now + getConfig().getInt("musician-buff-cooldown-seconds", 15) * 1000L);
        p.sendMessage("§d연주 버프! §f5초간 공격력 +20%");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.5f);
    }
 
    /** 같은 피해를 일정 틱 뒤에 다시 입힘 (검사/싸움꾼 추가능력) */
    public void echo(Player atk, LivingEntity victim, double dmg, long... delays) {
        for (long d : delays) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!victim.isValid() || victim.isDead() || !atk.isOnline()) return;
                echoing.add(atk.getUniqueId());
                try {
                    victim.setNoDamageTicks(0);
                    victim.damage(dmg, atk);
                } finally {
                    echoing.remove(atk.getUniqueId());
                }
            }, d);
        }
    }
 
    // ───────────────────────── 명령어 ─────────────────────────
 
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String n = c.getName().toLowerCase();
        if (n.equals("race") || n.equals("job")) {
            if (!(s instanceof Player p)) {
                s.sendMessage("플레이어만 사용할 수 있습니다.");
                return true;
            }
            boolean race = n.equals("race");
            if (flag(p, race ? kPendRace : kPendJob)) {
                if (race) openRaceMenu(p); else openJobMenu(p);
            } else if (race) {
                p.sendMessage("§e현재 종족: §f" + getRace(p).display + " §7(종족 선택권 = 종이 + 네더라이트 주괴 조합)");
            } else {
                Job j = getJob(p);
                p.sendMessage("§e현재 직업: §f" + (j == null ? "없음" : j.display + " T" + getTier(p))
                        + " §7(직업 선택권 = 종이 + 네더의 별 조합)");
            }
            return true;
        }
 
        // /rj
        if (!s.hasPermission("racejob.admin")) {
            s.sendMessage("§c권한이 없습니다.");
            return true;
        }
        String usage = "§e/rj race <플레이어> <" + Arrays.toString(Race.values()).toLowerCase() + ">\n"
                + "§e/rj job <플레이어> <" + Arrays.toString(Job.values()).toLowerCase() + "|none>\n"
                + "§e/rj tier <플레이어> <0-3>\n§e/rj give <플레이어> <race|job|star> [개수]\n§e/rj reload";
        if (a.length == 0) { s.sendMessage(usage); return true; }
        if (a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage("§a설정을 다시 불러왔습니다.");
            return true;
        }
        if (a.length < 3) { s.sendMessage(usage); return true; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage("§c플레이어를 찾을 수 없습니다."); return true; }
        try {
            switch (a[0].toLowerCase()) {
                case "race" -> chooseRace(t, Race.valueOf(a[2].toUpperCase()));
                case "job" -> chooseJob(t, a[2].equalsIgnoreCase("none") ? null : Job.valueOf(a[2].toUpperCase()));
                case "tier" -> setTier(t, Integer.parseInt(a[2]));
                case "give" -> {
                    int cnt = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                    ItemStack it = switch (a[2].toLowerCase()) {
                        case "race" -> ticketItem(true);
                        case "job" -> ticketItem(false);
                        default -> starItem();
                    };
                    it.setAmount(Math.max(1, Math.min(64, cnt)));
                    giveOrDrop(t, it);
                }
                default -> { s.sendMessage(usage); return true; }
            }
            s.sendMessage("§a완료.");
        } catch (IllegalArgumentException ex) {
            s.sendMessage("§c잘못된 값입니다.\n" + usage);
        }
        return true;
    }
}
 
enum Race {
    HUMAN("사람", Material.PLAYER_HEAD, "능력 없음"),
    MERMAN("어인", Material.COD,
            "물속: 채굴속도 증가, 산소 무한, 힘 II, 저항, 수영속도 +50%",
            "단점: 지상에서는 물고기(체력/속도 물고기 수준)"),
    BIRD("새", Material.FEATHER,
            "겉날개 지급 (귀속 저주)",
            "단점: 최대 체력 절반"),
    FURRY("퍼리", Material.BONE,
            "이동속도, 점프력 +20%",
            "단점: 채팅 뒤에 meow 가 붙음"),
    VAMPIRE("흡혈귀", Material.REDSTONE,
            "플레이어 타격 시 체력 0.5칸 회복, 밤에 이동속도 +30%",
            "단점: 낮에 불탐(화염저항 무효), 익힌 고기 못 먹음, 불 피해 증가"),
    BLIND("장님", Material.BLACK_DYE,
            "공격력 2배, 받는 피해 절반",
            "단점: 실명 효과"),
    GIANT("거인", Material.IRON_BLOCK,
            "공격력 2배, 받는 피해 절반, 이동속도 x1.5, 신체 크기 2배"),
    DWARF("소인", Material.LEATHER_BOOTS,
            "이동속도 x1.5",
            "단점: 공격력 절반, 낙하 피해 2배");
 
    public final String display;
    public final Material icon;
    public final String[] lore;
 
    Race(String display, Material icon, String... lore) {
        this.display = display;
        this.icon = icon;
        this.lore = lore;
    }
}
 
enum Job {
    SWORDSMAN("검사", Material.STONE_SWORD,
            "돌검 지급, 검 데미지 +20%",
            "전직: T1 25% / T2 30% / T3 40%",
            "추가능력: 타격 1초 뒤 같은 데미지 한 번 더"),
    ARCHER("궁수", Material.BOW,
            "활 지급, 활 데미지 +20%",
            "전직: T1 25% / T2 30% / T3 40%",
            "추가능력: 화살이 한 번에 3발"),
    BRAWLER("길거리 싸움꾼", Material.IRON_NUGGET,
            "주먹 데미지 +20%",
            "전직: T1 40% / T2 60% / T3 100%",
            "추가능력: 타격 시 3초에 걸쳐 넉백+데미지 2회 추가"),
    MUSICIAN("음악가", Material.JUKEBOX,
            "머리에 주크박스, 5초마다 반경 3칸 파동(갑옷 무시 0.5하트)",
            "전직: 주기 T1 3초 / T2 2초 / T3 1초",
            "추가능력: 주크박스 우클릭 시 5초간 공격 +20%");
 
    public final String display;
    public final Material icon;
    public final String[] lore;
 
    Job(String display, Material icon, String... lore) {
        this.display = display;
        this.icon = icon;
        this.lore = lore;
    }
}
 
class GameListener implements Listener {
 
    private final RaceJobPlugin plugin;
 
    private static final Set<Material> COOKED_MEAT = EnumSet.of(
            Material.COOKED_BEEF, Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN,
            Material.COOKED_MUTTON, Material.COOKED_RABBIT, Material.COOKED_COD, Material.COOKED_SALMON);
 
    public GameListener(RaceJobPlugin plugin) {
        this.plugin = plugin;
    }
 
    // ───────────────────────── 접속 / 종료 / 사망 ─────────────────────────
 
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.raceCache.remove(p.getUniqueId());
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(plugin.kInit, PersistentDataType.BYTE)) {
            pdc.set(plugin.kInit, PersistentDataType.BYTE, (byte) 1);
            plugin.setFlag(p, plugin.kPendRace, true);
            plugin.setFlag(p, plugin.kPendJob, true);
            if (plugin.getConfig().getBoolean("give-starter-materials", true)) {
                plugin.giveOrDrop(p, new ItemStack(Material.PAPER, 2));
                plugin.giveOrDrop(p, new ItemStack(Material.NETHERITE_INGOT, 1));
                plugin.giveOrDrop(p, new ItemStack(Material.NETHER_STAR, 1));
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.refresh(p);
            if (plugin.flag(p, plugin.kPendRace)) {
                p.sendMessage("§e종족을 선택해주세요! (/race)");
                plugin.openRaceMenu(p);
            } else if (plugin.flag(p, plugin.kPendJob)) {
                p.sendMessage("§e직업을 선택해주세요! (/job)");
                plugin.openJobMenu(p);
            }
        }, 20L);
    }
 
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plugin.raceCache.remove(id);
        plugin.stateCache.remove(id);
        plugin.disguised.remove(id);
        plugin.musicCount.remove(id);
        plugin.buffUntil.remove(id);
        plugin.buffReady.remove(id);
    }
 
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(i -> {
            String t = plugin.tagOf(i);
            return "race_elytra".equals(t) || "music_helmet".equals(t);
        });
    }
 
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        plugin.stateCache.remove(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.refresh(p);
            plugin.ensureItems(p);
        }, 2L);
    }
 
    // ───────────────────────── 메뉴 ─────────────────────────
 
    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof RaceJobPlugin.MenuHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        String c = it.getItemMeta().getPersistentDataContainer().get(plugin.kChoice, PersistentDataType.STRING);
        if (c == null) return;
 
        if (h.race) {
            if (!plugin.flag(p, plugin.kPendRace)) { p.closeInventory(); return; }
            plugin.chooseRace(p, Race.valueOf(c));
            p.closeInventory();
            if (plugin.flag(p, plugin.kPendJob)) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) plugin.openJobMenu(p);
                }, 5L);
            }
        } else {
            if (!plugin.flag(p, plugin.kPendJob)) { p.closeInventory(); return; }
            plugin.chooseJob(p, Job.valueOf(c));
            p.closeInventory();
        }
    }
 
    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof RaceJobPlugin.MenuHolder) e.setCancelled(true);
    }
 
    // ───────────────────────── 우클릭 (선택권 / 전직의 별 / 음악가) ─────────────────────────
 
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack it = e.getItem();
        String tag = plugin.tagOf(it);
 
        if (tag != null) {
            switch (tag) {
                case "star" -> { e.setCancelled(true); useStar(p); }
                case "ticket_race" -> {
                    e.setCancelled(true);
                    consumeHand(p);
                    plugin.setFlag(p, plugin.kPendRace, true);
                    plugin.openRaceMenu(p);
                }
                case "ticket_job" -> {
                    e.setCancelled(true);
                    consumeHand(p);
                    plugin.setFlag(p, plugin.kPendJob, true);
                    plugin.openJobMenu(p);
                }
                default -> { }
            }
            return;
        }
 
        if (plugin.getJob(p) == Job.MUSICIAN && plugin.extraUnlocked(p)) {
            boolean juke = (it != null && it.getType() == Material.JUKEBOX)
                    || (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null
                    && e.getClickedBlock().getType() == Material.JUKEBOX);
            if (juke) plugin.musicianBuff(p);
        }
    }
 
    private void consumeHand(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        h.setAmount(h.getAmount() - 1);
    }
 
    private void useStar(Player p) {
        Job j = plugin.getJob(p);
        if (j == null) { p.sendMessage("§c직업이 없어서 사용할 수 없습니다."); return; }
        int t = plugin.getTier(p);
        if (t >= 3) { p.sendMessage("§e이미 최고 단계(T3)입니다."); return; }
        consumeHand(p);
        plugin.setTier(p, t + 1);
        plugin.musicCount.remove(p.getUniqueId());
 
        Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class);
        fw.getPersistentDataContainer().set(plugin.kTag, PersistentDataType.STRING, "fx");
        FireworkMeta fm = fw.getFireworkMeta();
        fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR)
                .withColor(Color.YELLOW, Color.AQUA).withFade(Color.WHITE).flicker(true).build());
        fw.setFireworkMeta(fm);
        fw.detonate();
 
        Bukkit.broadcastMessage("§6[전직] §e" + p.getName() + "§f이(가) 전직 §bt" + (t + 1) + "§f에 성공했습니다!");
    }
 
    /** 전직 연출용 폭죽이 피해를 주지 않도록 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFireworkFx(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f
                && "fx".equals(f.getPersistentDataContainer().get(plugin.kTag, PersistentDataType.STRING))) {
            e.setCancelled(true);
        }
    }
 
    // ───────────────────────── 전투 ─────────────────────────
 
    /** 받는 피해 보정 (종족) */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Race r = plugin.getRace(v);
        double m = 1.0;
        switch (r) {
            case BLIND, GIANT -> m = 0.5;
            case DWARF -> { if (e.getCause() == DamageCause.FALL) m = 2.0; }
            case VAMPIRE -> {
                switch (e.getCause()) {
                    case FIRE, FIRE_TICK, LAVA, HOT_FLOOR ->
                            m = plugin.getConfig().getDouble("vampire-fire-damage-multiplier", 2.0);
                    default -> { }
                }
            }
            default -> { }
        }
        if (m != 1.0) e.setDamage(e.getDamage() * m);
    }
 
    /** 주는 피해 보정 (종족 + 직업) 및 추가능력 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        Player atk;
        boolean ranged = false;
        if (e.getDamager() instanceof Player pl) {
            atk = pl;
        } else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sp) {
            atk = sp;
            ranged = true;
        } else {
            return;
        }
        if (plugin.echoing.contains(atk.getUniqueId())) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
 
        Race r = plugin.getRace(atk);
        Job j = plugin.getJob(atk);
        int tier = plugin.getTier(atk);
        boolean extra = plugin.extraUnlocked(atk);
        double mult = 1.0;
 
        switch (r) {
            case BLIND, GIANT -> mult *= 2.0;
            case DWARF -> mult *= 0.5;
            default -> { }
        }
 
        if (r == Race.VAMPIRE && !ranged && victim instanceof Player) {
            AttributeInstance mh = atk.getAttribute(Attribute.MAX_HEALTH);
            atk.setHealth(Math.min(mh.getValue(), atk.getHealth() + 1.0));
        }
 
        Material hand = atk.getInventory().getItemInMainHand().getType();
        boolean swordHit = false, brawlHit = false;
        if (j != null) {
            switch (j) {
                case SWORDSMAN -> {
                    if (!ranged && hand.name().endsWith("_SWORD")) {
                        mult *= 1 + RaceJobPlugin.SWORD_BONUS[tier];
                        swordHit = true;
                    }
                }
                case ARCHER -> {
                    if (ranged && e.getDamager() instanceof AbstractArrow && !(e.getDamager() instanceof Trident)) {
                        mult *= 1 + RaceJobPlugin.BOW_BONUS[tier];
                    }
                }
                case BRAWLER -> {
                    if (!ranged && hand == Material.AIR) {
                        mult *= 1 + RaceJobPlugin.BRAWL_BONUS[tier];
                        brawlHit = true;
                    }
                }
                case MUSICIAN -> {
                    Long until = plugin.buffUntil.get(atk.getUniqueId());
                    if (until != null && until > System.currentTimeMillis()) mult *= 1.2;
                }
            }
        }
 
        double dmg = e.getDamage() * mult;
        e.setDamage(dmg);
 
        if (extra) {
            if (swordHit) plugin.echo(atk, victim, dmg, 20L);          // 1초 뒤 한 번 더
            if (brawlHit) plugin.echo(atk, victim, dmg, 30L, 60L);     // 3초에 걸쳐 2회
        }
    }
 
    /** 궁수 추가능력: 화살 3발 */
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (plugin.getJob(p) != Job.ARCHER || !plugin.extraUnlocked(p)) return;
        if (!(e.getProjectile() instanceof AbstractArrow orig)) return;
        Vector v = orig.getVelocity();
        for (double ang : new double[]{-10, 10}) {
            Arrow a = p.getWorld().spawn(orig.getLocation(), Arrow.class);
            a.setShooter(p);
            a.setVelocity(v.clone().rotateAroundY(Math.toRadians(ang)));
            a.setCritical(orig.isCritical());
            a.setDamage(orig.getDamage());
            a.setFireTicks(orig.getFireTicks());
            a.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        }
    }
 
    // ───────────────────────── 종족 단점 ─────────────────────────
 
    @EventHandler(ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        if (plugin.getRace(p) == Race.VAMPIRE && COOKED_MEAT.contains(e.getItem().getType())) {
            e.setCancelled(true);
            p.sendMessage("§c흡혈귀는 익힌 고기를 먹을 수 없습니다.");
        }
    }
 
    @EventHandler(ignoreCancelled = true)
    public void onEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getNewEffect() == null) return;
        if (e.getNewEffect().getType().equals(PotionEffectType.FIRE_RESISTANCE)
                && plugin.getRace(p) == Race.VAMPIRE) {
            e.setCancelled(true);
        }
    }
 
    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Race r = plugin.raceCache.get(e.getPlayer().getUniqueId());
        if (r == Race.FURRY) e.message(e.message().append(Component.text(" meow")));
    }
}
 
/**
 * - 구리/철/금/다이아 도구의 기존 제작법 삭제 → 모루에서 확률 제작
 * - 네더라이트 도구: 스미딩 테이블 업그레이드 시 확률, 실패 시 파괴
 * - 전직의 별 / 선택권 제작법 등록
 */
class CraftListener implements Listener {
 
    private final RaceJobPlugin plugin;
 
    record Tool(Material result, Material ingot, int ingots, int sticks, String tierKey, boolean axe) { }
 
    private final List<Tool> tools = new ArrayList<>();
 
    public CraftListener(RaceJobPlugin plugin) {
        this.plugin = plugin;
        String[][] tiers = {
                {"COPPER", "COPPER_INGOT", "copper"},
                {"IRON", "IRON_INGOT", "iron"},
                {"GOLDEN", "GOLD_INGOT", "gold"},
                {"DIAMOND", "DIAMOND", "diamond"}};
        // 종류, 재료 개수, 막대기 개수, 도끼 여부(이름표로 구분)
        Object[][] kinds = {
                {"SWORD", 2, 1, false},
                {"SHOVEL", 1, 2, false},
                {"HOE", 2, 2, false},
                {"PICKAXE", 3, 2, false},
                {"AXE", 3, 2, true}};
        for (String[] t : tiers) {
            Material ingot = Material.matchMaterial(t[1]);
            if (ingot == null) continue;
            for (Object[] k : kinds) {
                Material res = Material.matchMaterial(t[0] + "_" + k[0]); // 서버에 없는 도구(구리 등)는 자동 제외
                if (res == null) continue;
                tools.add(new Tool(res, ingot, (int) k[1], (int) k[2], t[2], (boolean) k[3]));
            }
        }
    }
 
    public void setupRecipes() {
        // 1) 기존 제작법 삭제
        Set<Material> targets = new HashSet<>();
        for (Tool t : tools) targets.add(t.result());
        List<NamespacedKey> remove = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r instanceof Keyed k && (r instanceof ShapedRecipe || r instanceof ShapelessRecipe)
                    && targets.contains(r.getResult().getType())) {
                remove.add(k.getKey());
            }
        }
        for (NamespacedKey k : remove) Bukkit.removeRecipe(k);
 
        // 2) 전직의 별 / 선택권
        addShapeless("career_star", plugin.starItem(),
                Material.EMERALD_BLOCK, Material.DIAMOND_BLOCK, Material.REDSTONE_BLOCK, Material.GOLD_BLOCK,
                Material.IRON_BLOCK, Material.COPPER_BLOCK, Material.PAPER, Material.COBBLESTONE);
        addShapeless("ticket_race", plugin.ticketItem(true), Material.PAPER, Material.NETHERITE_INGOT);
        addShapeless("ticket_job", plugin.ticketItem(false), Material.PAPER, Material.NETHER_STAR);
 
        Bukkit.updateRecipes();
    }
 
    private void addShapeless(String key, ItemStack result, Material... mats) {
        NamespacedKey k = new NamespacedKey(plugin, key);
        Bukkit.removeRecipe(k);
        ShapelessRecipe r = new ShapelessRecipe(k, result);
        for (Material m : mats) r.addIngredient(m);
        Bukkit.addRecipe(r);
    }
 
    /** 커스텀 아이템(선택권/별 등)을 일반 제작 재료로 쓰는 것 방지 */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        for (ItemStack i : e.getInventory().getMatrix()) {
            if (plugin.tagOf(i) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
 
    // ───────────────────────── 모루 제작 ─────────────────────────
 
    private Tool match(Inventory inv, String rename) {
        ItemStack a = inv.getItem(0);
        ItemStack b = inv.getItem(1);
        if (a == null || b == null || b.getType() != Material.STICK) return null;
        String rn = rename == null ? "" : rename.trim();
        boolean axeName = rn.equalsIgnoreCase("axe") || rn.equals("도끼");
        for (Tool t : tools) {
            if (t.ingot() == a.getType() && t.ingots() == a.getAmount()
                    && t.sticks() == b.getAmount() && t.axe() == axeName) {
                return t;
            }
        }
        return null;
    }
 
    private double chance(String key) {
        return plugin.getConfig().getDouble("chances." + key, 0.5);
    }
 
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        AnvilView v = e.getView();
        Tool t = match(e.getInventory(), v.getRenameText());
        if (t == null) return;
        ItemStack res = new ItemStack(t.result());
        ItemMeta m = res.getItemMeta();
        m.setLore(List.of("§7제작 성공 확률: §e" + (int) Math.round(chance(t.tierKey()) * 100) + "%",
                "§c실패하면 재료가 사라집니다"));
        res.setItemMeta(m);
        e.setResult(res);
        v.setRepairCost(plugin.getConfig().getInt("anvil-level-cost", 1));
    }
 
    @EventHandler
    public void onAnvilClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory ai)) return;
        if (e.getRawSlot() != 2) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String rn = (e.getView() instanceof AnvilView av) ? av.getRenameText() : null;
        Tool t = match(ai, rn);
        if (t == null) return;
        ItemStack res = ai.getItem(2);
        if (res == null || res.getType() != t.result()) return;
 
        e.setCancelled(true);
        int cost = plugin.getConfig().getInt("anvil-level-cost", 1);
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        if (!creative && p.getLevel() < cost) {
            p.sendMessage("§c경험치 레벨이 부족합니다. (필요: " + cost + ")");
            return;
        }
        if (!creative) p.setLevel(p.getLevel() - cost);
 
        consume(ai, 0, t.ingots());
        consume(ai, 1, t.sticks());
        ai.setItem(2, null);
 
        if (ThreadLocalRandom.current().nextDouble() < chance(t.tierKey())) {
            plugin.giveOrDrop(p, new ItemStack(t.result()));
            p.sendMessage("§a제작 성공!");
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f);
        } else {
            p.sendMessage("§c제작 실패... 재료가 사라졌습니다.");
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        }
        Bukkit.getScheduler().runTask(plugin, p::updateInventory);
    }
 
    private void consume(Inventory inv, int slot, int n) {
        ItemStack s = inv.getItem(slot);
        if (s == null) return;
        if (s.getAmount() <= n) {
            inv.setItem(slot, null);
        } else {
            s.setAmount(s.getAmount() - n);
            inv.setItem(slot, s);
        }
    }
 
    // ───────────────────────── 네더라이트 업그레이드 ─────────────────────────
 
    private boolean isNetheriteTool(Material m) {
        String n = m.name();
        return n.startsWith("NETHERITE_") && (n.endsWith("_SWORD") || n.endsWith("_PICKAXE")
                || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE"));
    }
 
    @EventHandler
    public void onSmith(SmithItemEvent e) {
        SmithingInventory inv = e.getInventory();
        ItemStack res = inv.getResult();
        if (res == null || !isNetheriteTool(res.getType())) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
 
        e.setCancelled(true);
        ItemStack out = res.clone();
        for (int i = 0; i < 3; i++) consume(inv, i, 1); // 템플릿, 기존 도구, 네더라이트 주괴
        inv.setResult(null);
 
        if (ThreadLocalRandom.current().nextDouble() < chance("netherite")) {
            plugin.giveOrDrop(p, out);
            p.sendMessage("§a네더라이트 강화 성공!");
            p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1.2f);
        } else {
            p.sendMessage("§c네더라이트 강화 실패... 도구가 파괴되었습니다.");
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
        }
        Bukkit.getScheduler().runTask(plugin, p::updateInventory);
    }
}
 
 
