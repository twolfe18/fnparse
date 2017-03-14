package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import com.google.common.hash.Hashing;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.StringUtils;
import edu.jhu.prim.tuple.Pair;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.PTBTokenizer;

/**
 * Represents a sentence like:
 *   test2 clueweb09-freebase-annotation $ zcat extractedAnnotation/ClueWeb09_English_2/en0012/00.gz | head -n 1
 *   Raja Annamalai Mandram, [FREEBASE mid=/m/058z43 Parrys]Parrys[/FREEBASE], [FREEBASE mid=/m/0c8tk Chennai]Chennai[/FREEBASE] between 10am to 6pm .
 *
 * @author travis
 */
public class CluewebLinkedSentence {

  static final Charset UTF8 = Charset.forName("UTF-8");
  
  public static class Link {
    static final String tail = "[/FREEBASE]";
    static final String pre = "[FREEBASE mid=";
    
    int hstart;   // index of '[' in "[FREEBASE mid=..."
    int mstart;   // index of first char in text
    int mend;     // index of last char in text+1
//    byte[] mid;
    boolean valid;
    
    public Link(String source, int hstart) {
      this.valid = false;
      this.hstart = hstart;
      this.mstart = source.indexOf(']', hstart+1) + 1;
      if (mstart > 0) {
        this.mend = source.indexOf('[', mstart + 1);
        if (mend < 0) {
//          Log.info("WARNING: no closing tag on: " + source.substring(hstart));
          mend = source.length();
        } else {
//          valid = true;
          valid = source.indexOf(tail, mend) == tend()-tail.length();
        }
        assert 0 <= hstart && hstart < mstart && mstart < mend;
        assert source.indexOf(pre, hstart) == hstart;
      }
    }

    /** return the index of ']' in "[/FREEBASE]" */
    public int tend() {
      return mend + tail.length();
    }
    
    public String getMention(String markup) {
      return markup.substring(mstart, mend);
    }
    
    public String getMid(String markup) {
      int s = hstart + pre.length();
      int e = markup.indexOf(' ', s);
      return markup.substring(s, e);
    }
    
    @Override
    public String toString() {
//      return "(Link " + Arrays.toString(mid) + " " + hstart + "-" + mstart + "-" + mend + ")";
      return "(Link " + hstart + "-" + mstart + "-" + mend + ")";
    }
  }
  
  /**
   * Reads lines in a file and skips over invalid ones.
   */
  public static class ValidatorIterator implements Iterator<CluewebLinkedSentence>, AutoCloseable {
    private BufferedReader r;
    private CluewebLinkedSentence cur;
    private int maxSentenceLength;
    private int linesRead, validLines;
    
    public ValidatorIterator(File f) throws IOException {
      this(f, 0);
    }
    public ValidatorIterator(File f, int maxSentenceLength) throws IOException {
      Log.info("f=" + f.getPath() + " maxSentenceLength=" + maxSentenceLength);
      r = FileUtil.getReader(f);
      this.maxSentenceLength = maxSentenceLength;
      advance();
    }
    
    public List<CluewebLinkedSentence> toList() {
      List<CluewebLinkedSentence> l = new ArrayList<>();
      while (hasNext())
        l.add(next());
      return l;
    }
    
    private void advance() {
      cur = null;
      while (cur == null) {
        try {
          String line = r.readLine();
          if (line == null)
            break;
          linesRead++;
          cur = new CluewebLinkedSentence(line);
          if (!cur.allLinksValid)
            cur = null;
          if (maxSentenceLength > 0 && cur.getTextTokenizedNumTokens() > maxSentenceLength)
            cur = null;
        } catch (Exception e) {
          cur = null;
        }
      }
      if (cur != null)
        validLines++;
      
      if (validLines % 25000 == 0) {
        Log.info(String.format("valid=%d\tread=%d\t%.2f%% invalid",
            validLines, linesRead, 100d * (1 - ((double) validLines)/linesRead)));
      }
    }

    @Override
    public boolean hasNext() {
      return cur != null;
    }

    @Override
    public CluewebLinkedSentence next() {
      CluewebLinkedSentence c = cur;
      advance();
      return c;
    }
    
    @Override
    public void close() throws IOException {
      r.close();
    }
  }
  
  // TODO just store byte[] utf8?
  private String markup;
  private Link[] links;
  private boolean allLinksValid;
  private byte[] hash;    // 128 bits, aka UUID size
  
  public CluewebLinkedSentence(String markup) {
    this.markup = markup;
    List<Link> links = new ArrayList<>();
    for (int offset = markup.indexOf("[FREEBASE "); offset >= 0; offset = markup.indexOf("[FREEBASE ", offset+1)) {
      links.add(new Link(markup, offset));
    }
    this.allLinksValid = true;
    this.links = new Link[links.size()];
    for (int i = 0; i < this.links.length; i++) {
      this.links[i] = links.get(i);
      this.allLinksValid &= this.links[i].valid;
    }
  }
  
  public <T extends Collection<String>> T getAllMids(T addTo) {
    for (int i = 0; i < links.length; i++)
      addTo.add(links[i].getMid(markup));
    return addTo;
  }

  public <T extends Collection<String>> T getAllWords(T addTo, boolean lowercase) {
    for (SegmentedTextAroundLink st : getTextTokenized()) {
      for (String t : st.allTokens()) {
        if (lowercase)
          t = t.toLowerCase();
        addTo.add(t);
      }
    }
    return addTo;
  }
  
  public byte[] hash() {
    if (hash == null) {
//      hash = Hashing.murmur3_128().hashString(markup, UTF8).asBytes();
      hash = Hashing.sha256().hashString(markup, UTF8).asBytes();
      assert hash.length == 32;
      hash = Arrays.copyOf(hash, 16);
      assert hash.length == 16;
    }
    return hash;
  }
  
  public UUID hashUuid() {
    ByteBuffer bb = ByteBuffer.wrap(hash());
    long hi = bb.getLong();
    long lo = bb.getLong();
    return new UUID(hi, lo);
  }
  
  public String hashHex() {
    StringBuilder sb = new StringBuilder();
    byte[] h = hash();
    for (int i = 0; i < h.length; i++)
      sb.append(String.format("%02x", h[i]));
    return sb.toString();
  }
  
  public String getMarkup() {
    return markup;
  }
  
  static class SegmentedText {
    int origStartGlobal;
    String orig;
    List<String> toks;
    List<IntPair> tokLocalOffsets;
    
    public SegmentedText(String orig, int offset) {
      this.origStartGlobal = offset;
      this.orig = orig;
      this.toks = new ArrayList<>();
      this.tokLocalOffsets = new ArrayList<>();
      String options = null;
      PTBTokenizer<CoreLabel> tok = new PTBTokenizer<>(new StringReader(orig), new CoreLabelTokenFactory(), options);
      while (tok.hasNext()) {
        CoreLabel cl = tok.next();
        toks.add(cl.word());
        IntPair ij = new IntPair(cl.beginPosition(), cl.endPosition() + 1);
        tokLocalOffsets.add(ij);
      }
    }
    
    public int numTokens() {
      return toks.size();
    }
    
    public IntPair getCharsLocal(int i) {
      return tokLocalOffsets.get(i);
    }
    
    public IntPair getCharsGlobal(int i) {
      IntPair ij = tokLocalOffsets.get(i);
      return new IntPair(ij.first + origStartGlobal, ij.second + origStartGlobal);
    }
  }
  
  class SegmentedTextAroundLink {
    int charStart;            // characters which appear before this segment
    int tokStart;             // tokens which appear before this segment
    int linkIdx;              // <0 iff the last one
    SegmentedText outside;    // text before link unless link not specified, then this is after the last link
    SegmentedText inside;     // text within the link (if present)
    
    public SegmentedTextAroundLink(int charStart, int tokStart, int linkIdx) {
      this.charStart = charStart;
      this.tokStart = tokStart;
      this.linkIdx = linkIdx;
      if (linkIdx < 0) {
        String t = markup.substring(charStart);
        this.outside = new SegmentedText(t, charStart);
        this.inside = null;
      } else {
        Link l = links[linkIdx];
        String t = markup.substring(charStart, l.hstart);
        this.outside = new SegmentedText(t, charStart);
        this.inside = new SegmentedText(l.getMention(markup), l.mstart);
      }
    }
    
    public boolean hasLink() {
      return linkIdx >= 0;
    }
    
    public String getMid() {
      return getLink().getMid(markup);
    }
    
    public Link getLink() {
      if (linkIdx < 0)
        return null;
      return links[linkIdx];
    }
    
    public IntPair getTokLoc() {
      int start = tokStart + outside.numTokens();
      int end = start + inside.numTokens();
      return new IntPair(start, end);
    }
    
    public List<String> allTokens() {
      if (linkIdx < 0)
        return outside.toks;
      List<String> t = new ArrayList<>();
      t.addAll(outside.toks);
      t.addAll(inside.toks);
      return t;
    }
    
    public List<Pair<Integer, String>> getLinkTokensGlobalIndexed() {
      List<Pair<Integer, String>> l = new ArrayList<>();
      for (int i = 0; i < inside.numTokens(); i++) {
        int t = tokStart + i;
        String w = inside.toks.get(i);
        l.add(new Pair<>(t, w));
      }
      return l;
    }
  }
  
  /**
   * Forces the tokenizer to work around the mentions in the markup.
   */
  public List<SegmentedTextAroundLink> getTextTokenized() {
    List<SegmentedTextAroundLink> foo = new ArrayList<>();
    int preChar = 0;
    int preTok = 0;
    for (int i = 0; i < links.length; i++) {
      SegmentedTextAroundLink st = new SegmentedTextAroundLink(preChar, preTok, i);
      foo.add(st);
      preChar = links[i].tend();
      preTok += st.allTokens().size();
    }
    foo.add(new SegmentedTextAroundLink(preChar, preTok, -1));
    return foo;
  }
  
  public int getTextTokenizedNumTokens() {
    int nt = 0;
    for (SegmentedTextAroundLink st : getTextTokenized())
      nt += st.allTokens().size();
    return nt;
  }
  
  public String getResultsHighlighted(String mid) {
    StringBuilder sb = new StringBuilder();
    List<SegmentedTextAroundLink> segs = getTextTokenized();
    for (SegmentedTextAroundLink seg : segs) {
      if (seg.linkIdx < 0) {
        // Last part
        sb.append(' ');
        sb.append(StringUtils.join(" ", seg.allTokens()));
      } else {
        if (seg.linkIdx > 0)
          sb.append(' ');
        // Pre
        sb.append(StringUtils.join(" ", seg.outside.toks));
        // Inside
        sb.append(' ');
        String tag = mid.equals(seg.getMid()) ? "ENT" : "OTHER";
        sb.append("<" + tag + ">");
        sb.append(StringUtils.join(" ", seg.inside.toks));
        sb.append("</" + tag + ">");
      }
    }
    return sb.toString();
  }
  
  public List<String> getMentionStrings(String mid) {
    List<String> s = new ArrayList<>();
    for (int i = 0; i < links.length; i++)
      if (mid.equals(links[i].getMid(markup)))
        s.add(links[i].getMention(markup));
    return s;
  }
  
  public String getText() {
    StringBuilder sb = new StringBuilder();

    // Text before link(i) forall i
    int pre = 0;
    for (int i = 0; i < links.length; i++) {
      Link x = links[i];
      sb.append(markup.substring(pre, x.hstart));   // before
      sb.append(x.getMention(markup));              // inside
      pre = x.tend();
    }
    
    // Text after last link
    int last = links.length - 1;
    if (last >= 0)
      sb.append(markup.substring(links[last].tend()));
    
    return sb.toString();
  }
  
  public int indexOfMid(String mid) {
    return indexOfMid(mid, 0);
  }
  public int indexOfMid(String mid, int from) {
    for (int i = from; i < links.length; i++) {
      String m = links[i].getMid(markup);
      if (mid.equals(m))
        return i;
    }
    return -1;
  }
  
  public int numLinks() {
    return links.length;
  }
  
  public Link getLink(int i) {
    return links[i];
  }
  
  @Override
  public String toString() {
    return "(CWLinkSent nLink=" + links.length + " nChar=" + markup.length() + ")";
  }
  
  public static void testHashing() {
    String markup = "04/19/09 7:00p [FREEBASE mid=/m/01mjjvk Garrison Keillor]Garrison Keillor[/FREEBASE] - [FREEBASE mid=/m/0gly1 A Prairie Home Companion]A Prairie Home Companion[/FREEBASE] .";
    CluewebLinkedSentence sent = new CluewebLinkedSentence(markup);
    
    byte[] h1 = sent.hash();
    String h2 = sent.hashHex();
    UUID h3 = sent.hashUuid();
    System.out.println(Arrays.toString(h1));
    System.out.println(h2);
    System.out.println(h3.toString());
  }
  
  public static void main(String[] args) {
    ExperimentProperties config = ExperimentProperties.init(args);

    if (config.getBoolean("test", true)) {
      testHashing();
      return;
    }

    String t = "Raja Annamalai Mandram, [FREEBASE mid=/m/058z43 Parrys]Parrys[/FREEBASE], [FREEBASE mid=/m/0c8tk Chennai]Chennai[/FREEBASE] between 10am to 6pm .";
    CluewebLinkedSentence sent = new CluewebLinkedSentence(t);
    Log.info(sent);
    Log.info(sent.getMarkup());
    Log.info(sent.getText());
    int n = sent.numLinks();
    Log.info("n=" + n);
    for (int i = 0; i < n; i++) {
      Link x = sent.getLink(i);
      Log.info("link(" + i + "): " + x
        + " " + x.getMention(sent.getMarkup())
        + " " + x.getMid(sent.getMarkup()));
    }
  }

}
