package edu.jhu.hlt.uberts.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;
import edu.jhu.prim.tuple.Triple;

/**
 * Assuming you have a many-doc file (a bunch of facts prefixed by a "startdoc"
 * line) and a CoNLL formatted input file (10 col TSV,
 * see scripts/uberts/multidoc2conll.py for definitions) where the sentences
 * and the startdocs align 1-to-1, then produce a new many-doc file with the
 * union of those annotations.
 *
 * @author travis
 */
public class ManyDocAndConllZipper extends FactWriter {

  public static final int ID = 0;
  public static final int FORM = 1;
  public static final int LEMMA = 2;
  public static final int CPOSTAG = 3;
  public static final int POSTAG = 4;
  public static final int FEATS = 5;
  public static final int HEAD = 6;
  public static final int DEPREL = 7;
  public static final int PHEAD = 8;
  public static final int PDEPREL = 9;

//  private List<Pair<int[], String>> conllColumn2RelationName;
  private List<Triple<String, String, int[]>> conllColumn2RelationName;
  private Set<String> discardRelations;
  private Map<String, String> renameRelations;

  public ManyDocAndConllZipper() {
    conllColumn2RelationName = new ArrayList<>();
    discardRelations = new HashSet<>();
    renameRelations = new HashMap<>();
  }

  public void convert(String outputFactType, String outputRelation, int... conllColumns) {
//    conllColumn2RelationName.add(new Pair<>(conllColumns, outputRelation));
    conllColumn2RelationName.add(new Triple<>(outputFactType, outputRelation, conllColumns));
  }

  /**
   * Only applies to Iterator<RelDoc> input.
   */
  public void rename(String inputManyDocRelation, String outputManyDocRelation) {
    String old = renameRelations.put(inputManyDocRelation, outputManyDocRelation);
    assert old == null;
  }

  /**
   * Only applies to Iterator<RelDoc> input.
   */
  public void drop(String inputManyDocRelation) {
    boolean a = discardRelations.add(inputManyDocRelation);
    assert a;
  }

  public void zip(File manyDocInput, File conllInput) throws IOException {
    Log.info("manyDocInput=" + manyDocInput.getPath()
        + " conllInput=" + conllInput.getPath());
    boolean dedup = true;
    try (RelationFileIterator rfi = new RelationFileIterator(manyDocInput, false);
        ManyDocRelationFileIterator mdi = new ManyDocRelationFileIterator(rfi, dedup);
        BufferedReader conllReader = FileUtil.getReader(conllInput)) {
      zip(mdi, conllReader);
    }
    Log.info("done");
  }

  /**
   * You specify the output using {@link FactWriter} methods.
   * @throws IOException 
   */
  public void zip(Iterator<RelDoc> manyDocInput, BufferedReader conllInput) throws IOException {
    TimeMarker tm = new TimeMarker();
    int docs = 0;
    while (manyDocInput.hasNext()) {
      RelDoc cur = manyDocInput.next();
      writeRelLine(cur.def);

      // Read from manyDocInput
      assert cur.facts.isEmpty();
      for (RelLine x : cur.items) {
        if (discardRelations.contains(x.tokens[1]))
          continue;
        String n = renameRelations.get(x.tokens[1]);
        if (n != null)
          x.tokens[1] = n;
        writeRelLine(x);
      }

      // Read from conll
      for (String line = conllInput.readLine(); line != null && !line.isEmpty(); line = conllInput.readLine()) {
        String[] toks = line.split("\t");
        if (toks.length != 8 && toks.length != 10)
          throw new RuntimeException("must be 8 or 10 columns: " + line);
        for (Triple<String, String, int[]> t : conllColumn2RelationName) {
          int[] k = t.get3();
          Object[] fact = new Object[k.length + 2];
          fact[0] = t.get1();
          fact[1] = t.get2();
          for (int i = 0; i < k.length; i++)
            fact[2 + i] = toks[k[i]];
          write(fact);
        }
      }

      docs++;
      if (tm.enoughTimePassed(15)) {
        Log.info("wrote " + docs + " documents in "
            + tm.secondsSinceFirstMark() + " seconds");
      }
    }
  }

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);

    File output = config.getFile("output");
    File facts = config.getExistingFile("facts");
    File conll = config.getExistingFile("conll");

    ManyDocAndConllZipper m = new ManyDocAndConllZipper();
    String newPos = config.getString("relation.remap.pos2", "pos2-stanford");
    if (!newPos.isEmpty())
      m.rename("pos2", newPos);
    m.convert("x", config.getString("relation.cpos", "cpos2"), ID, CPOSTAG);
    m.convert("x", config.getString("relation.pos", "pos2"), ID, POSTAG);
    m.convert("x", config.getString("relation.deps", "dsyn3-parsey"), HEAD, ID, DEPREL);

    m.writeToFile(output);
    m.zip(facts, conll);
    m.close();
  }
}
