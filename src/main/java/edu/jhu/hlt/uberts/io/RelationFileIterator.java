package edu.jhu.hlt.uberts.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

public class RelationFileIterator implements Iterator<RelLine>, AutoCloseable {

  public static class RelLine implements Serializable {
    private static final long serialVersionUID = 2244299116603856L;

    public final String[] tokens;
    public final String comment;    // may be null if there was no comment

    public RelLine(String[] tokens, String comment) {
      this.tokens = tokens;
      this.comment = comment;
    }

    public String toLine() {
      StringBuilder sb = new StringBuilder(tokens[0]);
      for (int i = 1; i < tokens.length; i++) {
        sb.append(' ');
        sb.append(tokens[i]);
      }
      if (comment != null) {
        sb.append(" # ");
        sb.append(comment);
      }
      return sb.toString();
    }

    @Override
    public String toString() {
      if (comment != null)
        return "<RelLine " + Arrays.toString(tokens) + " # " + comment + " >";
      return "<RelLine " + Arrays.toString(tokens) + ">";
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

  public RelationFileIterator(File f) throws IOException {
    this.file = f;
    this.reader = FileUtil.getReader(f);
    next();
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

  @Override
  public RelLine next() {
    String line;
    RelLine n = next;
    try {
      for (line = reader.readLine(), next = null;
          line != null && next == null;
          line = reader.readLine()) {
        String comment = null;
        int c = line.indexOf('#');
        if (c >= 0) {
          comment = line.substring(c + 1, line.length());
          line = line.substring(0, c).trim();
        }
        if (line.isEmpty())
          continue;
        String[] tokens = line.split(" ");
        next = new RelLine(tokens, comment == null ? null : comment.trim());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return n;
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }

}
