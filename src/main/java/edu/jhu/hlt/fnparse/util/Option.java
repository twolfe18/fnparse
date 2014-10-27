package edu.jhu.hlt.fnparse.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TODO work on this
 * 
 * Dont do this now, because the global nature of these options does not jive
 * well with seriali... BS?
 * 
 * @author travis
 */
public class Option {

  /**
   * Keeps track of all options
   */
  public static class Manager {
    private static List<Option> allOptions = new ArrayList<>();
    // TODO figure out what the "backing store" is for this
    // it could be mainArgs, System.getProperties, or something else
    private static String[] mainArgs;
    public static void addOption(Option opt) {
      allOptions.add(opt);
    }
    public static void setMainArgs(String[] args) {
      mainArgs = args;
    }
  }

  private String name;
  private String[] possibleValues;
  private String defaultValue;
  private boolean required;

  public Option(String name, String defaultValue, String... possibleValues) {
    this.name = name;
    this.possibleValues = possibleValues;
    this.defaultValue = defaultValue;
    this.required = false;
    Manager.addOption(this);
  }

  public Option(String name, String... possibleValues) {
    this(name, false, possibleValues);
  }

  public Option(String name, boolean required, String... possibleValues) {
    this.name = name;
    this.possibleValues = possibleValues;
    this.defaultValue = null;
    this.required = required;
    Manager.addOption(this);
  }

  public String getName() {
    return name;
  }

  public boolean isPossibleValue(String value) {
    for (int i = 0; i < possibleValues.length; i++)
      if (possibleValues[i].equals(value))
        return true;
    return false;
  }

  public List<String> getPossibleValues() {
    return Arrays.asList(possibleValues);
  }

  // TODO fill out all the getters (of various types) which ask Manager for
  // the value
}
