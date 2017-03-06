package edu.jhu.hlt.entsum;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.google.common.hash.Hashing;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

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
    private int linesRead, validLines;
    
    public ValidatorIterator(File f) throws IOException {
      r = FileUtil.getReader(f);
      advance();
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
    sb.append(markup.substring(links[last].tend()));
    
    return sb.toString();
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
  
  public static void main(String[] args) {
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
