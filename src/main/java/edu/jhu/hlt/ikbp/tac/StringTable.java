package edu.jhu.hlt.ikbp.tac;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;

public class StringTable {
  private Map<String, List<String>> m;
  
  public StringTable() {
    this.m = new HashMap<>();
  }

  public void add(File f, int keyCol, int valCol, String sep, boolean dedup) throws IOException {
    Log.info("adding keyCol=" + keyCol + " valCol=" + valCol + " sep=" + sep + " dedup=" + dedup + " from f=" + f.getPath());
    int c = 0, dd = 0;
    try (BufferedReader r = FileUtil.getReader(f)) {
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] ar = line.split(sep);
        List<String> vals = m.get(ar[keyCol]);
        if (vals == null) {
          vals = new ArrayList<>(1);
          m.put(ar[keyCol], vals);
        }
        if (dedup && vals.contains(ar[valCol])) {
          // no-op
          dd++;
        } else {
          vals.add(ar[valCol]);
        }
        c++;
      }
    }
    Log.info("read " + c + " lines, deduped=" + dd + ", total entries: " + m.size());
  }
  
  public String get1(String key) {
    List<String> l = m.get(key);
    if (l == null)
      return null;
    assert l.size() == 1;
    return l.get(0);
  }
  
  public List<String> get(String key) {
    List<String> l = m.get(key);
    if (l == null)
      return Collections.emptyList();
    return l;
  }
}
