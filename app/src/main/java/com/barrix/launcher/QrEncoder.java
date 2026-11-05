package com.barrix.launcher;

import java.nio.charset.Charset;

/**
 * Generador de códigos QR sin dependencias (modo byte, corrección M, versiones 1-10).
 * Suficiente para direcciones web cortas (hasta ~210 caracteres).
 * Uso: boolean[][] m = QrEncoder.encode("http://192.168.1.5:8080/?k=1234"); // true = módulo oscuro
 */
public final class QrEncoder {
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    private QrEncoder() { }

    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(Charset.forName("UTF-8"));
        int ver = -1;
        for (int v = 1; v <= 10; v++) {
            int ccBits = v <= 9 ? 8 : 16;
            int cap = numDataCodewords(v) * 8;
            if (4 + ccBits + data.length * 8 <= cap) { ver = v; break; }
        }
        if (ver < 0) throw new IllegalArgumentException("Texto demasiado largo para el QR");

        // 1. Bits de datos
        int ccBits = ver <= 9 ? 8 : 16;
        int capBits = numDataCodewords(ver) * 8;
        int[] bits = new int[capBits + 64];
        int n = 0;
        n = put(bits, n, 4, 4); // modo byte = 0100
        n = put(bits, n, data.length, ccBits);
        for (byte b : data) n = put(bits, n, b & 0xFF, 8);
        n += Math.min(4, capBits - n);       // terminador
        n = (n + 7) / 8 * 8;                 // relleno a byte
        byte[] cw = new byte[numDataCodewords(ver)];
        for (int i = 0; i < n; i++) if (bits[i] != 0) cw[i >> 3] |= 1 << (7 - (i & 7));
        for (int i = n / 8, pad = 0xEC; i < cw.length; i++, pad ^= 0xEC ^ 0x11) cw[i] = (byte) pad;

        // 2. Reed-Solomon e intercalado
        byte[] all = addEccAndInterleave(cw, ver);

        // 3. Matriz
        int size = ver * 4 + 17;
        boolean[][] mod = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(mod, fn, ver);
        drawCodewords(mod, fn, all);

        // 4. Mejor máscara
        int best = 0;
        long bestPen = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(mod, fn, m);
            drawFormatBits(mod, fn, m);
            long p = penalty(mod);
            if (p < bestPen) { bestPen = p; best = m; }
            applyMask(mod, fn, m); // deshacer (XOR)
        }
        applyMask(mod, fn, best);
        drawFormatBits(mod, fn, best);
        return mod;
    }

    private static int put(int[] bits, int pos, int val, int len) {
        for (int i = len - 1; i >= 0; i--) bits[pos++] = (val >>> i) & 1;
        return pos;
    }

    private static int numRawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int na = ver / 7 + 2;
            r -= (25 * na - 10) * na - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int numDataCodewords(int ver) {
        return numRawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private static byte[] addEccAndInterleave(byte[] data, int ver) {
        int nb = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
        int raw = numRawModules(ver) / 8;
        int numShort = nb - raw % nb;
        int shortLen = raw / nb;
        byte[][] blocks = new byte[nb][];
        byte[] div = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < nb; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, div);
            byte[] blk = new byte[shortLen + 1];
            System.arraycopy(dat, 0, blk, 0, datLen);
            System.arraycopy(ecc, 0, blk, blk.length - eccLen, eccLen);
            blocks[i] = blk;
        }
        byte[] out = new byte[raw];
        int o = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < nb; j++) {
                if (i != shortLen - eccLen || j >= numShort) out[o++] = blocks[j][i];
            }
        }
        return out;
    }

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= gfMul(div[i] & 0xFF, f);
        }
        return r;
    }

    private static void setFn(boolean[][] mod, boolean[][] fn, int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private static void drawFunctionPatterns(boolean[][] mod, boolean[][] fn, int ver) {
        int size = mod.length;
        for (int i = 0; i < size; i++) {
            setFn(mod, fn, 6, i, i % 2 == 0);
            setFn(mod, fn, i, 6, i % 2 == 0);
        }
        drawFinder(mod, fn, 3, 3);
        drawFinder(mod, fn, size - 4, 3);
        drawFinder(mod, fn, 3, size - 4);
        int[] pos = alignPositions(ver, size);
        int last = pos.length - 1;
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFn(mod, fn, pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormatBits(mod, fn, 0); // reserva el sitio
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean c = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                setFn(mod, fn, a, b, c);
                setFn(mod, fn, b, a, c);
            }
        }
    }

    private static void drawFinder(boolean[][] mod, boolean[][] fn, int cx, int cy) {
        int size = mod.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFn(mod, fn, x, y, d != 2 && d != 4);
            }
        }
    }

    private static int[] alignPositions(int ver, int size) {
        if (ver == 1) return new int[0];
        int na = ver / 7 + 2;
        int step = (ver * 4 + na * 2 + 1) / (na * 2 - 2) * 2;
        int[] r = new int[na];
        r[0] = 6;
        for (int i = na - 1, p = size - 7; i >= 1; i--, p -= step) r[i] = p;
        return r;
    }

    private static void drawFormatBits(boolean[][] mod, boolean[][] fn, int mask) {
        int size = mod.length;
        int data = (0 << 3) | mask; // nivel M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFn(mod, fn, 8, i, bit(bits, i));
        setFn(mod, fn, 8, 7, bit(bits, 6));
        setFn(mod, fn, 8, 8, bit(bits, 7));
        setFn(mod, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(mod, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFn(mod, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(mod, fn, 8, size - 15 + i, bit(bits, i));
        setFn(mod, fn, 8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static void drawCodewords(boolean[][] mod, boolean[][] fn, byte[] data) {
        int size = mod.length;
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = bit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] mod, boolean[][] fn, int m) {
        int size = mod.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!fn[y][x] && inv) mod[y][x] = !mod[y][x];
            }
        }
    }

    private static long penalty(boolean[][] m) {
        int size = m.length;
        long p = 0;
        int dark = 0;
        for (int a = 0; a < size; a++) {
            for (int dir = 0; dir < 2; dir++) {
                int run = 1;
                long hist = 0; // 11 últimos módulos
                for (int b = 0; b < size; b++) {
                    boolean c = dir == 0 ? m[a][b] : m[b][a];
                    if (dir == 0 && c) dark++;
                    if (b > 0) {
                        boolean prev = dir == 0 ? m[a][b - 1] : m[b - 1][a];
                        if (c == prev) { run++; if (run == 5) p += 3; else if (run > 5) p++; }
                        else run = 1;
                    }
                    hist = ((hist << 1) | (c ? 1 : 0)) & 0x7FF;
                    if (b >= 10 && (hist == 0x05D || hist == 0x5D0)) p += 40;
                }
            }
        }
        for (int y = 0; y + 1 < size; y++)
            for (int x = 0; x + 1 < size; x++) {
                boolean c = m[y][x];
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3;
            }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += k * 10L;
        return p;
    }
}
