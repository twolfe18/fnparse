package edu.jhu.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.tutils.InputStreamGobbler;
import edu.jhu.hlt.tutils.Log;

/**
 * Pays the cost of reading an entire model in every time you call parse
 * (which is excessively wasteful if say you're only parsing a sentence).
 *
 * TODO Expose batch processing via static function
 *
 * @author travis
 */
public class SlowParseyWrapper {
  
  /** http://ilk.uvt.nl/conll/#dataformat */
//  public static class ConllXCols {
    public static final int ID = 0;
    public static final int FORM = 1;
    public static final int LEMMA = 2;
    public static final int CPOSTAG = 3;
    public static final int POSTAG = 4;
    public static final int FEATS = 5;
    public static final int HEAD = 6;
    public static final int DEPREL = 7;
    public static final int PHEAD = 8;
    public static final int PDEPREL = 9;
//  }
  
  private File script;  // e.g. /home/travis/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh
  private String path;  // e.g. /home/travis/anaconda2/bin
  public boolean verbose = false;
  
  /**
   * @param script is a path to parsey-docker-wrapper.sh which uses CoNLL-X for stdin and stdout.
   * @param path should include the correct version of python which parsey likes.
   */
  public SlowParseyWrapper(File script, String path) {
    Log.info("using " + script.getPath());
    this.script = script;
    this.path = path;
  }
  
//  public Document parse(String[] tokens, MultiAlphabet a) throws IOException {
//    throw new RuntimeException("implement me");
//  }
  
  public List<String[]> parse(String... tokens) throws Exception {
    if (verbose)
      Log.info("parsing: " + Arrays.toString(tokens));
    
    // Write out conll to a tempfile for parsey to read
//    File t = File.createTempFile(this.getClass() + "-input-", ".conll");
//    try (BufferedWriter w = FileUtil.getWriter(t)) {
//      for (int i = 0; i < tokens.length; i++) {
//        w.write(String.format("%d\t%s\t_\t_\t_\t_\t_\t_", i+1, tokens[i]));
//        w.newLine();
//      }
//    }
    
    // Call parsey
    ProcessBuilder pb = new ProcessBuilder(script.getPath());
    pb.environment().put("PATH", path);
    Process p = pb.start();
    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));

    if (verbose)
      Log.info("starting stdout/stderr threads...");
    InputStreamGobbler stdout = new InputStreamGobbler(p.getInputStream());
    InputStreamGobbler stderr = new InputStreamGobbler(p.getErrorStream());
    stdout.start();
    stderr.start();

    if (verbose)
      Log.info("writing tokens...");
    for (int i = 0; i < tokens.length; i++) {
      w.write(String.format("%d\t%s\t_\t_\t_\t_\t_\t_", i+1, tokens[i]));
      w.newLine();
    }

    if (verbose)
      Log.info("closing/flushing...");
//    w.flush();
    w.close();

    if (verbose)
      Log.info("waiting...");
    int r = p.waitFor();
    assert r == 0;
    
    List<String> lines = stdout.getLines();
    if (verbose)
      Log.info("formatting output... n=" + lines.size() + " stderr=" + stderr.getLines());
    List<String[]> conll = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      String[] ar = lines.get(i).split("\t");
      if (lines.get(i).isEmpty() || ar.length == 0) {
        assert i == lines.size()-1;
      } else {
        conll.add(ar);
      }
    }
    
    return conll;
  }
  
  public static SlowParseyWrapper buildForLaptop() {
    File s = new File("/home/travis/code/concrete-parsey/scripts/parsey-docker-wrapper-local.sh");
    String path = "/home/travis/anaconda2/bin";
    SlowParseyWrapper p = new SlowParseyWrapper(s, path);
    return p;
  }
  
  public static void main(String[] args) throws Exception {
    SlowParseyWrapper p = buildForLaptop();
    for (String[] t : p.parse("the", "quick", "brown", "fox", "hopped", "over", "the", "fence", ".")) {
//      System.out.println(Arrays.toString(t));
      System.out.println(t[FORM] + "\t" + t[POSTAG] + "\t" + t[HEAD] + "\t" + t[DEPREL]);
    }
  }

}
