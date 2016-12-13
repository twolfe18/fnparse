package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;

import edu.jhu.hlt.fnparse.util.Describe;
import edu.jhu.hlt.ikbp.tac.StringIntUuidIndex.StrIntUuidEntry;
import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.EfficientUuidList;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.LazyIteration.FIterator;
import edu.jhu.hlt.tutils.LazyIteration.FlatIterator;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.prim.map.IntObjectHashMap;
import edu.jhu.prim.tuple.Pair;

/**
 * (string, int) => [UUID]
 * Implemented as a map of maps: string => int => [UUID]
 * where the list of UUIDs is implemented efficiently with long[]s.
 *
 * @author travis
 */
public class StringIntUuidIndex implements Serializable, Iterable<StrIntUuidEntry> {
  private static final long serialVersionUID = 5768636562747522470L;
  
  public static class StrIntUuidEntry {
    public final String string;
    public final int integer;
    public final String uuid;
    public StrIntUuidEntry(String s, int i, String u) {
      string = s;
      integer = i;
      uuid = u;
    }
  }
  
  private Map<String, IntObjectHashMap<EfficientUuidList>> str2int2uuids;
  
  // Meta
  public MultiTimer timer = new MultiTimer();
  protected Counts<String> valuesPerString = new Counts<>();
  protected int n_values = 0;
  
  public StringIntUuidIndex() {
    str2int2uuids = new HashMap<>();
  }
  
  public int getNumEntries() {
    return n_values;
  }
  public int getNumKeys(String string) {
    IntObjectHashMap<?> m = str2int2uuids.get(string);
    if (m == null)
      return 0;
    return m.size();
  }
  public int getNumValues(String string, int i) {
    IntObjectHashMap<EfficientUuidList> m = str2int2uuids.get(string);
    if (m == null)
      return 0;
    EfficientUuidList l = m.get(i);
    if (l == null)
      return 0;
    return l.size();
  }
  
  @Override
  public Iterator<StrIntUuidEntry> iterator() {
    Iterator<Entry<String, IntObjectHashMap<EfficientUuidList>>> k = str2int2uuids.entrySet().iterator();
    FIterator<Entry<String, IntObjectHashMap<EfficientUuidList>>, List<StrIntUuidEntry>> nested = new FIterator<>(k, e -> {
      List<StrIntUuidEntry> l = new ArrayList<>();
      String s = e.getKey();
      IntObjectHashMap<EfficientUuidList> m = e.getValue();
      IntObjectHashMap<EfficientUuidList>.Iterator x = m.iterator();
      while (x.hasNext()) {
        x.advance();
        int i = x.key();
        EfficientUuidList u = x.value();
        int n = u.size();
        for (int j = 0; j < n; j++)
          l.add(new StrIntUuidEntry(s, i, u.getString(j)));
      }
      return l;
    });
    return new FlatIterator<>(nested);
  }
  
  public void pruneStringWithFewerThanKInts(int k) {
    // If used for (deprel, entityPair) -> [Tokenization UUID],
    // this operation corresponds to removing dependency paths which
    // have fewer than k observed entityPairs.
    throw new RuntimeException("implement me");
  }
  
  public void pruneStringWithFewerThanKValues(int k) {
    // If used for (deprel, entityPair) -> [Tokenization UUID],
    // this operation corresponds to removing dependency paths which
    // have fewer than k observations (mentions/Tokenizations)
    throw new RuntimeException("implement me");
  }
  
  /**
   * Writes lines with the format:
   * <string> <tab> <int> (<tab> <uuid>)+
   */
  public void writeStringIntLines(File f) {
    
    throw new RuntimeException("implement me");
  }
  
  /**
   * Writes lines with the format:
   * <int> (<tab> <uuid>)+
   * to the file provided by calling the given function with a string value.
   */
  public void writeIntLines(Function<String, File> whereToPutStringsValues) throws IOException {
    
    throw new RuntimeException("implement me");
  }
  
  /**
   * Reads lines with the format:
   * <string> <tab> <int> (<tab> <uuid>)+
   */
  public void addStringIntLines(File f) throws IOException {
    Log.info("reading lines from " + f.getPath());
    int read = 0;
    TimeMarker tm = new TimeMarker();
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        // string, int, uuid+
        String[] ar = line.split("\t");
        assert ar.length >= 3;
        int i = Integer.parseUnsignedInt(ar[1]);
        IntObjectHashMap<EfficientUuidList> m = str2int2uuids.get(ar[0]);
        if (m == null) {
          m = new IntObjectHashMap<>();
          str2int2uuids.put(ar[0], m);
        }
        EfficientUuidList l = m.get(i);
        if (l == null) {
          l = new EfficientUuidList(ar.length - 2);
          m.put(i, l);
        }
        for (int j = 2; j < ar.length; j++)
          l.add(ar[j]);
        n_values++;
        read++;
        if (tm.enoughTimePassed(5)) {
          Log.info("read " + read + " lines, nStrKeys=" + str2int2uuids.size()
            + "\t" + Describe.memoryUsage());
        }
      }
    }
    Log.info("done, read " + read + " lines from " + f.getPath());
  }
  
  /**
   * Reads lines with the format:
   * <int> (<tab> <uuid>)+
   * and applies the given string to all entries.
   * 
   * NOTE: This is a put not add method, if there are other values associated with
   * the given string, they will be over-written.
   */
  public void putIntLines(String string, File intUuidLines) throws IOException {
    Log.info("reading " + string + " lines from " + intUuidLines.getPath());
    timer.start("load/" + string);
    TimeMarker tm = new TimeMarker();
    IntObjectHashMap<EfficientUuidList> m = new IntObjectHashMap<>();
    try (BufferedReader r = FileUtil.getReader(intUuidLines)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        // term, comm_uuid+
        String[] ar = line.split("\t");
        assert ar.length >= 2;
        int term = Integer.parseUnsignedInt(ar[0]);
        EfficientUuidList uuids = new EfficientUuidList(ar.length-1);
        for (int i = 1; i < ar.length; i++)
          uuids.add(ar[i]);
        Object old = m.put(term, uuids);
        assert old == null;
        n_values++;

        if (tm.enoughTimePassed(5)) {
          Log.info("n_mentions=" + n_values + " f=" + intUuidLines.getPath()
          + " c=" + m.size() + "\t" + Describe.memoryUsage());
        }
      }
    }
    str2int2uuids.put(string, m);
  }

  /**
   * Reads many files with a string provided where each line is of the format:
   * <int> (<tab> <uuid>)+
   */
  public static StringIntUuidIndex buildFromStringFiles(List<Pair<String, File>> featuresByNerType) throws IOException {
    StringIntUuidIndex s = new StringIntUuidIndex();
    for (Pair<String, File> x : featuresByNerType) {
      s.putIntLines(x.get1(), x.get2());
    }
    return s;
  }

  public List<String> get(String stringCoarse, int intFine) {
    IntObjectHashMap<EfficientUuidList> t2m = str2int2uuids.get(stringCoarse);
    if (t2m == null) {
      return Collections.emptyList();
    }
    EfficientUuidList mentions = t2m.get(intFine);
    if (mentions == null) {
      return Collections.emptyList();
    }
    int n = mentions.size();
    List<String> l = new ArrayList<>(n);
    for (int i = 0; i < n; i++)
      l.add(mentions.getString(i));
    return l;
  }
  
  /**
   * Note, this may not be as fast as the bulk add methods from lines of a file.
   */
  public void add(String stringCoarse, int intFine, String uuid) {
    
    throw new RuntimeException("implement me");
  }
  
  @Override
  public String toString() {
    return "(StringIntUuidIndex " + valuesPerString
      + " n=" + n_values
      + ")";
  }

}
