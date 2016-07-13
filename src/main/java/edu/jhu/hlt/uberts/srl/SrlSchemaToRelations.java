package edu.jhu.hlt.uberts.srl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.google.common.collect.Iterators;

import edu.jhu.hlt.fnparse.data.FileFrameInstanceProvider;
import edu.jhu.hlt.fnparse.data.FrameIndex;
import edu.jhu.hlt.fnparse.datatypes.FNTagging;
import edu.jhu.hlt.fnparse.datatypes.Frame;
import edu.jhu.hlt.fnparse.datatypes.FrameInstance;
import edu.jhu.hlt.fnparse.datatypes.Sentence;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.mit.jwi.item.IWord;

/**
 * Writes out special relations to disk such as:
 *   frameTriage2(lemma, frame)
 *   role2(frame, role)
 *
 * @author travis
 */
public class SrlSchemaToRelations {

  // role2 can be a small relation if you're willing to use rules like:
  // predicate2(t,f) & coarsenFrame2(f,fc) & role2(fc,k) => srl3(t,f,k)
  public static boolean ROLE2_COARSE_FRAMES = false;

  public static String norm(String x) {
    return FNParseToRelations.norm(x);
  }

  private static String coarsenFrame(String frame) {
    if (!ROLE2_COARSE_FRAMES)
      return frame;
    if (frame.startsWith("propbank/")) {
      int c = frame.lastIndexOf('-');
      String coarse = frame.substring(0, c);
      return coarse;
    } else if (frame.startsWith("framenet/")) {
      return frame;
    } else {
      throw new RuntimeException("unknown frame: " + frame);
    }
  }

  public static void buildCoarsenFrame(File to) throws IOException {
    Log.info("building coarsenFrame2...");
    Set<String> uniq = new HashSet<>();
    try (BufferedWriter w = FileUtil.getWriter(to)) {
      w.write("def coarsenFrame2 <frame> <frame>");
      w.newLine();
      for (FrameIndex fi : Arrays.asList(
          FrameIndex.getPropbank(),
          FrameIndex.getFrameNet())) {
        for (Frame f : fi.allFrames()) {
          if (f == Frame.nullFrame)
            continue;
          String fine = f.getName();
          String coarse = coarsenFrame(fine);
          String line = "schema coarsenFrame2 " + norm(fine) + " " + norm(coarse);
          if (uniq.add(line)) {
            w.write(line);
            w.newLine();
          }
        }
      }
    }
  }

  public static void buildFrameTriageRelationPB(FrameIndex fi, BufferedWriter w, Set<String> seen) throws IOException {
    Log.info("building frameTriage4...");
    for (Frame f : fi.allFrames()) {
      if (f == Frame.nullFrame)
        continue;
      // e.g. f.getName() == "propbank/FedEx-v-1"
      int s = f.getName().indexOf('/');
      int d1 = f.getName().lastIndexOf('-');
      int d2 = f.getName().lastIndexOf('-', d1-1);
      String frame = norm(f.getName());
      String lemmaStr = norm(f.getName().substring(s+1, d2));
      String pos = norm(f.getName().substring(d2+1, d1).toUpperCase());

      String senseString = f.getName().substring(d1+1);
      try {
        Integer.parseInt(senseString);
      } catch (Exception e) {
        assert senseString.equals("LV") : senseString;
      }

      IWord iw = Sentence.tryGetWnWord(lemmaStr, pos);
      String wnss = iw == null ? "nil" : norm(iw.getSynset().getID().toString());
      String l = "schema frameTriage4 " + lemmaStr + " " + pos + " " + wnss + " " + frame;
      if (seen.add(l)) {
        w.write(l);
        w.newLine();
      }
    }
  }

  public static void buildFrameTriageRelationFN(FrameIndex fi, BufferedWriter w, Set<String> seen) throws IOException {
    Log.info("building frameTriage4...");
    Iterator<FNTagging> itr = FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences();
    itr = Iterators.concat(itr, FileFrameInstanceProvider.fn15trainFIP.getParsedOrTaggedSentences());
    while (itr.hasNext()) {
      FNTagging t = itr.next();
      Sentence s = t.getSentence();
      s.lemmatize();
      for (FrameInstance fin : t.getFrameInstances()) {
        Span target = fin.getTarget();
        int ti = target.end - 1;
        String lemma = norm(s.getLemma(ti).toLowerCase());
        String pos = norm(s.getPos(ti).substring(0, 1).toUpperCase());
        String wnss = s.getWnWord(ti) == null ? "nil" : norm(s.getWnWord(ti).getSynset().getID().toString());
        String frame = norm(fin.getFrame().getName());
        String l = "schema frameTriage4 " + lemma + " " + pos + " " + wnss + " " + frame;
        if (seen.add(l)) {
          w.write(l);
          w.newLine();
        }
        String l2 = "schema frameTriage4-full " + lemma + " " + pos + " " + wnss + " " + frame;
        if (seen.add(l2)) {
          w.write(l2);
          w.newLine();
        }
      }
    }
    // The "full lexicon" is the lexicon over the train and test split.
    // Just the training data is called the "Semafor lexicon" in
    // http://www.aclweb.org/anthology/P14-1136
    itr = FileFrameInstanceProvider.fn15testFIP.getParsedOrTaggedSentences();
    while (itr.hasNext()) {
      FNTagging t = itr.next();
      Sentence s = t.getSentence();
      s.lemmatize();
      for (FrameInstance fin : t.getFrameInstances()) {
        Span target = fin.getTarget();
        int ti = target.end - 1;
        String lemma = norm(s.getLemma(ti).toLowerCase());
        String pos = norm(s.getPos(ti).substring(0, 1).toUpperCase());
        String wnss = s.getWnWord(ti) == null ? "nil" : norm(s.getWnWord(ti).getSynset().getID().toString());
        String frame = norm(fin.getFrame().getName());
        String l = "schema frameTriage4-full " + lemma + " " + pos + " " + wnss + " " + frame;
        if (seen.add(l)) {
          w.write(l);
          w.newLine();
        }
      }
    }
  }

  public static void buildRoleRelation(FrameIndex fi, BufferedWriter w, Set<String> uniq) throws IOException {
    Log.info("building role2...");
    for (Frame f : fi.allFrames()) {
      if (f == Frame.nullFrame)
        continue;
      String frame = norm(coarsenFrame(f.getName()));
      int K = f.numRoles();
      for (int k = 0; k < K; k++) {
        String ks = norm(f.getRole(k));
        String line = "schema role2 " + frame + " " + ks;
        if (uniq.add(line)) {
          w.write(line);
          w.newLine();
        }
      }
    }
  }

  /**
   * TODO Add support for SF schema? (Currently only does PB/FN).
   * Maybe don't do this: extract the instance data from Concrete and then write
   * a grammar which can be used by TypeInference.propagateValue to derive the
   * role schema.
   */
  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    boolean overwrite = config.getBoolean("overwrite", false);
    File outdir = config.getOrMakeDir("outdir");
    Log.info("outdir=" + outdir);

    File frameTriage = new File(outdir, "frameTriage4.rel.gz");
    if (frameTriage.isFile() && !overwrite)
      throw new RuntimeException("output exists and overwrite=false, " + frameTriage.getPath());

    File role = new File(outdir, "role2.rel.gz");
    if (role.isFile() && !overwrite)
      throw new RuntimeException("output exists and overwrite=false, " + role.getPath());

    File coarsenFrame = new File(outdir, "coarsenFrame2.rel.gz");
    if (coarsenFrame.isFile() && !overwrite)
      throw new RuntimeException("output exists and overwrite=false, " + coarsenFrame.getPath());

    try (BufferedWriter w = FileUtil.getWriter(frameTriage)) {
      Set<String> seen = new HashSet<>();
      // Relation definition
      w.write("def frameTriage4 <lemma> <pos> <synset> <frame>");
      w.newLine();
      w.write("def frameTriage4-full <lemma> <pos> <synset> <frame> # includes LU->frame mappings from the test set");
      w.newLine();
      buildFrameTriageRelationPB(FrameIndex.getPropbank(), w, seen);
      buildFrameTriageRelationFN(FrameIndex.getFrameNet(), w, seen);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    try (BufferedWriter w = FileUtil.getWriter(role)) {
      Set<String> seen = new HashSet<>();
      // Relation definition
      w.write("def role2 <frame> <role>");
      w.newLine();
      buildRoleRelation(FrameIndex.getPropbank(), w, seen);
      buildRoleRelation(FrameIndex.getFrameNet(), w, seen);
    }

    buildCoarsenFrame(coarsenFrame);

    Log.info("done");
  }
}
