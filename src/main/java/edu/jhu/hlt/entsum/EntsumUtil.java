package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import edu.jhu.hlt.tutils.Counts;
import edu.jhu.hlt.tutils.FileUtil;

public class EntsumUtil {
  
  public static List<File> getAllEntityDirs(File parent) {
    List<File> ps = FileUtil.find(parent, "glob:**/parse.conll");
    List<File> eds = new ArrayList<>();
    for (File p : ps)
      eds.add(p.getParentFile());
    return eds;
  }
  
  public static String getEntityName(File entityDir) throws IOException {
    File f = new File(entityDir, "entity-types-rel0.txt");
    if (!f.isFile())
      return null;
    
    Counts<String> dbp = new Counts<>();
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(f, false)) {
      while (iter.hasNext()) {
        DbpediaTtl x = iter.next();
        dbp.increment(x.subject().getValue());
      }
    }
    
    List<String> mc = dbp.getKeysSortedByCount(true);
    String[] ar = mc.get(0).split("/");
    String name = ar[ar.length-1];
    return name.replaceAll("_", " ");
  }

}
