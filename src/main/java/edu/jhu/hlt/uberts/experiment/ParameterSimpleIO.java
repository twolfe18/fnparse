package edu.jhu.hlt.uberts.experiment;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import edu.jhu.hlt.tutils.ExperimentProperties;
import edu.jhu.hlt.tutils.Log;
import edu.jhu.hlt.uberts.experiment.ParameterIO.Instance;

public class ParameterSimpleIO {

  private Map<String, Instance> rel2config = new HashMap<>();

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
  public void configure(String description, File serializationLocation, boolean read, boolean write) {
    if (!read && !write)
      throw new IllegalArgumentException();
    Instance i = new Instance();
    i.description = description;
    i.serializeLocation = serializationLocation;
    i.read = read;
    i.write = write;
    configure(i);
  }

  /**
   * Tell this class how to save parameters. Eagerly loads if "r" is specified.
   */
  public void configure(Instance i) {
    Log.info("[main] " + i);
    Object old = rel2config.put(i.description, i);
    if (old != null)
      throw new RuntimeException("double definition: " + i + " and " + old);
  }

  public File read(String description) {
    Instance i = rel2config.get(description);
    if (i != null && i.read)
      return i.serializeLocation;
    return null;
  }

  /**
   * Returns the file which you should write parameters for the model described
   * by the given string to, or null if you shouldn't save that model.
   */
  public File write(String description) {
    Instance i = rel2config.get(description);
    if (i != null && i.write)
      return i.serializeLocation;
    return null;
  }
}
