package edu.jhu.hlt.uberts.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.function.Predicate;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.HypNode;
import edu.jhu.hlt.uberts.NodeType;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.Uberts;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.prim.tuple.Pair;

public class RelationFileIterator implements Iterator<RelLine>, AutoCloseable {

  public static class RelLine implements Serializable {
    private static final long serialVersionUID = 2244299116603856L;

    public final String[] tokens;
    public final String comment;    // may be null if there was no comment
    public final String providence;

    public RelLine(String[] tokens, String comment) {
      this(tokens, comment, null);
    }
    public RelLine(String[] tokens, String comment, String providence) {
      this.tokens = tokens;
      this.comment = comment;
      this.providence = providence;
      for (int i = 0; i < tokens.length; i++) {
        assert tokens[i].indexOf('#') < 0;
        assert tokens[i].indexOf(' ') < 0;
      }
    }
    
    /**
     * Normally you would convert between {@link RelLine}s and
     * {@link HypEdge}s using an {@link Uberts}s instance which keeps
     * track of relations and objects. Use this ONLY if you are doing
     * simple IO.
     */
    public HypEdge buildEdgeWithoutInterning() {
      NodeType untyped = new NodeType("untyped"); // no types!

      HypNode[] tail = new HypNode[tokens.length - 2];
      for (int i = 0; i < tail.length; i++)
        tail[i] = new HypNode(untyped, tokens[i+2]);

      NodeType[] types = new NodeType[tail.length];
      Arrays.fill(types, untyped);

      Relation edgeType = new Relation(tokens[1], types);
      HypNode head = null;  // no head!
      HypEdge e = new HypEdge(edgeType, head, tail);
      return e;
    }
    
    public boolean commentContains(String substring) {
      if (comment != null)
        return comment.contains(substring);
      return false;
    }

    public String toLine() {
      return toLine(true);
    }

    public String toLine(boolean includeCommentIfPresent) {
      StringBuilder sb = new StringBuilder(tokens[0]);
      for (int i = 1; i < tokens.length; i++) {
        sb.append(' ');
        sb.append(tokens[i]);
      }
      if (includeCommentIfPresent && comment != null) {
        sb.append(" # ");
        sb.append(comment);
      }
      return sb.toString();
    }

    public void lookForIntsToIntern() {
      lookForIntsToIntern(100);
    }
    public void lookForIntsToIntern(int maxIntToIntern) {
      for (int i = 0; i < tokens.length; i++) {
        try {
          int x = Integer.parseInt(tokens[i]);
          if (Math.abs(x) <= maxIntToIntern)
            tokens[i] = String.valueOf(x).intern();
        } catch (NumberFormatException e) {
        }
      }
    }

    public void lookForShortStringsToIntern() {
      lookForIntsToIntern(4);   // This will get POS tags and short words
    }
    public void lookForShortStringsToIntern(int maxLengthToIntern) {
      for (int i = 0; i < tokens.length; i++)
        if (tokens[i].length() <= maxLengthToIntern)
          tokens[i] = tokens[i].intern();
    }

    @Override
    public String toString() {
      if (comment != null)
        return "<RelLine " + Arrays.toString(tokens) + " # " + comment + " >";
      return "<RelLine " + Arrays.toString(tokens) + ">";
    }

    public boolean isSchema() {
      return tokens[0].equals("schema");
    }
    public boolean isX() {
      return tokens[0].equals("x");
    }
    public boolean isY() {
      return tokens[0].equals("y");
    }
  }

  private File file;
  private BufferedReader reader;
  private RelLine next;
  private int lineNo;
  public boolean includeProvidence;
  private final Predicate<RelLine> skip;

  public RelationFileIterator(File f, boolean includeProvidence, Predicate<RelLine> skip) throws IOException {
    Log.info("includeProvidence=" + includeProvidence + " f=" + f.getPath());
    this.includeProvidence = includeProvidence;
    this.file = null;
    this.reader = FileUtil.getReader(f);
    this.lineNo = -1;
    this.skip = skip;
    next();
  }

  public RelationFileIterator(File f, boolean includeProvidence) throws IOException {
    this(f, includeProvidence, null);
  }

  public RelationFileIterator(InputStream is, Predicate<RelLine> skip) throws IOException {
    this.includeProvidence = false;
    this.file = null;
    this.reader = new BufferedReader(new InputStreamReader(is));
    this.lineNo = -1;
    this.skip = skip;
    next();
  }

  public RelationFileIterator(InputStream is) throws IOException {
    this(is, null);
  }

  @Override
  public String toString() {
    if (file == null)
      return "(RelFileItr null lineNo=" + lineNo + ")";
    return "(RelFileItr " + file.getPath() + " lineNo=" + lineNo + ")";
  }

  public File getFile() {
    return file;
  }

  public BufferedReader getInternalReader() {
    return reader;
  }

  @Override
  public boolean hasNext() {
    return next != null;
  }

  private String readLine() throws IOException {
    lineNo++;
    return reader.readLine();
  }

  @Override
  public RelLine next() {
    RelLine n = next;
    String line;
    try {
      next = null;
      while (next == null && (line = readLine()) != null) {
        Pair<String, String> lc = Uberts.stripComment2(line);
        line = lc.get1();
        String comment = lc.get2();
//        if (line.startsWith("x ") || line.startsWith("y ") || line.startsWith("schema ")) {
//          // Do not allow comments on data lines so that we don't have to worry
//          // about creating escape characters for #
//          comment = null;
//        } else {
//          int c = line.indexOf('#');
//          if (c >= 0) {
//            comment = line.substring(c + 1, line.length()).trim();
//            line = line.substring(0, c).trim();
//          } else {
//            comment = null;
//          }
//        }
        if (line.isEmpty())
          continue;
        String[] tokens = line.split(" ");
        String providence = null;
        if (includeProvidence)
          providence = "line " + lineNo + " of " + file.getPath();
        next = new RelLine(tokens, comment, providence);
        if (skip != null && skip.test(next))
          next = null;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return n;
  }

  @Override
  public void close() throws IOException {
    if (file != null) {
      Log.info("closing " + file.getPath());
      reader.close();
    }
    // If an InputStream was provided, then the onus is on the caller to close
  }

}
