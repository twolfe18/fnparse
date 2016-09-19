package edu.jhu.hlt.ikbp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotator.QueryGenerationMode;
import edu.jhu.hlt.ikbp.RfToConcreteClusterings.Link;
import edu.jhu.hlt.ikbp.data.Query;
import edu.jhu.hlt.ikbp.data.Response;
import edu.jhu.hlt.ikbp.evaluation.QueryResponseAnnotations;
import edu.jhu.hlt.ikbp.features.ConcreteMentionFeatureExtractor;
import edu.jhu.hlt.tutils.Average;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.scoring.Adjoints;
import edu.jhu.prim.tuple.Pair;

/**
 * Train and test a IKBP service. Backwards chaining for the work I need to do.
 *
 * @author travis
 */
public class InformationRetrievalExperiment { //implements Iterator<Pair<Query, Iterable<Response>>> {
  public static boolean VERBOSE = false;
  
  private ConcreteIkbpAnnotations labels;
  private Topic curTopic;

  private ConcreteIkbpAnnotator.SingleTopicAnnotator anno;
  private QueryGenerationMode annoQMode;

  private ConcreteMentionFeatureExtractor searchFeats;
  private ConcreteIkbpSearch search;
  private IkbpSearch.FeatureBased searchParams; // wraps search

  private boolean verbose = false;
  private Counts<String> events;
  
  private List<QueryResponseAnnotations> testEval = new ArrayList<>();

  public InformationRetrievalExperiment(ConcreteIkbpAnnotations labels, QueryGenerationMode qmode, Random rand) {
    Log.info("labels.toolName=" + labels.getName() + " queryGenerationMode=" + qmode.name());
    this.labels = labels;
    this.searchFeats = new ConcreteMentionFeatureExtractor(labels.getName(), Collections.emptyList());
    this.searchParams = new IkbpSearch.FeatureBased(null, searchFeats, rand);
    this.annoQMode = qmode;
    this.events = new Counts<>();
    nextTopic();
  }

  public boolean hasNextTopic() {
    return labels.hasNext();
  }

  public void nextTopic() {
    curTopic = labels.next();
    anno = new ConcreteIkbpAnnotator.SingleTopicAnnotator(curTopic, annoQMode);
    search = new ConcreteIkbpSearch(curTopic.clustering, curTopic.comms);
    searchFeats.set(curTopic.comms);
    searchParams.setWrapped(search);
  }

  @Override
  public String toString() {
    return "(Trainer " + events.toStringWithEq() + ")";
  }
  
  public boolean hasNext() {
    return anno.hasNext() || hasNextTopic();
  }

  public QueryResponseAnnotations next() {
    if (!anno.hasNext())
      nextTopic();
    Query q = anno.next();
    if (q == null)
      return null;
    Iterable<Response> r = search.search(q);
    QueryResponseAnnotations yy = new QueryResponseAnnotations(q);
    double prevScore = Double.POSITIVE_INFINITY;
    for (Response rr : r) {
      assert rr.getScore() <= prevScore;
      Response y = anno.annotate(q, rr);
      yy.add(rr, y);
      prevScore = rr.getScore();
    }
    return yy;
  }

  /**
   * like next() with side-effects (so don't call unless hasNext()).
   * @return progressive-validation loss
   */
  public QueryResponseAnnotations epoch() {
    // Get query
    if (!anno.hasNext())
      nextTopic();
    Query q = anno.next();
    if (verbose)
      DataUtil.showQuery(q);
    
    boolean learn = Arrays.asList("train", "dev").contains(curTopic.part);

    // Search
    List<Pair<Response, Adjoints>> r = searchParams.search2(q);
    
    QueryResponseAnnotations inst = new QueryResponseAnnotations(q);

    // Elicit response from annotator
    Response[] y = new Response[r.size()];
    Response[] yhat = new Response[r.size()];
    Adjoints[] s = new Adjoints[r.size()];
    for (int i = 0; i < y.length; i++) {
      yhat[i] = r.get(i).get1();
      s[i] = r.get(i).get2();
      y[i] = anno.annotate(q, yhat[i]);
      
      inst.add(yhat[i], y[i]);

      if (verbose) {
        DataUtil.showResponse(yhat[i]);
        System.out.println("\tlabel:       " + y[i].getScore());
      }
    }

    // Perform the update
    // Currently: minimize squared error
    if (learn) {
      for (int i = 0; i < y.length; i++) {
        double resid = y[i].getScore() - yhat[i].getScore();
        s[i].backwards(-2 * resid);
        events.increment("nTrain/response");
      }
      events.increment("nTrain/query");
    } else {
      testEval.add(inst);
    }

    if (verbose)
      System.out.println();
    return inst;
  }

  public static InformationRetrievalExperiment buildEcbTrainer(ExperimentProperties config, Random rand) {
    EcbPlusXmlStore xmlDocs = new EcbPlusXmlStore(config);
    File cdDefault = new File("data/parma/ecbplus/ECB+_LREC2014/concrete-parsey-and-stanford/");
    File concreteDocDir = config.getExistingDir("data.ecbplus.comms", cdDefault);
    ConcreteIkbpAnnotations labels = new EcbPlusConcreteClusterings("ecbplus", xmlDocs, concreteDocDir);
    InformationRetrievalExperiment t = new InformationRetrievalExperiment(labels, getQmode(config), rand);
    
    return t;
  }
  
  public static QueryGenerationMode getQmode(ExperimentProperties config) {
    QueryGenerationMode qm = QueryGenerationMode.valueOf(
        config.getString("queryGenerationMode", QueryGenerationMode.ADD_TARGETS.name()));
    return qm;
  }

  public static InformationRetrievalExperiment buildRfConcreteTrainer(ExperimentProperties config, Random rand) throws IOException {
    
    // 10x dev, 1x test
    ConcreteIkbpAnnotations labels = null;
    String tool = "rothfrank";
    for (int i = 0; i < 10; i++) {
      Predicate<Link> dev = l -> l.pair.contains("XML/dev");
      ConcreteIkbpAnnotations l = new RfToConcreteClusterings(tool, dev, config);
      if (i == 0) labels = l;
      else labels = new ConcreteIkbpAnnotations.Chain(labels, l);
    }
    Predicate<Link> dev = l -> l.pair.contains("XML/test");
    ConcreteIkbpAnnotations l = new RfToConcreteClusterings(tool, dev, config);
    labels = new ConcreteIkbpAnnotations.Chain(labels, l);

    InformationRetrievalExperiment t = new InformationRetrievalExperiment(labels, getQmode(config), rand);

    return t;
  }
  
  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    Random rand = config.getRandom();

//    InformationRetrievalExperiment t = buildEcbTrainer(config, rand);
    InformationRetrievalExperiment t = buildRfConcreteTrainer(config, rand);
//    t.verbose = true;
    
    // Train/dev/test setup by data provider
    while (t.hasNext()) {
      t.epoch();
    }
    Average testLoss = new Average.Uniform();
    for (QueryResponseAnnotations qr : t.testEval) {
      Double ap = qr.averagePrecisionAssumingPerfectRecall();
      if (ap == null)
        Log.info("WARNING: no alignments in " + qr.getQuery().getId());
      else
        testLoss.add(ap);
    }
    System.out.println("map=" + testLoss.getAverage() + " n=" + testLoss.getNumObservations());


//    int nTrain = 600;
//    int nTest = 100;
//
//    // Progressive validation
//    double totalLoss = 0;
//    Average avgLoss = new Average.Exponential(0.9);
//    for (int i = 0; i < nTrain && t.hasNext(); i++) {
//      QueryResponseAnnotations instance = t.epoch();
//      double l = instance.meanSquaredError();
//      assert !Double.isNaN(l) && Double.isFinite(l);
//      totalLoss += l;
//      avgLoss.add(l);
//      if (i % 10 == 0) {
//        System.out.println("i=" + i
//            + "\tloss=" + l
//            + "\tavgLoss=" + (totalLoss / (i+1))
//            + "\tlocalAvgLoss=" + avgLoss.getAverage()
//            + "\t" + t.toString());
//      }
//    }
//    
//    // IR evaluation metrics
//    Average map = new Average.Uniform();
//    for (int i = 0; i < nTest && t.hasNext(); i++) {
//      QueryResponseAnnotations yy = t.next();
//      Double ap = yy.averagePrecisionAssumingPerfectRecall();
//      if (ap != null)
//        map.add(ap);
//    }
//    System.out.println("map=" + map.getAverage() + " n=" + map.getNumObservations());
  }
}
