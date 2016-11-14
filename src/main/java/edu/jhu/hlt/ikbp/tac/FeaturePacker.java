package edu.jhu.hlt.ikbp.tac;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.ikbp.tac.IndexCommunications.SentFeats;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.IntTrip;

public class FeaturePacker {
  
  /*
   * Because Situations are not fixed width (may have arbitrary number of args),
   * I'm just going to use run-length encoding.
   */
  
  public static final byte ENTITY = 0;
  public static final byte DEPREL = 1;
  public static final byte SIT_FN = 2;
  public static final byte SIT_PB = 3;
  
  public static class Buf {
    byte[] b;
    int ofs;
  }
  
  public static void writeEntity(int entity, byte nerType, SentFeats dest) {
    // Assume that dest.featBuf is always tight, no extra bytes
    int offset = dest.featBuf.length;
    dest.featBuf = Arrays.copyOf(dest.featBuf, offset + 1+1+4);
    writeEntity(entity, nerType, dest.featBuf, offset);
  }

  /** assumes there are enough bytes in dest */
  public static void writeEntity(int entity, byte nerType, byte[] dest, int destOffset) {
    ByteBuffer bb = ByteBuffer.wrap(dest, destOffset, 1+1+4);
    bb.put(ENTITY);
    bb.put(nerType);
    bb.putInt(entity);
  }
  
  /** returns the new offset */
  public static int readEntity(byte[] source, int sourceOffset, Unpacked dest) {
    ByteBuffer bb = ByteBuffer.wrap(source, sourceOffset, 1+1+4);
    bb.get(); // entry type
    byte nerType = bb.get();
    int ent = bb.getInt();
    dest.entities.add(new IntPair(nerType, ent));
    return sourceOffset + 1+1+4;
  }

  public static void writeDeprel(int deprel, int arg0, int arg1, SentFeats dest) {
    int offset = dest.featBuf.length;
    dest.featBuf = Arrays.copyOf(dest.featBuf, offset + 1+3*4);
    writeDeprel(deprel, arg0, arg1, dest.featBuf, offset);
  }
  
  /** assumes there are enough bytes in dest */
  public static void writeDeprel(int deprel, int arg0, int arg1, byte[] dest, int destOffset) {
    ByteBuffer bb = ByteBuffer.wrap(dest, destOffset, 1 + 3*4);
    bb.put(DEPREL);
    bb.putInt(deprel);
    bb.putInt(arg0);
    bb.putInt(arg1);
  }
  
  /** returns the new offset */
  public static int readDeprel(byte[] source, int sourceOffset, Unpacked dest) {
    ByteBuffer bb = ByteBuffer.wrap(source, sourceOffset, 1 + 3*4);
    bb.get(); // entry type
    int deprel = bb.getInt();
    int arg0 = bb.getInt();
    int arg1 = bb.getInt();
    dest.deprels.add(new IntTrip(deprel, arg0, arg1));
    return sourceOffset + 1+3*4;
  }
  
  public static class Unpacked {
    List<IntPair> entities = new ArrayList<>();   // (nerType, entity)
    List<IntTrip> deprels = new ArrayList<>();    // (deprel, arg0, arg1)
    // etc
  }
  
  public static Unpacked unpack(byte[] buf) {
    Unpacked u = new Unpacked();
    int offset = 0;
    while (offset < buf.length) {
      int t = buf[offset];
      switch (t) {
      case ENTITY:
        offset = readEntity(buf, offset, u);
        break;
      case DEPREL:
        offset = readDeprel(buf, offset, u);
        break;
      default:
        throw new RuntimeException("t="+ t);
      }
    }
    return u;
  }
  
  public static void main(String[] args) {
    SentFeats sf = new SentFeats();
    byte nt = 2;
    System.out.println(Arrays.toString(sf.featBuf));
    writeEntity(-100, nt, sf);
    System.out.println(Arrays.toString(sf.featBuf));
    writeEntity(42, nt, sf);
    writeDeprel(1, 2, 3, sf);
    writeDeprel(-4, 5, -6, sf);
    writeDeprel(7, -8, -9, sf);
    Unpacked u = unpack(sf.featBuf);
    System.out.println(u.entities);
    System.out.println(u.deprels);
  }
}
