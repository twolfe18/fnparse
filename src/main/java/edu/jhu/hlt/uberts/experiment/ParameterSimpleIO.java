package edu.jhu.hlt.uberts.experiment;

import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;

public class ParameterSimpleIO {

  /**
   * Represents string directives like:
   *   predicate2+learn+read:/input/path.model+write:/output/path.model
   * You can use +frozen to mean "don't learn".
   * You can use +writeSafe:path if you don't want to overwrite (+write:path will let you overwrite).
   *
   * TODO Add directives for things like hashingTrickDim/hashMethod/alphabet
   */
  public static class Instance2 implements Serializable {
    private static final long serialVersionUID = 3446836180189662756L;

    public String name;
    public File read;
    public File write;
    public boolean learn;
    public String desc;    // full string given at construction

    public String toString() {
      return desc;
    }

    public Instance2(String description) {
      Log.info("[main] " + description);
      String[] toks = description.split("\\+");
      desc = description;
      name = toks[0];
      for (int i = 1; i < toks.length; i++) {
        if (toks[i].equalsIgnoreCase("learn")) {
          learn = true;
        } else if (toks[i].equalsIgnoreCase("frozen")) {
          learn = false;
        } else if (StringUtils.startsWithIgnoreCase(toks[i], "read:")) {
          assert read == null : "reading from more than one location? " + description;
          read = new File(toks[i].substring(5));
          if (!read.exists())
            throw new RuntimeException("+read:path fails if file doesn't already exists: " + read.getPath());
        } else if (StringUtils.startsWithIgnoreCase(toks[i], "write:")) {
          assert write == null : "writing to more than one location? " + description;
          write = new File(toks[i].substring(5));
        } else if (StringUtils.startsWithIgnoreCase(toks[i], "writeSafe:")) {
          assert write == null : "writing to more than one location? " + description;
          write = new File(toks[i].substring(9));
          if (write.isFile())
            throw new RuntimeException("+writeSafe:path fails if file already exists: " + write.getPath());
        } else {
          assert false : "unknown command: " + toks[i];
        }
      }
      if (write != null)
        assert learn : "why write out model when you're not learning? " + description;
    }

//    public static Instance2[] parseMany(String descriptions, String seperatorRegex) {
//      String[] desc = descriptions.split(seperatorRegex);
//      Instance2[] inst = new Instance2[desc.length];
//      for (int i = 0; i < inst.length; i++)
//        inst[i] = new Instance2(desc[i]);
//      return inst;
//    }
  }

  private Map<String, Instance2> name2config = new HashMap<>();

  public Instance2 get(String name) {
    return name2config.get(name);
  }

  /**
   * Default is +learn but with no IO.
   */
  public Instance2 getOrAddDefault(String name) {
    Instance2 i = name2config.get(name);
    if (i == null) {
      i = new Instance2(name + "+learn");
      name2config.put(name, i);
    }
    return i;
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
      Instance2 i = new Instance2(v);
      configure(i);
    }
  }

  /**
   * Tell this class how to save parameters. Eagerly loads if "r" is specified.
   */
  public void configure(Instance2 i) {
    Log.info("[main] " + i);
    Object old = name2config.put(i.name, i);
    if (old != null)
      throw new RuntimeException("double definition: " + i + " and " + old);
  }

  public File read(String name) {
    Instance2 i = name2config.get(name);
    if (i != null && i.read != null)
      return i.read;
    return null;
  }

  /**
   * Returns the file which you should write parameters for the model described
   * by the given string to, or null if you shouldn't save that model.
   */
  public File write(String name) {
    Instance2 i = name2config.get(name);
    if (i != null && i.write != null)
      return i.write;
    return null;
  }
}
