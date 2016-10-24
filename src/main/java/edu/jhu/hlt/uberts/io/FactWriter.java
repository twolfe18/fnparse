package edu.jhu.hlt.uberts.io;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * Parent class of classes which write out facts.
 *
 * The write method intentionally doesn't take a thing to write to so that can
 * be configured separately with methods like writeToFile(File).
 *
 * @author travis
 */
public class FactWriter implements Closeable {

  private BufferedWriter w;
  
  // Every startdoc constructs a new RelDoc which is pushed onto this stack.
  // All facts after that are added to whatever is on the top of the stack.
  private Deque<RelDoc> docs;

  public FactWriter(File f) {
    writeToFile(f);
  }

  public FactWriter() {
    docs = new ArrayDeque<>();
  }

  public RelDoc popDoc() {
    return docs.pop();
  }

  public void writeToFile(File f) {
    Log.info("writing to " + f.getPath());
    try {
      w = FileUtil.getWriter(f);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    docs = null;
  }

  public void writeRelLine(RelLine rl) throws IOException {
    if (docs != null) {
      if (rl.tokens[0].equals("startdoc")) {
        RelDoc d = new RelDoc(rl);
        docs.push(d);
      } else {
        docs.peek().items.add(rl);
      }
    } else {
      for (int i = 0; i < rl.tokens.length; i++) {
        if (i > 0)
          w.write(' '); // could replace with tab
        w.write(norm(rl.tokens[i].toString()));
      }
      w.newLine();
    }
  }

  public void write(Object... tokens) throws IOException {
    if (docs != null) {
      String[] tkz = new String[tokens.length];
      for (int i = 0; i < tkz.length; i++)
        tkz[i] = norm(tokens[i].toString());
      writeRelLine(new RelLine(tkz, null));
    } else {
      for (int i = 0; i < tokens.length; i++) {
        if (i > 0)
          w.write(' '); // could replace with tab
        w.write(norm(tokens[i].toString()));
      }
      w.newLine();
    }
  }

  @Override
  public void close() throws IOException {
    if (w != null)
      w.close();
  }

  public static String norm(String x) {
    x = x.trim();
    x = x.replaceAll("\\s+", "_");
    x = x.replaceAll("#", "-HASH-");
    return x;
  }
}
