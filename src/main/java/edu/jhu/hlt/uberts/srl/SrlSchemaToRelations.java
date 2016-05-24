package edu.jhu.hlt.uberts.srl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
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
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.mit.jwi.item.IWord;

/**
 * Writes out special relations to disk such as:
 *   frameTriage2(lemma, frame)
 *   role2(frame, role)
 *
 * TODO How to come up with lemma->[frame] for FN?
 *
 * @author travis
 */
public class SrlSchemaToRelations {

  public static void buildFrameTriageRelationPB(FrameIndex fi, File to) throws IOException {
    Set<String> seen = new HashSet<>();
    try (BufferedWriter w = FileUtil.getWriter(to)) {
      // Relation definition
      w.write("def frameTriage4 lemma pos synset frame");
      w.newLine();
      // Instances
      for (Frame f : fi.allFrames()) {
        String frame = f.getName();   // e.g. propbank/FedEx-v-1
        String[] ar = frame.split("\\W+");
        String lemmaStr = ar[1].toLowerCase();
        String pos = ar[2].toUpperCase();
        IWord iw = Sentence.tryGetWnWord(lemmaStr, pos);
        String wnss = iw == null ? "nil" : iw.getSynset().getID().toString();
        String l = "schema frameTriage4 " + lemmaStr + " " + pos + " " + wnss + " " + frame;
        if (seen.add(l)) {
          w.write(l);
          w.newLine();
        }
      }
    }
  }

  public static void buildFrameTriageRelationFN(FrameIndex fi, File to) throws IOException {
    Iterator<FNTagging> itr = FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences();
    itr = Iterators.concat(itr, FileFrameInstanceProvider.fn15trainFIP.getParsedOrTaggedSentences());
    Set<String> seen = new HashSet<>();
    try (BufferedWriter w = FileUtil.getWriter(to)) {
      // Relation definition
      w.write("def frameTriage4 lemma pos synset frame");
      w.newLine();
      // Instances
      while (itr.hasNext()) {
        FNTagging t = itr.next();
        Sentence s = t.getSentence();
        s.lemmatize();
        for (FrameInstance fin : t.getFrameInstances()) {
          Span target = fin.getTarget();
          int ti = target.end - 1;
          String lemma = s.getLemma(ti).toLowerCase();
          String pos = s.getPos(ti).substring(0, 1).toUpperCase();
          String wnss = s.getWnWord(ti) == null ? "nil" : s.getWnWord(ti).getSynset().getID().toString();
          String frame = fin.getFrame().getName();
          String l = "schema frameTriage4 " + lemma + " " + pos + " " + wnss + " " + frame;
          if (seen.add(l)) {
            w.write(l);
            w.newLine();
          }
        }
      }
    }
  }

  public static void buildRoleRelation(FrameIndex fi, File to) throws IOException {
//    NodeType frameNT = new NodeType("frame");
//    NodeType roleNT = new NodeType("role");
//    Relation role = new Relation("role", frameNT, roleNT);
//    Log.info("writing " + role + " to " + to.getPath());
    try (BufferedWriter w = FileUtil.getWriter(to)) {
      // Relation definition
//      w.write(role.getDefinitionString());
      w.write("def role2 <frame> <role>");
      w.newLine();
      // Instances
      for (Frame f : fi.allFrames()) {
        int K = f.numRoles();
//        HypNode fNode = new HypNode(frameNT, f.getName());
        for (int k = 0; k < K; k++) {
          String ks = f.getRole(k);
//          HypNode kNode = new HypNode(roleNT, ks);
//          HypNode[] tail = new HypNode[] { fNode, kNode };
//          HypEdge e = new HypEdge(role, null, tail);
//          w.write(e.getRelFileString("schema"));
          w.write("schema role2 " + f.getName() + " " + ks);
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
    boolean pb = config.getBoolean("propbank");

    boolean overwrite = config.getBoolean("overwrite", false);
    File outdir = config.getOrMakeDir("outdir");
    Log.info("propbank=" + pb + " outdir=" + outdir);

    File frameTriage = new File(outdir, "frameTriage.rel.gz");
    if (frameTriage.isFile() && !overwrite)
      throw new RuntimeException("output exists and overwrite=false, " + frameTriage.getPath());

    File role = new File(outdir, "role.rel.gz");
    if (role.isFile() && !overwrite)
      throw new RuntimeException("output exists and overwrite=false, " + role.getPath());

    FrameIndex fi = pb
        ? FrameIndex.getPropbank()
        : FrameIndex.getFrameNet();

    if (pb)
      buildFrameTriageRelationPB(fi, frameTriage);
    else
      buildFrameTriageRelationFN(fi, frameTriage);

    buildRoleRelation(fi, role);
  }
}
