package edu.jhu.hlt.uberts.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
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

  public RelationFileIterator(File f, boolean includeProvidence) throws IOException {
    Log.info("includeProvidence=" + includeProvidence + " f=" + f.getPath());
    this.includeProvidence = includeProvidence;
    this.file = null;
    this.reader = FileUtil.getReader(f);
    this.lineNo = -1;
    next();
  }
  public RelationFileIterator(InputStream is) throws IOException {
    this.includeProvidence = false;
    this.file = null;
    this.reader = new BufferedReader(new InputStreamReader(is));
    this.lineNo = -1;
    next();
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
