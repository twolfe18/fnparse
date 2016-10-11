package edu.jhu.hlt.uberts.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.HypEdge;
import edu.jhu.hlt.uberts.Relation;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * For reading a file which contains many documents (a collection, could
 * correspond to a sentence or any other unit of processing) of relations.
 * The file should look like:
 *   startdoc id42 # maybe a comment
 *   x word2 0 John
 *   x word2 1 loves
 *   x word2 2 Mary
 *   startdoc id42
 *   x word2 0 More
 *   x word2 1 relations
 *   ...
 *
 * This class groups by entries deliminated by "startdoc ..." lines and reads
 * one at a time into memory (List).
 *
 * NOTE: I chose to use a simple text-file format consisting of many pieces
 * (documents) instead of using zip files. I think that freedom to view this
 * as a plain text file, instead of a directory of virtual files in the case of
 * zip, makes this implementation nicer when dealing with external tools (e.g.
 * grep, awk, perl -pe, hand-rolled python scripts, etc). This is not perfect
 * however, you can't use sort, for example, since order matters (not that this
 * is something you could do with zip...).
 *
 * @author travis
 */
public class ManyDocRelationFileIterator implements Iterator<RelDoc>, AutoCloseable {

  public static class RelDoc {

    public final RelLine def; // aka startdoc
    public List<RelLine> items;
    public List<HypEdge.WithProps> facts;

    public RelDoc(RelLine def) {
      if (!def.tokens[0].equals("startdoc"))
        throw new IllegalArgumentException("ManyDocRelation files must start with startdoc, not: " + def.toLine());
      this.def = def;
      this.items = new ArrayList<>();
      this.facts = new ArrayList<>();
    }

    public String getId() {
      return def.tokens[1];
    }

    public List<RelLine> findLinesOfRelation(String relationName) {
      List<RelLine> r = new ArrayList<>();
      for (RelLine l : items)
        if (relationName.equals(l.tokens[0]))
          r.add(l);
      return r;
    }

    public List<HypEdge.WithProps> findFactsOfRelation(String relationName) {
      List<HypEdge.WithProps> r = new ArrayList<>();
      for (HypEdge.WithProps l : facts)
        if (relationName.equals(l.getRelation().getName()))
          r.add(l);
      return r;
    }

    public void internCommandStrings() {
      def.tokens[0] = def.tokens[0].intern();
      for (RelLine rl : items) {
        // e.g. "x", "y", or "schema"
        rl.tokens[0] = rl.tokens[0].intern();
      }
    }

    public void internRelationNames() {
      for (RelLine rl : items) {
        // e.g. "word2", "csyn6-stanford"
        rl.tokens[1] = rl.tokens[1].intern();
      }
    }

    public void lookForIntsToIntern() {
      lookForIntsToIntern(100);
    }
    public void lookForIntsToIntern(int maxIntToIntern) {
      def.lookForIntsToIntern(maxIntToIntern);
      for (RelLine rl : items)
        rl.lookForIntsToIntern(maxIntToIntern);
    }

    public void lookForShortStringsToIntern() {
      lookForIntsToIntern(4);   // This will get POS tags and short words
    }
    public void lookForShortStringsToIntern(int maxLengthToIntern) {
      def.lookForShortStringsToIntern(maxLengthToIntern);
      for (RelLine rl : items)
        rl.lookForShortStringsToIntern(maxLengthToIntern);
    }

    public List<HypEdge.WithProps> match2FromFacts(Relation r) {
      List<HypEdge.WithProps> l = new ArrayList<>();
      for (HypEdge.WithProps e : facts)
        if (e.getRelation() == r)
          l.add(e);
      return l;
    }

    public Counts<String> countFacts() {
      Counts<String> c = new Counts<>();
      for (HypEdge e : facts)
        c.increment(e.getRelation().getName());
      return c;
    }
  }

//  // prepend "startdoc ..." line?
//  public static ManyDocRelationFileIterator fromSingle(String docId, File f) throws IOException  {
//    try (RelationFileIterator itr = new RelationFileIterator(f)) {
//    }
//  }

  private Iterator<RelLine> itr;
  private RelDoc cur;
  private RelLine nextDesc;
  private Set<RelLine> uniq;

  /**
   * @param dedup if true, will remove duplicate {@link RelLine} entries within
   * each document.
   */
  public ManyDocRelationFileIterator(Iterator<RelLine> itr, boolean dedup) {
    Log.info("dedup=" + dedup + " itr=" + itr);
    this.itr = itr;
    if (dedup)
      uniq = new HashSet<>();

    // Load up the current item
    this.nextDesc = itr.next();
    next();
  }

  public Iterator<RelLine> getWrapped() {
    return itr;
  }

  @Override
  public boolean hasNext() {
    return cur != null;
  }

  @Override
  public RelDoc next() {
    RelDoc n = cur;
    if (nextDesc == null) {
      cur = null;
      return n;
    }
    cur = new RelDoc(nextDesc);
    nextDesc = null;
    if (uniq != null)
      uniq.clear();
    while (itr.hasNext() && nextDesc == null) {
      nextDesc = itr.next();
      if (!nextDesc.tokens[0].equals("startdoc")) {
        if (uniq == null || uniq.add(nextDesc))
          cur.items.add(nextDesc);
        nextDesc = null;
      }
    }
    assert n == null || n.def != null;
    return n;
  }

  @Override
  public void close() throws IOException {
    if (itr instanceof AutoCloseable) {
      Log.info("closing " + itr);
      try {
        ((AutoCloseable) itr).close();
      } catch (Exception e) {
        if (e instanceof IOException)
          throw (IOException) e;
        throw new RuntimeException("not going to refactor all my "
            + "code to handle Exception instead of IOException...", e);
      }
    }
  }
}
