package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.entsum.CluewebLinkedPreprocess.PrepareSentencesForParsey;
import edu.jhu.hlt.ikbp.tac.ComputeIdf;
import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.MultiAlphabet;
import edu.jhu.hlt.tutils.MultiTimer;
import edu.jhu.hlt.tutils.Span;

/**
 * Short for "efficient sentence"
 *
 * @author travis
 */
public class EffSent implements Serializable {
  private static final long serialVersionUID = -7935126113889559888L;
  public static final Charset UTF8 = Charset.forName("UTF8");
  
  public static class Mention implements Serializable {
    private static final long serialVersionUID = 7528881636006561742L;
    public byte[] mid;   // UTF-8 encoded, everything after "/m/"
    public short head;   // token index
    public short start;  // inclusive token index
    public short end;    // exclusive token index
    
    public void setSpan(Span s) {
      if (s.start > Short.MAX_VALUE || s.start < 0)
        throw new IllegalArgumentException();
      if (s.end > Short.MAX_VALUE || s.end < 0)
        throw new IllegalArgumentException();
      start = (short) s.start;
      end = (short) s.end;
    }
    
    public Span span() {
      return Span.getSpan(start, end);
    }
    
    public String getFullMid() {
      return "/m/" + new String(mid, UTF8);
    }
    public String getAbbrMid() {
      return new String(mid, UTF8);
    }
    
    @Override
    public String toString() {
      return "(" + getFullMid() + " " + span().shortString() + " h=" + head + ")";
    }
  }
  
  private DepNode[] parse;
  private Mention[] entities;
  
  public EffSent(DepNode[] parse) {
    this.parse = parse;
  }
  
  public DepNode[] parse() {
    return parse;
  }
  public DepNode parse(int i) {
    return parse[i];
  }

  void setMentions(String mentionLine) {
    this.entities = parseMentions(mentionLine);
  }
  
  public Mention mention(int i) {
    return entities[i];
  }
  
  public int numMentions() {
    return entities.length;
  }
  
  public Iterable<Mention> mentions() {
    return Arrays.asList(entities);
  }
  
  /**
   * Reads the format created by {@link PrepareSentencesForParsey},
   * namely: (<midWithSlashMSlash> <mentionSpanStart>-<mentionSpanEnd>)*
   */
  public static Mention[] parseMentions(String mentionLine) {
    String[] a = mentionLine.split("\\s+");
    if (a.length % 2 != 0)
      throw new IllegalArgumentException("mentionLine=" + mentionLine);
    Mention[] entities = new Mention[a.length/2];
    for (int i = 0; i < entities.length; i++) {
      String mid = a[2*i + 0];
      Span s = Span.inverseShortString(a[2*i + 1]);
      
      if (mid.startsWith("/m/"))
        mid = mid.substring(3);
      
      entities[i] = new Mention();
      entities[i].head = -1;
      entities[i].mid = mid.getBytes(UTF8);
      entities[i].setSpan(s);
    }
    return entities;
  }
  
  public void showConllStyle(MultiAlphabet a) {
    String[] mids = new String[parse.length];
    Arrays.fill(mids, "_");
    for (int i = 0; i < entities.length; i++) {
      for (int j = entities[i].start; j < entities[i].end; j++) {
        mids[j] = entities[i].getFullMid();
        if (j == entities[i].head)
          mids[j] += " *head";
      }
    }
    
    for (int i = 0; i < parse.length; i++) {
      System.out.printf("% 4d  %-16s %s\n", i, a.word(parse[i].word), mids[i]);
    }
  }

  /**
   * The reason I want this is that there appears to be duplicates still getting through, e.g.
   *   tokenized-sentences/dev/m.017kjq/sentences.txt
   *   2000 ([FREEBASE mid=/m/0hsqf Seoul]Seoul[/FREEBASE]-[FREEBASE mid=/m/017kjq Yonhap News Agency]Yonhap News Agency[/FREEBASE]
   *   2000 ([FREEBASE mid=/m/0hsqf Seoul]Seoul[/FREEBASE]-[FREEBASE mid=/m/017kjq Yonhap News Agency]Yonhap News Agency[/FREEBASE]).
   *
   * I think the key where I just have ordered-mids is not good enough, especially when there are only 2 mids for example.
   * I think a better key to dedup on is:
   *   <orderedMids> ++ <threeHighestIdfWordsInSentenceOrder>
   */
  public static class DedupMaW3Iter implements Iterator<EffSent>, AutoCloseable {
    private Iter wrapped;
    private ComputeIdf df;
    private int numWords;
    private Set<String> seen;
    private EffSent cur;
    private int total, skipped;
    
    public DedupMaW3Iter(Iter wrapped, ComputeIdf df, int numWordsInKey) {
      this.wrapped = wrapped;
      this.df = df;
      this.numWords = numWordsInKey;
      this.seen = new HashSet<>();
      total = skipped = 0;
      advance();
    }
    
    static class WM {
      int tokenIdx;
      double idf;
      public WM(int tok, double idf) {
        this.tokenIdx = tok;
        this.idf = idf;
      }
      public static final Comparator<WM> BY_IDF_DESC = new Comparator<WM>() {
        @Override
        public int compare(WM o1, WM o2) {
          if (o1.idf > o2.idf)
            return -1;
          if (o2.idf > o1.idf)
            return +1;
          return 0;
        }
      };
      public static final Comparator<WM> BY_TOKEN_ASC = new Comparator<WM>() {
        @Override
        public int compare(WM o1, WM o2) {
          if (o1.tokenIdx < o2.tokenIdx)
            return -1;
          if (o2.tokenIdx < o1.tokenIdx)
            return +1;
          return 0;
        }
      };
    }
    
    public String computeKey(EffSent s) {
      
      // Find the ordered set of mids
      BitSet midTokens = new BitSet();
      StringBuilder key = new StringBuilder();
      int n = cur.numMentions();
      for (int i = 0; i < n; i++) {
        Mention m = cur.mention(i);
        String mid = m.getAbbrMid();
        if (i > 0)
          key.append('_');
        key.append(mid);
        
        for (int t = m.start; t < m.end; t++)
          midTokens.set(t);
      }

      // Find the k-highest-idf words (which aren't inside an mention)
      List<WM> words = new ArrayList<>();
      MultiAlphabet a = wrapped.getParseAlph();
      for (int i = 0; i < cur.parse.length; i++) {
        if (midTokens.get(i))
          continue;
        String w = a.word(cur.parse[i].word);
        double idf = df.idf(w);
        words.add(new WM(i, idf));
      }
      Collections.sort(words, WM.BY_IDF_DESC);
      while (words.size() > numWords)
        words.remove(words.size()-1);
      Collections.sort(words, WM.BY_TOKEN_ASC);
      for (WM t : words) {
        String w = a.word(cur.parse[t.tokenIdx].word);
        key.append('#');
        key.append(w);
      }
      
      return key.toString();
    }
    
    private void advance() {
      boolean disable = false;
      cur = null;
      while (wrapped.hasNext()) {
        total++;
        if (total % 1000 == 0)
          Log.info("skipped=" + skipped + " total=" + total + " uniq=" + seen.size());
        cur = wrapped.next();
        if (disable || seen.add(computeKey(cur)))
          break;
        cur = null;
        skipped++;
      }
    }

    @Override
    public void close() throws IOException {
      Log.info("skipped=" + skipped + " total=" + total + " uniq=" + seen.size());
      wrapped.close();
    }

    @Override
    public boolean hasNext() {
      return cur != null;
    }

    @Override
    public EffSent next() {
      EffSent c = cur;
      advance();
      return c;
    }
  }
  
  public static class Iter implements Iterator<EffSent>, AutoCloseable {
    private DepNode.ConllxFileReader iter;
    private BufferedReader r;
    private EffSent cur;
    private MultiAlphabet parseAlph;
    
    public Iter(File parses, File mentions, MultiAlphabet a) throws IOException {
      Log.info("parses=" + parses.getPath() + " mentions=" + mentions.getPath());
      iter = new DepNode.ConllxFileReader(parses, a);
      r = FileUtil.getReader(mentions);
      this.parseAlph = a;
      advance();
    }
    
    public MultiAlphabet getParseAlph() {
      return parseAlph;
    }

    private void advance() throws IOException {
      DepNode[] parse = iter.next();
      String ms = r.readLine();
      cur = new EffSent(parse);
      cur.setMentions(ms);
    }

    @Override
    public void close() throws IOException {
      iter.close();
      r.close();
    }

    @Override
    public boolean hasNext() {
      return iter.hasNext();
    }

    @Override
    public EffSent next() {
      EffSent c = cur;
      try {
        advance();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      return c;
    }
  }
  
  /**
   * Raw/strings is twice as fast as jser!
   *
   * # no gzip
   * write/jser/array     <Timer write/jser/array 11.56 sec and 20 calls total, log(sec/call)=-0.5 sec/call=0.582>
   * read/jser/list       <Timer read/jser/list 7.08 sec and 20 calls total, log(sec/call)=-1.0 sec/call=0.352>
   * read/jser/array      <Timer read/jser/array 6.58 sec and 20 calls total, log(sec/call)=-1.1 sec/call=0.331>
   * read/raw             <Timer read/raw 3.51 sec and 20 calls total, log(sec/call)=-1.8 sec/call=0.159>
   * write/jser/alph      <Timer write/jser/alph 2.45 sec and 20 calls total, log(sec/call)=-2.1 sec/call=0.119>
   * read/jser/alph       <Timer read/jser/alph 1.26 sec and 20 calls total, log(sec/call)=-2.8 sec/call=0.058>
   * total: 44.771
   * 
   * jser does lead to smaller files though:
   * 
   * caligula fnparse $ du -sch data/facc1-entsum/code-testing-data/m.01ztq_/debug-out/*
   * 3.2M    data/facc1-entsum/code-testing-data/m.01ztq_/debug-out/eff-sents.array.jser
   * 3.2M    data/facc1-entsum/code-testing-data/m.01ztq_/debug-out/eff-sents.list.jser
   * 384K    data/facc1-entsum/code-testing-data/m.01ztq_/debug-out/parse-alph.jser
   * 6.8M    total
   * caligula fnparse $ du -sch data/facc1-entsum/code-testing-data/m.01ztq_/parse.conll data/facc1-entsum/code-testing-data/m.01ztq_/mentionLocs.txt 
   * 4.6M    data/facc1-entsum/code-testing-data/m.01ztq_/parse.conll
   * 256K    data/facc1-entsum/code-testing-data/m.01ztq_/mentionLocs.txt
   * 4.8M    total
   * 
   * gzip makes it a little closer though:
   * 
   * # gzipOut=true gzipIn=false
   * write/jser/array     <Timer write/jser/array 8.84 sec and 20 calls total, log(sec/call)=-0.8 sec/call=0.443>
   * read/jser/list       <Timer read/jser/list 4.90 sec and 20 calls total, log(sec/call)=-1.4 sec/call=0.238>
   * read/jser/array      <Timer read/jser/array 4.82 sec and 20 calls total, log(sec/call)=-1.4 sec/call=0.241>
   * read/raw             <Timer read/raw 3.35 sec and 20 calls total, log(sec/call)=-1.9 sec/call=0.151>
   * write/jser/alph      <Timer write/jser/alph 1.87 sec and 20 calls total, log(sec/call)=-2.4 sec/call=0.088>
   * read/jser/alph       <Timer read/jser/alph 0.97 sec and 20 calls total, log(sec/call)=-3.2 sec/call=0.042>
   * total: 34.18
   * 
   * gzip on the input side uses 5x less space in the same time:
   * 
   * write/jser/array     <Timer write/jser/array 8.95 sec and 20 calls total, log(sec/call)=-0.8 sec/call=0.447>
   * read/jser/list       <Timer read/jser/list 5.02 sec and 20 calls total, log(sec/call)=-1.4 sec/call=0.246>
   * read/jser/array      <Timer read/jser/array 4.94 sec and 20 calls total, log(sec/call)=-1.4 sec/call=0.247>
   * read/raw             <Timer read/raw 3.54 sec and 20 calls total, log(sec/call)=-1.8 sec/call=0.159>
   * write/jser/alph      <Timer write/jser/alph 1.91 sec and 20 calls total, log(sec/call)=-2.4 sec/call=0.088>
   * read/jser/alph       <Timer read/jser/alph 1.03 sec and 20 calls total, log(sec/call)=-3.1 sec/call=0.046>
   * total: 34.674
   * 
   * caligula m.01ztq_ $ du -sch parse.conll mentionLocs.txt
   * 4.6M    parse.conll
   * 256K    mentionLocs.txt
   * 4.8M    total
   * caligula m.01ztq_ $ du -sch parse.conll.gz mentionLocs.txt.gz 
   * 872K    parse.conll.gz
   * 64K     mentionLocs.txt.gz
   * 936K    total
   */
  public static void benchmark(ExperimentProperties config) throws Exception {
    
    int times = config.getInt("times", 20);
    boolean gzipOut = config.getBoolean("gzipOut", true);
    boolean gzipIn = config.getBoolean("gzipIn", true);
    
    FileUtil.VERBOSE = true;

    String suf = gzipOut ? ".jser.gz" : ".jser";
    File p = new File("data/facc1-entsum/code-testing-data/m.01ztq_/");
    File conll = new File(p, "parse.conll" + (gzipIn ? ".gz" : ""));
    File mentions = new File(p, "mentionLocs.txt" + (gzipIn ? ".gz" : ""));
    MultiTimer t = new MultiTimer();
    for (int i = 0; i < times; i++) {
      Log.info("starting iter=" + i);

      // Build
      MultiAlphabet a = new MultiAlphabet();
      List<EffSent> sentences = new ArrayList<>();
      t.start("read/raw");
      try (DepNode.ConllxFileReader iter = new DepNode.ConllxFileReader(conll, a);
          BufferedReader r = FileUtil.getReader(mentions)) {
        while (iter.hasNext()) {
          DepNode[] parse = iter.next();
          String ms = r.readLine();
          EffSent s = new EffSent(parse);
          s.setMentions(ms);
          sentences.add(s);
        }
      }
      t.stop("read/raw");
      Log.info("read " + sentences.size() + " sentences");

      // Save
      File out = new File(p, "debug-out");
      out.mkdirs();
      t.start("write/jser/alph");
      FileUtil.serialize(a, new File(out, "parse-alph" + suf));
      t.stop("write/jser/alph");
      t.start("write/jser/list");
      FileUtil.serialize(sentences, new File(out, "eff-sents.list" + suf));
      t.stop("write/jser/list");
      t.start("write/jser/array");
      FileUtil.serialize(sentences.toArray(), new File(out, "eff-sents.array" + suf));
      t.stop("write/jser/array");

      // Read back
      t.start("read/jser/alph");
      FileUtil.deserialize(new File(out, "parse-alph" + suf));
      t.stop("read/jser/alph");
      t.start("read/jser/list");
      FileUtil.deserialize(new File(out, "eff-sents.list" + suf));
      t.stop("read/jser/list");
      t.start("read/jser/array");
      FileUtil.deserialize(new File(out, "eff-sents.array" + suf));
      t.stop("read/jser/array");

      Log.info(t);
    }
    Log.info("done");
  }

  public static void main(String[] args) throws Exception {
    ExperimentProperties config = ExperimentProperties.init(args);
    benchmark(config);
  }
}
