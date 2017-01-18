package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Range;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import edu.jhu.hlt.concrete.AnnotationMetadata;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.concrete.Dependency;
import edu.jhu.hlt.concrete.DependencyParse;
import edu.jhu.hlt.concrete.EntityMention;
import edu.jhu.hlt.concrete.EntityMentionSet;
import edu.jhu.hlt.concrete.MentionArgument;
import edu.jhu.hlt.concrete.Section;
import edu.jhu.hlt.concrete.Sentence;
import edu.jhu.hlt.concrete.SituationMention;
import edu.jhu.hlt.concrete.SituationMentionSet;
import edu.jhu.hlt.concrete.TaggedToken;
import edu.jhu.hlt.concrete.Token;
import edu.jhu.hlt.concrete.TokenRefSequence;
import edu.jhu.hlt.concrete.TokenTagging;
import edu.jhu.hlt.concrete.Tokenization;
import edu.jhu.hlt.concrete.UUID;
import edu.jhu.hlt.concrete.access.FetchCommunicationService;
import edu.jhu.hlt.concrete.access.FetchRequest;
import edu.jhu.hlt.concrete.access.FetchResult;
import edu.jhu.hlt.concrete.serialization.iterators.TarGzArchiveEntryCommunicationIterator;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumulo;
import edu.jhu.hlt.concrete.simpleaccumulo.SimpleAccumuloConfig;
import edu.jhu.hlt.fnparse.features.Path.EdgeType;
import edu.jhu.hlt.fnparse.features.Path.NodeType;
import edu.jhu.hlt.fnparse.features.Path2;
import edu.jhu.hlt.fnparse.inference.pruning.TargetPruningData;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.CreateParmaTrainingData;
import edu.jhu.hlt.ikbp.DataUtil;
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.features.ConcreteMentionFeatureExtractor;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.AccumuloIndex.WeightedFeature;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.ParmaVw.QResultCluster;
import edu.jhu.hlt.ikbp.tac.StringIntUuidIndex.StrIntUuidEntry;
import edu.jhu.hlt.ikbp.tac.TacKbp.KbpQuery;
import edu.jhu.hlt.tutils.ArgMax;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.EfficientUuidList;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.IntTrip;
import edu.jhu.hlt.tutils.LabeledDirectedGraph;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.OrderStatistics;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringInt;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.TokenObservationCounts;
import edu.jhu.hlt.tutils.ling.DParseHeadFinder;
import edu.jhu.hlt.utilt.AutoCloseableIterator;
import edu.jhu.prim.map.IntDoubleHashMap;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.util.Lambda.FnIntFloatToFloat;
import edu.jhu.prim.vector.IntFloatUnsortedVector;
import edu.jhu.prim.vector.IntIntHashVector;
import edu.jhu.util.DiskBackedFetchWrapper;
import edu.jhu.util.SlowParseyWrapper;
import edu.jhu.util.TokenizationIter;
import edu.mit.jwi.IRAMDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.item.POS;

/**
 * Produces an index of a given Concrete corpus. Writes everything to TSVs.
 *
 * @author travis
 */
public class IndexCommunications implements AutoCloseable {

//  public static final File HOME = new File("data/concretely-annotated-gigaword/ner-indexing/2016-11-18");
//  public static final File HOME = new File("data/concretely-annotated-gigaword/ner-indexing/nyt_eng_2007-fromCOE"); // disk
  public static final File HOME = new File("data/concretely-annotated-gigaword/ner-indexing/nyt_eng_2007-fromCOE-scion");
//  public static final File HOME = new File("data/concretely-annotated-gigaword/ner-indexing/apw_eng_2XXX-scion");

  public static final MultiTimer TIMER = AccumuloIndex.TIMER;
  public static final Counts<String> EC = AccumuloIndex.EC;
  
  // How many words to store for every document
  public static final int N_DOC_TERMS = 128;    // don't change this!

  public static int err_head = 0;
  public static int err_ner = 0;
  public static int err_misc = 0;
  private static long n_doc = 0, n_tok = 0, n_ent = 0, n_termWrites = 0, n_termHashes = 0;

  // May be used by any class in this module, but plays no role by default
  static IntObjectHashMap<String> INVERSE_HASH = null;


  enum DataProfile {
    CAG_TINY {
      @Override
      public boolean keep(String commId) {
        return commId.toUpperCase().startsWith("NYT_ENG_200909");
      }
      @Override
      public Optional<Pair<String, String>> commIdBoundaries() {
        return Optional.of(
            new Pair<>(
                "NYT_ENG_20090901.0001",
                "NYT_ENG_20090931.9999"));
      }
    },
    CAG_SMALL {
      @Override
      public boolean keep(String commId) {
        return commId.toUpperCase().startsWith("NYT_ENG_2007");
      }
      @Override
      public Optional<Pair<String, String>> commIdBoundaries() {
        return Optional.of(
            new Pair<>(
                "NYT_ENG_20070101.0001",
                "NYT_ENG_20071231.9999"));
      }
    },
    CAG_MEDIUM {
      @Override
      public boolean keep(String commId) {
        return commId.toUpperCase().startsWith("APW_ENG_2");
      }
      @Override
      public Optional<Pair<String, String>> commIdBoundaries() {
        return Optional.of(
            new Pair<>(
                "APW_ENG_20000101.0001",
                "APW_ENG_29991231.9999"));
      }
    },
    CAG_FULL {
      @Override
      public boolean keep(String commId) {
        return true;
      }
    };

    public abstract boolean keep(String commId);

    public Optional<Pair<String, String>> commIdBoundaries() {
      return Optional.empty();
    }
  }

  
  /**
   * Determines whether two {@link SitSearchResult}s are duplicates by looking at
   * all pairs of entities and situations which appear in pairs of results.
   *
   * Proof of concept to show how to train/predict with the features
   * implemented in {@link CreateParmaTrainingData}.
   * 
   * Models are trained in with VW in data/parma/training_files/Makefile
   */
  public static class ParmaVw implements AutoCloseable {
    private ConcreteMentionFeatureExtractor featEx;
    private VwWrapper classifier;
    
    // These are the tool names which generated the entity/situation
    // mentions which are being featurized.
    private String situationMentionToolName;
    private String entityMentionToolName;
    
    /** @deprecated in accumulo pipeline we use strings not hashes */
    private IntDoubleHashMap idf;
    
    private boolean verbose = false;
    public void verbose(boolean setTo) {
      verbose = setTo;
      featEx.verbose = setTo;
    }
    
    /**
     * @param modelFile should be trained by VW,
     * e.g. data/parma/training_files/ecbplus/model_neg4.vw
     * see data/parma/training_files/Makefile
     */
    public ParmaVw(File modelFile, IntDoubleHashMap idf, ExperimentProperties config) throws IOException {
      this(modelFile, idf, null, config.getString("entTool", "Stanford Coref"), config.getInt("vw.port", 8094));
    }
    
    public ParmaVw(File modelFile, IntDoubleHashMap idf, String sitMentionTool, String entityToolName, int port) throws IOException {
      this.idf = idf;

      // Problem is this:
      // when I pass in "Stanford Coref" for the situationMentionTool it tries to add to a (em:UUID <=> tutils.Document.Constituent) bijection
      // on the side, ConcreteMentionFeatureExtractor also has readConcreteStanford (for coref), which also adds em:UUID mappings, but with other cons, thus breaking the bijection
      
      // Question 1: Why are we reading concrete-stanford input in ConcreteMentionFeatureExtractor?
      
      this.situationMentionToolName = sitMentionTool;
      this.entityMentionToolName = entityToolName;
      
      this.featEx = new ConcreteMentionFeatureExtractor(
          situationMentionToolName, null, Collections.emptyList());
//          situationMentionToolName, entityMentionToolName, Collections.emptyList());
      
      try {
        classifier = new VwWrapper(modelFile, port);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    public void setIdf(IntDoubleHashMap idf) {
      this.idf = idf;
    }
    
    public boolean coref(SitSearchResult a, SitSearchResult b) {
      // TODO right now it computes features by UNIONING over all mentions
      // in either Tokenization, then computes score/prob of coreference.
      // PERHAPS I SHOULD compute the score for every pair and then combine in a principled manner.

//      // First, the tf-idf score for the two documents must exceed a threshold
//      if (a.featsResult != null && b.featsResult != null) {
//        double tfidfThresh = 0.7;
//        double sim = TermVec.tfidfCosineSim(a.featsResult.tfidf, b.featsResult.tfidf, idf);
//        if (verbose)
//          System.out.println("[coref] tfidfCosineSim=" + sim);
//        if (sim < tfidfThresh)
//          return false;
//        EC.increment("parma/tfidfThreshMet");
//      } else {
//        Log.info("no SentFeats, skipping tf-idf filtering!");
//      }
      
      TIMER.start("parmaVW/featEx");
      // Get features from a
      List<edu.jhu.hlt.ikbp.data.Id> aFeat = new ArrayList<>();
      edu.jhu.hlt.ikbp.data.Node aNode = convert(a);
      featEx.extractSafe(aNode, aFeat);
      
      // Get features from b
      List<edu.jhu.hlt.ikbp.data.Id> bFeat = new ArrayList<>();
      edu.jhu.hlt.ikbp.data.Node bNode = convert(b);
      featEx.extractSafe(bNode, bFeat);
      
      // Get pairwise features
      // I'm abusing featEx.pairwiseFeats a bit, normally it takes nodes
      // corresponding to a (situation|entity) mention, not a tokenization.
      // Here I call it repeatedly on all pairs.
      List<Id> aMentions = DataUtil.filterByFeatureType(aNode.getFeatures(), FeatureType.CONCRETE_UUID);
      List<Id> bMentions = DataUtil.filterByFeatureType(bNode.getFeatures(), FeatureType.CONCRETE_UUID);
      List<String> pws = new ArrayList<>();
      for (Id am : aMentions) {
        for (Id bm : bMentions) {
          // TODO Filter pairs which have the same mention type
          List<String> f = featEx.pairwiseFeats(am, bm);
          pws.addAll(f);
        }
      }
      List<Id> pw = CreateParmaTrainingData.upconvert(pws);
      
      String x = CreateParmaTrainingData.buildLine("0", "none", aFeat, bFeat, pw);
      TIMER.stop("parmaVW/featEx");
      double y = classifier.predict(x);
      if (verbose)
        System.out.println("[coref] y: " + y + " x: " + StringUtils.trim(x, 120));
      return y > 0;
    }
    
    public static SituationMention makeSingleTokenSm(int tokenIndex, String tokenizationUuid, String sitKind) {
      if (tokenIndex < 0)
        throw new IllegalArgumentException("tokenIndex=" + tokenIndex);
      SituationMention sm = new SituationMention();
      sm.setUuid(new UUID(""));
      sm.setArgumentList(Collections.emptyList());
      sm.setSituationKind(sitKind);
      sm.setSituationType("EVENT");
      sm.setTokens(new TokenRefSequence());
      sm.getTokens().setAnchorTokenIndex(tokenIndex);
      sm.getTokens().setTokenizationId(new UUID(tokenizationUuid));
      sm.getTokens().addToTokenIndexList(tokenIndex);
      return sm;
    }
    
    public static SituationMentionSet makeSingleMentionSms(SituationMention m, String toolname) {
      SituationMentionSet sms = new SituationMentionSet();
      sms.setUuid(new UUID(""));
      sms.setMetadata(new AnnotationMetadata()
          .setTimestamp(System.currentTimeMillis() / 1000)
          .setTool(toolname));
      sms.addToMentionList(m);
      return sms;
    }
    
    public static void addToOrCreateSitutationMentionSet(Communication c, SituationMention m, String toolname) {
      if (c.isSetSituationMentionSetList()) {
        for (SituationMentionSet sms : c.getSituationMentionSetList()) {
          if (sms.getMetadata().getTool().equals(toolname)) {
            sms.addToMentionList(m);
            return;
          }
        }
      }
      SituationMentionSet sms = makeSingleMentionSms(m, toolname);
      c.addToSituationMentionSetList(sms);
    }

    /**
     * Reads {@link SitSearchResult#yhatEntitySituation} and adds
     * a {@link SituationMention} and {@link SituationMentionSet}
     * to the given result's {@link Communication}.
     * 
     * @deprecated This doesn't work because we must add the {@link SituationMention}s
     * well before this method is called.
     */
    private edu.jhu.hlt.ikbp.data.Node convertAssumingGivenSituationToken(SitSearchResult a) {
      if (a.comm == null)
        throw new IllegalArgumentException();
      
      edu.jhu.hlt.ikbp.data.Node n = new edu.jhu.hlt.ikbp.data.Node();
      
      // Node's id is the Tokenization UUID of the result
      n.setId(new Id()
          .setType(FeatureType.CONCRETE_UUID.getValue())
          .setName(a.tokUuid));
      
      n.setFeatures(new ArrayList<>());
      
      SituationMention sm = makeSingleTokenSm(a.yhatEntitySituation, a.tokUuid, "treeDistIdfWeighting");
      SituationMentionSet sms = makeSingleMentionSms(sm, situationMentionToolName);
      
      // Check that there isn't already a SMS with conflicting name
      if (a.comm.isSetSituationMentionSetList()) {
        for (SituationMentionSet sms2 : a.comm.getSituationMentionSetList())
          assert !sms2.getMetadata().getTool().equals(situationMentionToolName);
      } else {
        a.comm.setSituationMentionSetList(new ArrayList<>());
      }
      // Add this new SMS to the comm
      a.comm.addToSituationMentionSetList(sms);
      
      return n;
    }
    
    /**
     * Finds all {@link EntityMention}s and {@link SituationMention}s which
     * appear in the {@link Tokenization} specified by the given {@link SitSearchResult}.
     */
    private edu.jhu.hlt.ikbp.data.Node convertAllPairs(SitSearchResult a) {
      if (a.comm == null)
        throw new IllegalArgumentException();
      
      if (verbose)
        Log.info("finding nodes for " + a.getWordsInTokenizationWithHighlightedEntAndSit());
      
      edu.jhu.hlt.ikbp.data.Node n = new edu.jhu.hlt.ikbp.data.Node();
      
      // Node's id is the Tokenization UUID of the result
      n.setId(new Id()
          .setType(FeatureType.CONCRETE_UUID.getValue())
          .setName(a.tokUuid));
      
      n.setFeatures(new ArrayList<>());
      
      // Add features for all pairs
      if (verbose)
        Log.info("checking EntityMention tool " + entityMentionToolName);
      int nEnt = 0;
      if (a.comm.isSetEntityMentionSetList() && entityMentionToolName != null) {
        for (EntityMentionSet ems : a.comm.getEntityMentionSetList()) {
          if (entityMentionToolName.equals(ems.getMetadata().getTool())) {
            for (EntityMention em : ems.getMentionList()) {
              String t = em.getTokens().getTokenizationId().getUuidString();
              if (!a.tokUuid.equals(t))
                continue;
              Id id = new Id()
                  .setType(FeatureType.CONCRETE_UUID.getValue())
                  .setName(em.getUuid().getUuidString());
              if (verbose) {
                Log.info("creating entity node, emUuid=" + em.getUuid().getUuidString()
                    + " from tool " + ems.getMetadata().getTool()
                    + " in comm=" + a.getCommunicationId());
              }
              n.addToFeatures(id);
              nEnt++;
            }
          }
        }
      }

      if (verbose)
        Log.info("checking SituationMention tool " + situationMentionToolName);
      int nSit = 0;
      if (a.comm.isSetSituationMentionSetList() && situationMentionToolName != null) {
        for (SituationMentionSet sms : a.comm.getSituationMentionSetList()) {
          if (situationMentionToolName.equals(sms.getMetadata().getTool())) {
            for (SituationMention sm : sms.getMentionList()) {
              String t = sm.getTokens().getTokenizationId().getUuidString();
              if (!a.tokUuid.equals(t))
                continue;
              Id id = new Id()
                  .setType(FeatureType.CONCRETE_UUID.getValue())
                  .setName(sm.getUuid().getUuidString());
              if (verbose) {
                Log.info("creating situation node smUuid=" + sm.getUuid().getUuidString()
                    + " from tool " + sms.getMetadata().getTool()
                    + " sm.trs=" + sm.getTokens()
                    + " in comm=" + a.getCommunicationId());
              }
              n.addToFeatures(id);
              nSit++;
            }
          }
        }
      }

      if (verbose)
        Log.info("nEnt=" + nEnt + " nSit=" + nSit + " for SitSearchResult " + a);
      return n;
    }
    
    private edu.jhu.hlt.ikbp.data.Node convert(SitSearchResult a) {
//      if (a.yhatEntitySituation >= 0)
//        return convertAssumingGivenSituationToken(a);
      return convertAllPairs(a);
    }
    
    private void setTopicForResults(List<SitSearchResult> results) {
      TIMER.start("parmaVW/setTopic");
      List<Communication> comms = new ArrayList<>();
      Map<String, Communication> assureNoCommDups = new HashMap<>();
      for (SitSearchResult r : results) {
        if (r.comm == null)
          throw new IllegalArgumentException();
        Communication cur = r.getCommunication();
        Communication old = assureNoCommDups.put(r.getCommunicationId(), cur);
        if (old == null) {
          comms.add(r.getCommunication());
        } else if (old != cur) {
          throw new RuntimeException("duplicate comms sharing an id! " + r.getCommunicationId());
        }
      }
      if (verbose)
        Log.info("setting topic, nComms=" + comms.size() + " nResults=" + results.size());
      
      
      // Feature extractor needs parsey, which may not be available,
      // fall back on stanford
      String sourceTool = "Stanford CoreNLP basic";
      String destTool = "parsey";
      boolean allowFail = false;
      for (Communication c : comms)
        ConcreteToolAliaser.DParse.copyIfNotPresent(c, sourceTool, destTool, allowFail);
      
      featEx.set(comms);

      if (verbose)
        Log.info("done, " + featEx.events);
      TIMER.stop("parmaVW/setTopic");
    }
    
    public static class QResultCluster {
      public final SitSearchResult canonical;
      private List<SitSearchResult> redundant;    // canonical doesn't appear in this list
      
      public QResultCluster(SitSearchResult canonical) {
        this.canonical = canonical;
        this.redundant = new ArrayList<>();
      }
      
      public void addRedundant(SitSearchResult r) {
        redundant.add(r);
      }
      
      public int numRedundant() {
        return redundant.size();
      }
      
      public SitSearchResult getRedundant(int i) {
        return redundant.get(i);
      }
    }

    /**
     * Resulting list contains (canonicalResult, otherResultsInSameCluster) entries.
     * The canonicalResult will not appear in the second list.
     * 
     * @param maxOutputClusters is an upper limit on how many outputs to produce,
     * set to 0 to remove the limit.
     */
    public List<QResultCluster> dedup(List<SitSearchResult> results, int maxOutputClusters) {
      if (results.isEmpty())
        return Collections.emptyList();
      TIMER.start("parmaVW/dedup");

      // Tell the feature extractor about the relevant Communications
      setTopicForResults(results);

      // Greedily take results, deduping as you go
      List<QResultCluster> d = new ArrayList<>();
      d.add(new QResultCluster(results.get(0)));
      int n = results.size();
      for (int i = 1; i < n; i++) {
        EC.increment("parma/mention");
        SitSearchResult b = results.get(i);
        boolean dup  = false;
        for (int j = 0; j < d.size() && !dup; j++) {
          QResultCluster m = d.get(j);
          boolean c = coref(m.canonical, b);
          dup |= c;
          if (c) {
            m.addRedundant(b);
            EC.increment("parma/dup");
            if (verbose) {
              int termCharLimit = 120;
              System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
              System.out.println("COREF:");
              boolean highlighVerbs = true;
              ShowResult.showQResult(b, b.comm, termCharLimit, highlighVerbs);
              System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
              ShowResult.showQResult(m.canonical, m.canonical.comm, termCharLimit, highlighVerbs);
              System.out.println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
            }
          }
        }
        if (!dup)
          d.add(new QResultCluster(b));
        
        if (maxOutputClusters > 0 && d.size() >= maxOutputClusters) {
          if (verbose)
            Log.info("stopping early at " + d.size() + " clusters");
          break;
        }
      }
      
      if (verbose)
        Log.info("filtered " + results.size() + " results down to " + d.size());

      TIMER.stop("parmaVW/dedup");
      return d;
    }

    @Override
    public void close() throws Exception {
      classifier.close();
    }
    
    /**
     * Trains and serializes a model.
     */
    public static void main(ExperimentProperties config) throws Exception {
      File modelFile = config.getFile("dedup.model");
      IntDoubleHashMap idf = null;
      try (ParmaVw p = new ParmaVw(modelFile, idf, config)) {
      }
    }
  }
  
  public static class MturkCorefHit {
    private KbpQuery query;
    private SitSearchResult res;
    private Communication comm;   // borrowed from res
    
    public MturkCorefHit(KbpQuery q, SitSearchResult r) {
      this.query = q;
      this.res = r;
      this.comm = r.comm;
      if (comm == null)
        throw new IllegalArgumentException();
      if (findTok(res.tokUuid, comm) == null)
        throw new IllegalArgumentException("couldn't find tokUuid=" + res.tokUuid + " in " + comm.getId());
    }

    public static String[] getMturkCorefHitHeader() {
//      return new String[] {"hitId", "sourceDocId", "score", "targetDocId", "sourceMentionHtml", "targetMentionHtml"};
      return new String[] {"hitId", "sourceDocId", "targetDocId", "score", "nicelyFormattedHtml"};
    }
    
    /**
     * Outputs (hitId, sourceDocId, targetDocId, score, sourceHtml, targetHtml)
     * Currently score is a placeholder.
     */
    public String[] emitMturkCorefHit(File writeHtmlTableTo, IntPair rank, double score) {
      // How do I highlight an entity
      // Which ent to highlight!

      // Where do I get the source comm from?
      if (query.sourceComm == null || query.entityMention == null)
        throw new IllegalStateException("must resolve Communication/EntityMention for query");

      EntityMention s = query.entityMention;
      Tokenization st = findTok(s.getTokens().getTokenizationId().getUuidString(), query.sourceComm);
      Span ss = convert(s.getTokens());
      
      Tokenization t = res.getTokenization();
      if (t == null)
        throw new RuntimeException("couldn't resolve result Tokenization for: " + res);
//      Span ts = Span.widthOne(res.yhatQueryEntityHead);
      DependencyParse depsR = getPreferredDependencyParse(res.getTokenization());
      Span ts = nounPhraseExpand(res.yhatQueryEntityHead, depsR);
      
      String rankStr = String.format("%04d-of-%04d", rank.first, rank.second);
      String hitId = query.id
          + "_" + rankStr
          + "_rTokUuid:" + res.tokUuid;
      
      String sourceMentionHtml = formatHighlightedSpanInHtml(st, ss);
      String targetMentionHtml = formatHighlightedSpanInHtml(t, ts);
      
      int minToksGoal = 200;
      int maxToksGoal = 1000;
      String sourceContext = new NewsStoryContext(query.sourceComm)
          .createSummaryWorkBackwardsFromQuery(minToksGoal, maxToksGoal, query.entityMention);
      String targetContext = new NewsStoryContext(res.getCommunication())
          .createSummaryWorkBackwardsFromQuery(minToksGoal, maxToksGoal, res.tokUuid, ts);
      
      int k = 15;
      List<String> sterms = query.docCtxImportantTerms;
      if (sterms.size() > k) sterms = sterms.subList(0, k);
      List<String> rterms = res.importantTerms;
      if (rterms.size() > k) rterms = rterms.subList(0, k);
      String sourceTerms = StringUtils.join(", ", sterms);
      String targetTerms = StringUtils.join(", ", rterms);
      
      StringBuilder sb = new StringBuilder();

      // Set this in the mturk editor
//      sb.append("<style>");
//      sb.append("span.entity { color: blue; font-weight: bold; }");
//      sb.append("span.terms { font-style: italic; }");
//      sb.append("</style>");

      sb.append("<center>");
      sb.append("<table border=\"1\" cellpadding=\"10\" width=\"80%\">");
      sb.append("<col width=\"50%\"><col width=\"50%\">");
//      sb.append("<tr><td>Source</td><td>Target</td></tr>");

      // mention
      sb.append("<tr>");
      sb.append("<td valign=top><span class=\"mention\">" + sourceMentionHtml + "</span></td>");
      sb.append("<td valign=top><span class=\"mention\">" + targetMentionHtml + "</span></td>");
      sb.append("<tr>");

      // terms
      sb.append("<tr>");
      sb.append("<td valign=top>Keywords: <span class=\"terms\">" + sourceTerms + "</span></td>");
      sb.append("<td valign=top>Keywords: <span class=\"terms\">" + targetTerms + "</span></td>");
      sb.append("<tr>");

      // first paragraph
      sb.append("<tr>");
      sb.append("<td valign=top><span class=\"context\">" + sourceContext + "</span></td>");
      sb.append("<td valign=top><span class=\"context\">" + targetContext + "</span></td>");
      sb.append("<tr>");

      sb.append("</table>");
      sb.append("</center>");
      
      String nicelyFormattedHtml = sb.toString();

      if (writeHtmlTableTo != null) {
        assert writeHtmlTableTo.isDirectory();
        File f = new File(writeHtmlTableTo, hitId + ".html");
        Log.info("writing to " + f.getPath());
        try (BufferedWriter w = FileUtil.getWriter(f)) {
          w.write("<html><body>\n");
          w.write("<style>");
          w.write("span.entity { color: blue; font-weight: bold; }\n");
          w.write("span.terms { font-style: italic; }\n");
          w.write("</style>\n");

          w.write("<center>\n");
          w.write("<h2>NOT SHOWN TO TURKERS: rank=" + rank + " score=" + score + "</h2>\n");
          w.write("</center>\n");
          w.write("<pre>\n");
          ShowResult sr = new ShowResult(query, res);
          for (String line : sr.show2(Collections.emptyList())) {
            w.write(line);
          }
          w.write("</pre>\n");

          w.write("<center><h2>SHOWN TO TURKERS</h2></center>\n");
          w.write(nicelyFormattedHtml);
          w.write("</body></html>\n");
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      
      return new String[] {
          hitId,
          query.sourceComm.getId(),
          res.getCommunicationId(),
          "" + res.getScore(),
          nicelyFormattedHtml,
//          sourceMentionHtml,
//          targetMentionHtml,
//          sourceContext,
//          targetContext,
//          sourceTerms,
//          targetTerms,
      };
    }
    
    public static Span convert(TokenRefSequence trs) {
      int min = Integer.MAX_VALUE;
      int max = -1;
      for (int i : trs.getTokenIndexList()) {
        min = Math.min(min, i);
        max = Math.max(max, i);
      }
      return Span.getSpan(min, max+1);
    }
    
    public static String formatHighlightedSpanInHtml(Tokenization words, Span highlight) {
      StringBuilder sb = new StringBuilder();
      List<Token> t = words.getTokenList().getTokenList();
      int n = t.size();
      assert 0 <= highlight.start && highlight.start <= highlight.end && highlight.end <= n : "highlight=" + highlight;
      for (int i = 0; i < n; i++) {
        if (i > 0)
          sb.append(' ');
        if (i == highlight.start)     // assumption: start is inclusive
          sb.append("<span class=\"entity\">");
        sb.append(t.get(i).getText());
        if (i == highlight.end-1)     // assumption: end is exclusive
          sb.append("</span>");
      }
      return sb.toString();
    }
    
  }

  public static String nerType(int head, Tokenization toks) {
    TokenTagging ner = getPreferredLemmas(toks);
    TaggedToken nt = ner.getTaggedTokenList().get(head);
    assert nt.getTokenIndex() == head;
    return nt.getTag();
  }

  /**
   * Takes all nn dependencies out of a head to get a span
   */
  public static Span nounPhraseExpand(int head, DependencyParse deps) {
    assert head >= 0;
    int left = head;
    int right = head;
    List<String> edges = Arrays.asList("nn");
    for (Dependency d : deps.getDependencyList()) {
      if (d.isSetGov() && d.getGov() == head && edges.contains(d.getEdgeType())) {
        if (d.getDep() < left) left = d.getDep();
        if (d.getDep() > right) right = d.getDep();
      }
    }
    return Span.getSpan(left, right+1);
  }

  
  /**
   * Provides a paragraph-length snippet which summarizes a {@link Communication} news story.
   */
  public static class NewsStoryContext {
    private Communication comm;
    
    public NewsStoryContext(Communication comm) {
      if (comm == null)
        throw new IllegalArgumentException();
      this.comm = comm;
    }

    public String createSummaryWorkBackwardsFromQuery(int minToksGoal, int maxToksGoal, EntityMention em) {
      String tokUuid = em.getTokens().getTokenizationId().getUuidString();
      List<Integer> toks = em.getTokens().getTokenIndexList();
      int left = toks.get(0);
      int right = left;
      for (int i : toks) {
        if (i < left) left = i;
        if (i > right) right = i;
      }
      return createSummaryWorkBackwardsFromQuery(minToksGoal, maxToksGoal, tokUuid, Span.getSpan(left, right+1));
    }
    
    /**
     * @param minToksGoal
     * @param maxToksGoal
     * @param tokUuidOfQuery
     * @param highlight can be null, otherwise will put a <span class="entity> around this span.
     */
    public String createSummaryWorkBackwardsFromQuery(int minToksGoal, int maxToksGoal, String tokUuidOfQuery, Span highlight) {
      StringBuilder sb = new StringBuilder();
      ArrayDeque<Tokenization> stack = new ArrayDeque<>();
      int toks = 0;
      for (Tokenization t : new TokenizationIter(comm)) {
        if (sb.length() == 0) {
          // Case 1: We haven't found the desired tokenization yet
          stack.push(t);
          if (tokUuidOfQuery.equals(t.getUuid().getUuidString())) {
            // If we found it, unwind and keep what fits
            ArrayDeque<Tokenization> properOrder = new ArrayDeque<>();
            while (toks < minToksGoal && !stack.isEmpty()) {
              Tokenization cur = stack.pop();
              toks += cur.getTokenList().getTokenListSize();
              properOrder.push(cur);
            }
            // Output what fits to strings
            while (!properOrder.isEmpty()) {
              Tokenization cur = properOrder.pop();
              // Query tok was the last one in, highlight == null means rel is moot
              boolean rel = properOrder.isEmpty() && highlight != null;
              for (Token tt : cur.getTokenList().getTokenList()) {
                if (sb.length() > 0)
                  sb.append(' ');

                if (rel && tt.getTokenIndex() == highlight.start)
                  sb.append("<span class=\"entity\">");

                sb.append(tt.getText());

                if (rel && tt.getTokenIndex() == highlight.end-1)
                  sb.append("</span>");
              }
              sb.append("<br/>");
            }
          }
        } else {
          // Case 2: we've gotten everything up through the desired tokenization,
          // and we are going to keep going until we hit min
          toks += t.getTokenList().getTokenListSize();
          for (Token tt : t.getTokenList().getTokenList())
            sb.append(" " + tt.getText());
          sb.append("<br/>");
          if (toks >= minToksGoal)
            break;
        }
      }
      return sb.toString();
    }
    
    /**
     * Takes the first K tokens of the communication. This is normally a good thing for news,
     * but sometimes in long or incoherent docs, the query does not appear in this prefix.
     */
    public String createSummaryInitialSentences(int minToksGoal, int maxToksGoal) {
      StringBuilder sb = new StringBuilder();
      int toks = 0;
      for (Section section : comm.getSectionList()) {

//        sb.append("kind=" + section.getKind());       // dateline, headline, passage
//        sb.append(" label=" + section.getLabel());    // null
        
//        if (section.getKind().equalsIgnoreCase("headline"))
//          sb.append("<b>");
//        if (section.getKind().equalsIgnoreCase("dateline"))
//          sb.append("<i>");

//        if (section.getKind().equalsIgnoreCase("dateline"))
//          continue;

        boolean first = true;
        for (Sentence sent : section.getSentenceList()) {
          Tokenization t = sent.getTokenization();
          toks += t.getTokenList().getTokenListSize();

          String s = comm.getText().substring(sent.getTextSpan().getStart(), sent.getTextSpan().getEnding());
          s = s.replace('\n', ' ').replaceAll("\\s+", " ");
          if (first) {
            first = false;
            sb.append(' ');
          }
          sb.append(s);

          if (toks >= minToksGoal) {
//            int e = sent.getTextSpan().getEnding();
//            assert e > toks;
//            return comm.getText().substring(0, e);
            return sb.toString();
          }
        }

//        if (section.getKind().equalsIgnoreCase("headline"))
//          sb.append("</b>");
//        if (section.getKind().equalsIgnoreCase("dateline"))
//          sb.append("</i>");

        sb.append("<br/>");
      }
//      return comm.getText();
      return sb.toString();
    }
  }
  
  /**
   * Shows {@link SitSearchResult}s in a human readable form.
   */
  public static class ShowResult {
    private KbpQuery query;
    private SitSearchResult res;
    private Communication comm;   // borrowed from res
    
    public ShowResult(KbpQuery q, SitSearchResult r) {
      this.query = q;
      this.res = r;
      this.comm = r.comm;
      if (comm == null)
        throw new IllegalArgumentException();
      if (findTok(res.tokUuid, comm) == null)
        throw new IllegalArgumentException("couldn't find tokUuid=" + res.tokUuid + " in " + comm.getId());
    }
    
    public List<String> show2(List<TermVec> pkbDocs) {
      List<String> lines = new ArrayList<>();
      int termCharLimit = 120;

      lines.add("####################################################################################################\n");
      lines.add(query + "\tres.tokUuid=" + res.tokUuid + " in " + comm.getId() + "\n");
      if (res.getCommunicationId().equals(query.docid))
        lines.add("FOUND QUERY DOCUMENT, this result will be filtered out later\n");
      lines.add("\n");

      // Mention itself
      lines.add("query mention itself:\n");
      String toks = query.findMentionHighlighted();
      if (toks == null) {
        lines.add("Communication or EntityMention not set, cannot show\n");
      } else {
        lines.add(toks + "\n");
      }
      lines.add("\n");
      
      // Query features
      lines.add("pkbDocs.size=" + pkbDocs.size() + "\n");
      if (query.docCtxImportantTerms != null)
        lines.add("queryDocCtxImportantTerms: " + query.docCtxImportantTerms + "\n");
      for (TermVec queryDoc : pkbDocs)
        lines.add("queryDoc: " + queryDoc.showTerms(termCharLimit) + "\n");
      if (query.features != null) {
        for (String f : query.features)
          lines.add("\tqf: " + f + "\n");
      }
      lines.add("\n");
      

      boolean highlightVerbs = true;
      lines.addAll(showQResult2(res, comm, termCharLimit, highlightVerbs));
      return lines;
    }

    public void show(List<TermVec> pkbDocs) {
//      int termCharLimit = 120;
//
//      System.out.println("####################################################################################################");
//      System.out.println(query + "\tres.tokUuid=" + res.tokUuid + " in " + comm.getId());
//      if (res.getCommunicationId().equals(query.docid))
//        System.out.println("FOUND QUERY DOCUMENT, this result will be filtered out later");
//
//      System.out.println();
//
//      // Mention itself
//      System.out.println("query mention itself:");
//      String toks = query.findMentionHighlighted();
//      if (toks == null) {
//        System.out.println("Communication or EntityMention not set, cannot show");
//      } else {
//        System.out.println(toks);
//      }
//      System.out.println();
//      
//      // Query features
//      System.out.println("pkbDocs.size=" + pkbDocs.size());
//      if (query.docCtxImportantTerms != null)
//        System.out.println("queryDocCtxImportantTerms: " + query.docCtxImportantTerms);
//      for (TermVec queryDoc : pkbDocs)
//        System.out.println("queryDoc: " + queryDoc.showTerms(termCharLimit));
//      if (query.features != null) {
//        for (String f : query.features)
//          System.out.println("\tqf: " + f);
//      }
//      System.out.println();
//      
//
//      boolean highlightVerbs = true;
//      showQResult(res, comm, termCharLimit, highlightVerbs);
      for (String line : show2(pkbDocs)) {
        assert line.endsWith("\n");
        System.out.print(line);
      }
    }
    
    public static List<String> showQResult2(SitSearchResult res, Communication comm, int termCharLimit, boolean highlightVerbs) {
      List<String> lines = new ArrayList<>();

      // Result features
      lines.add("result features:\n");
      if (res.featsResult != null)
        lines.add(res.featsResult.show(termCharLimit));
      lines.add("important terms: " + res.importantTerms + "\n");
      
      // Query entity features
      lines.add("query entity features:\n");
      if (res.entSearchResult != null) {
        for (String f : res.entSearchResult.queryEntityFeatures)
          lines.add("\tes: " + f + "\n");
      }
      lines.add("\n");
      
      // Attribute features
      lines.add("attribute features\n");
      lines.add("query:   " + res.attributeFeaturesQ + "\n");
      lines.add("result:  " + res.attributeFeaturesR + "\n");
      lines.add("matched: " + res.attributeFeaturesMatched + "\n");
      lines.add("\n");
      
      // score = weights * (featQuery outerProd featResult)... or something like that
      lines.add("score derivation:\n");
      for (Feat f : res.scoreDerivation)
        lines.add("\t" + f + "\n");
      lines.add(String.format("sum=%.3f\n", res.getScore()));
      lines.add("\n");
      

      // Words/Tokenization
      lines.add(res.getWordsInTokenizationWithHighlightedEntAndSit() + "\n");
      lines.add("\n");
      
      return lines;
    }

    public static void showQResult(SitSearchResult res, Communication comm, int termCharLimit, boolean highlightVerbs) {
      for (String line : showQResult2(res, comm, termCharLimit, highlightVerbs)) {
        assert line.endsWith("\n");
        System.out.print(line);
      }
    }
  }
    
  public static boolean isVerb(int i, Tokenization t) {
    TokenTagging tt = getPreferredPosTags(t);
    TaggedToken tag = tt.getTaggedTokenList().get(i);
    assert tag.getTokenIndex() == i;
    return tag.getTag().toUpperCase().startsWith("V");
  }
  
//  public static boolean isNominalizedVerb(int i, edu.jhu.hlt.fnparse.datatypes.Sentence sent) {
//    String tag = sent.getPos(i);
//    boolean nom = tag.equalsIgnoreCase("NN") || tag.equalsIgnoreCase("NNS");
//    if (!nom)
//      return false;
//
//    IWord w = sent.getWnWord(i);
//    if (w == null) {
//      Log.info("failure1: " + sent.getLemmaLU(i));
//      sent.getWnWord(i);
//      return false;
//    }
//    ISynset ss = w.getSynset();
//    if (ss == null) {
//      Log.info("failure2: " + sent.getLemmaLU(i));
//      return false;
//    }
//    
//    List<ISynsetID> rel = ss.getRelatedSynsets(Pointer.DERIVATIONALLY_RELATED);
//    
//    Log.info("succ1");
//    IRAMDictionary wn = TargetPruningData.getInstance().getWordnetDict();
//    for (ISynsetID ssid : rel) {
//      Log.info("checkme: " + ssid.getPOS().getTag() + " " + wn.getSynset(ssid));
//      if (ssid.getPOS().getTag() == 'V') {
//        // good enough
//        Log.info("word=" + sent.getLemmaLU(i) + " relatedSynset=" + wn.getSynset(ssid));
//        return true;
//      }
//    }
//    Log.info("succ2");
//    return false;
//  }
  
  /**
   * Returns a list of verbs which the given word could be a nominalization of,
   * e.g. "resolution" => ["resolve", "preparation"]
   */
  public static List<String> inverseNominalization(int i, edu.jhu.hlt.fnparse.datatypes.Sentence sent) {
    String pos = sent.getPos(i);
    if (!(pos.equals("NN") || pos.equals("NNS"))) {
      return Collections.emptyList();
    }
    IRAMDictionary wn = TargetPruningData.getInstance().getWordnetDict();

    String term = sent.getLemma(i);
    if (term == null) {
      EC.increment("noLemmaUseWord");
      term = sent.getWord(i);
    }
    IIndexWord iw = wn.getIndexWord(term, POS.NOUN);
    if (iw == null) {
//      Log.info("couldn't find " + sent.getWord(i) + "?");
      return Collections.emptyList();
    }
//    System.err.flush();
//    System.out.flush();
//    System.out.println(iw);
    Set<String> uniq = new HashSet<>();
    List<String> l = new ArrayList<>();
    if (iw.getWordIDs() == null) {
      Log.info("no word ids for " + sent.getWord(i) + "?");
      return Collections.emptyList();
    }
    for (IWordID wid : iw.getWordIDs()) {
//      Log.info("word id: " + wid + " lemma=" + wid.getLemma() + " ss=" + wid.getSynsetID());
      IWord w = wn.getWord(wid);
      for (IWordID rw : w.getRelatedWords()) {
//        System.out.println("\tREL:" + rw.getPOS().getTag() + "\t" + rw);
        if (rw.getPOS().getTag() == 'v') {
          if (uniq.add(rw.getLemma()))
            l.add(rw.getLemma());
        }
      }
    }
    return l;
  }
    
  public static class EntityEventPathExtraction {
    List<String> queryEntityFeatures;
    List<WeightedFeature> queryEntityFeaturesMatched;
    Tokenization tokenization;
    Communication comm;
    
    public boolean verboseEntSelection = false;
    public boolean verboseSitSelection = false;
    
    public EntityEventPathExtraction(SitSearchResult r) {
      this(r.triageFeatures, r.triageFeaturesMatched, r.getTokenization(), r.getCommunication());
    }

    public EntityEventPathExtraction(
        List<String> queryEntityFeatures,
        List<WeightedFeature> queryEntityFeaturesMatched,
        Tokenization toks,
        Communication comm) {

      if (queryEntityFeatures == null || queryEntityFeatures.isEmpty())
        throw new IllegalArgumentException();
      if (toks == null || comm == null)
        throw new IllegalArgumentException();
        
      this.queryEntityFeatures = queryEntityFeatures;
      this.queryEntityFeaturesMatched = queryEntityFeaturesMatched;
      this.tokenization = toks;
      this.comm = comm;
    }
    
    private Map<String, Double> _indexMatchedFeatsMemo;
    private Map<String, Double> indexMatchedFeats() {
      if (_indexMatchedFeatsMemo == null) {
        _indexMatchedFeatsMemo = new HashMap<>();
        if (queryEntityFeaturesMatched == null) {
          Log.info("queryEntityFeaturesMatched is not set!");
        } else {
          for (WeightedFeature wf : queryEntityFeaturesMatched) {
            Object old = _indexMatchedFeatsMemo.put(wf.feature, wf.weight);
            assert old == null : "duplicate features? " + wf.feature;
          }
        }
      }
      return _indexMatchedFeatsMemo;
    }

    private int chooseHeadOfEntityMention(EntityMention em, boolean verbose) {
      if (!em.getTokens().getTokenizationId().getUuidString().equals(tokenization.getUuid().getUuidString()))
        throw new IllegalArgumentException();
      // Try to choose a token which matches some triage features
      Map<String, Double> w = indexMatchedFeats();
      ArgMax<Integer> a = new ArgMax<>();
      for (int i = 0; i < em.getTokens().getTokenIndexListSize(); i++) {
        int t = em.getTokens().getTokenIndexList().get(i);
        double score = 0;
        
        // Features may not be present, if so fall back on the head
        boolean head = em.getTokens().isSetAnchorTokenIndex() && t == em.getTokens().getAnchorTokenIndex();
        if (head) score += 1.0;

        String word = tokenization.getTokenList().getTokenList().get(t).getText();
        score += w.getOrDefault("pi:" + word.toLowerCase(), 0d);
        score += w.getOrDefault("hi:" + word.toLowerCase(), 0d);
        score += w.getOrDefault("h:" + word, 0d);
        if (verbose)
          System.out.printf("[chooseHeadOfEntityMention] t=%d w=%s head=%s score=%.2f\n", t, w, head, score);
        a.offer(t, score);
      }
      return a.get();
    }

    /**
     * returns (entityHeadIdx, eventHeadIdx) in the given tokenization
     */
    public IntPair findMostInterestingEvent(ComputeIdf df, boolean verbose) {
      List<Token> toks = tokenization.getTokenList().getTokenList();
      BiPredicate<String, String> tieBreaker =
          edu.jhu.hlt.fnparse.datatypes.Sentence.takeParseyOr(edu.jhu.hlt.fnparse.datatypes.Sentence.KEEP_LAST);
      edu.jhu.hlt.fnparse.datatypes.Sentence sent2 = edu.jhu.hlt.fnparse.datatypes.Sentence.convertFromConcrete(
          "ds", "id", tokenization, tieBreaker, tieBreaker, tieBreaker);
      DependencyParse deps = edu.jhu.hlt.fnparse.datatypes.Sentence.extractDeps(tokenization, tieBreaker);
      boolean expectSingleHeaded = false;
      edu.jhu.hlt.fnparse.datatypes.DependencyParse deps2 =
          edu.jhu.hlt.fnparse.datatypes.DependencyParse.fromConcrete(toks.size(), deps, expectSingleHeaded);
      new AddNerTypeToEntityMentions(comm);
      EntityMention em = bestGuessAtQueryMention(tokenization, comm, queryEntityFeatures, verboseEntSelection);
      if (em == null) {
        if (verbose)
          Log.info("no EntityMentions in this Tokenization! comm=" + comm.getId() + " aka " + comm.getUuid().getUuidString());
        return new IntPair(-1, -1);
      }
      if (verbose)
        System.out.println("best guess: " + em);
      ArgMax<Pair<String, Token>> bestPath = new ArgMax<>();
      
      // NOTE: This is not always good.
      // For example, during the search for "Esther-Ethy Mamane"
      // we correctly find the EntityMention "Claudine and Esther-Ethy Mamane"
      // but get the wrong head of "Claudine"
//      int start = em.getTokens().getAnchorTokenIndex();
      int start = chooseHeadOfEntityMention(em, verbose);

      for (Token t : toks) {
        int end = t.getTokenIndex();
        if (start == end)
          continue;
        List<String> invNom = inverseNominalization(t.getTokenIndex(), sent2);
        boolean verb = isVerb(t.getTokenIndex(), tokenization);
        if (verb || invNom.size() > 0) {
          //        if (invNom.size() > 0)
          //          System.out.println("invNom(" + t.getText() + "):\t" + invNom);
          Path2 path = new Path2(start, end, deps2, sent2);
          if (path.connected()) {
            double idf = df.idf(t.getText());
            int pathLen = path.getEntries().size();
            double pathLenPenalth = 2d / (2 + pathLen);
            int numNom = invNom.size();
            double nomPenalty = 1;
            if (!verb) {
              nomPenalty *= 0.75;                 // have slight preference for verbs
              nomPenalty *= (1d / (1 + numNom));  // and penalize unsure nominalizations
            }
            double score = Math.pow(idf, 1.5) * pathLenPenalth * nomPenalty;
            String pathStr = path.getPath(NodeType.WORD, EdgeType.DEP, true);
            bestPath.offer(new Pair<>(pathStr, t), score);
            if (verbose) {
              System.out.printf("idf=%.2f pathLenPenalty=%.2f nomPenalty=%.2f score=%3g path=%s\n",
                  idf, pathLenPenalth, nomPenalty, score, pathStr);
            }
          }
        }
      }
      if (bestPath.numOffers() == 0)
        return new IntPair(start, -1);
      Pair<String, Token> b = bestPath.get();
      if (verbose) {
        System.out.println("bestPath=" + b.get1());
        System.out.println();
        System.out.println();
      }
      int end = b.get2().getTokenIndex();
      return new IntPair(start, end);
    }
  }

  /**
   * This is a first-stab at finding the position of the mention which matches the query.
   * Before this, we are extracting features at the Tokenization level, ignoring the actual locations.
   * 
   * This works by looping over entityMentions.
   * 
   * NOTE: This is slow, but a simple first impl.
   * 
   * @param t can be null (no filtering) or else will only consider EntityMentions in that sentence.
   */
  public static EntityMention bestGuessAtQueryMention(Tokenization t, Communication c, List<String> queryEntityFeatures, boolean verbose) {
    List<EntityMention> rel = new ArrayList<>();
    for (EntityMention em : getEntityMentions(c)) {
      String tokUuid = em.getTokens().getTokenizationId().getUuidString();
      if (t == null || tokUuid.equals(t.getUuid().getUuidString()))
        rel.add(em);
    }

    if (verbose)
      Log.info("found " + rel.size() + " relevant EntityMentions");
    
    // Score each mention based on features (just dot prod)
    if (rel.size() > 1) {
      if (queryEntityFeatures == null)
        throw new IllegalArgumentException("need features for disambiguation");
      if (verbose)
        Log.info("finding things similar to query with feats=" + queryEntityFeatures);
      Set<String> qf = new HashSet<>(queryEntityFeatures);
      ArgMax<EntityMention> a = new ArgMax<>();
      for (EntityMention em : rel) {
        TokenObservationCounts tokObs = null;
        TokenObservationCounts tokObsLc = null;
//        String nerType = TacKbp.tacNerTypesToStanfordNerType(em.getEntityType());
        String nerType = em.getEntityType();
        String[] headwords = em.getText().split("\\s+");  // TODO
        List<String> fs = getEntityMentionFeatures(em.getText(), headwords, nerType, tokObs, tokObsLc);
        double score = 0;
        for (String f : fs) {
          if (qf.contains(f)) {
            // Have a slight preference for smaller mentions, as they are biased to randomly match more features
            double k = 5;
            score += k / (k + fs.size());
          }
        }

        if (verbose) {
          System.out.printf("score=%.2f headwords=%s feats=%s\n", score, Arrays.toString(headwords), fs);
        }
        a.offer(em, score);
      }
      return a.get();
    }
    
    if (rel.isEmpty())
      return null;
    return rel.get(0);
  }
    
  public static Tokenization findTok(String tokUuid, Communication comm) {
    if (tokUuid == null)
      throw new IllegalArgumentException();
    if (comm == null)
      throw new IllegalArgumentException();
    Tokenization t = null;
    for (Section section : comm.getSectionList()) {
      for (Sentence sentence : section.getSentenceList()) {
        Tokenization tok = sentence.getTokenization();
        String tid = tok.getUuid().getUuidString();
        if (tid.equals(tokUuid)) {
          assert t == null;
          t = tok;
        }
      }
    }
    return t;
  }


  /**
   * I'm going to have to do some kind of deduplication or clustering
   * of mentions, and the TAC KBP 2013/2014 give me a set of entities to care about.
   * This reads in those queries, searches over an index, and spits out results.
   */
  public static class KbpDirectedEntitySearch {
    static boolean DEBUG = false;
    
    public static void main(ExperimentProperties config) throws Exception {
      
      // Where to output coref HITs
      File mturkCorefCsv = config.getFile("mturkCorefCsv");
      String[] mturkCorefCsvCols = MturkCorefHit.getMturkCorefHitHeader();
      CSVFormat csvFormat = CSVFormat.DEFAULT.withHeader(mturkCorefCsvCols);
      Log.info("writing to mturkCorefCsv=" + mturkCorefCsv
          + " with cols: " + Arrays.toString(mturkCorefCsvCols)
          + " and format: " + csvFormat);
      
      // Go to test1b, start edu.jhu.hlt.ikbp.tac.ScionForwarding on port 34343
      // Locally, run ssh -fNL 8088:test1:34343 test1b
      int scionFwdLocalPort = config.getInt("scionFwdLocalPort", 8088);
      ForwardedFetchCommunicationRetrieval commRet;
      try {
        commRet = new ForwardedFetchCommunicationRetrieval(scionFwdLocalPort);
        commRet.test("NYT_ENG_20090901.0206");
      } catch (Exception e) {
        throw new RuntimeException("did you start up an ssh tunnel on " + scionFwdLocalPort + "?", e);
      }

      // Finds EntityMentions for query documents which just come with char offsets.
      TacQueryEntityMentionResolver findEntityMention =
          new TacQueryEntityMentionResolver("tacQuery");

      File f = new File("data/parma/training_files/ecbplus/model_neg4.vw");
      File parmaModelFile = config.getExistingFile("dedup.model", f);
      try (ParmaVw parma = new ParmaVw(parmaModelFile, null, config);
          BufferedWriter mturkCsvW = FileUtil.getWriter(mturkCorefCsv);
          CSVPrinter mturkCorefCsvW = new CSVPrinter(mturkCsvW, csvFormat)) {
      
      StringTable commUuid2CommId = new StringTable();
      commUuid2CommId.add(new File(HOME, "raw/emTokCommUuidId.txt.gz"), 2, 3, "\t", true);
      
      List<KbpQuery> queries = TacKbp.getKbp2013SfQueries();
      
      Pair<SituationSearch, TfIdfDocumentStore> ss = SituationSearch.build(config);
      IntDoubleHashMap idfs = ss.get2().getIdfs();
      parma.setIdf(idfs);
      KbpDirectedEntitySearch k = new KbpDirectedEntitySearch(ss.get1(), commRet, idfs, commUuid2CommId);
      
      // How many results per KBP query (before dedup).
      // Higher values are noticeably slower.
      int limit = config.getInt("limit", 20);

      for (KbpQuery q : queries) {
        EC.increment("kbpQuery");
        System.out.println(TIMER);
        Log.info(q);
        
        q.sourceComm = commRet.get(q.docid);
        if (q.sourceComm == null) {
          EC.increment("kbpQuery/failResolveSourceDoc");
          continue;
        }
        boolean addEmToCommIfMissing = true;
        findEntityMention.resolve(q, addEmToCommIfMissing);

        try {
        boolean clearPkb = false;
        List<SitSearchResult> results = k.search(q, limit, clearPkb);
        if (results.isEmpty())
          EC.increment("kbpQuery/failConvert");
        
        // Add in NLSF
        k.nlsf.rescore(results);
        
        // Deduplicate with parma
        int maxResults = 50;
        List<QResultCluster> deduped = parma.dedup(results, maxResults);

        // Display results
        for (QResultCluster clust : deduped) {
          EC.increment("kbpQuery/result");
          SitSearchResult r = clust.canonical;
          ShowResult s = new ShowResult(q, r);
          s.show(k.search.state.pkbDocs);

          MturkCorefHit mtc = new MturkCorefHit(q, r);
          String[] csv = mtc.emitMturkCorefHit(null, null, 0);
          System.out.println("mturk coref csv: " + Arrays.toString(csv));
          System.out.println("mturk targetMentionHtml: " + csv[csv.length-1]);
          mturkCorefCsvW.printRecord(csv);
          mturkCorefCsvW.flush();
        }
        } catch (Exception e) {
          e.printStackTrace();
        }
        
        k.search.clearState();
      }
      }
      
      System.out.println(EC);
      Log.info("done");
    }
    
    private SituationSearch search;
    private ParmaVw dedup;
    private IntDoubleHashMap idf;
    private ForwardedFetchCommunicationRetrieval commRet;
    private NaturalLanguageRescoring nlsf;
    
    // scion/accumulo needs ids rather than UUIDs
    private StringTable commUuid2CommId;
    
    // Borrowed
    private TokenObservationCounts tokObs;
    private TokenObservationCounts tokObsLc;
    
    /**
     * @param search
     * @param idfs is a two-column TSV of (term:int, idf:double), e.g. doc/idf.txt
     */
    public KbpDirectedEntitySearch(
        SituationSearch search,
        ForwardedFetchCommunicationRetrieval commRet,
        IntDoubleHashMap idfs,
        StringTable commUuid2CommId) throws IOException {
      this.search = search;
      this.idf = idfs;
      this.commRet = commRet;
      this.commUuid2CommId = commUuid2CommId;

      // TODO this means that any features which are count/case sensitive won't fire!
      this.tokObs = null;
      this.tokObsLc = null;
      
      this.nlsf = new NaturalLanguageRescoring();
    }
    
    /**
     * Convert from a KBP (SF) query which only provides an entity and NER type
     * to a {@link SitSearchResult} which can be put into the PKB for searching.
     * Among other things, this seed contains a document providence which is
     * useful for disambiguation.
     */
    public List<SitSearchResult> buildQuerySeed(KbpQuery sfQuery) {
      SentFeats sf = new SentFeats();
      SitSearchResult seed = new SitSearchResult(null, sf, Collections.emptyList());
      
      // Get the Communication used by the query
      assert seed.comm == null;
      seed.comm = commRet.get(sfQuery.docid);
      if (seed.comm == null) {
        EC.increment("buildQuerySeed/noQueryComm");
        Log.info("couldn't find communication for query: " + sfQuery);
        return Collections.emptyList();
      }
      sf.setCommunicationUuid(seed.comm.getUuid().getUuidString());
      
      /*
       * I need to figure out how to extract the features for SentFeat/QResult
       *   entities, deprel, frames
       * In the retrieval case, these features are generated ahead of time by the extraction code.
       */
      
      // Entity features
      String mentionText = sfQuery.name;
      String[] headwords = sfQuery.name.split("\\s+");
      String nerTypeStr = TacKbp.tacNerTypesToStanfordNerType(sfQuery.entity_type);
      for (String emFeat : getEntityMentionFeatures(mentionText, headwords, nerTypeStr, tokObs, tokObsLc)) {
        int ef = ReversableHashWriter.onewayHash(emFeat);
        byte nerType = 0;    // TODO
        FeaturePacker.writeEntity(ef, nerType, sf);
      }
      
      // deprel
      Log.info("WARNING: implement deprels");
      
      // frame
      Log.info("WARNING: implement frames");
      
      // Build a TermVec from a Communication
      sf.tfidf = ComputeTfIdfDocVecs.packComm(N_DOC_TERMS, seed.comm, idf);
      
      // TODO Consider generating one or more queries for every slot.
      // Currently this is just a generic entity-centric seed/query.
      return Arrays.asList(seed);
    }
    
    public List<SitSearchResult> search(KbpQuery sfQuery, int limit, boolean clearPkb) {
      List<SitSearchResult> seeds = buildQuerySeed(sfQuery);
      if (seeds.isEmpty()) {
        Log.warn("no seeds for " + sfQuery);
        EC.increment("kbpDirEntSearch/noSeeds");
        return Collections.emptyList();
      }
      // TODO Add all tokenizations in the query document
      // so that none of those results are returned
      for (SitSearchResult s : seeds)
        search.addToPkb(s);
      
      // TODO Remove headwords, switch to purely a key-word based retrieval model.
      // NOTE that headwords must match the headwords extracted during the indexing phrase.
      String[] headwords = new String[] {};
      String entityName = sfQuery.name;
      String entityType = TacKbp.tacNerTypesToStanfordNerType(sfQuery.entity_type);
      List<SitSearchResult> res = search.query(entityName, entityType, headwords, limit);
      if (clearPkb)
        search.clearState();
      
      // Convert comm uuid => id (for retrieving Communications)
      for (SitSearchResult qr : res) {
        // TODO Can't I just get this from the Communication rather than commUuid2CommId?
        assert false;

        String uuid = qr.featsResult.getCommunicationUuidString();
        assert uuid != null && !uuid.isEmpty();
        String id = commUuid2CommId.get1(uuid);
        assert id != null && !id.isEmpty();
        qr.setCommunicationId(id);
      }
      // Resolve Communications
      commRet.fetch(res);
      
      return res;
    }
  }
  
  
  public static class NaturalLanguageRescoring {
    private NaturalLanguageSlotFill nlsf;
    private String entityMentionToolName;
    
    public NaturalLanguageRescoring() {
      ExperimentProperties config = ExperimentProperties.getInstance();
      nlsf = NaturalLanguageSlotFill.build(config);
      
      entityMentionToolName = "foo";  // TODO
    }
    
    public void rescore(List<SitSearchResult> results) {
      TIMER.start("nlsi/rescore");
      
      // Turn this one when you're ready
      boolean passageMST = false;
      
      for (SitSearchResult r : results) {
        if (r.comm == null)
          throw new IllegalArgumentException();
        
        Tokenization t = r.getTokenization();
        edu.jhu.hlt.fnparse.datatypes.Sentence s = null;
        if (t == null) {
          Log.info("couldn't lookup tokUuid=" + r.tokUuid + " in " + r.commId + ", aka " + r.getCommunicationId());
          continue;
        } else {
        try {
        s = edu.jhu.hlt.fnparse.datatypes.Sentence.convertFromConcrete("", r.tokUuid, t,
            edu.jhu.hlt.fnparse.datatypes.Sentence.takeParseyOr(edu.jhu.hlt.fnparse.datatypes.Sentence.KEEP_LAST),
            edu.jhu.hlt.fnparse.datatypes.Sentence.takeParseyOr(edu.jhu.hlt.fnparse.datatypes.Sentence.KEEP_LAST),
            edu.jhu.hlt.fnparse.datatypes.Sentence.takeParseyOr(edu.jhu.hlt.fnparse.datatypes.Sentence.KEEP_LAST));
        } catch (Exception e) {
          e.printStackTrace();
          Feat f = new Feat("exception/nlsf/tokSent");
          f.weight = -1;
          f.addJustification(e.getMessage());
          r.scoreDerivation.add(f);
          continue;
        }
        }
        
        edu.jhu.hlt.fnparse.datatypes.DependencyParse deps = null;
        if (passageMST) {
//          deps = s.getBasicDeps();
          deps = s.getParseyDeps();
          assert deps != null;
        }
        
        // These are the entities which appear in this result
        List<EntityMention> em = null;
        if (passageMST)
          em = r.findMentions(entityMentionToolName);

        List<NaturalLanguageSlotFill.Match> matches = nlsf.scoreAll(s);
        if (!matches.isEmpty()) {
          Feat f = new Feat("nlsf");
          for (NaturalLanguageSlotFill.Match m : matches) {
            f.weight += 1;
            f.addJustification(m.getRelation() + "/" + m.alignmentScript());

            if (passageMST) {
              // Compute MST over all of the aligned words in the result (wrt an NL-SF).
              // Take this as the max over all entities
              double ms = Double.NEGATIVE_INFINITY;
              for (EntityMention e : em) {
                int eHead = head(e);
                double af = nlsf.passageSpanningTreeFeature(m, deps, eHead);
                if (af > ms)
                  ms = af;
              }
              f.weight += ms;
              f.addJustification(m.getRelation() + "/nlsfMST=" + ms);
            }
          }
          r.scoreDerivation.add(f);
        }
      }
      TIMER.stop("nlsi/rescore");
    }
    
    private int head(EntityMention e) {
      throw new RuntimeException("implement me");
    }
  }

  
  /**
   * Given a bunch of query results, fetch their {@link Communication}s from scion/accumulo.
   * 
   * @see DiskBackedFetchWrapper
   */
  public static class ForwardedFetchCommunicationRetrieval {
    private FetchCommunicationService.Client client;

    // If true, prints the Communication ids of any documents it can't find
    public boolean logFailures = true;
    
    public ForwardedFetchCommunicationRetrieval(int localPort) {
      Log.info("talking to localhost:" + localPort + " which should be"
          + " forwarded to something which implements FetchCommunicationService, e.g. ScionForwarding");
      try {
        TTransport transport = new TFramedTransport(new TSocket("localhost", localPort), Integer.MAX_VALUE);
        transport.open();
        TProtocol protocol = new TCompactProtocol(transport);
        client = new FetchCommunicationService.Client(protocol);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    public void test(String commId) {
      Log.info("trying to fetch " + commId);
      FetchRequest fr = new FetchRequest();
      fr.addToCommunicationIds(commId);
      Log.info(fr);
      try {
        FetchResult res = client.fetch(fr);
        if (res.getCommunicationsSize() == 0) {
          if (logFailures)
            Log.info("failed to retrieve: " + commId);
          Log.info("no results!");
        } else {
          Communication c = res.getCommunications().get(0);
          Log.info("got back: " + c.getId());
          System.out.println(c.getText());
        }
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    public Communication get(String commId) {
      TIMER.start("commRet/get");
      FetchRequest fr = new FetchRequest();
      fr.addToCommunicationIds(commId);
      try {
        FetchResult res = client.fetch(fr);
        if (res.getCommunicationsSize() == 0) {
          if (logFailures)
            Log.info("failed to retrieve: " + commId);
          EC.increment("commRet/get/noResults");
          TIMER.stop("commRet/get");
          return null;
        }
        Communication c = res.getCommunications().get(0);
        assert res.getCommunicationsSize() == 1;
        TIMER.stop("commRet/get");
        return c;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    public void fetch(List<SitSearchResult> needComms) {
      if (needComms.isEmpty()) {
        EC.increment("commRet/emptyFetch");
        return;
      }
      TIMER.start("commRet/fetch");
      
      // TODO Break up request if there are too many?
      // TODO Have option to do one-at-a-time retrieval?
      FetchRequest fr = new FetchRequest();
      for (SitSearchResult r : needComms) {
        if (r.getCommunicationId() == null)
          throw new IllegalArgumentException("no comm id provided");
        if (r.comm != null) {
          // TODO need for resolving a subset of comms?
          throw new IllegalArgumentException("already resolved!");
        }
        fr.addToCommunicationIds(r.getCommunicationId());
      }
      
      FetchResult res = null;
      try {
        res = client.fetch(fr);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      
      IndexCommsById ic = new IndexCommsById(res.getCommunications());
      for (int i = 0; i < needComms.size(); i++) {
        String id = needComms.get(i).getCommunicationId();
        Communication comm = ic.getCommunication(id);
        if (comm == null) {
          if (logFailures)
            Log.info("failed to retrieve: " + id);
          EC.increment("commRet/missingId");
        } else {
          needComms.get(i).setCommunication(comm);
        }
      }
      TIMER.stop("commRet/fetch");
    }
  }
  
  private static class IndexCommsById {
    private Map<String, Communication> byId;
    public IndexCommsById(Iterable<Communication> res) {
      this.byId = new HashMap<>();
      for (Communication c : res) {
        Object old = this.byId.put(c.getId(), c);
        assert old == null : "duplicate id? " + c.getId();
      }
    }
    public Communication getCommunication(String id) {
      return byId.get(id);
    }
  }
  
  /**
   * Stores everything needed to score a mention to be shown to a user.
   * Does not include all of the Communication info, which must be queried separately.
   *
   * Implicitly has an external Tokenization UUID primary key.
   * Store a Map<String, SentFeats> for all sentences in CAG which:
   * 1) contain two entities
   * 2) contains either a deprel or situation
   *
   * If we assume there are 20 of these per document, 10M docs in CAG,
   * 16 + 3 + 4*3 = 32 bytes per instance
   * 200M * 32 = 6G?
   * The 32 is not accurate since the arrays require pointers.
   *
   * If I solve the pointer issue and we count the 16 bytes for the Tokenization UUID key,
   * then 16+16+3+4*3 = 47 bytes, round up to 64 bytes
   * 200M * 64 bytes < 2^28 * 2^6 = 2^34 = 16 GB
   * 
   * If I include the pointer to TermDoc and ignore the cost of the actual vectors
   * 8+47 = 55, which is still under the 64 byte budget.
   */
  public static class SentFeats implements Serializable {
    private static final long serialVersionUID = 7940630194450897925L;

    // Use this to track down the Communication to show to the user
    long comm_uuid_lo, comm_uuid_hi;
    
    // wait, the only reason to split these features out is so that you can assign them different weights.
    // alternatively, i could
    // for now: put all these features into one bag, strings hashed in would ...
    // check the score code first.
    
    /*
     * The only reason to maintain any FEATURES is to support the scoring model.
     * The scoring model only supports things in the seeds/PKB.
     * I should fully describe the seed/PKB model so that I can craft these features in support of them.
     * 
     * 
     * 1) queries: do entity name matching
     *    once you click on an entity, it is put into the PKB and other results which mention this entity will be highly ranked
     * 2) seed relations: "X, married to Y", "X bought Y from Z", "X traveled to Y"
     *    these can be syntactic or semantic descriptions of situations.
     *    we parse them into a formal representation and then use that representation to match against retrieved events.
     *    a) in a retrieved situation, we want every argument to be realized
     *    b) for every argument that is realized, we want the filler to be in the query (10 points) or the PKB (1 point)
     *    c) maybe I'll have time to worry about computing the utility of roles, this may be done through online learning
     *
     * It seems that I can unify this scoring model under a data model where we store:
     *   (frame, (role,arg)+)+
     * If there are 4 deprels, 10 fn, and 10 extra situations,
     * each having an average of 3 arguments,
     * 24 * (1 + 3) = 96 ints = 384 bytes.
     * That is WAY more than my 64 byte budget.
     * ...If I cut out the 10 extra and use shorts instead of ints:
     * 14 * 4 = 56 shorts = 112 bytes
     * I think this is as small as we can hope to go:
     * there is already 16 bytes for comm_uuid and 8 for the TermVec pointer, 112/24 = 4.67
     *
     * What about roles?
     * i.e. how do we distinguish "man bites dog" from "dog bites man"?
     * option 1: ignore this, then we're just trying to find ANY alignment between PKB ents and mention ents
     * AH, this is correct, at least as long as our scoring model doesn't distinguish between roles!
     *
     *
     * bit readout:
     * "frame" = hash32("fn/Commerce_buy")
     * "numArgs" = 8 bits saying how many arguments will follow this (run-length encoding)
     * "roles" = [optional/future] a 32 bit mask saying which roles appeared. roles/args to follow are always in sorted order.
     * "args" = hash32("PERSON/Barack_Obama"). Note: does not include a role. Need for backoff to NER type?
     */

    /** Use {@link FeaturePacker} to read/write this */
    byte[] featBuf;
    
    // Pointer to TermDoc so that you can do tf-idf cosine sim with PKB documents
    TermVec tfidf;
    
    // TODO Have pointers to other Tokenizations in the same Communication?
    
    public SentFeats() {
      featBuf = new byte[0];
    }
    
    @Override
    public String toString() {
      java.util.UUID u = new java.util.UUID(comm_uuid_hi, comm_uuid_lo);
      FeaturePacker.Unpacked ff = FeaturePacker.unpack(featBuf);
      return "(Sentence comm=" + u
          + " " + ff
          + " n_words=" + (tfidf == null ? "NA" : tfidf.totalCount)
          + ")";
    }
    
    public String show() {
      return show(0);
    }

    public String show(int termCharLimit) {
      StringBuilder sb = new StringBuilder();
      sb.append("comm=" + getCommunicationUuidString() + "\n");

      FeaturePacker.Unpacked ff = FeaturePacker.unpack(featBuf);

      sb.append("deprel:");
      if (ff.deprels == null || ff.deprels.isEmpty()) {
        sb.append(" NONE");
      } else {
        for (IntTrip t : ff.deprels) {
          String deprel = INVERSE_HASH.get(t.first);
          String arg0 = INVERSE_HASH.get(t.second);
          String arg1 = INVERSE_HASH.get(t.third);
          if (deprel == null) deprel = t.first + "?";
          if (arg0 == null) arg0 = t.second + "?";
          if (arg1 == null) arg1 = t.third + "?";
          sb.append(String.format(" (%s, %s, %s)", deprel, arg0, arg1));
        }
      }
      sb.append('\n');

      sb.append("entities:");
      if (ff.entities == null || ff.entities.isEmpty()) {
        sb.append(" NONE");
      } else {
        for (IntPair e : ff.entities) {
          int type = e.first;
          String ent = INVERSE_HASH.get(e.second);
          if (ent == null) ent = e.second + "?";
          sb.append(String.format(" %s:%d", ent, type));
        }
      }
      sb.append('\n');
      
      sb.append("terms: " + tfidf.showTerms(termCharLimit));
      sb.append('\n');
      
      return sb.toString();
    }
    
    public String getCommunicationUuidString() {
      java.util.UUID u = new java.util.UUID(comm_uuid_hi, comm_uuid_lo);
      return u.toString();
    }
    
    public void setCommunicationUuid(String commUuid) {
      java.util.UUID u = java.util.UUID.fromString(commUuid);
      long old_hi = comm_uuid_hi;
      long old_lo = comm_uuid_lo;
      comm_uuid_hi = u.getMostSignificantBits();
      comm_uuid_lo = u.getLeastSignificantBits();
      assert old_hi == 0 || old_hi == comm_uuid_hi;
      assert old_lo == 0 || old_lo == comm_uuid_lo;
    }
  }
  
  public static class Feat implements Serializable {
    private static final long serialVersionUID = -2723964704627341786L;
  
    /**
     * @returns (cosineSim, commonFeatures)
     */
    public static Pair<Double, List<String>> cosineSim(List<Feat> a, List<Feat> b) {

      double ssa = 0;
      Map<String, Feat> am = index(a);
      for (Feat f : am.values())
        ssa += f.weight * f.weight;
      assert ssa >= 0;

      double ssb = 0;
      Map<String, Feat> bm = index(b);
      for (Feat f : bm.values())
        ssb += f.weight * f.weight;
      assert ssb >= 0;

      double dot = 0;
      List<String> common = new ArrayList<>();
      for (Feat f : bm.values()) {
        Feat ff = am.get(f.name);
        if (ff != null) {
          dot += f.weight * ff.weight;
          common.add(f.name);
        }
      }

      if (dot == 0 || ssa * ssb == 0)
        return new Pair<>(0d, Collections.emptyList());
      
      double cosineSim = dot / (Math.sqrt(ssa) * Math.sqrt(ssb));
      return new Pair<>(cosineSim, common);
    }
    
    @SafeVarargs
    public static Map<String, Feat> index(List<Feat>... features) {
      Map<String, Feat> c = new HashMap<>();
      for (List<Feat> l : features) {
        for (Feat f : l) {
          Feat e = c.get(f.name);
          if (e == null) {
            c.put(f.name, f);
          } else {
            c.put(f.name, new Feat(f.name, f.weight + e.weight));
          }
        }
      }
      return c;
    }
    
    /**
     * interprets the two lists as vectors and adds them (combining Feats with the same name by value-addition).
     */
    public static List<Feat> vecadd(List<Feat> a, List<Feat> b) {
      Map<String, Feat> c = new HashMap<>();
      for (List<Feat> l : Arrays.asList(a, b)) {
        for (Feat f : l) {
          Feat e = c.get(f.name);
          if (e == null) {
            c.put(f.name, f);
          } else {
            c.put(f.name, new Feat(f.name, f.weight + e.weight));
          }
        }
      }
      return new ArrayList<>(c.values());
    }
    
    public static final Comparator<Feat> BY_NAME = new Comparator<Feat>() {
      @Override
      public int compare(Feat o1, Feat o2) {
        return o1.name.compareTo(o2.name);
      }
    };
    public static final Comparator<Feat> BY_SCORE_DESC = new Comparator<Feat>() {
      @Override
      public int compare(Feat o1, Feat o2) {
        assert !Double.isNaN(o1.weight);
        assert Double.isFinite(o1.weight);
        assert !Double.isNaN(o2.weight);
        assert Double.isFinite(o2.weight);
        if (o1.weight > o2.weight)
          return -1;
        if (o2.weight > o1.weight)
          return +1;
        return 0;
      }
    };
    
    public static String showScore(List<Feat> features, int maxChars) {
      List<Feat> out = new ArrayList<>();
      for (Feat f : features)
        out.add(f);
      Collections.sort(out, Feat.BY_SCORE_DESC);
      
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("%+.2f", Feat.sum(features)));
      for (int i = 0; i < out.size(); i++) {
        Feat f = out.get(i);
        String app = " " + f.toString();
        String alt = " and " + (out.size()-i) + " more";
        if (sb.length() + app.length() < maxChars) {
          sb.append(app);
        } else {
          sb.append(alt);
          break;
        }
      }
      return sb.toString();
    }

    public static List<Feat> sortAndPrune(Map<String, Double> in, double eps) {
      List<Feat> l = new ArrayList<>();
      for (Entry<String,  Double> e : in.entrySet()) {
        l.add(new Feat(e.getKey(), e.getValue()));
      }
      return sortAndPrune(l, eps);
    }

    public static List<Feat> sortAndPrune(Map<String, Double> in, int topk) {
      List<Feat> l = new ArrayList<>();
      for (Entry<String,  Double> e : in.entrySet()) {
        l.add(new Feat(e.getKey(), e.getValue()));
      }
      return sortAndPrune(l, topk);
    }

    public static List<Feat> sortAndPrune(Iterable<Feat> in, double eps) {
      List<Feat> out = new ArrayList<>();
      for (Feat f : in)
        if (Math.abs(f.weight) > eps)
          out.add(f);
      //    Collections.sort(out, Feat.BY_NAME);
      return out;
    }

    public static List<Feat> sortAndPrune(Iterable<Feat> in, int topk) {
      List<Feat> out = new ArrayList<>();
      for (Feat f : in)
        out.add(f);
      Collections.sort(out, Feat.BY_SCORE_DESC);
      while (out.size() > topk)
        out.remove(out.size()-1);
      //    Collections.sort(out, Feat.BY_NAME);
      return out;
    }
    
    public static List<String> demote(Iterable<Feat> feats, boolean dedup) {
      Set<String> uniq = new HashSet<>();
      List<String> out = new ArrayList<>();
      for (Feat f : feats)
        if (!dedup || uniq.add(f.name))
          out.add(f.name);
      return out;
    }
    
    public static List<Feat> promote(double value, Iterable<String> feats) {
      return promote(value, false, feats);
    }

    public static List<Feat> promote(double value, boolean dedup, Iterable<String> feats) {
      Set<String> seen = new HashSet<>();
      List<Feat> out = new ArrayList<>();
      for (String f : feats)
        if (!dedup || seen.add(f))
          out.add(new Feat(f, value));
      return out;
    }
    
    public static double sum(Iterable<Feat> features) {
      double s = 0;
      for (Feat f : features)
        s += f.weight;
      return s;
    }
    public static double avg(Iterable<Feat> features) {
      double s = 0;
      int n = 0;
      for (Feat f : features) {
        s += f.weight;
        n++;
      }
      if (n == 0)
        return 0;
      return s / n;
    }

    String name;
    double weight;
    List<String> justifications;    // details which are nice to include, arbitrary values
    
    public Feat(String name) {
      this.name = name;
    }
    public Feat(String name, double weight) {
      this.name = name;
      this.weight = weight;
    }
    
    public String getName() {
      return name;
    }
    
    public Feat rescale(String reason, double factor) {
      this.weight *= factor;
      addJustification(String.format("rescale[%s]=%.2g", reason, factor));
      return this;
    }
    
    public Feat setWeight(double w) {
      this.weight = w;
      return this;
    }
    
    public Feat addJustification(Object... terms) {
      String j = StringUtils.join(" ", terms);
      if (justifications == null)
        justifications = new ArrayList<>();
      justifications.add(j);
      return this;
    }
    
    @Override
    public String toString() {
//      String s = String.format("%-20s %.2f", name, weight);
      String s = String.format("%s %.2f", name, weight);
      if (justifications == null)
        return s;
      String j = StringUtils.join(", ", justifications);
//      return String.format("%-26s b/c %s", s, j);
      return String.format("%s b/c %s", s, j);
    }
  }
  

  public static class SitSearchResult implements Serializable {
    private static final long serialVersionUID = -2345944595589887531L;

    public final String tokUuid;
    public final SentFeats featsResult; // features on the result represented by this instance (as opposed to the query)
    private List<Feat> scoreDerivation;

    // Can be set later, not stored in memory by main pipeline (use scion/accumulo).
    private Communication comm;
    
    // SentFeats/etc store a UUID rather than an id. scion/accumulo needs an id.
    private String commId;
    
    // Features from the EntityRetrieval module
    Result entSearchResult;

    public List<String> importantTerms; // in the document (typically high tf-idf)

    /**
     * These are the features about an entity in a query which are used to look up Tokenizations.
     * See {@link IndexCommunications#getEntityMentionFeatures(String, String[], String, TokenObservationCounts, TokenObservationCounts)}
     */
    public List<String> triageFeatures;
    
    /**
     * A subset of triageFeats which were found in this result, along with the
     * weight they received (based on how common a given feature is).
     */
    public List<WeightedFeature> triageFeaturesMatched;
    

    // Attribute features are things like "PERSON-nn-Dr." which are discriminative modifiers of the entity headword
    /** @deprecated */ public List<String> attributeFeaturesQ; // query
    /** @deprecated */ public List<String> attributeFeaturesR; // response
    /** @deprecated */ public List<String> attributeFeaturesMatched; // intersection
    public List<Feat> attrFeatQ;
    public List<Feat> attrFeatR;

    
    /** Head token index of the mention of the query entity/subject in this tokenization */
    public int yhatQueryEntityHead = -1;
    public String yhatQueryEntityNerType = null;
    public Span yhatQueryEntitySpan = null;   // e.g. see nounPhraseExpand

    /** Head token index of a situtation mention related to the query entity */
    public int yhatEntitySituation = -1;

    public SitSearchResult(String tokUuid, SentFeats featsResult, List<Feat> score) {
      this.tokUuid = tokUuid;
      this.featsResult = featsResult;
      this.scoreDerivation = score;
    }
    
    /** Searches across all tools */
    public List<EntityMention> findMentions() {
      return findMentions(null);
    }
    public List<EntityMention> findMentions(String entityMentionToolName) {
//      FeaturePacker.Unpacked u = FeaturePacker.unpack(featsResult);
//      u.entities  // int pairs!
      if (comm == null)
        throw new IllegalStateException("need to resolve the Communication first");
      List<EntityMention> e = new ArrayList<>();
      for (EntityMentionSet ems : comm.getEntityMentionSetList()) {
        if (entityMentionToolName == null || entityMentionToolName.equals(ems.getMetadata().getTool())) {
          e.addAll(ems.getMentionList());
        }
      }
      return e;
    }
    
    public String getEntityHeadGuess() {
      if (yhatQueryEntityHead < 0)
        throw new IllegalStateException();
      Tokenization toks = getTokenization();
      return toks.getTokenList().getTokenList().get(yhatQueryEntityHead).getText();
    }
    
    public String getEntitySpanGuess() {
      if (yhatQueryEntitySpan == null)
        throw new IllegalStateException();
      Tokenization toks = getTokenization();
      StringBuilder sb = new StringBuilder();
      for (int i = yhatQueryEntitySpan.start; i < yhatQueryEntitySpan.end; i++) {
        if (sb.length() > 0)
          sb.append(' ');
        String s = toks.getTokenList().getTokenList().get(i).getText();
        sb.append(s);
      }
      return sb.toString();
    }
    
    public Tokenization getTokenization() {
      if (comm == null)
        throw new RuntimeException("can't ask for tokenization until communication is provided!");
      Tokenization tok = null;
      for (Section section : comm.getSectionList()) {
        for (Sentence sentence : section.getSentenceList()) {
          Tokenization t = sentence.getTokenization();
          if (tokUuid.equals(t.getUuid().getUuidString())) {
            assert tok == null;
            tok = t;
          }
        }
      }
      return tok;
    }
    
    public List<String> getWordsInTokenization() {
      Tokenization tokenization = findTok(tokUuid, comm);
      List<Token> toks = tokenization.getTokenList().getTokenList();
      List<String> words = new ArrayList<>(toks.size());
      for (Token t : toks)
        words.add(t.getText());
      return words;
    }
    
    /** returns "commId/tokUuidSuf" */
    public String getCommTokIdShort() {
      return getCommunicationId() + "/" + tokUuid.substring(tokUuid.length()-4, tokUuid.length());
    }
    
    public String getWordsInTokenizationWithHighlightedEntAndSit() {
      return getWordsInTokenizationWithHighlightedEntAndSit(true);
    }
    public String getWordsInTokenizationWithHighlightedEntAndSit(boolean includeCommTokId) {
      StringBuilder sb = new StringBuilder();
      if (includeCommTokId) {
        sb.append(getCommTokIdShort());
        sb.append(':');
      }
      if (yhatEntitySituation < 0)
        sb.append(" noSit");
      if (yhatQueryEntityHead < 0)
        sb.append(" noEnt");
      Tokenization tokenization = findTok(tokUuid, comm);
      List<Token> toks = tokenization.getTokenList().getTokenList();
      for (Token t : toks) {
        sb.append(' ');
        if (t.getTokenIndex() == yhatQueryEntityHead)
          sb.append("[ENT]");
        if (t.getTokenIndex() == yhatEntitySituation)
          sb.append("[SIT]");
        sb.append(t.getText());
        if (t.getTokenIndex() == yhatEntitySituation)
          sb.append("[/SIT]");
        if (t.getTokenIndex() == yhatQueryEntityHead)
          sb.append("[/ENT]");
      }
      return sb.toString();
    }
    
    public String getCommunicationId() {
      if (commId == null && comm == null) {
        return null;
      }
      if (commId != null)
        return commId;
      return comm.getId();
    }
    
    public void setCommunicationId(String commId) {
      assert comm == null || comm.getId().equals(commId);
      this.commId = commId;
    }
    
    public Communication setCommunication(Communication c) {
      Communication old = comm;
      comm = c;
      return old;
    }
    
    public Communication getCommunication() {
      return comm;
    }
    
    public void addToScore(String feature, double weight) {
      scoreDerivation.add(new Feat(feature, weight));
    }
    
    public double getScore() {
      double s = 0;
      for (Feat f : scoreDerivation)
        s += f.weight;
      return s;
    }
    
    @Override
    public String toString() {
      return String.format("(SitSearchResult score=%.3f tok=%s has_comm=%s feats=%s)", getScore(), tokUuid, comm!=null, featsResult);
    }
    
    public String show() {
      StringBuilder sb = new StringBuilder();
      sb.append(String.format("(SitSearchResult score=%.3f tok=%s\n", getScore(), tokUuid));
      sb.append(featsResult.show());
      sb.append(')');
      return sb.toString();
    }
    
    public static final Comparator<SitSearchResult> BY_SCORE_DESC = new Comparator<SitSearchResult>() {
      @Override
      public int compare(SitSearchResult o1, SitSearchResult o2) {
        double s1 = o1.getScore();
        double s2 = o2.getScore();
        if (s1 > s2)
          return -1;
        if (s1 < s2)
          return +1;
        return 0;
      }
    };
  }
  
  /**
   * Retrieve situations (FN/PB frames as well as universal schema dependency relations).
   */
  public static class SituationSearch {
    
    /**
     * This is user/session-specific data, comes and goes, not index data.
     */
    static class State {
      List<TermVec> pkbDocs = new ArrayList<>();
      Set<String> pkbTokUuid = new HashSet<>();

      List<StringInt> pkbRel = new ArrayList<>();     // e.g. "appos</PERSON/appos</PERSON/appos>/PERSON/appos>"
      List<StringInt> pkbEnt = new ArrayList<>();     // e.g. "Barack_Obama"
      List<StringInt> pkbFrame = new ArrayList<>();   // e.g. "Commerce_buy"

      List<StringInt> seedRel = new ArrayList<>();    // e.g. "appos</PERSON/appos</PERSON/appos>/PERSON/appos>"
      List<StringInt> seedEnt = new ArrayList<>();    // e.g. "Barack_Obama"
      List<StringInt> seedFrame = new ArrayList<>();  // e.g. "Commerce_buy"
      
      // TODO Have PKB/seed slots, which is a relation + argument position + entity
      // TODO Include list of queries and responses?
      
      public boolean containsEntity(int entity) {
        throw new RuntimeException("implement me");
      }

      public boolean containsPkbEntity(int nerType, int entity) {
        for (StringInt x : pkbEnt)
          if (x.integer == entity)
            return true;
        return false;
      }
      
      /**
       * Compute the distribution over entity types in the PKB
       * with add-lambda smoothing, return mass on a given type.
       */
      public double pEntityType(int nerType, double lambda) {
        throw new RuntimeException("implement me");
      }
      
      public boolean containsPkbDeprel(int deprel) {
        for (StringInt x : pkbRel)
          if (x.integer == deprel)
            return true;
        return false;
      }
    }

    // PKB, seeds, etc.
    private transient State state;
    
    
    // Should I just be constructing tok2feats on the fly?
    // As in I find some tokenizations to score using the inverted indices,
    // fetch the Communication, then re-extract features?
    // There is a distinction between feature EXTRACTION (parma) and building features from inverted indices (used in SituationSearch.score).
    // Right now I pay the cost of feature extraction 
    
    
    // Contains ~200M entries? ~20GB?
    private Map<String, SentFeats> tok2feats;  // keys are Tokenization UUID
    
    // These let you look up Tokenizations given some features
    // which may appear in a seed or a query.
    private StringIntUuidIndex deprel;        // dependency path string, hash("entityWord"), Tokenization UUID+
    private StringIntUuidIndex situation;     // SituationMention kind, hash("role/entityWord"), Tokenization UUID+
    private NerFeatureInvertedIndex entity;   // NER type, hash(entity word), Tokenization UUID+
    
    // Pretty small, used for cosine similarity
    private IntDoubleHashMap idf;
    
    // These are optional, talk to scion/accumulo server
//    private CommunicationRetrieval commRet;   // get the Communication => Sentence for NaturalLanguageSlotFill
//    private NaturalLanguageSlotFill nlsf;     // re-score sentences based on alignment to seed expressions
    
    // Misc
    private MultiTimer tm;

    boolean showResultsBeforeRescoring = false;

    /**
     * @param tokUuid2commUuid has lines like: <tokenizationUuid> <tab> <communicationUuid>
     * @param commRet may be null (optional). If non-null, resolve {@link ForwardedFetchCommunicationRetrieval}s
     *        during scoring, enables {@link NaturalLanguageSlotFill}.
     */
    public SituationSearch(File tokUuid2commUuid, TfIdfDocumentStore docVecs) throws IOException {
      TimeMarker tmk = new TimeMarker();
      this.tm = new MultiTimer();
      this.idf = docVecs.getIdfs();
      
      INVERSE_HASH = new IntObjectHashMap<>();
      readInverseHash(new File(HOME, "raw/termHash.sortu.txt.gz"), INVERSE_HASH);
      readInverseHash(new File(HOME, "deprel/hashedArgs.approx.txt.gz"), INVERSE_HASH);
      
      // TODO Populate [deprel, situation, entity] => Tokenization UUID maps
      tm.start("load/PERSON");
      entity = new NerFeatureInvertedIndex(Collections.emptyList());
      entity.putIntLines("PERSON", new File(HOME, "ner_feats/nerFeats.PERSON.txt"));
      tm.stop("load/PERSON");
      
      // NOTE: Currently this is very fine grain, e.g.
      // key = ("appos>/chairman/prep>/of/pobj>", hash("ARG1/ORGANIZATION/NBC"))
      // The datastructure lets me iterate over all int values, so I could search by deprel.
      tm.start("load/deprel");
      deprel = new StringIntUuidIndex();
      deprel.addStringIntLines(new File(HOME, "deprel/rel-arg-tok.txt.gz"));
      tm.stop("load/deprel");
      

      /* Populate SentFeats **************************************************/

      // Deprels
      tm.start("load/feats/deprel");
      tok2feats = new HashMap<>();
      File f = new File(HOME, "deprel/deprels.txt.gz");
      Log.info("loading deprel features from " + f.getPath());
      int read = 0;
      try (BufferedReader r = FileUtil.getReader(f)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          String deprel = ar[0];
          String tokUuid = ar[7];
          
          // This may not have made its way into the inverse hash
          addInverseHash(deprel, INVERSE_HASH);

          String a0Type = ar[2];
          String a0Head = ar[3];
          String a1Type = ar[5];
          String a1Head = ar[6];
          int arg0 = ReversableHashWriter.onewayHash("arg0/" + a0Type + "/" + a0Head);
          int arg1 = ReversableHashWriter.onewayHash("arg1/" + a1Type + "/" + a1Head);
//          int arg0 = ReversableHashWriter.onewayHash(ar[3]);
//          int arg1 = ReversableHashWriter.onewayHash(ar[6]);
          if ("ARG0".equalsIgnoreCase(ar[1])) {
            // no-op
          } else {
            assert "ARG1".equalsIgnoreCase(ar[1]);
            // swap
            int t;
            t = arg0; arg0 = arg1; arg1 = t;
          }
          SentFeats sf = getOrPutNew(tokUuid);
          assert sf != null;
          int hd = ReversableHashWriter.onewayHash(deprel);
          FeaturePacker.writeDeprel(hd, arg0, arg1, sf);
          
          read++;
          if (tmk.enoughTimePassed(5)) {
            Log.info("read " + read + " deprel features");
          }
        }
      }
      tm.stop("load/feats/deprel");
      

      // Entities
      tm.start("load/feats/entity");
      Log.info("copying entity features from index into SentFeats... (n=" + entity.getNumEntries() + ")");
      read = 0;
      for (StrIntUuidEntry x : entity) {
        SentFeats sf = getOrPutNew(x.uuid);
//        sf.addEntity(x.integer);
        byte nerType = 0;    // TODO
        FeaturePacker.writeEntity(x.integer, nerType, sf);
        EC.increment("feat/entity");
        read++;
        if (tmk.enoughTimePassed(5)) {
          Log.info("converted " + read + " entity features\t" + Describe.memoryUsage());
        }
      }
      tm.stop("load/feats/entity");

      // TODO situations
      
      
      /* *********************************************************************/
      // Read in the Communication UUID for every Tokenization UUID in the input
      Log.info("we know about " + tok2feats.size() + " Tokenizations,"
          + " looking for the docVecs for them in " + tokUuid2commUuid.getPath());
      tm.start("load/docVec/link");
      read = 0;
      int nd = 0;
      try (BufferedReader r = FileUtil.getReader(tokUuid2commUuid)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          if (ar.length == 0) {
            Log.info("why is there a line with a single tab in: " + tokUuid2commUuid.getPath());
            continue;
          }
          assert ar.length == 2;
          String tokUuid = ar[0];
          SentFeats sf = tok2feats.get(tokUuid);
          if (sf != null) {
            nd++;
            sf.setCommunicationUuid(ar[1]);
            sf.tfidf = docVecs.get(ar[1]);
            assert sf.tfidf != null;
            EC.increment("docVec");
          } else {
            EC.increment("skip/docVec");
          }
          
          read++;
          if (tmk.enoughTimePassed(5)) {
            Log.info("read " + read + " (Tokenization UUID, Communication UUID) pairs"
                + " and built the TermVecs for " + nd + " docs");
          }
        }
      }
      tm.stop("load/docVec/link");
      
      state = new State();
      Log.info("done setup, " + EC);
    }
    
    private SentFeats getOrPutNew(String tokUuid) {
      SentFeats sf = tok2feats.get(tokUuid);
      if (sf == null) {
        sf = new SentFeats();
        tok2feats.put(tokUuid, sf);
        EC.increment("tokenization");
      }
      return sf;
    }
    
    public void clearState() {
      state = new State();
    }
    
    public void seed(String s) {
      // TODO detect variables like "X" and "Y"
      // TODO Parse s and/or run semafor/etc
      // TODO extract deprel, situation, entity
      // TODO Add to state
//      throw new RuntimeException("implement me");
      
      Log.info("WARNING: assuming this is a deprel: " + s);
      state.seedRel.add(h(s));
    }
    
    public void addToPkb(SitSearchResult response) {
//      Log.info("response: " + response);
      
      SentFeats f = response.featsResult;
      FeaturePacker.Unpacked ff = FeaturePacker.unpack(f.featBuf);
      
      assert f.tfidf != null;
      state.pkbDocs.add(f.tfidf);

      // TODO entity features need to follow "p:" "pi:" and "pb:" extractions from below
      for (IntPair e : ff.entities) {
        state.pkbEnt.add(new StringInt("NA", e.second));
      }
      
      // TODO Situations
      
      for (IntTrip d : ff.deprels)
        state.pkbRel.add(new StringInt("NA", d.first));
      
      state.pkbTokUuid.add(response.tokUuid);
    }
    
    /**
     * Doesn't resolve {@link Communication}s for the results.
     *
     * @param entityName e.g. "President Barack_Obama"
     * @param entityType e.g. "PERSON"
     * @param limit is how many items to return (max)
     */
    public List<SitSearchResult> query(String entityName, String entityType, String[] headwords, int limit) {
      tm.start("query");
      Log.info("entityName=" + entityName + " entityType=" + entityType);
      
      // TODO
      TokenObservationCounts tokObs = null;
      TokenObservationCounts tokObsLc = null;
      // Lookup mentions for the query entity
      List<Result> toks = entity.findMentionsMatching(entityName, entityType, headwords, tokObs, tokObsLc);

      // Prune the results which are almost certainly wrong
      double minNameMatchScore = 0.01;
      for (int i = 0; i < toks.size(); i++) {
        assert i == 0 || toks.get(i-1).score <= toks.get(i).score : "should be non-increasing";
        if (toks.get(i).score < minNameMatchScore) {
          Log.info("pruning the top=" + i + " results of=" + toks.size() + " minNameMatchScore=" + minNameMatchScore);
          toks = toks.subList(0, i);
          break;
        }
      }
      
      List<Result> ts = toks.size() > 20 ? toks.subList(0, 20) : toks;
      Log.info("entity results initial (" + toks.size() + "): " + ts);
      
      // Re-score mentions based on seeds/PKB
      List<SitSearchResult> scored = new ArrayList<>();
      for (int i = 0; i < toks.size(); i++) {
        Result t = toks.get(i);
        
        if (showResultsBeforeRescoring) {
          System.out.printf("[SS query] entityName=\"%s\" entityType=%s limit=%d resultIdx=%d resultTok=%s\n",
              entityName, entityType, limit, i, t.tokenizationUuid);
        }

        String tokUuid = t.tokenizationUuid;
        if (state.pkbTokUuid.contains(tokUuid)) {
          Log.info("skipping tok=" + t + " because it is already in the PKB");
          continue;
        }
        
        double nameMatchScore = t.score;
        SitSearchResult r = score(tokUuid, nameMatchScore);
        r.entSearchResult = t;
        scored.add(r);
      }
      Collections.sort(scored, SitSearchResult.BY_SCORE_DESC);
      
      if (limit > 0 && scored.size() > limit)
        scored = scored.subList(0, limit);
      
      if (scored.isEmpty()) {
        EC.increment("sitSearch/noResults");
        Log.info("no result for " + entityName + ":" + entityType);
      }

      tm.stop("query");
      return scored;
    }
    
    /**
     * Score a retrieved sentence/tokenization against seed and PKB items.
     */
    private SitSearchResult score(String tokUuid, double nameMatchScore) {
      tm.start("score");
      boolean verbose = false;
      
      /*
       * entMentions WILL be Tokenization UUIDs.
       * Go through each of them and boost their score if they:
       * 1) contain a deprel which is a seed
       * 2) contain an entity type/sim which is a seed
       * 3) contain an entity in the PKB
       * 4) etc for seed/PKB situations
       */
      
      SentFeats f = tok2feats.get(tokUuid);
      FeaturePacker.Unpacked ff = FeaturePacker.unpack(f.featBuf);

      if (verbose) {
        Log.info("f.tok=" + tokUuid);
        Log.info("f.comm=" + f.getCommunicationUuidString());
      }
      
      Feat tfidfAvg = new Feat("tfidf/avg");
      Feat tfidfMax = new Feat("tfidf/max");
      Feat pkbDocMatch = new Feat("pkbDocMatch");
      for (TermVec d : state.pkbDocs) {
        double t = TermVec.tfidfCosineSim(f.tfidf, d, idf);
        tfidfAvg.weight += t;
        if (f.tfidf == d)
          pkbDocMatch.weight += 1;
        if (t > tfidfMax.weight)
          tfidfMax.weight = t;
      }
      double z = Math.sqrt(state.pkbDocs.size() + 1);
      tfidfAvg.rescale("nPkbDoc=" + state.pkbDocs.size(), 1/z).rescale("weight", 20);
      pkbDocMatch.rescale("nPkbDoc=" + state.pkbDocs.size(), 1/z).rescale("weight", 2);
      
      // TODO Currently we measure a hard "this entity/deprel/situation/etc
      // showed up in the result and the PKB/seed", but we should generalize
      // this to be similarity of the embeddings.
      
      Feat entSeed = new Feat("ent/seed").addJustification("TODO(impl)");
      Feat entPkb = new Feat("ent/pkb");
      for (IntPair et : ff.entities) {
        if (state.containsPkbEntity(et.first, et.second))
          entPkb.weight += 1;
      }
      z = Math.sqrt(ff.entities.size() + 1);
      entPkb.rescale("nEnt=" + ff.entities.size(), 1/z);
      
      // TODO
      Feat scoreSeedSit = new Feat("sit/seed").addJustification("TODO(impl)");
      Feat scorePkbSit = new Feat("sit/pkb").addJustification("TODO(impl)");
      
      Feat deprelSeed = new Feat("deprel/seed").addJustification("TODO(impl)");
      Feat deprelPkb = new Feat("deprel/pkb");
      for (IntTrip d : ff.deprels) {
        int deprel = d.first;
        int arg0 = d.second;
        int arg1 = d.third;
        if (state.containsPkbDeprel(deprel)) {
          boolean ca0 = state.containsEntity(arg0);
          boolean ca1 = state.containsEntity(arg1);
          if (ca0 && ca1) {
            deprelPkb.addJustification("both/deprel=" + deprel, "both/arg0=" + arg0, "both/arg1=" + arg1);
            deprelPkb.weight += 3;
          }
          if (ca0 || ca1) {
            int a = ca0 ? arg0 : arg1;
            deprelPkb.addJustification("one/deprel=" + deprel, "one/arg=" + a, "one/arg0?=" + ca0);
            deprelPkb.weight += 1;
          }
        }
      }
      z = Math.sqrt(ff.deprels.size() + 1);
      deprelPkb.rescale("nDeprel=" + ff.deprels.size(), 1/z);
      
      Feat nm = new Feat("name").setWeight(nameMatchScore);
      
      
//      // Natural language expressions for slot filling relations
//      if (this.commRet != null) {
//        String commId = fuck i have no way to get from comm uuid to id.
//        commRet.get(commId);
//        Sentence passage = convert(f);
//        List<NaturalLanguageSlotFill.Match> matches = nlsf.scoreAll(passage);
//      }
      

      tm.stop("score");
      List<Feat> score = new ArrayList<>();
      score.addAll(Arrays.asList(nm,
          tfidfAvg, pkbDocMatch,
          deprelPkb, deprelSeed,
          scorePkbSit, scoreSeedSit,
          entPkb, entSeed));
      return new SitSearchResult(tokUuid, f, score);
    }
    
    public static Pair<SituationSearch, TfIdfDocumentStore> build(ExperimentProperties config) throws IOException {
      File tokUuid2commUuid = config.getExistingFile("tok2comm", new File(HOME, "tokUuid2commUuid.txt"));

      File docVecs = config.getExistingFile("docVecs", new File(HOME, "doc/docVecs." + N_DOC_TERMS + ".txt.gz"));
      File idf = config.getExistingFile("idf", new File(HOME, "doc/idf.txt"));
      TfIdfDocumentStore docs = new TfIdfDocumentStore(docVecs, idf);

      SituationSearch ss = new SituationSearch(tokUuid2commUuid, docs);
      return new Pair<>(ss, docs);
    }
    
    public static void main(ExperimentProperties config) throws IOException {
      
      File tokUuid2commUuid = config.getExistingFile("tok2comm", new File(HOME, "tokUuid2commUuid.txt"));

      File docVecs = config.getExistingFile("docVecs", new File(HOME, "doc/docVecs." + N_DOC_TERMS + ".txt"));
      File idf = config.getExistingFile("idf", new File(HOME, "doc/idf.txt"));
      TfIdfDocumentStore docs = new TfIdfDocumentStore(docVecs, idf);

      SituationSearch ss = new SituationSearch(tokUuid2commUuid, docs);

//      ss.seed("conj>/leaders/prep>/of/pobj>");    // This appears to be a pruned relation in the small data case
      ss.seed("conj>");

      int responseLim = 20;
      List<SitSearchResult> results = ss.query("Barack_Obama", "PERSON", new String[] {"Obama"}, responseLim);
      for (int i = 0; i < results.size(); i++) {
        System.out.println("res1[" + i + "]: " + results.get(i).show());
      }
      
      for (int i = 0; i < 20; i++)
        System.out.println();
      
      // Lets suppose that the user like the second response
      // Add it to the PKB and see if we can get the PKB/tfidf features to fire
      SitSearchResult userLiked = results.get(1);
      ss.addToPkb(userLiked);
      
      // Lets just re-do the same query
      List<SitSearchResult> results2 = ss.query("Barack_Obama", "PERSON", new String[] {"Obama"}, responseLim);
      for (int i = 0; i < results2.size(); i++) {
        System.out.println("res2[" + i + "]: " + results2.get(i).show());
      }
      
      Log.info("done\n" + ss.tm);
    }
  }

  private static StringInt h(String s) {
    int h = ReversableHashWriter.onewayHash(s);
    return new StringInt(s, h);
  }
  
  
  /**
   * After pruning, run this to do the conversion:
   * (deprel, arg0, arg1, location) => 2 instances of (deprel, hash(argpos, argval), Tokenization UUID)
   * NOTE: Output is and UNSORTED version of input for {@link StringIntUuidIndex}.
   * Also outputs a argHash file mapping strings like "ARG0/DATE/July" to their hashed values, e.g. sit_feats/index_deprel/hashedArgs.txt.gz
   */
  public static class HashDeprels {
    /**
     * @see IndexDeprels#writeDepRels(Communication, Map) for line format
     */
    public static void main(ExperimentProperties config) throws IOException {
      File input = config.getExistingFile("input");
      File output = config.getFile("output"); // may want to be /dev/stdout so you can pipe into sort
      File alph = config.getFile("argHash");
      
      try (BufferedWriter w = FileUtil.getWriter(output);
          BufferedReader r = FileUtil.getReader(input);
          ReversableHashWriter a = new ReversableHashWriter(alph);) {
        
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          assert ar.length == 10;
          
          String deprel = ar[0];
//          String role0 = ar[1];
//          String type0 = ar[2];
          String head0 = ar[3];
//          String role1 = ar[4];
//          String type1 = ar[5];
          String head1 = ar[6];
          String tokUuid = ar[7];
//          String commUuid = ar[8];
//          String commId = ar[9];
          
//          int h0 = a.hash(role0 + "/" + type0 + "/" + head0);
//          int h1 = a.hash(role1 + "/" + type1 + "/" + head1);
          int h0 = a.hash(head0);
          int h1 = a.hash(head1);
          
          w.write(deprel);
          w.write('\t');
          w.write(Integer.toUnsignedString(h0));
          w.write('\t');
          w.write(tokUuid);
          w.newLine();
          
          w.write(deprel);
          w.write('\t');
          w.write(Integer.toUnsignedString(h1));
          w.write('\t');
          w.write(tokUuid);
          w.newLine();
        }
      }
    }
  }
  

  /**
   * Indexes frames (just FrameNet for now).
   * 
   * Produces two tables:
   * - for retrieval: frame, hash(argHead), Tokenization UUID
   * - for embedding: entity, frame, roleEntPlaysInFrame, roleAlt, argAltHead
   *
   * WAIT: (For indexing) we have two things to play with:
   * string/outerKey/exactMatch/userQueryDirected:  entity name, e.g. "Barack_Obama", maybe "PERSON/Barack_Obama"
   * int/innerKey/couldEnumerate/seedPkbDirected:   frame/role/argHead, e.g. "Commerce_buy/Seller/McDonalds" or "Commerce_buy/Buyer/IDENT"
   * 
   * As long as we can embed (matrix factorize) both [entityName, frameRoleArgHead], we can have a similarity over both.
   * Initially I think I should ignore similarity_{entityName} and just do exact matching on the query entityName.
   * similarity_{frameRoleArg} can be used to smooth over instances of frameRoleArgHead in the PKB/seeds
   */
  public static class IndexFrames implements AutoCloseable {

    public static void main(ExperimentProperties config) throws Exception {
      File outputDir = config.getOrMakeDir("outputDirectory", new File(HOME, "frame"));
      String nerTool = config.getString("nerTool", "Stanford CoreNLP");
      try (IndexFrames is = new IndexFrames(nerTool,
          new File(outputDir, "frames.forRetrieval.txt.gz"),
          new File(outputDir, "frames.forEmbedding.txt.gz"),
          new File(outputDir, "tokUuid_commUuid_commId.txt.gz"),
          new File(outputDir, "hashedTerms.approx.txt.gz"));
          AutoCloseableIterator<Communication> iter = getCommunicationsForIngest(config)) {
        while (iter.hasNext()) {
          Communication c = iter.next();
          is.observe(c);
        }
      }
    }

    private String nerTool;
    private Set<String> retrievalEntityTypes;
    private ReversableHashWriter revAlph;
    private BufferedWriter w_forRetrieval;
    private BufferedWriter w_forEmbedding;
    private BufferedWriter w_tok2comm;

    // Misc
    private TimeMarker tm;
    
    public IndexFrames(String nerTool, File f_forRetrieval, File f_forEmbedding, File tok2comm, File revAlph) throws IOException {
      Log.info("f_forRetrieval=" + f_forRetrieval.getPath());
      Log.info("f_forEmbedding=" + f_forEmbedding.getPath());
      Log.info("tok2comm=" + tok2comm.getPath());
      Log.info("revAlph=" + revAlph.getPath());
      this.w_forRetrieval = FileUtil.getWriter(f_forRetrieval);
      this.w_forEmbedding = FileUtil.getWriter(f_forEmbedding);
      this.w_tok2comm = FileUtil.getWriter(tok2comm);
      this.nerTool = nerTool;
      this.tm = new TimeMarker();
      
      this.revAlph = new ReversableHashWriter(revAlph);
      
      this.retrievalEntityTypes = new HashSet<>();
      this.retrievalEntityTypes.addAll(Arrays.asList(
          "PERSON",
          "LOCATION",
          "ORGANIZATION",
          "MISC"));
//      415620 PERSON
//      238344 LOCATION
//      228197 ORGANIZATION
//      201512 NUMBER
//      144665 DATE
//       61295 MISC
//       45475 DURATION
//       32155 ORDINAL
//       12266 TIME
//        1541 SET
    }

    public void observe(File commTgzArchive) throws IOException {
      EC.increment("files");
      Log.info("reading from " + commTgzArchive.getPath());
      try (InputStream is = new FileInputStream(commTgzArchive);
          TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
        while (iter.hasNext()) {
          Communication c = iter.next();
          observe(c);
        }
      }
    }
    
    public void observe(Communication c) throws IOException {
      EC.increment("communications");
      try {
      Map<String, Tokenization> tmap = AddNerTypeToEntityMentions.buildTokzIndex(c);
      writeSituations(c, tmap);
      if (tm.enoughTimePassed(5))
        Log.info(c.getId() + "\t" + EC + "\t" + Describe.memoryUsage());
      } catch (Exception e) {
        System.out.flush();
        System.err.println("on comm=" + c.getId());
        e.printStackTrace();
        System.err.flush();
        EC.increment("err/" + e.getClass());
      }
    }

    private void writeSituations(Communication c, Map<String, Tokenization> tmap) throws IOException {
      if (c.isSetSituationMentionSetList()) {
        for (SituationMentionSet sms : c.getSituationMentionSetList()) {
          if (sms.isSetMentionList()) {
            for (SituationMention sm : sms.getMentionList()) {

              TokenRefSequence sitMention = sm.getTokens();
              assert tmap.get(sitMention.getTokenizationId().getUuidString()) != null;

              int n = sm.getArgumentListSize();
              for (int i = 0; i < n; i++) {
                MentionArgument a = sm.getArgumentList().get(i);

                // Do not output if argument doens't contain a named entity
                TokenRefSequence argMention = a.getTokens();
                String nerType = AddNerTypeToEntityMentions.getNerType(argMention, tmap, nerTool);
                if ("O".equals(nerType)) {
                  EC.increment("skip/arguments/O");
                  continue;
                }
                if (nerType.split(",").length > 1) {
                  EC.increment("skip/arguments/complexType");
                  continue;
                }

                boolean takeNnCompounds = true;
                boolean allowFailures = true;
                String argHead = headword(argMention, tmap, takeNnCompounds, allowFailures);
                if (argHead == null) {
                  EC.increment("err/headfinder");
                  continue;
                }
                assert argHead.split("\\s+").length == 1;
                String tokId = argMention.getTokenizationId().getUuidString();
                assert tokId.equals(sitMention.getTokenizationId().getUuidString());
                
                String target = headword(sm.getTokens(), tmap, takeNnCompounds, allowFailures);

                // for retrieval: frame/role, hash(argHead), Tokenization UUID
                if (retrievalEntityTypes.contains(nerType)) {
                  w_forRetrieval.write(sm.getSituationKind()
                      + "/" + a.getRole()
                      //                    + "\t" + nerType
                      + "\t" + revAlph.hash(argHead)
                      + "\t" + tokId);
                  w_forRetrieval.newLine();
                  
                  w_tok2comm.write(tokId
                      + "\t" + c.getUuid().getUuidString()
                      + "\t" + c.getId());
                  w_tok2comm.newLine();
                }
                
                for (int j = 0; j < n; j++) {
                  if (j == i) continue;
                  MentionArgument aa = sm.getArgumentList().get(j);
                  String argHeadAlt = headword(aa.getTokens(), tmap, takeNnCompounds, allowFailures);
                  String nerTypeAlt = AddNerTypeToEntityMentions.getNerType(aa.getTokens(), tmap, nerTool);
                  if ("O".equals(nerTypeAlt)) {
                    EC.increment("skip/arguments2/O");
                    continue;
                  }
                  if (nerTypeAlt.split(",").length > 1) {
                    EC.increment("skip/arguments2/complexType");
                    continue;
                  }
                  
                  // for embedding: entity, frame, roleEntPlaysInFrame, roleAlt, argAltHead
                  w_forEmbedding.write(sm.getSituationKind()
                      + "\t" + target
                      + "\t" + a.getRole()
                      + "\t" + nerType
                      + "\t" + argHead
                      + "\t" + aa.getRole()
                      + "\t" + nerTypeAlt
                      + "\t" + argHeadAlt);
                  w_forEmbedding.newLine();
                }
                
                EC.increment("arguments");
              }
            }
          }
        }
      }
    }


    @Override
    public void close() throws Exception {
      Log.info("closing, " + EC);
      if (w_forRetrieval != null) {
        w_forRetrieval.close();
        w_forRetrieval = null;
      }
      if (w_forEmbedding != null) {
        w_forEmbedding.close();
        w_forEmbedding = null;
      }
      if (w_tok2comm != null) {
        w_tok2comm.close();
        w_tok2comm = null;
      }
      if (revAlph != null) {
        revAlph.close();
        revAlph = null;
      }
    }
  }

  
  /**
   * Extracts depenency-path relations (deprels).
   *
   * Currently (2016-11-08) setup for
   * edu.jhu.hlt.framenet.semafor.parsing.JHUParserDriver via Semafor V2.1 4.10.3-SNAPSHOT
   * /export/projects/fferraro/cag-4.6.10/processing/from-marcc/20161012-083257/gigaword-merged
   * $FNPARSE/data/concretely-annotated-gigaword/sample-with-semafor-nov08
   */
  public static class IndexDeprels implements AutoCloseable {

    public static void main(ExperimentProperties config) throws Exception {
      File outputDir = config.getOrMakeDir("outputDirectory", new File(HOME, "deprel"));
      String nerTool = config.getString("nerTool", "Stanford CoreNLP");
      
      // This includes the two headwords on either end of the path.
      int maxWordsOnDeprelPath = config.getInt("maxWordsOnDeprelPath", 5);

      try (IndexDeprels is = new IndexDeprels(
          nerTool,
          maxWordsOnDeprelPath,
          new File(outputDir, "hashedArgs.approx.txt.gz"),
          new File(outputDir, "deprels.txt.gz"),
          new File(outputDir, "rel-arg-tok.txt.gz"));
          AutoCloseableIterator<Communication> iter = getCommunicationsForIngest(config)) {
        while (iter.hasNext()) {
          Communication c = iter.next();
          is.observe(c);
        }
      }
    }
    
    private String nerTool;
    private Set<String> deprelEntityTypeEnpoints;
    private int maxWordsOnDeprelPath;
    
    private BufferedWriter w_deprel;
    private BufferedWriter w_deprel_arg_tok;
    private ReversableHashWriter hash;

    // Misc
    private TimeMarker tm;
    
    public IndexDeprels(String nerTool, int maxWordsOnDeprelPath, File argHash, File f_deprel, File f_deprel_arg_tok) throws IOException {
      Log.info("nerTool=" + nerTool);
      Log.info("maxWordsOnDeprelPath=" + maxWordsOnDeprelPath);
      this.nerTool = nerTool;
      this.maxWordsOnDeprelPath = maxWordsOnDeprelPath;
      this.tm =  new TimeMarker();
      Log.info("writing deprels to " + f_deprel.getPath());
      w_deprel = FileUtil.getWriter(f_deprel);
      w_deprel_arg_tok = FileUtil.getWriter(f_deprel_arg_tok);
      hash = new ReversableHashWriter(argHash);
      
      this.deprelEntityTypeEnpoints = new HashSet<>();
      this.deprelEntityTypeEnpoints.addAll(Arrays.asList(
          "PERSON",
          "LOCATION",
          "ORGANIZATION",
          "DATE",
          "MISC"));
//      415620 PERSON
//      238344 LOCATION
//      228197 ORGANIZATION
//      201512 NUMBER
//      144665 DATE
//       61295 MISC
//       45475 DURATION
//       32155 ORDINAL
//       12266 TIME
//        1541 SET
    }
    
    public void observe(File commTgzArchive) throws IOException {
      EC.increment("files");
      Log.info("reading from " + commTgzArchive.getPath());
      try (InputStream is = new FileInputStream(commTgzArchive);
          TarGzArchiveEntryCommunicationIterator iter = new TarGzArchiveEntryCommunicationIterator(is)) {
        while (iter.hasNext()) {
          Communication c = iter.next();
          observe(c);
        }
      }
    }
    
    public void observe(Communication c) throws IOException {
      EC.increment("communications");
      try {
        Map<String, Tokenization> tmap = AddNerTypeToEntityMentions.buildTokzIndex(c);
        writeDepRels(c, tmap);
        if (tm.enoughTimePassed(5))
          Log.info(c.getId() + "\t" + EC + "\t" + Describe.memoryUsage());
      } catch (Exception e) {
        System.out.flush();
        System.err.println("on comm=" + c.getId());
        e.printStackTrace();
        System.err.flush();
        EC.increment("err/" + e.getClass());
      }
    }
    
    private void writeDepRels(Communication comm, Map<String, Tokenization> tmap) throws IOException {
      new AddNerTypeToEntityMentions(comm, tmap);
      List<EntityMention> mentions = getEntityMentions(comm);
      
      // Go over every pair of mentions in the same sentence, and output
      // a deprel fact if there is a relatively short path that connects
      // the two entities.
      Map<String, List<EntityMention>> bySent = groupBySentence(mentions);
      for (List<EntityMention> ms : bySent.values()) {
        
        int n = ms.size();
        for (int i = 0; i < n-1; i++) {
          for (int j = i+1; j < n; j++) {
            EntityMention a0 = ms.get(i);
            EntityMention a1 = ms.get(j);

            if (overlap(a0.getTokens(), a1.getTokens()))
              continue;
            
            String a0Type = AddNerTypeToEntityMentions.getNerType(a0.getTokens(), tmap, nerTool);
            String a1Type = AddNerTypeToEntityMentions.getNerType(a1.getTokens(), tmap, nerTool);
            if (!deprelEntityTypeEnpoints.contains(a0Type))
              continue;
            if (!deprelEntityTypeEnpoints.contains(a1Type))
              continue;
            
            Pair<String, Boolean> d = deprelBetween(
                a0.getTokens(), a1.getTokens(), tmap, maxWordsOnDeprelPath);
            if (d == null)
              continue;

            boolean takeNnCompounts = true;
            boolean allowFailures = true;
            String a0Head = headword(a0.getTokens(), tmap, takeNnCompounts, allowFailures);
            String a1Head = headword(a1.getTokens(), tmap, takeNnCompounts, allowFailures);
            if (a0Head == null || a1Head == null) {
              EC.increment("err/headfinder");
              continue;
            }

            assert a0Head.split("\\s+").length == 1;
            assert a1Head.split("\\s+").length == 1;
            String tokId = a0.getTokens().getTokenizationId().getUuidString();
            assert tokId.equals(a1.getTokens().getTokenizationId().getUuidString());

            w_deprel.write(d.get1()
                + "\t" + (d.get2() ? "ARG1" : "ARG0")
                + "\t" + a0Type
                + "\t" + a0Head
                + "\t" + (d.get2() ? "ARG0" : "ARG1")
                + "\t" + a1Type
                + "\t" + a1Head
                + "\t" + tokId
                + "\t" + comm.getUuid().getUuidString()
                + "\t" + comm.getId());
            w_deprel.newLine();

            int a0h = hash.hash("arg0/" + a0Type + "/" + a0Head);
            int a1h = hash.hash("arg1/" + a1Type + "/" + a1Head);
            w_deprel_arg_tok.write(d.get1()
                + "\t" + Integer.toUnsignedString(a0h)
                + "\t" + tokId);
            w_deprel_arg_tok.newLine();
            w_deprel_arg_tok.write(d.get1()
                + "\t" + Integer.toUnsignedString(a1h)
                + "\t" + tokId);
            w_deprel_arg_tok.newLine();
          }
        }
      }
    }
    
    public static boolean overlap(TokenRefSequence a, TokenRefSequence b) {
      BitSet x = new BitSet();
      for (int i : a.getTokenIndexList())
        x.set(i);
      for (int i : b.getTokenIndexList())
        if (x.get(i))
          return true;
      return false;
    }
    
    private static Pair<String, Boolean> deprelBetween(
        TokenRefSequence a, TokenRefSequence b, Map<String, Tokenization> tmap, int maxWordsOnDeprelPath) {
      
      assert a.getTokenizationId().getUuidString().equals(b.getTokenizationId().getUuidString());
      Tokenization t = tmap.get(a.getTokenizationId().getUuidString());
      List<Token> toks = t.getTokenList().getTokenList();
      
      int n = toks.size();
      DependencyParse d = getPreferredDependencyParse(t);
      MultiAlphabet alph = new MultiAlphabet();
      LabeledDirectedGraph graph =
          LabeledDirectedGraph.fromConcrete(d, n, alph);
      DParseHeadFinder hf = new DParseHeadFinder();
      hf.ignoreEdge(alph.dep("prep"));
      hf.ignoreEdge(alph.dep("poss"));
      hf.ignoreEdge(alph.dep("possessive"));

      List<Integer> ts;
      int first, last;
      int ha, hb;

      try {
        ts = a.getTokenIndexList();
        first = ts.get(0);
        last = ts.get(ts.size()-1);
        ha = hf.head(graph, first, last);

        ts = b.getTokenIndexList();
        first = ts.get(0);
        last = ts.get(ts.size()-1);
        hb = hf.head(graph, first, last);
      } catch (RuntimeException e) {
        err_head++;
        Log.info("head finding error: " + e.getMessage());
        return null;
      }

      boolean flip = false;
      if (ha > hb) {
        flip = true;
        int temp = ha;
        ha = hb;
        hb = temp;
      }
      
      boolean allowMultipleHeads = true;
      edu.jhu.hlt.fnparse.datatypes.DependencyParse fndp = null;
      edu.jhu.hlt.fnparse.datatypes.Sentence sent = null;
      try {
        fndp = new edu.jhu.hlt.fnparse.datatypes.DependencyParse(graph, alph, 0, n, allowMultipleHeads);
        sent = edu.jhu.hlt.fnparse.datatypes.Sentence.convertFromConcrete("ds", "id", t,
            edu.jhu.hlt.fnparse.datatypes.Sentence.takeParseyOr(edu.jhu.hlt.fnparse.datatypes.Sentence.KEEP_LAST),
            edu.jhu.hlt.fnparse.datatypes.Sentence.takeParseyOr(edu.jhu.hlt.fnparse.datatypes.Sentence.KEEP_LAST),
            edu.jhu.hlt.fnparse.datatypes.Sentence.takeParseyOr(edu.jhu.hlt.fnparse.datatypes.Sentence.KEEP_LAST));
      } catch (Exception e) {
        e.printStackTrace();
        System.err.flush();
        System.out.flush();
        for (Dependency dep : d.getDependencyList()) {
          System.err.println(dep);
        }
        for (int i = 0; i < n; i++) {
          System.err.println(i + "\t" + toks.get(i));
        }
        return null;
      }
      Path2 path = new Path2(ha, hb, fndp, sent);
      
      if (path.connected() && path.getNumWordsOnPath() <= maxWordsOnDeprelPath) {
        boolean includeEndpoints = false;
        String p = path.getPath(NodeType.WORD_NER_BACKOFF, EdgeType.DEP, includeEndpoints);
//        System.out.println("flip:     " + flip);
//        System.out.println("path:     " + p);
//        System.out.println("entries:  " + path.getEntries());
//        System.out.println("sentence: " + Arrays.toString(sent.getWords()));
        return new Pair<>(p, flip);
      }
      return null;
    }
    
    private static Map<String, List<EntityMention>> groupBySentence(List<EntityMention> mentions) {
      Map<String, List<EntityMention>> m = new HashMap<>();
      for (EntityMention em : mentions) {
        String key = em.getTokens().getTokenizationId().getUuidString();
        List<EntityMention> l = m.get(key);
        if (l == null) {
          l = new ArrayList<>();
          m.put(key, l);
        }
        l.add(em);
      }
      return m;
    }

    @Override
    public void close() throws Exception {
      Log.info("closing, " + EC);
      if (w_deprel != null) {
        w_deprel.close();
        w_deprel = null;
      }
      if (w_deprel_arg_tok != null) {
        w_deprel_arg_tok.close();
        w_deprel_arg_tok = null;
      }
      if (hash != null) {
        hash.close();
        hash = null;
      }
    }
  }
  

  /**
   * Given an (entityName, entityType, entityContextDoc) query,
   * finds mentions where:
   * a) there is ngram overlap between entityName and EntityMention.getText()
   * b) entityType matches
   * c) the tf-idf similarity between entityContextDoc and the doc in which the result appears.
   */
  public static class EntitySearch {
    
    public static EntitySearch build(ExperimentProperties config) throws IOException {
      File nerFeatures = config.getExistingDir("nerFeatureDir", new File(HOME, "ner_feats"));
      File docVecs = config.getExistingFile("docVecs", new File(HOME, "doc/docVecs." + N_DOC_TERMS + ".txt"));
      File idf = config.getExistingFile("idf", new File(HOME, "doc/idf.txt"));
//      File mentionLocs = config.getExistingFile("mentionLocs", new File(HOME, "raw/mentionLocs.txt.gz"));
      File emTokCommUuidId = config.getExistingFile("emTokCommUuidId", new File(HOME, "raw/emTokCommUuidId.txt.gz"));
      File tokObs = config.getExistingFile("tokenObs", new File(HOME, "tokenObs.jser.gz"));
      File tokObsLc = config.getExistingFile("tokenObsLower", new File(HOME, "tokenObs.lower.jser.gz"));
      EntitySearch s = new EntitySearch(nerFeatures, docVecs, idf, emTokCommUuidId, tokObs, tokObsLc);
      return s;
    }
    
    public static void main(ExperimentProperties config) throws IOException {
      EfficientUuidList.simpleTest();
      EntitySearch s = build(config);
      
      int n = config.getInt("maxResults", 10);
      
      // Demo
      Log.info("demo query:");
      String context = "Barack Hussein Obama II (US Listen i/bəˈrɑːk huːˈseɪn oʊˈbɑːmə/;[1][2] born August 4 , 1961 )"
          + " is an American politician who is the 44th and current President of the United States . He is the first"
          + " African American to hold the office and the first president born outside the continental United States . "
          + "Born in Honolulu , Hawaii , Obama is a graduate of Columbia University and Harvard Law School, where he was "
          + "president of the Harvard Law Review . He was a community organizer in Chicago before earning his law degree . "
          + "He worked as a civil rights attorney and taught constitutional law at the University of Chicago Law School "
          + "between 1992 and 2004 . While serving three terms representing the 13th District in the Illinois Senate from "
          + "1997 to 2004 , he ran unsuccessfully in the Democratic primary for the United States House of Representatives "
          + "in 2000 against incumbent Bobby Rush .";
      List<Result> rr = s.search("Barack Obama", "PERSON", new String[] {"Obama"}, context);
      for (int i = 0; i < n && i < rr.size(); i++) {
        System.out.println(rr.get(i));
      }
      
      // User loop
      if (config.getBoolean("interactive", false)) {
//        Console c = System.console();
        while (true) {
          Log.info("interactive query session");
          Log.info("enter on 4 lines: query/nertype/headwords/contextwords");
//          String q = c.readLine("query string (entity name, full): ");
//          String t = c.readLine("NER type (e.g. PERSON): ");
//          String h = c.readLine("query headwords (space-separated): ");
//          String d = c.readLine("context words (space-separated): ");
          BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
          String q = r.readLine();
          String t = r.readLine();
          String h = r.readLine();
          String d = r.readLine();
          rr = s.search(q, t, h.split("\\s+"), d);
          for (int i = 0; i < n && i < rr.size(); i++) {
            System.out.println(rr.get(i));
          }
          System.out.println();
        }
      }
      Log.info("use \"interactive true\" configuration if you want to query");
      
      System.out.println(TIMER);
    }

    private NerFeatureInvertedIndex nerFeatures;
    private TfIdfDocumentStore tfidf;
//    private Map<String, String> emUuid2commUuid;
//    private Map<String, String> emUuid2commId;
    private Map<String, String> tokUuid2commUuid;
    private Map<String, String> tokUuid2commId;
    private TokenObservationCounts tokObs, tokObsLc;
    
    public EntitySearch(File nerFeaturesDir, File docVecs, File idf,
//        File mentionLocs,
        File emTokCommUuidId,
        File tokObs, File tokObsLc) throws IOException {
      this.tfidf = new TfIdfDocumentStore(docVecs, idf);
      this.tokObs = (TokenObservationCounts) FileUtil.deserialize(tokObs);
      this.tokObsLc = (TokenObservationCounts) FileUtil.deserialize(tokObsLc);
      loadNerFeatures(nerFeaturesDir);

//      Log.info("loading mention locations from " + mentionLocs.getPath());
      Log.info("loading emTokCommUuidId=" + emTokCommUuidId.getPath());
      TIMER.start("load/mentionLocs");
//      emUuid2commUuid = new HashMap<>();
//      emUuid2commId = new HashMap<>();
      tokUuid2commUuid = new HashMap<>();
      tokUuid2commId = new HashMap<>();
//      try (BufferedReader r = FileUtil.getReader(mentionLocs)) {
      try (BufferedReader r = FileUtil.getReader(emTokCommUuidId)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          // EntityMention UUID, Communication UUID, Communication id, entityType, hasHead?, numTokens, numChars
          String[] ar = line.split("\t");
//          assert ar.length == 7;
//          String emUuid = ar[0];
//          String commUuid = ar[1];
//          String commId = ar[2];
//          Object old1 = emUuid2commUuid.put(emUuid, commUuid);
//          Object old2 = emUuid2commId.put(emUuid, commId);
          assert ar.length == 4;
//          String emUuid = ar[0];
          String tokUuid = ar[1];
          String commUuid = ar[2];
          String commId = ar[3];
          Object old1 = tokUuid2commUuid.put(tokUuid, commUuid);
          Object old2 = tokUuid2commId.put(tokUuid, commId);
          assert old1 == null || old1.equals(commUuid) : line;
          assert old2 == null || old2.equals(commId) : line;
//          assert old1 == old2 && old2 == null : "old1=" + old1 + " old2=" + old2 + " line=" + line;
        }
      }
      TIMER.stop("load/mentionLocs");
//      Log.info("indexed the location of " + emUuid2commUuid.size() + " mentions");
      Log.info("indexed the location of " + tokUuid2commUuid.size() + " mentions");
    }
    
    public void loadNerFeatures(File nerFeaturesDir) throws IOException {
//      this.nerFeatures = new NerFeatureInvertedIndex(nerFeatures);
      if (!nerFeaturesDir.isDirectory())
        throw new IllegalArgumentException("must be a dir: " + nerFeaturesDir.getPath());
      List<Pair<String, File>> sources = new ArrayList<>();
      for (File f : nerFeaturesDir.listFiles()) {
        String[] a = f.getName().split("\\.");
        if (a.length >= 3 && a[0].equals("nerFeats")) {
          String nerType = a[1];
          sources.add(new Pair<>(nerType, f));
        }
      }
      if (sources.isEmpty())
        err_misc++;
      Log.info("sources: " + sources);
      this.nerFeatures = new NerFeatureInvertedIndex(sources);
    }
    
    public List<Result> search(String entityName, String entityType, String[] headwords, String contextWhitespaceDelim) {
      TIMER.start("search");
      int numTermsInPack = tfidf.getMaxVecLength();
      String[] context = contextWhitespaceDelim.split("\\s+");
      TermVec contextVec = TfIdfDocumentStore.build(context, numTermsInPack, tfidf.idf);
      
      List<Result> mentions = nerFeatures.findMentionsMatching(entityName, entityType, headwords, tokObs, tokObsLc);
      for (Result r : mentions) {
        assert Double.isFinite(r.score);
        assert !Double.isNaN(r.score);
//        r.communicationUuid = emUuid2commUuid.get(r.entityMentionUuid);
//        r.communicationId = emUuid2commId.get(r.entityMentionUuid);
        r.communicationUuid = tokUuid2commUuid.get(r.tokenizationUuid);
        r.communicationId = tokUuid2commId.get(r.tokenizationUuid);
        r.debug("queryNgramOverlap", r.score);
        assert r.communicationUuid != null : "no comm uuid for em uuid: " + r.tokenizationUuid;
        double scoreContext = tfidf.tfidfCosineSim(contextVec, r.communicationUuid);
        double scoreMention = Math.min(1, r.score / entityName.length());
        r.score = scoreContext * scoreMention;
        r.debug("scoreContext", scoreContext);
        r.debug("scoreMention", scoreMention);
        assert Double.isFinite(r.score);
        assert !Double.isNaN(r.score);
      }
      
      Collections.sort(mentions, Result.BY_SCORE_DESC);
      TIMER.stop("search");
      return mentions;
    }
  }

  public static class Result {
    String tokenizationUuid;
    String communicationUuid;
    double score;
    
    // Optional
    String communicationId;

    String queryEntityName;
    String queryEntityType;
    
    // Debugging
    Map<String, String> debugValues;
    List<String> queryEntityFeatures;

    public String debug(String key) {
      return debugValues.get(key);
    }
    public String debug(String key, Object value) {
      if (debugValues == null)
        debugValues = new HashMap<>();
      return debugValues.put(key, String.valueOf(value));
    }

    @Override
    public String toString() {
//      String s = "EntityMention:" + entityMentionUuid
      String s = "Tokenization:" + tokenizationUuid
          + " Communication:" + communicationUuid
          + " score:" + score
          + " query:" + queryEntityName + "/" + queryEntityType;
      if (debugValues != null) {
        s += " " + debugValues;
      }
      return s;
    }
    
    public static final Comparator<Result> BY_SCORE_DESC = new Comparator<Result>() {
      @Override
      public int compare(Result o1, Result o2) {
        if (o1.score > o2.score)
          return -1;
        if (o1.score < o2.score)
          return +1;
        return 0;
      }
    };
  }

  /**
   * Runs after (ngram, nerType, EntityMention UUID) table has been built.
   * Given a query string and nerType, this breaks the string up into ngrams,
   * and finds all EntityMentions which match the type and contain ngrams from
   * the query string.
   * 
   * Strategies for sub-selecting ngrams to index (slightly diff than sharding which is not intelligble)
   * 1) only index NYT
   * 2) only index 2005-2007
   * 3) only index PERSON mentions
   */
  public static class NerFeatureInvertedIndex extends StringIntUuidIndex {
    private static final long serialVersionUID = -8638109659685198036L;

    public static void main(ExperimentProperties config) throws Exception {
//      File input = config.getExistingFile("input", new File(HOME, "raw/nerFeatures.txt.gz"));
      File input = config.getExistingFile("input", new File(HOME, "ner_feats/nerFeats.PERSON.txt"));

//      NerFeatureInvertedIndex n = new NerFeatureInvertedIndex(input);
      NerFeatureInvertedIndex n = new NerFeatureInvertedIndex(Arrays.asList(new Pair<>("PERSON", input)));
      
      n.showFeatureExtraction = false;
      List<KbpQuery> qs = TacKbp.getKbp2013SfQueries();
      for (KbpQuery q : qs) {
        System.out.println(q);
        String entityType = TacKbp.tacNerTypesToStanfordNerType(q.entity_type);
        List<Result> rs = n.findMentionsMatching(q.name, entityType, new String[] {});
        int nn = Math.min(300, rs.size());
        for (int i = 0; i < nn; i++)
          System.out.println(i + "\t" + rs.get(i));
        if (nn < rs.size())
          System.out.println("and " + (rs.size() - nn) + " more");
        System.out.println();
      }

//      for (Result x : n.findMentionsMatching("Barack Obama", "PERSON", new String[] {"Obama"}))
//        System.out.println(x);
//
//      for (Result x : n.findMentionsMatching("OBAMA", "PERSON", new String[] {"OBAMA"}))
//        System.out.println(x);
//
//      for (Result x : n.findMentionsMatching("barry", "PERSON", new String[] {"barry"}))
//        System.out.println(x);
//
//      for (Result x : n.findMentionsMatching("UN", "ORGANIZATION", new String[] {"UN"}))
//        System.out.println(x);
//
//      for (Result x : n.findMentionsMatching("United Nations", "ORGANIZATION", new String[] {"Nations"}))
//        System.out.println(x);
    }
    
    boolean verbose = true;
    boolean showFeatureExtraction = true;
    
    public NerFeatureInvertedIndex(List<Pair<String, File>> featuresByNerType) throws IOException {
      super();
      for (Pair<String, File> x : featuresByNerType) {
        this.putIntLines(x.get1(), x.get2());
      }
    }
    
    @Override
    public String toString() {
      return "(NerFeatures mentions:" + valuesPerString
        + " totalMentions=" + n_values
        + ")";
    }

    public List<Result> findMentionsMatching(String entityName, String entityType, String[] headwords) {
      err_misc++;
      return findMentionsMatching(entityName, entityType, headwords, null, null);
    }

    /**
     * Returns a list of (Tokenization UUID, score) pairs.
     */
    public List<Result> findMentionsMatching(String entityName, String entityType, String[] headwords,
        TokenObservationCounts tokenObs, TokenObservationCounts tokenObsLc) {
      TIMER.start("find/nerFeatures");
      if (verbose)
        Log.info("entityName=" + entityName + " nerType=" + entityType);
      
      // Find out which EntityMentions contain the query ngrams
      List<String> features = getEntityMentionFeatures(entityName, headwords, entityType, tokenObs, tokenObsLc);
      int n = features.size();
      Counts.Pseudo<String> emNgramOverlap = new Counts.Pseudo<>();
      double D = getNumKeys(entityType);
      for (int i = 0; i < n; i++) {
        int term = ReversableHashWriter.onewayHash(features.get(i));
//        int weight = getEntityMentionFeatureWeight(features.get(i));
        List<String> emsContainingTerm = get(entityType, term);
//        double weight = 4d / (3 + emsContainingTerm.size());
//        double weight = (1d/n) * Math.log(D / emsContainingTerm.size());
        double weight = (1d/n) * Math.pow(D / emsContainingTerm.size(), 0.25d);
        if (verbose) {
          System.out.printf("[NerInvIdx ment.match] entityName=\"%s\" entityType=%s feature=%s featureTokObs=%d featureWeight=%.3f D=%d\n",
              entityName, entityType, features.get(i), emsContainingTerm.size(), weight, (long)D);
        }
        for (String em : emsContainingTerm) {
          emNgramOverlap.update(em, weight);
        }
      }
      
      List<Result> rr = new ArrayList<>();
      for (String em : emNgramOverlap.getKeysSortedByCount(true)) {
        Result r = new Result();
        r.queryEntityName = entityName;
        r.queryEntityType = entityType;
        r.tokenizationUuid = em;
        r.communicationUuid = null;
        r.score = emNgramOverlap.getCount(em);
        r.queryEntityFeatures = features;
        rr.add(r);
      }
      
      // Sort results by score (decreasing)
      Collections.sort(rr, Result.BY_SCORE_DESC);
      
      if (showFeatureExtraction) {
        int nn = Math.min(300, rr.size());
        for (int i = 0; i < nn; i++) {
          System.out.printf("[NerInvIdx ment.match] entityName=\"%s\" entityType=%s headwords=%s resIdx=%d resTok=%s resScore=%.3f\n",
              entityName, entityType, Arrays.toString(headwords), i, rr.get(i).tokenizationUuid, rr.get(i).score);
        }
        if (nn < rr.size())
          System.out.println("and " + (rr.size() - nn) + " more");
        System.out.println();
      }

      TIMER.stop("find/nerFeatures");
      return rr;
    }
  }
  
  public static class TermVec implements Serializable {
    private static final long serialVersionUID = -6978085630850790443L;

    int totalCount = -1;        // sum(counts)
    int totalCountNoAppox = -1; // sum(counts) if counts didn't have to truncate
    int[] terms;
    short[] counts;
    
    public TermVec(int size) {
      terms = new int[size];
      counts = new short[size];
    }
    
    public void computeTotalCount() {
      totalCount = 0;
      for (int i = 0; i < counts.length; i++)
        totalCount += counts[i];
    }
    
    public int numTerms() {
      return terms.length;
    }
    
    public double tfLowerBound(int index) {
      assert totalCount > 0 : "totalCount=" + totalCount + " index=" + index + " length=" + counts.length;
      double tf = counts[index];
      return tf / totalCount;
    }
    
    void set(int index, int term, int count) {
      if (count > Short.MAX_VALUE)
        throw new IllegalArgumentException("count too big: " + count);
      if (count < 1)
        throw new IllegalArgumentException("count too small: " + count);
      terms[index] = term;
      counts[index] = (short) count;
    }

    public static double tfidfCosineSim(TermVec x, TermVec y, IntDoubleHashMap idf) {
      TIMER.start("tfidf");

      int sizeGuess = y.numTerms() + x.numTerms();
      double missingEntry = 0;
      IntDoubleHashMap a = new IntDoubleHashMap(sizeGuess, missingEntry);
      for (int i = 0; i < y.numTerms(); i++) {
        double s = y.tfLowerBound(i);
        a.put(y.terms[i], s);
      }
      
      double dot = 0;
      for (int i = 0; i < x.numTerms(); i++) {
        double w = idf.getWithDefault(x.terms[i], 0);

        // y.tf(w) * idf(w)
        double left = a.getWithDefault(x.terms[i], 0) * w;
        
        // x.tf(w) * idf(w)
        double right = x.tfLowerBound(i) * w;
        
        dot += left * right;

        assert Double.isFinite(dot);
        assert !Double.isNaN(dot);
      }
      
      double xnorm = tfidfL2Norm(x, idf);
      double ynorm = tfidfL2Norm(y, idf);
      double cos = dot / (xnorm * ynorm);
      
      TIMER.stop("tfidf");
      return cos;
    }
    
    public static double tfidfL2Norm(TermVec x, IntDoubleHashMap idf) {
      double d = 0;
      for (int i = 0; i < x.numTerms(); i++) {
        double tf = x.tfLowerBound(i);
        double w = idf.getWithDefault(x.terms[i], 0);
        double e = tf * w;
        d += e * e;
      }
      return Math.sqrt(d);
    }
    
    public String showTerms(int termCharLimit) {
      StringBuilder sb = new StringBuilder();
      int tc = 0;
      int nt = numTerms();
      for (int i = 0; i < nt; i++) {
        String term = INVERSE_HASH.get(terms[i]);
        sb.append(' ');
        sb.append(term);
        tc += term.length() + 1;
        if (termCharLimit > 0 && tc >= termCharLimit) {
          sb.append(" ...and " + (nt-(i+1)) + " more terms");
          break;
        }
      }
      return sb.toString();
    }
  }

  /**
   * Stores the tf-idf vecs for a bunch of docs which you can query against.
   * The representation stored here can be generated by {@link ComputeTfIdfDocVecs}.
   */
  public static class TfIdfDocumentStore {
    private Map<String, TermVec> comm2vec;
    private IntDoubleHashMap idf;
    private int vecMaxLen = -1;
    
    public TfIdfDocumentStore(File docVecs, File idf) throws IOException {
      this.idf = ComputeTfIdfDocVecs.readIdfs(idf);
      loadDocVecs(docVecs);
    }
    
    public IntDoubleHashMap getIdfs() {
      return idf;
    }
    
    public TermVec get(String commUuid) {
      return comm2vec.get(commUuid);
    }

    /**
     * @param docVecs should have lines like: <commUuid> (<tab> <hashedWord>:<count>)+
     */
    public void loadDocVecs(File docVecs) throws IOException {
      Log.info("source=" + docVecs.getPath());
      TIMER.start("load/docVecs");
      comm2vec = new HashMap<>();
      try (BufferedReader r = FileUtil.getReader(docVecs)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          assert ar.length > 2;
          TermVec tv = new TermVec(ar.length - 2);
          String commUuid = ar[0];
          tv.totalCountNoAppox = Integer.parseInt(ar[1]);
          for (int i = 2; i < ar.length; i++) {
            String[] kv = ar[i].split(":");
            assert kv.length == 2;
            int term = Integer.parseUnsignedInt(kv[0]);
            int count = Integer.parseInt(kv[1]);
            tv.set(i-2, term, count);
          }
          tv.computeTotalCount();
          Object old = comm2vec.put(commUuid, tv);
          assert old == null : "commUuid is not unique? " + commUuid;
          if (tv.numTerms() > vecMaxLen)
            vecMaxLen = tv.numTerms();
        }
      }
      TIMER.stop("load/docVecs");
    }
    
    public int getMaxVecLength() {
      return vecMaxLen;
    }
    
    public double tfidfCosineSim(TermVec query, String commUuid) {
      TermVec comm = comm2vec.get(commUuid);
      return TermVec.tfidfCosineSim(query, comm, idf);
    }
    
    public static TermVec build(String[] document, int numTermsInPack, IntDoubleHashMap idf) {
      TIMER.start("build/termVec");

      // Count words
      int sizeGuess = document.length;
      IntFloatUnsortedVector tf = new IntFloatUnsortedVector(sizeGuess);
      for (String t : document) {
//        int term = HASH.hashString(t, UTF8).asInt();
        int term = ReversableHashWriter.onewayHash(t);
        tf.add(term, 1);
      }

      // Compute tf*idf entries
      List<Pair<IntPair, Double>> doc = new ArrayList<>(tf.getUsed());
      tf.forEach(e -> {
        int term = e.index();
        float tfTerm = e.get();
        double idfTerm = idf.getWithDefault(term, 0);
        doc.add(new Pair<>(new IntPair(term, (int) tfTerm), tfTerm * idfTerm));
      });
      Collections.sort(doc, ComputeTfIdfDocVecs.byVal);
      
      // Truncate and pack (term, freq) into vec (discarding idf info)
      int n = Math.min(numTermsInPack, doc.size());
      TermVec tv = new TermVec(n);
      for (int i = 0; i < n; i++) {
        Pair<IntPair, Double> x = doc.get(i);
        IntPair termFreq = x.get1();
        tv.set(i, termFreq.first, termFreq.second);
      }
      tv.totalCountNoAppox = document.length;
      tv.computeTotalCount();
      
      TIMER.stop("build/termVec");
      return tv;
    }
  }
  

  /**
   * Runs after (count, hashedTerm, comm uuid) table is built.
   * 
   * Makes two passes over this file (e.g. raw/termDoc.txt.gz).
   * The first to compute idfs, the second to build and write out
   * the pruned document vectors.
   * 
   * If there are 9.9M documents in gigaword (best estimate),
   * Each doc needs
   * a) 16 bytes for UUID
   * b) 128 * (4 bytes term idx + 1 byte for count)
   * = 656 bytes
   * * 9.9M docs = 6.3GB
   * 
   * I think I estimated without pruning that it would be something like 12G?
   * 
   * The point is that 6.3G easily fits in memory and can be scaled up or down.
   */
  public static class ComputeTfIdfDocVecs {
    
    public static void main(ExperimentProperties config) throws IOException {
      File in = config.getExistingFile("input", new File(HOME, "raw/termDoc.txt.gz"));
      File outInvIdx = config.getFile("outputDocVecs", new File(HOME, "doc/docVecs." + N_DOC_TERMS + ".txt"));
      File outIdfs = config.getFile("outputIdf", new File(HOME, "doc/idf.txt"));
      ComputeTfIdfDocVecs c = new ComputeTfIdfDocVecs(in);
      c.writeoutIdfs(outIdfs);
      c.pack(N_DOC_TERMS, outInvIdx);
    }
    
    private IntIntHashVector termDocCounts; // keys are hashed words, values are document frequencies
    private int nDoc;
    private double logN;
    private File termDoc;
      
    // For pack, track order stats of approx quality l2(approx(tfidf))/l2(tfidf)
    // TODO This stores O(n) memory, may break on big datasets
    private OrderStatistics<Double> approxQualOrdStats = new OrderStatistics<>();
    
    public ComputeTfIdfDocVecs(File termDoc) throws IOException {
      this.termDoc = termDoc;
      this.termDocCounts = new IntIntHashVector();
      this.nDoc = 0;
      TimeMarker tm = new TimeMarker();
      String prevId = null;
      TIMER.start("load/termVec/raw");
      try (BufferedReader r = FileUtil.getReader(termDoc)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          // count, term, doc
          String[] ar = line.split("\t");
          assert ar.length == 3;
//          int count = Integer.parseInt(ar[0]);
          int term = Integer.parseUnsignedInt(ar[1]);
          String uuid = ar[2];
          if (!uuid.equals(prevId)) {
            nDoc++;
            prevId = uuid;
          }
          termDocCounts.add(term, 1);
          
          if (tm.enoughTimePassed(5)) {
            Log.info("nDoc=" + nDoc + " termDocCounts.size=" + termDocCounts.size()
                + "\t" + Describe.memoryUsage());
          }
        }
      }
      this.logN = Math.log(nDoc);
      TIMER.stop("load/termVec/raw");
    }
    
    public double idf(int term) {
      int df = termDocCounts.get(term) + 1;
      return this.logN - Math.log(df);
    }
    
    public void writeoutIdfs(File idf) throws IOException {
      Log.info("output=" + idf.getPath());
      try (BufferedWriter w = FileUtil.getWriter(idf)) {
        termDocCounts.forEach(iie -> {
          int term = iie.index();
          try {
            w.write(Integer.toUnsignedString(term) + "\t" + idf(term));
            w.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
    }
    
    public static IntDoubleHashMap readIdfs(File idf) throws IOException {
      TIMER.start("load/idf");
      Log.info("source=" + idf.getPath());
      int sizeGuess = 1024;
      double missingEntry = 0;
      IntDoubleHashMap idfs = new IntDoubleHashMap(sizeGuess, missingEntry);
      try (BufferedReader r = FileUtil.getReader(idf)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] ar = line.split("\t");
          assert ar.length == 2;
          int term = Integer.parseUnsignedInt(ar[0]);
          double idfTerm = Double.parseDouble(ar[1]);
          idfs.put(term, idfTerm);
        }
      }
      TIMER.stop("load/idf");
      return idfs;
    }
    
    public static TermVec packComm(int numTerms, Communication c, IntDoubleHashMap idfs) {
      // Extract words
      List<String> terms = terms(c);
      
      // Count
      IntFloatUnsortedVector tf = new IntFloatUnsortedVector(terms.size());
      for (String t : terms) {
        int i = ReversableHashWriter.onewayHash(t);
        tf.add(i, 1);
      }
      
      // Compute tf-idf
      List<Pair<IntPair, Double>> tfi = new ArrayList<>();
      tf.apply(new FnIntFloatToFloat() {
        @Override public float call(int term, float count) {
          double idf = idfs.get(term);
          if (Double.isNaN(idf) || idf <= 0 || Double.isInfinite(idf)) {
            EC.increment("packComm/errIdf");
          } else {
            IntPair key = new IntPair(term, (int) count);
            tfi.add(new Pair<>(key, count * idf));
          }
          return count;
        }
      });

      // Sort by tf*idf and take the most that fit
      Collections.sort(tfi, byVal);
      int n = Math.min(numTerms, tfi.size());
      TermVec v = new TermVec(n);
      for (int i = 0; i < n; i++) {
        IntPair tc = tfi.get(i).get1();
        v.set(i, tc.first, tc.second);
      }
      v.computeTotalCount();
      return v;
    }

    /**
     * Writes out lines of the form:
     * <commUuid> <tab> <numTermsInComm> (<tab> <term>:<freq>)*
     * 
     * @param numTerms
     * @param prunedTermDoc
     * @throws IOException
     */
    public void pack(int numTerms, File prunedTermDoc) throws IOException {
      TIMER.start("pack");
      Log.info("numTerms=" + numTerms + " output=" + prunedTermDoc);
      TimeMarker tm = new TimeMarker();
      int n = 0;
      // ((term, count), tf*idf)
      List<Pair<IntPair, Double>> curTerms = new ArrayList<>();
      String curId = null;
      try (BufferedReader r = FileUtil.getReader(termDoc);
          BufferedWriter w = FileUtil.getWriter(prunedTermDoc)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] toks = line.split("\t");
          assert toks.length == 3;
          int count = Integer.parseInt(toks[0]);
          int term = Integer.parseUnsignedInt(toks[1]);
          String id = toks[2];
          
          if (!id.equals(curId)) {
            if (curId != null)
              packHelper(numTerms, w, curTerms, curId);
            curId = id;
            curTerms.clear();
          }

          IntPair key = new IntPair(term, count);
          curTerms.add(new Pair<>(key, count * idf(term)));
          
          n++;
          if (tm.enoughTimePassed(5)) {
            Log.info("processed " + n + " documents\t" + Describe.memoryUsage());
          }
        }
        if (curId != null)
          packHelper(numTerms, w, curTerms, curId);
      }
      Log.info("done, approximation quality = l2(approx(tfidf)) / l2(tfidf), 1 is perfect:");
      List<Double> r = Arrays.asList(0.0, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5);
      List<Double> p = approxQualOrdStats.getOrders(r);
      for (int i = 0; i < r.size(); i++) {
        System.out.printf("\t%.1f%%-percentile approximation quality is %.3f\n",
            100*r.get(i), p.get(i));
      }
      TIMER.stop("pack");
    }
    
//    public TermVec packHelper2(int numTerms, List<String> words) {
//      TermVec v = new TermVec(numTerms);
//    }

    private void packHelper(int numTerms, BufferedWriter w, List<Pair<IntPair, Double>> doc, String id) throws IOException {
      TIMER.start("pack/vec");
      // Sort by tf*idf
      Collections.sort(doc, byVal);
      // Count all terms
      int z = 0;
      for (Pair<IntPair, Double> e : doc)
        z += e.get1().second;
      // Prune
      List<Pair<IntPair, Double>> approx;
      if (doc.size() > numTerms) {
        approx = doc.subList(0, numTerms);
      } else {
        approx = doc;
      }
      // Output
      w.write(id);
      w.write('\t');
      w.write(Integer.toString(z));
      for (Pair<IntPair, Double> e : approx) {
        w.write('\t');
        w.write(Integer.toUnsignedString(e.get1().first));
        w.write(':');
        w.write(Integer.toString(e.get1().second));
      }
      w.newLine();
      
      // Compute approximation error
      double a = l2(approx);
      double b = l2(doc);
      approxQualOrdStats.add(a / b);
      TIMER.stop("pack/vec");
    }
    private double l2(List<Pair<IntPair, Double>> vec) {
      double ss = 0;
      for (Pair<IntPair, Double> x : vec) {
        double s = x.get2();
        ss += s * s;
      }
      return Math.sqrt(ss);
    }
    private static final Comparator<Pair<IntPair, Double>> byVal = new Comparator<Pair<IntPair, Double>>() {
      @Override
      public int compare(Pair<IntPair, Double> o1, Pair<IntPair, Double> o2) {
        if (o1.get2() > o2.get2())
          return -1;
        if (o1.get2() < o2.get2())
          return +1;
        return 0;
      }
    };
  }
  
  

  // Writes out a file containing (hashedTerm, term) pairs for every
  // term used in any output file.
  private ReversableHashWriter termHash;    // hashedTerm, term
  
  // For determining the correct prefix length based on counts
  private TokenObservationCounts tokObs;
  private TokenObservationCounts tokObsLc;
  
  private BufferedWriter w_nerFeatures;   // hashedFeature, nerType, EntityMention UUID, Tokenization UUID
  private BufferedWriter w_termDoc;       // count, hashedTerm, Communication UUID
  private BufferedWriter w_mentionLocs;   // EntityMention UUID, Communication UUID, Communication id, entityType, hasHead?, numTokens, numChars
  private BufferedWriter w_tok2comm;      // EntityMention UUID, Tokenization UUID, Communication UUID, Communication ID

  private boolean outputTfIdfTerms = false;
  
  // Indexes the Tokenizations of the Communication currently being observed
  private Map<String, Tokenization> tokMap;

  public IndexCommunications(
      TokenObservationCounts tokObs, TokenObservationCounts tokObsLc,
      File nerFeatures, File termDoc, File termHash, File mentionLocs, File tok2comm) {
    this.tokObs = tokObs;
    this.tokObsLc = tokObsLc;
    tokMap = new HashMap<>();
    try {
      w_nerFeatures = FileUtil.getWriter(nerFeatures);
      w_termDoc = FileUtil.getWriter(termDoc);
      w_mentionLocs = FileUtil.getWriter(mentionLocs);
      w_tok2comm = FileUtil.getWriter(tok2comm);
      this.termHash = new ReversableHashWriter(termHash, 1<<14, 1<<22);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
  
  static String headword(TokenRefSequence trs, Map<String, Tokenization> tokMap, boolean takeNnCompounds, boolean allowFailures) {
    Tokenization t = tokMap.get(trs.getTokenizationId().getUuidString());
    if (t == null) {
      if (!allowFailures)
        throw new RuntimeException("can't find tokenization! " + trs.getTokenizationId().getUuidString());
      return null;
    }
    
    List<Token> toks = t.getTokenList().getTokenList();
    if (!takeNnCompounds) {
      if (trs.isSetAnchorTokenIndex())
        return toks.get(trs.getAnchorTokenIndex()).getText();
      if (trs.getTokenIndexListSize() == 1)
        return toks.get(trs.getTokenIndexList().get(0)).getText();
    }
    
    // Fall back on a dependency parse
    int n = toks.size();
    DependencyParse d = getPreferredDependencyParse(t);
    MultiAlphabet a = new MultiAlphabet();
    LabeledDirectedGraph graph =
        LabeledDirectedGraph.fromConcrete(d, n, a);
    DParseHeadFinder hf = new DParseHeadFinder();
    hf.ignoreEdge(a.dep("prep"));
    hf.ignoreEdge(a.dep("poss"));
    hf.ignoreEdge(a.dep("possessive"));
    try {

      List<Integer> ts = trs.getTokenIndexList();
      int first = ts.get(0);
      int last = ts.get(ts.size()-1);
      
      if (takeNnCompounds) {
        int nnEdgeType = a.dep("nn");
        int[] hs = hf.headWithNn(graph, first, last, nnEdgeType);
        if (hs == null) {
          err_head++;
          if (!allowFailures)
            throw new RuntimeException();
          return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hs.length; i++) {
          if (i > 0) sb.append('_');
          assert hs[i] >= 0;
          sb.append(toks.get(hs[i]).getText());
        }
        return sb.toString();
      } else {
        int h = hf.head(graph, first, last);
        return toks.get(h).getText();
      }

    } catch (RuntimeException e) {
      if (allowFailures)
        return null;
      System.err.println(d);
      throw e;
    }
  }

  static TokenTagging getPreferredLemmas(Tokenization t) {
    List<TokenTagging> all = new ArrayList<>(1);
    for (TokenTagging tt : t.getTokenTaggingList())
      if (tt.getTaggingType().equalsIgnoreCase("lemma"))
        all.add(tt);
    if (all.isEmpty())
      throw new IllegalArgumentException("there are no lemmas");
    if (all.size() > 1)
      throw new RuntimeException("implement choice");
    return all.get(0);
  }

  static TokenTagging getPreferredNerTags(Tokenization t) {
    List<TokenTagging> tt = new ArrayList<>();
    for (TokenTagging tags : t.getTokenTaggingList()) {
      if (tags.getTaggingType().equalsIgnoreCase("NER")) {
        if (tags.getMetadata().getTool().contains("parsey"))
          return tags;
        tt.add(tags);
      }
    }
    if (tt.isEmpty())
      throw new RuntimeException("no NER in " + t);
    if (tt.size() == 1)
      return tt.get(0);
    throw new RuntimeException("implement a preference over: " + tt);
  }

  static TokenTagging getPreferredPosTags(Tokenization t) {
    List<TokenTagging> tt = new ArrayList<>();
    for (TokenTagging tags : t.getTokenTaggingList()) {
      if (tags.getTaggingType().equalsIgnoreCase("POS")) {
        if (tags.getMetadata().getTool().contains("parsey"))
          return tags;
        tt.add(tags);
      }
    }
    if (tt.isEmpty())
      throw new RuntimeException("no POS in " + t);
    if (tt.size() == 1)
      return tt.get(0);
    throw new RuntimeException("implement a preference over: " + tt);
  }
  
  static DependencyParse getPreferredDependencyParse(Tokenization toks) {
    for (DependencyParse dp : toks.getDependencyParseList()) {
      if ("parsey".equalsIgnoreCase(dp.getMetadata().getTool())) {
        EC.increment("preferredDParse/parsey");
        return dp;
      }
    }
    for (DependencyParse dp : toks.getDependencyParseList()) {
      if ("Stanford CoreNLP basic".equalsIgnoreCase(dp.getMetadata().getTool())) {
        EC.increment("preferredDParse/stanfordBasic");
        return dp;
      }
    }
    EC.increment("preferredDParse/err");
    return null;
  }
  
  private void buildTokMap(Communication c) {
    tokMap.clear();
    if (c.isSetSectionList()) {
      for (Section sect : c.getSectionList()) {
        if (sect.isSetSentenceList()) {
          for (Sentence sent : sect.getSentenceList()) {
            Tokenization t = sent.getTokenization();
            Object old = tokMap.put(t.getUuid().getUuidString(), t);
            assert old == null;
          }
        }
      }
    }
  }
  
  /**
   * @param tokObs can be null, will take full string
   * @param tokObsLc can be null, will take full string
   */
  private static List<String> prefixGrams(String mentionText,
      TokenObservationCounts tokObs, TokenObservationCounts tokObsLc) {
    boolean verbose = false;
    String[] toks = mentionText
        .replaceAll("-", " ")
        .replaceAll("\\d", "0")
        .replaceAll("[^A-Za-z0 ]", "")
        .split("\\s+");
    String[] toksLc = new String[toks.length];
    for (int i = 0; i < toks.length; i++)
      toksLc[i] = toks[i].toLowerCase();
    
    if (verbose) {
      System.out.println(mentionText);
      System.out.println(Arrays.toString(toks));
    }
    if (toks.length == 0) {
      err_misc++;
      return Collections.emptyList();
    }

    List<String> f = new ArrayList<>();
    
    // unigram prefixes
    for (int i = 0; i < toks.length; i++) {
//      String p = tokObs == null ? toks[i] : tokObs.getPrefixOccuringAtLeast(toks[i], 10);
      String pi = tokObsLc == null ? toksLc[i] : tokObsLc.getPrefixOccuringAtLeast(toksLc[i], 10);
//      f.add("p:" + p);
      f.add("pi:" + pi);
    }
    
    // bigram prefixes
    String B = "BBBB";
    String A = "AAAA";
    int lim = 10;
    boolean lc = true;
    TokenObservationCounts c = lc ? tokObsLc : tokObs;
    String[] tk = lc ? toksLc : toks;
    for (int i = -1; i < toks.length; i++) {
      String w, ww;
      if (i < 0) {
        w = B;
        ww = c == null ? tk[i+1] : c.getPrefixOccuringAtLeast(tk[i+1], lim);
      } else if (i == toks.length-1) {
        w = c == null ? tk[i] : c.getPrefixOccuringAtLeast(tk[i], lim);
        ww = A;
      } else {
        if (c == null) {
          w = tk[i];
          ww = tk[i+1];
        } else {
          w = c.getPrefixOccuringAtLeast(tk[i], lim);
          ww = c.getPrefixOccuringAtLeast(tk[i+1], lim);
        }
      }
      f.add("pb:" + w + "_" + ww);
    }

    if (verbose) {
      System.out.println(f);
      System.out.println();
    }

    return f;
  }
  
  public static final Set<String> STOP_TRIAGE_FEATURES;
  static {
    STOP_TRIAGE_FEATURES = new HashSet<>();
    for (String t : Arrays.asList("p:", "pi:", "pb:", "h:", "hi:", "hp:", "hip:")) {
      STOP_TRIAGE_FEATURES.add(t + "s_AAAA");
      STOP_TRIAGE_FEATURES.add(t + "s");
      STOP_TRIAGE_FEATURES.add(t + "i");
      STOP_TRIAGE_FEATURES.add(t + "I");
      STOP_TRIAGE_FEATURES.add(t + "a");
      STOP_TRIAGE_FEATURES.add(t + "A");
      STOP_TRIAGE_FEATURES.add(t + "the");
      STOP_TRIAGE_FEATURES.add(t + "BBBB_the");
      STOP_TRIAGE_FEATURES.add(t + "of");
      for (int i = 1; i < 10; i++) {

        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < i; j++)
          sb.append('0');
        String num = sb.toString();
        
        STOP_TRIAGE_FEATURES.add(t + num);
        STOP_TRIAGE_FEATURES.add(t + num + "_AAAA");
        STOP_TRIAGE_FEATURES.add(t + "BBBB_" + num);
      }
    }
  }
  
  /**
   * @param tokObs can be null
   * @param tokObsLc can be null
   * @return
   */
  public static List<String> getEntityMentionFeatures(String mentionText, String[] headwords, String nerType,
      TokenObservationCounts tokObs, TokenObservationCounts tokObsLc) {
    // ngrams
    List<String> features = new ArrayList<>();
    features.addAll(prefixGrams(mentionText, tokObs, tokObsLc));
    // headword
    for (int i = 0; i < headwords.length; i++) {
      if (headwords[i] == null)
        continue;
      String h = headwords[i];

      // NOTE: There are a lot of features which have lots of numbers in them
      // This will not normalize any words with 3 or fewer numbers
      int numeric = 0;
      for (int j = 0; j < h.length(); j++) {
        if (Character.isDigit(h.codePointAt(j)))
          numeric++;
        if (numeric == 4) {
          h = h.replaceAll("\\d", "0");
          break;
        }
      }

      String hi = headwords[i].toLowerCase();
      String hp = tokObs == null ? headwords[i] : tokObs.getPrefixOccuringAtLeast(headwords[i], 5);
//      String hip = tokObsLc.getPrefixOccuringAtLeast(headwords[i].toLowerCase(), 5);
      features.add("h:" + h);
      if (!hi.equals(h))
        features.add("hi:" + hi);
      if (!hp.equals(h))
        features.add("hp:" + hp);
//      if (!hip.equals(h))
//        features.add("hip:" + hip);
    }
    
    for (String f : features) {
      int c = f.indexOf(':');
      if (c >= 0 && c <= 5) {
        String p = f.substring(0, c);
        EC.increment("mentionFeat/" + p);
      }
    }
    
    List<String> pruned = new ArrayList<>();
    for (String f : features) {
      if (!STOP_TRIAGE_FEATURES.contains(f)) {
        pruned.add(f);
      }
    }
    return pruned;
//    return features;
  }
  public static int getEntityMentionFeatureWeight(String feature) {
    if (feature.startsWith("h:"))
      return 30;
    if (feature.startsWith("hi:"))
      return 20;
    if (feature.startsWith("hp:"))
      return 16;
    if (feature.startsWith("hip:"))
      return 8;
    if (feature.startsWith("p:"))
      return 2;
    if (feature.startsWith("pi:"))
      return 1;
    if (feature.startsWith("pb:"))
      return 5;
    err_misc++;
    return 1;
  }

  /**
   * Make sure you add entity mention types, a la:
   *   new AddNerTypeToEntityMentions(comm);
   */
  public static List<EntityMention> getEntityMentions(Communication c) {
    List<EntityMention> mentions = new ArrayList<>();
    if (c.isSetEntityMentionSetList()) {
      for (EntityMentionSet ems : c.getEntityMentionSetList()) {
        for (EntityMention em : ems.getMentionList()) {

          if (!em.isSetEntityType() || em.getEntityType().indexOf('\t') >= 0) {
            err_ner++;
            continue;
          }
          
          if ("O".equals(em.getEntityType()))
            continue;
          int ttypes = em.getEntityType().split(",").length;
          if (ttypes > 1)
            continue;

          n_ent++;
          mentions.add(em);
        }
      }
    }
    return mentions;
  }
  
  public void observe(Communication c) throws IOException {
    buildTokMap(c);
    new AddNerTypeToEntityMentions(c);
    n_doc++;

    for (EntityMention em : getEntityMentions(c)) {

      // EntityMention UUID, Communication UUID, Communication id, entityType, hasHead?, numTokens, numChars
      w_mentionLocs.write(em.getUuid().getUuidString());
      w_mentionLocs.write('\t');
      w_mentionLocs.write(c.getUuid().getUuidString());
      w_mentionLocs.write('\t');
      w_mentionLocs.write(c.getId());
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getEntityType()));
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getTokens().isSetAnchorTokenIndex()));
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getTokens().getTokenIndexListSize()));
      w_mentionLocs.write('\t');
      w_mentionLocs.write(String.valueOf(em.getText().length()));
      w_mentionLocs.newLine();
      
      // EntityMention UUID, Tokenization UUID, Communication UUID, Communication ID
      w_tok2comm.write(em.getUuid().getUuidString());
      w_tok2comm.write('\t');
      w_tok2comm.write(em.getTokens().getTokenizationId().getUuidString());
      w_tok2comm.write('\t');
      w_tok2comm.write(c.getUuid().getUuidString());
      w_tok2comm.write('\t');
      w_tok2comm.write(c.getId());
      w_tok2comm.newLine();

      boolean takeNnCompounts = true;
      boolean allowFailures = true;
      String head = headword(em.getTokens(), tokMap, takeNnCompounts, allowFailures);
      List<String> feats = getEntityMentionFeatures(em.getText(), new String[] {head}, em.getEntityType(),
          tokObs, tokObsLc);
      for (String f : feats) {
        int i = termHash.hash(f);
        // hash(word), nerType, EntityMention UUID, Tokenization UUID
        w_nerFeatures.write(Integer.toUnsignedString(i));
        w_nerFeatures.write('\t');
        w_nerFeatures.write(em.getEntityType());
        w_nerFeatures.write('\t');
        w_nerFeatures.write(em.getUuid().getUuidString());
        w_nerFeatures.write('\t');
        w_nerFeatures.write(em.getTokens().getTokenizationId().getUuidString());
        w_nerFeatures.newLine();
      }
    }
    
    // Terms
    List<String> terms = terms(c);
    if (outputTfIdfTerms) {

      // need to read-in IDF table
      throw new RuntimeException("implement me");

    } else {
      
      IntFloatUnsortedVector tf = new IntFloatUnsortedVector(terms.size());
      for (String t : terms) {
        n_tok++;
        int i = termHash.hash(t);
        tf.add(i, 1);
      }
      // apply calls compact
      tf.apply(new FnIntFloatToFloat() {
        @Override public float call(int arg0, float arg1) {
          try {
            // count, word, doc
            w_termDoc.write(Integer.toUnsignedString((int) arg1));
            w_termDoc.write('\t');
            w_termDoc.write(Integer.toUnsignedString(arg0));
            w_termDoc.write('\t');
            w_termDoc.write(c.getUuid().getUuidString());
            w_termDoc.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
          return arg1;
        }
      });
    }
  }
  
  /**
   * Any time you need a bag-of-words representation for a {@link Communication},
   * use this method.
   */
  static List<String> terms(Communication c) {
    List<String> t = new ArrayList<>(256);
    if (c.isSetSectionList()) {
      for (Section section : c.getSectionList()) {
        if (section.isSetSentenceList()) {
          for (Sentence sentence : section.getSentenceList()) {
            for (Token tok : sentence.getTokenization().getTokenList().getTokenList()) {
              String w = tok.getText()
                  .replaceAll("\\d", "0");
              t.add(w);
            }
          }
        }
      }
    }
    return t;
  }
  
  static Counts<String> terms2(Communication c) {
    List<String> cc = terms(c);
    Counts<String> cn = new Counts<>();
    for (String w : cc)
      cn.increment(w);
    return cn;
  }

  @Override
  public void close() throws Exception {
    w_nerFeatures.close();
    w_termDoc.close();
    w_mentionLocs.close();
    w_tok2comm.close();
    termHash.close();
  }
  
  @Override
  public String toString() {
    return String.format("(IC n_doc=%d n_tok=%d n_ent=%d err_ner=%d n_termHashes=%d n_termWrites=%d)",
        n_doc, n_tok, n_ent, err_ner, n_termHashes, n_termWrites);
  }
  
//  public static void mainTokenObs(ExperimentProperties config) throws IOException {
//    List<File> inputCommTgzs = config.getFileGlob("communicationArchives");
//    File output = config.getFile("outputTokenObs", new File(HOME, "tokenObs.jser.gz"));
//    File outputLower = config.getFile("outputTokenObsLower", new File(HOME, "tokenObs.lower.jser.gz"));
//    TokenObservationCounts t = new TokenObservationCounts();
//    TokenObservationCounts tLower = new TokenObservationCounts();
//    TokenObservationCounts b = null;
//    TokenObservationCounts bLower = null;
//    TokenObservationCounts.trainOnCommunications(inputCommTgzs, t, tLower, b, bLower);
//    FileUtil.serialize(t, output);
//    FileUtil.serialize(tLower, outputLower);
//  }
  
  public static void extractBasicData(ExperimentProperties config) throws Exception {
    File outputDir = config.getOrMakeDir("outputDirectory", new File(HOME, "raw"));
    File tokObsFile = config.getExistingFile("tokenObs", new File(HOME, "tokenObs.jser.gz"));
    File tokObsLowerFile = config.getExistingFile("tokenObsLower", new File(HOME, "tokenObs.lower.jser.gz"));
    
    Log.info("loading TokenObservationCounts from " + tokObsFile.getPath() + "\t" + Describe.memoryUsage());
    TokenObservationCounts t = (TokenObservationCounts) FileUtil.deserialize(tokObsFile);
    Log.info("loading TokenObservationCounts from " + tokObsLowerFile.getPath() + "\t" + Describe.memoryUsage());
    TokenObservationCounts tl = (TokenObservationCounts) FileUtil.deserialize(tokObsLowerFile);
    
    File nerFeatures = new File(outputDir, "nerFeatures.txt.gz");
    File termDoc = new File(outputDir, "termDoc.txt.gz");
    File termHash = new File(outputDir, "termHash.approx.txt.gz");
    File mentionLocs = new File(outputDir, "mentionLocs.txt.gz");
    File tok2comm = new File(outputDir, "emTokCommUuidId.txt.gz");

    TimeMarker tm = new TimeMarker();
    try (IndexCommunications ic = new IndexCommunications(t, tl,
        nerFeatures, termDoc, termHash, mentionLocs, tok2comm);
        AutoCloseableIterator<Communication> iter = getCommunicationsForIngest(config)) {
      while (iter.hasNext()) {
        Communication c = iter.next();
        ic.observe(c);
        if (tm.enoughTimePassed(5)) {
          Log.info(ic + "\t" + c.getId() + "\t" + EC + "\t" + Describe.memoryUsage());
        }
      }
    }
  }


  /**
   * Queries two keys:
   * 1) dataProfile, see {@link DataProfile}
   * 2) dataProvider which is either
   *    "scion"
   *    "disk:/path/to/CAG/root"
   */
  public static AutoCloseableIterator<Communication> getCommunicationsForIngest(ExperimentProperties config) {
    
    DataProfile p = DataProfile.valueOf(
        config.getString("dataProfile", DataProfile.CAG_FULL.name()));
    Log.info("dataProfile=" + p.name());
    
    String method = config.getString("dataProvider");
    if (method.equalsIgnoreCase("scion")) {
      // scion
//      try {
//        Log.info("using scion");
//        return new ScionBasedCommIter(p);
//      } catch (Exception e) {
//        throw new RuntimeException(e);
//      }
      throw new RuntimeException("scion is no longer supported");
    }
    
    String saPref = "simpleAccumulo:".toLowerCase();
    if (method.toLowerCase().startsWith(saPref)) {
      // simpleAccumulo:<namespace>
      String namespace = method.substring(saPref.length());
      Log.info("using simpleAccumulo, namespace=" + namespace);
      SimpleAccumuloConfig saConf = new SimpleAccumuloConfig(
          namespace, // e.g. twolfe-cag1 or twolfe-cawiki-en1
          SimpleAccumuloConfig.DEFAULT_TABLE,
          SimpleAccumuloConfig.DEFAULT_INSTANCE,
          SimpleAccumuloConfig.DEFAULT_ZOOKEEPERS);
      return new SimpleAccumuloCommIter(saConf, p);
    }
    
    if (method.toLowerCase().startsWith("disk:")) {
      // disk:/path/to/cag/root
      Log.info("using tgz files");
      File cagRoot = new File(method.substring("disk:".length()));
      if (!cagRoot.isDirectory())
        throw new RuntimeException("not a dir: " + cagRoot.getPath());
      List<File> archives = dataProfileCagFileFilter(cagRoot, p);
      return new FileBasedCommIter(archives);
    }
    
    // Single file (primarily for debugging)
    if (method.toLowerCase().startsWith("file:")) {
      File f = new File(method.substring("file:".length(), method.length()));
      assert f.isFile();
      return new FileBasedCommIter(Arrays.asList(f));
    }

    throw new RuntimeException("can't parse: " + method);
  }

//  /**
//   * If "communicationArchives" is provided, this is taken to be CAG root,
//   * and tgz files are used for iteration. Otherwise it is assumed that we
//   * are running on the COE and scion may be used to connect to accumulo.
//   */
//  public static AutoCloseableIterator<Communication> getCommunicationsForIngest(ExperimentProperties config) {
//    
//    DataProfile p = DataProfile.valueOf(
//        config.getString("dataProfile", DataProfile.CAG_FULL.name()));
//    Log.info("dataProfile=" + p.name());
//    
//    String key = "communicationArchives";
//    if (config.containsKey(key)) {
//      Log.info("using tgz files");
//      File cagRoot = config.getExistingDir(key);
//      List<File> archives = dataProfileCagFileFilter(cagRoot, p);
//      return new FileBasedCommIter(archives);
//    }
//    try {
//      Log.info("using scion");
//      return new ScionBasedCommIter();
//    } catch (Exception e) {
//      throw new RuntimeException(e);
//    }
//  }

  public static class SimpleAccumuloCommIter implements AutoCloseableIterator<Communication> {
    private edu.jhu.hlt.concrete.simpleaccumulo.AutoCloseableIterator<Communication> iter;

    public SimpleAccumuloCommIter(SimpleAccumuloConfig saConf, DataProfile p) {
      Log.info("using " + saConf);

      Optional<Pair<String, String>> b = p.commIdBoundaries();
      Range r = null;
      if (b.isPresent()) {
        r = new Range(b.get().get1(), true, b.get().get2(), true);
        Log.info("with range " + r);
        Log.info("aka " + b.get());
      }

      SimpleAccumulo sa = new SimpleAccumulo(saConf);
      
      ExperimentProperties config = ExperimentProperties.getInstance();
      String username = config.getString("scion.accumulo.user", "reader");
      String password = config.getString("scion.accumulo.password", "an accumulo reader");
      try {
        sa.connect(username, new PasswordToken(password));
        iter = sa.scan(r);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public void close() throws Exception {
      iter.close();
    }

    @Override
    public boolean hasNext() {
      return iter.hasNext();
    }

    @Override
    public Communication next() {
      return iter.next();
    }
  }

  /*
   * Note:
   * 1) This must run on the COE (talks to seemingly arbitrary grid machines, difficult to tunnel)
   * 2) -Dscion.accumulo.user=reader -Dscion.accumulo.password='an accumulo reader'
  public static class ScionBasedCommIter implements AutoCloseableIterator<Communication> {

    public static void main(ExperimentProperties config) throws Exception {

      // Show what we can pull out
      ConnectorFactory cf = new ConnectorFactory();
      ScionConnector sc = cf.getConnector();
      Analytics a = new Analytics(sc);
      Multimap<String, String> mm = a.getAvailableAnalytics();
      for (Map.Entry<String, Collection<String>> e : mm.asMap().entrySet()) {
        System.out.println("Analytic: " + e.getKey());
        System.out.println("\tContains analytic layers: " + e.getValue().toString());
        System.out.println();
      }
      System.out.println();
      
      try (AutoCloseableIterator<Communication> iter = getCommunicationsForIngest(config)) {
        while (iter.hasNext()) {
          Communication c = iter.next();
          showComm(c);
        }
      }
    }
    
    public static void showComm(Communication c) {
      System.out.println(c.getId());
      if (c.isSetSectionList()) {
        for (Section section : c.getSectionList()) {
          System.out.println("section:" + section.getUuid());
          if (section.isSetSentenceList()) {
            for (Sentence sentence : section.getSentenceList()) {
              System.out.println("sentence:" + sentence.getUuid());
              Tokenization t = sentence.getTokenization();
              if (t == null) {
                System.out.println("NULL TOK!");
              } else {
                System.out.println("tok:" + t.getUuid());
                if (t.isSetDependencyParseList()) {
                  for (DependencyParse dp : t.getDependencyParseList())
                    System.out.println("dparse:" + dp.getMetadata());
                }
                if (t.isSetTokenTaggingList()) {
                  for (TokenTagging tt : t.getTokenTaggingList())
                    System.out.println("toktag:" + tt.getMetadata());
                }
              }
            }
          }
        }
      }
      if (c.isSetSituationMentionSetList()) {
        System.out.println("sms is set");
        for (SituationMentionSet sms : c.getSituationMentionSetList())
          System.out.println("sms: " + sms.getMetadata());
      }
      if (c.isSetEntityMentionSetList()) {
        System.out.println("ems is set");
        for (EntityMentionSet ems : c.getEntityMentionSetList())
          System.out.println("ems:" + ems.getMetadata());
      }
      System.out.println();
    }

    private AutoCloseableIterator<byte[]> i;
    private Iterator<Communication> itr;
    
    public ScionBasedCommIter(DataProfile filter) throws ScionException {
      ConcreteDataSets corpus = ConcreteDataSets.GIGAWORD;
      ConnectorFactory cf = new ConnectorFactory();
      ScionConnector sc = cf.getConnector();

      SequentialQueryRunner.Builder qrb = new SequentialQueryRunner.Builder();
      qrb.setDataSet(corpus);
      qrb.setConnector(sc);

      List<String> analyticList = new ArrayList<String>();
      analyticList.add("Stanford Coref-1");                 // EntitySet (and hopefully EntityMentionSet)
      analyticList.add("Section");
      analyticList.add("Sentence");
      analyticList.add("Stanford CoreNLP PTB-1");           // Tokenization
      analyticList.add("Stanford CoreNLP basic-1");         // DependencyParse
      analyticList.add("Stanford CoreNLP-1");               // TokenTaggings for POS, NER, lemma
      
      ExperimentProperties config = ExperimentProperties.getInstance();
      if (config.containsKey("analytics")) {
        String[] as = config.getStrings("analytics");
        analyticList = Arrays.asList(as);
        Log.info("over-riding analyticsList=" + analyticList);
      }

//      ExperimentProperties config = ExperimentProperties.getInstance();
//      for (String a : config.getStrings("analytics")) {
//        Log.info("adding analytic: " + a);
//        analyticList.add(a);
//      }

      Analytics a = new Analytics(sc);
      qrb.addAllAnalytics(a.createAnalytics(analyticList));

      // How to list all available analytics
//      for (AnalyticViaUser as : a.createAllAvailableAnalytics())
//        System.out.println("name=\"" + as.getName() + "\"\t" + as);

      if (filter != null) {
        Log.info("filter: " + filter);
        Optional<Pair<String, String>> b = filter.commIdBoundaries();
        if (b.isPresent()) {
          Log.info("setting beg/end to: " + b.get());
          qrb.setBeginningRange(b.get().get1());
          qrb.setEngingRange(b.get().get2());
        }
      }

      SequentialQueryRunner qr = qrb.build();
      i = qr.query();
      itr = new AccumuloCommunicationIterator(i);
    }

    @Override
    public boolean hasNext() {
      return itr.hasNext();
    }
    @Override
    public Communication next() {
      return itr.next();
    }

    @Override
    public void close() throws Exception {
      Log.info("closing");
      i.close();
    }
  }
   */

  
    
  /** Relies on the fact that CAG tgz files have prefixes which are the same as {@link Communication} id prefixes */
  public static List<File> dataProfileCagFileFilter(File cagRoot, DataProfile filter) {
    List<File> all = FileUtil.find(cagRoot, "glob:**/*");
    List<File> keep = new ArrayList<>();
    for (File f : all)
      if (filter.keep(f.getName()))
        keep.add(f);
    Log.info("kept k=" + keep.size() + " of n=" + all.size()
      + " files matching dataProfile=" + filter.name() +  " in cagRoot=" + cagRoot.getPath());
    return keep;
  }

   
  /**
   * Iterates over tgz archives.
   */
  public static class FileBasedCommIter implements AutoCloseableIterator<Communication> {
    private ArrayDeque<File> inputCommTgzs;
    private TarGzArchiveEntryCommunicationIterator iter;

    public FileBasedCommIter(List<File> inputCommTgzs) {
      this.inputCommTgzs = new ArrayDeque<>();
      this.inputCommTgzs.addAll(inputCommTgzs);
      Log.info("numFiles=" + inputCommTgzs.size() + " first=" + this.inputCommTgzs.peek().getPath());
      advance();
    }
    
    private void advance() {
      File f = inputCommTgzs.pop();
      Log.info("reading " + f.getName());
      try {
        iter = new TarGzArchiveEntryCommunicationIterator(new FileInputStream(f));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public boolean hasNext() {
      return iter != null && iter.hasNext();
    }

    @Override
    public Communication next() {
      Communication c = iter.next();
      if (!iter.hasNext()) {
        iter.close();
        iter = null;
        if (!inputCommTgzs.isEmpty())
          advance();
      }
      return c;
    }

    @Override
    public void close() throws Exception {
      if (iter != null)
        iter.close();
    }
  }
  

  public static void addInverseHash(String s, IntObjectHashMap<String> addTo) {
    int h = ReversableHashWriter.onewayHash(s);
    String ss = addTo.get(h);
    if (ss == null) {
      addTo.put(h, s);
    } else if (ss.equals(s)) {
      // no-op
    } else {
      // TODO split on "||" and check if already contains
      addTo.put(h, ss + "||" + s);
    }
  }
  
  /**
   * Adds "||" between two strings which could both serve as values.
   * @param f should have lines like: <hashedTerm> <tab> <termString>
   */
  public static void readInverseHash(File f, IntObjectHashMap<String> addTo) {
    Log.info("adding rows from " + f.getPath());
    int n0 = addTo.size();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] ar = line.split("\t", 2);
        int k = Integer.parseUnsignedInt(ar[0]);
        Object old = addTo.put(k, ar[1]);
//        assert old == null || ar[1].equals(old) : "key=" + k + " appears to have two values old=" + old + " and new=" + ar[1];
        if (old != null) {
          if (old.equals(ar[1])) {
            // no op
            // TODO split on "||" and check each term
          } else {
            addTo.put(k, old + "||" + ar[1]);
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    int added = addTo.size() - n0;
    Log.info("added " + added + " rows, prevSize=" + n0 + " curSize=" + addTo.size());
  }

  /**
   * Holds a query string like "PERSON works for COMPANY",
   * finds arg0 and arg1 by looking for all uppercase words,
   * and stores a dependency path relation, e.g. "nsubj</works/prep>/for/pobj> ".
   */
  public static class QueryDeprel {
    private String[] terms;
    private int arg0idx, arg1idx; // into terms
    private int[] heads;
    private String[] deps;
    private Path2 path;
    private String pathStr;
    
    public static QueryDeprel fromConllX(List<String[]> conllx) {
      QueryDeprel q = new QueryDeprel();
      int n = conllx.size();
      q.terms = new String[n];
      q.heads = new int[n];
      q.deps = new String[n];
      for (int i = 0; i < n; i++) {
        String[] ar = conllx.get(i);
        q.terms[i] = ar[SlowParseyWrapper.FORM];
        q.heads[i] = Integer.parseInt(ar[SlowParseyWrapper.HEAD]) - 1;
        q.deps[i] = ar[SlowParseyWrapper.DEPREL];
        assert Integer.parseInt(ar[SlowParseyWrapper.ID]) == i+1;
      }
      
      q.arg0idx = q.findArg(0);
      assert q.arg0idx >= 0 : "no arg0? " + Arrays.toString(q.terms);
      q.arg1idx = q.findArg(q.arg0idx + 1);
      assert q.arg1idx >= 0 : "no arg1? " + Arrays.toString(q.terms);
      assert q.findArg(q.arg1idx + 1) < 0 : "should only be two args!";
      
      q.path = new Path2(q.arg0idx, q.arg1idx,
          edu.jhu.hlt.fnparse.datatypes.DependencyParse.fromConllx(conllx),
          edu.jhu.hlt.fnparse.datatypes.Sentence.convertFromConllX("", "", conllx, false));
      q.pathStr = q.path.getPath(NodeType.WORD, EdgeType.DEP, false);
//      Log.info(Arrays.toString(q.terms));
//      Log.info(q.path.connected());
//      Log.info(q.path.getPath(NodeType.WORD, EdgeType.DEP, true));
      return q;
    }
    
    private int findArg(int offset) {
      for (int i = offset; i < terms.length; i++) {
        if (terms[i].matches("[A-Z]+")) {
          return i;
        }
      }
      return -1;
    }
    
    @Override
    public String toString() {
      return "(QueryDeprel " + terms[arg0idx] + " " + pathStr + " " + terms[arg1idx] + ")";
    }

    public static void demo() throws Exception {
      List<String> queries = new ArrayList<>();
      queries.add("PERSON works for COMPANY");
      queries.add("PERSON joined COMPANY in December");
      queries.add("PERSON joined COMPANY");
      queries.add("PERSON grew up in LOCATION");
      queries.add("PERSON was born in LOCATION");
      queries.add("PERSON , from LOCATION");

      SlowParseyWrapper p = SlowParseyWrapper.buildForLaptop();
      for (String q : queries) {
        System.out.println(q);
        List<String[]> conllx = p.parse(q.split("\\s+"));
        //      for (String[] t : conllx) {
        //        System.out.println(t[SlowParseyWrapper.ID]
        //            + "\t" + t[SlowParseyWrapper.FORM]
        //            + "\t" + t[SlowParseyWrapper.POSTAG]
        //            + "\t" + t[SlowParseyWrapper.HEAD]
        //            + "\t" + t[SlowParseyWrapper.DEPREL]);
        //      }
        System.out.println(QueryDeprel.fromConllX(conllx));
        System.out.println();
      }
      //    QueryDeprel q = QueryDeprel.fromConllX(Arrays.asList(
      //        "1 PERSON  _ NOUN  NN  _ 2 nsubj _ _".split("\\s+"),
      //        "2 works _ VERB  VBZ _ 0 ROOT  _ _".split("\\s+"),
      //        "3 for _ ADP IN  _ 2 prep  _ _".split("\\s+"),
      //        "4 COMPANY _ NOUN  NN  _ 3 pobj  _ _".split("\\s+")));
      //    System.out.println(q);
    }

  }

  
  /** meta, just for developing code 
   * @throws Exception */
  public static void develop(ExperimentProperties config) throws Exception {
//    QueryDeprel.demo();
    
    
    // Wn debug:
    // I should be able to get from "resolution.n" to "resolve.v"
    // also: "demonstrations.n" => "demonstrate.v"
    
    IRAMDictionary wn = TargetPruningData.getInstance().getWordnetDict();
    
//    IIndexWord iw = wn.getIndexWord("resolution", POS.NOUN);
    IIndexWord iw = wn.getIndexWord("demonstration", POS.NOUN);
    System.err.flush();
    System.out.flush();
    System.out.println(iw);
    
    for (IWordID wid : iw.getWordIDs()) {
      Log.info("word id: " + wid + " lemma=" + wid.getLemma() + " ss=" + wid.getSynsetID());
      
      
      IWord w = wn.getWord(wid);
      for (IWordID rw : w.getRelatedWords()) {
        System.out.println("\t" + rw);
      }
      
//      ISynset ss = wn.getSynset(wid.getSynsetID());
//      for (Entry<IPointer, List<ISynsetID>> x : ss.getRelatedMap().entrySet()) {
//        System.out.println("\t" + x.getKey() + " => " + x.getValue());
//        
//        if (x.getKey().getName().equalsIgnoreCase("hypernym")) {
//          for (ISynsetID hs : x.getValue()) {
//            ISynset hss = wn.getSynset(hs);
//            System.out.println("\t\t" + hss.getGloss() + " " + hss.getPOS());
//            System.out.println("\t\t" + hss.getRelatedMap());
//          }
//        }
//      }
      System.out.println();
    }
  }
  
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Log.info("starting, args: " + Arrays.toString(args));
    String command = config.getString("command");
    switch (command) {
//    case "tokenObs":
//      mainTokenObs(config);
//      break;
    case "extractBasicData":
      extractBasicData(config);
      break;
    case "buildDocVecs":
      ComputeTfIdfDocVecs.main(config);
      break;
    case "nerFeatures":
      NerFeatureInvertedIndex.main(config);
      break;
    case "entitySearch":
      EntitySearch.main(config);
      break;
    case "indexDeprels":
      IndexDeprels.main(config);
      break;
    case "hashDeprels":
      HashDeprels.main(config);
      break;
    case "indexFrames":
      IndexFrames.main(config);
      break;
    case "situationSearch":
      SituationSearch.main(config);
      break;
    case "testAccumulo":
      // ssh -fNL 8083:test1:8082 test2b
      // where 8083 is local (use below) and 8082 is remote (see ScionForwarding)
      int localPort = config.getInt("port", 8088);
      ForwardedFetchCommunicationRetrieval cr = new ForwardedFetchCommunicationRetrieval(localPort);
      cr.test("NYT_ENG_20090901.0206");
      break;
    case "develop":
      develop(config);
      break;
    case "kbpDirectedEntitySearch":
      KbpDirectedEntitySearch.main(config);
      break;
    case "trainDedup":
      ParmaVw.main(config);
      break;
//    case "scionDev":
//      ScionBasedCommIter.main(config);
//      break;
    default:
      Log.info("unknown command: " + command);
      break;
    }
    
    Log.info("done");
  }
}
