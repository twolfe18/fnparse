package edu.jhu.hlt.uberts.srl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.MentionArgument;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.tutils.ConcreteToDocument;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.TNode.GraphTraversalTrace;
import edu.jhu.hlt.uberts.TNode.TKey;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.transition.TransitionGenerator;
import edu.jhu.prim.tuple.Pair;

/**
 * Provides the event1(target) relation via pred-patt. Works in batch mode,
 * as in you provide a set of {@link Communication}s which have had pred-patt
 * run on them and this will read in the targets from disk.
 *
 * The simplest version (currently) will just add all potential targets as
 * {@link HypEdge}s immediately. I am not running pred-patt with any pos/dep
 * labels created by {@link Uberts} inference (they are assumed as offline
 * dependencies).
 *
 * In the future, if I need to control the rate at which new edges are added,
 * I can create a relation called sentence(i,s,e) where i is an index starting
 * at 0, s is the first token of the sentence, and e is the last. Or if each
 * document should have its own indices: sentence(i,d,s,e) where d is a doc id.
 *
 * @author travis
 */
public class PredPatt {

  // Name of tool in AnnotationMetadata for desired SituationMentionSet
  public static String PRED_PATT_TOOL_NAME = "pred-patt";

  static class Target {
    public static boolean SPANS_ARE_INCLUSIVE = true;
    public static boolean ADD_ARGS = false;
    String docId;
    Span location;
    List<Span> associatedArgs;  // null if args haven't been extracted
    public Target(String docId, Span location) {
      this.docId = docId;
      this.location = location;
      if (ADD_ARGS)
        associatedArgs = new ArrayList<>();
    }
    public boolean matches(int lastTaggedTokenIndex) {
      if (SPANS_ARE_INCLUSIVE)
        return location.end == lastTaggedTokenIndex;
     return location.end - 1 == lastTaggedTokenIndex;
    }
  }

  // targets produced by pred-patt and read-in from given Communication file
  private List<Target> targets;

  // Graph elements created by this class (constructor)
  private NodeType preds;       // head(event1)
  private Relation event1;      // event1(startTokenInclusive, endTokenExclusive)

  /**
   * @param communicationTarGzFile is a .tar.gz file which pred-patt has been
   * run on, containing {@link SituationMention}s 
   * @throws IOException 
   */
  public PredPatt(Uberts u, File communicationTarGzFile) throws IOException {
    // Read the args from Communications on disk
    this(u, readTargets(communicationTarGzFile));
  }

  public PredPatt(Uberts u, List<Target> targets) {
    this.targets = targets;

    // Setup simple TransitionGenerator which (maybe) adds the Target which ends
    // at the last POS tag added.
    NodeType tokenIndex = u.lookupNodeType("tokenIndex", false);
    this.event1 = u.addEdgeType(new Relation("event1", tokenIndex, tokenIndex));
    this.preds = u.getWitnessNodeType(event1);
    TKey[] newPosTagAfterWord = new TKey[] {
        new TKey(u.getEdgeType("pos")),
        new TKey(tokenIndex),
//        new TKey(u.getEdgeType("word")),
    };
    Log.info("adding TransitionGenerator for " + event1);
    u.addTransitionGenerator(newPosTagAfterWord, new TransitionGenerator() {
      @Override
      public Iterable<Pair<HypEdge, Adjoints>> generate(GraphTraversalTrace lhsValues) {
        HypEdge posTagging = lhsValues.getBoundEdge(0);
        assert posTagging.getNumTails() == 2;
        int i = (Integer) posTagging.getTail(0).getValue();
//        String tag = (String) posTagging.getTail(1).getValue();
        Log.info("call on " + i + "\t" + lhsValues);
        if (targets.size() > 100)
          throw new RuntimeException("TODO: build index for this");
        List<Pair<HypEdge, Adjoints>> edges = new ArrayList<>();
//        int position = 0;
        for (Target t : targets) {
//          position++;
          if (t.matches(i)) {
            Log.info("match on " + i);
            int s = t.location.start;
            int e = t.location.end;
            if (!Target.SPANS_ARE_INCLUSIVE)
              e++;
            HypEdge ed = u.makeEdge(event1,
                u.lookupNode(tokenIndex, s),
                u.lookupNode(tokenIndex, e));
//            Adjoints score = new Adjoints.Constant(1d / Math.sqrt(position));
            Adjoints score = Adjoints.Constant.ONE;
            edges.add(new Pair<>(ed, score));
          }
        }
        return edges;
      }
    });

    Log.info("done");
  }

  public static List<Target> readTargets(File communicationTarGzFile) throws IOException {
    Log.info("reading pred-patt targets from " + communicationTarGzFile.getPath());
    List<Target> targets = new ArrayList<>();
    try (InputStream is = FileUtil.getInputStream(communicationTarGzFile)) {
      TarGzArchiveEntryCommunicationIterator itr = new TarGzArchiveEntryCommunicationIterator(is);
      while (itr.hasNext()) {
        Communication c = itr.next();
        addTargets(c, targets);
      }
    }
    Log.info("read in " + targets.size() + " targets");
    return targets;
  }

  public static void addTargets(Communication c, List<Target> addTo) {
    SituationMentionSet sms = ConcreteToDocument.findByTool(c.getSituationMentionSetList(), PRED_PATT_TOOL_NAME);
    for (SituationMention sm : sms.getMentionList()) {
      TokenRefSequence trs = sm.getTokens();
      Target t = new Target(
          c.getId(),
          Span.getSpan(trs, Target.SPANS_ARE_INCLUSIVE));
      if (Target.ADD_ARGS) {
        for (MentionArgument ma : sm.getArgumentList())
          t.associatedArgs.add(Span.getSpan(ma.getTokens(), Target.SPANS_ARE_INCLUSIVE));
      }
      addTo.add(t);
    }
  }

  public NodeType getPredWitness() {
    return preds;
  }
  public Relation getEvent1Relation() {
    return event1;
  }
  public int getNumTargets() {
    return targets.size();
  }
  public Target getTarget(int i) {
    return targets.get(i);
  }

}
