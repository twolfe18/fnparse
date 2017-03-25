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
import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.entsum.EffSent.Mention;
import edu.jhu.hlt.entsum.GillickFavre09Summarization.ConceptMention;
import edu.jhu.hlt.entsum.GillickFavre09Summarization.SoftConceptMention;
import edu.jhu.hlt.entsum.GillickFavre09Summarization.SoftSolution;
import edu.jhu.hlt.entsum.ObservedArgTypes.Verb;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.InputStreamGobbler;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.prim.list.DoubleArrayList;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.util.Alphabet;
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
public class SlotsAsConcepts {
  
  /**
   * Input:
   *   tokenized-sentences/$ENTITY/facts-rel*-types.txt
   *   tokenized-sentences/$ENTITY/mid2dbp-rel*.txt
   *   tokenized-sentences/$ENTITY/mentionLocs.txt
   *   tokenized-sentences/$ENTITY/parse.conll
   *   distsup-infobox/observed-arg-types.jser
   *
   * And produces a VW training file where the label is an infobox fact's relation
   * and the features are lexico-syntactic features derived from the sentence/parse.
   * 
   * Output:
   *   distsup-infobox.locations.txt := <sentenceIdx> <subjMentionIdx> <objMentionIdx>, location of corresponding csoaa_ldf instance
   *   distsup-infobox.csoaa_ldf.yhat := VW-format, just a list of instances
   */
  public static class StreamingDistSupFeatEx {
    public static final String FACT_LOC_FILE_NAME = "distsup-infobox.locations.txt";
    public static final String FACT_FEAT_FILE_NAME = "distsup-infobox.csoaa_ldf.yhat";

    private ObservedArgTypes verbTypes;
    private File entityDir;
    private String entityMid;
    private MultiMap<String, String> mid2dbp;           // tokenized-sentences/$ENTITY/mid2dbp-rel*.txt
    private EntityTypes dbp2type;
    private MultiMap<String, DbpediaTtl> dbp2facts;
    private MultiAlphabet parseAlph;
    
    public StreamingDistSupFeatEx(ObservedArgTypes verbTypes, File entityDir, String entityMid) throws IOException {
      Log.info("mid=" + entityMid + " dir=" + entityDir.getPath());
      this.verbTypes = verbTypes;
      this.entityDir = entityDir;
      this.entityMid = entityMid;
      // Read in data from files
      mid2dbp = new MultiMap<>();
      addMid2Dbp(new File(entityDir, "mid2dbp-rel0.txt"));
      addMid2Dbp(new File(entityDir, "mid2dbp-rel1.txt"));
      dbp2type = new EntityTypes(entityDir);
      dbp2facts = new MultiMap<>();
      addDbp2Fact(new File(entityDir, "facts-rel0-types.txt"));
      addDbp2Fact(new File(entityDir, "facts-rel1-types.txt"));
      parseAlph = new MultiAlphabet();
    }

    private void addDbp2Fact(File f) throws IOException {
      if (!f.isFile()) {
        Log.info("WARNING: not a file: " + f.getPath());
        return;
      }
      Log.info("reading from " + f.getPath());
      boolean keepLines = false;
      try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(f, keepLines)) {
        while (iter.hasNext()) {
          DbpediaTtl x = iter.next();
          if (x.subject().type == Type.DBPEDIA_ENTITY)
            dbp2facts.add(x.subject().getValue(), x);
          if (x.object().type == Type.DBPEDIA_ENTITY)
            dbp2facts.add(x.object().getValue(), x);
        }
      }
    }

    private void addMid2Dbp(File f) throws IOException {
      if (!f.isFile()) {
        Log.info("WARNING: not a file: " + f.getPath());
        return;
      }
      Log.info("reading from " + f.getPath());
      boolean keepLines = false;
      try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(f, keepLines)) {
        while (iter.hasNext()) {
          DbpediaTtl x = iter.next();
          assert x.subject().type == Type.DBPEDIA_ENTITY;
          String dbp = x.subject().getValue();
          String mid = DbpediaTtl.extractMidFromTtl(x.object().getValue());
          mid2dbp.add(mid, dbp);
        }
      }
    }
    
    // TODO Memoize if slow
    List<String> entityTypesForMid(String mid) {
      List<String> at = new ArrayList<>();
      for (String dbp : mid2dbp.get(mid))
        at.addAll(dbp2type.typesForDbp(dbp));
      return at;
    }
    
    List<String> featurize(EffSent sent, int subjMention, int objMention) {
      Mention subj = sent.mention(subjMention);
      Mention obj = sent.mention(objMention);
      List<String> subjTypes = entityTypesForMid(subj.getFullMid());
      List<String> objTypes = entityTypesForMid(obj.getFullMid());
      return DistSupFact.extractLexicoSyntacticFeats(
          subj.head, subj.span(), subjTypes,
          obj.head, obj.span(), objTypes,
          sent.parse(), parseAlph);
    }
    
    // TODO Investigate whether distsup is sensitive to the implementation of this method
    List<String> plausibleVerbsFor(EffSent sent, int subjMention, int objMention) {
      String subjMid = sent.mention(subjMention).getFullMid();
      String objMid = sent.mention(objMention).getFullMid();
      List<String> subjTypes = entityTypesForMid(subjMid);
      List<String> objTypes = entityTypesForMid(objMid);
      List<Verb> verbs = verbTypes.plausibleVerbs(subjTypes, objTypes);
      List<String> vs = new ArrayList<>();
      for (Verb v : verbs)
        if (v.svoCount > 1)
          vs.add(v.verb);
      return vs;
    }
    
    public void writeFeatures() throws IOException {
      File parses = new File(entityDir, "parses.conll");
      File mentions = new File(entityDir, "mentionLocs.txt");
      File outLocs = new File(entityDir, FACT_LOC_FILE_NAME);
      File outFeats = new File(entityDir, FACT_FEAT_FILE_NAME);
      MultiAlphabet parseAlph = new MultiAlphabet();
      try (EffSent.Iter iter = new EffSent.Iter(parses, mentions, parseAlph);
          BufferedWriter wLoc = FileUtil.getWriter(outLocs);
          BufferedWriter wFeat = FileUtil.getWriter(outFeats)) {
        int sentIdx = 0;
        while (iter.hasNext()) {
          EffSent sent = iter.next();
          // See what facts we can match up against this sentence
          List<Fact> fs = findFacts(sentIdx++, sent);
          for (Fact f : fs) {
            // Output location of this fact
            wLoc.write(f.tsv());
            wLoc.newLine();
            
            // Output lexico-syntactic features (VW-format)
            List<String> fx = featurize(sent, f.subjMention, f.objMention);
            wFeat.write("shared |");
            for (String feat : fx) {
              wFeat.write(' ');
              wFeat.write(feat);
            }
            wFeat.newLine();
            int yes = 0, all = 0;
            List<String> ys = plausibleVerbsFor(sent, f.subjMention, f.objMention);
            for (String y : ys) {
              if (f.verb.equals(y)) {
                wFeat.write(y + ":0 | " + y);
                yes++;
              } else {
                wFeat.write(y + ":1 | " + y);
              }
              wFeat.newLine();
              all++;
            }
            wFeat.newLine();    // empty line for end of instance
            assert yes > 0 && all > 1;
          }
        }
      }
    }
    
    public static class Fact {
      int sentIdx;
      int subjMention;
      int objMention;
      String verb;

      public Fact(int sentIdx, int subjMention, int objMention, String verb) {
        this.sentIdx = sentIdx;
        this.subjMention = subjMention;
        this.objMention = objMention;
        this.verb = verb;
      }

      public String tsv() {
        return sentIdx + "\t" + subjMention + "\t" + objMention + "\t" + verb;
      }
    }
    
    /**
     * Look through the given {@link EffSent} for facts in facts-rel0-types.txt.
     * @param sentIdx is just for handing off to a returned {@link Fact}, has nothing to do with the impl of this method.
     */
    public List<Fact> findFacts(int sentIdx, EffSent sent) {
      List<Fact> fs = new ArrayList<>();
      int n = sent.numMentions();
      for (int i = 0; i < n; i++) {
        Mention mi = sent.mention(i);
        if (!entityMid.equals(mi.getFullMid()))
          continue;
        for (int j = 0; j < n; j++) {
          if (i == j) continue;
          Mention mj = sent.mention(j);
          // All facts match mi's mid, enumerate mj's dbp and check dbp2fact
          for (String dbpJ : mid2dbp.get(mj.getFullMid())) {
            for (DbpediaTtl f : dbp2facts.get(dbpJ)) {
              // f matches mi by construction (rel0) and mj by proof, output it
              boolean mjIsSubj = dbpJ.equals(f.subject().getValue());
              String v = f.verb().getValue();
              if (mjIsSubj)
                fs.add(new Fact(sentIdx, j, i, v));
              else
                fs.add(new Fact(sentIdx, i, j, v));
            }
          }
        }
      }
      return fs;
    }
    
    // Once you've created this for all $ENTITYs, to __train__ a VW relation model,
    // mkdir distsup-infobox/
    // cat tokenized-sentences/train/*/distsup-infobox.csoaa_ldf.vw | shuf >distsup-infobox/train.csoaa_ldf.vw
    // vw --csoaa_ldf m ... <distsup-infobox/train.csoaa_ldf.vw >distsup-infobox/mode.vw

    // To __predict__ with this model, maybe start a vw server like ikbp/.../VwWrapper.java?
    // Alternative is to batch everything up.
    // Or don't batch, just invoke model once per dev/test entity.
    
    public static void computeFeaturesForAllEntities(ExperimentProperties config) throws IOException {
      File obsArgTypes = config.getExistingFile("obsArgTypes");
      ObservedArgTypes oat = (ObservedArgTypes) FileUtil.deserialize(obsArgTypes);
      File entityDirParent = config.getExistingDir("entityDirParent");
      String entityDirGlob = config.getString("entityDirGlob");
      List<File> entityDirs = FileUtil.find(entityDirParent, entityDirGlob);
      Log.info("found " + entityDirs.size() + " entity directories to compute features for");
      for (File ed : entityDirs) {
        String mid = ed.getName().replaceAll("m.", "/m/");
        StreamingDistSupFeatEx f = new StreamingDistSupFeatEx(oat, ed, mid);
        f.writeFeatures();
      }
    }
  }
  

  /*
   * TODO have two models:
   * A) Given a sentence, what entity pairs should we even test for evoking a relation or not
   *    (this model could be rule/code-based rather than ML)
   * B) Given a mention pair, predict fact
   */
  
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
   *
   * @deprecated The new pipeline doesn't use this path
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
    
    static List<DistSupFact> prefilter(List<DistSupFact> input) {
      List<DistSupFact> keep = new ArrayList<>();
      Set<String> uniq = new HashSet<>();
      for (DistSupFact in : input) {
        int nl = in.sentence().numLinks();
        int nt = in.sentence().getTextTokenizedNumTokens();
        if (nt < 10 || nt < 2 * nl)
          continue;
        
        // check for exact duplicates
        String h = in.sentence().hashHex();
        if (!uniq.add(h))
          continue;

        keep.add(in);
        
        if (keep.size() == 5000)
          break;
      }
      Log.info("nIn=" + input.size() + " nOut=" + keep.size());
      return keep;
    }
    
    /**
     * @param inputFacts should have one line per *possible* fact (i.e. there may be multiple lines with the same subj and obj but different verbs/relations).
     */
    public List<DistSupFact> summarize(List<DistSupFact> input, int wordBudget) throws Exception {
      
      input = prefilter(input);
      
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
      int maxLabelsPerInstance = 10;
      List<List<Feat>> pred = zipAndSortAndFilter(Y, Yscores, maxLabelsPerInstance);

      // 5) Call gurobi, read out results, build summary
      Log.info("there are " + pred.size() + " concept predictions");
      Alphabet<String> concepts = new Alphabet<>();
      List<ConceptMention> occ = new ArrayList<>();
      IntArrayList sentenceLengths = new IntArrayList();
      int n = pred.size();
      for (int i = 0; i < n; i++) {
        DistSupFact f = input.get(i);
        sentenceLengths.add(f.sentence().getTextTokenizedNumTokens());
        for (Feat c : pred.get(i)) {
          String cons = f.subject() + "\t" + c.getName() + "\t" + f.object();
//          String cons = c.getName();
          int con = concepts.lookupIndex(cons);
          double cost = Math.max(0, c.getWeight());
          occ.add(new SoftConceptMention(con, i, cost));
          
          
          // Lets add concepts for the verb and the object
          // This will encourage further diversity
          // NOTE: This does not work now, given how I compute the concept utilities,
          // which is based on repetition. Coarse grain concepts are more likely to repeat.
          // I need to have a codebook cost to divide out, need to count over more data than we're trying to summarize.
//          occ.add(new SoftConceptMention(concepts.lookupIndex("verb/" + c.getName()), i, cost));
//          occ.add(new SoftConceptMention(concepts.lookupIndex("obj/" + f.object()), i, cost));
        }
      }
      
      double[] conceptUtilities = new double[concepts.size()];
      for (ConceptMention c : occ)
        conceptUtilities[c.i] += 1.0;

      GillickFavre09Summarization gf = new GillickFavre09Summarization(occ, sentenceLengths, conceptUtilities);

//      IntArrayList s = gf.solve(wordBudget);
      SoftSolution s = gf.solveSoft(wordBudget);
      Log.info(s);
      for (int i = 0; i < s.sentences.size(); i++) {
        int sent = s.sentences.get(i);
        System.out.println(input.get(sent));
      }
      for (int i = 0; i < s.sentences.size(); i++) {
        int sent = s.sentences.get(i);
        System.out.println(input.get(sent).sentence().getMarkup());
      }

      List<DistSupFact> out = new ArrayList<>();
      for (int i = 0; i < s.sentences.size(); i++) {
        int sent = s.sentences.get(i);
        out.add(input.get(sent));
      }
      return out;
    }

    public List<List<Feat>> zipAndSort(List<List<String>> Ys, List<DoubleArrayList> Yscores) {
      return zipAndSortAndFilter(Ys, Yscores, 0);
    }
    public List<List<Feat>> zipAndSortAndFilter(List<List<String>> Ys, List<DoubleArrayList> Yscores, int maxLabelsPerInstance) {
      int n = Ys.size();
      if (n != Yscores.size())
        throw new IllegalArgumentException();
      int prunedRows = 0, pruned = 0;
      List<List<Feat>> out = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        int d = Ys.get(i).size();
        if (d != Yscores.get(i).size())
          throw new IllegalArgumentException();
        List<Feat> fs = new ArrayList<>(d);
        for (int j = 0; j < d; j++)
          fs.add(new Feat(Ys.get(i).get(j), Yscores.get(i).get(j)));
        Collections.sort(fs, Feat.BY_SCORE_ASC);
        if (maxLabelsPerInstance > 0 && fs.size() > maxLabelsPerInstance) {
          prunedRows++;
          pruned += (fs.size() - maxLabelsPerInstance);
//          fs = fs.subList(0, maxLabelsPerInstance);   // NotSerializableException
          fs = trim(fs, maxLabelsPerInstance);
        }
        out.add(fs);
      }
      Log.info("prunedRows=" + prunedRows + " pruned=" + pruned);
      return out;
    }
    
    static <T> List<T> trim(List<T> in, int k) {
      List<T> out = new ArrayList<>();
      for (int i = 0; i < k; i++)
        out.add(in.get(i));
      return out;
    }
    
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
            @SuppressWarnings("unused")
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
  
  public static void prototypeTrainTestImpl(ExperimentProperties config) throws Exception {
    File p = new File("/home/travis/code/data/clueweb09-freebase-annotation/gen-for-entsum");
    File fedFile = config.getExistingFile("fedFile", new File(p, "feature-extracted/fed.jser"));
    Log.info("reading from fedFile=" + fedFile.getPath());
    FeatExData fed = (FeatExData) FileUtil.deserialize(fedFile);
    
    testFed(fed);

    // Train on all facts
    File distSupFactJserStream = config.getExistingFile("distSupFactJserStream", new File(p, "dbpedia-distsup-rare4/facts.jser"));
    List<DistSupFact> facts = DistSupFact.readFacts(distSupFactJserStream);
    BatchVwTrain bt = new BatchVwTrain(fed, facts);
//    File outputTrainFile = config.getFile("outputTrainFile", new File("/tmp/a.vw"));
//    bt.buildTrainingFile(facts, outputTrainFile);
    File outputModelFile = config.getFile("outputModelFile", new File("/tmp/a-model.vw"));
//    bt.trainModel(outputTrainFile, outputModelFile);
    bt.model = outputModelFile;
    
    // Summarize with only facts from a particular entity
    List<DistSupFact> factsRelevant = new ArrayList<>();
    for (DistSupFact f : facts) {
      if ("/m/0gly1".equals(f.subjectMid()) || "/m/0gly1".equals(f.objectMid()))
        factsRelevant.add(f);
    }
    Log.info("nFactsAll=" + facts.size() + " nFactsRelevant=" + factsRelevant.size());
    File workingDir = config.getOrMakeDir("workingDir", new File("/tmp/summ-working-dir"));
    Summarize s = new Summarize(workingDir, bt, fed);
    int wordBudget = 100;
    s.summarize(factsRelevant, wordBudget);
    Log.info("done");
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    
    String m = config.getString("mode");
    switch (m) {
    case "extractFeatures":   // makes tokenized-sentences/$ENTITY/distsup-infobox.csoaa_ldf.yx
      StreamingDistSupFeatEx.computeFeaturesForAllEntities(config);
      break;
    case "extractFeaturesForOneEntity":
      File obsArgTypes = config.getExistingFile("obsArgTypes");
      ObservedArgTypes oat = (ObservedArgTypes) FileUtil.deserialize(obsArgTypes);
      File output = config.getFile("output");
      File entityDir = output.getParentFile();
      String mid = entityDir.getName().replaceAll("m.", "/m/");
      StreamingDistSupFeatEx f = new StreamingDistSupFeatEx(oat, entityDir, mid);
      f.writeFeatures();
      break;
      // Other actions are carried out by Makefile and scripts in data/facc1-entsum/
//    case "train":             // makes distsup-infobox/train.csoaa_ldf.yx
//                              //   and distsup-infobox/model.vw
//      break;
//    case "predict":           // makes tokenized-sentences/$ENTITY/distsup-infobox.csoaa_ldf.yhat
//      break;
//    case "summarize":         // makes summaries/$ENTITY/distsup-infobox/summary-100.txt
//      break;
    }
    
    Log.info("done");
  }
}
