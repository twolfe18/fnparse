package edu.jhu.hlt.fnparse.rl.rerank;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.util.DataStreamSerializable;

public class Item implements DataStreamSerializable {
  private int t;  // index of frame instance
  private int k;  // role
  private Span span;
  private double priorScore;
  private double featureScore;

  public Item(int t, int k, Span s, double priorScore) {
    this.t = t;
    this.k = k;
    this.span = s;
    this.priorScore = priorScore;
  }

  public int t() { return t; }
  public int k() { return k; }
  public Span getSpan() { return span; }

  public String toString() {
    String ss = span == Span.nullSpan ? "nullSpan" : span.start + "-" + span.end;
    return String.format(
        "<Item t=%d k=%d span=%s priorScore=%.2f featureScore=%.2f>",
        t, k, ss, priorScore, featureScore);
  }

  public void setFeatureScore(double fs) {
    this.featureScore = fs;
  }

  public double getScore() {
    return priorScore + featureScore;
  }

  @Override
  public void serialize(DataOutputStream dos) throws IOException {
    dos.writeInt(t);
    dos.writeInt(k);
    if (span == Span.nullSpan) {
      dos.writeInt(-1);
    } else {
      dos.writeInt(span.start);
      dos.writeInt(span.end);
    }
    dos.writeDouble(priorScore);
    dos.writeDouble(featureScore);
  }

  @Override
  public void deserialize(DataInputStream dis) throws IOException {
    t = dis.readInt();
    k = dis.readInt();
    int s1 = dis.readInt();
    if (s1 == -1) {
      span = Span.nullSpan;
    } else {
      span = Span.getSpan(s1, dis.readInt());
    }
    priorScore = dis.readDouble();
    featureScore = dis.readDouble();
  }
}