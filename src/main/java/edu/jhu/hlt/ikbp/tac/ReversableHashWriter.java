package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map.Entry;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;

/**
 * Gives you constant time access to a String -> int method,
 * while writing out the reverse int -> String+ mapping to
 * disk, so you can approximately invert this almost-bijection.
 * 
 * NOTE: You will need to use sort -u to deduplicate the output of this class.
 *
 * @author travis
 */
public class ReversableHashWriter implements Closeable {

  public static final Charset UTF8 = Charset.forName("UTF-8");
  public static final HashFunction HASH = Hashing.murmur3_32();

  /**
   * Use this if you don't need to reverse the mapping.
   */
  public static int onewayHash(String t) {
    return HASH.hashString(t, UTF8).asInt();
  }

  // Given a word, if it's not in this approx-set, then you definitely haven't seen it
  private WrittenOut seenTerms;
  private BufferedWriter w_termHash;      // hashedTerm, term
  private int n_termHashes = 0;
  private int n_termWrites = 0;
  
  public ReversableHashWriter(File writeInverseMappingTo) throws IOException {
    this(writeInverseMappingTo, 1<<14, 1<<22);
  }

  public ReversableHashWriter(File writeInverseMappingTo, int recentLim, int commonLim) throws IOException {
    seenTerms = new WrittenOut(recentLim, commonLim);
    w_termHash = FileUtil.getWriter(writeInverseMappingTo);
  }

  public int hash(String t) {
    n_termHashes++;
    int i = HASH.hashString(t, UTF8).asInt();
    if (seenTerms.add(t)) {
      n_termWrites++;
      try {
        w_termHash.write(Integer.toUnsignedString(i));
        w_termHash.write('\t');
        w_termHash.write(t);
        w_termHash.newLine();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    return i;
  }

  @Override
  public void close() throws IOException {
    w_termHash.close();
    w_termHash = null;
  }
  
  @Override
  public String toString() {
    return "(RevHash writes=" + n_termWrites + " hashes=" + n_termHashes + ")";
  }

  /**
   * Approximate set.
   */
  private static class WrittenOut {
    Counts<String> common, recent;
    int commonLim, recentLim;
    
    public WrittenOut(int recentLim, int commonLim) {
      this.recentLim = recentLim;
      this.commonLim = commonLim;
      this.recent = new Counts<>();
      this.common = new Counts<>();
    }
    
    public boolean add(String t) {
      if (common.getCount(t) >= 1)
        return false;
      if (recent.getCount(t) >= 1)
        return false;
      
      recent.increment(t);
      
      // COMPACT
      if (recent.numNonZero() > recentLim) {
        // Merge recent into common
        for (Entry<String, Integer> tt : recent.entrySet())
          common.update(tt.getKey(), tt.getValue());
        recent.clear();
        
        // Trim common
        if (common.numNonZero() > commonLim) {
          Counts<String> c = new Counts<>(commonLim);
          for (String x : common.getKeysSortedByCount(true)) {
            int cc = Math.max(1, common.getCount(x) - 100);
            c.update(x, cc);
            if (c.numNonZero() == commonLim)
              break;
          }
          common = c;
        }
      }
      return true;
    }
  }
  
}
