package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.entsum.CluewebLinkedSentence.Link;
import edu.jhu.hlt.entsum.DbpediaDistSup.FeatExData;
import edu.jhu.hlt.entsum.DbpediaDistSup.Join;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.InputStreamGobbler;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.prim.list.DoubleArrayList;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.MultiMap;

/**
 * Produces a set of lexico-syntactic patterns which have high MI with
 * dbpedia infobox relations. Treat each infobox relation as a concept,
 * building the Occ_{ij} matrix using these patterns.
 *
 * Given this, produce a summary for an entity.
 *
 * TODO: Ah, I do not have to extract any patterns per se,
 * I can rely entirely on the classifier scores.
 * In the model I currently have in latex, what I want to produce
 * are p_ij = \frac{p(Occ_ij=1)}{p(Occ_ij=0)}.
 * The confusing part is the sentence/mention mismatch.
 * Perhaps not, if we assume the entity we are summarizing only appears once in
 * every sentence, then there is a bijection between sentences and mentions, and
 * this is a reasonable assumption.
 * So in computing p_ij values,
 * i = an infobox relation, index to a binary classifier
 * j = a (sentence, mention) index pointing to a {@link Link} matching the mid of the summarized entity
 * If there happens to be more than one mention of the summarization entity
 *   just take the max of the scores across all mentions to get p_ij (max_{probs} => lowest cost)
 *
 * @see DbpediaDistSup for how to extract patterns.
 *
 * @author travis
 */
public class SlotsAsConceptsSummarization {
  
  /*
   * TODO have two models:
   * A) Given a sentence, what entity pairs should we even test for evoking a relation or not
   *    (this model could be rule/code-based rather than ML)
   * B) Given a mention pair, predict fact
   */
  
//  static class BatchFeatureExtraction {
//    /**
//     * Format: <sentHash> <extractionScore> <factMentionMapping> <subject> <verb> <object> <extractionFeature>+
//     * e.g. dbpedia-distsup-rare4/facts.txt {@link DbpediaDistSup.Join}
//     * Should only read <sentHash> and <factMentionMapping>, e.g. ("4ef0368789e83aa0308c6b0702826b85", "s=1,s=2,o=3")
//     */
//    private File factInput;
//    /**
//     * Create a file vwFeatureOutputDir/verbName.vw.txt for every verb in the input.
//     * 
//     */
//    private File vwFeatureOutputDir;
//  }
  
  public static String clean(String dbpediaUrl) {
    String x = dbpediaUrl.replace("http://", "");
    x = x.replaceAll("dbpedia.org/", "");
    return x;
  }
  
  /**
   * Creates a cost-sensitive multiclass training dataset for p(relation(fact) | features(pairOfEntityMentions)).
   * 
   * Issue: I have the positive examples, but I do not know what other facts are "possible" given this text.
   * Naive answer: all possible relations
   * More plausible: all relations which match on some types provable by the arguments
   * 
   * e.g. Given CluewebLinkedSentence*(subj+obj):
   *    isa(subj, author), isa(subj, person), isa(obj, book), isa(obj, workOfArt)
   * Check:
   *    observedSubjObjTypes(author) = [(author, book), (person, book), (person, workOfArt)]  => matches
   *    observedSubjObjTypes(architect) = [(person, workOfArt)]                               => doesn't match
   */
  static class BatchVwTrain {
    
    private FeatExData fed;
    private Counts<String> ec;
    private MultiMap<Pair<String, String>, String> subjObjTypes2verbs;
    private File model;
    
    public BatchVwTrain(FeatExData fed, List<DistSupFact> facts) {
      this.ec = new Counts<>();
      this.fed = fed;

      // Figure out what the space of negatives can be
      subjObjTypes2verbs = new MultiMap<>();
      for (DistSupFact f : facts) {
        ec.increment("fact");
        List<String> st = fed.getDbpediaSupertypes(f.subject());
        List<String> ot = fed.getDbpediaSupertypes(f.object());
        for (String stt : st) {
          for (String ott : ot) {
            ec.increment("fact/type");
            Pair<String, String> k = new Pair<>(stt, ott);
//            subjObjTypes2verbs.add(k, f.verb());
            subjObjTypes2verbs.addIfNotPresent(k, f.verb());
          }
        }
      }
      Log.info(ec);
    }
    
    public void buildTrainingFile(List<DistSupFact> facts, File outputVwMulti) throws Exception {
      // Output multi-line vw instances
      Log.info("writing to outputVwMulti=" + outputVwMulti.getPath());
      try (BufferedWriter w = FileUtil.getWriter(outputVwMulti)) {
        for (DistSupFact f : facts)
          writeCsoaaLdfFact(w, f, fed, subjObjTypes2verbs);
      }
    }
    
    public void trainModel(File inputTrainCsoaaLdf, File outputModel) throws Exception {
      if (!inputTrainCsoaaLdf.isFile())
        throw new RuntimeException("not a file: " + inputTrainCsoaaLdf.getPath());
      
      String[] command = new String[] {
          "vw",
          "--csoaa_ldf", "m",
          "-b", "22",
          "-f", outputModel.getPath(),
          "-q", "::",
          "-d", inputTrainCsoaaLdf.getPath(),
      };
      Log.info("running: " + Arrays.toString(command));
      ProcessBuilder pb = new ProcessBuilder(command);
      Process p = pb.start();
      InputStreamGobbler stdout = new InputStreamGobbler(p.getInputStream());
      InputStreamGobbler stderr = new InputStreamGobbler(p.getErrorStream());
      stdout.start();
      stderr.start();
      int r = p.waitFor();
      if (r != 0)
        throw new RuntimeException("r=" + r);
      
      this.model = outputModel;
    }
    
    public File getModelFile() {
      return model;
    }
  }
    
  /** @return a list of dbpedia verbs which are possible conditioned on the subj+obj types */
  public static List<String> writeCsoaaLdfFact(
      BufferedWriter w,
      DistSupFact f,
      FeatExData fed,
      MultiMap<Pair<String, String>, String> subjObjTypes2verbs) throws IOException {

    // Retrieve space of all possible verbs
    Set<String> verbs = new HashSet<>();
    List<String> st = fed.getDbpediaSupertypes(f.subject());
    List<String> ot = fed.getDbpediaSupertypes(f.object());
    for (String stt : st) {
      for (String ott : ot) {
        Pair<String, String> k = new Pair<>(stt, ott);
        verbs.addAll(subjObjTypes2verbs.get(k));
      }
    }

    // Output
    if (verbs.size() < 2) {
      return null;  //Collections.emptyList();
    }
    List<String> out = new ArrayList<>();
    w.write("shared |");
    List<Feat> fs = f.extractLexicoSyntacticFeats(fed);
    for (Feat feat : fs) {
      w.write(' ');
      w.write(feat.getName());
      assert feat.getWeight() == 1d;
    }
    w.newLine();
    int ny = 0;
    for (String verb : verbs) {
      out.add(verb);
      String cverb = clean(verb);
      boolean y = f.verb().equals(verb);
      if (y) ny++;
      w.write(cverb);
      w.write(':');
      w.write(y ? '0' : '1');
      w.write(" | " + cverb);
      w.newLine();
    }
    assert ny > 0;
    w.newLine();
    return out;
  }
  
  /**
   * Reads a fact file, builds a summary from the sentences in col1, using the Occ_{ij} predictions
   * from a trained model {@link BatchVwTrain}, and then does a slot-concept based summary.
   * 
   * Uses the soft concept model described in:
   * thesis-outline/entity-summarization/entsum.tex
   */
  static class Summarize {
    private BatchVwTrain model;
    private FeatExData fed;
    private File workingDir;
    
    public Summarize(File workingDir, BatchVwTrain model, FeatExData fed) {
      this.workingDir = workingDir;
      this.model = model;
      this.fed = fed;
    }
    
    /**
     * @param inputFacts should have one line per *possible* fact (i.e. there may be multiple lines with the same subj and obj but different verbs/relations).
     */
    public List<DistSupFact> summarize(List<DistSupFact> input, int wordBudget) throws Exception {
      
      File dbgCache = new File("/tmp/slots-summ-debug-cache.jser");
      List<List<Feat>> pred;
      
      if (dbgCache.isFile()) {
        Log.info("loading from " + dbgCache.getPath());
        pred = (List) FileUtil.deserialize(dbgCache);
      } else {
        // 1) read in sentences and retrieve parses
        // 2) extract features for (sentence, subj, obj), write to file
        //    (this should have one row for every (verb, features) pair)
        File features = new File(workingDir, "features.vw");
        assert !features.isFile();
        List<List<String>> Y = new ArrayList<>();
        try (BufferedWriter w = FileUtil.getWriter(features)) {
          for (DistSupFact f : input) {
            List<String> Ys = writeCsoaaLdfFact(w, f, fed, model.subjObjTypes2verbs);
            if (Ys != null)
              Y.add(Ys);
          }
        }
        // 3) batch call to VW to get p(Occ_{ij}) scores
        File scores = new File(workingDir, "scores.txt");
        String[] command = new String[] {
            "vw",
            "-t",
            "-i", model.getModelFile().getPath(),
            "-d", features.getPath(),
            "-r", scores.getPath(),
        };
        Log.info("running: " + Arrays.toString(command));
        ProcessBuilder pb = new ProcessBuilder(command);
        Process p = pb.start();
        InputStreamGobbler stdout = new InputStreamGobbler(p.getInputStream());
        InputStreamGobbler stderr = new InputStreamGobbler(p.getErrorStream());
        stdout.start();
        stderr.start();
        int r = p.waitFor();
        if (r != 0)
          throw new RuntimeException("r=" + r);
        // 4) Build MIQP based on soft concept model
        List<DoubleArrayList> Yscores = readCsoaaLdfPredictions(scores);
        pred = zipAndSort(Y, Yscores);
        // Save for later
        Log.info("saving to " + dbgCache.getPath());
        FileUtil.serialize(pred, dbgCache);
      }

      // 5) Call gurobi, read out results, build summary
      for (int i = 0; i < pred.size(); i++) {
        DistSupFact f = input.get(i);
        List<Feat> ys = pred.get(i);
        System.out.println(f + "\t" + ys);
      }

      throw new RuntimeException("implement me");
    }
    
    public List<List<Feat>> zipAndSort(List<List<String>> Ys, List<DoubleArrayList> Yscores) {
      int n = Ys.size();
      if (n != Yscores.size())
        throw new IllegalArgumentException();
      List<List<Feat>> out = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        int d = Ys.get(i).size();
        if (d != Yscores.get(i).size())
          throw new IllegalArgumentException();
        List<Feat> fs = new ArrayList<>(d);
        for (int j = 0; j < d; j++)
          fs.add(new Feat(Ys.get(i).get(j), Yscores.get(i).get(j)));
        Collections.sort(fs, Feat.BY_SCORE_ASC);
        out.add(fs);
      }
      return out;
    }
    
    // TODO write pruning methods for List<List<Feat>>
    
    /**
     * @param predictions is the vw predictions for a multi-line/class predictions.
     * Each line is a score, not a cost (TODO check this!)
     * 
     * @return a list where each value is a list of COSTS for a particular multi-line/class instance.
     */
    public static List<DoubleArrayList> readCsoaaLdfPredictions(File predictions) throws IOException {
      List<DoubleArrayList> all = new ArrayList<>();
      DoubleArrayList cur = new DoubleArrayList();
      try (BufferedReader r = FileUtil.getReader(predictions)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          if (line.isEmpty()) {
            all.add(cur);
            cur = new DoubleArrayList();
          } else {
            String[] ar = line.split(":");
            assert ar.length == 2;
            long hy = Long.parseLong(ar[0]);
            double cost = Double.parseDouble(ar[1]);
            cur.add(cost);
          }
        }
      }
      return all;
    }
  }
  
  public static void testFed(FeatExData fed) {
    String e = "http://dbpedia.org/resource/The_Pogues";
    List<String> st = fed.getDbpediaSupertypes(e);
    Log.info("types for " + e + "\t" + st);
    if (st.contains("http://dbpedia.org/resource/SFOR") || !st.contains("http://schema.org/MusicGroup"))
      throw new RuntimeException("fixme");
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    File p = new File("/home/travis/code/data/clueweb09-freebase-annotation/gen-for-entsum");
    File fedFile = config.getExistingFile("fedFile", new File(p, "feature-extracted/fed.jser"));
    Log.info("reading from fedFile=" + fedFile.getPath());
    FeatExData fed = (FeatExData) FileUtil.deserialize(fedFile);
    
//    File jf = config.getExistingFile("join", new File(p, "dbpedia-distsup-rare4/join.jser"));
//    Join j = (Join) FileUtil.deserialize(jf);

    testFed(fed);

    File distSupFactJserStream = config.getExistingFile("distSupFactJserStream", new File(p, "dbpedia-distsup-rare4/facts.jser"));
    List<DistSupFact> facts = DistSupFact.readFacts(distSupFactJserStream);
    BatchVwTrain bt = new BatchVwTrain(fed, facts);
    File outputTrainFile = config.getFile("outputTrainFile", new File("/tmp/a.vw"));
    bt.buildTrainingFile(facts, outputTrainFile);
    File outputModelFile = config.getFile("outputModelFile", new File("/tmp/a-model.vw"));
    bt.trainModel(outputTrainFile, outputModelFile);
    
    File workingDir = config.getOrMakeDir("workingDir", new File("/tmp/summ-working-dir"));
    Summarize s = new Summarize(workingDir, bt, fed);
    int wordBudget = 100;
    List<DistSupFact> summ = s.summarize(facts, wordBudget);
  }
}
