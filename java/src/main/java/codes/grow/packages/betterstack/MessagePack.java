package codes.grow.packages.betterstack;

import codes.grow.packages.betterstack.core.ValidationException;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal MessagePack encoder for Better Stack ingest bodies (no dependency). {@link Instant}
 * values become timestamp extensions (type -1, nanosecond precision), {@code byte[]} becomes {@code
 * bin}; maps, collections, arrays, strings, booleans, integers, floats and {@code null} map to
 * their MessagePack types. Any other object is written as its {@code toString()} text.
 */
public final class MessagePack {
  private static final int MAX_DEPTH = 100;
  private static final BigInteger MAX_U64 = BigInteger.ONE.shiftLeft(64);

  private MessagePack() {}

  /**
   * Encodes a value.
   *
   * @param value value
   * @return MessagePack bytes
   * @throws ValidationException when the value is nested deeper than 100 levels or an integer does
   *     not fit 64 bits
   */
  public static byte[] encode(Object value) {
    Out out = new Out();
    write(out, value, 0);
    return out.toByteArray();
  }

  /**
   * Writes the header of an array of {@code n} elements (the elements follow).
   *
   * @param n element count
   * @return header bytes
   */
  public static byte[] arrayHeader(int n) {
    Out out = new Out();
    length(out, n, 0x90, 15, 0, 0xdc, 0xdd);
    return out.toByteArray();
  }

  private static final class Out extends ByteArrayOutputStream {
    void u8(int v) {
      write(v & 0xff);
    }

    void u16(int v) {
      u8(v >>> 8);
      u8(v);
    }

    void u32(long v) {
      u8((int) (v >>> 24));
      u8((int) (v >>> 16));
      u8((int) (v >>> 8));
      u8((int) v);
    }

    void u64(long v) {
      u32(v >>> 32);
      u32(v);
    }
  }

  private static void write(Out out, Object v, int depth) {
    if (depth > MAX_DEPTH) {
      throw new ValidationException("msgpack: value nested too deeply");
    }
    if (v == null) {
      out.u8(0xc0);
    } else if (v instanceof Boolean) {
      out.u8((Boolean) v ? 0xc3 : 0xc2);
    } else if (v instanceof Byte
        || v instanceof Short
        || v instanceof Integer
        || v instanceof Long) {
      writeLong(out, ((Number) v).longValue());
    } else if (v instanceof BigInteger) {
      writeBig(out, (BigInteger) v);
    } else if (v instanceof Float || v instanceof Double) {
      out.u8(0xcb);
      out.u64(Double.doubleToLongBits(((Number) v).doubleValue()));
    } else if (v instanceof Number) {
      writeString(out, v.toString());
    } else if (v instanceof CharSequence) {
      writeString(out, v.toString());
    } else if (v instanceof Instant) {
      writeTimestamp(out, (Instant) v);
    } else if (v instanceof byte[]) {
      byte[] b = (byte[]) v;
      length(out, b.length, -1, -1, 0xc4, 0xc5, 0xc6);
      out.writeBytes(b);
    } else if (v instanceof Map) {
      Map<?, ?> m = (Map<?, ?>) v;
      length(out, m.size(), 0x80, 15, 0, 0xde, 0xdf);
      for (Map.Entry<?, ?> e : m.entrySet()) {
        writeString(out, String.valueOf(e.getKey()));
        write(out, e.getValue(), depth + 1);
      }
    } else if (v instanceof Collection) {
      Collection<?> c = (Collection<?>) v;
      length(out, c.size(), 0x90, 15, 0, 0xdc, 0xdd);
      for (Object item : c) {
        write(out, item, depth + 1);
      }
    } else if (v instanceof Object[]) {
      Object[] a = (Object[]) v;
      length(out, a.length, 0x90, 15, 0, 0xdc, 0xdd);
      for (Object item : a) {
        write(out, item, depth + 1);
      }
    } else {
      writeString(out, v.toString());
    }
  }

  private static void writeString(Out out, String s) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    length(out, b.length, 0xa0, 31, 0xd9, 0xda, 0xdb);
    out.writeBytes(b);
  }

  /** Fix form ({@code fix | n} up to {@code fixMax}) or the 8/16/32-bit codes (0 = none). */
  private static void length(Out out, int n, int fix, int fixMax, int c8, int c16, int c32) {
    if (fix >= 0 && n <= fixMax) {
      out.u8(fix | n);
    } else if (c8 != 0 && n < 256) {
      out.u8(c8);
      out.u8(n);
    } else if (n < 65536) {
      out.u8(c16);
      out.u16(n);
    } else {
      out.u8(c32);
      out.u32(n);
    }
  }

  private static void writeLong(Out out, long v) {
    if (v >= 0) {
      if (v < 128) {
        out.u8((int) v);
      } else if (v < 256) {
        out.u8(0xcc);
        out.u8((int) v);
      } else if (v < 65536) {
        out.u8(0xcd);
        out.u16((int) v);
      } else if (v < 4294967296L) {
        out.u8(0xce);
        out.u32(v);
      } else {
        out.u8(0xcf);
        out.u64(v);
      }
    } else if (v >= -32) {
      out.u8((int) v);
    } else if (v >= -128) {
      out.u8(0xd0);
      out.u8((int) v);
    } else if (v >= -32768) {
      out.u8(0xd1);
      out.u16((int) v);
    } else if (v >= Integer.MIN_VALUE) {
      out.u8(0xd2);
      out.u32(v);
    } else {
      out.u8(0xd3);
      out.u64(v);
    }
  }

  private static void writeBig(Out out, BigInteger v) {
    if (v.bitLength() < 64) {
      writeLong(out, v.longValue());
    } else if (v.signum() > 0 && v.compareTo(MAX_U64) < 0) {
      out.u8(0xcf);
      out.u64(v.longValue());
    } else {
      throw new ValidationException("msgpack: integer out of range");
    }
  }

  private static void writeTimestamp(Out out, Instant t) {
    long sec = t.getEpochSecond();
    int nsec = t.getNano();
    if (sec >= 0 && sec < (1L << 34)) {
      if (nsec == 0 && sec < (1L << 32)) {
        out.u8(0xd6);
        out.u8(0xff);
        out.u32(sec);
      } else {
        out.u8(0xd7);
        out.u8(0xff);
        out.u64(((long) nsec << 34) | sec);
      }
    } else {
      out.u8(0xc7);
      out.u8(12);
      out.u8(0xff);
      out.u32(nsec);
      out.u64(sec);
    }
  }
}
