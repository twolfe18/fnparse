package edu.jhu.hlt.ikbp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.function.Consumer;

import edu.jhu.hlt.concrete.Cluster;
import edu.jhu.hlt.concrete.ClusterMember;
import edu.jhu.hlt.concrete.Communication;
import edu.jhu.hlt.ikbp.ConcreteIkbpAnnotations.Topic;
import edu.jhu.hlt.ikbp.data.FeatureType;
import edu.jhu.hlt.ikbp.data.Id;
import edu.jhu.hlt.ikbp.data.Node;
import edu.jhu.hlt.ikbp.data.NodeType;
import edu.jhu.hlt.ikbp.features.ConcreteMentionFeatureExtractor;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.features.indexing.FNode;
import edu.jhu.hlt.tutils.features.indexing.FNode.AssignIndices;
import edu.jhu.hlt.tutils.rand.ReservoirSample;

/**
 * Writes out (coreferent, mentionType, mention1, mention2, m1Features, m2Features) tuples
 * to a text file for offline training.
 * 
 * This could be produced by something which might be easy to make:
 * (clusterLabel, mentionType, mentionUUID, commUUID)
 * Then do a join on (clusterLabel, mentionType)
 * THEN extract features.
 * 
 * OK, do the second thing, but assuming that you have Concrete input.
 *
 * @author travis
 */
public class CreateParmaTrainingData {

  private ConcreteMentionFeatureExtractor featEx;
  
  private boolean extractPairwiseFeats = true;
  
  public CreateParmaTrainingData(ConcreteMentionFeatureExtractor featEx) {
    this.featEx = featEx;
  }
    
  public static String escape(String feat) {
    return feat.replaceAll(":", "-COLON-");
  }

  public static String buildLine(String y, String topic, List<Id> queryFeats, List<Id> responseFeats, List<Id> pairwiseFeats) {
    StringBuilder sb = new StringBuilder();
    sb.append(y);
    // Just the features with q:foo and r:bar
    sb.append(" " + escape(topic));
    sb.append("|q");
    for (Id f : queryFeats) {
      if (!InformationRetrievalExperiment.skip(f)) {
        String t = FeatureType.findByValue(f.getType()).name();
        //          sb.append(" q:" + t + "/" + f.getName());
        sb.append(" " + t + "/" + escape(f.getName()));
      }
    }
    sb.append(" |r");
    for (Id f : responseFeats) {
      if (!InformationRetrievalExperiment.skip(f)) {
        String t = FeatureType.findByValue(f.getType()).name();
        //          sb.append(" r:" + t + "/" + f.getName());
        sb.append(" " + t + "/" + escape(f.getName()));
      }
    }
    if (pairwiseFeats != null) {
      sb.append(" |p");
      for (Id f : pairwiseFeats) {
        String qr = f.getName();
        //        for (String qr : pairwiseFeats) {
        //          sb.append(" qr:PW/" + qr);
        sb.append(" PW/" + escape(qr));
      }
    }
    return sb.toString();
  }
  
  public static List<Id> upconvert(List<String> pairwiseFeatures) {
    List<Id> pairwise = new ArrayList<>();
    for (String f : pairwiseFeatures)
      pairwise.add(new Id().setName(f));
    return pairwise;
  }
  
  public static class Inst {
    String topic;
    Topic topicValue;       // watch out for OOM issues if this is non-null
    String clusterLabel;
    Node node;  // handles mentionType and features
    ClusterMember item;
    
    public String toLine() {
      StringBuilder sb = new StringBuilder();
      
      sb.append(clusterLabel);
      sb.append(' ');
      sb.append(NodeType.findByValue(node.getId().getType()));
      sb.append(' ');
      sb.append(item.getCommunicationId().getUuidString());
      
      for (int i = 0; i < node.getFeaturesSize(); i++) {
        Id f = node.getFeatures().get(i);
//        if (InformationRetrievalExperiment.skip(f))
//          continue;
        sb.append(' ');
        sb.append(FeatureType.findByValue(f.getType()));
        sb.append('/');
        sb.append(f.getName());
      }

      return sb.toString();
    }
  }
  
  public static class PairInst {
    boolean y;
    Inst left, right;
    List<String> pairwiseFeats;
    
    public PairInst(boolean y, Inst left, Inst right) {
      this.y = y;
      this.left = left;
      this.right = right;
    }

    public String toString() {
      assert left.topic.equals(right.topic);
      return buildLine(y ? "+1" : "-1", left.topic, left.node.getFeatures(), right.node.getFeatures(), upconvert(pairwiseFeats));
//      StringBuilder sb = new StringBuilder();
//      sb.append(y ? "+1" : "-1");
//      // Just the features with q:foo and r:bar
//      assert left.topic.equals(right.topic);
//      sb.append(" " + escape(left.topic));
//      sb.append("|q");
//      for (Id f : left.node.getFeatures()) {
//        if (!InformationRetrievalExperiment.skip(f)) {
//          String t = FeatureType.findByValue(f.getType()).name();
////          sb.append(" q:" + t + "/" + f.getName());
//          sb.append(" " + t + "/" + escape(f.getName()));
//        }
//      }
//      sb.append(" |r");
//      for (Id f : right.node.getFeatures()) {
//        if (!InformationRetrievalExperiment.skip(f)) {
//          String t = FeatureType.findByValue(f.getType()).name();
////          sb.append(" r:" + t + "/" + f.getName());
//          sb.append(" " + t + "/" + escape(f.getName()));
//        }
//      }
//      if (pairwiseFeats != null) {
//        sb.append(" |p");
//        for (String qr : pairwiseFeats) {
////          sb.append(" qr:PW/" + qr);
//          sb.append(" PW/" + escape(qr));
//        }
//      }
//      return sb.toString();
    }

  }
  
  public List<Inst> extractMentionsAndFeatures(Topic t) {
    featEx.set(t.comms);
    List<Inst> items = new ArrayList<>();
    int numCluster = t.clustering.getClusterListSize();
    for (int i = 0; i < numCluster; i++) {
      Cluster cluster = t.clustering.getClusterList().get(i);
      for (int mIdx : cluster.getClusterMemberIndexList()) {
        ClusterMember x = t.clustering.getClusterMemberList().get(mIdx);
        Communication c = t.getCommById(x.getCommunicationId());
        EntitySituationCommIndex es = new EntitySituationCommIndex(c);
        
        Node n = new Node().setId(new Id());
        n.addToFeatures(new Id()
            .setType(FeatureType.CONCRETE_UUID.getValue())
            .setName(x.getElementId().getUuidString()));
        n.getId().setName(x.getElementId().getUuidString());
        if (es.getEntityMentionById(x.getElementId()) != null) {
          n.getId().setType(NodeType.ENTITY.getValue());
        } else if (es.getSituationMentionById(x.getElementId()) != null) {
          n.getId().setType(NodeType.SITUATION.getValue());
        } else {
          throw new RuntimeException();
        }

        Inst y = new Inst();
        y.topic = t.name;
        y.clusterLabel = t.name + "/c" + i;
        y.item = x;
        y.node = n;
        if (extractPairwiseFeats)
          y.topicValue = t;
        featEx.extract(n, n.getFeatures());
        items.add(y);
      }
    }
    Log.info("make " + items.size() + " items");
    return items;
  }
  
  /**
   * @return cluster to mentions.
   */
  public Map<String, List<Inst>> produceTrainingPairs(
      List<Inst> mentions, double maxNegToPos, Consumer<PairInst> output, Random rand) {
    
    Counts<String> events = new Counts<>();
    TimeMarker tm = new TimeMarker();
    List<PairInst> toOutput = new ArrayList<>();
    
    // Group by cluster
    Map<String, List<Inst>> c2m = new HashMap<>();
    for (Inst i : mentions) {
      List<Inst> l = c2m.get(i.clusterLabel);
      if (l == null) {
        l = new ArrayList<>();
        c2m.put(i.clusterLabel, l);
      }
      l.add(i);
    }

    // Group by topic
    Map<String, List<Inst>> t2m = new HashMap<>();
    for (Inst i : mentions) {
      List<Inst> l = t2m.get(i.topic);
      if (l == null) {
        l = new ArrayList<>();
        t2m.put(i.topic, l);
      }
      l.add(i);
    }
    
    Log.info("given " + mentions.size() + " mentions, found " + c2m.size() + " clusters across " + t2m.size() + " topics");
    
    // Output positive examples
    Counts<String> posInEachTopic = new Counts<>();
    for (List<Inst> clust : c2m.values()) {
      int n = clust.size();
      for (int i = 0; i < n-1; i++) {
        for (int j = i+1; j < n; j++) {
          Inst left = clust.get(i);
          Inst right = clust.get(j);
          PairInst p = new PairInst(true, left, right);
          toOutput.add(p);
          assert left.topic.equals(right.topic);
          posInEachTopic.increment(left.topic);
          events.increment("pos");
        }
      }
    }
    Log.info("source of positive instances: " + posInEachTopic);
    
    // Output random sample of negatives
    int negTotal = 0;
    for (Entry<String, List<Inst>> x : t2m.entrySet()) {
      String topic = x.getKey();
      if (tm.enoughTimePassed(5))
        Log.info("working on negatives for " + topic + "\t" + events);
      int countPos = posInEachTopic.getCount(topic);
      int neg = (int) (1 + maxNegToPos * countPos);
      negTotal += neg;
      ReservoirSample<IntPair> reservoir = new ReservoirSample<>(neg, rand);
      List<Inst> all = x.getValue();
      int n = all.size();
      for (int i = 0; i < n-1; i++) {
        for (int j = i+1; j < n; j++) {
          Inst left = all.get(i);
          Inst right = all.get(j);
          assert left.topic.equals(right.topic);
          if (left.clusterLabel.equals(right.clusterLabel))
            continue;
          reservoir.add(new IntPair(i, j));
          events.increment("neg/all");
        }
      }
      for (IntPair ij : reservoir) {
        Inst left = all.get(ij.first);
        Inst right = all.get(ij.second);
        PairInst p = new PairInst(false, left, right);
        toOutput.add(p);
        events.increment("neg/sample");
      }
    }
    
    
    // Compute pairwise features
    if (extractPairwiseFeats) {
      Log.info("computing pairwise features");
      Collections.sort(toOutput, new Comparator<PairInst>() {
        @Override
        public int compare(PairInst o1, PairInst o2) {
          return o1.left.topic.compareTo(o2.left.topic);
        }
      });
      String t = null;
      for (PairInst p : toOutput) {
        if (tm.enoughTimePassed(5)) {
          Log.info("working on pairwise features for " + p.left.topic + "\t" + events);
        }
        if (t == null || !t.equals(p.left.topic)) {
          featEx.set(p.left.topicValue.comms);
          t = p.left.topic;
        }
        Id cm1 = DataUtil.filterByFeatureType(p.left.node.getFeatures(), FeatureType.CONCRETE_UUID).get(0);
        Id cm2 = DataUtil.filterByFeatureType(p.right.node.getFeatures(), FeatureType.CONCRETE_UUID).get(0);
        p.pairwiseFeats = featEx.pairwiseFeats(cm1, cm2);
        output.accept(p);
        events.increment("pw/" + (p.y ? "pos" : "neg"));
      }
    } else {
      for (PairInst p : toOutput)
        output.accept(p);
    }
    
    Log.info("output " + posInEachTopic.getTotalCount() + " pos and " + negTotal + " neg examples");
    return c2m;
  }
  
    /*
awk '{print $1}' </tmp/parma-train-mentions.txt | uniq -c | awk '{print $1}' | sort -n | uniq -c | sort -rn | head -n 20
<count> <clusterSize>
  14527 1       # mostly singletons clusters!
    424 2
    254 3
    184 4
    147 6
    136 5
    121 7
     84 8
     80 9
     56 10
     51 11
     37 12      # long tail!
     25 17      # lots of big clusters!
     25 14
     19 15
     18 16
     17 13
     16 18
     15 19
     14 21
     */

      /*
       * Does HEADWORD match explain all/most the examples?
       * 
       * cat ecbplus-all-pairs.sampleNeg8.txt | perl -pe 's/([qr]:HEADWORD)\/(\S+)/\1=\2/g' | perl -pe 's/([+-]1) /y=\1 /' | key-values y q:HEADWORD r:HEADWORD | awk '{l=($2 == $3); print $1, l}' | sort | uniq -c
1880250 -1 0
 158758 +1 0
  13121 -1 1
  77908 +1 1
       *
       * If HEADWORDs match, there is a 77908/(77908+13121) = 86% chance they are coreferent,
       * As opposed to a 158758/(158758+1880250) = 7.8% chance otherwise
       * 
       * AH, but HEADWORD match only explains 77908/(77908+158758) = 33% of the positive corefs!
       */

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    String c = config.getString("command");
    switch (c.toLowerCase()) {
    case "genstrings":
    case "genstr":
    case "extract":
    case "extractfeats":
      new GenStr().run(config);
      break;
    case "genints":
    case "genint":
    case "intify":
      new GenInt().run(config);
      break;
    default:
      System.err.println("unknown command: " + c);
      break;
    }
  }
  
  public static FNode<String> countMentionFeats(File mentionsIn, boolean onlyPosFeats) throws IOException {
    Log.info("counting features in " + mentionsIn.getPath());
    FNode<String> root = FNode.root();
    try (BufferedReader r = FileUtil.getReader(mentionsIn)) {
      int n = 0;
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        n++;
        String[] tokens = line.split("\\s+");

        if (onlyPosFeats) {
          // tokens[0] is label
          boolean pos = "+1".equals(tokens[0]);
          assert pos || "-1".equals(tokens[0]);
          if (pos) continue;
        }

        for (int i = 1; i < tokens.length; i++) {
          String[] path2 = tokens[i].split("/");
          root.add(0, path2);
        }
      }
      Log.info("processed " + n + " lines and " + root.getNumObs() + " features");
    }
    return root;
  }
  
  public static void writeAlphabet(FNode<String> root, File alphOut) throws IOException {
    Log.info("writing alphabet to " + alphOut.getPath());
    int nFeatPrune = 0, nFeatKept = 0;;
    try (BufferedWriter w = FileUtil.getWriter(alphOut)) {
      Deque<FNode<String>> q = new ArrayDeque<>();
      q.push(root);
      root.buildParents();
      while (!q.isEmpty()) {
        FNode<String> cur = q.pop();

        if (!cur.isValidFeature()) {
          nFeatPrune++;
          continue;
        }
        nFeatKept++;

        IntPair tf = cur.getTypeFeat();
        List<String> path = cur.getObjectPath();
        if (path.isEmpty()) {
          w.write("<ROOT>\t<INTERCEPT>");
        } else {
          int n = path.size();
          w.write(n == 1 ? "<ROOT>" : StringUtils.join("/", path.subList(0, n-1)));
          w.write('\t');
          w.write(path.get(n-1));
        }
        w.write('\t');
        w.write(String.valueOf(tf.first));
        w.write('\t');
        w.write(String.valueOf(tf.second));
        w.write('\t');
        w.write(String.valueOf(cur.getNumObs()));
        w.write('\t');
        w.write(String.valueOf(cur.getNumValidChildren()));
        w.newLine();

        for (FNode<String> c : cur.getChildren())
          q.push(c);
      }
    }
    Log.info("kept " + nFeatKept + " and pruned " + nFeatPrune + " features");
  }
  
  /**
   * Read in strings and convert them to ints using FNode
   * Input and output format are the same modulo strings/ints.
   * The format is specified by the format function above.
   * 
   * Alphabet has format:
   * <typeStr> <featStr> <typeInt> <featInt> <nObs>
   */
  public static class GenInt {
    private FNode<String> root;
    public void run(ExperimentProperties config) throws IOException {
      File mentionsIn = config.getFile("mentionsIn");
      File alphOut = config.getFile("alphOut");
      
      boolean onlyPosFeats = config.getBoolean("onlyPosFeats", false);
      Log.info("onlyPosFeats=" + onlyPosFeats);
      
      // Count features in input
      // There is on row/line for every mention in the corpus.
      Log.info("counting features in " + mentionsIn.getPath());
      root = countMentionFeats(mentionsIn, onlyPosFeats);
      
      // Assign indices
      Log.info("assigning feature indices...");
      AssignIndices<String> ai = new AssignIndices<>(root);
      int minObs = config.getInt("minObs", 3);          // I'm setting this low b/c I plan on using word embeddings
      int maxTypes = config.getInt("maxTypes", 1000);
      int maxFeats = config.getInt("maxFeats", 1<<20);
      ai.run(minObs, maxTypes, maxFeats);

      // Map over the mention-pair features from strings to ints
      Log.info("converting mention-pair feats str=>int");
      File mentionPairsIn = config.getFile("mentionPairsIn");
      File mentionPairsOut = config.getFile("mentionPairsOut");
      Log.info(mentionPairsIn.getPath() + "  ==>  " + mentionPairsOut.getPath());
      root.buildParents();
      try (BufferedReader r = FileUtil.getReader(mentionPairsIn);
          BufferedWriter w = FileUtil.getWriter(mentionPairsOut)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          String[] tokens = line.split("\\s+");
          w.write(tokens[0]); // label
          for (int i = 1; i < tokens.length; i++) {
            int c = tokens[i].indexOf(':');
            String[] path = tokens[i].substring(c+1).split("/");
            FNode<String> node = root.get(0, path);
            if (node.isValidFeature()) {
              IntPair tf = node.getTypeFeat();
              w.write(' ');
              w.write(tokens[i].substring(0, c+1));
              w.write(tf.first + "/" + tf.second);
            }
          }
          w.newLine();
        }
      }

      // Output the alphabet
      writeAlphabet(root, alphOut);
      
      Log.info("done");
    }
  }

  public static class GenStr {
    public void run(ExperimentProperties config) throws IOException {
      File mentionPairOut = config.getFile("mentionPairsOut"); // new File("data/parma/ecbplus/training_files/ecbplus-all-pairs.sampleNeg8.txt");
      File mentionsOut = config.getFile("mentionsOut", null);

      Log.info("starting...");
      ConcreteIkbpAnnotations anno;
      String smTool, emTool;// = "ecbplus";
      String ds = config.getString("dataset");
      switch (ds.toLowerCase()) {
      case "ecb+":
      case "ecbplus":
        anno = EcbPlusConcreteClusterings.build(config);
        smTool = emTool = "ecbplus";
        break;
      case "rf":
      case "rothfrank":
        anno = new RfToConcreteClusterings("rothfrank", p -> true, config);
        smTool = emTool = "rothfrank";
        break;
      default:
        throw new RuntimeException("unknown dataset: " + ds);
      }
      ConcreteMentionFeatureExtractor featEx = new ConcreteMentionFeatureExtractor(
          smTool, emTool, Collections.emptyList());
      CreateParmaTrainingData cpd = new CreateParmaTrainingData(featEx);

      Log.info("making training instances...");
      List<Inst> instances = new ArrayList<>();
      while (anno.hasNext()) {
        Topic t = anno.next();
        List<Inst> y = cpd.extractMentionsAndFeatures(t);
        instances.addAll(y);
      }

      // Write out mention-pair features
      Random rand = new Random(9001);
      double maxNegToPos = config.getDouble("maxNegToPos", 2);
      Log.info("writing mention-pair training instances to " + mentionPairOut.getPath());
      Map<String, List<Inst>> c2m = null;
      try (BufferedWriter w = FileUtil.getWriter(mentionPairOut)) {
        c2m = cpd.produceTrainingPairs(instances, maxNegToPos, pi -> {
          try {
            w.write(pi.toString());
            w.newLine();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, rand);
      }

      // Write out features on each mention (e.g. for building an alphabet or training a singleton detector)
      if (mentionsOut == null) {
        Log.info("not writing out the mentions");
      } else {
        Log.info("writing mention features (label=singleton?) instances to " + mentionsOut.getPath());
        try (BufferedWriter w = FileUtil.getWriter(mentionsOut)) {
          for (List<Inst> clust : c2m.values()) {
            boolean singleton = clust.size() < 2;
            for (Inst i : clust) {
              String label = singleton ? "-1" : "+1";
              w.write(label);
              for (Id f : i.node.getFeatures()) {
                if (!InformationRetrievalExperiment.skip(f)) {
                  String[] s = f.getName().split("\\s+");
                  if (s.length > 1) {
                    Log.warn("skipping feature with whitespace in value: " + f);
                  } else {
                    w.write(' ');
                    w.write(FeatureType.findByValue(f.getType()).name());
                    w.write('/');
                    w.write(f.getName());
                  }
                }
              }
              w.newLine();
            }
          }
        }
      }
      
      Log.info("done");
    }
  }

}
