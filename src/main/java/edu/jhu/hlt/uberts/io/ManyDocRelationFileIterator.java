package edu.jhu.hlt.uberts.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import edu.jhu.hlt.uberts.HypEdge;
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
    public final List<RelLine> items;
    public final List<HypEdge.WithProps> facts;
    public RelDoc(RelLine def) {
      this.def = def;
      this.items = new ArrayList<>();
      this.facts = new ArrayList<>();
    }
    public String getId() {
      return def.tokens[1];
    }
  }

//  // prepend "startdoc ..." line?
//  public static ManyDocRelationFileIterator fromSingle(String docId, File f) throws IOException  {
//    try (RelationFileIterator itr = new RelationFileIterator(f)) {
//    }
//  }

  private RelationFileIterator itr;
  private RelDoc cur;
  private RelLine nextDesc;
  private Set<RelLine> uniq;

  /**
   * @param dedup if true, will remove duplicate {@link RelLine} entries within
   * each document.
   */
  public ManyDocRelationFileIterator(RelationFileIterator itr, boolean dedup) {
    this.itr = itr;
    if (dedup)
      uniq = new HashSet<>();

    // Load up the current item
    this.nextDesc = itr.next();
    next();
  }

  @Override
  public boolean hasNext() {
    return cur != null;
  }

  @Override
  public RelDoc next() {
    RelDoc n = cur;
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
    if (nextDesc == null)
      cur = null;
    // first call to next is in the constructor, n will be null
    // penultimate call sets cur (and thus on the ultimate call n) to null
    assert n == null || n.def != null;
    return n;
  }

  @Override
  public void close() throws IOException {
    itr.close();
  }
}
