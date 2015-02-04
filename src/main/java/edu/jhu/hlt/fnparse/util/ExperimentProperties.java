package edu.jhu.hlt.fnparse.util;

import java.io.File;

/**
 * Methods with defaults will return the default if the key is not in this map,
 * and also add the (key, defaultValue) pair to this map.
 *
 * @author travis
 */
public class ExperimentProperties extends java.util.Properties {
  private static final long serialVersionUID = 1L;

  public void putAll(String[] mainArgs) {
    putAll(mainArgs, false);
  }

  public void putAll(String[] mainArgs, boolean allowOverwrites) {
    if (mainArgs.length % 2 != 0)
      throw new IllegalArgumentException();
    for (int i = 0; i < mainArgs.length; i += 2) {
      Object old = put(mainArgs[i], mainArgs[i+1]);
      if (!allowOverwrites && old != null) {
        throw new RuntimeException(mainArgs[i] + " has two values: "
            + mainArgs[i+1] + " and " + old);
      }
    }
  }

  public int getInt(String key, int defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      put(key, String.valueOf(defaultValue));
      return defaultValue;
    }
    return Integer.parseInt(value);
  }

  public int getInt(String key) {
    String value = getProperty(key);
    return Integer.parseInt(value);
  }

  public double getDouble(String key, double defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      put(key, String.valueOf(defaultValue));
      return defaultValue;
    }
    return Double.parseDouble(value);
  }

  public double getDouble(String key) {
    String value = getProperty(key);
    return Double.parseDouble(value);
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      put(key, String.valueOf(defaultValue));
      return defaultValue;
    }
    return Boolean.parseBoolean(value);
  }

  public boolean getBoolean(String key) {
    String value = getProperty(key);
    return Boolean.parseBoolean(value);
  }

  public File getOrMakeDir(String key, File defaultValue) {
    File f = getFile(key, defaultValue);
    if (!f.isDirectory())
      f.mkdirs();
    return f;
  }

  public File getOrMakeDir(String key) {
    File f = getFile(key);
    if (!f.isDirectory())
      f.mkdirs();
    return f;
  }

  public File getExistingDir(String key, File defaultValue) {
    File f = getFile(key, defaultValue);
    if (!f.isDirectory())
      throw new RuntimeException();
    return f;
  }

  public File getExistingDir(String key) {
    File f = getFile(key);
    if (!f.isDirectory())
      throw new RuntimeException();
    return f;
  }

  public File getExistingFile(String key, File defaultValue) {
    File f = getFile(key, defaultValue);
    if (!f.isFile())
      throw new RuntimeException();
    return f;
  }

  public File getExistingFile(String key) {
    File f = getFile(key);
    if (!f.isFile())
      throw new RuntimeException();
    return f;
  }

  public File getFile(String key, File defaultValue) {
    String value = getProperty(key);
    File f;
    if (value == null) {
      put(key, defaultValue.getPath());
      f = defaultValue;
    } else {
      f = new File(value);
    }
    return f;
  }

  public File getFile(String key) {
    String value = getProperty(key);
    if (value == null)
      throw new RuntimeException();
    File f = new File(value);
    return f;
  }

  public String getString(String key, String defaultValue) {
    String value = getProperty(key);
    if (value == null) {
      put(key, defaultValue);
      return defaultValue;
    }
    return value;
  }

  public String getString(String key) {
    String value = getProperty(key);
    if (value == null)
      throw new RuntimeException();
    return value;
  }
}
