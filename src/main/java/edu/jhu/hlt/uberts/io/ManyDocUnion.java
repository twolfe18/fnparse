package edu.jhu.hlt.uberts.io;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * Given two many-doc fact files with the same document ids (in the same order),
 * produce a new many-doc fact file with the union of the facts in both inputs.
 * You can optionally filter either input file before the union process.
 *
 * @author travis
 */
public class ManyDocUnion extends FactWriter {

  /**
   * Selects whether to keep a RelLine or not based on the relation it represents.
   */
  static class Filter {
    // Only one of these can be non-null.
    // If both are null, then keep everything.
    private Set<String> include;  // if non-null, only keep relations in this set
    private Set<String> exclude;  // if non-null, keep everything except relations in this set

    public void include(String k) {
      if (exclude != null)
        throw new IllegalStateException();
      if (include == null)
        include = new HashSet<>();
      include.add(k);
    }

    public void exclude(String k) {
      if (include != null)
        throw new IllegalStateException();
      if (exclude == null)
        exclude = new HashSet<>();
      exclude.add(k);
    }

    public boolean keep(String k) {
      if (include != null)
        return include.contains(k);
      if (exclude != null)
        return !exclude.contains(k);
      return true;
    }

    public static Filter parseFilter(String keyPrefix, ExperimentProperties config) {
      Filter lf = new Filter();
      String inc = keyPrefix + ".include";
      if (config.containsKey(inc)) {
        for (String s : config.getStrings(inc))
          lf.include(s);
      }
      String exc = keyPrefix + ".exclude";
      if (config.containsKey(exc)) {
        for (String s : config.getStrings(exc))
          lf.exclude(s);
      }
      return lf;
    }
  }

  private Filter aFilter, bFilter;

  public ManyDocUnion() {
    this(new Filter(), new Filter());
  }

  public ManyDocUnion(Filter aFilter, Filter bFilter) {
    this.aFilter = aFilter;
    this.bFilter = bFilter;
  }

  public void join(File a, File b) throws IOException {
    Log.info("joining " + a.getPath() + " and " + b.getPath());
    boolean includeProvidence = false;
    boolean dedup = true;
    try (RelationFileIterator i1 = new RelationFileIterator(a, includeProvidence);
        RelationFileIterator i2 = new RelationFileIterator(b, includeProvidence);
        ManyDocRelationFileIterator m1 = new ManyDocRelationFileIterator(i1, dedup);
        ManyDocRelationFileIterator m2 = new ManyDocRelationFileIterator(i2, dedup)) {
      join(m1, m2);
    }
  }

  /**
   * You specify the output using {@link FactWriter} methods.
   */
  public void join(Iterator<RelDoc> a, Iterator<RelDoc> b) throws IOException {
    TimeMarker tm = new TimeMarker();
    int n = 0;
    while (a.hasNext()) {
      if (!b.hasNext())
        throw new IllegalArgumentException("lengths are not the same");

      RelDoc d1 = a.next();
      RelDoc d2 = b.next();
      n++;

      String def1 = d1.def.toLine(false);
      String def2 = d2.def.toLine(false);
      if (!def1.equals(def2)) {
        throw new RuntimeException("different set of document ids: " + def1 + " vs " + def2);
      }
      writeRelLine(d1.def);

      assert d1.facts.isEmpty();
      assert d2.facts.isEmpty();

      Set<String> uniq = new HashSet<>();
      for (RelLine f : d1.items) {
        String r = f.tokens[1];
        if (aFilter.keep(r) && uniq.add(f.toLine()))
          writeRelLine(f);
      }
      for (RelLine f : d2.items) {
        String r = f.tokens[1];
        if (bFilter.keep(r) && uniq.add(f.toLine()))
          writeRelLine(f);
      }

      if (tm.enoughTimePassed(15)) {
        Log.info("wrote " + n + " documents in " + tm.secondsSinceFirstMark() + " seconds");
      }
    }
    if (b.hasNext())
      throw new IllegalArgumentException("lengths are not the same");
    Log.info("done, wrote " + n + " documents in " + tm.secondsSinceFirstMark() + " seconds");
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    File l = config.getExistingFile("input.left");
    File r = config.getExistingFile("input.right");
    File out = config.getFile("output");
    Filter lf = Filter.parseFilter("filter.left", config);
    Filter rf = Filter.parseFilter("filter.right", config);
    try (ManyDocUnion m = new ManyDocUnion(lf, rf)) {
      m.writeToFile(out);
      m.join(l, r);
    }
  }
}
