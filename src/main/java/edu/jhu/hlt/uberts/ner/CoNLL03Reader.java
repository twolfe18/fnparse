package edu.jhu.hlt.uberts.ner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import edu.jhu.hlt.tutils.Document;
import edu.jhu.hlt.tutils.Document.Constituent;
import edu.jhu.hlt.tutils.Document.Token;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.MultiAlphabet;

/**
 * Reads whitespace separated CoNLL 2003 NER files and puts them into a {@link Document}.
 *
 * For now it looks for "-DOCSTART- -X- O O" and enters in many sentences per doc.
 *
 * TODO Chunks!
 *
 * @author travis
 */
public class CoNLL03Reader implements Iterator<Document>, AutoCloseable {

  private File conllFile;
  private BufferedReader reader;
  private MultiAlphabet alph;
  private Document doc;

  public CoNLL03Reader(File f) throws IOException {
    this.conllFile = f;
    this.reader = FileUtil.getReader(f);
    this.alph = new MultiAlphabet();
    this.doc = readADocument();
  }

  @Override
  public boolean hasNext() {
    return doc != null;
  }

  @Override
  public Document next() {
    Document r = doc;
    try {
      doc = readADocument();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return r;
  }

  public Document readADocument() throws IOException {
    String docstart = reader.readLine();
    if (!"-DOCSTART- -X- O O".equals(docstart))
      return null;

    String emptyLine = reader.readLine();
    assert emptyLine.isEmpty();

    Constituent prevSent = null;
    Document doc = new Document(null, -1, alph);
    for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
      if (line.isEmpty()) {
        // Add sentence boundaries
        Constituent sent = doc.newConstituent();
        sent.setRightSib(Document.NONE);
        if (prevSent == null) {
          // First sentence, set the doc field
          doc.cons_sentences = sent.getIndex();
          sent.setFirstToken(0);
        } else {
          prevSent.setRightSib(sent.getIndex());
          sent.setLeftSib(prevSent.getIndex());
          sent.setFirstToken(prevSent.getLastToken() + 1);
        }
        sent.setLastToken(doc.numTokens() - 1);
        prevSent = sent;
        continue;
      }
      String[] toks = line.split("\\s+");
      int i = 0;
      Token t = doc.newToken();
      t.setWord(alph.word(toks[i++]));
      t.setPosG(alph.pos(toks[i++]));
      int chunk = alph.cfg(toks[i++]);
      t.setNerG(alph.ner(toks[i++]));
      assert i == toks.length;
      // lemma, shape, wordNocase, brown clusters
    }
    return doc;
  }

  @Override
  public void close() throws Exception {
    reader.close();
  }

}
