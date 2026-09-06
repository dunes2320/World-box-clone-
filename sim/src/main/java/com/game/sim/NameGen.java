package com.game.sim;

/**
 * Deterministic per-species name generator.
 *
 * <p>Two syllables from a species-specific pool, picked by a stable hash
 * of the unit index and a per-world salt. Deterministic on the input
 * alone - no {@link java.util.Random} state advanced, so calling this to
 * name a newborn does not shift the RNG stream downstream systems depend
 * on for reproducibility.
 */
public final class NameGen {

    private NameGen() {
    }

    private static final String[] HUMAN_PRE = {
        "Ari", "Bran", "Cass", "Del", "El", "Fen", "Gar", "Hal",
        "Ily", "Jor", "Kel", "Lin", "Mar", "Nell", "Ol", "Per",
        "Quen", "Ros", "Sel", "Tal", "Var", "Wen", "Yal", "Zor"};
    private static final String[] HUMAN_POST = {
        "an", "en", "ir", "ea", "ric", "wyn", "ton", "mir",
        "an", "el", "os", "us", "ard", "eth", "ion", "ora"};

    private static final String[] ORC_PRE = {
        "Grath", "Zog", "Mok", "Ur", "Krak", "Vor", "Nog", "Dur",
        "Brak", "Skar", "Grim", "Hurk", "Uz", "Tark", "Ork", "Rag"};
    private static final String[] ORC_POST = {
        "ak", "ur", "or", "gash", "moth", "grim", "ash", "og",
        "ok", "lug", "nak", "rok", "muk", "gor", "rath", "durn"};

    private static final String[] ELF_PRE = {
        "Aer", "Cael", "Elen", "Faela", "Gala", "Ilya", "Ith", "Lira",
        "Mira", "Ny", "Ori", "Quen", "Rhi", "Sae", "Sil", "Tha",
        "Val", "Wyla", "Xir", "Yena"};
    private static final String[] ELF_POST = {
        "ion", "iel", "aris", "wen", "rin", "andir", "esse", "avel",
        "arion", "iar", "asel", "yne", "yr", "eth", "uril", "aloth"};

    private static final String[] DWARF_PRE = {
        "Bal", "Bruk", "Dur", "Falk", "Gim", "Har", "Ker", "Mor",
        "Nul", "Orik", "Ru", "Skar", "Thor", "Ur", "Vand", "Zar"};
    private static final String[] DWARF_POST = {
        "in", "ur", "im", "grim", "borg", "rik", "din", "gar",
        "mund", "ok", "run", "dr", "kek", "eth", "arth", "vor"};

    /**
     * Names a unit by its slot index. Two different worlds pick different
     * names for the same slot via the seed salt; within one world the
     * name is stable, so a saved world round-trips to the same names.
     */
    public static String name(int unitIndex, byte species, long worldSeed) {
        String[] pre;
        String[] post;
        switch (species) {
            case Species.ORC: pre = ORC_PRE; post = ORC_POST; break;
            case Species.ELF: pre = ELF_PRE; post = ELF_POST; break;
            case Species.DWARF: pre = DWARF_PRE; post = DWARF_POST; break;
            case Species.HUMAN:
            default: pre = HUMAN_PRE; post = HUMAN_POST;
        }
        // A trivial mixing hash - not cryptographic, just enough to spread
        // adjacent unit indices to different syllable combinations.
        long h = worldSeed * 0x9E3779B97F4A7C15L + unitIndex * 0xBF58476D1CE4E5B9L;
        int a = (int) ((h >>> 16) & 0x7fffffff) % pre.length;
        int b = (int) ((h >>> 32) & 0x7fffffff) % post.length;
        return pre[a] + post[b];
    }
}
