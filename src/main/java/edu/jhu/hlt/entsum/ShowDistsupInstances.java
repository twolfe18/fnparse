package edu.jhu.hlt.entsum;

import java.io.File;

import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.prim.tuple.Pair;

public class ShowDistsupInstances {
  
  public static String sigScore(double score) {
    if (score > 0.9)
      return "***";
    if (score > 0.75)
      return "**";
    if (score > 0.5)
      return "*";
    return "";
  }

  public static String sigCost(double cost) {
    if (cost < 0.25)
      return "***";
    if (cost < 0.5)
      return "**";
    if (cost < 1)
      return "*";
    return "";
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);

//    File ed = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/train/m.025k5p");
//    File ed = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev/m.01c49s"); // Ken Ham
//    File ed = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev/m.024shx");   // Lynn_Woolsey
    File ed = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev/m.0148vr");   // Rainier III of Monaco
    File loc = new File(ed, "infobox-binary/unlab.location");
    File x = new File(ed, "infobox-binary/unlab.x");
    File yhat = new File(ed, "infobox-binary/unlab.yhat");
    File conll = new File(ed, "parse.conll");
    File mentions = new File(ed, "mentionLocs.txt");
    boolean ldf = false;
    MultiAlphabet a = new MultiAlphabet();
    
    // Lets to top-k for now
    int k = config.getInt("k", 32);
    Beam<Pair<EffSent, VwInstance>> b = Beam.getMostEfficientImpl(k);
    
    Log.info("finding the " + k + " highest scoring predictions");
    try (VwInstanceReader verbs = new VwInstanceReader(loc, x, yhat, ldf);
        EffSent.Iter sents = new EffSent.Iter(conll, mentions, a);
        VwInstanceEffSentReader iter = new VwInstanceEffSentReader(verbs, sents)) {
      
      while (iter.hasNext()) {
        Pair<EffSent, VwInstance> p = iter.next();
        b.push(p, p.get2().maxScore());
      }
    }
    
    Log.info("showing the " + k + " highest scoring predictions");
    int m = config.getInt("m", 5);
    int i = 0;
    while (b.size() > 0) {
      Pair<EffSent, VwInstance> p = b.pop();
      EffSent sent = p.get1();
      VwInstance fact = p.get2();
      
      System.out.println(++i + "th best extraction:");
      sent.showChunkedStyle(a);
      System.out.println("subj: " + sent.mention(fact.loc.subjMention).show(sent.parse(), a));
      System.out.println("obj: " + sent.mention(fact.loc.objMention).show(sent.parse(), a));
      for (Pair<String, Double> v : fact.getMostLikelyLabels(m)) {
        String sig = sigScore(v.get2());
        System.out.printf("\t%-20s %.2f sig=%s\n", v.get1(), v.get2(), sig);
      }
      System.out.println();
    }
    
    Log.info("done");
  }
}
