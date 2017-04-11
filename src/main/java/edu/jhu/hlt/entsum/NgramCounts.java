package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.simpleaccumulo.TimeMarker;
import edu.jhu.hlt.concrete.util.CommunicationUtils;
import edu.jhu.hlt.ikbp.tac.IndexCommunications;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.util.CountMinSketch.StringCountMinSketch;
import edu.jhu.util.ConcreteUtil;
import edu.jhu.util.TokenizationIter;

public class NgramCounts extends StringCountMinSketch {
  private static final long serialVersionUID = 968661768611576968L;

  public NgramCounts(int nHash, int logCountersPerHash) {
    super(nHash, logCountersPerHash, true);
  }
  
  public static String join(String... words) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < words.length; i++) {
      sb.append(i);
      sb.append(words[i]);
    }
    return sb.toString();
  }

  public int getCount(String... words) {
    return super.apply(join(words), false);
  }
  
  public int increment(String... words) {
    return super.apply(join(words), true);
  }
  
  public void addCountsFromConcreteTgz(File f, int n) throws IOException {
    try (IndexCommunications.FileBasedCommIter iter = new IndexCommunications.FileBasedCommIter(Arrays.asList(f))) {
      while (iter.hasNext()) {
        Communication c = iter.next();
        addCountsFromCommunication(c, n);
      }
    }
  }
  
  public void addCountsFromCommunication(Communication c, int n) {
    int maxSentenceLength = 80;
    for (Tokenization t : new TokenizationIter(c, maxSentenceLength)) {
      List<Token> toks = t.getTokenList().getTokenList();
      String[] words = new String[toks.size()];
      for (int i = 0; i < words.length; i++)
        words[i] = toks.get(i).getText();
      for (int i = 0; i < words.length-n; i++)
        increment(Arrays.copyOfRange(words, i, i+n));
    }
  }
  
  public static void main(String[] args) throws Exception {
    File parent = new File("/home/travis/code/data/fetch-comms-cache");
    File out = new File("data/facc1-entsum/code-testing-data/bigram-counts.nhash11-logb22.jser");
    File out2 = new File("data/facc1-entsum/code-testing-data/bigram-counts.nhash16-logb25.jser");
    NgramCounts ng = new NgramCounts(12, 21);
    NgramCounts ng2 = new NgramCounts(20, 24);
    int n = 2;
    List<String[]> sample = Arrays.asList(
        new String[] {"in", "the"},
        new String[] {"the", "thing"},
        new String[] {"he", "said"},
        new String[] {"she", "said"},
        new String[] {"Barack", "Obama"},
        new String[] {"Hussein", "Obama"},
        new String[] {"George", "Bush"},
        new String[] {"Bush", "George"},
        new String[] {"unladen", "swallow"},
        new String[] {"Apocalypse", "Now"},
        new String[] {"three", "word", "phrase"},
        new String[] {"King", "George"},
        new String[] {"give", "up"},
        new String[] {"Apple", "released"},
        new String[] {"time", "travel"},
        new String[] {"all", "of"},
        new String[] {"very", "good"},
        new String[] {"lsakjf;ldskjf", "zz"},
        new String[] {"John", "Smith"},
        new String[] {"loves", "Mary"},
        new String[] {"fast", "cats"},
        new String[] {"New", "York"},
        new String[] {"NEW", "YORK"});
    TimeMarker tm = new TimeMarker();
    TimeMarker tm2 = new TimeMarker();
    File[] fs = parent.listFiles();
    Log.info("found " + fs.length + " files to read through");
    int nf = 0;
    for (File f : fs) {
      Communication c = ConcreteUtil.readOneComm(f);
      ng.addCountsFromCommunication(c, n);
      ng2.addCountsFromCommunication(c, n);
      nf++;
        
      if (tm.enoughTimePassed(5)) {
        Log.info("examples:");
        for (String[] ex : sample) {
//          System.out.printf("%-32s % 5d\n", Arrays.asList(ex), ng.getCount(ex));
          int c1 = ng.getCount(ex);
          int c2 = ng2.getCount(ex);
          System.out.printf("%-32s % 6d  % 6d  %d\n", Arrays.asList(ex), c1, c2, c1 - c2);
        }
        System.out.println();

        if (tm2.enoughTimePassed(10 * 60)) {
          Log.info("saving to " + out.getPath() + " nf=" + nf + " n=" + ng.numIncrements());
          System.out.print("*");
          FileUtil.serialize(ng, out);
          System.out.print("*");
          FileUtil.serialize(ng2, out2);
          System.out.println("*");
        }
      }
    }
    Log.info("done");
  }
}
