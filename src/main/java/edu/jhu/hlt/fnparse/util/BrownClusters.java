package edu.jhu.hlt.fnparse.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper around the output of Percy Liang's brown clustering code output
 * @author travis
 */
public class BrownClusters {

  private Map<String, String> word2path;

  public BrownClusters(File pathToPercyOutput) {
    if (!pathToPercyOutput.isDirectory()) {
      throw new IllegalArgumentException(
          "should give a path that contains a file called \"path\"");
    }
    File pathFile = new File(pathToPercyOutput, "paths");
    word2path = new HashMap<>();
    try {
      BufferedReader r = new BufferedReader(
          new InputStreamReader(new FileInputStream(pathFile)));
      while (r.ready()) {
        String[] toks = r.readLine().split("\t");
        String path = toks[0];
        String word = toks[1];
        //int frequency = Integer.parseInt(toks[2]);
        String old = word2path.put(word, path);
        assert old == null;
      }
      r.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public String getPath(String word) {
    return getPath(word, Integer.MAX_VALUE);
  }

  public String getPath(String word, int maxLen) {
    String p = word2path.get(word);
    if (p == null)
      return unknown(maxLen);
    if (p.length() > maxLen)
      return p.substring(0, maxLen);
    return p;
  }

  private static String unknown(int length) {
    if (length == 1) return "?";
    if (length == 2) return "??";
    if (length == 3) return "???";
    if (length == 4) return "????";
    if (length == 5) return "?????";
    if (length == 6) return "??????";
    if (length == 7) return "???????";
    if (length == 8) return "????????";
    if (length == 9) return "?????????";
    if (length == 10) return "?????????";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++)
      sb.append("?");
    return sb.toString();
  }

  private static BrownClusters bc256, bc1000;

  public static BrownClusters getBc256() {
    if (bc256 == null) {
      bc256 = new BrownClusters(new File(
          "data/embeddings/bc_out_256/full.txt_en_256/"));
    }
    return bc256;
  }

  public static BrownClusters getBc1000() {
    if (bc1000 == null) {
      bc1000 = new BrownClusters(new File(
          "data/embeddings/bc_out_1000/full.txt_en_1000/bc/"));
    }
    return bc1000;
  }
}
