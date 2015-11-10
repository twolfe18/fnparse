package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import edu.jhu.hlt.fnparse.datatypes.Span;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Feature;
import edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * Takes a feature file with int features and packs it using a {@link DataOutputStream}.
 *
 * @deprecated Don't use this: this seems to be bigger than the un-gzipped
 * version! If I want to do this, I should benchmark, and I'm not convinced its
 * even worth it.
 *
 * @author travis
 */
public class BinaryPacker {

  /**
   * Reads the format defined in {@link FeaturePrecomputation#emit(java.io.Writer, edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation.Target, edu.jhu.hlt.fnparse.datatypes.Span, int, java.util.List)}
   */
  public static void pack(String inputLine, DataOutputStream output) throws IOException {
    String[] toks = inputLine.split("\t");
    int i = 0;

    // Target (see Target.toLine)
    String docId = toks[i++];
    output.writeUTF(docId);
    String sendId = toks[i++];
    output.writeUTF(sendId);
    int target = Integer.parseInt(toks[i++]);
    output.writeInt(target);

    // Span
    String span = toks[i++];    // start,end
    int s = span.indexOf(',');
    int start = Integer.parseInt(span.substring(0, s));
    int end = Integer.parseInt(span.substring(s + 1));
    output.writeInt(start);
    output.writeInt(end);

    // Role
    int k = Integer.parseInt(toks[i++]);
    output.writeInt(k);

    // Features
    int numFeat = toks.length - i;
    output.writeInt(numFeat);
    for (; i < toks.length; i++) {
      String f = toks[i];
      int c = f.indexOf(':');
      int template = Integer.parseInt(f.substring(0, c));
      int feature = Integer.parseInt(f.substring(c + 1));
      output.writeInt(template);
      output.writeInt(feature);
    }
  }

  public static class Unpack {
    public final Target target;
    public final Span span;
    public final int k;
    public final Feature[] features;
    public Unpack(DataInputStream dis) throws IOException {
      this.target = Target.fromDis(dis);
      int start = dis.readInt();
      int end = dis.readInt();
      this.span = Span.getSpan(start, end);
      this.k = dis.readInt();
      int numFeat = dis.readInt();
      features = new Feature[numFeat];
      for (int i = 0; i < numFeat; i++) {
        int template = dis.readInt();
        int feature = dis.readInt();
        features[i] = new Feature(null, template, null, feature, 1);
      }
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.out.println("please provide:");
      System.out.println("1) an input feature file generated by FeaturePrecomputation");
      System.out.println("2) an output feature file");
      return;
    }
    Log.info(args[0] + "  =>  " + args[1]);
    int c = 0;
    try (BufferedReader r = FileUtil.getReader(new File(args[0]));
        DataOutputStream w = new DataOutputStream(new FileOutputStream(args[1]))) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        pack(line, w);
        c++;
      }
    }
    Log.info("done, converted " + c + " lines");
  }

}
