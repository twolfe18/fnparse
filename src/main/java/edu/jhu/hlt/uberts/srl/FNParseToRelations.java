package edu.jhu.hlt.uberts.srl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse;
import edu.jhu.hlt.fnparse.datatypes.ConstituencyParse.Node;
import edu.jhu.hlt.fnparse.datatypes.DependencyParse;
import edu.jhu.hlt.fnparse.datatypes.FNParse;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.fnparse.util.ConcreteStanfordWrapper;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;

/**
 * Take {@link FNParse}s and write out the relations used by uberts/srl.
 *
 * @author travis
 */
public class FNParseToRelations {

  private void write(Sentence s, BufferedWriter w) throws IOException {
    int n = s.size();
    for (int i = 0; i < n; i++) {
      w.write("x word2 " + i + " " + s.getWord(i));
      w.newLine();
      w.write("x pos2 " + i + " " + s.getPos(i));
      w.newLine();
      w.write("x lemma2 " + i + " " + s.getLemma(i));
      w.newLine();
//      w.write("x shape2 " + i + " " + s.getShape(i));
//      w.newLine();
    }
    write(s.getBasicDeps(false), "basic", n, w);
    write(s.getCollapsedDeps(false), "col", n, w);
    write(s.getStanfordParse(false), "stanford", w);
  }

  private void write(DependencyParse deps, String name, int n, BufferedWriter w) throws IOException {
    if (deps == null) {
      Log.warn("skipping deps since they're not present: " + name);
      return;
    }
    String tn = "dsyn3-" + name;
    for (int d = 0; d < n; d++) {
      int g = deps.getHead(d);
      String l = deps.getLabel(d);
      w.write("x " + tn + " " + g + " " + d + " " + l);
      w.newLine();
    }
  }

  private void write(ConstituencyParse cons, String name, BufferedWriter w) throws IOException {
    if (cons == null) {
      Log.warn("skipping cons since they're not present: " + name);
      return;
    }
    String tn = "csyn3-" + name;
    for (Node n : cons.getAllConstituents()) {
      if (n.isLeaf()) {
        Log.info("skipping what should be LEXICAL leaf cons: " + n.getTag());
        continue;
      }
      Span s = n.getSpan();
      String t = n.getTag();
      w.write("x " + tn + " " + s.start + " " + s.end + " " + t);
      w.newLine();
    }
  }

  public void definitions(BufferedWriter w) throws IOException {
    w.write("def word2 <tokenIndex> <word>");
    w.newLine();
    w.write("def pos2 <tokenIndex> <pos>");
    w.newLine();
    w.write("def lemma2 <tokenIndex> <lemma>");
    w.newLine();
    w.write("def dsyn3-basic <tokenIndex> <tokenIndex> <edgeLabel> # gov token, dep token, edge label");
    w.newLine();
    w.write("def dsyn3-col <tokenIndex> <tokenIndex> <edgeLabel> # gov token, dep token, edge label");
    w.newLine();
    w.write("def csyn3-stanford <tokenIndex> <tokenIndex> <cfgLabel> # start token (inclusive), end token (exclusive), cfg label");
    w.newLine();
    w.write("def event1 <tokenIndex> <tokenIndex> # start token (inclusive), end token (exclusive)");
    w.newLine();
    w.write("def event2 <tokenIndex> <tokenIndex> <frame> # start token (inclusive), end token (exclusive), frame");
    w.newLine();
    w.write("def srl1 <tokenIndex> <tokenIndex> # arg start tok (inc), arg end tok (exc)");
    w.newLine();
    w.write("def srl2 <tokenIndex> <tokenIndex> <tokenIndex> <tokenIndex> # pred start tok (inc), pred end tok (exc), arg start tok (inc), arg end tok (exc)");
    w.newLine();
    w.write("def srl3 <tokenIndex> <tokenIndex> <frame> <tokenIndex> <tokenIndex> <roleLabel> # pred start tok (inc), pred end tok (exc), frame, arg start tok (inc), arg end tok (exc), role");
    w.newLine();
  }

  public void write(FNParse y, BufferedWriter w) throws IOException {
    // x:
    // word(i,t) pos2(i,t), lemma(i,t)
    // dsyn3-basic(g,d,l), dsyn3-col(g,d,l), dsyn3-colcc(g,d,l)
    // csyn3-stanford(i,j,l)
    write(y.getSentence(), w);
    // y:
    // event1(t), event2(t,f), srl1(s), srl2(t,s), srl3(t,s,k)
    for (FrameInstance fi : y.getFrameInstances()) {
      Span t = fi.getTarget();
      Frame f = fi.getFrame();
      String fs = f.getName();
      write(t, fs, w);
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        Span s = fi.getArgument(k);
        if (s == Span.nullSpan)
          continue;
        String ks = f.getFrameRole(k);
        write(t, fs, s, ks, w);
        for (Span cs : fi.getContinuationRoleSpans(k))
          write(t, fs, cs, ks + "/C", w);
        for (Span rs : fi.getReferenceRoleSpans(k))
          write(t, fs, rs, ks + "/R", w);
      }
    }
  }

  private void write(Span t, String f, BufferedWriter w) throws IOException {
//    String ts = t.start + ")" + t.end;
    String ts = t.start + " " + t.end;
    w.write("y event1 " + ts);
    w.newLine();
    w.write("y event2 " + ts + " " + f);
    w.newLine();
  }

  private void write(Span t, String f, Span s, String k, BufferedWriter w) throws IOException {
//    String ts = t.start + ")" + t.end;
//    String ss = s.start + ")" + s.end;
    String ts = t.start + " " + t.end;
    String ss = s.start + " " + s.end;
    w.write("y srl1 " + ss);
    w.newLine();
    w.write("y srl2 " + ts + " " + ss);
    w.newLine();
    w.write("y srl3 " + ts + " " + f + " " + ss + " " + k);
    w.newLine();
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties.init(args);
    // Just extract a single FNParse relation set for debugging
    Iterator<FNParse> itr = FileFrameInstanceProvider.fn15trainFIP.getParsedSentences();
    itr.next();
    FNParse y = itr.next();

    ConcreteStanfordWrapper csw = ConcreteStanfordWrapper.getSingleton(false);
    Sentence s = y.getSentence();
    s.setStanfordParse(csw.getCParse(s));
    s.setBasicDeps(csw.getBasicDParse(s));
    s.lemmatize();

    File output = new File("data/srl-reldata/srl-" + y.getId() + ".rel");
    FNParseToRelations fn2r = new FNParseToRelations();
    try (BufferedWriter w = FileUtil.getWriter(output)) {
      fn2r.definitions(w);
      fn2r.write(y, w);
    }
  }
}
