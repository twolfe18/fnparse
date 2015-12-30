package edu.jhu.hlt.fnparse.features.precompute;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

/**
 * Filter the entries in a bialph to only include a set of templates from one
 * or more feature sets.
 *
 * @author travis
 */
public class BiAlphFilter {

  // Selectivity was added to he file format last, so it may not appear in all
  // text files. If you need to use an old text file, you can set this to true
  // and it will skip selectivity and set it to 1.
  public static boolean SKIP_SLECTIVITY = false;

  public static class Feature {
    public final double score, mi, hx, selectivity;
    public final int arity;
    public final int[] template_int;
    public final String[] template_str;
    public Feature(String line) {
      String[] ar = line.split("\t");
      int i = 0;
      score = Double.parseDouble(ar[i++]);
      mi = Double.parseDouble(ar[i++]);
      hx = Double.parseDouble(ar[i++]);
      if (SKIP_SLECTIVITY)
        selectivity = 1;
      else
        selectivity = Double.parseDouble(ar[i++]);
      arity = Integer.parseInt(ar[i++]);
      String[] is = ar[i++].split("\\*");
      template_str = ar[i++].split("\\*");
      assert is.length == template_str.length;
      template_int = new int[is.length];
      for (int j = 0; j < is.length; j++)
        template_int[j] = Integer.parseInt(is[j]);
    }
  }

  public static BitSet getTemplates(String... fileNames) throws IOException {
    File[] files = new File[fileNames.length];
    for (int i = 0; i < files.length; i++)
      files[i] = new File(fileNames[i]);
    return getTemplates(files);
  }
  public static BitSet getTemplates(File... files) throws IOException {
    BitSet b = new BitSet();
    for (File f : files) {
      Log.info("reading from " + f.getPath() + " b.card=" + b.cardinality());
      try (BufferedReader r = FileUtil.getReader(f)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          Feature feat = new Feature(line);
          for (int t : feat.template_int)
            b.set(t);
        }
      }
    }
    Log.info("done! b.card=" + b.cardinality());
    return b;
  }

  public static void filter(File inputAlphabet, File outputAlphabet, BitSet relevant) throws IOException {
    Log.info(inputAlphabet.getPath() + "  =>  " + outputAlphabet.getPath());
    int interval = 500000;
    int i = 0;
    int x = 0;
    try (BufferedReader r = FileUtil.getReader(inputAlphabet);
        BufferedWriter w = FileUtil.getWriter(outputAlphabet)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] ar = line.split("\t");
        int template = Integer.parseInt(ar[0]);
        if (relevant.get(template)) {
          w.write(line);
          w.newLine();
          x++;
        }
        if (i++ % interval == 0)
          System.out.print(" " + i + "(" + x + ")");
      }
    }
    System.out.println();
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    if (args.length < 3) {
      System.out.println("please provide:");
      System.out.println("1) an input bialph (or someting else which is tsv having the first column be a template)");
      System.out.println("2) an output bialph");
      System.out.println("3+) feature files");
      return;
    }
    SKIP_SLECTIVITY = config.getBoolean("skipSelectivity", false);
    File inA = new File(args[0]);
    File outA = new File(args[1]);
    BitSet relevant = getTemplates(Arrays.copyOfRange(args, 2, args.length));
    filter(inA, outA, relevant);
    Log.info("done");
  }
}
