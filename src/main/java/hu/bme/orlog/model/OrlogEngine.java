package hu.bme.orlog.model;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class OrlogEngine {

    public Map<Face, Integer> countFaces(List<Face> faces) {
        Map<Face, Integer> m = new EnumMap<>(Face.class);
        for (Face f : faces)
            if (f != null)
                m.merge(f, 1, Integer::sum);
        return m;
    }

    private int c(Map<Face, Integer> m, Face f) {
        return m.getOrDefault(f, 0);
    }

    public int stealAmount(Map<Face, Integer> att) {
        return c(att, Face.STEAL) + c(att, Face.STEAL_GOLD);
    }

    public int melee(Map<Face, Integer> att) {
        return c(att, Face.MELEE) + c(att, Face.MELEE_GOLD);
    }

    public int ranged(Map<Face, Integer> att) {
        return c(att, Face.RANGED) + c(att, Face.RANGED_GOLD);
    }

    public int shields(Map<Face, Integer> def) {
        return c(def, Face.SHIELD) + c(def, Face.SHIELD_GOLD);
    }

    public int helmets(Map<Face, Integer> def) {
        return c(def, Face.HELMET) + c(def, Face.HELMET_GOLD);
    }

    public int goldCount(List<Face> faces) {
        int c = 0;
        for (Face f : faces)
            if (f != null && f.gold)
                c++;
        return c;
    }

    public void resolveRound(GameState gs, List<Face> p1Faces, List<Face> p2Faces) {
        var a = countFaces(p1Faces);
        var b = countFaces(p2Faces);

        applyBeforeFavors(gs, a, b);

        // STEAL
        int s1 = stealAmount(a);
        int s2 = stealAmount(b);
        int s1real = Math.min(s1, gs.p2.getFavor());
        int s2real = Math.min(s2, gs.p1.getFavor());
        gs.p1.addFavor(s1real);
        gs.p2.spendFavor(s1real);
        gs.p2.addFavor(s2real);
        gs.p1.spendFavor(s2real);

        // Count after before-effects
        int melee1 = melee(a), melee2 = melee(b);
        int ranged1 = ranged(a), ranged2 = ranged(b);
        int sh2 = shields(b), he2 = helmets(b);
        int sh1 = shields(a), he1 = helmets(a);

        int dmg1 = Math.max(0, melee1 - sh2) + Math.max(0, ranged1 - he2);
        int dmg2 = Math.max(0, melee2 - sh1) + Math.max(0, ranged2 - he1);

        gs.p1.addFavor(goldCount(p1Faces));
        gs.p2.addFavor(goldCount(p2Faces));

        gs.p2.damage(dmg1);
        gs.p1.damage(dmg2);

        applyAfterFavors(gs, a, b, dmg1, dmg2);

        gs.melee1 = melee1;
        gs.ranged1 = ranged1;
        gs.shields2 = sh2;
        gs.helmets2 = he2;
        gs.dmg1 = dmg1;
        gs.melee2 = melee2;
        gs.ranged2 = ranged2;
        gs.shields1 = sh1;
        gs.helmets1 = he1;
        gs.dmg2 = dmg2;

        gs.addLog(String.format(
                "R%d: %s dealt %d (M:%d vs S:%d, R:%d vs H:%d) | %s dealt %d (M:%d vs S:%d, R:%d vs H:%d)",
                gs.round, gs.p1.getName(), dmg1, melee1, sh2, ranged1, he2,
                gs.p2.getName(), dmg2, melee2, sh1, ranged2, he1));
        gs.round++;
        gs.rollPhase = 1;
        gs.p1.chooseFavor(null, 0);
        gs.p2.chooseFavor(null, 0);
        gs.p1.getDice().clearLocks();
        gs.p2.getDice().clearLocks();
    }

    private void spend(Player p, GodFavor f, int tier) {
        p.spendFavor(f.costs[tier]);
    }

    private void applyBeforeFavors(GameState gs, Map<Face, Integer> a, Map<Face, Integer> b) {
        List<Player> players = List.of(gs.p1, gs.p2);
        players.stream().sorted(Comparator.comparingInt(p -> {
            GodFavor f = p.getChosenFavor();
            return (f != null && f.phase == GodFavor.Phase.BEFORE) ? f.priority : Integer.MAX_VALUE;
        })).forEach(p -> {
            GodFavor f = p.getChosenFavor();
            int tier = p.getChosenTier();
            if (f == null || f.phase != GodFavor.Phase.BEFORE)
                return;
            if (p.getFavor() < f.costs[tier])
                return;
            Player opp = (p == gs.p1) ? gs.p2 : gs.p1;
            Map<Face, Integer> self = (p == gs.p1) ? a : b;
            Map<Face, Integer> enemy = (p == gs.p1) ? b : a;
            switch (f.type) {
                case REMOVE_OPP_HELMETS -> {
                    spend(p, f, tier);
                    int rm = Math.min(f.magnitudes[tier],
                            enemy.getOrDefault(Face.HELMET, 0) + enemy.getOrDefault(Face.HELMET_GOLD, 0));
                    int norm = Math.min(enemy.getOrDefault(Face.HELMET, 0), rm);
                    enemy.put(Face.HELMET, enemy.getOrDefault(Face.HELMET, 0) - norm);
                    int left = rm - norm;
                    if (left > 0)
                        enemy.put(Face.HELMET_GOLD, Math.max(0, enemy.getOrDefault(Face.HELMET_GOLD, 0) - left));
                    gs.addLog(p.getName() + " used " + f.name + " (-" + rm + " helmets)");
                }
                case IGNORE_OPP_RANGED_BLOCKS -> {
                    spend(p, f, tier);
                    int ignore = f.magnitudes[tier];
                    int sh = enemy.getOrDefault(Face.SHIELD, 0);
                    int gold = enemy.getOrDefault(Face.SHIELD_GOLD, 0);
                    int rm = Math.min(ignore, sh + gold);
                    int norm = Math.min(sh, rm);
                    enemy.put(Face.SHIELD, sh - norm);
                    int left = rm - norm;
                    if (left > 0)
                        enemy.put(Face.SHIELD_GOLD, Math.max(0, gold - left));
                    gs.addLog(p.getName() + " used " + f.name + " (ignore " + rm + " shields)");
                }
                case DOUBLE_BLOCKS -> {
                    spend(p, f, tier);
                    int add = f.magnitudes[tier];
                    self.put(Face.HELMET, self.getOrDefault(Face.HELMET, 0) + self.getOrDefault(Face.HELMET, 0) * add);
                    self.put(Face.HELMET_GOLD,
                            self.getOrDefault(Face.HELMET_GOLD, 0) + self.getOrDefault(Face.HELMET_GOLD, 0) * add);
                    self.put(Face.SHIELD, self.getOrDefault(Face.SHIELD, 0) + self.getOrDefault(Face.SHIELD, 0) * add);
                    self.put(Face.SHIELD_GOLD,
                            self.getOrDefault(Face.SHIELD_GOLD, 0) + self.getOrDefault(Face.SHIELD_GOLD, 0) * add);
                    gs.addLog(p.getName() + " used " + f.name + " (+blocks)");
                }
                case BONUS_PER_RANGED -> {
                    spend(p, f, tier);
                    int bonus = f.magnitudes[tier]
                            * (self.getOrDefault(Face.RANGED, 0) + self.getOrDefault(Face.RANGED_GOLD, 0));
                    self.put(Face.RANGED, self.getOrDefault(Face.RANGED, 0) + bonus);
                    gs.addLog(p.getName() + " used " + f.name + " (+" + bonus + " arrows)");
                }
                case MULTIPLY_MELEE -> {
                    spend(p, f, tier);
                    int percent = f.magnitudes[tier];
                    int base = self.getOrDefault(Face.MELEE, 0) + self.getOrDefault(Face.MELEE_GOLD, 0);
                    int extra = (base * (percent - 100)) / 100;
                    self.put(Face.MELEE, self.getOrDefault(Face.MELEE, 0) + extra);
                    gs.addLog(p.getName() + " used " + f.name + " (x" + percent + "% melee)");
                }
                case BONUS_MAJORITY -> {
                    spend(p, f, tier);
                    int m = self.getOrDefault(Face.MELEE, 0) + self.getOrDefault(Face.MELEE_GOLD, 0);
                    int r = self.getOrDefault(Face.RANGED, 0) + self.getOrDefault(Face.RANGED_GOLD, 0);
                    int h = self.getOrDefault(Face.HELMET, 0) + self.getOrDefault(Face.HELMET_GOLD, 0);
                    int s = self.getOrDefault(Face.SHIELD, 0) + self.getOrDefault(Face.SHIELD_GOLD, 0);
                    int st = self.getOrDefault(Face.STEAL, 0) + self.getOrDefault(Face.STEAL_GOLD, 0);
                    int add = f.magnitudes[tier];
                    if (m >= r && m >= h && m >= s && m >= st)
                        self.put(Face.MELEE, self.getOrDefault(Face.MELEE, 0) + add);
                    else if (r >= h && r >= s && r >= st)
                        self.put(Face.RANGED, self.getOrDefault(Face.RANGED, 0) + add);
                    else if (h >= s && h >= st)
                        self.put(Face.HELMET, self.getOrDefault(Face.HELMET, 0) + add);
                    else if (s >= st)
                        self.put(Face.SHIELD, self.getOrDefault(Face.SHIELD, 0) + add);
                    else
                        self.put(Face.STEAL, self.getOrDefault(Face.STEAL, 0) + add);
                    gs.addLog(p.getName() + " used " + f.name + " (+majority)");
                }
                case DESTROY_OPP_TOKENS_PER_ARROW -> {
                    spend(p, f, tier);
                    int arrows = self.getOrDefault(Face.RANGED, 0) + self.getOrDefault(Face.RANGED_GOLD, 0);
                    int toDestroy = arrows * f.magnitudes[tier];
                    int real = Math.min(toDestroy, opp.getFavor());
                    opp.spendFavor(real);
                    gs.addLog(p.getName() + " used " + f.name + " (-" + real + " opp tokens)");
                }
                case GAIN_TOKENS -> {
                    spend(p, f, tier);
                    p.addFavor(f.magnitudes[tier]);
                    gs.addLog(p.getName() + " used " + f.name + " (+" + f.magnitudes[tier] + " tokens)");
                }
                default -> {
                }
            }
        });
    }

    private void applyAfterFavors(GameState gs, Map<Face, Integer> a, Map<Face, Integer> b, int dmg1, int dmg2) {
        List<Player> players = List.of(gs.p1, gs.p2);
        players.stream().sorted(Comparator.comparingInt(p -> {
            GodFavor f = p.getChosenFavor();
            return (f != null && f.phase == GodFavor.Phase.AFTER) ? f.priority : Integer.MAX_VALUE;
        })).forEach(p -> {
            GodFavor f = p.getChosenFavor();
            int tier = p.getChosenTier();
            if (f == null || f.phase != GodFavor.Phase.AFTER)
                return;
            if (p.getFavor() < f.costs[tier])
                return;
            Player opp = (p == gs.p1) ? gs.p2 : gs.p1;
            switch (f.type) {
                case DAMAGE -> {
                    spend(p, f, tier);
                    opp.damage(f.magnitudes[tier]);
                    gs.addLog(p.getName() + " used " + f.name + " (" + f.magnitudes[tier] + " dmg)");
                }
                case HEAL -> {
                    spend(p, f, tier);
                    p.damage(-f.magnitudes[tier]);
                    gs.addLog(p.getName() + " used " + f.name + " (heal " + f.magnitudes[tier] + ")");
                }
                case HEAL_PER_BLOCKED -> {
                    spend(p, f, tier);
                    int blocked = Math.min(melee((p == gs.p1) ? b : a), helmets((p == gs.p1) ? a : b)) +
                            Math.min(ranged((p == gs.p1) ? b : a), shields((p == gs.p1) ? a : b));
                    int heal = blocked * f.magnitudes[tier];
                    p.damage(-heal);
                    gs.addLog(p.getName() + " used " + f.name + " (heal " + heal + ")");
                }
                case HEAL_PER_INCOMING_MELEE -> {
                    spend(p, f, tier);
                    int inc = Math.max(0, melee((p == gs.p1) ? b : a) - helmets((p == gs.p1) ? a : b));
                    int heal = inc * f.magnitudes[tier];
                    p.damage(-heal);
                    gs.addLog(p.getName() + " used " + f.name + " (heal " + heal + ")");
                }
                case TOKENS_PER_DAMAGE_TAKEN -> {
                    spend(p, f, tier);
                    int taken = (p == gs.p1) ? (dmg2) : (dmg1);
                    p.addFavor(taken * f.magnitudes[tier]);
                    gs.addLog(p.getName() + " used " + f.name + " (+" + (taken * f.magnitudes[tier]) + " tokens)");
                }
                case TOKENS_PER_STEAL -> {
                    spend(p, f, tier);
                    int steals = (p == gs.p1) ? (a.getOrDefault(Face.STEAL, 0) + a.getOrDefault(Face.STEAL_GOLD, 0))
                            : (b.getOrDefault(Face.STEAL, 0) + b.getOrDefault(Face.STEAL_GOLD, 0));
                    p.addFavor(steals * f.magnitudes[tier]);
                    gs.addLog(p.getName() + " used " + f.name + " (+" + (steals * f.magnitudes[tier]) + " tokens)");
                }
                case HEAL_PER_OPP_FAVOR_SPENT -> {
                    int spent = (opp.getChosenFavor() != null) ? opp.getChosenFavor().costs[opp.getChosenTier()] : 0;
                    spend(p, f, tier);
                    int heal = spent * f.magnitudes[tier];
                    p.damage(-heal);
                    gs.addLog(p.getName() + " used " + f.name + " (heal " + heal + ")");
                }
                default -> {
                }
            }
        });
    }
}
