package edu.jhu.hlt.entsum;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import edu.jhu.hlt.entsum.DbpediaToken.Type;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.util.MultiMap;

public class EntityTypes implements Serializable {
  private static final long serialVersionUID = 4877789136527559721L;

  private MultiMap<String, String> dbp2type;
  
  public EntityTypes(File entityDir) throws IOException {
    dbp2type = new MultiMap<>();
    add(new File(entityDir, "entity-types-rel0.txt"));
    add(new File(entityDir, "entity-types-rel1.txt"));
  }
  
  /**
   * @param typesTtl e.g. instance_types_transitive_en.ttl.gz or $ENTITY/entity-types-rel0.txt
   */
  public void add(File typesTtl) throws IOException {
    if (!typesTtl.isFile()) {
      Log.info("WARNING: not a file: " + typesTtl.getPath());
      return;
    }
    Log.info("adding " + typesTtl.getPath());
    boolean keepLines = false;
    try (DbpediaTtl.LineIterator iter = new DbpediaTtl.LineIterator(typesTtl, keepLines)) {
      while (iter.hasNext()) {
        DbpediaTtl x = iter.next();
        assert x.subject().type == Type.DBPEDIA_ENTITY;
        dbp2type.add(x.subject().getValue(), x.object().getValue());
      }
    }
  }
  
  public List<String> typesForDbp(String dbp) {
    return dbp2type.get(dbp);
  }

}
