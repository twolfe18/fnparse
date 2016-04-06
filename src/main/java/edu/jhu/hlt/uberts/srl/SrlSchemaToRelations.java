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
import edu.jhu.prim.tuple.Pair;

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
    NodeType lemma = new NodeType("lemma");
    NodeType frame = new NodeType("frame");
    Relation frameTriage2 = new Relation("frameTriage2", lemma, frame);
    Log.info("writing " + frameTriage2 + " to " + to.getPath());
    try (BufferedWriter w = FileUtil.getWriter(to)) {
      // Relation definition
      w.write(frameTriage2.getDefinitionString());
      w.newLine();
      // Instances
      for (Frame f : fi.allFrames()) {
        String lemmaStr = f.getName().split("\\W+")[1];
        HypNode lemmaNode = new HypNode(lemma, lemmaStr);
        HypNode frameNode = new HypNode(frame, f.getName());
        HypNode[] tail = new HypNode[] { lemmaNode, frameNode };
        HypEdge e = new HypEdge(frameTriage2, null, tail);
        w.write(e.getRelFileString("schema"));
        w.newLine();
      }
    }
  }

  public static void buildFrameTriageRelationFN(FrameIndex fi, File to) throws IOException {
    NodeType lemmaNT = new NodeType("lemma");
    NodeType frameNT = new NodeType("frame");
    Relation frameTriage2 = new Relation("frameTriage2", lemmaNT, frameNT);
    Log.info("writing " + frameTriage2 + " to " + to.getPath());
    Iterator<FNTagging> itr = FileFrameInstanceProvider.fn15lexFIP.getParsedOrTaggedSentences();
    itr = Iterators.concat(itr, FileFrameInstanceProvider.fn15trainFIP.getParsedOrTaggedSentences());
    Set<Pair<String, String>> seenLemmaFrame = new HashSet<>();
    try (BufferedWriter w = FileUtil.getWriter(to)) {
      // Relation definition
      w.write(frameTriage2.getDefinitionString());
      w.newLine();
      // Instances
      while (itr.hasNext()) {
        FNTagging t = itr.next();
        Sentence s = t.getSentence();
        s.lemmatize();
        for (FrameInstance fin : t.getFrameInstances()) {
          Span target = fin.getTarget();
          String lemma = s.getLemma(target.end - 1);
          String frame = fin.getFrame().getName();
          if (seenLemmaFrame.add(new Pair<>(lemma, frame))) {
            HypNode lemmaNode = new HypNode(lemmaNT, lemma);
            HypNode frameNode = new HypNode(frameNT, frame);
            HypNode[] tail = new HypNode[] { lemmaNode, frameNode };
            HypEdge e = new HypEdge(frameTriage2, null, tail);
            w.write(e.getRelFileString("schema"));
            w.newLine();
          }
        }
      }
    }
  }

  public static void buildRoleRelation(FrameIndex fi, File to) throws IOException {
    NodeType frameNT = new NodeType("frame");
    NodeType roleNT = new NodeType("roleLabel");
    Relation role = new Relation("role", frameNT, roleNT);
    Log.info("writing " + role + " to " + to.getPath());
    try (BufferedWriter w = FileUtil.getWriter(to)) {
      // Relation definition
      w.write(role.getDefinitionString());
      w.newLine();
      // Instances
      for (Frame f : fi.allFrames()) {
        int K = f.numRoles();
        HypNode fNode = new HypNode(frameNT, f.getName());
        for (int k = 0; k < K; k++) {
          String ks = f.getRole(k);
          HypNode kNode = new HypNode(roleNT, ks);
          HypNode[] tail = new HypNode[] { fNode, kNode };
          HypEdge e = new HypEdge(role, null, tail);
          w.write(e.getRelFileString("schema"));
          w.newLine();
        }
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    boolean pb = config.getBoolean("propbank");
    String ds = pb ? "propbank" : "framenet";
    Log.info("propbank=" + pb);

    boolean overwrite = config.getBoolean("overwrite", false);
    File outdir = config.getOrMakeDir("outdir");

    File frameTriage = new File(outdir, "frameTriage2." + ds + ".rel");
    if (frameTriage.isFile() && !overwrite)
      throw new RuntimeException("output exists and overwrite=false, " + frameTriage.getPath());

    File role = new File(outdir, "role2." + ds + ".rel.gz");
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
