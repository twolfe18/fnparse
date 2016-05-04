package edu.jhu.hlt.uberts.io;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.tutils.TimeMarker;
import edu.jhu.hlt.uberts.io.ManyDocRelationFileIterator.RelDoc;
import edu.jhu.hlt.uberts.io.RelationFileIterator.RelLine;

/**
 * Shuffle the documents (not facts within a document, their order is preserved)
 * in a .multi.rel file (startdoc command is used to mark the beginning of a document).
 *
 * Unfortunately this is done in memory, so you may need to split up large
 * corpora into many files to be processed separately (and possibly merged).
 *
 * Last time I ran this on some annotated Propbank data, I could fit about
 * 90k documents into around 4G of memory (sadly, this was only 210M in a
 * gzipped text file...), and it took ~400 seconds on my laptop.
 *
 * @author travis
 */
public class ManyDocShuffler {

  public static void main(String[] args) throws IOException {
    ExperimentProperties config = ExperimentProperties.init(args);
    File in = config.getExistingFile("input");
    File out = config.getFile("output");
    Log.info("shuffling " + in.getPath() + " and putting results in " + out.getPath());

    // Read
    TimeMarker tm = new TimeMarker();
    List<RelDoc> l = new ArrayList<>();
    boolean includeProvidence = config.getBoolean("includeProvidence", false);
    boolean dedup = config.getBoolean("dedupFacts", true);
    try (RelationFileIterator rfi = new RelationFileIterator(in, includeProvidence);
        ManyDocRelationFileIterator mdi = new ManyDocRelationFileIterator(rfi, dedup)) {
      while (mdi.hasNext()) {
        RelDoc d = mdi.next();
        l.add(d);

        // Save memory by interning stuff which can be interned.
        assert d.facts.isEmpty();
        d.facts = null;   // this is kind of a huge memory optimization!
        d.internCommandStrings();
        d.internRelationNames();
        d.lookForIntsToIntern();
        d.lookForShortStringsToIntern(8); // this is interning almost everything

        if (tm.enoughTimePassed(15)) {
          Log.info("read " + l.size() + " docs in " + tm.secondsSinceFirstMark() + " sec");
        }
      }
    }
    Log.info("done reading " + l.size() + " documents");

    // Shuffle
    if (config.containsKey("seed")) {
      int seed = config.getInt("seed");
      Log.info("shuffling with seed " + seed + "...");
      Collections.shuffle(l, new Random(seed));
    } else {
      Log.info("shuffling without seed...");
      Collections.shuffle(l);
    }

    // Output
    Log.info("writing results...");
    tm = new TimeMarker();
    try (BufferedWriter w = FileUtil.getWriter(out)) {
      int wrote = 0;
      for (RelDoc r : l) {
        w.write(r.def.toLine());
        w.newLine();
        assert r.facts == null || r.facts.isEmpty();
        for (RelLine rl : r.items) {
          w.write(rl.toLine());
          w.newLine();
        }
        wrote++;
        if (tm.enoughTimePassed(15)) {
          Log.info("wrote " + wrote + " docs in " + tm.secondsSinceFirstMark() + " sec");
        }
      }
    }
    Log.info("done");
  }
}
