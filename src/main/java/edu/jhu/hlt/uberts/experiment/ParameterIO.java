package edu.jhu.hlt.uberts.auto;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.FileUtil;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.factor.LocalFactor;
import edu.jhu.prim.tuple.Pair;

/**
 * Stores parameters on disk (reading and writing).
 *
 * @author travis
 */
public class ParameterIO {

  public static class Instance {
    String description;       // e.g. argument4
    File serializeLocation;
    boolean read, write;

    public void maybeWrite(Object params) throws IOException {
      if (!write)
        return;
      Log.info("writing " + params + " to " + serializeLocation.getPath());
      FileUtil.serialize(params, serializeLocation);
    }

    public Object maybeRead() throws IOException {
      if (!read)
        return null;
      Log.info("reading from " + serializeLocation.getPath());
      return FileUtil.deserialize(serializeLocation);
    }

    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("(Params");
      if (read)
        sb.append(" +read ");
      if (write)
        sb.append(" +write ");
      if (!(read || write))
        sb.append(' ');
      sb.append(description);
      sb.append(' ');
      sb.append(serializeLocation.getPath());
      sb.append(')');
      return sb.toString();
    }

    /**
     * Parses strings like "argument4:rw:path/to/model.jser.gz"
     * If read is enabled, checks that the file exists.
     */
    public static Instance parse(String description) {
      String[] toks = description.split(":", 3);
      Instance i = new Instance();
      i.description = toks[0];
      i.read = toks[1].contains("r");
      i.write = toks[1].contains("w");
      i.serializeLocation = new File(toks[2]);
      assert i.read || i.write;
      assert toks[1].length() <= 2;
//      if (i.read && !i.serializeLocation.isFile()) {
//        throw new IllegalArgumentException("asked to read a file that doesn't exist: " + i);
//      }
      return i;
    }
  }

  private Map<String, Pair<Rule, LocalFactor>> rel2params;    // what to save
  private Map<String, Instance> rel2config;                 // where to save it

  public ParameterIO() {
    this.rel2config = new HashMap<>();
    this.rel2params = new HashMap<>();
  }

  /**
   * Tell this class how to save parameters. Looks for a key called "parameterIO"
   * which should be a comma-separated list of values like "argument4:rw:path/to/model.jser.gz"
   */
  public void configure(ExperimentProperties config) {
    String key = "parameterIO";
    if (!config.containsKey(key)) {
      Log.info("[main] WARNING: " + key + " not specified, not setting up any parameter loading/saving");
      return;
    }
    String[] values = config.getStrings(key);
    for (String v : values) {
      Instance i = Instance.parse(v);
      configure(i);
    }
  }

  /**
   * Tell this class how to save parameters. Eagerly loads if "r" is specified.
   */
  public void configure(String rhsRelationName, File serializationLocation, boolean read, boolean write) {
    if (!read && !write)
      throw new IllegalArgumentException();
    Instance i = new Instance();
    i.description = rhsRelationName;
    i.serializeLocation = serializationLocation;
    i.read = read;
    i.write = write;
    configure(i);
  }

  /**
   * Tell this class how to save parameters. Eagerly loads if "r" is specified.
   */
  @SuppressWarnings("unchecked")
  public void configure(Instance i) {
    Log.info("[main] " + i);
    Object old = rel2config.put(i.description, i);
    if (old != null)
      throw new RuntimeException("double definition: " + i + " and " + old);
    if (i.read) {
      try {
        Object v = i.maybeRead();
        if (v != null)
          rel2params.put(i.description, (Pair<Rule, LocalFactor>) v);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * If this contains some saved params, return them, else null.
   */
  public LocalFactor get(Rule r) {
    Pair<Rule, LocalFactor> x = rel2params.get(r.rhs.relName);
    if (x == null)
      return null;
    assert r.equals(x.get1());
    return x.get2();
  }

  /**
   * Add some parameters.
   */
  public void put(Rule r, LocalFactor phi) {
    Object old = rel2params.put(r.rhs.relName, new Pair<>(r, phi));
    assert old == null;
  }

  /**
   * Save all parameters who have been configured with a "w" flag.
   */
  public void saveAll() {
    Log.info("[main] saving " + rel2params.size() + " params");
    for (String key : rel2params.keySet()) {
      Instance i = rel2config.get(key);
      if (i == null) {
        Log.info("[main] WARNING: no specification for how to save " + key);
        continue;
      }
      Pair<Rule, LocalFactor> x = rel2params.get(key);
      try {
        i.maybeWrite(x);
      } catch (IOException e) {
        Log.info("[main] error while saving " + i);
        e.printStackTrace();
      }
    }

    for (String key : rel2config.keySet()) {
      if (!rel2params.containsKey(key)) {
        Log.info("[main] WARNING: configuration for params which don't exist: "
            + rel2config.get(key));
      }
    }
    Log.info("[main] done");
  }
}
