package edu.jhu.hlt.fnparse.util;

import java.io.BufferedReader;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Logger;

import edu.jhu.hlt.tutils.FileUtil;

public class Config {
  public static final Logger LOG = Logger.getLogger(Config.class);
  public static boolean VERBOSE = true;

  /**
   * Reads key-value pairs, one on each line, separated by a tab.
   * @param readJavaProperties if true, will add entries from the Java system
   * properties specified as -DfnProperties="k1=v1,k2=v2"
   */
  public static Map<String, String> readConfig(File f, boolean readJavaProperties) {
    LOG.info("[readConfig] from " + f.getPath());
    if (!f.isFile())
      throw new IllegalArgumentException(f.getPath() + " is not a file");
    Map<String, String> configuration = new HashMap<>();
    try (BufferedReader r = FileUtil.getReader(f)) {
      String result = r.readLine(); // see ResultReporter
      LOG.info("[readConfig] result line: \"" + result + "\"");
      for (String line = r.readLine(); line != null; line = r.readLine()) {
        String[] tok = line.split("\t", 2);
        if (VERBOSE)
          LOG.info("[readConfig] adding " + tok[0] + "=" + tok[1]);
        String old = configuration.put(tok[0], tok[1]);
        assert old == null;
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    if (readJavaProperties)
      addJavaPropertiesToConfig(configuration, false);
    return configuration;
  }

  /**
   * Assumes that args is of the form
   * [specialValue] ([key] [value])+
   * and populates the given Map.
   * @param readJavaProperties if true, will add entries from the Java system
   * properties specified as -DfnProperties="k1=v1,k2=v2"
   * @return the first value (specialValue)
   */
  public static String parseIntoMap(String[] args, Map<String, String> config, boolean readJavaProperties) {
    assert config.size() == 0;
    assert args.length % 2 == 1;
    String name = args[0];
    for (int i = 1; i < args.length; i += 2) {
      if (VERBOSE)
        LOG.info("[parseIntoMap] adding " + args[i] + "=" + args[i + 1]);
      String oldValue = config.put(args[i], args[i + 1]);
      if (oldValue != null) {
        throw new RuntimeException(args[i] + " has at least two values: "
            + args[2] + " and " + oldValue);
      }
    }
    if (readJavaProperties)
      addJavaPropertiesToConfig(config, false);
    return name;
  }

  private static void addJavaPropertiesToConfig(Map<String, String> addTo, boolean allowOverwrite) {
    String value = System.getProperty("fnProperties");
    if (value == null) return;
    for (String v : value.split(",")) {
      v = v.trim();
      String[] kv = v.split("=");
      if (kv.length != 2)
        throw new IllegalStateException("kv=" + Arrays.toString(kv));
      if (VERBOSE)
        LOG.info("[addJavaProperties] adding " + kv[0] + "=" + kv[1]);
      String old = addTo.put(kv[0], kv[1]);
      if (!allowOverwrite && old != null) {
        throw new RuntimeException(kv[0] + " has two values: \"" + kv[1]
            + "\" and \"" + old + "\"");
      }
    }
  }
}
