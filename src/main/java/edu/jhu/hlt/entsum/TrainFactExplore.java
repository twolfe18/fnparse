package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.rand.ReservoirSample;
import edu.jhu.util.MultiMap;

public class TrainFactExplore {
  public static final Random RAND = new Random(9001);
  
  private MultiMap<String, DbpediaTtl> byRelation;
  private Set<String> mid, dbp;
  private Map<String, Counts<String>> rel2midCounts;
  
  // TODO Compute h-index for objects of a relation
  // TODO incorporate facts/objType instead of entitiesWhoHaveAFact/objType

  /**
   * @param facts should be created via:
   * find tokenized-sentences/train/ -name facts-rel0-types.txt | xargs cat >all-train-facts.txt
   * 
   * @param mid2dbp should be created via:
   * find tokenized-sentences/train -name mid2dbp-rel0.txt | xargs cat >all-train-mid2dbp.txt
   * 
   * @param factExtractionCounts should be created via:
   * (first grep puts one <mid> <verb> per fact extraction)
   * grep -P '^\d' tokenized-sentences/train/STAR/infobox-distsup.locations.txt \
   *    | perl -pe 's/tokenized-sentences\/train\/(.+)\/infobox-distsup.locations.txt:\d+\t\d+\t\d+(.+)/\1\t\2/' \
   *    >all-train-fact-distsup.txt
   */
  public TrainFactExplore(File facts, File mid2dbp, File factExtractionCounts) throws IOException {
    mid = new HashSet<>();
    dbp = new HashSet<>();
    ReservoirSample<String> dbpRes = new ReservoirSample<>(10, RAND);
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(mid2dbp, false)) {
      while (iter.hasNext()) {
        DbpediaTtl t = iter.next();
        dbpRes.add(DistSupFact.idCleanup(t.subject().getValue()));
        dbp.add(t.subject().getValue());
        mid.add(DbpediaTtl.extractMidFromTtl(t.object().getValue()));
      }
    }
    System.out.println("subj sample: " + dbpRes.toList());

    byRelation = new MultiMap<>();
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(facts, false)) {
      while (iter.hasNext()) {
        DbpediaTtl t = iter.next();
        if (t.subject().type != Type.DBPEDIA_ENTITY)
          continue;
        if (t.object().type != Type.DBPEDIA_ENTITY)
          continue;
        
//        if (!dbp.contains(t.subject().getValue()) || !dbp.contains(t.object().getValue()))
//          continue;
//        if (!dbp.contains(t.subject().getValue()))
//          continue;
        if (!dbp.contains(t.subject().getValue()) && !dbp.contains(t.object().getValue()))
          continue;
        
        byRelation.add(t.verb().getValue(), t);
      }
    }
    
//    ==> all-train-fact-distsup.counts.txt <==
//        6 m.0_03p         http://dbpedia.org/property/area
//        1 m.0_03p         http://dbpedia.org/property/birthPlace
//        1 m.0_03p         http://dbpedia.org/property/blankInfo
//      562 m.0_03p         http://dbpedia.org/property/city
//        2 m.0_03p         http://dbpedia.org/property/deathPlace
//        2 m.0_03p         http://dbpedia.org/property/placeOfBirth
//        2 m.0_03p         http://dbpedia.org/property/placeOfDeath
//      943 m.0_03p         http://dbpedia.org/property/subdivisionName
//       53 m.0_03p         http://dbpedia.org/property/west
//        5 m.01008g                http://dbpedia.org/property/birthPlace
    rel2midCounts = new HashMap<>();
    try (BufferedReader r = FileUtil.getReader(factExtractionCounts)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] a = line.trim().split("\\s+");
        assert a.length == 3;
        int c = Integer.parseInt(a[0]);
        String mid = a[1];
        String rel = a[2];
        Counts<String> mc = rel2midCounts.get(rel);
        if (mc == null) {
          mc = new Counts<>();
          rel2midCounts.put(rel, mc);
        }
        mc.update(mid, c);
      }
    }
  }
  
  static class Relation {
    String rel, relNice;
    List<DbpediaTtl> fs;
    Counts<DbpediaToken> subjEnts;    // counts of how many entities use appear as a subj to this relation/verb
    Counts<DbpediaToken> objEnts;
    Counts<String> mids;    // counts of how many fact extractions there are per mid/key

    public Relation(String rel, List<DbpediaTtl> fs, Counts<String> mids) {
      this.rel = rel;
      this.relNice = DistSupFact.relCleanup(rel).replaceAll("dbp/", "");
      this.fs = fs;
      subjEnts = new Counts<>();
      objEnts = new Counts<>();
      for (DbpediaTtl t : fs) {
        subjEnts.increment(t.subject());
        objEnts.increment(t.object());
      }
      this.mids = mids;
    }

    public double factsPerObj() {
      return ((double) fs.size()) / objEnts.numNonZero();
    }
    public double customScore() {
      if (relNice.equalsIgnoreCase("religion"))
        return Double.POSITIVE_INFINITY;
      double a = Math.sqrt(factsPerObj());
      double b = Math.log(fs.size());
      double c = subjEnts.hIndex();
      double d = objEnts.hIndex();
      double e = mids.hIndex();
      return (1+a) * (1+b) * (1+c) * (1+d) * (1+e);
    }
    public List<String> sampleObj(int k) {
      ReservoirSample<String> r = new ReservoirSample<>(k, RAND);
      for (DbpediaTtl f : fs)
        r.add(f.object().getValue());
      return r.toList();
    }
    public List<String> sampleSubj(int k) {
      ReservoirSample<String> r = new ReservoirSample<>(k, RAND);
      for (DbpediaTtl f : fs)
        r.add(f.subject().getValue());
      return r.toList();
    }
    public List<String> sampleSubjIn(Set<String> subset, int k) {
      ReservoirSample<String> r = new ReservoirSample<>(k, RAND);
      for (DbpediaTtl f : fs)
        if (subset.contains(f.subject().getValue()))
          r.add(f.subject().getValue());
      return r.toList();
    }
    public static final Comparator<Relation> BY_FPO_DESC = desc(Relation::factsPerObj);
  }

  public static Comparator<Relation> desc(ToDoubleFunction<Relation> f) {
    return new Comparator<Relation>() {
      public int compare(Relation o1, Relation o2) {
        double s1 = f.applyAsDouble(o1);
        double s2 = f.applyAsDouble(o2);
        if (s1 > s2)
          return -1;
        if (s2 > s1)
          return +1;
        return 0;
      }
    };
  }

  public void foo() {
    List<Relation> rs = new ArrayList<>();
    Counts<String> ec = new Counts<>();
    for (String rel : byRelation.keySet()) {
      List<DbpediaTtl> fs = byRelation.get(rel);
      Counts<String> mids = rel2midCounts.get(rel);
      if (mids == null) {
        Log.info("warning: " + rel + " has no mids");
        mids = new Counts<>();
      }
      Relation r = new Relation(rel, fs, mids);
      boolean keep = true;
      ec.increment("relation/all");
      if (fs.size() < 10) {
        ec.increment("relation/skip/nFact=" + fs.size());
        keep = false;
      }
      if (r.subjEnts.numNonZero() < 4) {
        ec.increment("relation/skip/nSubjT=" + r.subjEnts.numNonZero());
        keep = false;
      }
      if (r.objEnts.numNonZero() < 4) {
        ec.increment("relation/skip/nObjT=" + r.objEnts.numNonZero());
        keep = false;
      }
      if (keep) {
        ec.increment("relation/kept");
        rs.add(r);
      }
    }
//    Collections.sort(rs, Relation.BY_FPO_DESC);
    Collections.sort(rs, desc(Relation::customScore)); 
    for (int i = 0; i < Math.min(64, rs.size()); i++) {
      Relation r = rs.get(i);
//      System.out.printf("%-30s nObj=% 4d  facts=% 6d  subj.h=% 3d  obj.h=% 3d  factsPerObj=% 8.2f  subj=%s  obj=%s\n",
//          r.relNice, r.objEnts.numNonZero(), r.fs.size(), r.subjEnts.hIndex(), r.objEnts.hIndex(), r.factsPerObj(),
//          idCleanup(r.sampleSubj(5)),
//          idCleanup(r.sampleObj(5)));
      System.out.printf("%-20s nObj=% 4d  entFacts=% 6d  subjEnts.h=% 3d  objEnts.h=% 3d  factsPerObj=% 8.2f  factExs=% 8d  factExs.h=% 4d\n",
          r.relNice, r.objEnts.numNonZero(), r.fs.size(), r.subjEnts.hIndex(), r.objEnts.hIndex(), r.factsPerObj(),
          r.mids.getTotalCount(), r.mids.hIndex());
      System.out.printf("%ssubj=%s\n", rep(' ', 21), idCleanup(r.sampleSubj(20)));
      System.out.printf("%sobj=%s\n", rep(' ', 22), idCleanup(r.sampleObj(20)));
      System.out.println();
    }
    List<Integer> ts = Arrays.asList(2, 3, 5, 8, 13);
    for (Relation r : rs) {
      double fpo = r.factsPerObj();
      for (int t : ts)
        if (fpo > t)
          ec.increment(String.format("relation/fpo>%02d", t));
    }
//    Log.info(ec);
    for (String k : ec.getKeysSorted())
      System.out.printf("%-30s % 6d\n", k, ec.getCount(k));
  }
  
  public static String rep(char c, int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++)
      sb.append(c);
    return sb.toString();
  }

  public static String idCleanup(String id) {
//    id = id.replace("http://dbpedia.org/resource/", "dbp/");
    id = id.replace("http://dbpedia.org/resource/", "");
    return id;
  }
  public static List<String> idCleanup(List<String> id) {
    List<String> out = new ArrayList<>();
    for (String s : id)
      out.add(idCleanup(s));
    return out;
  }
  
  public static void main(String[] args) throws Exception {
    TrainFactExplore t = new TrainFactExplore(
        new File("data/facc1-entsum/all-train-facts.txt"),
        new File("data/facc1-entsum/all-train-mid2dbp.txt"),
        new File("data/facc1-entsum/all-train-fact-distsup.counts.txt"));
    t.foo();
  }
}
