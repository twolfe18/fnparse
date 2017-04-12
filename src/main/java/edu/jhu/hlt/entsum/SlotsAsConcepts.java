package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.typesafe.config.impl.Parseable;

import edu.jhu.hlt.entsum.CluewebLinkedSentence.Link;
import edu.jhu.hlt.entsum.DbpediaDistSup.FeatExData;
import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.entsum.EffSent.Mention;
import edu.jhu.hlt.entsum.GillickFavre09Summarization.ConceptMention;
import edu.jhu.hlt.entsum.GillickFavre09Summarization.SoftConceptMention;
import edu.jhu.hlt.entsum.GillickFavre09Summarization.SoftSolution;
import edu.jhu.hlt.entsum.ObservedArgTypes.Verb;
import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingDistSupFeatEx.Fact;
import edu.jhu.hlt.entsum.SlotsAsConcepts.StreamingSummarizer.Options;
import edu.jhu.hlt.entsum.VwLine.Namespace;
import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.ikbp.tac.IndexCommunications.Feat;
import edu.jhu.hlt.tutils.Beam;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.InputStreamGobbler;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.Span;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.tutils.hash.Hash;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.prim.list.DoubleArrayList;
import edu.jhu.prim.list.IntArrayList;
import edu.jhu.prim.set.IntHashSet;
import edu.jhu.prim.tuple.Pair;
import edu.jhu.prim.vector.IntDoubleHashVector;
import edu.jhu.util.Alphabet;
import edu.jhu.util.MultiMap;
import edu.jhu.util.UniqList;
import gurobi.GRBException;

/**
 * NEW:
 * Does all tasks related to summarization via "slots as concepts",
 * including feature extraction {@link StreamingDistSupFeatEx} and
 * summarization {@link StreamingSummarizer}. Intermediate tasks
 * related to learning/prediction are handled by vowpal wabbit and
 * you can find the appropriate commands in:
 *   $FNPARSE/data/facc1-entsum/Makefile
 * 
 * 
 * OLD:
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
  // These are used on TRAIN where you have infobox facts and entity types for distant supervision training
  public static final String INFOBOX_TRAIN_LOC_FILENAME =  "infobox-distsup.locations.txt";
  public static final String INFOBOX_TRAIN_FEAT_FILENAME = "infobox-distsup.csoaa_ldf.yx";
  // These are used on DEV/TEST where you don't assume you know any infobox facts but you do have entity types
  public static final String INFOBOX_PRED_LOC_FILENAME =  "infobox-pred.locations.txt";
  public static final String INFOBOX_PRED_FEAT_FILENAME = "infobox-pred.csoaa_ldf.x";
//  public static final String INFOBOX_PRED_SCORE_FILENAME = "infobox-pred.csoaa_ldf.model-2ndOrder-m-s1.yhat";
  public static final String INFOBOX_PRED_SCORE_FILENAME_GLOB = "glob:**/infobox-pred.csoaa_ldf.*-m-*.yhat";
  
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

    private ObservedArgTypes.PlausibleMemoizer verbTypes;   // not used for binary features
    private File entityDir;
    private String entityMid;
    private MultiMap<String, String> mid2dbp;           // tokenized-sentences/$ENTITY/mid2dbp-rel*.txt
    private EntityTypes dbp2type;
    private MultiMap<String, DbpediaTtl> dbp2facts;
    private MultiAlphabet parseAlph;
    private boolean train;
    
    public StreamingDistSupFeatEx(ObservedArgTypes verbTypes, File entityDir, String entityMid, boolean train) throws IOException {
      Log.info("mid=" + entityMid + " dir=" + entityDir.getPath() + " train=" + train);
      this.train = train;
      this.parseAlph = new MultiAlphabet();
      if (verbTypes != null)
        this.verbTypes = new ObservedArgTypes.PlausibleMemoizerA(verbTypes);
      this.entityDir = entityDir;
      this.entityMid = entityMid;

      // Read in data from files
      mid2dbp = new MultiMap<>();
      addMid2Dbp(new File(entityDir, "mid2dbp-rel0.txt"));
      addMid2Dbp(new File(entityDir, "mid2dbp-rel1.txt"));
      dbp2type = new EntityTypes(entityDir);

      if (train) {
        dbp2facts = new MultiMap<>();
        addDbp2Fact(new File(entityDir, "facts-rel0-types.txt"));
//      addDbp2Fact(new File(entityDir, "facts-rel1-types.txt"));
      }
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
            dbp2facts.addIfNotPresent(x.subject().getValue(), x);
          if (x.object().type == Type.DBPEDIA_ENTITY)
            dbp2facts.addIfNotPresent(x.object().getValue(), x);
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
          mid2dbp.addIfNotPresent(mid, dbp);
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
    
    List<String> entityIds(EffSent sent, int mention) {
      List<String> a = new ArrayList<>();
      Mention m = sent.mention(mention);
      a.add(m.getAbbrMid());
      for (String dbp : mid2dbp.get(m.getFullMid()))
        a.add(dbp);
      return a;
    }
    
    List<String> featurize(EffSent sent, int subjMention, int objMention, ComputeIdf df, List<String> nonLexSyn) {
      Mention subj = sent.mention(subjMention);
      Mention obj = sent.mention(objMention);
      List<String> subjTypes = entityTypesForMid(subj.getFullMid());
      List<String> objTypes = entityTypesForMid(obj.getFullMid());
      List<String> subjIds = entityIds(sent, subjMention);
      List<String> objIds = entityIds(sent, objMention);
      int[] t2e = sent.buildToken2EntityMap();
      return DistSupFact.extractLexicoSyntacticFeats(
          subj.head, subj.span(), subjTypes, subjIds,
          obj.head, obj.span(), objTypes, objIds,
          t2e,
          sent.parse(), parseAlph, df,
          nonLexSyn);
    }
    
    // TODO Investigate whether distsup is sensitive to the implementation of this method
    List<String> plausibleVerbsFor(EffSent sent, int subjMention, int objMention, int minPlausible, Counts<String> ecDbg) {
      String subjMid = sent.mention(subjMention).getFullMid();
      String objMid = sent.mention(objMention).getFullMid();
      List<String> subjTypes = entityTypesForMid(subjMid);
      List<String> objTypes = entityTypesForMid(objMid);
      List<Verb> verbs = verbTypes.plausibleVerbs(subjTypes, objTypes);
//      List<String> vs = new ArrayList<>();
      UniqList<String> vs = new UniqList<>();
      
      // First attempt: require (subjType, verb, objType) to have appeared
      ecDbg.increment("plausible");
      for (Verb v : verbs)
        if (v.svoCount > 0)
          vs.add(v.verb);

      // Second attempt: ???
      if (vs.size() < minPlausible) {
        ecDbg.increment("plausible/backoff1a");
        for (Verb v : verbs)
          if (v.svCount > 1 && v.voCount > 1)
            vs.add(v.verb);
      }
      if (vs.size() < minPlausible) {
        ecDbg.increment("plausible/backoff1b");
        for (Verb v : verbs)
          if (v.svCount > 0 && v.voCount > 0)
            vs.add(v.verb);
      }
      
      // Third attempt: backoff to any relation matching (subjType, verb, *) or (*, verb, objType)
      if (vs.size() < minPlausible) {
        ecDbg.increment("plausible/backoff2");
        for (Verb v : verbs)
          if (v.svCount > 0 || v.voCount > 0)
            vs.add(v.verb);
      }

      return vs.getList();
    }
    
    private static List<Fact> negSample(List<Fact> pos, List<Fact> neg, int negsTake, Random rand) {
      // Prune out any locations which are covered by pos examples
      Set<Fact> p = new HashSet<>();
      for (Fact f : pos)
        p.add(new Fact(f.sentIdx, f.subjMention, f.objMention, null));
      
      ReservoirSample<Fact> negKeep = new ReservoirSample<>(negsTake, rand);
      for (Fact f : neg)
        if (p.add(f))   // a.k.a "not a pos"
          negKeep.add(f);
      
      List<Fact> negOut = new ArrayList<>();
      for (Fact f : negKeep)
        negOut.add(f);
      return negOut;
    }
    
    public static List<String> nonLexSynFeatures(String mid, int sentIdx, Fact f) {
      String midSent = mid + "/s=" + sentIdx;
      String midSentMent = midSent + "/m=" + f.subjMention + "-" + f.objMention;
      return Arrays.asList(mid, midSent, midSentMent);
    }

    /**
     * Writes vw-formatted instances to
     *    $ENTITY/infobox-binary/pos-$RELATION.vw
     *    $ENTITY/infobox-binary/neg.vw
     *
     * @param negsPerSentence is only applicable for train, says how many neg to sample per sentence
     */
    public void writeBinaryFeatures(ComputeIdf df, int negsPerSentence) throws IOException {
      boolean debug = false;
      File p = new File(entityDir, "infobox-binary");
      if (!p.isDirectory())
        p.mkdirs();
      Log.info("negsPerSentence=" + negsPerSentence + " train=" + train + " putting instances in " + p.getPath());
      Map<String, BufferedWriter> v2w = new HashMap<>();
      
      String mid = getMidFromEntityDir(entityDir);
      Random rand = new Random(9001);
      TimeMarker tm = new TimeMarker();
      Counts<String> ec = new Counts<>();
      try (EffSent.DedupMaW3Iter diter = joinIter(df)) {
        while (diter.hasNext()) {
          Pair<EffSent, Integer> sentI = diter.next();
          EffSent sent = sentI.get1();
          int sentIdx = sentI.get2();

          if (!train) {
            List<Fact> x = findFactTest(sentIdx, sent);
            for (Fact f : x) {
              List<String> nonLexSynFx = nonLexSynFeatures(mid, sentIdx, f);
              ec.increment("unlab");
              File ff = new File(p, "unlab.x");
              BufferedWriter w = DistSupSetup.getOrOpen(ff.getPath(), v2w, ff);
              List<String> fx = featurize(sent, f.subjMention, f.objMention, df, nonLexSynFx);
              writeVw(w, "1", fx);
              File ff2 = new File(p, "unlab.location");
              BufferedWriter w2 = DistSupSetup.getOrOpen(ff2.getPath(), v2w, ff2);
              w2.write(f.tsv());
              w2.newLine();
            }
          } else {
//            List<Fact> neg = new ArrayList<>();
//            List<Fact> pos = findFacts(sentIdx, sent, neg, debug);
            List<Fact> neg = findFactTest(sentIdx, sent);
            List<Fact> pos = findFacts(sentIdx, sent, null, debug);
            neg = negSample(pos, neg, negsPerSentence, rand);
            for (Fact f : pos) {
              String yc = fileSystemSafe(vwSafety(clean(f.verb)));
              ec.increment("pos");
              ec.increment("pos/" + yc);
              File ff = new File(p, "pos-" + yc + ".vw");
              BufferedWriter w = DistSupSetup.getOrOpen(ff.getPath(), v2w, ff);
              List<String> fx = featurize(sent, f.subjMention, f.objMention, df, nonLexSynFeatures(mid, sentIdx, f));
              writeVw(w, "1", fx);
              File ff2 = new File(p, "pos-" + yc + ".location");
              BufferedWriter w2 = DistSupSetup.getOrOpen(ff2.getPath(), v2w, ff2);
              w2.write(f.tsv());
              w2.newLine();
            }
            for (Fact f : neg) {
              ec.increment("neg");
              File ff = new File(p, "neg.vw");
              BufferedWriter w = DistSupSetup.getOrOpen(ff.getPath(), v2w, ff);
              List<String> fx = featurize(sent, f.subjMention, f.objMention, df, nonLexSynFeatures(mid, sentIdx, f));
              writeVw(w, "0", fx);
            }
          }
          
          if (tm.enoughTimePassed(2))
            Log.info(ec);
        }
      }
      
      System.out.println(ec);
      Log.info("closing " + v2w.size() + " files");
      for (BufferedWriter w : v2w.values())
        w.close();
    }
    
    private static void writeVw(BufferedWriter w, String label, List<String> fx) throws IOException {
      MultiMap<String, Feat> fxg = Feat.groupByNamespace(Feat.promote(1, fx), '/');
      w.write(label);
      for (String ns : fxg.keySet()) {
        assert !"y".equalsIgnoreCase(ns);
        assert ns.equals(vwSafety(ns));
        w.write(" |" + ns);
        for (Feat fi : fxg.get(ns))
          w.write(" " + vwSafety(fi.getName()));
      }
      w.newLine();
    }
    
    public EffSent.DedupMaW3Iter joinIter(ComputeIdf df) throws IOException {
      File parses = new File(entityDir, "parse.conll");
      File mentions = new File(entityDir, "mentionLocs.txt");
      EffSent.Iter iter = new EffSent.Iter(parses, mentions, parseAlph);
      int numWordsInKey = 2;
      return new EffSent.DedupMaW3Iter(iter, df, numWordsInKey);
    }

    public void writeCsoaaLdfFeatures(ComputeIdf df) throws IOException {
      boolean debug = false;
      
      Random rand = new Random(9001);
      
      File outLocs, outFeats;
      if (train) {
        outLocs = new File(entityDir, INFOBOX_TRAIN_LOC_FILENAME);
        outFeats = new File(entityDir, INFOBOX_TRAIN_FEAT_FILENAME);
      } else {
        outLocs = new File(entityDir, INFOBOX_PRED_LOC_FILENAME);
        outFeats = new File(entityDir, INFOBOX_PRED_FEAT_FILENAME);
      }
      
      Log.info("nVerbObs=" + verbTypes.getWrapped().numObservations()
          + " nVerbs=" + verbTypes.getWrapped().getVerbs().size());
      Log.info("outLocs=" + outLocs.getPath());
      Log.info("outFeats=" + outFeats.getPath());
      
      String mid = getMidFromEntityDir(entityDir);
      TimeMarker tm = new TimeMarker();
      Counts<String> ec = new Counts<>();
      try (EffSent.DedupMaW3Iter diter = joinIter(df);
          BufferedWriter wLoc = FileUtil.getWriter(outLocs);
          BufferedWriter wFeat = FileUtil.getWriter(outFeats)) {
        while (diter.hasNext()) {
          Pair<EffSent, Integer> sentI = diter.next();
          EffSent sent = sentI.get1();
          int sentIdx = sentI.get2();
          ec.increment("sentence");
          
          // See what facts in the KB we can match up against this sentence
          List<Fact> fs = train
              ? findFacts(sentIdx, sent, null, debug)
              : findFactTest(sentIdx, sent);

          for (Fact f : fs) {

            // Given a positive fact, we need to choose negative verbs/relations to score against
            int minPlausible = 2;
            assert minPlausible > 0;
            List<String> ys = plausibleVerbsFor(sent, f.subjMention, f.objMention, minPlausible, ec);
            
            // If our heuristic doesn't retrieve the gold fact, then we add it anyway
            if (train && !ys.contains(f.verb)) {
              ys.add(f.verb);
              ec.increment("fact/addGold");
            }
            if (ys.size() < minPlausible) {
              ec.increment("fact/skip");
              continue;
            }

            // Output location of this fact
            wLoc.write(f.tsv());
            wLoc.newLine();
            
            // Output lexico-syntactic features (VW-format)
            List<String> fx = featurize(sent, f.subjMention, f.objMention, df, nonLexSynFeatures(mid, sentIdx, f));
            writeVw(wFeat, "shared", fx);
            
            int yes = 0, all = 0;
            
            // Alternative: print out all labels
            // TODO: sample only k non-plausible
            Alphabet<String> va = this.verbTypes.getWrapped().getVerbs();
            Set<String> plausibleS = new HashSet<>(ys);
            Set<String> implausibleS = null;
            if (train) {
              int nPlausible = 16;
              int nImplausible = 15;  // 32 total
              ReservoirSample<String> implausibleR = new ReservoirSample<>(nImplausible, rand);
              for (int yi = 0; yi < va.size(); yi++) {
                String y = va.lookupObject(yi);
                if (f.verb.equals(y))
                  continue;
                if (plausibleS.contains(y))
                  implausibleR.add(y);
              }
              implausibleS = new HashSet<>();
              for (String s : implausibleR)
                implausibleS.add(s);
              ReservoirSample<String> plausibleR = new ReservoirSample<>(nPlausible, rand);
              for (String y : ys)
                plausibleR.add(y);
              plausibleS.clear();
              for (String y : plausibleR)
                plausibleS.add(y);
            }
            for (int yi = 0; yi < va.size(); yi++) {
              String y = va.lookupObject(yi);
              String yc = vwSafety(clean(y));
              String cost;
              if (!train) {
                ec.increment("fact/unlab");
                cost = "";
              } else if (f.verb.equals(y)) {
                ec.increment("fact/yes");
                cost = ":0";
                yes++;
              } else if (plausibleS.contains(y)) {
                ec.increment("fact/plausible");
                cost = ":1";
              } else if (implausibleS.contains(y)) {
                ec.increment("fact/implausible");
                cost = ":2";
              } else {
                continue;
              }
              wFeat.write(yi + cost + " | " + yc);
              wFeat.newLine();
              all++;
            }
            
            // OLD WAY: write out all plausible verbs based on subj/obj type
//            for (String y : ys) {
//              ec.increment("fact/label");
//              int yi = va.lookupIndex(y) + 1;
////              String yc = clean(y);
//              String yc = vwSafety(clean(y));
//              String cost = "";
//              if (train) {
//                if (f.verb.equals(y)) {
//                  cost = ":0";
//                  yes++;
//                } else {
//                  cost = ":1";
//                }
//              }
//              wFeat.write(yi + cost + " | " + yc);
//              wFeat.newLine();
//              all++;
//            }

            wFeat.newLine();    // empty line for end of instance
            assert (!train || yes > 0) && all >= minPlausible : "yes=" + yes + " all=" + all;

            // DEBUG: show what we're printing out
            if (debug) {
              MultiMap<String, Feat> fxg = Feat.groupByNamespace(Feat.promote(1, fx), '/');
              sent.showChunkedStyle(parseAlph);
              System.out.println("subj: " + sent.mention(f.subjMention).show(sent.parse(), parseAlph));
              System.out.println("obj: " + sent.mention(f.objMention).show(sent.parse(), parseAlph));
              for (String ns : fxg.keySet()) {
                System.out.println("\t" + ns + ":");
                for (Feat fxi : fxg.get(ns))
                  System.out.println("\t\t" + fxi.getName());
              }
              System.out.println("y=" + f.verb);
              for (String y : ys)
                System.out.println("\tyhat=" + clean(y));
              System.out.println();
            }
          }
          
          if (tm.enoughTimePassed(3)) {
            Log.info(ec + "\t" + Describe.memoryUsage());
          }
        }
      }
      Log.info("done, " + ec + "\t" + Describe.memoryUsage());
    }
    
    public static class Fact implements Serializable {
      private static final long serialVersionUID = 1707370011840909975L;

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
      
      public static List<Fact> readTsv(File f) throws IOException {
        List<Fact> fs = new ArrayList<>();
        try (BufferedReader r = FileUtil.getReader(f)) {
          for (String line = r.readLine(); line != null; line = r.readLine())
            fs.add(fromTsv(line));
        }
        return fs;
      }
      
      public static Fact fromTsv(String line) {
        String[] ar = line.split("\t");
        assert ar.length == 4;
        return new Fact(
            Integer.parseInt(ar[0]),
            Integer.parseInt(ar[1]),
            Integer.parseInt(ar[2]),
            ar[3]);
      }

      public String tsv() {
        return sentIdx + "\t" + subjMention + "\t" + objMention + "\t" + verb;
      }
      
      @Override
      public String toString() {
        return "(Fact sent=" + sentIdx + " verb=" + verb + " subj=" + subjMention + " obj=" + objMention + ")";
      }
      
      public String unorderedKeyNoSent() {
        int min = Math.min(subjMention, objMention);
        int max = Math.max(subjMention, objMention);
//        return verb + "/" + sentIdx + "/" + min + "-" + max;
        return verb + "/" + min + "-" + max;
      }
      
      @Override
      public int hashCode() {
        int hv = verb == null ? 9001 : verb.hashCode();
        return Hash.mix(sentIdx, subjMention, objMention, hv);
      }
      
      public static boolean safeEq(Object a, Object b) {
        if (a == null)
          return a == b;
        return a.equals(b);
      }
      
      @Override
      public boolean equals(Object other) {
        if (other instanceof Fact) {
          Fact f = (Fact) other;
          return sentIdx == f.sentIdx
              && subjMention == f.subjMention
              && objMention == f.objMention
              && safeEq(verb, f.verb);
        }
        return false;
      }
    }
    
    /**
     * Look through the given {@link EffSent} for facts in facts-rel0-types.txt.
     * @param sentIdx is just for handing off to a returned {@link Fact}, has nothing to do with the impl of this method.
     */
    public List<Fact> findFacts(int sentIdx, EffSent sent, List<Fact> negAddTo, boolean debug) {
      UniqList<Fact> fs = new UniqList<>();
      int n = sent.numMentions();
      for (int i = 0; i < n; i++) {
        Mention mi = sent.mention(i);
        if (!entityMid.equals(mi.getFullMid()))
          continue;
        for (int j = 0; j < n; j++) {
          Mention mj = sent.mention(j);
          
          // To match up to a fact which presumably has different subj and objs,
          // we require that the mentions are different as well.
          if (Arrays.equals(mi.mid, mj.mid))
            continue;
//          if (i == j)   // INCORRECT! If a mid appears twice in a sentence this breaks!
//            continue;
          
          if (negAddTo != null)
            negAddTo.add(new Fact(sentIdx, i, j, null));

          // All facts match mi's mid, enumerate mj's dbp and check dbp2fact
          for (String dbpJ : mid2dbp.get(mj.getFullMid())) {
            for (DbpediaTtl f : dbp2facts.get(dbpJ)) {
              // f matches mi by construction (rel0) and mj by proof, output it
              boolean mjIsSubj = dbpJ.equals(f.subject().getValue());

              String v = f.verb().getValue();
              boolean added;
              if (mjIsSubj)
                added = fs.add(new Fact(sentIdx, j, i, v));
              else
                added = fs.add(new Fact(sentIdx, i, j, v));
              
              if (debug) {
                Log.info("added=" + added + " sent=" + sentIdx + " mi=" + mi + " mj=" + mj + " f=" + f.tsv() + " mjIsSubj=" + mjIsSubj);
              }
            }
          }
        }
      }
      return fs.getList();
    }
    
    /**
     * returns all possible {@link Fact}s (with null verbs) such that the entity in question is the subject.
     * TODO Include ent-in-question is object cases?
     * This will double the output but include cases where the ent-in-question might appear in non-subj position.
     */
    public List<Fact> findFactTest(int sentIdx, EffSent sent) {
      List<Fact> fs = new ArrayList<>();
      int n = sent.numMentions();
      for (int i = 0; i < n; i++) {
        Mention mi = sent.mention(i);
        if (!entityMid.equals(mi.getFullMid()))
          continue;
        for (int j = 0; j < n; j++) {
          Mention mj = sent.mention(j);
          
          // To match up to a fact which presumably has different subj and objs,
          // we require that the mentions are different as well.
          if (Arrays.equals(mi.mid, mj.mid))
            continue;
          
          fs.add(new Fact(sentIdx, i, j, null));
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
      boolean train = config.getBoolean("train");
      File obsArgTypes = config.getExistingFile("obsArgTypes");
      ObservedArgTypes oat = (ObservedArgTypes) FileUtil.deserialize(obsArgTypes);
      File entityDirParent = config.getExistingDir("entityDirParent");
      String entityDirGlob = config.getString("entityDirGlob");
      List<File> entityDirs = FileUtil.find(entityDirParent, entityDirGlob);
      Log.info("found " + entityDirs.size() + " entity directories to compute features for");

      File dfF = config.getExistingFile("wordDocFreq");
      ComputeIdf df = (ComputeIdf) FileUtil.deserialize(dfF);
      
      for (File ed : entityDirs) {
        String mid = getMidFromEntityDir(ed);
        StreamingDistSupFeatEx f = new StreamingDistSupFeatEx(oat, ed, mid, train);
        f.writeCsoaaLdfFeatures(df);
      }
    }
  }
  
  public static String getMidFromEntityDir(File entityDir) {
    return entityDir.getName().replaceAll("m.", "/m/");
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
    x = x.replaceAll("property/", "");
    return x;
  }
  
  public static String vwSafety(String feat) {
    feat = feat.replaceAll(":", "-C-");
    feat = feat.replaceAll("\\|", "-P-");
    assert feat.indexOf(' ') < 0;
    assert feat.indexOf('|') < 0;
    return feat;
  }
  
  public static String fileSystemSafe(String s) {
    return s.replaceAll("/", "_");
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
      w.write(vwSafety(feat.getName()));
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
  
  /*
   * TODO I need to have a way to make predictions based on featsel-mi instead of distsup
   * Before I had VW just output scores to
   *    $ENTITY/distsup-infobox.csoaa_ldf.yhat or
   *    $ENTITY/infobox-binary/unlab.yhat
   * Now I need to iterate over the same input file:
   *    $ENTITY/distsup-infobox.csoaa_ldf.x or
   *    $ENTITY/infobox-binary/unlab.x
   * And output the same format?
   * Could I loose anything as opposed to doing something more ad-hoc?
   * 
   * I should use the csoaa_ldf format for making predictions.
   */
  
  /**
   * Reads in the output of {@link PmiFeatureSelection}, a bunch of (relation, lexSynFeature, score)
   * instances, which are used to make predictions given new lexSynFeatures.
   */
  public static class PmiSlotPredictor {
    private MultiMap<String, Feat> feat2relMi;
    
    public PmiSlotPredictor() {
      feat2relMi = new MultiMap<>();
    }
    
    /** Keep the top k features for every relation (sorted by discounted PMI) */
    public void topKPrune(int k) {
      Log.info("k=" + k);
      
      // Prune
      int before = 0;
      Map<String, Beam<Feat>> top = new HashMap<>();
      for (String feat : feat2relMi.keySet()) {
        for (Feat rel : feat2relMi.get(feat)) {
          Beam<Feat> b = top.get(rel.getName());
          if (b == null) {
            b = Beam.getMostEfficientImpl(k);
            top.put(rel.getName(), b);
          }
          b.push(new Feat(feat, rel.getWeight()), rel.getWeight());
          before++;
        }
      }
      
      // Re-build the index
      feat2relMi = new MultiMap<>();
      int after = 0;
      for (String rel : top.keySet()) {
        Beam<Feat> b = top.get(rel);
        while (b.size() > 0) {
          Feat f = b.pop();
          feat2relMi.add(f.getName(), new Feat(rel, f.getWeight()));
          after++;
        }
      }
      feat2relMi.sortValues(Feat.BY_SCORE_DESC);
      Log.info("pruned " + before + " entries down to " + after);
    }
    
    public void add(File pmiFeatSelOutput) throws IOException {
      Log.info("reading " + pmiFeatSelOutput.getPath());
      int n = 0;
      try (BufferedReader r = FileUtil.getReader(pmiFeatSelOutput)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          n++;
          String[] ar = line.split("\t");
          String rel = ar[0];
          String feat = ar[1];
          double discountedPmi = Double.parseDouble(ar[3]);
          feat2relMi.add(feat, new Feat(rel, discountedPmi));
        }
      }
//      Log.info("read " + n + " lines");
    }
    
    public List<Feat> predict(VwLine x) {
      List<Feat> ys = new ArrayList<>();
      for (Namespace ns : x.x) {
        for (String f : ns.features) {
          String feat = ns.name + "/" + f;
          for (Feat rel : feat2relMi.get(feat)) {

//            // DEBUG
//            if (rel.getName().equals("pos-themeMusicComposer.vw"))
//              System.out.println("verb=" + rel + " feat=" + feat);

            ys.add(rel);
          }
        }
      }
      ys = Feat.aggregateSum(ys);
      Collections.sort(ys, Feat.BY_SCORE_DESC);
      return ys;
    }
    
    /**
     * format unclear: input is vw-features per line (not ldf) and output is:
     *   pred := <relation> <space> <cost>
     *   line := <pred> (<tab> <pred>)*
     *   
     * Get the features from:
     *   tokenized-sentences/dev/$ENTITY/infobox-binary/unlab.x
     */
    public static void predictOne(ExperimentProperties config) throws IOException {
      File entityDir = config.getExistingDir("entityDir");
      File vwFeatures = new File(entityDir, "infobox-binary/unlab.x");
      File outputPredictions = new File(entityDir, "infobox-binary/unlab-pmiPredictions.yhat");

      Log.info("entityDir=" + entityDir.getPath());

      PmiSlotPredictor model = new PmiSlotPredictor();
      List<File> pmiFiles = config.getFileGlob("pmiFiles");
      for (File f : pmiFiles)
        model.add(f);
      
      Log.info("features=" + vwFeatures.getPath() + " output=" + outputPredictions.getPath());
      try (BufferedReader r = FileUtil.getReader(vwFeatures);
          BufferedWriter w = FileUtil.getWriter(outputPredictions)) {
        for (String line = r.readLine(); line != null; line = r.readLine()) {
          VwLine features = new VwLine(line);
          List<Feat> relations = model.predict(features);
          List<String> toks = new ArrayList<>();
          for (Feat f : relations)
            toks.add(f.getName() + " " + f.getWeight());
          w.write(StringUtils.join("\t", toks));
          w.newLine();
        }
      }
    }
    
    class OnTheFlyPredictor implements Iterator<VwInstance>, AutoCloseable {
      private BufferedReader rFeatures;
      private BufferedReader rLocs;
      private VwInstance cur;
      private Counts<String> ec;
      
      // TODO Find an automatic way to set this; for long summaries, if this value is set too high, the summary will be needlessly short
      private double costNumerator = 1;
      
      public OnTheFlyPredictor(File features, File locations) throws IOException {
        rFeatures = FileUtil.getReader(features);
        rLocs = FileUtil.getReader(locations);
        ec = new Counts<>();
        advance();
      }
      
      private void advance() throws IOException {
        String fLine = rFeatures.readLine();
        if (fLine == null) {
          cur = null;
          return;
        }
        String lLine = rLocs.readLine();
        VwLine feats = new VwLine(fLine);
        List<Feat> verbs = predict(feats);
        ec.increment("pred");
        if (verbs.isEmpty())
          ec.increment("pred/none");
        cur = new VwInstance(Fact.fromTsv(lLine), feats);
        for (Feat f : verbs)
          if (f.getWeight() > 0)
            cur.add(f.getName(), costNumerator / f.getWeight());
      }

      @Override
      public boolean hasNext() {
        return cur != null;
      }

      @Override
      public VwInstance next() {
        VwInstance c = cur;
        try {
          advance();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        return c;
      }
      
      public VwInstance peek() {
        return cur;
      }

      @Override
      public void close() throws IOException {
        rFeatures.close();
        rLocs.close();
      }
    }
  }
  
  /**
   * Expects that you've already computed scores for potential relations in:
   *   $ENTITY/distsup-infobox.csoaa_ldf.yhat
   * and that these also exist:
   *   $ENTITY/distsup-infobox.locations.txt
   *   $ENTITY/mentionLocs.txt
   *   $ENTITY/parse.conll
   *
   * Builds a soft-concept-occurrence model.
   */
  static class StreamingSummarizer {
    private File entityDir;
    
    public StreamingSummarizer(File entityDir) {
      this.entityDir = entityDir;
    }
    
    public String getMid() {
      return entityDir.getName().replace("m.", "/m/");
    }
    
    public void writeoutAlphabet(MultiAlphabet a) {
      File f = new File(entityDir, "summary/alph.jser");
      Log.info("writing MultiAlphabet with " + a.numWord() + " words to " + f.getPath());
      FileUtil.serialize(a, f);
    }
    
    private List<Pair<EffSent, List<VwInstance>>> relationExtractionJoin2(MultiAlphabet a, PmiSlotPredictor pmi) throws IOException {
      File relLocsF = new File(entityDir, "infobox-binary/unlab.location");
      File relFeatsF = new File(entityDir, "infobox-binary/unlab.x");
      if (!relLocsF.isFile() || !relFeatsF.isFile()) {
        Log.info("WARNING: there were no binary features extracted for entityDir=" + entityDir.getPath());
        return null;
      }
      
      File parsesF = new File(entityDir, "parse.conll");
      File mentionLocsF = new File(entityDir, "mentionLocs.txt");

      List<Pair<EffSent, List<VwInstance>>> output = new ArrayList<>();
      try (PmiSlotPredictor.OnTheFlyPredictor fiter = pmi.new OnTheFlyPredictor(relFeatsF, relLocsF);
          EffSent.Iter siter = new EffSent.Iter(parsesF, mentionLocsF, a)) {
        int sentIdx = 0;
        while (siter.hasNext()) {
          EffSent sent = siter.next();
          List<VwInstance> inst = new ArrayList<>();
          while (fiter.hasNext()) {
            VwInstance i = fiter.peek();
            if (i.loc.sentIdx < sentIdx)
              fiter.next();
            else if (i.loc.sentIdx == sentIdx)
              inst.add(fiter.next());
            else
              break;
          }
          if (!inst.isEmpty())
            output.add(new Pair<>(sent, inst));
          sentIdx++;
        }
      }
      return output;
    }
    
    public static class Options {
      boolean entities;
      boolean slots;
      NgramCounts ngrams;
      boolean ngrams_excludeMentions = false;
      double sentenceCostOdd;
      double sentenceCostTopicality;
      int prune_sentKeepFactor = 128;
      
      public Options(boolean entities, boolean slots, NgramCounts ngrams, double sentenceCostOdd, double sentenceCostTopicality) {
        this.entities = entities;
        this.slots = slots;
        this.ngrams = ngrams;
        this.sentenceCostOdd = sentenceCostOdd;
        this.sentenceCostTopicality = sentenceCostTopicality;
      }

      public String desc() {
        String options = "";
        if (entities) options += "e";
        if (slots) options += "s";
        if (ngrams != null) options += "w";
        if (options.isEmpty())
          throw new RuntimeException("no concept definitions!");
        return options;
      }
      
      @Override
      public String toString() {
        return "(Options " + desc() + ")";
      }
    }
    
    /**
     * Rounds to 0 any weight which is not in the top-k.
     */
    public static IntDoubleHashVector prune(IntDoubleHashVector weights, int k, String tag) {
      if (k <= 0) {
        Log.info("k=0, no pruning");
        return weights;
      }
      if (weights.getNumImplicitEntries() <= k) {
        Log.info(tag + ": no pruning needed " + weights.getNumImplicitEntries() + " < " + k);
        return weights;
      }
      final List<Feat> p = new ArrayList<>();
      weights.forEach(ide -> {
        p.add(new Feat("" + ide.index(), ide.get()));
      });
      List<Feat> p2 = Feat.sortAndPrune(p, k);
      Log.info(tag + ": pruned from " + p.size() + " to " + p2.size());
      IntDoubleHashVector m = new IntDoubleHashVector();
      for (Feat f : p2) {
        m.add(Integer.parseInt(f.getName()), f.getWeight());
      }
      return m;
    }
    
    public static List<Pair<EffSent, List<VwInstance>>> filterOutNoVerb(
        List<Pair<EffSent, List<VwInstance>>> in, MultiAlphabet a, ComputeIdf df) {
      if (in == null)
        return null;
      int nv = 0, v0 = 0;
      List<Pair<EffSent, List<VwInstance>>> out = new ArrayList<>();
      for (Pair<EffSent, List<VwInstance>> p : in) {
        EffSent s = p.get1();
        if (!s.containsVerb(a)) {
          nv++;
          continue;
        }
        String pos0 = a.pos(s.parse(0).pos);
        if (pos0.startsWith("V")) {
          v0++;
          continue;
        }
        out.add(p);
      }
      double k = (100d*out.size()) / in.size();
      Log.info("in.size=" + in.size() + " out.size=" + out.size() + " %keep=" + k + " nv=" + nv + " v0=" + v0);
      return out;
    }
  
    public static final Set<String> NGRAM_POS_EXCLUDE;
    static {
      NGRAM_POS_EXCLUDE = new HashSet<>();
      NGRAM_POS_EXCLUDE.add("``");
      NGRAM_POS_EXCLUDE.add(",");
      NGRAM_POS_EXCLUDE.add(":");
      NGRAM_POS_EXCLUDE.add(".");
      NGRAM_POS_EXCLUDE.add("''");
      NGRAM_POS_EXCLUDE.add("$");
      NGRAM_POS_EXCLUDE.add("ADD");
      NGRAM_POS_EXCLUDE.add("FW");
      NGRAM_POS_EXCLUDE.add("GW");
      NGRAM_POS_EXCLUDE.add("HYPH");
      NGRAM_POS_EXCLUDE.add("-LRB-");
      NGRAM_POS_EXCLUDE.add("LS");
      NGRAM_POS_EXCLUDE.add("NFP");
      NGRAM_POS_EXCLUDE.add("-RRB-");
      NGRAM_POS_EXCLUDE.add("SYM");
    }

      
    public static List<SoftConceptMention> pruneBySentence(List<SoftConceptMention> occ, double[] conceptUtilities, int sentKeep) {
      // Lets prune based on sentences with the highest utility
      IntDoubleHashVector sentUpperUtilityBound = new IntDoubleHashVector();
      for (SoftConceptMention cm : occ)
        sentUpperUtilityBound.add(cm.sentence(), conceptUtilities[cm.concept()]);
      Beam<Integer> b = Beam.getMostEfficientImpl(sentKeep);
      sentUpperUtilityBound.forEach(ide -> {
        b.push(ide.index(), ide.get());
      });
      IntHashSet s = new IntHashSet(b.size());
      while (b.size() > 0)
        s.add(b.pop());
      List<SoftConceptMention> keep = new ArrayList<>();
      for (SoftConceptMention cm : occ)
        if (s.contains(cm.sentence()))
          keep.add(cm);
      Log.info("occOld=" + occ.size() + " occNew=" + keep.size() + " sentKeep=" + sentKeep);
      return keep;
    }

    public Summary summarize2(int numWords, MultiAlphabet parseAlph, PmiSlotPredictor pmi,
        ComputeIdf df,
        OddSentenceScore odd,
        CluewebLinkedPreprocess.EntCounts entCounts,
        Options options, boolean debug) throws IOException, GRBException {
      Log.info("options=" + options);
      Alphabet<String> concepts = new Alphabet<>();
      List<SoftConceptMention> occ = new ArrayList<>();
      IntArrayList sentenceLengths = new IntArrayList();
      DoubleArrayList sentenceCosts = null;
      if (options.sentenceCostOdd > 0)
        sentenceCosts = new DoubleArrayList();
      
      int maxSlotsPerInstance = 5;
      int maxSlotsPerSentence = 5;
      String thisMid = this.getMid();
      IntDoubleHashVector relatedEntityConceptCounts = new IntDoubleHashVector();
      IntDoubleHashVector slotConceptCounts = new IntDoubleHashVector();
      IntDoubleHashVector ngramConceptCounts = new IntDoubleHashVector();
      
      double logD_ent = Math.log(entCounts.numMidObservations());

      List<Pair<EffSent, List<VwInstance>>> j2 =
          filterOutNoVerb(relationExtractionJoin2(parseAlph, pmi), parseAlph, df);
      if (j2 == null)
        return null;
      Log.info("computing occurrences and utilities for " + j2.size() + " sentences");
      for (int sIdx = 0; sIdx < j2.size(); sIdx++) {
        List<VwInstance> facts = j2.get(sIdx).get2();
        EffSent sent = j2.get(sIdx).get1();
        sentenceLengths.add(sent.parse().length);
        
        if (sIdx % 2000 == 0)
          Log.info(sIdx + " sentences in...");
        
        // Cost
        double tc = topicalityCost(sent, thisMid);
        double oc = 0;
        if (options.sentenceCostOdd > 0) {
          oc = odd.cost(sent, parseAlph, false);
          oc = Math.min(1000, Math.exp(oc));
        }
        double sentCost = options.sentenceCostOdd * oc
            + options.sentenceCostTopicality * tc;
        sentenceCosts.add(sentCost);
        
        if (options.entities) {
          int nm = sent.numMentions();
          for (int i = 0; i < nm; i++) {
            String mid = sent.mention(i).getFullMid();
            if (thisMid.equals(mid))
              continue;
            
            int count = entCounts.getCount(mid);
            double idf = logD_ent - Math.log(count);
//            System.out.println("count(" + mid + ")=" + count);
//            System.out.println("idf(" + mid + ")=" + idf);
            
            String cs = "r/" + mid;
            int c = concepts.lookupIndex(cs);
            double cost = 0;
            occ.add(new SoftConceptMention(c, sIdx, cost));
//            relatedEntityConceptCounts.add(c, 1);
            relatedEntityConceptCounts.add(c, idf/10d);
          }
        }
        
        if (options.slots) {
          // BEFORE: take the top-K facts per pair of entities in a sentence
          // PROBLEM: sentences which have very long lists of entities can introduce a ton of possible slots
          // AFTER: take the top-K facts per sentence so that no sentence can introduce a huge number of slots
          boolean fix = true;
          if (fix) {  // AFTER
            Map<String, Pair<String, SoftConceptMention>> minCostForSlots = new HashMap<>();
            for (VwInstance f : facts) {
              List<Feat> slots = f.getMostLikelyLabels(maxSlotsPerInstance);
              for (Feat slot : slots) {
                String cs = "s/" + slot.getName();
                double costOfEvokingConcept = slot.getWeight();
                Pair<String, SoftConceptMention> curCost = minCostForSlots.get(cs);
                if (curCost == null || curCost.get2().costOfEvokingConcept > costOfEvokingConcept) {
                  minCostForSlots.put(cs, new Pair<>(cs, new SoftConceptMention(-1, sIdx, costOfEvokingConcept)));
                }
              }
            }
            Beam<Pair<String, SoftConceptMention>> minCosts = Beam.getMostEfficientImpl(maxSlotsPerSentence);
            for (Pair<String, SoftConceptMention> p : minCostForSlots.values()) {
              minCosts.push(p, -p.get2().costOfEvokingConcept);
            }
            while (minCosts.size() > 0) {
              Pair<String, SoftConceptMention> p = minCosts.pop();
              int c = concepts.lookupIndex(p.get1());
              SoftConceptMention scm = new SoftConceptMention(c, sIdx, p.get2().costOfEvokingConcept);
              occ.add(scm);
              slotConceptCounts.add(c, 1);
            }
          } else {    // BEFORE
            for (VwInstance f : facts) {
              List<Feat> slots = f.getMostLikelyLabels(maxSlotsPerInstance);
              for (Feat slot : slots) {
                String cs = "s/" + slot.getName();
                int c = concepts.lookupIndex(cs);
                double costOfEvokingConcept = slot.getWeight();
                SoftConceptMention scm = new SoftConceptMention(c, sIdx, costOfEvokingConcept);
                occ.add(scm);
                slotConceptCounts.add(c, 1);
              }
            }
          }
        }
        
        if (options.ngrams != null) {
          String[] ng = new String[2];
          int n = sent.parse().length;
          String[] words = new String[n];
          for (int i = 0; i < n; i++)
            words[i] = parseAlph.word(sent.parse(i).word);
          int[] mentions = sent.buildToken2EntityMap();
          double logD = Math.log(options.ngrams.numIncrements());
          words:
          for (int i = 0; i < n - ng.length; i++) {
            for (int j = 0; j < ng.length; j++) {
              
              // Skip this ngram if it overlaps with an entity mention
              if (options.ngrams_excludeMentions && mentions[i+j] >= 0)
                continue words;
              
              // Skip this ngram if it contains some sort of junk
              String pos = parseAlph.pos(sent.parse(i+j).pos);
              if (NGRAM_POS_EXCLUDE.contains(pos))
                continue words;

              ng[j] = words[i+j];
            }
            int c = options.ngrams.getCount(ng);
            if (c < 8)
              continue;
            double lc = logD - Math.log(c + 4);
            int concept = concepts.lookupIndex(NgramCounts.join(ng));
            occ.add(new SoftConceptMention(concept, sIdx, 0));
            ngramConceptCounts.add(concept, lc);
          }
        }
      }
      
      // Compute utitilies for each concept
      Log.info("concept counts: slots=" + slotConceptCounts.size()
        + " entities=" + relatedEntityConceptCounts.size()
        + " ngrams=" + ngramConceptCounts.size()
        + " all=" + concepts.size());
      double[] conceptUtilities = new double[concepts.size()];
      
//      int nConceptKeepPerType = (int) (2 * Math.sqrt(numWords) + 0.5);    // 39s on dev/m.08w4pm
//      int nConceptKeepPerType = (int) (5 * Math.sqrt(numWords) + 0.5);    // 37s on dev/m.08w4pm
//      int nConceptKeepPerType = (int) (12 * Math.sqrt(numWords) + 0.5);    // 37s on dev/m.08w4pm
//      int nConceptKeepPerType = (int) (25 * Math.sqrt(numWords) + 0.5);     // 172s on dev/m.08w4pm

//      int nConceptKeepPerType = (int) (Math.sqrt(numWords) * Math.log1p(sentenceLengths.size()) + 10);    // 80s on dev/m.08w4pm
//      int nConceptKeepPerType = (int) (0.5 * Math.sqrt(numWords) * Math.log1p(sentenceLengths.size()) + 10);    // 96s on dev/m.08w4pm
//      int nConceptKeepPerType = (int) (2 * Math.log1p(numWords) * Math.log1p(sentenceLengths.size()) + 10);    // 83s on dev/m.08w4pm
//      int nConceptKeepPerType = (int) (Math.log1p(numWords) * Math.log1p(sentenceLengths.size()) + 10);    // 105s on dev/m.08w4pm
      int nConceptKeepPerType = 0;
      Log.info("nConceptKeepPerType=" + nConceptKeepPerType);

      slotConceptCounts = prune(slotConceptCounts, nConceptKeepPerType, "slots");
      slotConceptCounts.forEach(ide -> {
        assert conceptUtilities[ide.index()] == 0;
//        conceptUtilities[ide.index()] = ide.get();
        conceptUtilities[ide.index()] = Math.log1p(ide.get());
        assert conceptUtilities[ide.index()] > 0;
      });

      relatedEntityConceptCounts = prune(relatedEntityConceptCounts, nConceptKeepPerType, "entities");
      relatedEntityConceptCounts.forEach(ide -> {
        assert conceptUtilities[ide.index()] == 0;
        conceptUtilities[ide.index()] = Math.sqrt(ide.get()) * 0.1;    // weight for entities
      });

      ngramConceptCounts = prune(ngramConceptCounts, nConceptKeepPerType, "ngrams");
      ngramConceptCounts.forEach(ide -> {
        assert conceptUtilities[ide.index()] == 0;
        conceptUtilities[ide.index()] = ide.get() * (3d / j2.size());
      });
      
      int sentKeep = (int) (options.prune_sentKeepFactor * (1 + Math.log1p(numWords)) + 0.5);
      occ = pruneBySentence(occ, conceptUtilities, sentKeep);

      {
      List<SoftConceptMention> occKeep = new ArrayList<>();
      for (SoftConceptMention cm : occ)
        if (conceptUtilities[cm.concept()] > 0)
          occKeep.add(cm);
      Log.info("pruning based on zero utility: " + occ.size() + " => " + occKeep.size());
      occ = occKeep;
      }
      
      Beam<Feat> bestConcepts = Beam.getMostEfficientImpl(32);
      for (int i = 0; i < conceptUtilities.length; i++) {
        if (conceptUtilities[i] > 0)
          bestConcepts.push(new Feat(concepts.lookupObject(i), conceptUtilities[i]), conceptUtilities[i]);
      }
      int ci = 0;
      while (bestConcepts.size() > 0) {
        Feat f = bestConcepts.pop();
        ci++;
        System.out.printf("% 3dth best concept: %-65s utility=%.2f\n", ci, f.getName(), f.getWeight());
      }
      System.out.println();

      GillickFavre09Summarization sum = new GillickFavre09Summarization(occ, sentenceLengths, sentenceCosts, conceptUtilities);
//      IntIntHashMap newSent2oldSent = sum.pruneBySentence(numWords * 2);

      SoftSolution keep = sum.solveSoft(numWords);
      Log.info("keep: " + keep);
      Summary s = new Summary(getMid(), options.desc());
      for (int i = 0; i < keep.sentences.size(); i++) {
        int sIdx = keep.sentences.get(i);
//        sIdx = newSent2oldSent.get(sIdx);
        s.sentences.add(j2.get(sIdx).get1());
        s.sentenceCosts.add(sentenceCosts.get(sIdx));
        s.conceptPredictions = j2.get(sIdx).get2();
        for (SoftConceptMention m : keep.mentionsIn(sIdx)) {
          String cs = concepts.lookupObject(m.concept());
          s.addConcept(i, Span.nullSpan, cs, conceptUtilities[m.concept()], m.costOfEvokingConcept);
        }
      }
      return s.orderSentencesByUtility();
//      return s;
    }
    
    public static double topicalityCost(EffSent sent, String subject) {
      int n = sent.numTokens();
      int shallow = n;
      int firstTok = n;
      int[] depth = DepNode.depths(sent.parse());
      for (int i = 0; i < sent.numMentions(); i++) {
        Mention m = sent.mention(i);
        if (subject.equals(m.getFullMid())) {
          for (int j = m.start; j < m.end; j++) {
            if (j < firstTok) firstTok = j;
            if (depth[j] < shallow) shallow = depth[j];
          }
        }
      }
      assert shallow >= 0;
      assert firstTok >= 0;
      return shallow + (2d*firstTok)/n;
    }
    
    /*
     * @param parseAlph you can/should provide a new/empty one of these (passed in so it can be mutated and show these changes to the caller)
    public Summary summarize(int numWords, MultiAlphabet parseAlph, PmiSlotPredictor pmi, boolean debug) throws IOException, GRBException {
      Alphabet<String> concepts = new Alphabet<>();
      List<SoftConceptMention> occ = new ArrayList<>();
      IntArrayList sentenceLengths = new IntArrayList();

      // Iterate over all the predictions (verb/relation costs) made over locations found by typePlausible.
      // Store them in memory.
      int maxVerbsPerInstance = 5;
      IntObjectHashMap<EffSent> sents = new IntObjectHashMap<>();
      List<Pair<EffSent, VwInstance>> j = relationExtractionJoin(parseAlph, pmi);
      if (j == null)
        return null;
      for (Pair<EffSent, VwInstance> x : j) {
        VwInstance i = x.get2();
        EffSent s = x.get1();
        int sIdx = i.loc.sentIdx;
        
        Object old = sents.put(sIdx, s);
        assert old == null || old == s;
        if (old == null) {
          sentenceLengths.add(s.parse().length);
        }
        
        // TODO Have a notion of "backoff concepts"
        // e.g. if "ceo(subj,ACME)" is a concept, with an associated utility and e_ij variable,
        // ensure that its utility includes the utility for the "backoff concept": "ceo(subj,*)"
        // You don't need to add new e_ij variables, just ensure that p_ij is computed by summing
        // the utilities for all backoffs of the ij^{th} concept.
        // Use the same counting method for computing utility of backoff concepts as terminal/leaf concepts.
        
        List<Feat> verbs = x.get2().getMostLikelyLabels(maxVerbsPerInstance);
//        verbs = Feat.aggregateSum(verbs);
        verbs = Feat.aggregateMax(verbs);
        
        String so = "s=" + i.getSubjMid(s) + "_o=" + i.getObjMid(s);
        for (Feat v : verbs) {
          String conceptS = "v=" + v.getName() + "_" + so;
//          String conceptS = "v=" + v.getName();
          int conceptI = concepts.lookupIndex(conceptS);
          double costOfEvokingConcept = v.getWeight();
          SoftConceptMention scm = new SoftConceptMention(conceptI, sIdx, costOfEvokingConcept);
          occ.add(scm);
        }
      }
      
      // Show the most likely relation prediction
      if (debug) {
        // Sort by the lowest cost prediction for a given location
        Collections.sort(j, new Comparator<Pair<EffSent, VwInstance>>() {
          @Override
          public int compare(Pair<EffSent, VwInstance> o1, Pair<EffSent, VwInstance> o2) {
            double s1 = o1.get2().minCost();
            double s2 = o2.get2().minCost();
            if (s1 < s2)
              return -1;
            if (s2 < s1)
              return +1;
            return 0;
          }
        });
        int k = Math.min(16, j.size());
        Log.info("showing the " + k + " most likely predictions...");
        for (int i = 0; i < k; i++) {
          Pair<EffSent, VwInstance> p = j.get(i);
          EffSent s = p.get1();
          VwInstance f = p.get2();
          s.showChunkedStyle(parseAlph);
          System.out.println("subj: " + s.mention(f.loc.subjMention).show(s.parse(), parseAlph));
          System.out.println("obj: " + s.mention(f.loc.objMention).show(s.parse(), parseAlph));
          List<Feat> verbs = f.getMostLikelyLabels(maxVerbsPerInstance);
          for (Feat v : verbs) {
            String sig = ShowDistsupInstances.sigCost(v.getWeight());
            System.out.printf("\t%-20s %.2f sig=%s\n", v.getName(), v.getWeight(), sig);
          }
          System.out.println();
        }
      }

      // TODO Revisit whether this is a good idea
      double[] conceptUtilities = new double[concepts.size()];
      for (ConceptMention c : occ)
        conceptUtilities[c.i] += 1.0;
      Beam<Feat> bestConcepts = Beam.getMostEfficientImpl(16);
      for (int i = 0; i < conceptUtilities.length; i++) {
        if (conceptUtilities[i] > 0)
          bestConcepts.push(new Feat(concepts.lookupObject(i), conceptUtilities[i]), conceptUtilities[i]);
      }
      int ci = 0;
      while (bestConcepts.size() > 0) {
        Feat f = bestConcepts.pop();
        ci++;
        System.out.printf("% 3dth best concept: %-65s utility=%.2f\n", ci, f.getName(), f.getWeight());
      }
      System.out.println();

      GillickFavre09Summarization sum = new GillickFavre09Summarization(occ, sentenceLengths, conceptUtilities);
      SoftSolution keep = sum.solveSoft(numWords);
      Log.info("keep: " + keep);
      Summary s = new Summary(getMid());
      for (int i = 0; i < keep.sentences.size(); i++) {
        int sIdx = keep.sentences.get(i);
        s.sentences.add(sents.get(sIdx));
        for (SoftConceptMention m : keep.mentionsIn(sIdx)) {
          String cs = concepts.lookupObject(m.concept());
          s.addConcept(i, Span.nullSpan, cs, conceptUtilities[m.concept()], m.costOfEvokingConcept);
        }
      }
      return s.orderSentencesByUtility();
//      return s;
    }
     */
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

      DoubleArrayList sentenceCosts = null;
      GillickFavre09Summarization gf = new GillickFavre09Summarization(occ, sentenceLengths, sentenceCosts, conceptUtilities);

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
  
  public static List<String> stripAngleBrackets(List<String> in) {
    List<String> out = new ArrayList<>();
    for (String s : in) {
//      s = s.replace("<", "");
      out.add(s.substring(1, s.length()-1));
    }
    return out;
  }
  
  public static void test0(ObservedArgTypes.PlausibleMemoizer oatM) throws IOException {
    // What we're trying to make work
    String verb = "http://dbpedia.org/property/headquarters";
    List<String> subj = new ArrayList<>();
    subj.add("<http://www.wikidata.org/entity/Q141683>");
    subj.add("<http://dbpedia.org/ontology/Broadcaster>");
    subj.add("<http://www.wikidata.org/entity/Q15265344>");
    subj.add("<http://dbpedia.org/ontology/Organisation>");
//    subj.add("<http://schema.org/Organization>");
//    subj.add("<http://www.ontologydesignpatterns.org/ont/dul/DUL.owl#SocialPerson>");
//    subj.add("<http://www.wikidata.org/entity/Q43229>");
//    subj.add("<http://dbpedia.org/ontology/Agent>");
//    subj.add("<http://www.ontologydesignpatterns.org/ont/dul/DUL.owl#Agent>");
//    subj.add("<http://www.w3.org/2002/07/owl#Thing>");
    List<String> obj = new ArrayList<>();
    obj.add("<http://schema.org/Country>");
    obj.add("<http://www.wikidata.org/entity/Q6256>");
    obj.add("<http://dbpedia.org/ontology/PopulatedPlace>");
    obj.add("<http://www.wikidata.org/entity/Q486972>");
    obj.add("<http://dbpedia.org/ontology/Place>");
    obj.add("<http://schema.org/Place>");
//    obj.add("<http://dbpedia.org/ontology/Location>");
//    obj.add("<http://www.w3.org/2002/07/owl#Thing>");
//    obj.add("<http://www.w3.org/2002/07/owl#Thing>");
//    obj.add("<http://www.wikidata.org/entity/Q476028>");
//    obj.add("<http://dbpedia.org/ontology/SportsTeam>");
//    obj.add("<http://schema.org/SportsTeam>");
//    obj.add("<http://dbpedia.org/ontology/Organisation>");
//    obj.add("<http://schema.org/Organization>");
//    obj.add("<http://www.ontologydesignpatterns.org/ont/dul/DUL.owl#SocialPerson>");
//    obj.add("<http://www.wikidata.org/entity/Q43229>");
//    obj.add("<http://dbpedia.org/ontology/Agent>");
//    obj.add("<http://www.ontologydesignpatterns.org/ont/dul/DUL.owl#Agent>");
//    obj.add("<http://www.w3.org/2002/07/owl#Thing>");
    
    
    // See if this changes anything
    subj = stripAngleBrackets(subj);
    obj = stripAngleBrackets(obj);
    
    boolean verbose = false;
    
    // First sanity check
    Log.info("sanity0");
    ObservedArgTypes oat = new ObservedArgTypes(10, 21);
    oat.add(subj, obj, verb, verbose);
    System.out.println("dummyPlausible=" + oat.plausibleVerbs(subj, obj, verbose));
    
    // Midpoint
    Log.info("sanity1");
    oat = new ObservedArgTypes(10, 21);
    File ed = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev/m.017kjq");
    EntityTypes ts = new EntityTypes(ed);
    oat.addAll(ts, new File(ed, "facts-rel0-types.txt"), verbose);
    System.out.println("maybePlausible=" + oat.plausibleVerbs(subj, obj, verbose));
    
    // Add what we're looking for
    Log.info("sanity2");
//    File ed = new File("data/facc1-entsum/code-testing-data/tokenized-sentences/dev/m.017kjq");
//    EntityTypes ts = new EntityTypes(ed);
    oatM.getWrapped().addAll(ts, new File(ed, "facts-rel0-types.txt"), verbose);
    System.out.println("realPlausible=" + oatM.getWrapped().plausibleVerbs(subj, obj, verbose));
    
    Log.info("sanity3");
    System.out.println("subj: " + subj);
    System.out.println("obj: " + obj);

    List<Verb> vs = oatM.plausibleVerbs(subj, obj);
    Collections.sort(vs, Verb.BY_SVO_DESC);
    System.out.println("vs: " + vs);
    int k = 0;
    for (Verb v : vs) {
      System.out.println(v);
      if (++k == 30)
        break;
    }
    
//    for (String st : subj) {
//      for (String ot : obj) {
//        Verb v = oatM.getWrapped().getCounts(st, verb, ot);
//        if (v.totalCount() > 0)
//          System.out.println(st + "\t" + ot + "\t" + v);
//      }
//    }
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    File output;
    File dfF;
    ComputeIdf df;
    
    String m = config.getString("mode");
    switch (m) {
    case "extractFeatures":   // makes tokenized-sentences/$ENTITY/distsup-infobox.csoaa_ldf.yx
      StreamingDistSupFeatEx.computeFeaturesForAllEntities(config);
      break;
    case "extractFeaturesForOneEntity":
      boolean train = config.getBoolean("train");
      String obsArgTypes = config.getString("obsArgTypes");
      ObservedArgTypes oat;
      if ("none".equalsIgnoreCase(obsArgTypes)) {
        Log.info("no obsArgTypes");
        oat = null;
      } else {
        oat = (ObservedArgTypes) FileUtil.deserialize(new File(obsArgTypes));
      }
      File entityDir = config.getExistingDir("entityDir");
      String mid = entityDir.getName().replaceAll("m.", "/m/");

      dfF = config.getExistingFile("wordDocFreq");
      df = (ComputeIdf) FileUtil.deserialize(dfF);

      StreamingDistSupFeatEx f = new StreamingDistSupFeatEx(oat, entityDir, mid, train);
      if (config.getBoolean("binary")) {
        f.writeBinaryFeatures(df, config.getInt("negsPerSentence", 20));
      } else {
        f.writeCsoaaLdfFeatures(df);
      }
      break;
    case "pmiPrediction":
      PmiSlotPredictor.predictOne(config);
      break;
    case "summarize":
      StreamingSummarizer sum = new StreamingSummarizer(config.getExistingDir("entityDir"));
      MultiAlphabet a = new MultiAlphabet();
      File outputDir = config.getOrMakeDir("outputDir");

      PmiSlotPredictor pmi = new PmiSlotPredictor();
      List<File> pmiFiles = config.getFileGlob("pmiFiles");
      for (File ff : pmiFiles)
        pmi.add(ff);
//      int k = config.getInt("topFeats", 30);
      int k = config.getInt("topFeats", 20);
      pmi.topKPrune(k);
      
      dfF = config.getExistingFile("wordDocFreq");
      df = (ComputeIdf) FileUtil.deserialize(dfF);

      boolean debug = config.getBoolean("debug", true);

      boolean entities = config.getBoolean("entities", true);
      boolean slots = config.getBoolean("slots", true);
      String ngramFile = config.getString("ngrams", "none");
      NgramCounts ngrams = null;
      if (ngramFile != null && !"none".equalsIgnoreCase(ngramFile)) {
        Log.info("loading ngram counts from " + ngramFile);
        ngrams = (NgramCounts) FileUtil.deserialize(new File(ngramFile));
        //              new File("data/facc1-entsum/code-testing-data/bigram-counts.nhash11-logb22.jser"));
      } else {
        Log.info("disregarding ngram concepts");
      }
      double sentCostOdd = config.getDouble("sentCostOdd");
      Log.info("sentCostOdd=" + sentCostOdd);
      double sentCostTopicality = config.getDouble("sentCostTopicality");
      Log.info("sentCostTopicality=" + sentCostTopicality);
      Options opt = new Options(entities, slots, ngrams, sentCostOdd, sentCostTopicality);
      opt.ngrams_excludeMentions = config.getBoolean("ngrams_excludeMentions", false);
      Log.info("ngrams_excludeMentions=" + opt.ngrams_excludeMentions);
      
      OddSentenceScore odd = null;
      if (sentCostOdd > 0) {
        File oddF = config.getExistingFile("oddSentenceScores", new File("/tmp/odd.jser"));
        odd = (OddSentenceScore) FileUtil.deserialize(oddF);
      }
      
//      File entCountsF = new File("data/facc1-entsum/train-dev-test/freebase-mid-mention-frequency.cms.jser");
      File entCountsF = config.getExistingFile("entCounts");
      Log.info("reading from entCounts=" + entCountsF);
      CluewebLinkedPreprocess.EntCounts entCounts = (CluewebLinkedPreprocess.EntCounts) FileUtil.deserialize(entCountsF);
      

      //      for (String nws : config.getString("numWords", "40,80,160,320").split("\\D+")) {
      for (String nws : config.getString("numWords", "100").split("\\D+")) {
        int numWords = Integer.parseInt(nws);
        assert numWords > 0;
        File jserOutputP = new File(outputDir, "w" + numWords);
        jserOutputP.mkdirs();
        File jserOutput = new File(jserOutputP, opt.desc() + ".jser");
        Log.info("numWords=" + numWords + " output=" + jserOutput.getPath());

        Summary s = sum.summarize2(numWords, a, pmi, df, odd, entCounts, opt, debug);

        if (s != null) {
          s.show(a);
          FileUtil.VERBOSE = true;
          FileUtil.serialize(s, jserOutput);
        }
      }
      sum.writeoutAlphabet(a);
      break;
    default:
      throw new RuntimeException("unknown mode: " + m);
    }
    Log.info("done");
  }
}
