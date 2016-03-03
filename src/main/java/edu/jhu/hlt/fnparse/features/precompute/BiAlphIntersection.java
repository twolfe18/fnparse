package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;

import edu.jhu.hlt.fnparse.features.precompute.BiAlph.LineMode;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;

/**
 * Intersects two BiAlphs (4 column, just alphabet) sorted by string columns:
 *   i_{left} <=> S_{left}
 *   i_{right} <=> S_{right}
 *
 * Where i is a set of int indices for templates/features and S is a set of
 * strings for template/features.
 *
 * It produces a third BiAlph (6 column) which is the intersection of the input:
 *   subset(i_{left}) <=> S_{left} \cap S_{right} <=> subset(i_{right})
 *
 * NOTE: Make sure you set the locale: `LC_ALL=C sort -t '\t' -k 3,4 ...`,
 * and make sure that the `sort` command immediately follows `LC_ALL=C`.
 *
 * @author travis
 */
public class BiAlphIntersection {

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("please provide:");
      System.err.println("1) an input alph (will be newInt* in output)");
      System.err.println("2) an input alph (will be oldInt* in output)");
      System.err.println("3) an output bialph");
      return;
    }
    Log.info("input1=" + args[0]);
    Log.info("input2=" + args[1]);
    Log.info("output=" + args[2]);
    TimeMarker tm = new TimeMarker();
    String[] output = new String[6];
    int n1 = 1, n2 = 1, n3 = 0;
    try (BufferedReader r1 = FileUtil.getReader(new File(args[0]));
        BufferedReader r2 = FileUtil.getReader(new File(args[1]));
        BufferedWriter w = FileUtil.getWriter(new File(args[2]))) {
      BiAlph.Line l1 = new BiAlph.Line(r1.readLine(), LineMode.ALPH);
      BiAlph.Line l2 = new BiAlph.Line(r2.readLine(), LineMode.ALPH);
      while (!l1.isNull() && l2.isNull()) {
        int c = BiAlph.Line.BY_STRINGS.compare(l1, l2);
        if (c == 0) {
          // Same, in intersection
          n3++;
          output[0] = String.valueOf(l1.newIntTemplate);
          output[1] = String.valueOf(l1.newIntFeature);
          output[2] = l1.stringTemplate;
          output[3] = l1.stringFeature;
          output[4] = String.valueOf(l2.newIntTemplate);
          output[5] = String.valueOf(l2.newIntFeature);
          w.write(StringUtils.join("\t", output));
          w.newLine();
        } else if (c < 0) {
          n1++;
          l1.set(r1.readLine(), LineMode.ALPH);
        } else {
          n2++;
          l2.set(r2.readLine(), LineMode.ALPH);
        }
        if (tm.enoughTimePassed(15)) {
          Log.info(tm.secondsSinceFirstMark() + " seconds,"
              + " input1Lines=" + n1
              + " input2Lines=" + n2
              + " outputLines=" + n3);
        }
      }
    }
    Log.info("done in " + tm.secondsSinceFirstMark() + " seconds,"
        + " input1Lines=" + n1
        + " input2Lines=" + n2
        + " outputLines=" + n3);
  }
}
