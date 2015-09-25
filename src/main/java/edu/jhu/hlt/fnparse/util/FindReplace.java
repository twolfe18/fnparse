package edu.jhu.hlt.fnparse.util;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.function.Function;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.IntPair;
import edu.jhu.hlt.tutils.Log;

/**
 * Convenience class for doing find/search and replace in a file. You provide
 * the find function and the replace function. Only allows replacement functions
 * which are total functions (i.e. you cannot do any filtering).
 *
 * @author travis
 */
public class FindReplace {

  private Function<String, Iterator<IntPair>> find;

  /**
   * Must not return null -- no convenient way to define behavior if
   * null/filtering is allowed
   */
  private Function<String, String> replace;

  /**
   * @param find should return an interator of [start,end) positions in input which should be replaced.
   * @param replace should replace a string with another string.
   */
  public FindReplace(
      Function<String, Iterator<IntPair>> find,
      Function<String, String> replace) {
    this.find = find;
    this.replace = replace;
  }

  public String findReplace(String input) {
    StringBuilder output = new StringBuilder();
    Iterator<IntPair> pos = find.apply(input);
    int prev = 0;
    while (pos.hasNext()) {
      IntPair p = pos.next();
      assert prev <= p.first && p.first < p.second;
      String replacement = replace.apply(input.substring(p.first, p.second));
      if (replacement == null)
        throw new RuntimeException("replace() should not return null or else weird behavior results");
      output.append(input.substring(prev, p.first));
      output.append(replacement);
      prev = p.second;
    }
    output.append(input.substring(prev));
    return output.toString();
  }

  public void findReplace(File input, File output) throws IOException {
    findReplace(input, output, false);
  }

  public void findReplace(File input, File output, boolean append) throws IOException {
    Log.info(input.getPath() + "  =>  " + output.getPath() +  "  append=" + append);
    try (BufferedWriter w = FileUtil.getWriter(output, append);
        BufferedReader r = FileUtil.getReader(input)) {
      int c = 0;
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        c++;
        assert line.indexOf('\n') < 0;
        String fr = findReplace(line);
        assert fr.indexOf('\n') < 0;
        w.write(fr);
        w.newLine();
      }
      w.flush();
      System.out.println("went through that loop " + c + " times");
    }
  }
}
